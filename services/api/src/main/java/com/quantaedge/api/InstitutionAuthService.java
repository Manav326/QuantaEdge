package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authentication and tenant-scoped learner membership for institution customers.
 *
 * Institution administrators are not staff_account, hr_employee, user_account or learner
 * principals. Their opaque credentials live only in institution_admin_session.
 */
@Service
public class InstitutionAuthService {
  private static final String DUMMY_BCRYPT =
      "$2a$12$vv2074FPhWNMKb9HDsP9hu3Av11EyJX/Z8cjDAaCShvZWbrV1m2bK";
  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Kolkata");

  private final JdbcTemplate jdbc;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();

  @Value("${app.institution.code-encryption-key:}")
  private String codeEncryptionKey = "";

  public InstitutionAuthService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record InstitutionPrincipal(
      long adminId,
      long institutionId,
      String tenantCode,
      String institutionName,
      String username,
      String displayName,
      boolean mustChangePassword) {}

  public record SessionTokens(String accessToken, String refreshToken, InstitutionPrincipal principal) {}

  public record IssuedJoinCode(long id, String code, String hint, Instant expiresAt) {}

  @Transactional(noRollbackFor = {ResponseStatusException.class, IllegalArgumentException.class})
  public SessionTokens login(String username, String password) {
    String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    if (!normalized.matches("^[a-z0-9][a-z0-9._-]{2,63}$") || password == null || password.isBlank()) {
      passwordEncoder.matches(password == null ? "" : password, DUMMY_BCRYPT);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }

    String identifierHash = hash("institution-login:" + normalized);
    jdbc.update("delete from institution_auth_failure where attempted_at < now()-interval '24 hours'");
    Long failures = jdbc.queryForObject("""
        select count(*) from institution_auth_failure
        where username_hash=? and attempted_at > now()-interval '15 minutes'
        """, Long.class, identifierHash);
    if (failures != null && failures >= 5) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many sign-in attempts. Please wait 15 minutes before trying again.");
    }

    List<Map<String,Object>> rows = jdbc.queryForList("""
        select a.id,a.institution_id,a.password_hash,a.active,a.must_change_password,t.status as tenant_status
        from institution_admin_account a
        join institution_tenant t on t.id=a.institution_id
        where lower(a.username)=?
        """, normalized);
    if (rows.isEmpty()) {
      passwordEncoder.matches(password, DUMMY_BCRYPT);
      recordLoginFailure(identifierHash);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }

    Map<String,Object> row = rows.getFirst();
    String passwordHash = row.get("password_hash") == null ? "" : String.valueOf(row.get("password_hash"));
    boolean valid = !passwordHash.isBlank() && passwordEncoder.matches(password, passwordHash);
    if (!valid || !Boolean.TRUE.equals(row.get("active"))
        || !"ACTIVE".equals(String.valueOf(row.get("tenant_status")))) {
      recordLoginFailure(identifierHash);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }

    long adminId = ((Number) row.get("id")).longValue();
    long institutionId = ((Number) row.get("institution_id")).longValue();
    jdbc.update("delete from institution_auth_failure where username_hash=?", identifierHash);
    jdbc.update("update institution_admin_account set last_login_at=now() where id=?", adminId);
    return issueSession(adminId, institutionId);
  }

  @Transactional
  public SessionTokens issueSession(long adminId, long institutionId) {
    Boolean allowed = jdbc.queryForObject("""
        select exists(
          select 1 from institution_admin_account a
          join institution_tenant t on t.id=a.institution_id
          where a.id=? and a.institution_id=? and a.active=true and t.status='ACTIVE'
        )
        """, Boolean.class, adminId, institutionId);
    if (!Boolean.TRUE.equals(allowed)) throw new SecurityException("Institution access is not active.");

    String access = randomToken();
    String refresh = randomToken();
    jdbc.update("""
        insert into institution_admin_session(
          institution_admin_id,institution_id,access_token_hash,refresh_token_hash,
          expires_at,refresh_expires_at,absolute_expires_at)
        values (?,?,?,?,now()+interval '15 minutes',now()+interval '7 days',now()+interval '30 days')
        """, adminId, institutionId, hash(access), hash(refresh));
    InstitutionPrincipal principal = current(access);
    if (principal == null) throw new IllegalStateException("Unable to establish institution session.");
    return new SessionTokens(access, refresh, principal);
  }

  public InstitutionPrincipal current(String rawAccessToken) {
    if (rawAccessToken == null || rawAccessToken.isBlank()) return null;
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select s.id as session_id,a.id as admin_id,a.institution_id,a.username,
          a.display_name as admin_display_name,a.must_change_password,
          t.tenant_code,coalesce(b.display_name,t.display_name) as institution_name
        from institution_admin_session s
        join institution_admin_account a on a.id=s.institution_admin_id and a.active=true
        join institution_tenant t on t.id=s.institution_id and t.id=a.institution_id and t.status='ACTIVE'
        left join institution_branding b on b.institution_id=t.id
        where s.access_token_hash=? and s.revoked_at is null and s.expires_at>now()
          and s.absolute_expires_at>now() and s.last_seen_at>now()-interval '12 hours'
        """, hash(rawAccessToken));
    if (rows.isEmpty()) return null;
    Map<String,Object> row = rows.getFirst();
    jdbc.update("update institution_admin_session set last_seen_at=now() where id=? and revoked_at is null",
        row.get("session_id"));
    return principal(row);
  }

  @Transactional
  public SessionTokens refresh(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw new SecurityException("Session expired. Please sign in again.");
    }
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select s.id as session_id,s.institution_admin_id,s.institution_id
        from institution_admin_session s
        join institution_admin_account a on a.id=s.institution_admin_id and a.active=true
        join institution_tenant t on t.id=s.institution_id and t.id=a.institution_id and t.status='ACTIVE'
        where s.refresh_token_hash=? and s.revoked_at is null
          and s.refresh_expires_at>now() and s.absolute_expires_at>now()
          and s.last_seen_at>now()-interval '12 hours'
        for update of s
        """, hash(rawRefreshToken));
    if (rows.isEmpty()) throw new SecurityException("Institution session expired. Please sign in again.");
    Map<String,Object> row = rows.getFirst();
    String nextAccess = randomToken();
    String nextRefresh = randomToken();
    int changed = jdbc.update("""
        update institution_admin_session
        set access_token_hash=?,refresh_token_hash=?,
            expires_at=least(now()+interval '15 minutes',absolute_expires_at),
            refresh_expires_at=least(now()+interval '7 days',absolute_expires_at),last_seen_at=now()
        where id=? and revoked_at is null
        """, hash(nextAccess), hash(nextRefresh), row.get("session_id"));
    if (changed != 1) throw new SecurityException("Institution session expired. Please sign in again.");
    InstitutionPrincipal principal = current(nextAccess);
    if (principal == null) throw new SecurityException("Institution session expired. Please sign in again.");
    return new SessionTokens(nextAccess, nextRefresh, principal);
  }

  public void logout(String rawAccessToken, String rawRefreshToken) {
    if (rawAccessToken != null && !rawAccessToken.isBlank()) {
      jdbc.update("update institution_admin_session set revoked_at=now() where access_token_hash=? and revoked_at is null",
          hash(rawAccessToken));
    }
    if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
      jdbc.update("update institution_admin_session set revoked_at=now() where refresh_token_hash=? and revoked_at is null",
          hash(rawRefreshToken));
    }
  }

  @Transactional
  public void changePassword(InstitutionPrincipal principal, String currentPassword, String nextPassword) {
    if (principal == null) throw new SecurityException("Institution sign-in is required.");
    validatePassword(nextPassword);
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select password_hash from institution_admin_account
        where id=? and institution_id=? and active=true for update
        """, principal.adminId(), principal.institutionId());
    if (rows.isEmpty()) throw new SecurityException("Institution account is not active.");
    String existingHash = String.valueOf(rows.getFirst().get("password_hash"));
    if (currentPassword == null || !passwordEncoder.matches(currentPassword, existingHash)) {
      throw new IllegalArgumentException("Current password is incorrect.");
    }
    jdbc.update("""
        update institution_admin_account
        set password_hash=?,must_change_password=false,password_changed_at=now(),updated_at=now()
        where id=? and institution_id=?
        """, passwordEncoder.encode(nextPassword), principal.adminId(), principal.institutionId());
    jdbc.update("""
        update institution_admin_session set revoked_at=now()
        where institution_admin_id=? and revoked_at is null
        """, principal.adminId());
  }

  public IssuedJoinCode rotateJoinCode(long institutionId, long actorStaffId, Instant expiresAt, Long useLimit) {
    ensureEncryptionKey();
    if (useLimit != null && useLimit < 1) throw new IllegalArgumentException("Use limit must be a positive number.");
    jdbc.update("""
        update institution_join_code set status='REVOKED',revoked_at=now()
        where institution_id=? and status='ACTIVE'
        """, institutionId);
    String code = generateJoinCode();
    String codeHash = hash(canonicalCode(code));
    String encrypted = encryptCode(code);
    Long id = jdbc.queryForObject("""
        insert into institution_join_code(institution_id,code_hash,encrypted_code,code_hint,expires_at,use_limit,created_by_staff_id)
        values (?,?,?,?,?,?,?) returning id
        """, Long.class, institutionId, codeHash, encrypted,
        code.substring(Math.max(0, code.length()-4)), expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
        useLimit, actorStaffId);
    return new IssuedJoinCode(java.util.Objects.requireNonNull(id), code,
        code.substring(Math.max(0, code.length()-4)), expiresAt);
  }

  /** Returns the live share code only for the owning institution admin, after successful authentication. */
  public String readableJoinCode(long institutionId) {
    ensureEncryptionKey();
    List<String> encryptedCodes = jdbc.queryForList("""
        select encrypted_code from institution_join_code
        where institution_id=? and status='ACTIVE'
          and (expires_at is null or expires_at>now())
          and (use_limit is null or redeemed_count<use_limit)
        order by created_at desc,id desc limit 1
        """, String.class, institutionId);
    if (encryptedCodes.isEmpty()) return null;
    return decryptCode(encryptedCodes.getFirst());
  }

  public String publicBranding(String tenantCode) {
    String normalized = tenantCode == null ? "" : tenantCode.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^[A-Z0-9][A-Z0-9_-]{2,31}$")) return null;
    List<String> names = jdbc.queryForList("""
        select coalesce(b.display_name,t.display_name)
        from institution_tenant t left join institution_branding b on b.institution_id=t.id
        where t.tenant_code=? and t.status='ACTIVE'
        """, String.class, normalized);
    return names.isEmpty() ? null : names.getFirst();
  }

  @Transactional
  public Map<String,Object> redeemJoinCode(String suppliedCode, long studentId, String sourceHash) {
    if (suppliedCode == null || suppliedCode.isBlank()) {
      throw new IllegalArgumentException("Enter a valid institution code.");
    }
    Long recentAttempts = jdbc.queryForObject("""
        select count(*) from institution_join_attempt
        where source_hash=? and attempted_at>now()-interval '10 minutes'
        """, Long.class, sourceHash);
    if (recentAttempts != null && recentAttempts >= 20) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many institution-code attempts. Please try again later.");
    }
    jdbc.update("insert into institution_join_attempt(source_hash) values (?)", sourceHash);

    String codeHash = hash(canonicalCode(suppliedCode));
    List<Map<String,Object>> codeRows = jdbc.queryForList("""
        select c.id as code_id,c.institution_id,c.use_limit,c.redeemed_count,t.tenant_code,
          coalesce(b.display_name,t.display_name) as institution_name
        from institution_join_code c
        join institution_tenant t on t.id=c.institution_id and t.status='ACTIVE'
        left join institution_branding b on b.institution_id=t.id
        join student st on st.id=? and st.active=true and st.environment='PRODUCTION'
        where c.code_hash=? and c.status='ACTIVE'
          and (c.expires_at is null or c.expires_at>now())
          and (c.use_limit is null or c.redeemed_count<c.use_limit)
          and exists (select 1 from institution_billing_contract bc
            where bc.institution_id=t.id and bc.active=true and bc.effective_from<=current_date
              and (bc.effective_until is null or bc.effective_until>=current_date))
        for update of c
        """, studentId, codeHash);
    if (codeRows.isEmpty()) throw new IllegalArgumentException("This institution code is invalid or no longer active.");

    Map<String,Object> codeRow = codeRows.getFirst();
    long institutionId = ((Number) codeRow.get("institution_id")).longValue();
    long codeId = ((Number) codeRow.get("code_id")).longValue();
    List<Map<String,Object>> existing = jdbc.queryForList("""
        select id,status from institution_membership
        where institution_id=? and student_id=? for update
        """, institutionId, studentId);
    if (!existing.isEmpty() && "ACTIVE".equals(String.valueOf(existing.getFirst().get("status")))) {
      Map<String,Object> result = new java.util.LinkedHashMap<>();
      result.put("joined", true);
      result.put("alreadyMember", true);
      result.put("institutionCode", codeRow.get("tenant_code"));
      result.put("institutionName", codeRow.get("institution_name"));
      return result;
    }

    int consumed = jdbc.update("""
        update institution_join_code set redeemed_count=redeemed_count+1
        where id=? and status='ACTIVE' and (use_limit is null or redeemed_count<use_limit)
          and (expires_at is null or expires_at>now())
        """, codeId);
    if (consumed != 1) throw new IllegalArgumentException("This institution code is invalid or no longer active.");

    Long membershipId = jdbc.queryForObject("""
        insert into institution_membership(institution_id,student_id,join_code_id,status,joined_at,left_at,created_at,updated_at)
        values (?,?,?,'ACTIVE',now(),null,now(),now())
        on conflict(institution_id,student_id) do update
          set status='ACTIVE',join_code_id=excluded.join_code_id,
              joined_at=case when institution_membership.status='ACTIVE'
                then institution_membership.joined_at else now() end,
              left_at=null,updated_at=now()
        returning id
        """, Long.class, institutionId, studentId, codeId);
    boolean reactivated = !existing.isEmpty();
    jdbc.update("""
        insert into institution_membership_event(institution_id,membership_id,student_id,join_code_id,event_type,actor_student_id,details)
        values (?,?,?,?,?,?,jsonb_build_object('source','institution_code','reactivated',?))
        """, institutionId, membershipId, studentId, codeId,
        reactivated ? "MEMBERSHIP_REACTIVATED" : "MEMBERSHIP_CREATED", studentId, reactivated);
    jdbc.update("""
        insert into institution_membership_event(institution_id,membership_id,student_id,join_code_id,event_type,actor_student_id)
        values (?,?,?,?,'JOIN_CODE_REDEEMED',?)
        """, institutionId, membershipId, studentId, codeId, studentId);
    recordBillableEvent(institutionId, membershipId, studentId);

    Map<String,Object> result = new java.util.LinkedHashMap<>();
    result.put("joined", true);
    result.put("alreadyMember", false);
    result.put("institutionCode", codeRow.get("tenant_code"));
    result.put("institutionName", codeRow.get("institution_name"));
    result.put("memberOfMultipleInstitutions", jdbc.queryForObject("""
        select count(*)>1 from institution_membership
        where student_id=? and status='ACTIVE'
        """, Boolean.class, studentId));
    return result;
  }

  private void recordBillableEvent(long institutionId, long membershipId, long studentId) {
    LocalDate today = LocalDate.now(BILLING_ZONE);
    List<Map<String,Object>> contracts = jdbc.queryForList("""
        select id,currency,unit_price_paise,billing_cadence
        from institution_billing_contract
        where institution_id=? and active=true and effective_from<=?
          and (effective_until is null or effective_until>=?)
        order by effective_from desc,id desc limit 1
        """, institutionId, today, today);
    if (contracts.isEmpty()) return;
    Map<String,Object> contract = contracts.getFirst();
    LocalDate periodStart;
    LocalDate periodEnd;
    if ("ANNUAL".equals(String.valueOf(contract.get("billing_cadence")))) {
      periodStart = today.withDayOfYear(1);
      periodEnd = periodStart.plusYears(1).minusDays(1);
    } else {
      periodStart = today.withDayOfMonth(1);
      periodEnd = periodStart.plusMonths(1).minusDays(1);
    }
    String idempotencyKey = "institution:" + institutionId + ":membership:" + membershipId + ":period:" + periodStart + ":charge";
    jdbc.update("""
        insert into institution_billable_event(
          institution_id,membership_id,student_id,contract_id,billing_period_start,billing_period_end,
          event_type,amount_paise,currency,idempotency_key,description)
        values (?,?,?,?,?,?,'CHARGE',?,?,?,'Active institution enrollment')
        on conflict do nothing
        """, institutionId, membershipId, studentId, contract.get("id"), periodStart, periodEnd,
        contract.get("unit_price_paise"), contract.get("currency"), idempotencyKey);
  }

  private InstitutionPrincipal principal(Map<String,Object> row) {
    return new InstitutionPrincipal(
        ((Number) row.get("admin_id")).longValue(),
        ((Number) row.get("institution_id")).longValue(),
        String.valueOf(row.get("tenant_code")),
        String.valueOf(row.get("institution_name")),
        String.valueOf(row.get("username")),
        String.valueOf(row.get("admin_display_name")),
        Boolean.TRUE.equals(row.get("must_change_password")));
  }

  private void recordLoginFailure(String identifierHash) {
    jdbc.update("insert into institution_auth_failure(username_hash) values (?)", identifierHash);
  }

  private String generateJoinCode() {
    StringBuilder raw = new StringBuilder(16);
    for (int i=0;i<16;i++) raw.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
    return raw.substring(0,4)+"-"+raw.substring(4,8)+"-"+raw.substring(8,12)+"-"+raw.substring(12,16);
  }

  public String hash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 is not available.", ex);
    }
  }

  private String canonicalCode(String value) {
    return value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  private String randomToken() {
    byte[] bytes = new byte[48];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72
        || password.isBlank()) {
      throw new IllegalArgumentException("Password must be at least 12 characters and at most 72 UTF-8 bytes.");
    }
  }

  public String encodePassword(String password) {
    validatePassword(password);
    return passwordEncoder.encode(password);
  }

  private void ensureEncryptionKey() {
    if (codeEncryptionKey == null || codeEncryptionKey.trim().length() < 32) {
      throw new IllegalStateException(
          "Institution join-code storage is not configured. Set APP_INSTITUTION_CODE_ENCRYPTION_KEY to a random secret of at least 32 characters.");
    }
  }

  private String encryptCode(String code) {
    try {
      ensureEncryptionKey();
      byte[] key = MessageDigest.getInstance("SHA-256").digest(codeEncryptionKey.getBytes(StandardCharsets.UTF_8));
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length+encrypted.length];
      System.arraycopy(nonce,0,combined,0,nonce.length);
      System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to encrypt the institution join code.", ex);
    }
  }

  private String decryptCode(String encryptedValue) {
    try {
      ensureEncryptionKey();
      byte[] combined = Base64.getUrlDecoder().decode(encryptedValue);
      if (combined.length < 29) throw new IllegalArgumentException("Encrypted join code is invalid.");
      byte[] nonce = java.util.Arrays.copyOfRange(combined,0,12);
      byte[] ciphertext = java.util.Arrays.copyOfRange(combined,12,combined.length);
      byte[] key = MessageDigest.getInstance("SHA-256").digest(codeEncryptionKey.getBytes(StandardCharsets.UTF_8));
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
      return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to decrypt institution join code. Check APP_INSTITUTION_CODE_ENCRYPTION_KEY.",ex);
    }
  }
}
