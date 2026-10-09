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
  private final boolean secureCookies;
  private final int otpCooldownSeconds;
  private final List<String> adminMobiles;
  @Value("${app.auth.max-children-per-parent:3}")
  private int maxChildrenPerParent = 3;

  public AuthService(
      JdbcTemplate jdbc,
      @Value("${app.demo-seed:false}") boolean demoSeed,
      @Value("${app.auth.session-hours:168}") int sessionHours,
      @Value("${app.auth.secure-cookies:true}") boolean secureCookies,
      @Value("${app.auth.otp-cooldown-seconds:60}") int otpCooldownSeconds,
      @Value("${app.auth.admin-mobiles:}") String adminMobiles) {
    this.jdbc=jdbc;
    this.demoSeed=demoSeed;
    this.sessionHours=sessionHours;
    this.secureCookies=secureCookies;
    this.otpCooldownSeconds=otpCooldownSeconds;
    this.adminMobiles=List.of(adminMobiles.split(",")).stream()
        .map(this::normalizeMobile).filter(v->!v.isBlank()).toList();
  }

  public int getSessionHours() { return sessionHours; }

  public String normalizeMobile(String value) {
    String digits=value==null?"":value.replaceAll("[^0-9]","");
    if(digits.startsWith("91") && digits.length()==12) return "+"+digits;
    if(digits.length()==10) return "+91"+digits;
    return value==null?"":value.trim();
  }

  public String requestOtp(String mobile,String purpose) {
    if(!"LOGIN".equals(purpose) && !"SIGNUP".equals(purpose) && !"STAFF_LOGIN".equals(purpose))
      throw new IllegalArgumentException("Invalid OTP purpose");
    String normalized=normalizeMobile(mobile);
    if(!(normalized.startsWith("+91") && normalized.length()==13 && normalized.substring(3).chars().allMatch(Character::isDigit))) throw new IllegalArgumentException("Invalid Indian mobile number");

    if ("STAFF_LOGIN".equals(purpose)) {
      boolean staffExists = Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from staff_account where mobile_e164=? and active=true)", Boolean.class, normalized));
      if (!staffExists && !adminMobiles.contains(normalized)) {
        throw new IllegalArgumentException("This mobile is not assigned to an active staff account.");
      }
    } else {
      boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
          "select exists(select 1 from user_account where mobile_e164=?)", Boolean.class, normalized));
      if("SIGNUP".equals(purpose) && accountExists)
        throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
      if("LOGIN".equals(purpose) && !accountExists)
        throw new IllegalArgumentException("No account found for this mobile number. Please register first.");
    }

    long recent=jdbc.queryForObject(
        "select count(*) from otp_challenge where mobile_e164=? and requested_at > now() - (? * interval '1 second')",
        Long.class,normalized,otpCooldownSeconds);
    if(recent>0) throw new IllegalStateException("Please wait before requesting another OTP");
    long hourly=jdbc.queryForObject(
        "select count(*) from otp_challenge where mobile_e164=? and requested_at > now()-interval '1 hour'",
        Long.class,normalized);
    if(hourly>=5) throw new IllegalStateException("Too many OTP requests. Please try again later.");

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
    String normalized=normalizeMobile(mobile);
    if(!"LOGIN".equals(purpose) && !"SIGNUP".equals(purpose) && !"STAFF_LOGIN".equals(purpose)) throw new IllegalArgumentException("Invalid OTP purpose");
    if("SIGNUP".equals(purpose) && (displayName==null || displayName.trim().length()<2 || displayName.trim().length()>120))
      throw new IllegalArgumentException("Parent name must be between 2 and 120 characters");

    var rows=jdbc.queryForList("""
      select id,code_hash,attempts,expires_at,purpose
      from otp_challenge where mobile_e164=? and consumed_at is null
      order by requested_at desc limit 1
      """,normalized);
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
      jdbc.update("update otp_challenge set consumed_at=now() where id=?", row.get("id"));
      List<Map<String,Object>> staffRows=jdbc.queryForList(
          "select id, active from staff_account where mobile_e164=?", normalized);
      Long staffId=null;
      if (!staffRows.isEmpty()) {
        Map<String,Object> staffRow=staffRows.getFirst();
        if (!Boolean.TRUE.equals(staffRow.get("active"))) {
          throw new SecurityException("This staff account is inactive. Contact an administrator.");
        }
        staffId=((Number)staffRow.get("id")).longValue();
      } else if (adminMobiles.contains(normalized)) {
        String staffName=displayName==null||displayName.isBlank()?"Administrator":displayName.trim();
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
      return contextForStaff(staffId);
    }

    boolean accountExists=Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from user_account where mobile_e164=?)", Boolean.class, normalized));
    if("SIGNUP".equals(purpose) && accountExists)
      throw new IllegalStateException("An account already exists for this mobile number. Please log in.");
    if("LOGIN".equals(purpose) && !accountExists)
      throw new IllegalArgumentException("No account found for this mobile number. Please register first.");

    jdbc.update("update otp_challenge set consumed_at=now() where id=?",row.get("id"));

    String role="PARENT";
    Long userId;
    try {
      userId=jdbc.queryForObject("select id from user_account where mobile_e164=?",Long.class,normalized);
      jdbc.update("update user_account set display_name=coalesce(?,display_name),active=true,updated_at=now(),role=? where id=?",
          displayName,role,userId);
    } catch(EmptyResultDataAccessException ex) {
      userId=jdbc.queryForObject("""
        insert into user_account(public_id,mobile_e164,display_name,role)
        values (?,?,?,?) returning id
        """,Long.class,UUID.randomUUID(),normalized,displayName,role);
    }
    return contextForUser(userId);
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
    if(!"hi".equalsIgnoreCase(language))
      throw new IllegalArgumentException("Only Hindi student profiles are currently supported");
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
      """,Long.class,UUID.randomUUID(),name.trim(),classCode,board,language,hash(pin));
    jdbc.update("""
      insert into guardian_student(guardian_user_id,student_id,relationship,consent_status)
      values (?,?,'PARENT','CONSENTED')
      """,userId,id);
    applyStudentTracks(id,classCode,trackCodes);
    return contextForStudent(userId,id);
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

  @Transactional
  public AuthContext selectStudent(long userId,long studentId) {
    requireParent(userId);
    if(!hasGuardianAccess(userId,studentId)) throw new SecurityException("Child access not granted");
    return contextForStudent(userId,studentId);
  }

  public AuthContext loginStudent(String publicId,String pin) {
    var row=jdbc.queryForMap("""
      select st.id,st.display_name,st.access_pin_hash
      from student st
      where st.public_id=? and st.active=true and st.environment='PRODUCTION'
        and exists(select 1 from guardian_student gs join user_account u on u.id=gs.guardian_user_id
          where gs.student_id=st.id and gs.active=true and gs.consent_status='CONSENTED' and u.active=true
            and u.role in ('PARENT','ADMIN'))
      """,UUID.fromString(publicId));
    String stored=String.valueOf(row.get("access_pin_hash"));
    if(stored==null || stored.isBlank() || !hash(pin).equals(stored)) throw new IllegalArgumentException("Invalid student access code");
    return contextForStudent(null,((Number)row.get("id")).longValue());
  }

  public AuthContext previewLogin() {
    if(!demoSeed) throw new IllegalStateException("Preview login is disabled");
    Long id=jdbc.queryForObject("select id from student where environment='LOCAL_PREVIEW' and active=true order by id limit 1",Long.class);
    if(id==null) throw new IllegalStateException("Preview student unavailable");
    return contextForStudent(null,id);
  }

  public AuthContext current(String token) {
    if(token==null || token.isBlank()) return null;
    var rows=jdbc.queryForList("""
      select s.user_id,s.student_id,s.staff_id,
             u.role,u.display_name,st.display_name as student_name,
             e.role as staff_role,e.display_name as staff_name
      from auth_session s
      left join user_account u on u.id=s.user_id
      left join student st on st.id=s.student_id
      left join staff_account e on e.id=s.staff_id and e.active=true
      where s.token_hash=? and s.revoked_at is null and s.expires_at>now()
        and (s.staff_id is null or e.id is not null)
      """,hash(token));
    if(rows.isEmpty()) return null;
    var r=rows.getFirst();
    jdbc.update("update auth_session set last_seen_at=now() where token_hash=?",hash(token));
    Long userId=r.get("user_id")==null?null:((Number)r.get("user_id")).longValue();
    Long studentId=r.get("student_id")==null?null:((Number)r.get("student_id")).longValue();
    Long staffId=r.get("staff_id")==null?null:((Number)r.get("staff_id")).longValue();
    String name=studentId!=null?String.valueOf(r.get("student_name")):
        staffId!=null?String.valueOf(r.get("staff_name")):
        (r.get("display_name")==null?null:String.valueOf(r.get("display_name")));
    String role=studentId!=null?"STUDENT":
        staffId!=null?String.valueOf(r.get("staff_role")):String.valueOf(r.get("role"));
    return new AuthContext(userId,studentId,staffId,role,name);
  }

  public String issueToken(AuthContext context) {
    String raw=randomToken();
    jdbc.update("""
      insert into auth_session(token_hash,user_id,student_id,staff_id,expires_at)
      values (?,?,?,?,?)
      """,hash(raw),context.userId(),context.studentId(),context.staffId(),java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(sessionHours))));
    return raw;
  }

  public void revoke(String token) {
    if(token!=null && !token.isBlank()) jdbc.update("update auth_session set revoked_at=now() where token_hash=?",hash(token));
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
    return jdbc.queryForList("""
        select permission_key from staff_permission_grant
        where staff_id=? order by permission_key
        """, String.class, staffId);
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
