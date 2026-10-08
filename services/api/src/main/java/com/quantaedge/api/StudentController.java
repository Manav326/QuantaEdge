package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  @GetMapping("/preview")
  public Map<String,Object> preview(){
    List<Map<String,Object>> students=jdbc.queryForList("""
      select id from student where environment='LOCAL_PREVIEW' and active=true order by id limit 1
      """);
    if(students.isEmpty()) throw new IllegalStateException("Local preview student is not seeded");
    Map<String,Object> result=statsFor(((Number)students.getFirst().get("id")).longValue()); result.put("preview",true); return result;
  }

  @GetMapping("/{studentId}/lessons")
  public List<Map<String,Object>> lessons(@PathVariable long studentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireAuth(context);
    if(context.studentId()!=null && !context.studentId().equals(studentId)) throw new SecurityException("Student access denied");
    if(context.studentId()==null && (context.userId()==null || !hasGuardian(context.userId(),studentId)))
      throw new SecurityException("Child access denied");
    return jdbc.queryForList("""
      select l.id,c.code as class_code,s.code as subject_code,s.display_name as subject_name,
             ch.code as chapter_code,ch.display_name as chapter_name,l.code,l.title,l.summary,l.estimated_minutes,
             l.sort_order,coalesce(p.status,'NOT_STARTED') as progress_status,coalesce(p.progress_percent,0) as progress_percent
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id join curriculum_class c on c.id=s.class_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and c.code=(select class_code from student where id=?)
      order by c.sort_order,s.sort_order,ch.sort_order,l.sort_order
      """,studentId,studentId);
  }

  private boolean hasGuardian(long userId,long studentId){
    return jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,userId,studentId);
  }

  private Map<String,Object> statsFor(long studentId){
    Map<String,Object> student=jdbc.queryForMap("""
      select id,public_id,display_name,class_code,board,language from student where id=? and active=true
      """,studentId);
    Map<String,Object> result=new LinkedHashMap<>(student);
    result.put("lessonStats",jdbc.queryForMap("""
      select count(*) filter(where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED') as total_lessons,
             count(*) filter(where p.status='COMPLETED') as completed_lessons,
             coalesce(round(100.0*count(*) filter(where p.status='COMPLETED')/
               nullif(count(*) filter(where l.active=true and l.status='PUBLISHED'),0),1),0) as completion_percent
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      """,studentId));
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
      join curriculum_chapter ch on ch.subject_id=s.id
      left join lesson l on l.chapter_id=ch.id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.code=(select class_code from student where id=?)
      group by c.code,s.code,s.display_name,s.sort_order order by s.sort_order
      """,studentId,studentId));
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
