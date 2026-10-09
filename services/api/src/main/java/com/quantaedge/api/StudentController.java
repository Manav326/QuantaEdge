
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
    if (students.isEmpty()) throw new IllegalStateException("Local preview student is not seeded");

    Map<String, Object> result = new LinkedHashMap<>(students.getFirst());
    Long studentId = ((Number) result.get("id")).longValue();
    String classCode = String.valueOf(result.get("class_code"));

    result.put("lessonStats", jdbc.queryForMap("""
      select count(l.id) as total_lessons,
             count(p.id) filter (where p.status='COMPLETED') as completed_lessons,
             coalesce(round(100.0 * count(p.id) filter (where p.status='COMPLETED') /
               nullif(count(l.id),0),1),0) as completion_percent
      from curriculum_class c
      left join curriculum_subject s on s.class_id=c.id and s.active=true
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.code=?
      """, studentId, classCode));

    result.put("questionStats", jdbc.queryForMap("""
      select count(a.id) as attempts,
             count(a.id) filter (where a.correct=true) as correct,
             coalesce(round(100.0 * count(a.id) filter (where a.correct=true) /
               nullif(count(a.id),0),1),0) as accuracy_percent
      from student_question_attempt a
      join question q on q.id=a.question_id
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where a.student_id=? and c.code=?
      """, studentId, classCode));

    result.put("curriculum", jdbc.queryForList("""
      select c.code as class_code, s.code as subject_code, s.display_name as subject_name,
             count(distinct ch.id) as chapters,
             count(distinct l.id) as lessons,
             count(distinct p.lesson_id) filter (where p.status='COMPLETED') as completed
      from curriculum_class c
      join curriculum_subject s on s.class_id=c.id and s.active=true
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      left join student_lesson_progress p on p.lesson_id=l.id and p.student_id=?
      where c.code=? and c.active=true
      group by c.code,s.code,s.display_name,s.sort_order
      order by s.sort_order
      """, studentId, classCode));

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
        and ch.active=true and ch.content_status='PUBLISHED'
        and s.active=true and c.active=true
      order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order
      """, studentId);
  }
}
