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
    Long diagnostics=jdbc.queryForObject("""
      select count(*) from student_diagnostic_attempt where student_id=? and status='SUBMITTED'
      """,Long.class,context.studentId());
    if(diagnostics==null||diagnostics==0) return Map.of("available",true,"kind","DIAGNOSTIC","reason","INITIAL_DIAGNOSTIC");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select l.id,l.code,l.title,l.summary,l.estimated_minutes,
             ch.code as chapter_code,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,
             coalesce(round(avg(coalesce(m.mastery_percent,0))),0) as mastery,
             test_result.percent as latest_assessment_percent
      from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      left join question q on q.lesson_id=l.id and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      left join student_concept_mastery m on m.student_id=? and m.concept_id=q.concept_id
      left join student_lesson_progress p on p.student_id=? and p.lesson_id=l.id
      join student_track_enrollment ste on ste.student_id=? and ste.subject_id=s.id and ste.status='ACTIVE'
      left join lateral (
        select round(100.0*att.final_score/nullif(att.max_score,0))::int as percent
        from student_assessment_attempt att
        join assessment a on a.id=att.assessment_id
        where att.student_id=? and att.status='RELEASED' and att.final_score is not null and att.max_score>0
          and (a.lesson_id=l.id or (a.lesson_id is null and a.chapter_id=ch.id))
        order by att.released_at desc,att.id desc limit 1
      ) test_result on true
      where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED'
        and c.active=true and s.active=true
        and s.program_id is null
        and c.code=(select class_code from student where id=?)
      group by l.id,ch.id,s.id,p.status,test_result.percent
      having coalesce(p.status,'NOT_STARTED') <> 'COMPLETED'
          or (test_result.percent is not null and test_result.percent < 80)
          or (count(m.id)>0 and avg(m.mastery_percent)<80)
      order by case when coalesce(p.status,'NOT_STARTED')='COMPLETED' then 1 else 0 end,
               coalesce(round(avg(coalesce(m.mastery_percent,0))*0.7 + coalesce(test_result.percent,avg(coalesce(m.mastery_percent,0)))*0.3),0),
               s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      limit 1
      """,context.studentId(),context.studentId(),context.studentId(),context.studentId(),context.studentId());
    if(rows.isEmpty()) return Map.of("available",false);
    return Map.of("available",true,"reason","PROGRESS_MASTERY_AND_RELEASED_ASSESSMENTS","lesson",rows.getFirst());
  }
}
