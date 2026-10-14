package com.quantaedge.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Retrieves a small, bounded set of adjacent published lesson blocks for tutor grounding.
 *
 * The current textbook library stores PDF bytes and page metadata, not searchable extracted
 * page text. This component therefore retrieves from the published, bilingual lesson corpus
 * that is actually searchable today; it never pretends those lesson blocks are PDF quotations.
 */
@Component
public class TutorCurriculumRetriever {
  private static final int MAX_CANDIDATES = 160;
  private static final int MAX_RESULTS = 4;
  private static final int MAX_CONTEXT_CHARS = 4200;
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

  private final JdbcTemplate jdbc;

  public TutorCurriculumRetriever(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String retrieve(long sessionId, String language, String learnerMessage, String currentTopic) {
    Set<String> queryTerms = terms(learnerMessage + " " + currentTopic);
    if (queryTerms.isEmpty()) return "";

    List<Map<String, Object>> candidates = jdbc.queryForList("""
      select l.id as lesson_id, l.title as source_lesson,
             b.id as block_id, b.block_type,
             coalesce(bt.content,b.content)::text as content
      from tutor_session ts
      join lesson current_lesson on current_lesson.id=ts.lesson_id
      join curriculum_chapter ch on ch.id=current_lesson.chapter_id
      join lesson l on l.chapter_id=ch.id and l.id<>current_lesson.id
      join lesson_block b on b.lesson_id=l.id and b.active=true
      left join lateral (
        select t.content from lesson_block_translation t
        where t.block_id=b.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(b.content::text)
        order by t.version_no desc,t.id desc limit 1
      ) bt on true
      where ts.id=? and ts.ended_at is null
        and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and b.block_type in ('PREREQUISITE','EXPLANATION','WORKED_EXAMPLE','SUMMARY','RECAP')
      order by l.id,b.sequence_no,b.id
      limit ?
      """, language, sessionId, MAX_CANDIDATES);

    List<ScoredBlock> scored = new ArrayList<>();
    for (Map<String, Object> row : candidates) {
      String content = String.valueOf(row.getOrDefault("content", ""));
      Set<String> contentTerms = terms(
          String.valueOf(row.getOrDefault("source_lesson", "")) + " " + content);
      int score = overlapScore(queryTerms, contentTerms);
      if (score > 0 && !content.isBlank()) {
        scored.add(new ScoredBlock(
            String.valueOf(row.getOrDefault("source_lesson", "Published lesson")),
            String.valueOf(row.getOrDefault("block_type", "EXPLANATION")),
            content,
            score));
      }
    }
    scored.sort(Comparator.comparingInt(ScoredBlock::score).reversed()
        .thenComparing(ScoredBlock::lesson, String.CASE_INSENSITIVE_ORDER));
    StringBuilder result = new StringBuilder();
    int added = 0;
    for (ScoredBlock block : scored) {
      if (added >= MAX_RESULTS || result.length() >= MAX_CONTEXT_CHARS) break;
      String excerpt = block.content().trim();
      int remaining = MAX_CONTEXT_CHARS - result.length();
      if (excerpt.length() > Math.min(1800, remaining)) {
        excerpt = excerpt.substring(0, Math.max(0, Math.min(1800, remaining)));
      }
      if (excerpt.isBlank()) continue;
      result.append("- Source: published QuantaEdge lesson \"")
          .append(block.lesson()).append("\"; section: ")
          .append(block.type()).append("\n  Content: ")
          .append(excerpt).append("\n");
      added++;
    }
    String textbookPages = retrieveTextbookPages(sessionId, language, learnerMessage, currentTopic);
    if (!textbookPages.isBlank()) {
      if (result.length() > 0) result.append("\\n");
      result.append("REVIEWED TEXTBOOK PAGE EXCERPTS (source/page metadata follows):\\n")
          .append(textbookPages);
    }
    return result.toString();
  }

  private String retrieveTextbookPages(long sessionId, String language, String learnerMessage, String currentTopic) {
    String query = (learnerMessage + " " + currentTopic).trim();
    if (query.isBlank()) return "";
    if (query.length() > 400) query = query.substring(0, 400);
    List<Map<String, Object>> pages = jdbc.queryForList("""
      select a.title as asset_title,
             coalesce(nullif(d.source_title,''),a.title) as source_title,
             coalesce(d.edition,'edition not recorded') as edition,
             p.page_no,coalesce(ch.display_name,'chapter not mapped') as chapter_title,
             left(p.extracted_text,2200) as excerpt,
             case when p.chapter_id=current_lesson.chapter_id then 1 else 0 end as current_chapter
      from tutor_session ts
      join lesson current_lesson on current_lesson.id=ts.lesson_id
      join curriculum_chapter current_chapter on current_chapter.id=current_lesson.chapter_id
      join curriculum_subject s on s.id=current_chapter.subject_id
      join textbook_extracted_page p on p.subject_id=current_chapter.subject_id
        and p.class_id=s.class_id and p.language=?
      join learning_pdf_asset a on a.id=p.pdf_asset_id and a.review_status='APPROVED'
      left join learning_document d on d.id=p.learning_document_id
      left join curriculum_chapter ch on ch.id=p.chapter_id
      where ts.id=? and ts.ended_at is null
        and p.quality_status='REVIEW'
        and p.extraction_method in ('PDF_TEXT','OCR')
        and length(trim(p.extracted_text))>=35
        and (
          (d.id is not null and d.status='PUBLISHED')
          or exists (
            select 1 from learning_book_intake i
            where i.pdf_asset_id=a.id and i.status='REVIEWED'
          )
        )
        and to_tsvector('simple',coalesce(p.extracted_text,'')) @@ plainto_tsquery('simple',?)
      order by case when p.chapter_id=current_lesson.chapter_id then 0 else 1 end,
        ts_rank_cd(to_tsvector('simple',coalesce(p.extracted_text,'')),plainto_tsquery('simple',?)) desc,
        p.page_no
      limit 3
      """, language, sessionId, query, query);
    StringBuilder context = new StringBuilder();
    for (Map<String, Object> row : pages) {
      if (context.length() >= 5200) break;
      String excerpt = String.valueOf(row.getOrDefault("excerpt", "")).trim();
      if (excerpt.isBlank()) continue;
      context.append("- Source: ").append(row.get("source_title"))
          .append("; edition: ").append(row.get("edition"))
          .append("; chapter: ").append(row.get("chapter_title"))
          .append("; PDF page: ").append(row.get("page_no")).append("\\n  Content: ")
          .append(excerpt).append("\\n");
    }
    return context.toString();
  }

  static Set<String> terms(String value) {
    Set<String> result = new LinkedHashSet<>();
    if (value == null || value.isBlank()) return result;
    for (String token : TOKEN_SPLIT.split(value.toLowerCase(Locale.ROOT))) {
      String cleaned = token.trim();
      if (cleaned.codePointCount(0, cleaned.length()) >= 3) result.add(cleaned);
      if (result.size() >= 80) break;
    }
    return result;
  }

  static int overlapScore(Set<String> queryTerms, Set<String> contentTerms) {
    int score = 0;
    for (String term : queryTerms) if (contentTerms.contains(term)) score++;
    return score;
  }

  private record ScoredBlock(String lesson, String type, String content, int score) {}
}
