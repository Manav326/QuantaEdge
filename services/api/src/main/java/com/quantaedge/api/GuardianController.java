package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import java.util.Base64;
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
  @Value("${app.auth.max-children-per-parent:3}") private int maxChildrenPerParent=3;
  public GuardianController(JdbcTemplate jdbc,AuthorizationService authorization,AuthService auth){this.jdbc=jdbc;this.authorization=authorization;this.auth=auth;}

  @GetMapping("/account")
  public Map<String,Object> account(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    long activeChildren=jdbc.queryForObject("""
      select count(distinct st.id) from guardian_student gs join student st on st.id=gs.student_id
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED'
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

  private Map<String,Object> parentProfile(long userId) {
    return jdbc.queryForMap("""
      select id,public_id,display_name,mobile_e164,email,city,state,occupation,
             organization,preferred_language,
             case when profile_image_data_url is not null then '/api/v1/guardians/profile-photo' else null end as profile_image_url,
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
      join curriculum_class cc on cc.code=st.class_code
      left join student_login_credential slc on slc.guardian_user_id=gs.guardian_user_id and slc.student_id=st.id and slc.active=true
      where gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED'
        and st.active=true and st.environment='PRODUCTION' order by st.created_at
      """,context.userId());
  }

  @DeleteMapping("/children/{studentId}")
  @Transactional
  public Map<String,Object> archiveChild(@PathVariable long studentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,context.userId(),studentId);
    if(!allowed) throw new SecurityException("Child access denied");
    jdbc.update("update guardian_student set active=false,consent_status='REVOKED' where guardian_user_id=? and student_id=?",context.userId(),studentId);
    jdbc.update("update student_login_credential set active=false,updated_at=now() where guardian_user_id=? and student_id=?",context.userId(),studentId);
    Boolean anotherGuardian=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,studentId);
    if(!anotherGuardian){
      jdbc.update("update student set active=false where id=? and environment='PRODUCTION'",studentId);
      jdbc.update("update auth_session set revoked_at=now() where student_id=? and revoked_at is null",studentId);
    }
    return Map.of("archived",true,"studentId",studentId,"studentDisabled",!anotherGuardian);
  }

  @GetMapping("/children/{studentId}/report")
  public Map<String,Object> report(@PathVariable long studentId,@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,context.userId(),studentId);
    if(!allowed) throw new SecurityException("Child access denied");
    Map<String,Object> child=jdbc.queryForMap("""
      select id,public_id,display_name,class_code,board,language from student where id=? and active=true
      """,studentId);
    Map<String,Object> result=new java.util.LinkedHashMap<>(child);
    result.put("lessonStats",jdbc.queryForMap("""
      select count(*) filter(where l.active=true and l.status='PUBLISHED') as total_lessons,
             count(*) filter(where p.status='COMPLETED') as completed_lessons,
             coalesce(round(100.0*count(*) filter(where p.status='COMPLETED')/
               nullif(count(*) filter(where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'),0),1),0) as completion_percent
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      """,studentId,studentId));
    result.put("questionStats",jdbc.queryForMap("""
      select count(*) as attempts,count(*) filter(where correct=true) as correct,
             count(*) filter(where correct is not null) as graded_attempts,
             coalesce(round(100.0*count(*) filter(where correct=true)/
               nullif(count(*) filter(where correct is not null),0),1),0) as accuracy_percent
      from student_question_attempt where student_id=?
      """,studentId));
    result.put("curriculum",jdbc.queryForList("""
      select c.code as class_code,s.code as subject_code,s.display_name as subject_name,
             count(distinct ch.id) as chapters,
             count(distinct l.id) filter(where l.active=true and l.status='PUBLISHED') as lessons,
             count(distinct p.id) filter(where p.status='COMPLETED') as completed
      from curriculum_class c join curriculum_subject s on s.class_id=c.id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      join curriculum_chapter ch on ch.subject_id=s.id
      left join lesson l on l.chapter_id=ch.id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.code=(select class_code from student where id=?)
      group by c.code,s.code,s.display_name,s.sort_order order by s.sort_order
      """,studentId,studentId,studentId));
    result.put("sessionStats",jdbc.queryForMap("""
      select count(*) as sessions_30d,
             coalesce(sum(minutes) filter(where started_at>=now()-interval '30 days'),0) as minutes_30d,
             coalesce(round(avg(minutes) filter(where ended_at is not null and started_at>=now()-interval '30 days'),1),0) as avg_minutes
      from learning_session where student_id=?
      """,studentId));
    result.put("recentAttempts",jdbc.queryForList("""
      select qa.answered_at,qa.correct,q.question_type,ch.display_name as chapter_name,
             cc.title as concept_title
      from student_question_attempt qa
      join question q on q.id=qa.question_id
      left join chapter_concept cc on cc.id=q.concept_id
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      where qa.student_id=?
      order by qa.answered_at desc limit 20
      """,studentId));
    result.put("masteryDetails",jdbc.queryForList("""
      select cc.title as concept_title,ch.display_name as chapter_name,s.display_name as subject_name,
             m.mastery_percent,m.attempts
      from student_concept_mastery m
      join chapter_concept cc on cc.id=m.concept_id
      join curriculum_chapter ch on ch.id=cc.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where m.student_id=? order by m.mastery_percent asc,coalesce(ch.teaching_order,ch.sort_order),cc.concept_order limit 20
      """,studentId));
    return result;
  }

  @PostMapping("/children")
  public Map<String,Object> createChild(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body){
    context=authorization.requireParent(context);
    boolean consentAccepted=Boolean.TRUE.equals(body.get("consentAccepted"));
    AuthContext child=auth.createChild(context.userId(),String.valueOf(body.getOrDefault("displayName","")),
        String.valueOf(body.getOrDefault("classCode","7")),String.valueOf(body.getOrDefault("language","hi")),
        String.valueOf(body.getOrDefault("username","")),String.valueOf(body.getOrDefault("password","")),
        consentAccepted,
        body.get("trackCodes") instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of("maths","science"));
    return jdbc.queryForMap("""
      select st.id,st.public_id,st.display_name,st.class_code,st.board,st.language,
        coalesce((select string_agg(s.code,',' order by s.sort_order) from student_track_enrollment ste
          join curriculum_subject s on s.id=ste.subject_id where ste.student_id=st.id and ste.status='ACTIVE'),'') as track_codes
      from student st where st.id=?
      """,child.studentId());
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
    return Map.of("updated",true,"studentId",studentId,"trackCodes",activeTracks);
  }
}
