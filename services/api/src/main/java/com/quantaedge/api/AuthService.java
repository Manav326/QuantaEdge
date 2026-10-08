package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  public static final String COOKIE = "QE_SESSION";
  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();
  private final boolean demoSeed;
  private final int sessionHours;
  private final List<String> adminMobiles;

  public AuthService(
      JdbcTemplate jdbc,
      @Value("${app.demo-seed:false}") boolean demoSeed,
      @Value("${app.auth.session-hours:168}") int sessionHours,
      @Value("${app.auth.admin-mobiles:}") String adminMobiles) {
    this.jdbc = jdbc;
    this.demoSeed = demoSeed;
    this.sessionHours = sessionHours;
    this.adminMobiles = List.of(adminMobiles.split(",")).stream()
        .map(this::normalizeMobile)
        .filter(v -> !v.isBlank())
        .toList();
  }

  public String normalizeMobile(String value) {
    String digits = value == null ? "" : value.replaceAll("\D", "");
    if (digits.startsWith("91") && digits.length() == 12) return "+" + digits;
    if (digits.length() == 10) return "+91" + digits;
    return value == null ? "" : value.trim();
  }

  public String requestOtp(String mobile, String purpose) {
    String normalized = normalizeMobile(mobile);
    if (!normalized.matches("\+91\d{10}")) throw new IllegalArgumentException("Invalid Indian mobile number");
    long recent = jdbc.queryForObject(
        "select count(*) from otp_challenge where mobile_e164=? and requested_at > now()-interval '60 seconds'",
        Long.class, normalized);
    if (recent > 0) throw new IllegalStateException("Please wait before requesting another OTP");

    String otp = demoSeed ? "123456" : String.format("%06d", random.nextInt(1_000_000));
    jdbc.update("""
      insert into otp_challenge(mobile_e164,purpose,code_hash,expires_at)
      values (?,?,?,now()+interval '5 minutes')
      """, normalized, purpose, hash(otp));
    // Production delivery is deliberately delegated to the configured provider
    // integration. Local demo returns the code to make deterministic QA possible.
    if (demoSeed) return otp;
    String providerUrl = System.getenv("APP_OTP_PROVIDER_URL");
    String providerToken = System.getenv("APP_OTP_PROVIDER_TOKEN");
    if (providerUrl == null || providerUrl.isBlank() || providerToken == null || providerToken.isBlank()) {
      throw new IllegalStateException("OTP provider is not configured");
    }
    try {
      var client = java.net.http.HttpClient.newHttpClient();
      var payload = "{\"to\":\"" + normalized + "\",\"otp\":\"" + otp + "\",\"purpose\":\"" + purpose + "\"}";
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(providerUrl))
          .header("Authorization", "Bearer " + providerToken)
          .header("Content-Type", "application/json")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
          .build();
      var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) throw new IllegalStateException("OTP provider rejected request");
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to deliver OTP");
    }
    return null;
  }

  @Transactional
  public AuthContext verifyOtp(String mobile, String otp, String displayName) {
    String normalized = normalizeMobile(mobile);
    var rows = jdbc.queryForList("""
      select id,code_hash,attempts,expires_at
      from otp_challenge
      where mobile_e164=? and consumed_at is null
      order by requested_at desc limit 1
      """, normalized);
    if (rows.isEmpty()) throw new IllegalArgumentException("OTP not found or expired");
    var row = rows.getFirst();
    if (((Number) row.get("attempts")).intValue() >= 5) throw new IllegalStateException("Too many OTP attempts");
    jdbc.update("update otp_challenge set attempts=attempts+1 where id=?", row.get("id"));
    if (java.time.OffsetDateTime.now().isAfter((java.time.OffsetDateTime) row.get("expires_at"))) {
      throw new IllegalArgumentException("OTP expired");
    }
    if (!hash(otp).equals(String.valueOf(row.get("code_hash")))) {
      throw new IllegalArgumentException("Incorrect OTP");
    }
    jdbc.update("update otp_challenge set consumed_at=now() where id=?", row.get("id"));
    String role = adminMobiles.contains(normalized) ? "ADMIN" : "PARENT";
    Long userId;
    try {
      userId = jdbc.queryForObject("select id from user_account where mobile_e164=?", Long.class, normalized);
      jdbc.update("update user_account set display_name=coalesce(?,display_name),active=true,updated_at=now(),role=? where id=?",
          displayName, role, userId);
    } catch (EmptyResultDataAccessException ex) {
      userId = jdbc.queryForObject("""
        insert into user_account(public_id,mobile_e164,display_name,role)
        values (?,?,?,?) returning id
        """, Long.class, UUID.randomUUID(), normalized, displayName, role);
    }
    return createSession(userId, null);
  }

  public AuthContext loginStudent(String publicId, String pin) {
    var row = jdbc.queryForMap("""
      select id,display_name,access_pin_hash
      from student where public_id=? and active=true and environment='PRODUCTION'
      """, UUID.fromString(publicId));
    String stored = String.valueOf(row.get("access_pin_hash"));
    if (stored == null || stored.isBlank() || !hash(pin).equals(stored)) {
      throw new IllegalArgumentException("Invalid student access code");
    }
    return createSession(null, ((Number) row.get("id")).longValue());
  }

  @Transactional
  public AuthContext previewLogin() {
    if (!demoSeed) throw new IllegalStateException("Preview login is disabled");
    Long id = jdbc.queryForObject("""
      select id from student where environment='LOCAL_PREVIEW' and active=true order by id limit 1
      """, Long.class);
    if (id == null) throw new IllegalStateException("Preview student unavailable");
    return createSession(null, id);
  }

  public AuthContext current(String token) {
    if (token == null || token.isBlank()) return null;
    var rows = jdbc.queryForList("""
      select s.user_id,s.student_id,u.role,u.display_name,
             st.display_name as student_name
      from auth_session s
      left join user_account u on u.id=s.user_id
      left join student st on st.id=s.student_id
      where s.token_hash=? and s.revoked_at is null and s.expires_at>now()
      """, hash(token));
    if (rows.isEmpty()) return null;
    var r = rows.getFirst();
    jdbc.update("update auth_session set last_seen_at=now() where token_hash=?", hash(token));
    String name = r.get("student_name") != null ? String.valueOf(r.get("student_name")) :
        (r.get("display_name") == null ? null : String.valueOf(r.get("display_name")));
    return new AuthContext(
        r.get("user_id")==null?null:((Number)r.get("user_id")).longValue(),
        r.get("student_id")==null?null:((Number)r.get("student_id")).longValue(),
        r.get("role")==null?null:String.valueOf(r.get("role")), name);
  }

  public String issueToken(AuthContext context) {
    String raw = randomToken();
    String tokenHash = hash(raw);
    Instant expires = Instant.now().plus(Duration.ofHours(sessionHours));
    jdbc.update("""
      insert into auth_session(token_hash,user_id,student_id,expires_at)
      values (?,?,?,?)
      """, tokenHash, context.userId(), context.studentId(), java.sql.Timestamp.from(expires));
    return raw;
  }

  public void revoke(String token) {
    if (token != null && !token.isBlank()) jdbc.update("update auth_session set revoked_at=now() where token_hash=?", hash(token));
  }

  public AuthContext createSession(Long userId, Long studentId) {
    if (studentId != null) {
      return new AuthContext(null, studentId, "STUDENT", lookupStudentName(studentId));
    }
    String role = jdbc.queryForObject("select role from user_account where id=?", String.class, userId);
    String name = jdbc.queryForObject("select display_name from user_account where id=?", String.class, userId);
    return new AuthContext(userId, null, role, name);
  }

  public boolean hasGuardianAccess(long userId, long studentId) {
    return jdbc.queryForObject("""
      select exists(
        select 1 from guardian_student
        where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED'
      )
      """, Boolean.class, userId, studentId);
  }

  private String lookupStudentName(long studentId) {
    return jdbc.queryForObject("select display_name from student where id=?", String.class, studentId);
  }

  public String hash(String value) {
    try {
      var md = MessageDigest.getInstance("SHA-256");
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
