package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {
  private final JdbcTemplate jdbc;

  public LearningController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/lessons")
  public List<Map<String, Object>> lessons(
      @RequestParam String classCode,
      @RequestParam String subjectCode) {
    return jdbc.queryForList("""
      select l.id, l.code, l.title, l.summary, l.estimated_minutes,
             l.status, ch.code as chapter_code, ch.display_name as chapter_name,
             c.code as class_code, s.code as subject_code, s.display_name as subject_name,
             o.code as objective_code, o.title as objective_title
      from lesson l
      join curriculum_chapter ch on ch.id = l.chapter_id
      join curriculum_subject s on s.id = ch.subject_id
      join curriculum_class c on c.id = s.class_id
      left join learning_objective o on o.id = l.objective_id
      where c.code = ? and s.code = ? and c.active = true and s.active = true and ch.active = true and ch.content_status = 'PUBLISHED' and l.active = true and l.status = 'PUBLISHED'
      order by coalesce(ch.teaching_order, ch.sort_order), l.sort_order
      """, classCode, subjectCode);
  }

  @GetMapping("/lessons/{lessonId}")
  public Map<String, Object> lesson(@PathVariable long lessonId) {
    List<Map<String, Object>> lessons = jdbc.queryForList("""
      select l.id, l.code, l.title, l.summary, l.estimated_minutes,
             l.status, ch.code as chapter_code, ch.display_name as chapter_name,
             c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name,
             o.code as objective_code, o.title as objective_title
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      left join learning_objective o on o.id=l.objective_id
      where l.id=? and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED' and c.active=true and s.active=true
      """, lessonId);

    if (lessons.isEmpty()) {
      throw new LessonNotFoundException(lessonId);
    }

    Map<String, Object> result = new LinkedHashMap<>(lessons.getFirst());
    result.put("blocks", jdbc.queryForList("""
      select id, sequence_no, block_type, content::text as content
      from lesson_block
      where lesson_id=? and active=true
      order by sequence_no
      """, lessonId));
    result.put("questions", jdbc.queryForList("""
      select q.id, q.question_type, q.prompt, q.explanation,
             q.difficulty, q.sort_order,
             coalesce(
               (select jsonb_agg(
                  jsonb_build_object(
                    'key', qo.option_key,
                    'label', qo.label,
                    'correct', qo.is_correct
                  ) order by qo.sort_order
               ) from question_option qo where qo.question_id=q.id),
               '[]'::jsonb
             )::text as options
      from question q
      where q.lesson_id=? and q.active=true and exists (select 1 from lesson l join curriculum_chapter ch on ch.id=l.chapter_id where l.id=q.lesson_id and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED')
      order by q.sort_order
      """, lessonId));
    return result;
  }

  @GetMapping("/lessons/{lessonId}/questions")
  public List<Map<String, Object>> questions(@PathVariable long lessonId) {
    return jdbc.queryForList("""
      select q.id, q.question_type, q.prompt, q.explanation,
             q.difficulty, q.sort_order,
             coalesce(
               (select jsonb_agg(
                  jsonb_build_object(
                    'key', qo.option_key,
                    'label', qo.label,
                    'correct', qo.is_correct
                  ) order by qo.sort_order
               ) from question_option qo where qo.question_id=q.id),
               '[]'::jsonb
             )::text as options
      from question q
      where q.lesson_id=? and q.active=true and exists (select 1 from lesson l join curriculum_chapter ch on ch.id=l.chapter_id where l.id=q.lesson_id and l.active=true and l.status='PUBLISHED' and ch.active=true and ch.content_status='PUBLISHED')
      order by q.sort_order
      """, lessonId);
  }
}
