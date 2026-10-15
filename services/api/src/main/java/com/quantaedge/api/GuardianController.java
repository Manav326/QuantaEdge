package com.quantaedge.api;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianController {
  private final JdbcTemplate jdbc; private final AuthorizationService authorization; private final AuthService auth;
  private final LearningMetricsService learningMetrics;
  @Value("${app.auth.max-children-per-parent:3}") private int maxChildrenPerParent=3;
  public GuardianController(JdbcTemplate jdbc,AuthorizationService authorization,AuthService auth,
      LearningMetricsService learningMetrics){
    this.jdbc=jdbc;this.authorization=authorization;this.auth=auth;this.learningMetrics=learningMetrics;
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
      set display_name=?,profile_image_data_url=?,email=?,city=?,state=?,occupation=?,
          organization=?,preferred_language=?,profile_updated_at=now(),updated_at=now()
      where id=? and active=true and role='PARENT'
      """,name,image,nullable(email),nullable(city),nullable(state),nullable(occupation),
         nullable(organization),preferredLanguage,userId);
    if(changed!=1) throw new SecurityException("Active parent account not found.");
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
      select id,public_id,display_name,mobile_e164,email,city,state,occupation,
             organization,preferred_language,
             case when profile_image_data_url is not null then '/api/v1/guardians/profile-photo?v=' || floor(extract(epoch from profile_updated_at)*1000)::bigint::text else null end as profile_image_url,
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
  public List<Map<String,Object>> children(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.class_code,cc.display_name as class_name,st.board,st.language,
             slc.username as login_username,gs.relationship,gs.consent_status,
             coalesce((select string_agg(s.code,',' order by s.sort_order) from student_track_enrollment ste
               join curriculum_subject s on s.id=ste.subject_id
               where ste.student_id=st.id and ste.status='ACTIVE'),'') as track_codes
      from guardian_student gs join student st on st.id=gs.student_id
      left join curriculum_class cc on cc.code=st.class_code
      left join student_login_credential slc on slc.guardian_user_id=gs.guardian_user_id and slc.student_id=st.id and slc.active=true
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL'
        and st.active=true and st.environment='PRODUCTION' order by st.created_at
      """,context.userId());
  }

  @GetMapping("/children/archived")
  public List<Map<String,Object>> archivedChildren(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.class_code,cc.display_name as class_name,st.board,st.language,
             gs.relationship,gs.consent_status
      from guardian_student gs join student st on st.id=gs.student_id
      left join curriculum_class cc on cc.code=st.class_code
      where gs.guardian_user_id=? and gs.active=false and gs.consent_status='REVOKED'
        and st.environment='PRODUCTION'
      order by gs.revoked_at desc nulls last,gs.id desc
      """,context.userId());
  }

  @PostMapping("/children/{studentId}/restore")
  @Transactional
  public Map<String,Object> restoreChild(@PathVariable long studentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
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
  public Map<String,Object> report(@PathVariable long studentId,@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED' and access_level='FULL')
      """,Boolean.class,context.userId(),studentId);
    if(!allowed) throw new SecurityException("Child access denied");
    Map<String,Object> child=jdbc.queryForMap("""
      select id,public_id,display_name,class_code,board,language
      from student where id=? and active=true
      """,studentId);
    Map<String,Object> result=new java.util.LinkedHashMap<>(learningMetrics.statsFor(studentId));
    result.putAll(child);
    return result;
  }

  @PostMapping("/children")
  @Transactional
  public Map<String,Object> createChild(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body){
    context=authorization.requireParent(context);
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
      select st.id,gs.active,gs.consent_status
      from guardian_child_creation_request req
      join student st on st.id=req.student_id
      join guardian_student gs on gs.guardian_user_id=req.guardian_user_id and gs.student_id=st.id
      where req.guardian_user_id=? and req.request_key=?
      """,context.userId(),requestKey);
    if(!prior.isEmpty()) {
      Map<String,Object> priorRow=prior.getFirst();
      if(!Boolean.TRUE.equals(priorRow.get("active")) || !"CONSENTED".equals(String.valueOf(priorRow.get("consent_status"))))
        throw new IllegalStateException("This creation request already succeeded but the profile was later archived. Reload your profiles before retrying.");
      return childProfileResponse(((Number)priorRow.get("id")).longValue());
    }
    boolean consentAccepted=Boolean.TRUE.equals(body.get("consentAccepted"));
    AuthContext child=auth.createChild(context.userId(),String.valueOf(body.getOrDefault("displayName","")),
        String.valueOf(body.getOrDefault("classCode","7")),String.valueOf(body.getOrDefault("language","hi")),
        String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password","")),
        consentAccepted,
        body.get("trackCodes") instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of("maths","science"));
    jdbc.update("insert into guardian_child_creation_request(guardian_user_id,request_key,student_id) values (?,?,?)",
        context.userId(),requestKey,child.studentId());
    return childProfileResponse(child.studentId());
  }

  private Map<String,Object> childProfileResponse(long studentId) {
    return jdbc.queryForMap("""
      select st.id,st.public_id,st.display_name,st.class_code,st.board,st.language,
        coalesce((select string_agg(s.code,',' order by s.sort_order) from student_track_enrollment ste
          join curriculum_subject s on s.id=ste.subject_id where ste.student_id=st.id and ste.status='ACTIVE'),'') as track_codes
      from student st where st.id=?
      """,studentId);
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

  @PutMapping("/children/{studentId}/tracks")
  @Transactional
  public Map<String,Object> updateChildTracks(@PathVariable long studentId,
      @RequestBody Map<String,Object> body,@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student gs join student st on st.id=gs.student_id
        where gs.guardian_user_id=? and gs.student_id=? and gs.active=true and gs.consent_status='CONSENTED'
          and st.active=true and st.environment='PRODUCTION')
      """,Boolean.class,context.userId(),studentId);
    if(!allowed) throw new SecurityException("Child access denied");
    List<String> tracks=body.get("trackCodes") instanceof List<?> values
        ? values.stream().map(String::valueOf).toList() : List.of();
    List<String> activeTracks=auth.updateStudentTracks(studentId,tracks);
    auth.auditChildEvent(context.userId(),studentId,"TRACKS_UPDATED",
        "{\"trackCodes\":\""+String.join(",",activeTracks)+"\"}");
    return Map.of("updated",true,"studentId",studentId,"trackCodes",activeTracks);
  }



  /** Independent courses are assigned to a child separately from class/subject selection. */
  @GetMapping("/children/{studentId}/programs")
  public List<Map<String,Object>> childPrograms(@PathVariable long studentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    requireFullGuardianChildAccess(context.userId(),studentId);
    List<Map<String,Object>> programs=jdbc.queryForList("""
      select p.id,p.public_id,p.code,p.display_name,p.curriculum_version,p.status,
        exists(select 1 from student_program_enrollment spe
          where spe.student_id=? and spe.program_id=p.id and spe.status='ACTIVE') as enrolled
      from curriculum_program p
      where p.status='ACTIVE' and p.code<>'GENERAL'
      order by p.display_name,p.id
      """,studentId);
    for(Map<String,Object> program:programs) {
      long courseId=((Number)program.get("id")).longValue();
      program.put("subjects",jdbc.queryForList("""
        select s.id,s.code as subject_code,s.display_name as subject_name,
          c.code as curriculum_class_code,
          count(distinct ch.id) as chapters,count(distinct l.id) as lessons
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
        join curriculum_class c on c.id=s.class_id
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
          and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where ps.program_id=?
        group by s.id,s.code,s.display_name,c.code,s.sort_order
        order by ps.sort_order,s.sort_order,s.id
        """,courseId));
    }
    return programs;
  }

  /** Idempotent parent-to-child course assignment; the child's identity and history are untouched. */
  @PostMapping("/children/{studentId}/programs/{courseId}/enroll")
  @Transactional
  public Map<String,Object> enrollChildInProgram(@PathVariable long studentId,@PathVariable long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireParent(context);
    requireFullGuardianChildAccess(context.userId(),studentId);
    List<Map<String,Object>> courses=jdbc.queryForList("""
      select id,public_id,code,display_name from curriculum_program
      where id=? and status='ACTIVE' and code<>'GENERAL'
      """,courseId);
    if(courses.isEmpty())throw new IllegalArgumentException("This course is not available or active.");
    List<Map<String,Object>> subjects=jdbc.queryForList("""
      select s.id,s.code
      from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
        and s.class_id=ps.class_id and s.active=true
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """,courseId);
    if(subjects.isEmpty())throw new IllegalStateException("This course has no active subject tracks yet.");
    jdbc.update("""
      insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
      values(?,?,'ACTIVE',now(),null,now(),now())
      on conflict(student_id,program_id) do update set
        status='ACTIVE',enrolled_at=case when student_program_enrollment.status='ACTIVE'
          then student_program_enrollment.enrolled_at else now() end,
        ended_at=null,updated_at=now()
      """,studentId,courseId);
    for(Map<String,Object> subject:subjects) {
      jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,status)
        values(?,?,'ACTIVE')
        on conflict(student_id,subject_id) do update set status='ACTIVE',updated_at=now()
        """,studentId,((Number)subject.get("id")).longValue());
    }
    auth.auditChildEvent(context.userId(),studentId,"COURSE_ENROLLED",
        "{\"courseId\":"+courseId+",\"subjectCount\":"+subjects.size()+"}");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("enrolled",true);result.put("studentId",studentId);result.put("courseId",courseId);
    result.put("publicId",courses.getFirst().get("public_id"));
    result.put("programCode",courses.getFirst().get("code"));
    result.put("programName",courses.getFirst().get("display_name"));
    result.put("subjectCount",subjects.size());
    return result;
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
