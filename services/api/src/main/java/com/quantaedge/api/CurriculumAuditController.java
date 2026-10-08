package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/curriculum")
public class CurriculumAuditController {
  private final JdbcTemplate jdbc;

  private static final Map<String, Integer> EXPECTED = Map.of(
      "6/maths", 15, "6/science", 18,
      "7/maths", 16, "7/science", 19,
      "8/maths", 16, "8/science", 19);

  private static final List<String> REQUIRED_FORMATS = List.of(
      "MCQ", "TRUE_FALSE", "INPUT", "MATCH", "ORDER", "ASSERTION_REASON",
      "CASE_BASED", "SHORT_ANSWER", "LONG_ANSWER", "NUMERICAL", "SOURCE_BASED");

  public CurriculumAuditController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/audit")
  public Map<String, Object> audit() {
    var result = new LinkedHashMap<String, Object>();
    result.put("expected", EXPECTED);
    result.put("actual", jdbc.queryForList("""
      select c.code||'/'||s.code as curriculum, count(*) as chapters
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and ch.active=true and ch.content_status='PUBLISHED'
      group by c.code,s.code
      order by c.code,s.code
      """));
    result.put("ordered", jdbc.queryForList("""
      select c.code as class_code,s.code as subject_code,
             ch.textbook_chapter_no,ch.teaching_order,ch.code,ch.display_name
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and ch.active=true and ch.content_status='PUBLISHED'
      order by c.sort_order,s.sort_order,ch.teaching_order
      """));
    result.put("strict", strictAudit());
    return result;
  }

  @GetMapping("/audit/strict")
  public Map<String, Object> strictAudit() {
    var chapters = jdbc.queryForList("""
      select ch.id, c.code as class_code, s.code as subject_code,
             ch.code, ch.display_name, ch.teaching_order, ch.textbook_chapter_no
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and ch.active=true and ch.content_status='PUBLISHED'
      order by c.sort_order,s.sort_order,ch.teaching_order,ch.sort_order
      """);

    var failures = new ArrayList<Map<String, Object>>();

    // A missing curriculum pair is a hard failure; do not let an incomplete seed
    // look green merely because every chapter that exists passes its local checks.
    var actualCounts = new LinkedHashMap<String, Integer>();
    for (var row : jdbc.queryForList("""
      select c.code||'/'||s.code as curriculum, count(*) as chapters
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code in ('6','7','8') and s.code in ('maths','science')
        and ch.active=true and ch.content_status='PUBLISHED'
      group by c.code,s.code
      """)) {
      actualCounts.put(String.valueOf(row.get("curriculum")),
          ((Number) row.get("chapters")).intValue());
    }
    for (var expected : EXPECTED.entrySet()) {
      int actual = actualCounts.getOrDefault(expected.getKey(), 0);
      if (actual != expected.getValue()) {
        failures.add(new LinkedHashMap<>(Map.of(
            "curriculum", expected.getKey(),
            "missing", List.of("CHAPTER_COUNT (" + actual + "/" + expected.getValue() + ")"))));
      }
    }

    for (var chapter : chapters) {
      long id = ((Number) chapter.get("id")).longValue();
      var missing = new ArrayList<String>();

      long lessons = jdbc.queryForObject(
          "select count(*) from lesson where chapter_id=? and active=true and status='PUBLISHED'",
          Long.class, id);
      if (lessons < 3) missing.add("TEACHING_STAGES (" + lessons + "/3)");

      long concepts = jdbc.queryForObject(
          "select count(*) from chapter_concept where chapter_id=? and status in ('READY_FOR_REVIEW','PUBLISHED')",
          Long.class, id);
      if (concepts < 3) missing.add("CONCEPTS (" + concepts + "/3)");

      var requirements = jdbc.queryForList("""
        select requirement_code,min_count from lesson_requirement
        where chapter_id=? and required=true order by id
        """, id);

      for (var req : requirements) {
        String code = String.valueOf(req.get("requirement_code"));
        int min = ((Number) req.get("min_count")).intValue();
        long count = switch (code) {
          case "PREREQUISITE" -> countBlocks(id, "PREREQUISITE");
          case "EXPLANATION" -> countBlocks(id, "EXPLANATION");
          case "WORKED_EXAMPLE" -> countBlocks(id, "WORKED_EXAMPLE");
          case "GUIDED_PRACTICE" -> countBlocks(id, "GUIDED_PRACTICE");
          case "INDEPENDENT_PRACTICE" -> countBlocks(id, "INDEPENDENT_PRACTICE");
          case "RECAP" -> countBlocks(id, "RECAP") + countBlocks(id, "SUMMARY");
          case "VISUAL_OR_NOT_REQUIRED" -> countVisualDecision(id);
          case "NCERT_OR_TEXTBOOK_MAPPING" -> jdbc.queryForObject(
              "select count(*) from chapter_source where chapter_id=? and coverage_status in ('MAPPED','COVERED')",
              Long.class, id);
          case "BOARD_FORMAT_COVERAGE" -> jdbc.queryForObject(
              "select count(distinct coalesce(exam_format,question_type)) from question q join lesson l on l.id=q.lesson_id where l.chapter_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')",
              Long.class, id);
          case "SOURCE_TAGGED_QUESTIONS" -> jdbc.queryForObject(
              "select count(*) from question q join lesson l on l.id=q.lesson_id where l.chapter_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED') and q.source_kind <> 'AUTHOR_CREATED' and (q.source_ref is not null or q.tags <> '[]'::jsonb)",
              Long.class, id);
          default -> 0L;
        };
        if (count < min) missing.add(code + " (" + count + "/" + min + ")");
      }

      for (String format : REQUIRED_FORMATS) {
        long count = jdbc.queryForObject(
            "select count(*) from question q join lesson l on l.id=q.lesson_id " +
            "where l.chapter_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED') and q.question_type=?",
            Long.class, id, format);
        if (count < 1) missing.add("FORMAT_" + format + " (0/1)");
      }

      if (!missing.isEmpty()) {
        var failure = new LinkedHashMap<String, Object>();
        failure.put("chapter", chapter);
        failure.put("missing", missing);
        failures.add(failure);
      }
    }

    var result = new LinkedHashMap<String, Object>();
    result.put("status", failures.isEmpty() ? "GREEN" : "RED");
    result.put("chaptersChecked", chapters.size());
    result.put("expectedChapters", EXPECTED.values().stream().mapToInt(Integer::intValue).sum());
    result.put("failedChapters", failures.size());
    result.put("requiredQuestionFormats", REQUIRED_FORMATS);
    result.put("failures", failures);
    return result;
  }

  private long countBlocks(long chapterId, String type) {
    return jdbc.queryForObject("""
      select count(*) from lesson_block b
      join lesson l on l.id=b.lesson_id
      where l.chapter_id=? and l.active=true and l.status='PUBLISHED'
        and b.active=true and b.block_type=?
      """, Long.class, chapterId, type);
  }

  private long countVisualDecision(long chapterId) {
    return jdbc.queryForObject("""
      select count(*)
      from lesson_block b
      join lesson l on l.id=b.lesson_id
      where l.chapter_id=? and l.active=true and l.status='PUBLISHED'
        and b.active=true
        and (
          b.block_type in ('IMAGE','DIAGRAM','VIDEO')
          or (b.block_type='EXPLANATION' and b.content ? 'visualDecision')
        )
      """, Long.class, chapterId);
  }
}
