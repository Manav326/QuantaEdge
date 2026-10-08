package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianController {
  private final JdbcTemplate jdbc; private final AuthorizationService authorization; private final AuthService auth;
  public GuardianController(JdbcTemplate jdbc,AuthorizationService authorization,AuthService auth){this.jdbc=jdbc;this.authorization=authorization;this.auth=auth;}

  @GetMapping("/children")
  public List<Map<String,Object>> children(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    return jdbc.queryForList("""
      select st.id,st.public_id,st.display_name,st.class_code,cc.display_name as class_name,st.board,st.language,
             gs.relationship,gs.consent_status
      from guardian_student gs join student st on st.id=gs.student_id
      join curriculum_class cc on cc.code=st.class_code
      where gs.guardian_user_id=? and gs.active=true order by st.created_at
      """,context.userId());
  }

  @DeleteMapping("/children/{studentId}")
  public Map<String,Object> archiveChild(@PathVariable long studentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireParent(context);
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from guardian_student where guardian_user_id=? and student_id=? and active=true and consent_status='CONSENTED')
      """,Boolean.class,context.userId(),studentId);
    if(!allowed) throw new SecurityException("Child access denied");
    jdbc.update("update guardian_student set active=false,consent_status='REVOKED' where guardian_user_id=? and student_id=?",context.userId(),studentId);
    jdbc.update("update student set active=false where id=?",studentId);
    return Map.of("deleted",true,"studentId",studentId);
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
    return result;
  }

  @PostMapping("/children")
  public Map<String,Object> createChild(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestBody Map<String,Object> body){
    context=authorization.requireParent(context);
    AuthContext child=auth.createChild(context.userId(),String.valueOf(body.getOrDefault("displayName","")),
        String.valueOf(body.getOrDefault("classCode","7")),String.valueOf(body.getOrDefault("language","hi")),
        String.valueOf(body.getOrDefault("pin","")));
    return jdbc.queryForMap("select public_id,display_name,class_code,board,language from student where id=?",child.studentId());
  }
}
