package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
             o.code as objective_code, o.title as objective_title
      from lesson l
      join curriculum_chapter ch on ch.id = l.chapter_id
      join curriculum_subject s on s.id = ch.subject_id
      join curriculum_class c on c.id = s.class_id
      left join learning_objective o on o.id = l.objective_id
      where c.code = ? and s.code = ? and c.active = true and s.active = true
        and ch.active = true and ch.content_status = 'PUBLISHED'
        and l.active = true and l.status = 'PUBLISHED'
      order by ch.sort_order, l.sort_order
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
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and c.active=true and s.active=true
      """, lessonId);

    if (lessons.isEmpty()) {
      throw new LessonNotFoundException(lessonId);
    }

    Map<String, Object> result = new LinkedHashMap<>(lessons.getFirst());
    // Never send answer-bearing fields from lesson blocks to the browser. The
    // authoritative answer lives in question.answer_payload and is only used by
    // the answer endpoint.
    result.put("blocks", jdbc.queryForList("""
      select id, sequence_no, block_type,
             case when block_type in ('QUESTION','MCQ','TRUE_FALSE','MATCH','ORDER','INPUT')
                  then (content - 'correctOption' - 'answer' - 'answer_payload')
                  else content end as content
      from lesson_block
      where lesson_id=? and active=true
      order by sequence_no
      """, lessonId));
    result.put("questions", publicQuestions(lessonId));
    return result;
  }

  @GetMapping("/lessons/{lessonId}/questions")
  public List<Map<String, Object>> questions(@PathVariable long lessonId) {
    return publicQuestions(lessonId);
  }

  @PostMapping("/questions/{questionId}/answer")
  public Map<String, Object> answer(
      @PathVariable long questionId,
      @RequestBody Map<String, Object> body) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select q.id, q.question_type, q.explanation, q.answer_payload::text as answer_payload
      from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      where q.id=? and q.active=true and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
      """, questionId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("Question not found");
    }

    var q = rows.getFirst();
    String submitted = String.valueOf(body.getOrDefault("answer", "")).trim();
    String expectedJson = String.valueOf(q.get("answer_payload"));
    String expected = extractJsonString(expectedJson, "value");
    String kind = extractJsonString(expectedJson, "kind");

    boolean autoGradable = !"".equals(expected)
        && ("OPTION".equals(kind) || "TEXT".equals(kind));
    boolean correct = autoGradable && normalize(submitted).equals(normalize(expected));

    var result = new LinkedHashMap<String, Object>();
    result.put("questionId", questionId);
    result.put("questionType", q.get("question_type"));
    result.put("correct", autoGradable ? correct : null);
    result.put("autoGraded", autoGradable);
    result.put("explanation", q.get("explanation"));
    result.put("feedback", autoGradable
        ? (correct ? "सही। अब यह बताइए कि आपने यह उत्तर क्यों चुना।"
                   : "अभी सही नहीं। समाधान दोबारा देखें और फिर प्रयास करें।")
        : "उत्तर दर्ज हो गया। इस प्रश्न के लिए teacher/rubric आधारित जाँच आवश्यक है।");
    return result;
  }

  private List<Map<String, Object>> publicQuestions(long lessonId) {
    return jdbc.queryForList("""
      select q.id, q.question_type, q.prompt, q.explanation,
             q.difficulty, q.sort_order,
             q.source_kind, q.source_title, q.source_ref, q.source_year,
             q.board, q.marks, q.exam_format, q.topic, q.subtopic, q.skill, q.tags::text as tags,
             case
               when q.question_type in ('MCQ','TRUE_FALSE') then
                 coalesce((select jsonb_agg(
                    jsonb_build_object('key', qo.option_key, 'label', qo.label)
                    order by qo.sort_order
                 ) from question_option qo where qo.question_id=q.id), '[]'::jsonb)
               else '[]'::jsonb
             end::text as options,
             case
               when q.question_type in ('INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER') then 'text'
               when q.question_type in ('MATCH','ORDER') then 'structured-text'
               else 'choice'
             end as response_mode
      from question q
      where q.lesson_id=? and q.active=true
      order by q.sort_order
      """, lessonId);
  }

  private String normalize(String value) {
    return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private String extractJsonString(String json, String key) {
    String marker = "\"" + key + "\":\"";
    int start = json.indexOf(marker);
    if (start < 0) return "";
    start += marker.length();
    int end = json.indexOf("\"", start);
    return end < 0 ? "" : json.substring(start, end);
  }
}
