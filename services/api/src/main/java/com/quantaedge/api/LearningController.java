package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
      select id, sequence_no, block_type, strip_answer_keys(content)::text as content
      from lesson_block
      where lesson_id=? and active=true
      order by sequence_no
      """, lessonId));
    result.put("questions", jdbc.queryForList(publicQuestionsSql(), lessonId));
    return result;
  }

  @GetMapping("/lessons/{lessonId}/questions")
  public List<Map<String, Object>> questions(@PathVariable long lessonId) {
    return jdbc.queryForList(publicQuestionsSql(), lessonId);
  }

  private String publicQuestionsSql() {
    return """
      select q.id, q.question_type, q.prompt,
             q.difficulty, q.sort_order,
             coalesce(
               (select jsonb_agg(
                  jsonb_build_object(
                    'key', qo.option_key,
                    'label', qo.label
                  ) order by qo.sort_order
               ) from question_option qo where qo.question_id=q.id),
               '[]'::jsonb
             )::text as options
      from question q
      where q.lesson_id=? and q.active=true and q.review_status='APPROVED'
        and q.question_type in ('MCQ','TRUE_FALSE') and exists (
        select 1 from lesson l join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_class c on c.id=s.class_id
        where l.id=q.lesson_id and l.active=true and l.status='PUBLISHED'
          and ch.active=true and ch.content_status='PUBLISHED'
          and s.active=true and c.active=true
      )
      order by q.sort_order
      """;
  }

  /**
   * Local-preview grading only. The client never receives the answer key.
   * Production submissions must be connected to an authenticated student identity
   * before this endpoint is enabled for production accounts.
   */
  @PostMapping("/questions/{questionId}/answer")
  public Map<String, Object> answer(
      @PathVariable long questionId,
      @RequestBody Map<String, Object> body) {
    String selectedOption = String.valueOf(body.getOrDefault("selectedOption", "")).trim();
    if (selectedOption.isEmpty() || selectedOption.length() > 20) {
      throw new IllegalArgumentException("A valid selectedOption is required");
    }

    List<Map<String, Object>> previewStudents = jdbc.queryForList("""
      select id from student
      where environment='LOCAL_PREVIEW' and active=true
      order by id limit 1
      """);
    if (previewStudents.isEmpty()) {
      throw new SecurityException("Authenticated student identity is required to submit answers");
    }
    long studentId = ((Number) previewStudents.getFirst().get("id")).longValue();

    List<Map<String, Object>> rows = jdbc.queryForList("""
      select q.id, q.explanation,
             exists(select 1 from question_option qo
                    where qo.question_id=q.id and qo.option_key=? and qo.is_correct) as correct,
             exists(select 1 from question_option qo
                    where qo.question_id=q.id and qo.option_key=?) as option_exists
      from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where q.id=? and q.active=true and q.review_status='APPROVED' and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and s.active=true and c.active=true
      """, selectedOption, selectedOption, questionId);
    if (rows.isEmpty()) {
      throw new LessonNotFoundException(questionId);
    }

    Map<String, Object> graded = rows.getFirst();
    if (!Boolean.TRUE.equals(graded.get("option_exists"))) {
      throw new IllegalArgumentException("Selected option does not belong to this question");
    }
    boolean correct = Boolean.TRUE.equals(graded.get("correct"));
    jdbc.update("""
      insert into student_question_attempt(student_id,question_id,selected_option,correct)
      values (?,?,?,?)
      """, studentId, questionId, selectedOption, correct);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("questionId", questionId);
    result.put("correct", correct);
    result.put("explanation", graded.get("explanation"));
    result.put("saved", true);
    return result;
  }
}
