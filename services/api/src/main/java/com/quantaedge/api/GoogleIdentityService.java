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
 * No profile field supplied by the browser is trusted; identity claims come only
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

  @Transactional
  public GoogleSignIn authenticate(String credential) {
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

    // This product entry point deliberately accepts Gmail accounts only. A Google
    // account with a third-party email must not be presented as Gmail-verified.
    if (subject.isBlank() || !email.matches("^[^@\\s]+@gmail\\.com$") || !emailVerified) {
      throw new SecurityException("Use a Google account with a verified @gmail.com address to continue.");
    }
    if (name.isBlank()) name = email.substring(0, email.indexOf('@'));

    List<Map<String, Object>> subjectRows = jdbc.queryForList("""
      select id,role,display_name,google_subject
      from user_account where google_subject=?
      for update
      """, subject);

    long userId;
    boolean created = false;
    if (!subjectRows.isEmpty()) {
      Map<String, Object> row = subjectRows.getFirst();
      userId = ((Number) row.get("id")).longValue();
      String role = String.valueOf(row.get("role"));
      if ("ADMIN".equals(role)) {
        throw new SecurityException("This identity cannot sign in to the learner application.");
      }
      try {
        jdbc.update("""
          update user_account
          set email=?, email_verified_at=now(), google_picture_url=?,
              google_given_name=?, google_family_name=?, google_locale=?,
              display_name=case when display_name is null or btrim(display_name)='' then ? else display_name end,
              updated_at=now()
          where id=?
          """, email, picture, nullable(givenName), nullable(familyName), nullable(locale), name, userId);
      } catch (DuplicateKeyException ex) {
        throw new IllegalStateException("This Gmail address is already linked to another account. Sign in with that account and contact support to resolve the duplicate identity.", ex);
      }
    } else {
      List<Map<String, Object>> emailRows = jdbc.queryForList("""
        select id,role,display_name,google_subject
        from user_account where lower(email)=?
        for update
        """, email);
      if (!emailRows.isEmpty()) {
        Map<String, Object> row = emailRows.getFirst();
        if (row.get("google_subject") != null
            && !subject.equals(String.valueOf(row.get("google_subject")))) {
          throw new SecurityException("This Gmail address is already linked to a different Google identity.");
        }
        userId = ((Number) row.get("id")).longValue();
        String role = String.valueOf(row.get("role"));
        if ("ADMIN".equals(role)) {
          throw new SecurityException("This identity cannot sign in to the learner application.");
        }
        try {
          jdbc.update("""
            update user_account
            set google_subject=?, email_verified_at=now(), google_picture_url=?,
                google_given_name=?, google_family_name=?, google_locale=?,
                display_name=case when display_name is null or btrim(display_name)='' then ? else display_name end,
                updated_at=now()
            where id=?
            """, subject, picture, nullable(givenName), nullable(familyName), nullable(locale), name, userId);
        } catch (DuplicateKeyException ex) {
          throw new IllegalStateException("This Gmail address is already linked to another account.", ex);
        }
      } else {
        try {
          userId = jdbc.queryForObject("""
            insert into user_account(
              public_id,mobile_e164,display_name,role,email,email_verified_at,google_subject,
              google_picture_url,google_given_name,google_family_name,google_locale
            ) values (?,null,?,'LEARNER',?,now(),?,?,?,?,?)
            returning id
            """, Long.class, UUID.randomUUID(), name, email, subject, picture,
                nullable(givenName), nullable(familyName), nullable(locale));
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
          created, email, displayName, picture);
    }
    if (!"LEARNER".equals(role)) {
      throw new SecurityException("This account is not enabled for learner sign-in.");
    }

    Long studentId = jdbc.queryForObject("""
      select id from student
      where user_account_id=? and active=true and environment='PRODUCTION'
      for update
      """, Long.class, userId);
    if (studentId == null) {
      studentId = createLearnerProfile(userId, displayName);
    }
    return new GoogleSignIn(
        new AuthContext(null, studentId, null, "STUDENT", displayName, "STUDENT"),
        created, email, displayName, picture);
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

  public record GoogleSignIn(
      AuthContext context, boolean accountCreated, String email, String displayName, String pictureUrl) {}
}
