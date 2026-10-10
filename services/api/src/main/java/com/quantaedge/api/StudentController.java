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
  public StudentController(JdbcTemplate jdbc,AuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

  @GetMapping("/me")
  public Map<String,Object> me(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireStudent(context); return statsFor(context.studentId());
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

  private Map<String,Object> profileFor(long studentId) {
    return jdbc.queryForMap("""
      select id,public_id,display_name,class_code,board,language,
             profile_image_data_url,city,state,school_name,school_medium,
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
        and c.active=true and s.active=true
        and c.code=(select class_code from student where id=?)
      order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      """,studentId,studentId,studentId);
  }

  private Map<String,Object> statsFor(long studentId){
    Map<String,Object> student=jdbc.queryForMap("""
      select id,public_id,display_name,class_code,board,language,case when profile_image_data_url is not null then '/api/v1/students/me/profile-photo' else null end as profile_image_url,city,state,school_name,school_medium,favorite_subject,learning_goal from student where id=? and active=true
      """,studentId);
    Map<String,Object> result=new LinkedHashMap<>(student);
    result.put("lessonStats",jdbc.queryForMap("""
      select count(*) filter(where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED') as total_lessons,
             count(*) filter(where p.status='COMPLETED' and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED') as completed_lessons,
             coalesce(round(100.0*count(*) filter(where p.status='COMPLETED' and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED')/
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
             count(distinct l.id) as lessons,
             count(distinct ch.id) filter(where l.id is not null) as content_ready_chapters,
             count(distinct p.id) filter(where p.status='COMPLETED') as completed
      from curriculum_class c join curriculum_subject s on s.class_id=c.id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.active=true and s.active=true and c.code=(select class_code from student where id=?)
      group by c.code,s.code,s.display_name,s.sort_order order by s.sort_order
      """,studentId,studentId,studentId));
    result.put("mastery",jdbc.queryForMap("""
      select count(*) as concepts,coalesce(round(avg(mastery_percent),1),0) as average_mastery,
             count(*) filter(where mastery_percent>=80) as mastered,
             count(*) filter(where mastery_percent<50) as needs_support
      from student_concept_mastery where student_id=?
      """,studentId));
    result.put("masteryDetails",jdbc.queryForList("""
      select cc.id as concept_id,cc.title as concept_title,
             ch.display_name as chapter_name,s.display_name as subject_name,
             m.mastery_percent,m.attempts,m.last_attempt_at
      from student_concept_mastery m
      join chapter_concept cc on cc.id=m.concept_id
      join curriculum_chapter ch on ch.id=cc.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where m.student_id=?
      order by m.mastery_percent asc,ch.teaching_order,cc.concept_order
      limit 30
      """,studentId));
    return result;
  }
}
