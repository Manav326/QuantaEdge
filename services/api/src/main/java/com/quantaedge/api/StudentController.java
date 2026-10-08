package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {
  private final JdbcTemplate jdbc;

  public StudentController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/preview")
  public Map<String, Object> preview() {
    List<Map<String, Object>> students = jdbc.queryForList("""
      select id, public_id, display_name, class_code, board, language
      from student
      where environment='LOCAL_PREVIEW' and active=true
      order by id
      limit 1
      """);
    if (students.isEmpty()) {
      throw new IllegalStateException("Local preview student is not seeded");
    }

    Map<String, Object> result = new LinkedHashMap<>(students.getFirst());
    Long studentId = ((Number) result.get("id")).longValue();

    result.put("lessonStats", jdbc.queryForMap("""
      select
        count(*) filter (where l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED') as total_lessons,
        count(*) filter (where p.status='COMPLETED') as completed_lessons,
        coalesce(round(
          100.0 * count(*) filter (where p.status='COMPLETED') /
          nullif(count(*) filter (where l.active=true and l.status='PUBLISHED'),0), 1
        ),0) as completion_percent
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true and ch.content_status='PUBLISHED'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      """, studentId));

    result.put("questionStats", jdbc.queryForMap("""
      select count(*) as attempts,
             count(*) filter (where correct=true) as correct,
             coalesce(round(100.0 * count(*) filter (where correct=true) / nullif(count(*),0),1),0) as accuracy_percent
      from student_question_attempt
      where student_id=?
      """, studentId));

    result.put("curriculum", jdbc.queryForList("""
      select c.code as class_code, s.code as subject_code, s.display_name as subject_name,
             count(distinct ch.id) as chapters,
             count(distinct l.id) filter (where l.active=true and l.status='PUBLISHED') as lessons,
             count(distinct p.id) filter (where p.status='COMPLETED') as completed
      from curriculum_class c
      join curriculum_subject s on s.class_id=c.id
      join curriculum_chapter ch on ch.subject_id=s.id
      left join lesson l on l.chapter_id=ch.id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.code='7'
      group by c.code,s.code,s.display_name,s.sort_order
      order by s.sort_order
      """, studentId));

    return result;
  }

  @GetMapping("/{studentId}/lessons")
  public List<Map<String, Object>> lessons(@PathVariable long studentId) {
    return jdbc.queryForList("""
      select l.id, c.code as class_code, s.code as subject_code,
             s.display_name as subject_name, ch.code as chapter_code,
             ch.display_name as chapter_name, l.code, l.title,
             l.summary, l.estimated_minutes, l.sort_order,
             coalesce(p.status,'NOT_STARTED') as progress_status,
             coalesce(p.progress_percent,0) as progress_percent
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where l.active=true and l.status='PUBLISHED'
      order by c.sort_order,s.sort_order,ch.sort_order,l.sort_order
      """, studentId);
  }
}
