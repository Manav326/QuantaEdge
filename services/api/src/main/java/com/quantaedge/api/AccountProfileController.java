package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service account fields and credentials. Verified identities are read-only:
 * Gmail is verified only from a validated Google ID token and mobile only after OTP.
 */
@RestController
@RequestMapping("/api/v1/account/me")
public class AccountProfileController {
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");
  private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public AccountProfileController(JdbcTemplate jdbc, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  @GetMapping("/profile")
  public Map<String,Object> profile(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireAuth(context);
    Long accountId = accountIdFor(context);
    if (accountId == null) return childReadOnlyProfile(context.studentId());

    List<Map<String,Object>> rows = jdbc.queryForList("""
      select u.id,u.role,u.display_name,u.email,u.email_verified_at,u.google_subject,
        u.google_picture_url,u.google_given_name,u.google_family_name,u.google_locale,
        u.mobile_e164,u.mobile_verified_at,u.username,u.username_changed_at,
        u.preferred_locale,u.created_at,u.updated_at,
        st.id as linked_student_id,st.class_code,st.board,st.language
      from user_account u
      left join student st on st.user_account_id=u.id and st.active=true and st.environment='PRODUCTION'
      where u.id=? and u.active=true
      order by st.id
      limit 1
      """, accountId);
    if (rows.isEmpty()) throw new SecurityException("The account is inactive or unavailable.");
    Map<String,Object> row = rows.getFirst();
    boolean gmailVerified = row.get("email_verified_at") != null
        && row.get("google_subject") != null && row.get("email") != null;
    boolean mobileVerified = row.get("mobile_verified_at") != null && row.get("mobile_e164") != null;
    List<String> verifiedMethods = new ArrayList<>();
    if (gmailVerified) verifiedMethods.add("GMAIL");
    if (mobileVerified) verifiedMethods.add("MOBILE");
    Object changedAt = row.get("username_changed_at");
    Instant nextChange = changedAt == null ? null
        : toInstant(changedAt).plus(15, ChronoUnit.DAYS);
    boolean usernameCanChange = row.get("username") == null || nextChange == null
        || !nextChange.isAfter(Instant.now());

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("userId", row.get("id"));
    result.put("displayName", row.get("display_name"));
    result.put("accountType", "PARENT".equals(String.valueOf(row.get("role"))) ? "PARENT" : "USER");
    result.put("role", row.get("role"));
    result.put("email", row.get("email"));
    result.put("gmailVerified", gmailVerified);
    result.put("mobile", row.get("mobile_e164"));
    result.put("mobileVerified", mobileVerified);
    result.put("verifiedMethods", verifiedMethods);
    result.put("profileImageUrl", row.get("google_picture_url"));
    result.put("googleGivenName", row.get("google_given_name"));
    result.put("googleFamilyName", row.get("google_family_name"));
    result.put("googleLocale", row.get("google_locale"));
    result.put("username", row.get("username"));
    result.put("usernameChangedAt", row.get("username_changed_at"));
    result.put("usernameCanChange", usernameCanChange);
    result.put("usernameCanChangeAt", nextChange == null ? null : nextChange.toString());
    result.put("preferredLocale", row.get("preferred_locale"));
    result.put("createdAt", row.get("created_at"));
    result.put("updatedAt", row.get("updated_at"));
    result.put("linkedStudentId", row.get("linked_student_id"));
    result.put("classCode", row.get("class_code"));
    result.put("board", row.get("board"));
    result.put("learningLanguage", row.get("language"));
    result.put("credentialsManagedByParent", false);
    result.put("canRequestParentAccount", "LEARNER".equals(String.valueOf(row.get("role"))));
    return result;
  }

  @PutMapping("/profile")
  @Transactional
  public Map<String,Object> updateProfile(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireAuth(context);
    long userId = requireAccountId(context);
    String name = String.valueOf(body.getOrDefault("displayName", "")).trim();
    String locale = String.valueOf(body.getOrDefault("preferredLocale", "hinglish")).trim();
    if (name.length() < 2 || name.length() > 120) {
      throw new IllegalArgumentException("Full name must be between 2 and 120 characters.");
    }
    if (!locale.matches("^[A-Za-z][A-Za-z0-9_-]{1,31}$")) {
      throw new IllegalArgumentException("Select a supported interface language.");
    }
    int updated = jdbc.update("""
      update user_account set display_name=?,preferred_locale=?,updated_at=now()
      where id=? and active=true
      """, name, locale, userId);
    if (updated != 1) throw new SecurityException("The account is inactive or unavailable.");
    jdbc.update("""
      update student set display_name=?,profile_updated_at=now()
      where user_account_id=? and active=true and environment='PRODUCTION'
      """, name, userId);
    audit(userId, context.studentId(), "PROFILE_UPDATED",
        Map.of("fields", List.of("displayName", "preferredLocale")));
    return Map.of("saved", true);
  }

  @PutMapping("/username")
  @Transactional
  public Map<String,Object> updateUsername(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireAuth(context);
    long userId = requireAccountId(context);
    String username = String.valueOf(body.getOrDefault("username", ""))
        .trim().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(username).matches()) {
      throw new IllegalArgumentException("Username must be 3–32 characters and use letters, numbers, dots, underscores or hyphens.");
    }
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select username,username_changed_at from user_account
      where id=? and active=true for update
      """, userId);
    if (rows.isEmpty()) throw new SecurityException("The account is inactive or unavailable.");
    Map<String,Object> row = rows.getFirst();
    String current = row.get("username") == null ? "" : String.valueOf(row.get("username"));
    if (current.equals(username)) return Map.of("saved", true, "username", username, "unchanged", true);
    Object changedAt = row.get("username_changed_at");
    if (!current.isBlank() && changedAt != null
        && toInstant(changedAt).plus(15, ChronoUnit.DAYS).isAfter(Instant.now())) {
      Instant allowedAt = toInstant(changedAt).plus(15, ChronoUnit.DAYS);
      throw new IllegalStateException("Username can next be changed after " + allowedAt + ".");
    }
    try {
      jdbc.update("""
        update user_account set username=?,username_changed_at=now(),updated_at=now()
        where id=? and active=true
        """, username, userId);
    } catch (DuplicateKeyException ex) {
      throw new IllegalArgumentException("That username is already taken. Choose another one.", ex);
    }
    audit(userId, context.studentId(), current.isBlank() ? "USERNAME_SET" : "USERNAME_CHANGED",
        Map.of("username", username));
    return Map.of("saved", true, "username", username,
        "usernameCanChangeAt", Instant.now().plus(15, ChronoUnit.DAYS).toString());
  }

  @PutMapping("/password")
  @Transactional
  public Map<String,Object> updatePassword(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireAuth(context);
    long userId = requireAccountId(context);
    String newPassword = String.valueOf(body.getOrDefault("newPassword", ""));
    String currentPassword = String.valueOf(body.getOrDefault("currentPassword", ""));
    int bytes = newPassword.getBytes(StandardCharsets.UTF_8).length;
    if (newPassword.length() < 8 || bytes > 72 || newPassword.isBlank()) {
      throw new IllegalArgumentException("Password must be at least 8 characters and no more than 72 UTF-8 bytes.");
    }
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select password_hash from user_account where id=? and active=true for update
      """, userId);
    if (rows.isEmpty()) throw new SecurityException("The account is inactive or unavailable.");
    Object stored = rows.getFirst().get("password_hash");
    if (stored != null && !String.valueOf(stored).isBlank()) {
      if (currentPassword.isBlank() || !PASSWORD_ENCODER.matches(currentPassword, String.valueOf(stored))) {
        throw new SecurityException("Your current password is incorrect.");
      }
    }
    jdbc.update("update user_account set password_hash=?,updated_at=now() where id=? and active=true",
        PASSWORD_ENCODER.encode(newPassword), userId);
    jdbc.update("update auth_session set revoked_at=now() where user_id=? and revoked_at is null", userId);
    audit(userId, context.studentId(), stored == null ? "PASSWORD_SET" : "PASSWORD_CHANGED",
        Map.of("sessionPolicy", "other_sessions_revoked"));
    return Map.of("saved", true, "reauthenticationRequired", true);
  }

  private Map<String,Object> childReadOnlyProfile(long studentId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select st.id,st.display_name,st.class_code,st.board,st.language,c.username
      from student st
      left join student_login_credential c on c.student_id=st.id and c.active=true
      where st.id=? and st.active=true
      """, studentId);
    if (rows.isEmpty()) throw new SecurityException("The learner profile is unavailable.");
    Map<String,Object> row = rows.getFirst();
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("displayName", row.get("display_name"));
    result.put("accountType", "CHILD");
    result.put("role", "STUDENT");
    result.put("email", null);
    result.put("gmailVerified", false);
    result.put("mobile", null);
    result.put("mobileVerified", false);
    result.put("verifiedMethods", List.of());
    result.put("profileImageUrl", null);
    result.put("username", row.get("username"));
    result.put("usernameCanChange", false);
    result.put("usernameCanChangeAt", null);
    result.put("preferredLocale", row.get("language"));
    result.put("linkedStudentId", row.get("id"));
    result.put("classCode", row.get("class_code"));
    result.put("board", row.get("board"));
    result.put("learningLanguage", row.get("language"));
    result.put("credentialsManagedByParent", true);
    result.put("canRequestParentAccount", false);
    return result;
  }

  private long requireAccountId(AuthContext context) {
    Long id = accountIdFor(context);
    if (id == null) throw new SecurityException("This learner's account credentials are managed by their parent.");
    return id;
  }

  private Long accountIdFor(AuthContext context) {
    if (context.userId() != null) {
      List<Map<String,Object>> rows = jdbc.queryForList(
          "select id from user_account where id=? and active=true", context.userId());
      return rows.isEmpty() ? null : ((Number) rows.getFirst().get("id")).longValue();
    }
    if (context.studentId() != null) {
      List<Map<String,Object>> rows = jdbc.queryForList("""
        select u.id from student st
        join user_account u on u.id=st.user_account_id and u.active=true
        where st.id=? and st.active=true and st.environment='PRODUCTION'
        """, context.studentId());
      return rows.isEmpty() ? null : ((Number) rows.getFirst().get("id")).longValue();
    }
    return null;
  }

  private void audit(long userId, Long actorStudentId, String event, Map<String,Object> details) {
    jdbc.update("""
      insert into account_identity_audit(user_id,actor_student_id,event_type,details)
      values (?,?,?,?::jsonb)
      """, userId, actorStudentId, event, json(details));
  }

  private static String json(Map<String,Object> values) {
    try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values); }
    catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Audit details could not be encoded.", ex); }
  }

  private static Instant toInstant(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
    if (value instanceof java.time.LocalDateTime localDateTime) return localDateTime.toInstant(java.time.ZoneOffset.UTC);
    return Instant.parse(String.valueOf(value));
  }
}
