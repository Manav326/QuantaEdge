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
  public static final String INSTITUTION_COOKIE = "QE_INSTITUTION_SESSION";
  public static final String INSTITUTION_REFRESH_COOKIE = "QE_INSTITUTION_REFRESH";
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
      case "INSTITUTION" -> "INSTITUTION";
      default -> null;
    };
  }

  public static String sessionCookieForWorkspace(String workspace) {
    String normalized = normalizeWorkspace(workspace);
    if ("HRMS".equals(normalized)) return HRMS_COOKIE;
    if ("INSTITUTION".equals(normalized)) return INSTITUTION_COOKIE;
    if ("STAFF".equals(normalized) || "EDUCATION_ADMIN".equals(normalized)) return STAFF_COOKIE;
    if ("PARENT".equals(normalized)) return "QE_PARENT_SESSION";
    if ("STUDENT".equals(normalized)) return "QE_STUDENT_SESSION";
    return LEARNING_COOKIE;
  }

  public static String refreshCookieForWorkspace(String workspace) {
    String normalized = normalizeWorkspace(workspace);
    if ("HRMS".equals(normalized)) return HRMS_REFRESH_COOKIE;
    if ("INSTITUTION".equals(normalized)) return INSTITUTION_REFRESH_COOKIE;
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

  public int getLearningSessionDays() { return Math.max(1, learningAbsoluteDays); }

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
    return requestOtp(mobile,purpose,null);
  }

  /** New account creation must bind the OTP to the institution selected at signup. */
  public String requestOtp(String mobile,String purpose,Long institutionId) {
    if(!"LOGIN".equals(purpose) && !"SIGNUP".equals(purpose) && !"STAFF_LOGIN".equals(purpose)
        && !"PASSWORD_RESET".equals(purpose) && !"ACCOUNT_SIGNUP".equals(purpose))
      throw new IllegalArgumentException("Invalid OTP purpose");
    if(("SIGNUP".equals(purpose) || "ACCOUNT_SIGNUP".equals(purpose))
        && (institutionId==null || institutionId.longValue()<1)) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before requesting signup verification.");
    }
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
      boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=? and role in ('PARENT','LEARNER') and active=true)",
          Boolean.class, normalized));
      if(!accountExists) throw new IllegalArgumentException("No active account found for this mobile number.");
    } else if ("SIGNUP".equals(purpose) || "ACCOUNT_SIGNUP".equals(purpose)) {
      // Prevent duplicate customer identities independently of a matching staff identity.
      boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=?)", Boolean.class, normalized));
      if(accountExists)
        throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
    } else {
      boolean activeAccountExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=? and role in ('PARENT','LEARNER') and active=true)",
          Boolean.class, normalized));
      if(!activeAccountExists) {
        boolean inactiveAccountExists=Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from user_account where mobile_e164=? and role in ('PARENT','LEARNER'))",
            Boolean.class, normalized));
        if(inactiveAccountExists) throw new SecurityException("This account is inactive. Contact support.");
        throw new IllegalArgumentException("No active account found for this mobile number. Please sign up first.");
      }
    }

    return deliverOtp(normalized,purpose,null,institutionId);
  }

  /** OTP delivery shared by public sign-in and authenticated learner account conversion. */
  private String deliverOtp(String normalized,String purpose,Long studentId) {
    return deliverOtp(normalized,purpose,studentId,null);
  }

  private String deliverOtp(String normalized,String purpose,Long studentId,Long institutionId) {
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
      if(providerUrl==null || providerUrl.isBlank() || providerToken==null || providerToken.isBlank())
        throw new IllegalStateException("OTP provider is not configured");
      try {
        var client=java.net.http.HttpClient.newHttpClient();
        var payload="{\"to\":\"" + normalized + "\",\"otp\":\"" + otp + "\",\"purpose\":\"" + purpose + "\"}";
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(providerUrl))
            .header("Authorization","Bearer "+providerToken)
            .header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
        var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()/100!=2) throw new IllegalStateException("OTP provider rejected request");
      } catch(Exception ex) { throw new IllegalStateException("Unable to deliver OTP"); }
    }
    if(studentId==null) {
      jdbc.update("""
        insert into otp_challenge(mobile_e164,purpose,code_hash,expires_at,institution_id)
        values (?,?,?,now()+interval '5 minutes',?)
        """,normalized,purpose,hash(otp),institutionId);
    } else {
      jdbc.update("""
        insert into otp_challenge(mobile_e164,purpose,code_hash,expires_at,student_id,institution_id)
        values (?,?,?,now()+interval '5 minutes',?,?)
        """,normalized,purpose,hash(otp),studentId,institutionId);
    }
    return demoSeed ? otp : null;
  }

  /** Only a staff-verified adult can request the independent-account conversion OTP. */
  public String requestStudentTransitionOtp(long studentId,String mobile) {
    String normalized=normalizeMobile(mobile);
    if(!(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit)))
      throw new IllegalArgumentException("Enter a valid Indian mobile number.");
    Long eligible=jdbc.queryForObject("""
      select count(*) from student st
      join student_adult_verification av on av.student_id=st.id
      where st.id=? and st.active=true and st.environment='PRODUCTION'
        and st.user_account_id is null and av.status='VERIFIED' and av.expires_at>now()
      """,Long.class,studentId);
    if(eligible==null || eligible==0)
      throw new SecurityException("Verified adult eligibility is required before independent account conversion.");
    Boolean accountExists=jdbc.queryForObject(
        "select exists(select 1 from user_account where mobile_e164=?)",Boolean.class,normalized);
    if(Boolean.TRUE.equals(accountExists))
      throw new IllegalStateException("This mobile number is already associated with an account. Use a different verified mobile number.");
    return deliverOtp(normalized,"LEARNER_TRANSITION",studentId);
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
    return verifyOtp(mobile,otp,purpose,displayName,firstAccess,newPassword,null);
  }

  @Transactional
  public AuthContext verifyOtp(String mobile,String otp,String purpose,String displayName,boolean firstAccess,
      String newPassword,Long institutionId) {
    String normalized=normalizeMobile(mobile);
    if(!"LOGIN".equals(purpose) && !"SIGNUP".equals(purpose) && !"STAFF_LOGIN".equals(purpose)
        && !"PASSWORD_RESET".equals(purpose) && !"LEARNER_TRANSITION".equals(purpose) && !"ACCOUNT_SIGNUP".equals(purpose))
      throw new IllegalArgumentException("Invalid OTP purpose");
    if(("SIGNUP".equals(purpose) || "ACCOUNT_SIGNUP".equals(purpose))
        && (displayName==null || displayName.trim().length()<2 || displayName.trim().length()>120))
      throw new IllegalArgumentException("Full name must be between 2 and 120 characters");
    if("SIGNUP".equals(purpose) || "PASSWORD_RESET".equals(purpose) || "LEARNER_TRANSITION".equals(purpose))
      validatePassword(newPassword, 8, "Password");

    var rows=jdbc.queryForList("""
      select id,code_hash,attempts,expires_at,purpose,student_id,institution_id
      from otp_challenge
      where mobile_e164=? and purpose=? and consumed_at is null
      order by requested_at desc limit 1
      """,normalized,purpose);
    if(rows.isEmpty()) throw new IllegalArgumentException("OTP not found or expired");
    var row=rows.getFirst();
    if(!purpose.equals(String.valueOf(row.get("purpose"))))
      throw new IllegalArgumentException("OTP purpose mismatch");
    if("SIGNUP".equals(purpose) || "ACCOUNT_SIGNUP".equals(purpose)) {
      Object recordedInstitution=row.get("institution_id");
      if(institutionId==null || !(recordedInstitution instanceof Number recorded)
          || recorded.longValue()!=institutionId.longValue()) {
        throw new SecurityException("The signup verification belongs to a different institution. Request a new code from this institution.");
      }
    }
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

    if ("LEARNER_TRANSITION".equals(purpose)) {
      if (row.get("student_id")==null) throw new SecurityException("Learner transition OTP is not linked to a student.");
      return completeIndependentLearnerTransition(normalized,
          ((Number)row.get("student_id")).longValue(),newPassword,((Number)row.get("id")).longValue());
    }

    if ("PASSWORD_RESET".equals(purpose)) {
      List<Map<String,Object>> accountRows=jdbc.queryForList(
          "select id,role from user_account where mobile_e164=? and active=true and role in ('PARENT','LEARNER')",
          normalized);
      if(accountRows.isEmpty()) throw new SecurityException("Active account not found.");
      long accountId=((Number)accountRows.getFirst().get("id")).longValue();
      String accountRole=String.valueOf(accountRows.getFirst().get("role"));
      int changed=jdbc.update(
          "update user_account set password_hash=?,updated_at=now() where id=? and active=true and role in ('PARENT','LEARNER')",
          passwordEncoder.encode(newPassword), accountId);
      if(changed!=1) throw new SecurityException("Active account not found.");
      jdbc.update("update auth_session set revoked_at=now() where user_id=? and revoked_at is null", accountId);
      jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
      if ("PARENT".equals(accountRole)) return contextForUser(accountId);
      List<Map<String,Object>> profileRows=jdbc.queryForList(
          "select id from student where user_account_id=? and active=true and environment='PRODUCTION'",accountId);
      if(profileRows.isEmpty()) throw new IllegalStateException("Learner profile is not available for this account.");
      return contextForStudent(null,((Number)profileRows.getFirst().get("id")).longValue());
    }

    // Login never changes a role or activates a suspended identity.
    if("LOGIN".equals(purpose)) {
      List<Map<String,Object>> accounts=jdbc.queryForList(
          "select id,role from user_account where mobile_e164=? and role in ('PARENT','LEARNER') and active=true",
          normalized);
      if(accounts.isEmpty()) {
        Boolean accountExists=jdbc.queryForObject(
            "select exists(select 1 from user_account where mobile_e164=? and role in ('PARENT','LEARNER'))",
            Boolean.class, normalized);
        if(Boolean.TRUE.equals(accountExists)) throw new SecurityException("This account is inactive. Contact support.");
        throw new IllegalArgumentException("No active account found for this mobile number. Please sign up first.");
      }
      long userId=((Number)accounts.getFirst().get("id")).longValue();
      String role=String.valueOf(accounts.getFirst().get("role"));
      jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
      if ("PARENT".equals(role)) return contextForUser(userId);
      List<Map<String,Object>> profiles=jdbc.queryForList(
          "select id from student where user_account_id=? and active=true and environment='PRODUCTION'",userId);
      if(profiles.isEmpty()) throw new IllegalStateException("Learner profile is not available for this account.");
      return contextForStudent(null,((Number)profiles.getFirst().get("id")).longValue());
    }

    if("ACCOUNT_SIGNUP".equals(purpose)) {
      Boolean accountExists=jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=?)",Boolean.class,normalized);
      if(Boolean.TRUE.equals(accountExists))
        throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
      long newUserId;
      try {
        newUserId=jdbc.queryForObject("""
          insert into user_account(public_id,mobile_e164,display_name,role,mobile_verified_at)
          values (?,?,?,'LEARNER',now()) returning id
          """,Long.class,UUID.randomUUID(),normalized,displayName.trim());
      } catch (DuplicateKeyException ex) {
        throw new IllegalStateException("An account already exists for this mobile number. Please log in.",ex);
      }
      Long newStudentId=jdbc.queryForObject("""
        insert into student(
          public_id,display_name,class_code,board,language,environment,active,user_account_id
        ) values (?,?, '6','Bihar Board','hi','PRODUCTION',true,?)
        returning id
        """,Long.class,UUID.randomUUID(),displayName.trim(),newUserId);
      jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
      return contextForStudent(null,newStudentId);
    }

    // Legacy SIGNUP remains the dedicated parent-registration entry point.
    boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from user_account where mobile_e164=?)", Boolean.class, normalized));
    if(accountExists)
      throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
    String registrationPasswordHash=passwordEncoder.encode(newPassword);
    Long userId=jdbc.queryForObject("""
      insert into user_account(public_id,mobile_e164,display_name,role,password_hash,mobile_verified_at)
      values (?,?,?,?,?,now()) returning id
      """,Long.class,UUID.randomUUID(),normalized,displayName,"PARENT",registrationPasswordHash);
    jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));
    return contextForUser(userId);
  }


  private AuthContext completeIndependentLearnerTransition(String normalized,long studentId,String password,long otpId) {
    List<Map<String,Object>> students=jdbc.queryForList("""
      select id,display_name,user_account_id,public_user_id from student
      where id=? and active=true and environment='PRODUCTION' for update
      """,studentId);
    if(students.isEmpty()) throw new SecurityException("Active production learner not found.");
    Map<String,Object> student=students.getFirst();
    if(student.get("user_account_id")!=null)
      throw new IllegalStateException("This learner already has an independent account.");
    Long adultVerified=jdbc.queryForObject("""
      select count(*) from student_adult_verification
      where student_id=? and status='VERIFIED' and expires_at>now()
      """,Long.class,studentId);
    if(adultVerified==null || adultVerified==0)
      throw new SecurityException("Verified adult eligibility is required before conversion.");
    Boolean accountExists=jdbc.queryForObject(
        "select exists(select 1 from user_account where mobile_e164=?)",Boolean.class,normalized);
    if(Boolean.TRUE.equals(accountExists))
      throw new IllegalStateException("This mobile number is already associated with an account.");
    String displayName=String.valueOf(student.get("display_name"));
    Long userId=jdbc.queryForObject("""
      insert into user_account(public_id,public_user_id,mobile_e164,display_name,role,password_hash)
      values (?,?,?,?, 'LEARNER',?) returning id
      """,Long.class,UUID.randomUUID(),String.valueOf(student.get("public_user_id")),
          normalized,displayName,passwordEncoder.encode(password));
    int linked=jdbc.update("update student set user_account_id=? where id=? and user_account_id is null",userId,studentId);
    if(linked!=1) throw new IllegalStateException("Learner account link changed during conversion; retry sign-in.");
    // Mirror active institution memberships to the newly linked account without
    // reactivating any membership that an institution previously suspended.
    jdbc.update("""
      WITH created_memberships AS (
        INSERT INTO institution_user_membership(
          institution_id,user_id,status,joined_at,left_at,created_at,updated_at
        )
        SELECT im.institution_id,?,'ACTIVE',now(),null,now(),now()
        FROM institution_membership im
        JOIN institution_tenant tenant ON tenant.id=im.institution_id AND tenant.status='ACTIVE'
        WHERE im.student_id=? AND im.status='ACTIVE'
        ON CONFLICT(institution_id,user_id) DO NOTHING
        RETURNING id,institution_id,user_id
      )
      INSERT INTO institution_user_membership_event(
        institution_id,membership_id,user_id,event_type,actor_user_id,details
      )
      SELECT institution_id,id,user_id,'USER_MEMBERSHIP_CREATED',user_id,
        jsonb_build_object('source','LEARNER_ACCOUNT_CONVERSION')
      FROM created_memberships
      """,userId,studentId);
    int limited=jdbc.update("""
      update guardian_student set access_level='LIMITED'
      where student_id=? and active=true and consent_status='CONSENTED' and access_level='FULL'
      """,studentId);
    jdbc.update("update student_login_credential set active=false,updated_at=now() where student_id=?",studentId);
    jdbc.update("update auth_session set revoked_at=now() where student_id=? and revoked_at is null",studentId);
    jdbc.update("update otp_challenge set consumed_at=now() where id=?",otpId);
    jdbc.update("""
      insert into account_lifecycle_audit(event_type,student_id,user_id,actor_student_id,old_role,new_role,details)
      values ('STUDENT_BECAME_INDEPENDENT',?,?,?,'STUDENT','LEARNER',
        jsonb_build_object('guardianRelationshipsPreserved',true,'guardianAccessLimited',?))
      """,studentId,userId,studentId,limited);
    return contextForStudent(null,studentId);
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
    String principalHash=passwordAttemptKey(authType,identifier);
    // The composite index keeps cleanup scoped to this principal instead of scanning the full table.
    jdbc.update("""
      delete from account_login_failure
      where principal_hash=? and auth_type=? and attempted_at < now() - interval '24 hours'
      """,principalHash,authType);
    jdbc.update("insert into account_login_failure(principal_hash,auth_type) values (?,?)",
        principalHash,authType);
  }

  private void clearPasswordLoginFailures(String authType,String identifier) {
    jdbc.update("delete from account_login_failure where principal_hash=? and auth_type=?",
        passwordAttemptKey(authType,identifier),authType);
  }

  /**
   * A parent may enter their own learner workspace only when they hold an active
   * entitlement or an active/paused enrollment in this institution. Child-owned
   * entitlements do not satisfy this check.
   */
  public boolean hasParentPersonalLearningAccess(long parentUserId, long institutionId) {
    Boolean entitled = jdbc.queryForObject("""
      select exists(
        select 1
        from course_entitlement ce
        join curriculum_program p on p.id=ce.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where ce.institution_id=? and ce.holder_user_id=?
          and (ce.holder_student_id is null or exists(
            select 1 from student holder_profile
            where holder_profile.id=ce.holder_student_id
              and holder_profile.user_account_id=ce.holder_user_id
              and holder_profile.active=true and holder_profile.environment='PRODUCTION'
          ))
          and exists(
            select 1
            from institution_course_assignment assignment
            join institution_course_release release
              on release.id=assignment.course_release_id and release.program_id=p.id
            join institution_tenant tenant_scope
              on tenant_scope.id=assignment.institution_id and tenant_scope.status='ACTIVE'
            where assignment.institution_id=ce.institution_id and assignment.status='ASSIGNED'
          )
        union all
        select 1
        from student st
        join institution_membership m on m.student_id=st.id
          and m.institution_id=? and m.status='ACTIVE'
        join student_program_enrollment e on e.student_id=st.id
          and e.institution_id=m.institution_id
          and e.status in ('ACTIVE','PAUSED')
        join curriculum_program p on p.id=e.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where st.user_account_id=? and st.active=true and st.environment='PRODUCTION'
          and exists(
            select 1
            from institution_course_assignment assignment
            join institution_course_release release
              on release.id=assignment.course_release_id and release.program_id=p.id
            join institution_tenant tenant_scope
              on tenant_scope.id=assignment.institution_id and tenant_scope.status='ACTIVE'
            where assignment.institution_id=m.institution_id and assignment.status='ASSIGNED'
          )
        union all
        select 1
        from course_purchase purchase
        join student st on st.id=purchase.student_id and st.active=true
          and st.environment='PRODUCTION' and st.user_account_id=?
        join institution_membership m on m.student_id=st.id
          and m.institution_id=purchase.institution_id and m.status='ACTIVE'
        join institution_tenant tenant_scope on tenant_scope.id=m.institution_id and tenant_scope.status='ACTIVE'
        join curriculum_program p on p.id=purchase.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where purchase.institution_id=?
          and exists(
            select 1
            from institution_course_assignment assignment
            join institution_course_release release
              on release.id=assignment.course_release_id and release.program_id=p.id
            where assignment.institution_id=m.institution_id and assignment.status='ASSIGNED'
          )
      )
      """, Boolean.class, institutionId, parentUserId, institutionId, parentUserId,
          parentUserId, institutionId);
    return Boolean.TRUE.equals(entitled);
  }

  /** Open a separate learner-scoped session for a parent who wants to use their own courses. */
  @Transactional
  public AuthContext openParentLearningContext(long parentUserId) {
    List<Map<String,Object>> parents = jdbc.queryForList("""
      select id,display_name from user_account
      where id=? and role='PARENT' and active=true
      for update
      """, parentUserId);
    if (parents.isEmpty()) throw new SecurityException("An active parent account is required.");
    List<Map<String,Object>> profiles = jdbc.queryForList("""
      select id,display_name from student
      where user_account_id=? and active=true and environment='PRODUCTION'
      order by id limit 1 for update
      """, parentUserId);
    long studentId;
    String displayName = String.valueOf(parents.getFirst().get("display_name"));
    if (profiles.isEmpty()) {
      Long created = jdbc.queryForObject("""
        insert into student(
          public_id,display_name,class_code,board,language,environment,active,user_account_id
        ) values (?,?, '6','Bihar Board','hi','PRODUCTION',true,?)
        returning id
        """, Long.class, UUID.randomUUID(), displayName, parentUserId);
      studentId = java.util.Objects.requireNonNull(created);
    } else {
      studentId = ((Number)profiles.getFirst().get("id")).longValue();
    }
    return new AuthContext(null, studentId, null, "STUDENT", displayName, "STUDENT");
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

  /** Independent learners use their own verified mobile and password; history remains on the linked student row. */
  public AuthContext loginIndependentLearner(String mobile,String password) {
    String normalized=normalizeMobile(mobile);
    if(!(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit)))
      throw new IllegalArgumentException("Enter a valid Indian mobile number.");
    if(password==null || password.isBlank()) throw new IllegalArgumentException("Enter your password.");
    assertPasswordLoginAllowed("LEARNER",normalized);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select u.password_hash,st.id as student_id
      from user_account u
      join student st on st.user_account_id=u.id and st.active=true and st.environment='PRODUCTION'
      where u.mobile_e164=? and u.active=true and u.role in ('LEARNER','PARENT')
      """,normalized);
    if(rows.isEmpty()) {
      recordPasswordLoginFailure("LEARNER",normalized);
      throw new IllegalArgumentException("Mobile number or password is incorrect.");
    }
    Map<String,Object> row=rows.getFirst();
    String stored=row.get("password_hash")==null?"":String.valueOf(row.get("password_hash"));
    if(stored.isBlank() || !passwordEncoder.matches(password,stored)) {
      recordPasswordLoginFailure("LEARNER",normalized);
      throw new IllegalArgumentException("Mobile number or password is incorrect.");
    }
    clearPasswordLoginFailures("LEARNER",normalized);
    return contextForStudent(null,((Number)row.get("student_id")).longValue());
  }

  /** Student credentials are scoped to a verified parent contact; child access stays parent-owned. */
  public AuthContext loginStudentByParent(String parentMobile,String username,String password) {
    String supplied=parentMobile==null?"":parentMobile.trim();
    String email=supplied.toLowerCase(java.util.Locale.ROOT);
    boolean emailIdentifier=email.contains("@");
    String normalized=emailIdentifier?"":normalizeMobile(supplied);
    if(emailIdentifier && !email.matches("^[^@\\s]+@gmail\\.com$"))
      throw new IllegalArgumentException("Enter the parent's verified Gmail address or mobile number.");
    if(!emailIdentifier && !(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit)))
      throw new IllegalArgumentException("Enter the parent's verified Gmail address or 10-digit mobile number.");
    String cleanUsername=validateUsername(username);
    if(password==null || password.isBlank()) throw new IllegalArgumentException("Enter your student password.");
    String attemptIdentity=(emailIdentifier?email:normalized)+"|"+cleanUsername;
    assertPasswordLoginAllowed("STUDENT",attemptIdentity);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select c.student_id,c.password_hash
      from user_account u
      join student_login_credential c on c.guardian_user_id=u.id and c.active=true
      join guardian_student gs on gs.guardian_user_id=u.id and gs.student_id=c.student_id
      join student st on st.id=c.student_id
      where ((?<>'' and u.mobile_e164=? and u.mobile_verified_at is not null) or
             (?<>'' and lower(u.email)=? and u.email_verified_at is not null
               and u.google_subject is not null))
        and u.active=true and u.role='PARENT'
        and lower(c.username)=? and gs.active=true and gs.consent_status='CONSENTED'
        and st.active=true and st.environment='PRODUCTION'
      """,normalized,normalized,emailIdentifier?email:"",emailIdentifier?email:"",cleanUsername);
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

  /** Global username/password login for an ordinary account or parent account. */
  public AuthContext loginAccountByUsername(String username,String password) {
    String cleanUsername=validateUsername(username);
    if(password==null || password.isBlank()) throw new IllegalArgumentException("Enter your password.");
    assertPasswordLoginAllowed("LEARNER",cleanUsername);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select u.id as user_id,u.role,u.password_hash,st.id as student_id
      from user_account u
      left join student st on st.user_account_id=u.id and st.active=true and st.environment='PRODUCTION'
      where lower(u.username)=? and u.active=true and u.role in ('PARENT','LEARNER')
      order by st.id limit 1
      """,cleanUsername);
    if(rows.isEmpty()) {
      recordPasswordLoginFailure("LEARNER",cleanUsername);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }
    Map<String,Object> row=rows.getFirst();
    String stored=row.get("password_hash")==null?"":String.valueOf(row.get("password_hash"));
    if(stored.isBlank() || !passwordEncoder.matches(password,stored)) {
      recordPasswordLoginFailure("LEARNER",cleanUsername);
      throw new IllegalArgumentException("Username or password is incorrect.");
    }
    String role=String.valueOf(row.get("role"));
    clearPasswordLoginFailures("LEARNER",cleanUsername);
    long userId=((Number)row.get("user_id")).longValue();
    if("PARENT".equals(role)) return contextForUser(userId);
    if(row.get("student_id")==null) throw new IllegalStateException("The learner profile is not available for this account.");
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
      boolean consentAccepted) {
    String cleanUsername=validateUsername(username);
    validatePassword(password,8,"Student password");
    String legacyPin=String.format(java.util.Locale.ROOT,"%06d",random.nextInt(1_000_000));
    AuthContext created=createChild(userId,name,classCode,language,legacyPin,consentAccepted,List.of());
    setChildCredentials(userId,created.studentId(),cleanUsername,password);
    return created;
  }

  /** Compatibility overload: subject tracks no longer create learner entitlements. */
  @Transactional
  public AuthContext createChild(long userId,String name,String classCode,String language,String username,String password,
      boolean consentAccepted,List<String> ignoredTrackCodes) {
    return createChild(userId,name,classCode,language,username,password,consentAccepted);
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
    // A guardian-created profile is a family identity, not an implicit school enrolment.
    // Until an institution explicitly admits this child, do not create tenant-less track rows.
    // Subject tracks can be selected after a verified institution join/enrolment.
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

    // A class promotion changes the learner's grade identity only. The selected institution
    // courses own subject mapping and learning access; never recalculate legacy subject tracks
    // as a side effect of editing the child profile. Existing course enrollments/history remain intact.
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
    return updateStudentTracks(studentId,trackCodes,resolveSingleActiveInstitutionId(studentId));
  }

  @Transactional
  public List<String> updateStudentTracks(long studentId,List<String> trackCodes,Long institutionId) {
    Map<String,Object> row;
    try {
      row=jdbc.queryForMap("select class_code from student where id=? and active=true and environment='PRODUCTION' for update",studentId);
    } catch(EmptyResultDataAccessException ex) {
      throw new IllegalArgumentException("Active production student not found");
    }
    if(institutionId!=null) assertStudentInstitutionMembership(studentId,institutionId);
    return applyStudentTracks(studentId,String.valueOf(row.get("class_code")),trackCodes,institutionId);
  }

  private Long resolveSingleActiveInstitutionId(long studentId) {
    List<Long> ids=jdbc.queryForList("""
      select m.institution_id from institution_membership m
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      where m.student_id=? and m.status='ACTIVE' order by m.institution_id
      """,Long.class,studentId);
    if(ids.size()>1) throw new IllegalArgumentException(
        "This learner belongs to more than one institution; the institution context is required to change subjects.");
    return ids.isEmpty()?null:ids.getFirst();
  }

  private void assertStudentInstitutionMembership(long studentId,long institutionId) {
    Boolean member=jdbc.queryForObject("""
      select exists(select 1 from institution_membership m
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join student st on st.id=m.student_id and st.active=true
        where m.student_id=? and m.institution_id=? and m.status='ACTIVE')
      """,Boolean.class,studentId,institutionId);
    if(!Boolean.TRUE.equals(member)) throw new SecurityException(
        "The learner is not an active member of the selected institution.");
  }

  private List<String> applyStudentTracks(long studentId,String classCode,List<String> requested) {
    return applyStudentTracks(studentId,classCode,requested,resolveSingleActiveInstitutionId(studentId));
  }

  private List<String> applyStudentTracks(long studentId,String classCode,List<String> requested,Long institutionId) {
    List<String> selected=requested==null
        ? List.of("maths","science")
        : requested.stream().map(v->v==null?"":v.trim().toLowerCase(java.util.Locale.ROOT)).distinct().toList();
    if(selected.isEmpty() || selected.size()>2 || selected.stream().anyMatch(v->!List.of("maths","science").contains(v)))
      throw new IllegalArgumentException("Choose one or two valid subjects: Maths and/or Science");
    Long classExists=jdbc.queryForObject("select count(*) from curriculum_class where code=? and active=true",Long.class,classCode);
    if(classExists==null || classExists==0) throw new IllegalArgumentException("Invalid class selection");
    jdbc.update("""
      update student_track_enrollment set status='ENDED',updated_at=now()
      where student_id=? and institution_id is not distinct from ? and status='ACTIVE'
      """,studentId,institutionId);
    for(String code:selected) {
      List<Long> subjects=jdbc.queryForList("""
        select s.id from curriculum_subject s join curriculum_class c on c.id=s.class_id
        where c.code=? and c.active=true and s.code=? and s.active=true
        """,Long.class,classCode,code);
      if(subjects.isEmpty()) throw new IllegalArgumentException("The "+code+" track is not available for class "+classCode);
      long subjectId=subjects.getFirst();
      if(institutionId!=null) {
        jdbc.update("""
          insert into student_track_enrollment(student_id,subject_id,institution_id,status)
          values (?,?,?,'ACTIVE')
          on conflict(institution_id,student_id,subject_id) do update set status='ACTIVE',updated_at=now()
          """,studentId,subjectId,institutionId);
      } else {
        Boolean exists=jdbc.queryForObject("""
          select exists(select 1 from student_track_enrollment
            where student_id=? and subject_id=? and institution_id is null)
          """,Boolean.class,studentId,subjectId);
        if(Boolean.TRUE.equals(exists)) {
          jdbc.update("""
            update student_track_enrollment set status='ACTIVE',updated_at=now()
            where student_id=? and subject_id=? and institution_id is null
            """,studentId,subjectId);
        } else {
          jdbc.update("""
            insert into student_track_enrollment(student_id,subject_id,institution_id,status)
            values(?,?,null,'ACTIVE')
            """,studentId,subjectId);
        }
      }
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
    Long id=jdbc.queryForObject(
        "select id from student where public_id=? and environment=? and active=true",
        Long.class, LocalPreviewIdentity.PUBLIC_ID, LocalPreviewIdentity.ENVIRONMENT);
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
        and s.last_seen_at>now()-(case
          when s.client_type='ANDROID' and s.workspace_key in ('LEARNING','PARENT','STUDENT') then interval '30 days'
          when s.workspace_key in ('HRMS','STAFF','EDUCATION_ADMIN') then interval '30 minutes'
          else interval '4 hours' end)
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
          then case when client_type='ANDROID' then absolute_expires_at
            else now()+make_interval(hours => ?) end
          else refresh_expires_at end
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
    return issueSession(context, workspaceForContext(context), false);
  }

  @Transactional
  public SessionTokens issueSession(AuthContext context, String requestedWorkspace) {
    return issueSession(context, requestedWorkspace, false);
  }

  @Transactional
  public SessionTokens issueSession(AuthContext context, String requestedWorkspace, boolean androidClient) {
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
    boolean persistentAndroidLearner = androidClient && learnerWorkspace;
    String clientType = persistentAndroidLearner ? "ANDROID" : "WEB";
    Instant absoluteExpiry = learnerWorkspace
        ? now.plus(Duration.ofDays(Math.max(1, learningAbsoluteDays)))
        : now.plus(Duration.ofHours(Math.max(1, staffAbsoluteHours)));
    Instant refreshExpiry = persistentAndroidLearner
        ? absoluteExpiry : now.plus(Duration.ofHours(sessionHours));
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
        expires_at,refresh_expires_at,workspace_key,absolute_expires_at,client_type)
      values (?,?,?,?,?,?,?,?,?,?)
      """,hash(access),hash(refresh),context.userId(),context.studentId(),context.staffId(),
      java.sql.Timestamp.from(now.plus(Duration.ofMinutes(ACCESS_TOKEN_MINUTES))),
      java.sql.Timestamp.from(refreshExpiry),
      workspace, java.sql.Timestamp.from(absoluteExpiry), clientType);
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
        and s.last_seen_at>now()-(case
          when s.client_type='ANDROID' and s.workspace_key in ('LEARNING','PARENT','STUDENT') then interval '30 days'
          when s.workspace_key in ('HRMS','STAFF','EDUCATION_ADMIN') then interval '30 minutes'
          else interval '4 hours' end)
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
      update auth_session set token_hash=?,refresh_token_hash=?,expires_at=?,
        refresh_expires_at=case when client_type='ANDROID' then absolute_expires_at else ? end,last_seen_at=now()
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
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED' and access_level='FULL')
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
    String permissionWorkspace = "HRMS".equals(normalizeWorkspace(workspace)) ? "HRMS" : "EDUCATION_ADMIN";
    // Never synthesize permissions from the role label. The effective permission list is the
    // active workspace-scoped grants plus scoped additional-role grants, exactly as the API checks.
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


  /** Stable 12-character public identity for an account, without exposing its database key. */
  public String publicUserId(AuthContext context) {
    if(context==null) return null;
    if(context.userId()!=null) {
      List<String> ids=jdbc.queryForList(
          "select public_user_id from user_account where id=? and active=true",
          String.class,context.userId());
      return ids.isEmpty()?null:ids.getFirst();
    }
    if(context.studentId()!=null) {
      List<String> ids=jdbc.queryForList("""
        select st.public_user_id from student st
        where st.id=? and st.active=true
        """,String.class,context.studentId());
      return ids.isEmpty()?null:ids.getFirst();
    }
    return null;
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
    if ("INSTITUTION".equals(normalized)) {
      // Institution customers authenticate in a dedicated identity/session table.
      // No ordinary auth_session row is ever accepted for this workspace.
      return "\n        and 1=0";
    }
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
    if ("INSTITUTION".equals(workspace)) return false;
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
