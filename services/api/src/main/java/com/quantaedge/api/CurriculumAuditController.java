package com.quantaedge.api;

import java.util.ArrayList;
import java.util.HashMap;
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
      "7/maths", 16, "7/science", 20,
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

    var lessonCounts = countByChapter("""
      select chapter_id, count(*) as count
      from lesson
      where active=true and status='PUBLISHED'
      group by chapter_id
      """);

    var conceptCounts = countByChapter("""
      select chapter_id, count(*) as count
      from chapter_concept
      where status in ('READY_FOR_REVIEW','PUBLISHED')
      group by chapter_id
      """);

    var blockCounts = new HashMap<Long, Map<String, Long>>();
    for (var row : jdbc.queryForList("""
      select l.chapter_id, b.block_type, count(*) as count
      from lesson_block b
      join lesson l on l.id=b.lesson_id
      where l.active=true and l.status='PUBLISHED'
        and b.active=true
      group by l.chapter_id,b.block_type
      """)) {
      long chapterId = ((Number) row.get("chapter_id")).longValue();
      String blockType = String.valueOf(row.get("block_type"));
      blockCounts.computeIfAbsent(chapterId, ignored -> new HashMap<>())
          .put(blockType, ((Number) row.get("count")).longValue());
    }

    var visualCounts = countByChapter("""
      select l.chapter_id, count(*) as count
      from lesson_block b
      join lesson l on l.id=b.lesson_id
      where l.active=true and l.status='PUBLISHED'
        and b.active=true
        and (
          b.block_type in ('IMAGE','DIAGRAM','VIDEO')
          or (b.block_type='EXPLANATION' and jsonb_exists(b.content, 'visualDecision'))
        )
      group by l.chapter_id
      """);

    var requirementsByChapter = new HashMap<Long, List<Map<String, Object>>>();
    for (var req : jdbc.queryForList("""
      select chapter_id, requirement_code, min_count
      from lesson_requirement
      where required=true
      order by chapter_id,id
      """)) {
      long chapterId = ((Number) req.get("chapter_id")).longValue();
      requirementsByChapter.computeIfAbsent(chapterId, ignored -> new ArrayList<>()).add(req);
    }

    var sourceMappingCounts = countByChapter("""
      select chapter_id, count(*) as count
      from chapter_source
      where coverage_status in ('MAPPED','COVERED')
      group by chapter_id
      """);

    var boardFormatCounts = countDistinctByChapter("""
      select l.chapter_id, count(distinct coalesce(q.exam_format,q.question_type)) as count
      from question q
      join lesson l on l.id=q.lesson_id
      where q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      group by l.chapter_id
      """);

    var sourceTaggedCounts = countByChapter("""
      select l.chapter_id, count(*) as count
      from question q
      join lesson l on l.id=q.lesson_id
      where q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        and q.source_kind <> 'AUTHOR_CREATED'
        and (q.source_ref is not null or q.tags <> '[]'::jsonb)
      group by l.chapter_id
      """);

    var formatCounts = new HashMap<Long, Map<String, Long>>();
    for (var row : jdbc.queryForList("""
      select l.chapter_id, q.question_type, count(*) as count
      from question q
      join lesson l on l.id=q.lesson_id
      where q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      group by l.chapter_id,q.question_type
      """)) {
      long chapterId = ((Number) row.get("chapter_id")).longValue();
      String format = String.valueOf(row.get("question_type"));
      formatCounts.computeIfAbsent(chapterId, ignored -> new HashMap<>())
          .put(format, ((Number) row.get("count")).longValue());
    }

    for (var chapter : chapters) {
      long id = ((Number) chapter.get("id")).longValue();
      var missing = new ArrayList<String>();

      long lessons = lessonCounts.getOrDefault(id, 0L);
      if (lessons < 3) missing.add("TEACHING_STAGES (" + lessons + "/3)");

      long concepts = conceptCounts.getOrDefault(id, 0L);
      if (concepts < 3) missing.add("CONCEPTS (" + concepts + "/3)");

      for (var req : requirementsByChapter.getOrDefault(id, List.of())) {
        String code = String.valueOf(req.get("requirement_code"));
        int min = ((Number) req.get("min_count")).intValue();
        long count = requirementCount(
            code, id, blockCounts, visualCounts, sourceMappingCounts, boardFormatCounts, sourceTaggedCounts);
        if (count < min) missing.add(code + " (" + count + "/" + min + ")");
      }

      var chapterFormats = formatCounts.getOrDefault(id, Map.of());
      for (String format : REQUIRED_FORMATS) {
        long count = chapterFormats.getOrDefault(format, 0L);
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
    result.put("failedChapters", failures.size());
    result.put("requiredQuestionFormats", REQUIRED_FORMATS);
    result.put("failures", failures);
    return result;
  }

  private long requirementCount(
      String code,
      long chapterId,
      Map<Long, Map<String, Long>> blockCounts,
      Map<Long, Long> visualCounts,
      Map<Long, Long> sourceMappingCounts,
      Map<Long, Long> boardFormatCounts,
      Map<Long, Long> sourceTaggedCounts) {
    var blocks = blockCounts.getOrDefault(chapterId, Map.of());
    return switch (code) {
      case "PREREQUISITE", "EXPLANATION", "WORKED_EXAMPLE", "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE" ->
          blocks.getOrDefault(code, 0L);
      case "RECAP" -> blocks.getOrDefault("RECAP", 0L) + blocks.getOrDefault("SUMMARY", 0L);
      case "VISUAL_OR_NOT_REQUIRED" -> visualCounts.getOrDefault(chapterId, 0L);
      case "NCERT_OR_TEXTBOOK_MAPPING" -> sourceMappingCounts.getOrDefault(chapterId, 0L);
      case "BOARD_FORMAT_COVERAGE" -> boardFormatCounts.getOrDefault(chapterId, 0L);
      case "SOURCE_TAGGED_QUESTIONS" -> sourceTaggedCounts.getOrDefault(chapterId, 0L);
      default -> 0L;
    };
  }

  private Map<Long, Long> countByChapter(String sql) {
    var result = new HashMap<Long, Long>();
    for (var row : jdbc.queryForList(sql)) {
      result.put(((Number) row.get("chapter_id")).longValue(),
          ((Number) row.get("count")).longValue());
    }
    return result;
  }

  private Map<Long, Long> countDistinctByChapter(String sql) {
    return countByChapter(sql);
  }
}
