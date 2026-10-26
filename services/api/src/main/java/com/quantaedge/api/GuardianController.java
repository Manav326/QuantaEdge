package com.quantaedge.api;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianController {
  private static final Logger LOGGER = LoggerFactory.getLogger(GuardianController.class);
  private final JdbcTemplate jdbc; private final AuthorizationService authorization; private final AuthService auth;
  private final LearningMetricsService learningMetrics;
  private final InstitutionAuthService institutionAuth;
  private final InstitutionMembershipService memberships;
  @Value("${app.auth.max-children-per-parent:3}") private int maxChildrenPerParent=3;
  @Autowired
  public GuardianController(JdbcTemplate jdbc,AuthorizationService authorization,AuthService auth,
      LearningMetricsService learningMetrics,InstitutionAuthService institutionAuth,
      InstitutionMembershipService memberships){
    this.jdbc=jdbc;this.authorization=authorization;this.auth=auth;this.learningMetrics=learningMetrics;
    this.institutionAuth=institutionAuth;this.memberships=memberships;
  }

  /** Compatibility constructor for focused tests that do not exercise institution onboarding. */
  public GuardianController(JdbcTemplate jdbc,AuthorizationService authorization,AuthService auth,
      LearningMetricsService learningMetrics){
    this(jdbc,authorization,auth,learningMetrics,null,null);
  }

  @GetMapping("/account")
  public Map<String,Object> account(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    long activeChildren=jdbc.queryForObject("""
      select count(distinct st.id) from guardian_student gs join student st on st.id=gs.student_id
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL'
        and st.active=true and st.environment='PRODUCTION'
      """,Long.class,context.userId());
    int limit=Math.max(1,maxChildrenPerParent);
    return Map.of("parentName",context.displayName()==null?"":context.displayName(),"activeChildren",activeChildren,
        "maxChildren",limit,"remainingSlots",Math.max(0,limit-(int)activeChildren));
  }


  @GetMapping("/profile")
  public Map<String,Object> profile(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    if(context.userId()==null) throw new SecurityException("Parent account required");
    return parentProfile(context.userId());
  }

  @PutMapping("/profile")
  @Transactional
  public Map<String,Object> updateProfile(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    if(context.userId()==null) throw new SecurityException("Parent account required");
    long userId=context.userId();

    String name=profileText(body.get("displayName"),"Name",120);
    if(name.length()<2) throw new IllegalArgumentException("Name must contain at least 2 characters.");
    String email=profileText(body.get("email"),"Email",254);
    if(!email.isBlank() && (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length()>254))
      throw new IllegalArgumentException("Enter a valid email address, or leave it blank.");
    String city=profileText(body.get("city"),"City or town",100);
    String state=profileText(body.get("state"),"State",100);
    String occupation=profileText(body.get("occupation"),"Occupation",120);
    String organization=profileText(body.get("organization"),"Organisation",180);
    String preferredLanguage=profileText(body.get("preferredLanguage"),"Preferred language",30);
    if(!List.of("English","Hindi","Hindi & English","Other").contains(preferredLanguage))
      throw new IllegalArgumentException("Choose a valid preferred language.");

    String image;
    if(body.containsKey("profileImageDataUrl")) {
      image=validatedProfileImage(body.get("profileImageDataUrl"));
    } else {
      image=jdbc.queryForObject("select profile_image_data_url from user_account where id=? and active=true",String.class,userId);
    }

    int changed=jdbc.update("""
      update user_account
      set display_name=?,profile_image_data_url=?,contact_email=?,city=?,state=?,occupation=?,
          organization=?,preferred_language=?,profile_updated_at=now(),updated_at=now()
      where id=? and active=true and role='PARENT'
      """,name,image,nullable(email),nullable(city),nullable(state),nullable(occupation),
         nullable(organization),preferredLanguage,userId);
    if(changed!=1) throw new SecurityException("Active parent account not found.");
    jdbc.update("""
      update student set display_name=?,profile_updated_at=now()
      where user_account_id=? and active=true and environment='PRODUCTION'
      """, name, userId);
    return parentProfile(userId);
  }

  @GetMapping(value="/profile-photo",produces=MediaType.IMAGE_JPEG_VALUE)
  public ResponseEntity<byte[]> profilePhoto(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    if(context.userId()==null) throw new SecurityException("Parent account required");
    String data=jdbc.queryForObject(
        "select profile_image_data_url from user_account where id=? and active=true",
        String.class,context.userId());
    if(data==null || data.isBlank()) return ResponseEntity.notFound().build();
    final String prefix="data:image/jpeg;base64,";
    if(!data.startsWith(prefix)) return ResponseEntity.notFound().build();
    byte[] bytes;
    try {
      bytes=Base64.getDecoder().decode(data.substring(prefix.length()));
    } catch(IllegalArgumentException ex) {
      return ResponseEntity.notFound().build();
    }
    if(bytes.length==0 || bytes.length>350*1024) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options","nosniff")
        .body(bytes);
  }

  @PutMapping("/profile-photo")
  @Transactional
  public Map<String,Object> updateProfilePhoto(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    if(context.userId()==null) throw new SecurityException("Parent account required");
    if(!body.containsKey("profileImageDataUrl"))
      throw new IllegalArgumentException("Choose a photo or remove the current photo.");

    String image=validatedProfileImage(body.get("profileImageDataUrl"));
    int changed=jdbc.update(
        "update user_account set profile_image_data_url=?,profile_updated_at=now(),updated_at=now() where id=? and active=true and role='PARENT'",
        image,context.userId());
    if(changed!=1) throw new SecurityException("Active parent account not found.");

    Map<String,Object> result=new java.util.LinkedHashMap<>();
    result.put("saved",true);
    result.put("profile_image_url",image==null?null:"/api/v1/guardians/profile-photo?v="+System.currentTimeMillis());
    return result;
  }

  private Map<String,Object> parentProfile(long userId) {
    return jdbc.queryForMap("""
      select id,public_id,public_user_id,display_name,mobile_e164,contact_email as email,city,state,occupation,
             organization,preferred_language,
             case when profile_image_data_url is not null then '/api/v1/guardians/profile-photo?v=' || floor(extract(epoch from profile_updated_at)*1000)::bigint::text else google_picture_url end as profile_image_url,
             profile_updated_at
      from user_account
      where id=? and active=true and role='PARENT'
      """,userId);
  }

  private String profileText(Object value,String label,int maxLength) {
    String text=value==null?"":String.valueOf(value).trim();
    if(text.length()>maxLength) throw new IllegalArgumentException(label+" must be "+maxLength+" characters or fewer.");
    if(text.chars().anyMatch(ch -> Character.isISOControl(ch) && ch!='\n' && ch!='\t'))
      throw new IllegalArgumentException(label+" contains unsupported characters.");
    return text;
  }

  private String nullable(String value) {
    return value==null || value.isBlank()?null:value;
  }

  private String validatedProfileImage(Object value) {
    String data=value==null?"":String.valueOf(value).trim();
    if(data.isEmpty()) return null;
    final String prefix="data:image/jpeg;base64,";
    if(!data.startsWith(prefix) || data.length()>500_000)
      throw new IllegalArgumentException("Upload a JPG photo smaller than 350 KB.");
    byte[] bytes;
    try {
      bytes=Base64.getDecoder().decode(data.substring(prefix.length()));
    } catch(IllegalArgumentException ex) {
      throw new IllegalArgumentException("The uploaded photo is not a valid image.");
    }
    if(bytes.length==0 || bytes.length>350*1024)
      throw new IllegalArgumentException("Upload a JPG photo smaller than 350 KB.");
    return data;
  }

  @GetMapping("/children")
  public List<Map<String,Object>> children(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.class_code,cc.display_name as class_name,st.board,st.language,
             slc.username as login_username,gs.relationship,gs.consent_status,
             coalesce((select string_agg(distinct coalesce(identity.display_name,p.display_name),', ' order by coalesce(identity.display_name,p.display_name))
                 from student_program_enrollment spe
                 join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
                 left join institution_course_identity identity on identity.id=p.course_identity_id
                 join institution_course_assignment assignment on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
                 join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
                 where spe.student_id=st.id and spe.institution_id=? and spe.status in ('ACTIVE','PAUSED')),'') as course_names
      from guardian_student gs join student st on st.id=gs.student_id
      join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      left join curriculum_class cc on cc.code=st.class_code
      left join student_login_credential slc on slc.guardian_user_id=gs.guardian_user_id
        and slc.student_id=st.id and slc.active=true
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED'
        and gs.access_level='FULL' and st.active=true and st.environment='PRODUCTION'
      order by st.created_at
      """,institutionId,institutionId,context.userId());
  }

  @GetMapping("/children/archived")
  public List<Map<String,Object>> archivedChildren(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.class_code,cc.display_name as class_name,st.board,st.language,
             gs.relationship,gs.consent_status
      from guardian_student gs join student st on st.id=gs.student_id
      join institution_membership m on m.student_id=st.id and m.institution_id=?
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      left join curriculum_class cc on cc.code=st.class_code
      where gs.guardian_user_id=? and gs.active=false and gs.consent_status='REVOKED'
        and st.environment='PRODUCTION'
      order by gs.revoked_at desc nulls last,gs.id desc
      """,institutionId,context.userId());
  }

  @PostMapping("/children/{studentId}/restore")
  @Transactional
  public Map<String,Object> restoreChild(@PathVariable long studentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    Boolean archivedInInstitution=jdbc.queryForObject("""
      select exists(
        select 1 from guardian_student gs
        join student st on st.id=gs.student_id and st.environment='PRODUCTION'
        join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where gs.guardian_user_id=? and gs.student_id=?
          and gs.active=false and gs.consent_status='REVOKED'
      )
      """,Boolean.class,institutionId,context.userId(),studentId);
    if(!Boolean.TRUE.equals(archivedInInstitution))
      throw new SecurityException("This archived child is not available to this parent in the selected institution.");
    if(!Boolean.TRUE.equals(body.get("consentAccepted")))
      throw new SecurityException("Fresh guardian consent is required to restore a child profile.");
    long guardianId=context.userId();
    List<Map<String,Object>> parentRows=jdbc.queryForList(
        "select id from user_account where id=? and active=true and role in ('PARENT','ADMIN') for update",guardianId);
    if(parentRows.isEmpty()) throw new SecurityException("Active parent account required.");
    Long activeChildren=jdbc.queryForObject("""
      select count(distinct st.id) from guardian_student gs join student st on st.id=gs.student_id
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL'
        and st.active=true and st.environment='PRODUCTION'
      """,Long.class,guardianId);
    if(activeChildren!=null && activeChildren>=Math.max(1,maxChildrenPerParent))
      throw new IllegalStateException("Your account has reached its active child profile limit.");
    List<Map<String,Object>> archived=jdbc.queryForList("""
      select st.id from guardian_student gs join student st on st.id=gs.student_id
      where gs.guardian_user_id=? and gs.student_id=? and gs.active=false
        and gs.consent_status='REVOKED' and st.environment='PRODUCTION'
      for update of gs,st
      """,guardianId,studentId);
    if(archived.isEmpty()) throw new SecurityException("Archived child profile not found for this parent.");
    jdbc.update("""
      update guardian_student set active=true,consent_status='CONSENTED',consented_at=now(),revoked_at=null,
        consent_version=?,consent_source='PARENT_PORTAL',consented_by=?
      where guardian_user_id=? and student_id=? and active=false and consent_status='REVOKED'
      """,AuthService.GUARDIAN_CONSENT_VERSION,guardianId,guardianId,studentId);
    jdbc.update("update student set active=true,profile_updated_at=now() where id=? and environment='PRODUCTION'",studentId);
    jdbc.update("update student_login_credential set active=false where guardian_user_id=? and student_id=?",guardianId,studentId);
    jdbc.update("update auth_session set revoked_at=now() where student_id=? and revoked_at is null",studentId);
    auth.auditChildEvent(guardianId,studentId,"CONSENT_ACCEPTED","{\"consent\":\"affirmative-restore\",\"source\":\"PARENT_PORTAL\"}");
    auth.auditChildEvent(guardianId,studentId,"CHILD_RESTORED","{\"credentialsRequireReset\":true}");
    return Map.of("restored",true,"studentId",studentId,"credentialsRequireReset",true);
  }

  @DeleteMapping("/children/{studentId}")
  @Transactional
  public Map<String,Object> archiveChild(@PathVariable long studentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,context.userId(),studentId);
    if(!Boolean.TRUE.equals(allowed)) throw new SecurityException("Child access denied");
    auth.auditChildEvent(context.userId(),studentId,"CONSENT_REVOKED","{\"source\":\"PARENT_PORTAL\",\"reason\":\"CHILD_ARCHIVED\"}");
    auth.auditChildEvent(context.userId(),studentId,"CHILD_ARCHIVED","{\"learningHistory\":\"retained\"}");
    jdbc.update("update guardian_student set active=false,consent_status='REVOKED',revoked_at=now() where guardian_user_id=? and student_id=?",context.userId(),studentId);
    jdbc.update("update student_login_credential set active=false,updated_at=now() where guardian_user_id=? and student_id=?",context.userId(),studentId);
    Boolean anotherGuardian=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,studentId);
    if(!Boolean.TRUE.equals(anotherGuardian)){
      jdbc.update("update student set active=false where id=? and environment='PRODUCTION'",studentId);
    }
    jdbc.update("update auth_session set revoked_at=now() where student_id=? and revoked_at is null",studentId);
    return Map.of("archived",true,"studentId",studentId,"studentDisabled",!Boolean.TRUE.equals(anotherGuardian));
  }

  @GetMapping("/children/{studentId}/report")
  public Map<String,Object> report(@PathVariable long studentId,
      @RequestParam(required=false) Long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student gs
        join student st on st.id=gs.student_id and st.active=true
        join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where gs.guardian_user_id=? and gs.student_id=? and gs.active=true
          and gs.consent_status='CONSENTED' and gs.access_level='FULL'
          and st.environment='PRODUCTION')
      """,Boolean.class,institutionId,context.userId(),studentId);
    if(!Boolean.TRUE.equals(allowed)) throw new SecurityException("Child access denied in the selected institution.");
    Map<String,Object> child=jdbc.queryForMap("""
      select st.id,st.public_id,st.display_name,st.class_code,st.board,st.language
      from student st join institution_membership m on m.student_id=st.id
        and m.institution_id=? and m.status='ACTIVE'
      where st.id=? and st.active=true and st.environment='PRODUCTION'
      """,institutionId,studentId);

    List<Map<String,Object>> enrolledCourses=jdbc.queryForList("""
      with enrolled_versions as (
        select p.id as course_id,p.display_name as course_name,
          coalesce(identity.stable_course_key,p.code) as course_code,
          identity.public_id as course_identity_public_id,p.content_version_no,
          spe.status as enrollment_status,
          row_number() over (
            partition by coalesce(p.course_identity_id,-p.id)
            order by case when spe.status='ACTIVE' then 0 else 1 end,
              p.content_version_no desc,p.id desc
          ) as identity_rank
        from student_program_enrollment spe
        join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        left join institution_course_identity identity on identity.id=p.course_identity_id
        join institution_course_assignment assignment
          on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
        join institution_course_release release
          on release.id=assignment.course_release_id and release.program_id=p.id
        where spe.student_id=? and spe.institution_id=? and spe.status in ('ACTIVE','PAUSED')
      )
      select course_id,course_name,course_code,course_identity_public_id,enrollment_status
      from enrolled_versions
      where identity_rank=1
      order by course_name,course_code
      """,studentId,institutionId);
    if(enrolledCourses.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This child has no enrolled course in the selected institution. Enrol the child in a course before viewing a learning report.");
    }
    Long selectedCourseId=courseId;
    if(selectedCourseId==null) {
      if(enrolledCourses.size()>1) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Choose one of the child's enrolled courses before viewing this report.");
      }
      selectedCourseId=((Number)enrolledCourses.getFirst().get("course_id")).longValue();
    }
    final long scopedCourseId=selectedCourseId;
    Map<String,Object> selectedCourse=enrolledCourses.stream()
        .filter(item -> ((Number)item.get("course_id")).longValue()==scopedCourseId)
        .findFirst().orElseThrow(() -> new SecurityException(
            "The selected course is not enrolled for this child in the selected institution."));
    Map<String,Object> result=new java.util.LinkedHashMap<>(
        learningMetrics.statsFor(studentId,scopedCourseId,institutionId));
    result.putAll(child);
    result.put("courseId",scopedCourseId);
    result.put("course_id",scopedCourseId);
    result.put("courseName",selectedCourse.get("course_name"));
    result.put("course_name",selectedCourse.get("course_name"));
    result.put("availableCourses",enrolledCourses);
    return result;
  }

  @PostMapping("/children")
  @Transactional
  public Map<String,Object> createChild(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @RequestBody Map<String,Object> body,HttpServletRequest request){
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    UUID requestKey;
    String requestedKey=String.valueOf(body.getOrDefault("requestId","")).trim();
    try {
      requestKey=requestedKey.isBlank()?UUID.randomUUID():UUID.fromString(requestedKey);
    } catch(IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid profile-creation request identifier. Refresh the page and try again.");
    }
    List<Map<String,Object>> parentRows=jdbc.queryForList(
        "select id from user_account where id=? and active=true and role in ('PARENT','ADMIN') for update",context.userId());
    if(parentRows.isEmpty()) throw new SecurityException("Active parent account required.");
    List<Map<String,Object>> prior=jdbc.queryForList("""
      select st.id,gs.active,gs.consent_status,req.institution_id
      from guardian_child_creation_request req
      join student st on st.id=req.student_id
      join guardian_student gs on gs.guardian_user_id=req.guardian_user_id and gs.student_id=st.id
      where req.guardian_user_id=? and req.request_key=?
      """,context.userId(),requestKey);
    if(!prior.isEmpty()) {
      Map<String,Object> priorRow=prior.getFirst();
      long priorInstitution=((Number)priorRow.get("institution_id")).longValue();
      if(priorInstitution!=institutionId)
        throw new SecurityException("This profile-creation request belongs to a different institution.");
      if(!Boolean.TRUE.equals(priorRow.get("active")) || !"CONSENTED".equals(String.valueOf(priorRow.get("consent_status"))))
        throw new IllegalStateException("This creation request already succeeded but the profile was later archived. Reload your profiles before retrying.");
      return childProfileResponse(((Number)priorRow.get("id")).longValue(),institutionId);
    }
    Boolean parentAlreadyInInstitution=jdbc.queryForObject("""
      select exists(
        select 1 from institution_user_membership m
        join user_account u on u.id=m.user_id and u.active=true and u.role='PARENT'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where m.institution_id=? and m.user_id=? and m.status='ACTIVE'
      )
      """,Boolean.class,institutionId,context.userId());
    String joinCode=String.valueOf(body.getOrDefault("joinCode","")).trim();
    if(!Boolean.TRUE.equals(parentAlreadyInInstitution) && joinCode.isBlank()) {
      throw new SecurityException("To create a child in this institution, first join it with a valid institution code.");
    }
    boolean consentAccepted=Boolean.TRUE.equals(body.get("consentAccepted"));
    AuthContext child=auth.createChild(context.userId(),String.valueOf(body.getOrDefault("displayName","")),
        String.valueOf(body.getOrDefault("classCode","7")),String.valueOf(body.getOrDefault("language","hi")),
        String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password","")),
        consentAccepted);
    if(Boolean.TRUE.equals(parentAlreadyInInstitution)) {
      if(memberships==null) throw new IllegalStateException("Institution membership service is not configured.");
      memberships.ensureStudentMembership(child.studentId(),institutionId,"PARENT_CREATED_CHILD",context.userId());
      memberships.ensureUserMembership(context.userId(),institutionId,"PARENT_CREATED_CHILD",null);
    } else {
      if(institutionAuth==null || memberships==null)
        throw new IllegalStateException("Institution onboarding services are not configured.");
      String address=request.getRemoteAddr()==null?"unknown":request.getRemoteAddr();
      institutionAuth.redeemJoinCode(joinCode,child.studentId(),
          institutionAuth.hash("institution-join-source:"+address),tenant.tenantCode());
      // The child joins first by the code; the creating parent becomes an institution user as
      // part of that same transaction, never as an unaffiliated/global account.
      memberships.ensureUserMembership(context.userId(),institutionId,"PARENT_JOIN_CODE_FOR_CHILD",null);
    }
    jdbc.update("""
      insert into guardian_child_creation_request(guardian_user_id,request_key,student_id,institution_id)
      values (?,?,?,?)
      """,context.userId(),requestKey,child.studentId(),institutionId);
    return childProfileResponse(child.studentId(),institutionId);
  }

  /** Kept for source-compatible tests; HTTP child creation must always pass its institution. */
  public Map<String,Object> createChild(AuthContext context,Map<String,Object> body) {
    throw new InstitutionTenantContextService.InvalidInstitutionContextException(
        400,"INSTITUTION_CONTEXT_REQUIRED","Select an institution before creating a child.");
  }

  private Map<String,Object> childProfileResponse(long studentId,long institutionId) {
    return jdbc.queryForMap("""
      select st.id,st.public_id,st.display_name,st.class_code,st.board,st.language,
        coalesce((select string_agg(distinct coalesce(identity.display_name,p.display_name),', ' order by coalesce(identity.display_name,p.display_name))
                 from student_program_enrollment spe
                 join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
                 left join institution_course_identity identity on identity.id=p.course_identity_id
                 join institution_course_assignment assignment on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
                 join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
                 where spe.student_id=st.id and spe.institution_id=? and spe.status in ('ACTIVE','PAUSED')),'') as course_names
      from student st join institution_membership m on m.student_id=st.id
        and m.institution_id=? and m.status='ACTIVE'
      where st.id=?
      """,institutionId,institutionId,studentId);
  }

  private Map<String,Object> childProfileResponse(long studentId) {
    throw new InstitutionTenantContextService.InvalidInstitutionContextException(
        400,"INSTITUTION_CONTEXT_REQUIRED","Select an institution before reading a child profile.");
  }

  @PutMapping("/children/{studentId}/profile")
  @Transactional
  public Map<String,Object> updateChildProfile(@PathVariable long studentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    auth.updateChildProfile(context.userId(),studentId,
        String.valueOf(body.getOrDefault("displayName","")),
        String.valueOf(body.getOrDefault("classCode","")),
        String.valueOf(body.getOrDefault("language","")));
    return childProfileResponse(studentId);
  }

  @PutMapping("/children/{studentId}/credentials")
  @Transactional
  public Map<String,Object> updateChildCredentials(@PathVariable long studentId,
      @RequestBody Map<String,Object> body,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    auth.setChildCredentials(context.userId(),studentId,
        String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password","")));
    return Map.of("saved",true,"studentId",studentId);
  }

  /** Subject-only track editing is retired; access is granted through institution courses. */
  @PutMapping("/children/{studentId}/tracks")
  public Map<String,Object> updateChildTracks(@PathVariable long studentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    requireFullGuardianChildAccess(context.userId(),studentId);
    requireChildInstitutionMembership(studentId,institutionId);
    throw new IllegalArgumentException("Subject-only enrollment is no longer supported. Assign an institution course instead.");
  }


  /** Independent courses are assigned to a child separately from class/subject selection. */
  @GetMapping("/children/{studentId}/programs")
  @Transactional(readOnly = true)
  public List<Map<String,Object>> childPrograms(@PathVariable long studentId,
      @RequestParam(name="includeSubjects",defaultValue="false") boolean includeSubjects,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    requireFullGuardianChildAccess(context.userId(),studentId);
    requireChildInstitutionMembership(studentId,institutionId);
    List<Map<String,Object>> programs;
    try {
      programs=jdbc.queryForList("""
            select distinct p.id,p.public_id,p.code,p.display_name,p.curriculum_version,p.status,
              exists(select 1 from student_program_enrollment spe
                where spe.institution_id=? and spe.student_id=? and spe.program_id=p.id
                  and spe.status='ACTIVE') as enrolled,
              exists(select 1 from student_program_enrollment spe
                where spe.institution_id=? and spe.student_id=? and spe.program_id=p.id
                  and spe.status='PAUSED') as paused,
              exists(select 1
                from institution_marketplace_offer free_offer
                join institution_course_assignment free_assignment
                  on free_assignment.id=free_offer.assignment_id
                 and free_assignment.institution_id=free_offer.institution_id
                 and free_assignment.status='ASSIGNED'
                where free_offer.institution_id=assignment.institution_id
                  and free_offer.course_id=p.id and free_offer.status='PUBLISHED'
                   and qe_marketplace_offer_publicly_sellable(free_offer.id)
                  and free_offer.price_paise=0 and free_offer.currency='INR') as free_offer
            from curriculum_program p
            join institution_course_assignment assignment on assignment.institution_id=?
              and assignment.status='ASSIGNED'
            join institution_course_release release on release.id=assignment.course_release_id
              and release.program_id=p.id
            join institution_tenant t on t.id=assignment.institution_id and t.status='ACTIVE'
            where p.status='ACTIVE' and p.code<>'GENERAL'
              and (
                exists(select 1 from institution_marketplace_offer o
                  where o.institution_id=? and o.course_id=p.id and o.assignment_id=assignment.id
                    and o.status='PUBLISHED' and qe_marketplace_offer_publicly_sellable(o.id))
                or exists(select 1 from student_program_enrollment e
                  where e.institution_id=? and e.student_id=? and e.program_id=p.id
                    and e.status in ('ACTIVE','PAUSED'))
                or exists(select 1 from course_purchase cp
                  where cp.institution_id=? and cp.student_id=? and cp.course_id=p.id)
                or exists(select 1 from course_entitlement ce
                  where ce.institution_id=? and ce.course_id=p.id
                    and (ce.holder_student_id=? or ce.holder_user_id=(select user_account_id from student where id=?)))
              )
            order by p.display_name,p.id
            """, institutionId,studentId,institutionId,studentId,institutionId,institutionId,institutionId,studentId,
                institutionId,studentId,institutionId,studentId,studentId);
    if(includeSubjects) {
      for(Map<String,Object> program:programs) {
        long courseId=((Number)program.get("id")).longValue();
        program.put("subjects",jdbc.queryForList("""
              select s.id,s.code as subject_code,s.display_name as subject_name,
                c.code as curriculum_class_code,count(distinct ch.id) as chapters,count(distinct l.id) as lessons
              from curriculum_program_subject ps
              join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
              join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
                and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
              join curriculum_class c on c.id=s.class_id
              left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
              left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
              where ps.program_id=?
              group by ps.sort_order,s.id,s.code,s.display_name,c.code,s.sort_order
              order by ps.sort_order,s.sort_order,s.id
              """,courseId));
      }
    }
    } catch (DataAccessException ex) {
      // Keep the tenant and caller context in the server log so a genuine database/schema
      // failure can be diagnosed without exposing SQL details to the browser.
      LOGGER.error("Failed to load guardian child course catalogue (guardianUserId={}, studentId={}, institutionId={})",
          context.userId(), studentId, institutionId, ex);
      throw ex;
    }
    return programs;
  }

  /** Paid offers must use the server-authoritative checkout / parent request flow. */
  @PostMapping("/children/{studentId}/programs/{courseId}/enroll")
  @Transactional
  public Map<String,Object> enrollChildInProgram(@PathVariable long studentId,@PathVariable long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireParent(context);
    long institutionId=requireInstitution(tenant);
    requireFullGuardianChildAccess(context.userId(),studentId);
    requireChildInstitutionMembership(studentId,institutionId);
    List<Map<String,Object>> courses=jdbc.queryForList("""
      select p.id,p.public_id,p.code,p.display_name
      from curriculum_program p
      join institution_course_assignment a on a.institution_id=? and a.status='ASSIGNED'
      join institution_course_release r on r.id=a.course_release_id and r.program_id=p.id
      join institution_tenant t on t.id=a.institution_id and t.status='ACTIVE'
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
        and (exists(select 1 from institution_marketplace_offer o
                    where o.institution_id=a.institution_id and o.assignment_id=a.id
                      and o.course_id=p.id and o.status='PUBLISHED' and qe_marketplace_offer_publicly_sellable(o.id) and o.price_paise=0 and o.currency='INR')
          or exists(select 1 from student_program_enrollment e
                    where e.institution_id=a.institution_id and e.student_id=? and qe_same_logical_course(e.program_id,p.id)
                      and e.status in ('ACTIVE','PAUSED'))
          or exists(select 1 from course_purchase cp
                    where cp.institution_id=a.institution_id and cp.student_id=? and qe_same_logical_course(cp.course_id,p.id))
          or exists(select 1 from course_entitlement ce
                    where ce.institution_id=a.institution_id and qe_same_logical_course(ce.course_id,p.id)
                      and (ce.holder_student_id=? or ce.holder_user_id=(select user_account_id from student where id=?))))
      """,institutionId,courseId,studentId,studentId,studentId,studentId);
    if(courses.isEmpty()) throw new SecurityException("This course has no published offer or existing entitlement in the selected institution.");
    List<Map<String,Object>> subjects=jdbc.queryForList("""
      select s.id,s.code
      from curriculum_program_subject ps
      join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """,courseId);
    if(subjects.isEmpty())throw new IllegalStateException("This course has no active subject tracks yet.");
    // Enrolling in one course must not pause other course enrollments; report focus is separate.
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',now(),null,now(),now())
      on conflict(institution_id,student_id,program_id) do update set
        status='ACTIVE',enrolled_at=case when student_program_enrollment.status='ACTIVE'
          then student_program_enrollment.enrolled_at else now() end,
        ended_at=null,updated_at=now()
      """,studentId,courseId,institutionId);
    auth.auditChildEvent(context.userId(),studentId,"COURSE_ENROLLED",
        "{\"courseId\":"+courseId+",\"institutionId\":"+institutionId+",\"subjectCount\":"+subjects.size()+"}");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("enrolled",true);result.put("studentId",studentId);result.put("courseId",courseId);
    result.put("institutionId",institutionId);
    result.put("publicId",courses.getFirst().get("public_id"));
    result.put("programCode",courses.getFirst().get("code"));
    result.put("programName",courses.getFirst().get("display_name"));
    result.put("subjectCount",subjects.size());
    return result;
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() <= 0) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before managing a child's courses.");
    }
    return tenant.institutionId();
  }

  private void requireChildInstitutionMembership(long studentId,long institutionId) {
    Boolean member=jdbc.queryForObject("""
      select exists(select 1 from institution_membership m
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join student st on st.id=m.student_id and st.active=true
        where m.institution_id=? and m.student_id=? and m.status='ACTIVE')
      """,Boolean.class,institutionId,studentId);
    if(!Boolean.TRUE.equals(member)) {
      throw new SecurityException("The selected child is not an active member of this institution.");
    }
  }

  private void requireFullGuardianChildAccess(long guardianUserId,long studentId) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student gs join student st on st.id=gs.student_id
        where gs.guardian_user_id=? and gs.student_id=? and gs.active=true
          and gs.consent_status='CONSENTED' and gs.access_level='FULL'
          and st.active=true and st.environment='PRODUCTION')
      """,Boolean.class,guardianUserId,studentId);
    if(!Boolean.TRUE.equals(allowed))throw new SecurityException("Full guardian access to this child is required.");
  }

}
