package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final LearningMetricsService learningMetrics;
  public StudentController(JdbcTemplate jdbc,AuthorizationService authorization,LearningMetricsService learningMetrics){
    this.jdbc=jdbc;this.authorization=authorization;this.learningMetrics=learningMetrics;
  }

  @GetMapping("/me")
  public Map<String,Object> me(
      @RequestParam Long courseId,
      @RequestParam(required=false) String subjectCode,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    if(courseId==null || courseId<=0L)
      throw new IllegalArgumentException("Select an enrolled course before requesting learning metrics.");
    List<Map<String,Object>> enrolled=jdbc.queryForList("""
      select distinct p.id,p.code,p.display_name
      from curriculum_program p
      join student_program_enrollment spe on spe.program_id=p.id
        and spe.student_id=? and spe.institution_id=? and spe.status='ACTIVE'
         and spe.course_access_blocked=false
         and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      join institution_membership membership
        on membership.student_id=spe.student_id and membership.institution_id=spe.institution_id
           and membership.status='ACTIVE'
      join institution_course_assignment assignment
        on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=assignment.course_release_id and release.program_id=p.id
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """,context.studentId(),institutionId,courseId);
    if(enrolled.size()!=1)
      throw new SecurityException("The selected course is not actively enrolled and assigned in this institution.");
    Map<String,Object> course=enrolled.getFirst();
    long stableCourseId=((Number)course.get("id")).longValue();
    String selectedSubject=subjectCode==null||subjectCode.isBlank()||subjectCode.trim().equalsIgnoreCase("ALL")
        ? null : subjectCode.trim().toLowerCase(java.util.Locale.ROOT);
    if(selectedSubject!=null) {
      if(!selectedSubject.matches("[a-z0-9][a-z0-9_-]{0,39}"))
        throw new IllegalArgumentException("Choose a subject from the selected course.");
      Long mappedSubjectCount=jdbc.queryForObject("""
        select count(*)
        from curriculum_program_subject cps
        join curriculum_program selected_course
          on selected_course.id=cps.program_id and selected_course.status='ACTIVE'
        join curriculum_subject s
          on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
          and (s.program_id=cps.program_id
            or (s.program_id is null and selected_course.class_id=s.class_id and cps.class_id=s.class_id))
        where cps.program_id=? and lower(s.code)=lower(?)
        """,Long.class,stableCourseId,selectedSubject);
      if(mappedSubjectCount==null||mappedSubjectCount<1L)
        throw new SecurityException("This subject does not belong to the selected course.");
    }
    Map<String,Object> result=learningMetrics.statsFor(context.studentId(),stableCourseId,institutionId,selectedSubject);
    result.put("courseId",stableCourseId);
    result.put("courseName",course.get("display_name"));
    return result;
  }

  @GetMapping("/me/profile")
  public Map<String,Object> myProfile(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    return profileFor(context.studentId());
  }


  @GetMapping(value="/me/profile-photo",produces=MediaType.IMAGE_JPEG_VALUE)
  public ResponseEntity<byte[]> myProfilePhoto(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    String data=jdbc.queryForObject(
        "select profile_image_data_url from student where id=? and active=true",
        String.class,context.studentId());
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

  @PutMapping("/me/profile-photo")
  @Transactional
  public Map<String,Object> updateMyProfilePhoto(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    if(!body.containsKey("profileImageDataUrl"))
      throw new IllegalArgumentException("Choose a photo or remove the current photo.");

    String image=validatedProfileImage(body.get("profileImageDataUrl"));
    int changed=jdbc.update(
        "update student set profile_image_data_url=?,profile_updated_at=now() where id=? and active=true",
        image,context.studentId());
    if(changed!=1) throw new SecurityException("Active student profile not found.");

    Map<String,Object> result=new java.util.LinkedHashMap<>();
    result.put("saved",true);
    result.put("profile_image_url",image==null?null:"/api/v1/students/me/profile-photo?v="+System.currentTimeMillis());
    return result;
  }

  @PutMapping("/me/profile")
  @Transactional
  public Map<String,Object> updateMyProfile(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    long studentId=context.studentId();

    String displayName=profileText(body.get("displayName"),"Name",120);
    if(displayName.length()<2) throw new IllegalArgumentException("Name must contain at least 2 characters.");
    String city=profileText(body.get("city"),"City or town",100);
    String state=profileText(body.get("state"),"State",100);
    String schoolName=profileText(body.get("schoolName"),"School name",180);
    String schoolMedium=profileText(body.get("schoolMedium"),"School medium",40);
    String favoriteSubject=profileText(body.get("favoriteSubject"),"Favourite subject",30);
    String learningGoal=profileText(body.get("learningGoal"),"Learning goal",300);

    if(!schoolMedium.isEmpty() && !List.of("Hindi","English","Hindi & English","Other").contains(schoolMedium))
      throw new IllegalArgumentException("Choose a valid school medium.");
    if(!favoriteSubject.isEmpty() && !List.of("maths","science","both","other","not_sure").contains(favoriteSubject))
      throw new IllegalArgumentException("Choose a valid favourite subject.");

    String image;
    if(body.containsKey("profileImageDataUrl")) {
      image=validatedProfileImage(body.get("profileImageDataUrl"));
    } else {
      image=jdbc.queryForObject("select profile_image_data_url from student where id=? and active=true",String.class,studentId);
    }

    int changed=jdbc.update("""
      update student
      set display_name=?,profile_image_data_url=?,city=?,state=?,school_name=?,
          school_medium=?,favorite_subject=?,learning_goal=?,profile_updated_at=now()
      where id=? and active=true
      """,displayName,image,nullable(city),nullable(state),nullable(schoolName),
         nullable(schoolMedium),nullable(favoriteSubject),nullable(learningGoal),studentId);
    if(changed!=1) throw new SecurityException("Active student profile not found.");
    // Independent learner accounts share the same person identity as their student profile.
    // Keep the canonical account name synchronized when that learner edits their profile.
    jdbc.update("""
      update user_account set display_name=?,updated_at=now()
      where id=(select user_account_id from student where id=? and user_account_id is not null)
        and active=true
      """, displayName, studentId);
    return profileFor(studentId);
  }

  @PatchMapping("/me/language")
  @Transactional
  public Map<String,Object> updateMyLanguage(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    String language=body.get("language")==null?"":String.valueOf(body.get("language")).trim().toLowerCase(java.util.Locale.ROOT);
    if(!List.of("hi","en").contains(language)) {
      throw new IllegalArgumentException("Language must be 'hi' for Hindi or 'en' for English.");
    }
    int changed=jdbc.update("update student set language=? where id=? and active=true",language,context.studentId());
    if(changed!=1) throw new SecurityException("Active student profile not found.");
    return Map.of("language",language);
  }

  private Map<String,Object> profileFor(long studentId) {
    return jdbc.queryForMap("""
      select st.id,st.public_id,st.public_user_id,st.display_name,st.class_code,st.board,st.language,
             case when st.profile_image_data_url is not null
               then '/api/v1/students/me/profile-photo?v=' || floor(extract(epoch from st.profile_updated_at)*1000)::bigint::text
               else u.google_picture_url end as profile_image_url,
             st.city,st.state,st.school_name,st.school_medium,
             st.favorite_subject,st.learning_goal,st.profile_updated_at
      from student st
      left join user_account u on u.id=st.user_account_id
      where st.id=? and st.active=true
      """,studentId);
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

  @GetMapping("/preview")
  public Map<String,Object> preview(@RequestAttribute(value="authContext",required=false) AuthContext context){
    if(context==null || context.studentId()==null) throw new SecurityException("Authenticated local preview student required");
    List<Map<String,Object>> students=jdbc.queryForList("""
      select id from student where id=? and environment='LOCAL_PREVIEW' and active=true
      """,context.studentId());
    if(students.isEmpty()) throw new SecurityException("Local preview is not available for this account");
    Map<String,Object> result=statsFor(((Number)students.getFirst().get("id")).longValue()); result.put("preview",true); return result;
  }

  @GetMapping("/{studentId}/lessons")
  public List<Map<String,Object>> lessons(@PathVariable long studentId,
      @RequestParam Long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    if(context.studentId()==null || !context.studentId().equals(studentId))
      throw new SecurityException("Student access denied");
    if(courseId==null || courseId<=0L)
      throw new IllegalArgumentException("Select an enrolled course before loading lessons.");
    return jdbc.queryForList("""
      select l.id,selected_course.id as course_id,selected_course.code as course_code,
             selected_course.display_name as course_name,c.code as source_class_code,
             s.code as subject_code,s.display_name as subject_name,
             ch.code as chapter_code,ch.display_name as chapter_name,l.code,l.title,l.summary,l.estimated_minutes,
             l.sort_order,coalesce(progress.status,'NOT_STARTED') as progress_status,
             coalesce(progress.progress_percent,0) as progress_percent
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=? and st.active=true
      join institution_membership membership
        on membership.student_id=st.id and membership.institution_id=? and membership.status='ACTIVE'
      join institution_tenant tenant_scope on tenant_scope.id=membership.institution_id and tenant_scope.status='ACTIVE'
      join student_program_enrollment enrollment
        on enrollment.student_id=st.id and enrollment.institution_id=membership.institution_id
          and enrollment.program_id=? and enrollment.status='ACTIVE'
          and enrollment.course_access_blocked=false
          and (enrollment.course_expires_at is null or enrollment.course_expires_at>statement_timestamp())
      join curriculum_program selected_course
        on selected_course.id=enrollment.program_id and selected_course.status='ACTIVE' and selected_course.code<>'GENERAL'
      join institution_course_assignment assignment
        on assignment.institution_id=membership.institution_id and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=assignment.course_release_id and release.program_id=selected_course.id
      join curriculum_program_subject mapped
        on mapped.program_id=selected_course.id and mapped.subject_id=s.id and mapped.class_id=s.class_id
      left join student_lesson_progress progress
        on progress.lesson_id=l.id and progress.student_id=st.id and progress.institution_id=membership.institution_id
      where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and c.active=true and s.active=true
        and (s.program_id=selected_course.id
          or (s.program_id is null and selected_course.class_id=s.class_id))
      order by mapped.sort_order,c.sort_order,s.sort_order,
        coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,studentId,institutionId,courseId);
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "Select the institution for learner metrics.");
    }
    return tenant.institutionId();
  }

  private Map<String,Object> statsFor(long studentId){
    return learningMetrics.statsFor(studentId);
  }
}
