package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  public RecommendationController(JdbcTemplate jdbc,AuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

  @GetMapping("/next")
  public Map<String,Object> next(@RequestAttribute(value="authContext",required=false) AuthContext context){
    context=authorization.requireStudent(context);
    Long attempts=jdbc.queryForObject("select count(*) from student_question_attempt where student_id=? and correct is not null",Long.class,context.studentId());
    Long diagnostics=jdbc.queryForObject("select count(*) from learning_session where student_id=? and source='DIAGNOSTIC' and ended_at is not null",Long.class,context.studentId());
    if((attempts==null||attempts==0)&&(diagnostics==null||diagnostics==0)) return Map.of("available",true,"kind","DIAGNOSTIC","reason","INITIAL_DIAGNOSTIC");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select l.id,l.code,l.title,l.summary,l.estimated_minutes,
             ch.code as chapter_code,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,
             coalesce(round(avg(coalesce(m.mastery_percent,0))),0) as mastery
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      left join question q on q.lesson_id=l.id and q.active=true
      left join student_concept_mastery m on m.student_id=? and m.concept_id=q.concept_id
      left join student_lesson_progress p on p.student_id=? and p.lesson_id=l.id
      where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and c.code=(select class_code from student where id=?)
        and coalesce(p.status,'NOT_STARTED') <> 'COMPLETED'
      group by l.id,ch.id,s.id,p.status
      order by case when coalesce(p.status,'NOT_STARTED')='COMPLETED' then 1 else 0 end,
               coalesce(round(avg(coalesce(m.mastery_percent,0))),0),s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      limit 1
      """,context.studentId(),context.studentId(),context.studentId());
    if(rows.isEmpty()) return Map.of("available",false);
    return Map.of("available",true,"reason","PROGRESS_AND_MASTERY","lesson",rows.getFirst());
  }
}
