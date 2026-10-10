package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/diagnostic")
public class DiagnosticController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public DiagnosticController(JdbcTemplate jdbc, AuthorizationService authorization) {
    this.jdbc=jdbc;
    this.authorization=authorization;
  }

  @GetMapping
  public Map<String,Object> get(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    Long completed=jdbc.queryForObject(
        "select count(*) from learning_session where student_id=? and source='DIAGNOSTIC' and ended_at is not null",
        Long.class,context.studentId());
    if(completed!=null && completed>0) return Map.of("completed",true,"questions",List.of());

    List<Map<String,Object>> questions=jdbc.queryForList("""
      select q.id,q.lesson_id,q.question_type,q.prompt,q.explanation,q.marks,q.exam_format,
             q.source_kind,q.source_year,q.board,
             coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
               order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb)::text as options
      from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where q.active=true and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and c.code=(select class_code from student where id=?)
        and q.question_type in ('MCQ','TRUE_FALSE')
      order by s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id,q.sort_order,q.id
      limit 10
      """,context.studentId());

    return Map.of("completed",false,"questions",questions);
  }
}
