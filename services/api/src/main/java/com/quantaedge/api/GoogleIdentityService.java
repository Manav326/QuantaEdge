package com.quantaedge.api;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies real Google Identity Services ID tokens on the server.
 * No profile field supplied by a client is trusted; identity claims come only
 * from a signature-verified, audience-checked Google ID token.
 */
@Service
public class GoogleIdentityService {
  private final JdbcTemplate jdbc;
  private final String clientId;
  private final GoogleIdTokenVerifier verifier;

  public GoogleIdentityService(
      JdbcTemplate jdbc,
      @Value("${app.auth.google-client-id:}") String clientId) {
    this.jdbc = jdbc;
    this.clientId = clientId == null ? "" : clientId.trim();
    this.verifier = this.clientId.isBlank() ? null
        : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance())
            .setAudience(List.of(this.clientId))
            .build();
  }

  /** A public OAuth client ID, safe for browser/mobile clients to use as the token audience. */
  public String publicClientId() {
    return clientId;
  }

  @Transactional
  public GoogleSignIn authenticate(String credential) {
    GoogleProfile profile = verifyCredential(credential);
    List<Map<String, Object>> subjectRows = jdbc.queryForList("""
      select id,role,display_name,google_subject,active
      from user_account where google_subject=?
      for update
      """, profile.subject());

    long userId;
    boolean created = false;
    if (!subjectRows.isEmpty()) {
      Map<String, Object> row = subjectRows.getFirst();
      if (!Boolean.TRUE.equals(row.get("active"))) {
        throw new SecurityException("This QuantaEdge account is inactive. Contact support.");
      }
      userId = ((Number) row.get("id")).longValue();
      String role = String.valueOf(row.get("role"));
      if ("ADMIN".equals(role)) {
        throw new SecurityException("This identity cannot sign in to the learner application.");
      }
      updateGoogleProfile(userId, profile);
    } else {
      List<Map<String, Object>> emailRows = jdbc.queryForList("""
        select id,role,display_name,google_subject,active
        from user_account where lower(email)=?
        for update
        """, profile.email());
      if (!emailRows.isEmpty()) {
        Map<String, Object> row = emailRows.getFirst();
        if (!Boolean.TRUE.equals(row.get("active"))) {
          throw new SecurityException("This QuantaEdge account is inactive. Contact support.");
        }
        if (row.get("google_subject") != null
            && !profile.subject().equals(String.valueOf(row.get("google_subject")))) {
          throw new SecurityException("This Gmail address is already linked to a different Google identity.");
        }
        userId = ((Number) row.get("id")).longValue();
        String role = String.valueOf(row.get("role"));
        if ("ADMIN".equals(role)) {
          throw new SecurityException("This identity cannot sign in to the learner application.");
        }
        try {
          updateGoogleProfile(userId, profile);
        } catch (DuplicateKeyException ex) {
          throw new IllegalStateException("This Gmail address is already linked to another account.", ex);
        }
      } else {
        // Do not merge an editable, historical contact-email field into a sign-in
        // identity. Ask the user to sign into that account and explicitly link Google.
        Boolean contactConflict = jdbc.queryForObject("""
          select exists(select 1 from user_account
            where lower(contact_email)=? and active=true)
          """, Boolean.class, profile.email());
        if (Boolean.TRUE.equals(contactConflict)) {
          throw new IllegalStateException(
              "This Gmail is listed as a contact address on an existing account. Sign in to that account first, then connect Gmail from Account identity settings.");
        }
        try {
          userId = jdbc.queryForObject("""
            insert into user_account(
              public_id,mobile_e164,display_name,role,email,email_verified_at,google_subject,
              google_picture_url,google_given_name,google_family_name,google_locale
            ) values (?,null,?,'LEARNER',?,now(),?,?,?,?,?)
            returning id
            """, Long.class, UUID.randomUUID(), profile.name(), profile.email(), profile.subject(),
                profile.pictureUrl(), nullable(profile.givenName()), nullable(profile.familyName()),
                nullable(profile.locale()));
          created = true;
        } catch (DuplicateKeyException ex) {
          throw new IllegalStateException("This Gmail account was just linked elsewhere. Please retry sign-in.", ex);
        }
      }
    }

    Map<String, Object> account = jdbc.queryForMap("""
      select id,role,display_name,email,google_subject
      from user_account where id=? and active=true
      for update
      """, userId);
    String role = String.valueOf(account.get("role"));
    String displayName = safeText(account.get("display_name"), 120);
    if ("PARENT".equals(role)) {
      return new GoogleSignIn(
          new AuthContext(userId, null, null, "PARENT", displayName, "PARENT"),
          created, profile.email(), displayName, profile.pictureUrl());
    }
    if (!"LEARNER".equals(role)) {
      throw new SecurityException("This account is not enabled for learner sign-in.");
    }

    List<Map<String, Object>> learnerProfiles = jdbc.queryForList("""
      select id from student
      where user_account_id=? and active=true and environment='PRODUCTION'
      for update
      """, userId);
    long studentId = learnerProfiles.isEmpty()
        ? createLearnerProfile(userId, displayName)
        : ((Number) learnerProfiles.getFirst().get("id")).longValue();
    return new GoogleSignIn(
        new AuthContext(null, studentId, null, "STUDENT", displayName, "STUDENT"),
        created, profile.email(), displayName, profile.pictureUrl());
  }

  /** Link a verified Gmail identity to the currently authenticated regular user or parent. */
  @Transactional
  public Map<String,Object> linkExisting(AuthContext context, String credential) {
    if (context == null || !context.isAuthenticated()) {
      throw new SecurityException("Sign in before connecting Gmail to your account.");
    }
    Long userId = context.userId();
    if (userId == null && context.studentId() != null) {
      List<Map<String,Object>> links = jdbc.queryForList("""
        select user_account_id from student
        where id=? and active=true and environment='PRODUCTION'
        """, context.studentId());
      if (!links.isEmpty() && links.getFirst().get("user_account_id") != null) {
        userId = ((Number) links.getFirst().get("user_account_id")).longValue();
      }
    }
    if (userId == null) {
      throw new SecurityException("This learner's login is managed by a parent. Gmail cannot be connected to a child-managed credential.");
    }

    GoogleProfile profile = verifyCredential(credential);
    List<Map<String,Object>> accounts = jdbc.queryForList("""
      select id,role,google_subject,email from user_account
      where id=? and active=true and role in ('PARENT','LEARNER')
      for update
      """, userId);
    if (accounts.isEmpty()) throw new SecurityException("An active regular-user or parent account is required.");
    Map<String,Object> account = accounts.getFirst();
    Object previousSubject = account.get("google_subject");
    if (previousSubject != null && !profile.subject().equals(String.valueOf(previousSubject))) {
      throw new IllegalStateException("A different Google identity is already connected. Contact support to change it safely.");
    }

    List<Map<String,Object>> conflicting = jdbc.queryForList("""
      select id from user_account
      where id<>? and (google_subject=? or lower(email)=?)
      for update
      """, userId, profile.subject(), profile.email());
    if (!conflicting.isEmpty()) {
      throw new IllegalStateException("This Gmail identity is already connected to another account.");
    }

    updateGoogleProfile(userId, profile);
    jdbc.update("""
      insert into account_identity_audit(user_id,actor_student_id,event_type,details)
      values (?,?, 'GMAIL_LINKED', jsonb_build_object(
        'gmailVerified',true,'googleSubjectVerified',true,'source','SELF_SERVICE'))
      """, userId, context.studentId());
    return Map.of("linked", true, "gmailVerified", true, "email", profile.email(),
        "profileImageUrl", profile.pictureUrl() == null ? "" : profile.pictureUrl(),
        "displayName", profile.name(), "verifiedMethods", List.of("GMAIL"));
  }

  /**
   * Verify a newly-issued Google credential against the exact Google identity already linked
   * to this account. Unlike linkExisting, this method never changes the profile or identity.
   */
  @Transactional
  public Map<String,Object> verifyAccountDeletionIdentity(long userId,String credential) {
    GoogleProfile supplied=verifyCredential(credential);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select google_subject,email from user_account
      where id=? and active=true and role in ('PARENT','LEARNER')
      for update
      """,userId);
    if(rows.isEmpty()) throw new SecurityException("The account is inactive or unavailable.");
    Map<String,Object> account=rows.getFirst();
    Object linkedSubject=account.get("google_subject");
    Object linkedEmail=account.get("email");
    if(linkedSubject==null || linkedEmail==null
        || !supplied.subject().equals(String.valueOf(linkedSubject))
        || !supplied.email().equalsIgnoreCase(String.valueOf(linkedEmail))) {
      throw new SecurityException(
          "Re-authenticate with the verified Google account already linked to this QuantaEdge account.");
    }
    UUID verificationId=UUID.randomUUID();
    jdbc.update("""
      insert into account_deletion_verification(
        verification_id,user_account_id,method,verified_at,expires_at)
      values (?,?,'GOOGLE_REAUTH',now(),now()+interval '10 minutes')
      """,verificationId,userId);
    return Map.of(
        "verified",true,
        "verificationId",verificationId.toString(),
        "gmailVerified",true,
        "verifiedMethods",List.of("GMAIL"),
        "expiresInSeconds",600);
  }

  private void updateGoogleProfile(long userId, GoogleProfile profile) {
    try {
      int changed = jdbc.update("""
        update user_account
        set email=?,email_verified_at=now(),google_subject=?,google_picture_url=?,
            google_given_name=?,google_family_name=?,google_locale=?,
            display_name=case when display_name is null or btrim(display_name)='' then ? else display_name end,
            updated_at=now()
        where id=? and active=true
        """, profile.email(), profile.subject(), profile.pictureUrl(),
            nullable(profile.givenName()), nullable(profile.familyName()), nullable(profile.locale()),
            profile.name(), userId);
      if (changed != 1) throw new SecurityException("This QuantaEdge account is inactive. Contact support.");
    } catch (DuplicateKeyException ex) {
      throw new IllegalStateException("This Gmail address is already linked to another account.", ex);
    }
  }

  private GoogleProfile verifyCredential(String credential) {
    if (verifier == null || clientId.isBlank()) {
      throw new IllegalStateException("Google sign-in is not configured. Set APP_AUTH_GOOGLE_CLIENT_ID and the matching web client ID.");
    }
    if (credential == null || credential.isBlank() || credential.length() > 12000) {
      throw new IllegalArgumentException("Google did not return a valid sign-in credential. Please try again.");
    }

    final GoogleIdToken token;
    try {
      token = verifier.verify(credential);
    } catch (GeneralSecurityException | IOException ex) {
      throw new IllegalArgumentException("Google sign-in could not be verified. Please try again.", ex);
    }
    if (token == null) {
      throw new IllegalArgumentException("Google sign-in token is invalid, expired, or was issued to another application.");
    }

    GoogleIdToken.Payload payload = token.getPayload();
    String subject = safeText(payload.getSubject(), 255);
    String email = safeText(payload.getEmail(), 320).toLowerCase(Locale.ROOT);
    boolean emailVerified = Boolean.TRUE.equals(payload.getEmailVerified());
    String name = safeText(payload.get("name"), 120);
    String givenName = safeText(payload.get("given_name"), 120);
    String familyName = safeText(payload.get("family_name"), 120);
    String locale = safeText(payload.get("locale"), 24);
    String picture = safePicture(payload.get("picture"));

    // Gmail verification is asserted only from a signature-validated Google token.
    if (subject.isBlank() || !email.matches("^[^@\\s]+@gmail\\.com$") || !emailVerified) {
      throw new SecurityException("Use a Google account with a verified @gmail.com address to continue.");
    }
    if (name.isBlank()) name = email.substring(0, email.indexOf('@'));
    return new GoogleProfile(subject,email,name,givenName,familyName,locale,picture);
  }

  private long createLearnerProfile(long userId, String name) {
    Long studentId = jdbc.queryForObject("""
      insert into student(
        public_id,display_name,class_code,board,language,environment,active,user_account_id
      ) values (?,?, '6','Bihar Board','hi','PRODUCTION',true,?)
      returning id
      """, Long.class, UUID.randomUUID(), name, userId);
    return studentId;
  }

  private static String safeText(Object value, int maxLength) {
    if (value == null) return "";
    String text = String.valueOf(value).trim();
    return text.length() <= maxLength ? text : text.substring(0, maxLength);
  }

  private static String nullable(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String safePicture(Object value) {
    String url = safeText(value, 2048);
    if (url.isBlank()) return null;
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
          || !(host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com"))) {
        return null;
      }
      return url;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private record GoogleProfile(
      String subject,String email,String name,String givenName,String familyName,String locale,String pictureUrl) {}

  public record GoogleSignIn(
      AuthContext context, boolean accountCreated, String email, String displayName, String pictureUrl) {}
}
