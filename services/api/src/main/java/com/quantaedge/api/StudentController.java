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
      @RequestParam(required=false) String programCode,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireStudent(context);
    if(programCode==null || programCode.isBlank()) return statsFor(context.studentId());
    String requested=programCode.trim().toUpperCase(java.util.Locale.ROOT);
    List<Map<String,Object>> enrolled=jdbc.queryForList("""
      select p.id,p.code,p.display_name
      from curriculum_program p
      join curriculum_class c on c.id=p.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_program_enrollment spe on spe.student_id=st.id
        and spe.program_id=p.id and spe.status='ACTIVE'
      where upper(p.code)=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """,context.studentId(),requested);
    if(enrolled.isEmpty()) throw new SecurityException("Active enrolment in this course is required.");
    Map<String,Object> program=enrolled.getFirst();
    long programId=((Number)program.get("id")).longValue();
    Map<String,Object> result=learningMetrics.statsFor(context.studentId(),programId);
    result.put("programId",programId);
    result.put("programCode",program.get("code"));
    result.put("programName",program.get("display_name"));
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
      select id,public_id,display_name,class_code,board,language,
             case when profile_image_data_url is not null then '/api/v1/students/me/profile-photo?v=' || floor(extract(epoch from profile_updated_at)*1000)::bigint::text else null end as profile_image_url,
             city,state,school_name,school_medium,
             favorite_subject,learning_goal,profile_updated_at
      from student where id=? and active=true
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
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireStudent(context);
    if(context.studentId()==null || !context.studentId().equals(studentId))
      throw new SecurityException("Student access denied");
    return jdbc.queryForList("""
      select l.id,c.code as class_code,s.code as subject_code,s.display_name as subject_name,
             ch.code as chapter_code,ch.display_name as chapter_name,l.code,l.title,l.summary,l.estimated_minutes,
             l.sort_order,coalesce(p.status,'NOT_STARTED') as progress_status,coalesce(p.progress_percent,0) as progress_percent
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and c.active=true and s.active=true and s.program_id is null
        and c.code=(select class_code from student where id=?)
      order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,studentId,studentId,studentId);
  }

  private Map<String,Object> statsFor(long studentId){
    return learningMetrics.statsFor(studentId);
  }
}
