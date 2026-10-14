package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
  /** Shared legacy cookie names are read only for a workspace-matched migration fallback. */
  public static final String COOKIE = "QE_SESSION";
  public static final String REFRESH_COOKIE = "QE_REFRESH";
  public static final String STAFF_COOKIE = "QE_STAFF_SESSION";
  public static final String STAFF_REFRESH_COOKIE = "QE_STAFF_REFRESH";
  public static final String LEARNING_COOKIE = "QE_LEARN_SESSION";
  public static final String LEARNING_REFRESH_COOKIE = "QE_LEARN_REFRESH";
  public static final String HRMS_COOKIE = "QE_HRMS_SESSION";
  public static final String HRMS_REFRESH_COOKIE = "QE_HRMS_REFRESH";
  public static final String WORKSPACE_HEADER = "X-QE-Workspace";
  public static final String GUARDIAN_CONSENT_VERSION = "guardian-consent-v1";
  private static final int MAX_PASSWORD_LOGIN_FAILURES = 5;

  public static String normalizeWorkspace(String value) {
    if (value == null) return null;
    return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "STAFF" -> "STAFF"; // Legacy education-staff scope.
      case "EDUCATION_ADMIN" -> "EDUCATION_ADMIN";
      case "HRMS" -> "HRMS";
      case "PARENT" -> "PARENT";
      case "STUDENT" -> "STUDENT";
      case "LEARNING" -> "LEARNING";
      default -> null;
    };
  }

  public static String sessionCookieForWorkspace(String workspace) {
    String normalized = normalizeWorkspace(workspace);
    if ("HRMS".equals(normalized)) return HRMS_COOKIE;
    if ("STAFF".equals(normalized) || "EDUCATION_ADMIN".equals(normalized)) return STAFF_COOKIE;
    if ("PARENT".equals(normalized)) return "QE_PARENT_SESSION";
    if ("STUDENT".equals(normalized)) return "QE_STUDENT_SESSION";
    return LEARNING_COOKIE;
  }

  public static String refreshCookieForWorkspace(String workspace) {
    String normalized = normalizeWorkspace(workspace);
    if ("HRMS".equals(normalized)) return HRMS_REFRESH_COOKIE;
    if ("STAFF".equals(normalized) || "EDUCATION_ADMIN".equals(normalized)) return STAFF_REFRESH_COOKIE;
    if ("PARENT".equals(normalized)) return "QE_PARENT_REFRESH";
    if ("STUDENT".equals(normalized)) return "QE_STUDENT_REFRESH";
    return LEARNING_REFRESH_COOKIE;
  }

  public static String workspaceForContext(AuthContext context) {
    if (context == null) return "LEARNING";
    if (context.isEmployee()) return "STAFF";
    if (context.isStudent()) return "STUDENT";
    if (context.isParent()) return "PARENT";
    return "LEARNING";
  }
  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
  private final boolean demoSeed;
  private final int sessionHours;
  private final boolean secureCookies;
  private final int otpCooldownSeconds;
  private final List<String> adminMobiles;
  @Value("${app.auth.max-children-per-parent:3}")
  private int maxChildrenPerParent = 3;
  // This opt-in is effective only with demo OTP mode, never with real OTP delivery.
  @Value("${app.auth.allow-repeated-demo-otp:false}")
  private boolean allowRepeatedDemoOtp = false;
  @Value("${app.auth.staff-absolute-hours:8}")
  private int staffAbsoluteHours = 8;
  @Value("${app.auth.learning-absolute-days:30}")
  private int learningAbsoluteDays = 30;
  @Value("${app.environment:local}")
  private String appEnvironment = "local";
  @Value("${spring.profiles.active:}")
  private String activeProfiles = "";

  @PostConstruct
  void validateSecurityConfiguration() {
    boolean production = "production".equalsIgnoreCase(appEnvironment)
        || "prod".equalsIgnoreCase(appEnvironment)
        || Arrays.stream(activeProfiles.split(","))
            .map(String::trim)
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    if (production && !secureCookies) {
      throw new IllegalStateException("APP_AUTH_SECURE_COOKIES must be true in production.");
    }
    if (production && demoSeed) {
      throw new IllegalStateException("APP_DEMO_SEED must be false in production.");
    }
    if (production && allowRepeatedDemoOtp) {
      throw new IllegalStateException("APP_AUTH_ALLOW_REPEATED_DEMO_OTP must be false in production.");
    }
  }

  public AuthService(
      JdbcTemplate jdbc,
      @Value("${app.demo-seed:false}") boolean demoSeed,
      @Value("${app.auth.session-hours:168}") int sessionHours,
      @Value("${app.auth.secure-cookies:true}") boolean secureCookies,
      @Value("${app.auth.otp-cooldown-seconds:60}") int otpCooldownSeconds,
      @Value("${app.auth.admin-mobiles:}") String adminMobiles) {
    this.jdbc=jdbc;
    this.demoSeed=demoSeed;
    // Keep the sliding refresh lifetime at four hours for compatibility. The separate
    // per-workspace idle and absolute-expiry predicates enforce the actual session limits.
    this.sessionHours=4;
    this.secureCookies=secureCookies;
    this.otpCooldownSeconds=otpCooldownSeconds;
    this.adminMobiles=List.of(adminMobiles.split(",")).stream()
        .map(this::normalizeMobile).filter(v->!v.isBlank()).toList();
  }

  public int getSessionHours() { return sessionHours; }

  /** True while an invited staff account has not completed its first successful sign-in. */
  public boolean isStaffFirstAccess(String mobile) {
    String normalized=normalizeMobile(mobile);
    List<Map<String,Object>> rows=jdbc.queryForList(
        "select last_login_at from staff_account where mobile_e164=?", normalized);
    if (rows.isEmpty()) return adminMobiles.contains(normalized);
    return rows.getFirst().get("last_login_at")==null;
  }

  public String normalizeMobile(String value) {
    String digits=value==null?"":value.replaceAll("[^0-9]","");
    if(digits.startsWith("91") && digits.length()==12) return "+"+digits;
    if(digits.length()==10) return "+91"+digits;
    return value==null?"":value.trim();
  }

  public String requestOtp(String mobile,String purpose) {
    if(!"LOGIN".equals(purpose) && !"SIGNUP".equals(purpose) && !"STAFF_LOGIN".equals(purpose) && !"PASSWORD_RESET".equals(purpose))
      throw new IllegalArgumentException("Invalid OTP purpose");
    String normalized=normalizeMobile(mobile);
    if(!(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit))) throw new IllegalArgumentException("Invalid Indian mobile number");

    if ("STAFF_LOGIN".equals(purpose)) {
      List<Map<String,Object>> staffRows=jdbc.queryForList(
          "select active from staff_account where mobile_e164=?", normalized);
      if (!staffRows.isEmpty()) {
        if (!Boolean.TRUE.equals(staffRows.getFirst().get("active"))) {
          throw new SecurityException("This staff account is inactive. Contact an administrator.");
        }
      } else {
        if (!adminMobiles.contains(normalized)) {
          throw new IllegalArgumentException("This mobile is not assigned to an active staff account.");
        }
      }
    } else if ("PASSWORD_RESET".equals(purpose)) {
      boolean parentExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=? and role='PARENT' and active=true)",
          Boolean.class, normalized));
      if(!parentExists) throw new IllegalArgumentException("No parent account found for this mobile number.");
    } else if ("SIGNUP".equals(purpose)) {
      // Prevent duplicate customer identities independently of a matching staff identity.
      boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=?)", Boolean.class, normalized));
      if(accountExists)
        throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
    } else {
      // A login OTP must only be available to an active parent identity.
      boolean activeParentExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=? and role='PARENT' and active=true)",
          Boolean.class, normalized));
      if(!activeParentExists)
        throw new IllegalArgumentException("No active parent account found for this mobile number. If the account is suspended, contact support.");
    }

    // Keep real OTP delivery rate-limited. Local QA can opt out only when the fixed demo OTP
    // is enabled, avoiding repeated-login test lockouts without weakening production delivery.
    if (!(demoSeed && allowRepeatedDemoOtp)) {
      long recent=jdbc.queryForObject(
          "select count(*) from otp_challenge where mobile_e164=? and requested_at > now() - (? * interval '1 second')",
          Long.class,normalized,otpCooldownSeconds);
      if(recent>0) throw new IllegalStateException("Please wait before requesting another OTP");
      long hourly=jdbc.queryForObject(
          "select count(*) from otp_challenge where mobile_e164=? and requested_at > now()-interval '1 hour'",
          Long.class,normalized);
      if(hourly>=5) throw new IllegalStateException("Too many OTP requests. Please try again later.");
    }

    String otp=demoSeed ? "123456" : String.format("%06d",random.nextInt(1_000_000));

    if(!demoSeed) {
      String providerUrl=System.getenv("APP_OTP_PROVIDER_URL");
      String providerToken=System.getenv("APP_OTP_PROVIDER_TOKEN");
      if(providerUrl==null || providerUrl.isBlank() || providerToken==null || providerToken.isBlank()) {
        throw new IllegalStateException("OTP provider is not configured");
      }
      try {
        var client=java.net.http.HttpClient.newHttpClient();
        var payload="{\"to\":\"" + normalized + "\",\"otp\":\"" + otp + "\",\"purpose\":\"" + purpose + "\"}";
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(providerUrl))
            .header("Authorization","Bearer "+providerToken)
            .header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
        var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()/100!=2) throw new IllegalStateException("OTP provider rejected request");
      } catch(Exception ex) {
        throw new IllegalStateException("Unable to deliver OTP");
      }
    }

    jdbc.update("""
      insert into otp_challenge(mobile_e164,purpose,code_hash,expires_at)
      values (?,?,?,now()+interval '5 minutes')
      """,normalized,purpose,hash(otp));

    return demoSeed ? otp : null;
  }

  @Transactional
  public AuthContext verifyOtp(String mobile,String otp,String purpose,String displayName) {
    return verifyOtp(mobile, otp, purpose, displayName, false, null);
  }

  @Transactional
  public AuthContext verifyOtp(String mobile,String otp,String purpose,String displayName,boolean firstAccess) {
    return verifyOtp(mobile, otp, purpose, displayName, firstAccess, null);
  }

  @Transactional
  public AuthContext verifyOtp(String mobile,String otp,String purpose,String displayName,boolean firstAccess,String newPassword) {
    String normalized=normalizeMobile(mobile);
    if(!"LOGIN".equals(purpose) && !"SIGNUP".equals(purpose) && !"STAFF_LOGIN".equals(purpose) && !"PASSWORD_RESET".equals(purpose))
      throw new IllegalArgumentException("Invalid OTP purpose");
    if("SIGNUP".equals(purpose) && (displayName==null || displayName.trim().length()<2 || displayName.trim().length()>120))
      throw new IllegalArgumentException("Parent name must be between 2 and 120 characters");
    if("SIGNUP".equals(purpose) || "PASSWORD_RESET".equals(purpose))
      validatePassword(newPassword, 8, "Password");

    var rows=jdbc.queryForList("""
      select id,code_hash,attempts,expires_at,purpose
      from otp_challenge
      where mobile_e164=? and purpose=? and consumed_at is null
      order by requested_at desc limit 1
      """,normalized,purpose);
    if(rows.isEmpty()) throw new IllegalArgumentException("OTP not found or expired");
    var row=rows.getFirst();
    if(!purpose.equals(String.valueOf(row.get("purpose"))))
      throw new IllegalArgumentException("OTP purpose mismatch");
    if(((Number)row.get("attempts")).intValue()>=5) throw new IllegalStateException("Too many OTP attempts");
    jdbc.update("update otp_challenge set attempts=attempts+1 where id=?",row.get("id"));
    Object expiresAt = row.get("expires_at");
    Instant expiresInstant = expiresAt instanceof java.sql.Timestamp ts
        ? ts.toInstant()
        : expiresAt instanceof java.time.OffsetDateTime odt
            ? odt.toInstant()
            : java.time.Instant.parse(String.valueOf(expiresAt));
    if(Instant.now().isAfter(expiresInstant)) {
      throw new IllegalArgumentException("OTP expired");
    }
    if(!hash(otp).equals(String.valueOf(row.get("code_hash")))) throw new IllegalArgumentException("Incorrect OTP");

    if ("STAFF_LOGIN".equals(purpose)) {
      List<Map<String,Object>> staffRows=jdbc.queryForList(
          "select id, active, last_login_at from staff_account where mobile_e164=?", normalized);
      Long staffId;
      if (!staffRows.isEmpty()) {
        Map<String,Object> staffRow=staffRows.getFirst();
        if (!Boolean.TRUE.equals(staffRow.get("active"))) {
          throw new SecurityException("This staff account is inactive. Contact an administrator.");
        }
        boolean activated=staffRow.get("last_login_at")!=null;
        if (firstAccess && activated) {
          throw new IllegalStateException("This staff account is already activated. Choose Sign in instead.");
        }
        if (!firstAccess && !activated) {
          throw new IllegalStateException("Your staff invitation is ready. Choose First-time access to activate it.");
        }
        staffId=((Number)staffRow.get("id")).longValue();
      } else if (adminMobiles.contains(normalized)) {
        if (!firstAccess) {
          throw new IllegalStateException("Use First-time access to set up the bootstrap administrator.");
        }
        String staffName=displayName==null||displayName.isBlank()?"Administrator":displayName.trim();
        // Do not mutate or revoke the separate customer identity. STAFF_LOGIN is
        // isolated by OTP purpose and receives a staff_id-only session.
        staffId=jdbc.queryForObject("""
          insert into staff_account(public_id,mobile_e164,display_name,role,active)
          values (?,?,?,'ADMIN',true) returning id
          """, Long.class, UUID.randomUUID(), normalized, staffName);
        jdbc.update("""
          insert into staff_permission_grant(staff_id,permission_key)
          select ?, permission_key from staff_permission_catalog
          on conflict(staff_id,permission_key) do nothing
          """, staffId);
      } else {
        throw new SecurityException("This mobile is not assigned to an active staff account.");
      }
      jdbc.update("update otp_challenge set consumed_at=now() where id=?", row.get("id"));
      return contextForStaff(staffId);
    }

    if ("PASSWORD_RESET".equals(purpose)) {
      List<Map<String,Object>> parentRows=jdbc.queryForList(
          "select id from user_account where mobile_e164=? and active=true and role='PARENT'", normalized);
      if(parentRows.isEmpty()) throw new SecurityException("Active parent account not found.");
      long parentId=((Number)parentRows.getFirst().get("id")).longValue();
      int changed=jdbc.update(
          "update user_account set password_hash=?,updated_at=now() where id=? and active=true and role='PARENT'",
          passwordEncoder.encode(newPassword), parentId);
      if(changed!=1) throw new SecurityException("Active parent account not found.");
      // A password reset invalidates every previously issued parent access/refresh token.
      jdbc.update("update auth_session set revoked_at=now() where user_id=? and revoked_at is null", parentId);
      jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
      return contextForUser(parentId);
    }

    // Login must not change a parent's activation or role. Only explicit admin
    // reactivation can restore a suspended account.
    if("LOGIN".equals(purpose)) {
      List<Map<String,Object>> activeParents=jdbc.queryForList(
          "select id from user_account where mobile_e164=? and role='PARENT' and active=true", normalized);
      if(activeParents.isEmpty()) {
        Boolean parentExists=jdbc.queryForObject(
            "select exists(select 1 from user_account where mobile_e164=? and role='PARENT')",
            Boolean.class, normalized);
        if(Boolean.TRUE.equals(parentExists))
          throw new SecurityException("This parent account is inactive. Contact support.");
        throw new IllegalArgumentException("No active parent account found for this mobile number. Please register first.");
      }
      long userId=((Number)activeParents.getFirst().get("id")).longValue();
      jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
      return contextForUser(userId);
    }

    // Signup is the only path that creates a parent identity.
    boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from user_account where mobile_e164=?)", Boolean.class, normalized));
    if(accountExists)
      throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
    String registrationPasswordHash=passwordEncoder.encode(newPassword);
    Long userId=jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role,password_hash)
      values (?,?,?,?,?) returning id
      """,Long.class,UUID.randomUUID(),normalized,displayName,"PARENT",registrationPasswordHash);
    jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
    return contextForUser(userId);
  }


  /** Username/password login for the isolated HRMS workspace. */
  @Transactional(noRollbackFor = {IllegalArgumentException.class, ResponseStatusException.class})
  public AuthContext loginHrms(String username, String password) {
    String normalized = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.matches("^[a-z0-9][a-z0-9._-]{2,63}$") || password == null || password.isBlank()) {
      throw new IllegalArgumentException("Username or password is incorrect.");
    }
    String identifierHash = hash("hrms-login:" + normalized);
    Long recentFailures = jdbc.queryForObject("""
        select count(*) from hrms_login_failure
        where identifier_hash=? and attempted_at>now()-interval '15 minutes'
        """, Long.class, identifierHash);
    if (recentFailures != null && recentFailures >= 5) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many sign-in attempts. Please wait 15 minutes before trying again.");
    }

    List<Map<String,Object>> rows = jdbc.queryForList("""
        select s.id, s.password_hash
        from staff_account s
        where lower(s.username)=? and s.active=true
          and (
            lower(s.username)='hrms_admin'
            or exists (
              select 1 from hr_employee he
              where he.linked_staff_account_id=s.id
                and he.hrms_access_enabled=true
                and he.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
            )
          )
        """, normalized);
    if (rows.isEmpty()) {
      // Match the normal BCrypt cost for unknown usernames to reduce account-enumeration timing.
      passwordEncoder.matches(password, "$2a$12$vv2074FPhWNMKb9HDsP9hu3Av11EyJX/Z8cjDAaCShvZWbrV1m2bK");
      recordHrmsLoginFailure(identifierHash);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }
    Object stored = rows.getFirst().get("password_hash");
    boolean validPassword = false;
    if (stored != null && !String.valueOf(stored).isBlank()) {
      try {
        validPassword = passwordEncoder.matches(password, String.valueOf(stored));
      } catch (IllegalArgumentException ignored) {
        validPassword = false;
      }
    } else {
      passwordEncoder.matches(password, "$2a$12$vv2074FPhWNMKb9HDsP9hu3Av11EyJX/Z8cjDAaCShvZWbrV1m2bK");
    }
    if (!validPassword) {
      recordHrmsLoginFailure(identifierHash);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }
    jdbc.update("delete from hrms_login_failure where identifier_hash=?", identifierHash);
    return contextForStaff(((Number) rows.getFirst().get("id")).longValue());
  }

  private void recordHrmsLoginFailure(String identifierHash) {
    jdbc.update("insert into hrms_login_failure(identifier_hash) values (?)", identifierHash);
  }

  public void requireEducationAccess(long staffId) {
    Boolean enabled = jdbc.queryForObject("""
        select exists(
          select 1 from staff_account
          where id=? and active=true and education_access_enabled=true and education_access_configured=true
        )
        """, Boolean.class, staffId);
    if (!Boolean.TRUE.equals(enabled)) {
      throw new SecurityException("Educational Admin access is not enabled for this account.");
    }
  }

  public boolean staffMustChangePassword(long staffId) {
    try {
      return Boolean.TRUE.equals(jdbc.queryForObject(
          "select must_change_password from staff_account where id=? and active=true",
          Boolean.class, staffId));
    } catch (EmptyResultDataAccessException ex) {
      return true;
    }
  }

  @Transactional
  public void changeHrmsPassword(long staffId, String currentPassword, String newPassword, String accessToken) {
    validatePassword(newPassword, 12, "New password");
    String currentHash;
    try {
      currentHash = jdbc.queryForObject(
          "select password_hash from staff_account where id=? and active=true",
          String.class, staffId);
    } catch (EmptyResultDataAccessException ex) {
      throw new SecurityException("Active HRMS account required.");
    }
    if (currentHash == null || !passwordEncoder.matches(
        currentPassword == null ? "" : currentPassword, currentHash)) {
      throw new SecurityException("Current password is incorrect.");
    }
    if (passwordEncoder.matches(newPassword, currentHash)) {
      throw new IllegalArgumentException("Choose a new password different from the current password.");
    }
    Long currentSessionId = null;
    if (accessToken != null && !accessToken.isBlank()) {
      List<Long> ids = jdbc.queryForList("""
          select id from auth_session
          where token_hash=? and staff_id=? and workspace_key='HRMS' and revoked_at is null
          """, Long.class, hash(accessToken), staffId);
      if (!ids.isEmpty()) currentSessionId = ids.getFirst();
    }
    int changed = jdbc.update("""
        update staff_account
        set password_hash=?, must_change_password=false, password_changed_at=now(), updated_at=now()
        where id=? and active=true
        """, passwordEncoder.encode(newPassword), staffId);
    if (changed != 1) throw new SecurityException("Active HRMS account required.");
    if (currentSessionId == null) {
      jdbc.update("update auth_session set revoked_at=now() where staff_id=? and workspace_key='HRMS' and revoked_at is null", staffId);
    } else {
      jdbc.update("""
          update auth_session set revoked_at=now()
          where staff_id=? and workspace_key='HRMS' and revoked_at is null and id<>?
          """, staffId, currentSessionId);
    }
  }

  private String passwordAttemptKey(String authType,String identifier) {
    return hash(authType+"|"+(identifier==null?"":identifier.trim().toLowerCase(java.util.Locale.ROOT)));
  }

  private void assertPasswordLoginAllowed(String authType,String identifier) {
    Long failures=jdbc.queryForObject("""
      select count(*) from account_login_failure
      where principal_hash=? and auth_type=? and attempted_at > now() - interval '15 minutes'
      """,Long.class,passwordAttemptKey(authType,identifier),authType);
    if(failures!=null && failures>=MAX_PASSWORD_LOGIN_FAILURES)
      throw new SecurityException("Too many sign-in attempts. Please try again in 15 minutes.");
  }

  private void recordPasswordLoginFailure(String authType,String identifier) {
    jdbc.update("delete from account_login_failure where attempted_at < now() - interval '24 hours'");
    jdbc.update("insert into account_login_failure(principal_hash,auth_type) values (?,?)",
        passwordAttemptKey(authType,identifier),authType);
  }

  private void clearPasswordLoginFailures(String authType,String identifier) {
    jdbc.update("delete from account_login_failure where principal_hash=? and auth_type=?",
        passwordAttemptKey(authType,identifier),authType);
  }

  /** Password-based parent login. OTP is not required for returning parent sessions. */
  public AuthContext loginParent(String mobile,String password) {
    String normalized=normalizeMobile(mobile);
    if(!(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit)))
      throw new IllegalArgumentException("Enter a valid 10-digit parent mobile number.");
    if(password==null || password.isBlank()) throw new IllegalArgumentException("Enter your password.");
    assertPasswordLoginAllowed("PARENT",normalized);
    List<Map<String,Object>> rows=jdbc.queryForList(
        "select id,password_hash from user_account where mobile_e164=? and active=true and role='PARENT'",
        normalized);
    if(rows.isEmpty()) {
      recordPasswordLoginFailure("PARENT",normalized);
      throw new IllegalArgumentException("Mobile number or password is incorrect.");
    }
    Map<String,Object> row=rows.getFirst();
    Object storedValue=row.get("password_hash");
    if(storedValue==null || String.valueOf(storedValue).isBlank())
      throw new IllegalStateException("A password has not been set for this account. Use Forgot password to set one.");
    if(!passwordEncoder.matches(password,String.valueOf(storedValue))) {
      recordPasswordLoginFailure("PARENT",normalized);
      throw new IllegalArgumentException("Mobile number or password is incorrect.");
    }
    clearPasswordLoginFailures("PARENT",normalized);
    return contextForUser(((Number)row.get("id")).longValue());
  }

  /** Student credentials are scoped to the parent mobile; direct student login never receives parent-account access. */
  public AuthContext loginStudentByParent(String parentMobile,String username,String password) {
    String normalized=normalizeMobile(parentMobile);
    if(!(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit)))
      throw new IllegalArgumentException("Enter a valid 10-digit parent mobile number.");
    String cleanUsername=validateUsername(username);
    if(password==null || password.isBlank()) throw new IllegalArgumentException("Enter your student password.");
    String attemptIdentity=normalized+"|"+cleanUsername;
    assertPasswordLoginAllowed("STUDENT",attemptIdentity);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select c.student_id,c.password_hash
      from user_account u
      join student_login_credential c on c.guardian_user_id=u.id and c.active=true
      join guardian_student gs on gs.guardian_user_id=u.id and gs.student_id=c.student_id
      join student st on st.id=c.student_id
      where u.mobile_e164=? and u.active=true and u.role='PARENT'
        and lower(c.username)=? and gs.active=true and gs.consent_status='CONSENTED'
        and st.active=true and st.environment='PRODUCTION'
      """,normalized,cleanUsername);
    if(rows.isEmpty()) {
      recordPasswordLoginFailure("STUDENT",attemptIdentity);
      throw new IllegalArgumentException("Parent mobile, student username or password is incorrect.");
    }
    Map<String,Object> row=rows.getFirst();
    String stored=String.valueOf(row.get("password_hash"));
    if(!passwordEncoder.matches(password,stored)) {
      recordPasswordLoginFailure("STUDENT",attemptIdentity);
      throw new IllegalArgumentException("Parent mobile, student username or password is incorrect.");
    }
    clearPasswordLoginFailures("STUDENT",attemptIdentity);
    return contextForStudent(null,((Number)row.get("student_id")).longValue());
  }

  @Transactional
  public void setChildCredentials(long parentUserId,long studentId,String username,String password) {
    requireParent(parentUserId);
    if(!hasGuardianAccess(parentUserId,studentId)) throw new SecurityException("Child access not granted");
    String cleanUsername=validateUsername(username);
    validatePassword(password,8,"Student password");
    try {
      jdbc.update("""
        insert into student_login_credential(guardian_user_id,student_id,username,password_hash,active,updated_at)
        values (?,?,?,?,true,now())
        on conflict(guardian_user_id,student_id) do update
          set username=excluded.username,password_hash=excluded.password_hash,active=true,updated_at=now()
        """,parentUserId,studentId,cleanUsername,passwordEncoder.encode(password));
      // Credential rotation invalidates every already-issued student access/refresh token,
      // without revoking the parent's separate session.
      jdbc.update("update auth_session set revoked_at=now() where student_id=? and revoked_at is null", studentId);
      auditChildEvent(parentUserId,studentId,"CREDENTIALS_CHANGED","{\"credential\":\"student-login\"}");
    } catch(DuplicateKeyException ex) {
      throw new IllegalArgumentException("That username is already used by another child on this parent account. Choose a different username.");
    }
  }

  @Transactional
  public AuthContext createChild(long userId,String name,String classCode,String language,String username,String password,
      boolean consentAccepted,List<String> trackCodes) {
    String cleanUsername=validateUsername(username);
    validatePassword(password,8,"Student password");
    String legacyPin=String.format(java.util.Locale.ROOT,"%06d",random.nextInt(1_000_000));
    AuthContext created=createChild(userId,name,classCode,language,legacyPin,consentAccepted,trackCodes);
    setChildCredentials(userId,created.studentId(),cleanUsername,password);
    return created;
  }

  private String validateUsername(String username) {
    String clean=username==null?"":username.trim().toLowerCase(java.util.Locale.ROOT);
    if(!clean.matches("^[a-z0-9][a-z0-9._-]{2,31}$"))
      throw new IllegalArgumentException("Username must be 3–32 characters and use letters, numbers, dots, underscores or hyphens.");
    return clean;
  }

  private void validatePassword(String password,int minimumLength,String label) {
    if(password==null) throw new IllegalArgumentException(label+" must be at least "+minimumLength+" characters.");
    int bytes=password.getBytes(StandardCharsets.UTF_8).length;
    if(password.length()<minimumLength || bytes>72 || password.isBlank())
      throw new IllegalArgumentException(label+" must be at least "+minimumLength+" characters and no more than 72 UTF-8 bytes.");
  }

  @Transactional
  public AuthContext createChild(long userId,String name,String classCode,String language,String pin,
      boolean consentAccepted,List<String> trackCodes) {
    if(!consentAccepted) throw new SecurityException("Guardian consent is required before creating a student profile");
    requireParent(userId);
    List<Map<String,Object>> parentRows=jdbc.queryForList(
        "select id from user_account where id=? and active=true and role in ('PARENT','ADMIN') for update",userId);
    if(parentRows.isEmpty()) throw new SecurityException("Active parent account required");
    long activeChildren=jdbc.queryForObject("""
      select count(distinct st.id) from guardian_student gs join student st on st.id=gs.student_id
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED'
        and st.active=true and st.environment='PRODUCTION'
      """,Long.class,userId);
    int childLimit=Math.max(1,maxChildrenPerParent);
    if(activeChildren>=childLimit)
      throw new IllegalStateException("This parent account has reached its child profile limit ("+childLimit+"). Contact support to increase it.");
    if(name==null || name.trim().length()<2 || name.trim().length()>120)
      throw new IllegalArgumentException("Student name must be between 2 and 120 characters");
    if(classCode==null || classCode.isBlank()) throw new IllegalArgumentException("Class selection is required");
    String cleanLanguage=language==null?"":language.trim().toLowerCase(java.util.Locale.ROOT);
    if(!List.of("hi","en").contains(cleanLanguage))
      throw new IllegalArgumentException("Learning language must be Hindi ('hi') or English ('en').");
    if(pin==null || pin.length()<4 || pin.length()>8 || pin.chars().anyMatch(ch -> !Character.isDigit(ch)))
      throw new IllegalArgumentException("Student access PIN must be 4-8 digits");
    String board;
    try {
      board=jdbc.queryForObject("select board from curriculum_class where code=? and active=true",String.class,classCode);
    } catch(Exception ex) {
      throw new IllegalArgumentException("Invalid class selection");
    }
    if(board==null || board.isBlank()) throw new IllegalArgumentException("Invalid class selection");
    Long id=jdbc.queryForObject("""
      insert into student(public_id,display_name,class_code,board,language,environment,access_pin_hash,access_pin_set_at)
      values (?,?,?,?,?,'PRODUCTION',?,now()) returning id
      """,Long.class,UUID.randomUUID(),name.trim(),classCode,board,cleanLanguage,hash(pin));
    jdbc.update("""
      insert into guardian_student
        (guardian_user_id,student_id,relationship,consent_status,consent_version,consent_source,consented_by)
      values (?,?,'PARENT','CONSENTED',?,'PARENT_PORTAL',?)
      """,userId,id,GUARDIAN_CONSENT_VERSION,userId);
    recordGuardianStudentEvent(userId,id,"CONSENT_ACCEPTED",GUARDIAN_CONSENT_VERSION,
        null,classCode,"{\"consent\":\"affirmative-checkbox\",\"source\":\"PARENT_PORTAL\"}");
    applyStudentTracks(id,classCode,trackCodes);
    return contextForStudent(userId,id);
  }

  @Transactional
  public void updateChildProfile(long parentUserId,long studentId,String displayName,String classCode,String language) {
    requireParent(parentUserId);
    String name=displayName==null?"":displayName.trim();
    if(name.length()<2 || name.length()>120 || name.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Student name must be between 2 and 120 characters.");
    String nextClass=classCode==null?"":classCode.trim();
    if(nextClass.isBlank()) throw new IllegalArgumentException("Choose a class.");
    String nextLanguage=language==null?"":language.trim().toLowerCase(java.util.Locale.ROOT);
    if(!List.of("hi","en").contains(nextLanguage))
      throw new IllegalArgumentException("Choose Hindi or English as the learning-content language.");

    Map<String,Object> current;
    try {
      current=jdbc.queryForMap("""
        select st.class_code,gs.consent_version
        from guardian_student gs join student st on st.id=gs.student_id
        where gs.guardian_user_id=? and gs.student_id=? and gs.active=true
          and gs.consent_status='CONSENTED' and st.active=true and st.environment='PRODUCTION'
        for update of st
        """,parentUserId,studentId);
    } catch(EmptyResultDataAccessException ex) {
      throw new SecurityException("Child access denied");
    }
    String oldClass=String.valueOf(current.get("class_code"));
    boolean classChanged=!oldClass.equals(nextClass);
    if(classChanged && !(("6".equals(oldClass) && "7".equals(nextClass))
        || ("7".equals(oldClass) && "8".equals(nextClass)))) {
      throw new IllegalArgumentException("Class promotion must follow the supported path: Class 6 → 7 → 8. Previous learning records are preserved.");
    }
    Map<String,Object> targetClass;
    try {
      targetClass=jdbc.queryForMap(
          "select code,board from curriculum_class where code=? and active=true",nextClass);
    } catch(EmptyResultDataAccessException ex) {
      throw new IllegalArgumentException("This class is not currently available.");
    }
    String board=String.valueOf(targetClass.get("board"));
    if(board.isBlank() || "null".equalsIgnoreCase(board))
      throw new IllegalArgumentException("This class is not currently available.");

    if(classChanged) {
      List<String> activeTracks=jdbc.queryForList("""
        select s.code from student_track_enrollment ste
        join curriculum_subject s on s.id=ste.subject_id
        where ste.student_id=? and ste.status='ACTIVE' order by s.sort_order
        """,String.class,studentId);
      if(activeTracks.isEmpty())
        throw new IllegalStateException("Select at least one learning subject before promoting this profile.");
      applyStudentTracks(studentId,nextClass,activeTracks);
    }
    int changed=jdbc.update("""
      update student set display_name=?,class_code=?,board=?,language=?,profile_updated_at=now()
      where id=? and active=true and environment='PRODUCTION'
      """,name,nextClass,board,nextLanguage,studentId);
    if(changed!=1) throw new SecurityException("Active student profile not found.");
    String version=current.get("consent_version")==null?"legacy-unversioned":String.valueOf(current.get("consent_version"));
    recordGuardianStudentEvent(parentUserId,studentId,classChanged?"CLASS_PROMOTED":"PROFILE_UPDATED",
        version,oldClass,nextClass,"{\"fields\":[\"displayName\",\"classCode\",\"language\"]}");
  }

  /** Append-only child lifecycle audit; never pass credentials or other secrets in details. */
  void auditChildEvent(long guardianUserId,long studentId,String eventType,String details) {
    Map<String,Object> row=jdbc.queryForMap("""
      select gs.consent_version,st.class_code
      from guardian_student gs join student st on st.id=gs.student_id
      where gs.guardian_user_id=? and gs.student_id=?
      """,guardianUserId,studentId);
    String version=row.get("consent_version")==null?"legacy-unversioned":String.valueOf(row.get("consent_version"));
    String currentClass=row.get("class_code")==null?null:String.valueOf(row.get("class_code"));
    recordGuardianStudentEvent(guardianUserId,studentId,eventType,version,currentClass,currentClass,
        details==null?"{}":details);
  }

  private void recordGuardianStudentEvent(long guardianUserId,long studentId,String eventType,String consentVersion,
      String oldClassCode,String newClassCode,String details) {
    jdbc.update("""
      insert into guardian_student_audit
        (guardian_user_id,student_id,event_type,consent_version,actor_user_id,old_class_code,new_class_code,details)
      values (?,?,?,?,?,?,?,?::jsonb)
      """,guardianUserId,studentId,eventType,consentVersion,guardianUserId,oldClassCode,newClassCode,details);
  }

  @Transactional
  public List<String> updateStudentTracks(long studentId,List<String> trackCodes) {
    Map<String,Object> row;
    try {
      row=jdbc.queryForMap("select class_code from student where id=? and active=true and environment='PRODUCTION' for update",studentId);
    } catch(EmptyResultDataAccessException ex) {
      throw new IllegalArgumentException("Active production student not found");
    }
    return applyStudentTracks(studentId,String.valueOf(row.get("class_code")),trackCodes);
  }

  private List<String> applyStudentTracks(long studentId,String classCode,List<String> requested) {
    List<String> selected=requested==null
        ? List.of("maths","science")
        : requested.stream().map(v->v==null?"":v.trim().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
    if(selected.isEmpty() || selected.size()>2 || selected.stream().anyMatch(v->!List.of("maths","science").contains(v)))
      throw new IllegalArgumentException("Choose one or two valid subjects: Maths and/or Science");
    Long classExists=jdbc.queryForObject("select count(*) from curriculum_class where code=? and active=true",Long.class,classCode);
    if(classExists==null || classExists==0) throw new IllegalArgumentException("Invalid class selection");
    jdbc.update("update student_track_enrollment set status='ENDED',updated_at=now() where student_id=? and status='ACTIVE'",studentId);
    for(String code:selected) {
      List<Long> subjects=jdbc.queryForList("""
        select s.id from curriculum_subject s join curriculum_class c on c.id=s.class_id
        where c.code=? and c.active=true and s.code=? and s.active=true
        """,Long.class,classCode,code);
      if(subjects.isEmpty()) throw new IllegalArgumentException("The "+code+" track is not available for class "+classCode);
      jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,status)
        values (?,?,'ACTIVE')
        on conflict(student_id,subject_id) do update set status='ACTIVE',updated_at=now()
        """,studentId,subjects.getFirst());
    }
    return selected;
  }

  /** Parent dashboards are read-only; a parent must never become a child session. */
  public AuthContext selectStudent(long userId,long studentId) {
    throw new SecurityException("Parents can view child reports but cannot enter a child's learning account.");
  }

  /** Legacy PIN sign-in is disabled; student credentials must be provisioned by a parent. */
  public AuthContext loginStudent(String publicId,String pin) {
    throw new SecurityException("Student login requires a parent-created username and password.");
  }

  public AuthContext previewLogin() {
    if(!demoSeed) throw new IllegalStateException("Preview login is disabled");
    Long id=jdbc.queryForObject("select id from student where environment='LOCAL_PREVIEW' and active=true order by id limit 1",Long.class);
    if(id==null) throw new IllegalStateException("Preview student unavailable");
    return contextForStudent(null,id);
  }

  public AuthContext current(String token) {
    return current(token, null);
  }

  /** Resolves a session only when it belongs to the requested portal. */
  public AuthContext currentForWorkspace(String token, String workspace) {
    return current(token, normalizeWorkspace(workspace));
  }

  private AuthContext current(String token, String workspace) {
    if(token==null || token.isBlank()) return null;
    String scope = workspacePredicate(workspace, "s");
    var rows=jdbc.queryForList("""
      select s.user_id,s.student_id,s.staff_id,s.workspace_key,
             u.role,u.display_name,st.display_name as student_name,
             e.role as staff_role,e.display_name as staff_name
      from auth_session s
      left join user_account u on u.id=s.user_id
      left join student st on st.id=s.student_id
      left join staff_account e on e.id=s.staff_id and e.active=true
      where s.token_hash=? and s.revoked_at is null and s.expires_at>now()
        and s.absolute_expires_at>now()
        and s.last_seen_at>now()-(case when s.workspace_key in ('HRMS','STAFF','EDUCATION_ADMIN') then interval '30 minutes' else interval '4 hours' end)
        and (s.refresh_expires_at is null or s.refresh_expires_at>now())
        and (s.user_id is null or u.active=true)
        and (s.student_id is null or st.active=true)
        and (s.staff_id is null or e.id is not null)
        and (s.workspace_key <> 'HRMS'
          or exists (select 1 from staff_account sa where sa.id=s.staff_id
              and lower(sa.username)='hrms_admin' and sa.active=true)
          or exists (
            select 1 from hr_employee he where he.linked_staff_account_id=s.staff_id
              and he.hrms_access_enabled=true
              and he.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
          ))
        and (s.workspace_key not in ('STAFF','EDUCATION_ADMIN')
          or exists (select 1 from staff_account education_sa
              where education_sa.id=s.staff_id and education_sa.active=true
                and education_sa.education_access_enabled=true
                and education_sa.education_access_configured=true))
      """ + scope,hash(token));
    if(rows.isEmpty()) return null;
    var r=rows.getFirst();
    Long userId=r.get("user_id")==null?null:((Number)r.get("user_id")).longValue();
    Long studentId=r.get("student_id")==null?null:((Number)r.get("student_id")).longValue();
    Long staffId=r.get("staff_id")==null?null:((Number)r.get("staff_id")).longValue();
    // Legacy parent-impersonation sessions stored both identities. Revoke them rather than
    // letting an old browser cookie keep acting as a child after the access model changes.
    if(userId!=null && studentId!=null) {
      jdbc.update("update auth_session set revoked_at=now() where token_hash=? and revoked_at is null",hash(token));
      return null;
    }
    // Keep this guard in Java as well as in SQL so tests and future query changes cannot
    // accidentally let a staff cookie authenticate a learner request (or the reverse).
    if (!workspaceMatches(userId, studentId, staffId, String.valueOf(r.get("workspace_key")), workspace)) return null;
    jdbc.update("""
      update auth_session set last_seen_at=now(),
        refresh_expires_at=case when refresh_token_hash is not null
          then now()+make_interval(hours => ?) else refresh_expires_at end
      where token_hash=? and revoked_at is null
      """,sessionHours,hash(token));
    String name=studentId!=null?String.valueOf(r.get("student_name")):
        staffId!=null?String.valueOf(r.get("staff_name")):
        (r.get("display_name")==null?null:String.valueOf(r.get("display_name")));
    String role=studentId!=null?"STUDENT":
        staffId!=null?String.valueOf(r.get("staff_role")):String.valueOf(r.get("role"));
    String effectiveWorkspace = workspace == null ? String.valueOf(r.get("workspace_key")) : workspace;
    return new AuthContext(userId,studentId,staffId,role,name,effectiveWorkspace);
  }

  public record SessionTokens(String accessToken,String refreshToken,AuthContext context) {}
  public static final long ACCESS_TOKEN_MINUTES=15;

  /** Legacy token helper retained for compatibility. Web sign-ins use issueSession. */
  public String issueToken(AuthContext context) {
    String raw=randomToken();
    String workspace = workspaceForContext(context);
    Instant now = Instant.now();
    Instant absoluteExpiry = ("LEARNING".equals(workspace) || "PARENT".equals(workspace) || "STUDENT".equals(workspace))
        ? now.plus(Duration.ofDays(Math.max(1, learningAbsoluteDays)))
        : now.plus(Duration.ofHours(Math.max(1, staffAbsoluteHours)));
    jdbc.update("""
      insert into auth_session(token_hash,user_id,student_id,staff_id,expires_at,workspace_key,absolute_expires_at)
      values (?,?,?,?,?,?,?)
      """,hash(raw),context.userId(),context.studentId(),context.staffId(),
      java.sql.Timestamp.from(now.plus(Duration.ofHours(sessionHours))), workspace,
      java.sql.Timestamp.from(absoluteExpiry));
    return raw;
  }

  /** Issue short-lived access plus independently stored refresh credentials in one application scope. */
  @Transactional
  public SessionTokens issueSession(AuthContext context) {
    return issueSession(context, workspaceForContext(context));
  }

  @Transactional
  public SessionTokens issueSession(AuthContext context, String requestedWorkspace) {
    if (context == null) throw new SecurityException("Authentication required.");
    String workspace = normalizeWorkspace(requestedWorkspace);
    if (workspace == null) workspace = workspaceForContext(context);
    boolean staffWorkspace = "STAFF".equals(workspace)
        || "EDUCATION_ADMIN".equals(workspace) || "HRMS".equals(workspace);
    boolean learnerWorkspace = "LEARNING".equals(workspace)
        || "PARENT".equals(workspace) || "STUDENT".equals(workspace);
    if (context.isEmployee() != staffWorkspace || (!context.isEmployee() && !learnerWorkspace)) {
      throw new SecurityException("This identity cannot create a session in the requested application.");
    }
    if ("PARENT".equals(workspace) && (!context.isParent() || context.studentId()!=null)) {
      throw new SecurityException("A parent identity is required for the parent application.");
    }
    if ("STUDENT".equals(workspace) && (!context.isStudent() || context.userId()!=null)) {
      throw new SecurityException("A student identity is required for the student application.");
    }
    Instant now=Instant.now();
    String access=randomToken(), refresh=randomToken();
    Instant absoluteExpiry = ("LEARNING".equals(workspace) || "PARENT".equals(workspace) || "STUDENT".equals(workspace))
        ? now.plus(Duration.ofDays(Math.max(1, learningAbsoluteDays)))
        : now.plus(Duration.ofHours(Math.max(1, staffAbsoluteHours)));
    // HRMS intentionally supports one active device/session per staff identity. This
    // revocation is scoped to HRMS and never touches Education Admin or learner sessions.
    if ("HRMS".equals(workspace)) {
      // Serialize concurrent logins for this staff identity. The partial unique index below
      // remains a second line of defence if another write path is introduced later.
      jdbc.queryForObject("select id from staff_account where id=? for update", Long.class, context.staffId());
      jdbc.update("""
          update auth_session set revoked_at=now()
          where staff_id=? and workspace_key='HRMS' and revoked_at is null
          """, context.staffId());
    }
    jdbc.update("""
      insert into auth_session(
        token_hash,refresh_token_hash,user_id,student_id,staff_id,
        expires_at,refresh_expires_at,workspace_key,absolute_expires_at)
      values (?,?,?,?,?,?,?,?,?)
      """,hash(access),hash(refresh),context.userId(),context.studentId(),context.staffId(),
      java.sql.Timestamp.from(now.plus(Duration.ofMinutes(ACCESS_TOKEN_MINUTES))),
      java.sql.Timestamp.from(now.plus(Duration.ofHours(sessionHours))),
      workspace, java.sql.Timestamp.from(absoluteExpiry));
    return new SessionTokens(access,refresh,context);
  }

  /** Rotate the refresh token and the access token under a row lock. */
  @Transactional
  public SessionTokens refreshSession(String refreshToken) {
    return refreshSession(refreshToken, null);
  }

  /** Rotate credentials without allowing a refresh cookie from one portal into the other. */
  @Transactional
  public SessionTokens refreshSession(String refreshToken, String workspace) {
    workspace = normalizeWorkspace(workspace);
    if(refreshToken==null || refreshToken.isBlank()) throw new SecurityException("Session expired. Please sign in again.");
    String query = """
      select s.id
      from auth_session s
      left join user_account u on u.id=s.user_id and u.active=true
      left join student st on st.id=s.student_id and st.active=true
      left join staff_account e on e.id=s.staff_id and e.active=true
      where s.refresh_token_hash=? and s.revoked_at is null and s.refresh_expires_at>now()
        and s.absolute_expires_at>now()
        and s.last_seen_at>now()-(case when s.workspace_key in ('HRMS','STAFF','EDUCATION_ADMIN') then interval '30 minutes' else interval '4 hours' end)
        and (s.user_id is null or u.id is not null)
        and (s.student_id is null or st.id is not null)
        and (s.staff_id is null or e.id is not null)
        and (s.workspace_key <> 'HRMS'
          or exists (select 1 from staff_account sa where sa.id=s.staff_id
              and lower(sa.username)='hrms_admin' and sa.active=true)
          or exists (
            select 1 from hr_employee he where he.linked_staff_account_id=s.staff_id
              and he.hrms_access_enabled=true
              and he.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
          ))
        and (s.workspace_key not in ('STAFF','EDUCATION_ADMIN')
          or exists (select 1 from staff_account education_sa
              where education_sa.id=s.staff_id and education_sa.active=true
                and education_sa.education_access_enabled=true
                and education_sa.education_access_configured=true))
      """ + workspacePredicate(workspace, "s") + "\n      for update of s";
    var rows=jdbc.queryForList(query,hash(refreshToken));
    if(rows.isEmpty()) throw new SecurityException("Session expired. Please sign in again.");
    long sessionId=((Number)rows.getFirst().get("id")).longValue();
    String access=randomToken(), nextRefresh=randomToken();
    Instant now=Instant.now();
    int changed=jdbc.update("""
      update auth_session set token_hash=?,refresh_token_hash=?,expires_at=?,refresh_expires_at=?,last_seen_at=now()
      where id=? and revoked_at is null
      """,hash(access),hash(nextRefresh),
      java.sql.Timestamp.from(now.plus(Duration.ofMinutes(ACCESS_TOKEN_MINUTES))),
      java.sql.Timestamp.from(now.plus(Duration.ofHours(sessionHours))),sessionId);
    if(changed!=1) throw new SecurityException("Session expired. Please sign in again.");
    AuthContext context=current(access, workspace);
    if(context==null) throw new SecurityException("Session expired. Please sign in again.");
    return new SessionTokens(access,nextRefresh,context);
  }

  public void revoke(String token) {
    if(token!=null && !token.isBlank()) jdbc.update("update auth_session set revoked_at=now() where token_hash=? and revoked_at is null",hash(token));
  }

  public void revoke(String token,String refreshToken) {
    revoke(token);
    if(refreshToken!=null && !refreshToken.isBlank())
      jdbc.update("update auth_session set revoked_at=now() where refresh_token_hash=? and revoked_at is null",hash(refreshToken));
  }

  /**
   * Revokes only the specified portal's sessions. The legacy shared cookies can still
   * exist during migration, so token values alone are not enough to decide what to log out.
   * Returns the number of active session rows revoked.
   */
  @Transactional
  public int revokeForWorkspace(String token, String refreshToken, String workspace) {
    String normalized = normalizeWorkspace(workspace);
    if (normalized == null) {
      revoke(token, refreshToken);
      return 0;
    }
    String scope = workspacePredicate(normalized, null);
    int changed = 0;
    if (token != null && !token.isBlank()) {
      changed += jdbc.update("update auth_session set revoked_at=now() where token_hash=? and revoked_at is null"
          + scope, hash(token));
    }
    if (refreshToken != null && !refreshToken.isBlank()) {
      changed += jdbc.update("update auth_session set revoked_at=now() where refresh_token_hash=? and revoked_at is null"
          + scope, hash(refreshToken));
    }
    return changed;
  }

  public boolean hasGuardianAccess(long userId,long studentId) {
    return jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,userId,studentId);
  }

  public AuthContext contextForUser(long userId) {
    var r=jdbc.queryForMap("select role,display_name from user_account where id=? and active=true",userId);
    return new AuthContext(userId,null,String.valueOf(r.get("role")),r.get("display_name")==null?null:String.valueOf(r.get("display_name")));
  }

  public AuthContext contextForStaff(long staffId) {
    Map<String,Object> row=jdbc.queryForMap(
        "select role, display_name from staff_account where id=? and active=true", staffId);
    jdbc.update("update staff_account set last_login_at=now() where id=?", staffId);
    return new AuthContext(null, null, staffId, String.valueOf(row.get("role")),
        row.get("display_name")==null?null:String.valueOf(row.get("display_name")));
  }

  public List<String> permissionsForStaff(long staffId) {
    return permissionsForStaff(staffId, null);
  }

  public List<String> permissionsForStaff(long staffId, String workspace) {
    String role=jdbc.queryForObject(
        "select role from staff_account where id=?", String.class, staffId);
    // Global ADMIN semantics apply only to the education workspace. HRMS permissions are
    // explicit and remain least-privilege even if a staff identity has an education role.
    String permissionWorkspace = "HRMS".equals(normalizeWorkspace(workspace)) ? "HRMS" : "EDUCATION_ADMIN";
    if ("ADMIN".equals(role) && "EDUCATION_ADMIN".equals(permissionWorkspace)) {
      return jdbc.queryForList("""
          select permission_key from staff_permission_catalog
          where permission_key not like 'HR\\_%' escape '\\'
            and permission_key not in ('HR_SELF_SERVICE_VIEW','TEACHING_ASSIGNMENT_VIEW','CLASS_SCHEDULE_POLICY_MANAGE')
          order by permission_key
          """, String.class);
    }
    return jdbc.queryForList("""
        select effective.permission_key
        from (
          select g.permission_key
          from staff_permission_grant g
          join staff_permission_catalog p on p.permission_key=g.permission_key
          where g.staff_id=? and g.workspace_key=?
          union
          select rp.permission_key
          from staff_additional_role ar
          join staff_role_permission rp on rp.role_code=ar.role_code
          join staff_permission_catalog p on p.permission_key=rp.permission_key
          where ar.staff_id=? and rp.workspace_key=?
        ) effective
        order by effective.permission_key
        """, String.class, staffId, permissionWorkspace, staffId, permissionWorkspace);
  }

  public AuthContext contextForStudent(Long userId,long studentId) {
    String name=jdbc.queryForObject("select display_name from student where id=?",String.class,studentId);
    return new AuthContext(userId,studentId,"STUDENT",name);
  }

  private void requireParent(long userId) {
    List<String> roles=jdbc.queryForList("select role from user_account where id=? and active=true",String.class,userId);
    if(roles.isEmpty() || (!"PARENT".equals(roles.getFirst()) && !"ADMIN".equals(roles.getFirst())))
      throw new SecurityException("Active parent access required");
  }

  private String workspacePredicate(String workspace, String alias) {
    String normalized = normalizeWorkspace(workspace);
    String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
    if ("HRMS".equals(normalized)) {
      return "\n        and " + prefix + "workspace_key='HRMS'"
          + " and " + prefix + "staff_id is not null"
          + " and " + prefix + "user_id is null"
          + " and " + prefix + "student_id is null";
    }
    if ("STAFF".equals(normalized) || "EDUCATION_ADMIN".equals(normalized)) {
      return "\n        and " + prefix + "workspace_key in ('STAFF','EDUCATION_ADMIN')"
          + " and " + prefix + "staff_id is not null"
          + " and " + prefix + "user_id is null"
          + " and " + prefix + "student_id is null";
    }
    if ("PARENT".equals(normalized)) {
      return "\n        and " + prefix + "workspace_key in ('PARENT','LEARNING')"
          + " and " + prefix + "staff_id is null"
          + " and " + prefix + "user_id is not null"
          + " and " + prefix + "student_id is null";
    }
    if ("STUDENT".equals(normalized)) {
      return "\n        and " + prefix + "workspace_key in ('STUDENT','LEARNING')"
          + " and " + prefix + "staff_id is null"
          + " and " + prefix + "user_id is null"
          + " and " + prefix + "student_id is not null";
    }
    if ("LEARNING".equals(normalized)) {
      return "\n        and " + prefix + "workspace_key='LEARNING'"
          + " and " + prefix + "staff_id is null";
    }
    return "";
  }

  private boolean workspaceMatches(Long userId, Long studentId, Long staffId, String sessionWorkspace, String workspace) {
    if (workspace == null) return true;
    if ("HRMS".equals(workspace)) return "HRMS".equals(sessionWorkspace)
        && staffId != null && userId == null && studentId == null;
    if ("STAFF".equals(workspace) || "EDUCATION_ADMIN".equals(workspace)) {
      return ("STAFF".equals(sessionWorkspace) || "EDUCATION_ADMIN".equals(sessionWorkspace))
          && staffId != null && userId == null && studentId == null;
    }
    if ("PARENT".equals(workspace)) {
      return ("PARENT".equals(sessionWorkspace) || "LEARNING".equals(sessionWorkspace))
          && staffId == null && userId != null && studentId == null;
    }
    if ("STUDENT".equals(workspace)) {
      return ("STUDENT".equals(sessionWorkspace) || "LEARNING".equals(sessionWorkspace))
          && staffId == null && userId == null && studentId != null;
    }
    if ("LEARNING".equals(workspace)) {
      return "LEARNING".equals(sessionWorkspace) && staffId == null
          && (userId != null || studentId != null);
    }
    return false;
  }

  private String hash(String value) {
    try {
      var md=MessageDigest.getInstance("SHA-256");
      return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch(Exception ex) { throw new IllegalStateException(ex); }
  }

  private String randomToken() {
    byte[] bytes=new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
