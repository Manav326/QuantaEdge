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
  private static final int MAX_RESULTS = 2;
  private static final int MAX_CONTEXT_CHARS = 1000;
  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

  private final JdbcTemplate jdbc;

  public TutorCurriculumRetriever(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String retrieve(long sessionId, String language, String learnerMessage, String currentTopic) {
    return retrieveInternal(sessionId,null,language,learnerMessage,currentTopic);
  }

  public String retrieve(long sessionId, long institutionId, String language,
      String learnerMessage, String currentTopic) {
    return retrieveInternal(sessionId,institutionId,language,learnerMessage,currentTopic);
  }

  private String retrieveInternal(long sessionId, Long institutionId, String language,
      String learnerMessage, String currentTopic) {
    Set<String> queryTerms = terms(learnerMessage + " " + currentTopic);
    if (queryTerms.isEmpty()) return "";

    List<Map<String, Object>> candidates = jdbc.queryForList("""
      select l.id as lesson_id,ch.display_name as source_chapter,l.title as source_lesson,
             b.id as block_id,b.block_type,
             coalesce(bt.content,b.content)::text as content
      from tutor_session ts
      join lesson current_lesson on current_lesson.id=ts.lesson_id
      join curriculum_chapter ch on ch.id=current_lesson.chapter_id
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join curriculum_program p on p.id=ts.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=p.id
        and cps.subject_id=s.id and cps.class_id=s.class_id
      join lesson l on l.chapter_id=ch.id and l.id<>current_lesson.id
      join lesson_block b on b.lesson_id=l.id and b.active=true
      left join lateral (
        select t.content from lesson_block_translation t
        where t.block_id=b.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(b.content::text)
        order by t.version_no desc,t.id desc limit 1
      ) bt on true
      where ts.id=? and ts.ended_at is null __TENANT_SCOPE__
        and ts.course_id is not null
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
        and l.active=true and l.status='PUBLISHED'
        and ch.active=true and ch.content_status='PUBLISHED'
        and b.block_type in ('PREREQUISITE','EXPLANATION','WORKED_EXAMPLE','SUMMARY','RECAP')
      order by l.id,b.sequence_no,b.id
      limit ?
      """.replace("__TENANT_SCOPE__", institutionId==null ? "" : "and ts.institution_id=?"),
        retrieverArgs(language,sessionId,institutionId,MAX_CANDIDATES));

    List<ScoredBlock> scored = new ArrayList<>();
    for (Map<String, Object> row : candidates) {
      String content = String.valueOf(row.getOrDefault("content", ""));
      Set<String> contentTerms = terms(
          String.valueOf(row.getOrDefault("source_chapter", "")) + " " +
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
      if (excerpt.length() > Math.min(450, remaining)) {
        excerpt = excerpt.substring(0, Math.max(0, Math.min(450, remaining)));
      }
      if (excerpt.isBlank()) continue;
      result.append("- Source: published QuantaEdge lesson \"")
          .append(block.lesson()).append("\"; section: ")
          .append(block.type()).append("\n  Content: ")
          .append(excerpt).append("\n");
      added++;
    }
    String textbookPages = retrieveTextbookPages(sessionId,institutionId,language,learnerMessage,currentTopic);
    if (!textbookPages.isBlank()) {
      if (result.length() > 0) result.append("\n");
      result.append("APPROVED-SOURCE TEXTBOOK PAGE EXCERPTS (source/page metadata follows):\n")
          .append(textbookPages);
    }
    return result.toString();
  }

  /** Retrieve related published material for a packaged structured-artifact tutor session. */
  public String retrieveStructured(long sessionId,long institutionId,long courseId,String artifactId,
      String language,String learnerMessage,String currentTopic,String subjectCode,int classNumber,String chapterTitle){
    String queryText=(learnerMessage+" "+currentTopic+" "+chapterTitle).trim();
    Set<String> queryTerms=terms(queryText);
    if(queryTerms.isEmpty())return "";
    List<Map<String,Object>> candidates=jdbc.queryForList("""
      select l.id as lesson_id,ch.display_name as source_chapter,l.title as source_lesson,
             b.id as block_id,b.block_type,coalesce(bt.content,b.content)::text as content
      from curriculum_program p
      join curriculum_program_subject cps on cps.program_id=p.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id
        and s.active=true and lower(s.code)=lower(?)
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
      join curriculum_class c on c.id=s.class_id and c.active=true
        and regexp_replace(c.code,'[^0-9]','','g')=?
      join curriculum_chapter ch on ch.subject_id=s.id
        and ch.active=true and ch.content_status='PUBLISHED'
      join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      join lesson_block b on b.lesson_id=l.id and b.active=true
      join tutor_session ts on ts.course_id=p.id and ts.id=? and ts.institution_id=?
        and ts.course_id=? and ts.structured_artifact_id=? and ts.lesson_id is null and ts.ended_at is null
      left join lateral (
        select t.content from lesson_block_translation t
        where t.block_id=b.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(b.content::text)
        order by t.version_no desc,t.id desc limit 1
      ) bt on true
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
        and b.block_type in ('PREREQUISITE','EXPLANATION','WORKED_EXAMPLE','SUMMARY','RECAP')
      order by case when lower(trim(ch.display_name))=lower(trim(?)) then 0 else 1 end,
        l.id,b.sequence_no,b.id
      limit ?
      """,subjectCode,String.valueOf(classNumber),sessionId,institutionId,courseId,artifactId,
        language,courseId,chapterTitle,MAX_CANDIDATES);
    List<ScoredBlock> scored=new ArrayList<>();
    for(Map<String,Object> row:candidates){
      String content=String.valueOf(row.getOrDefault("content",""));
      Set<String> contentTerms=terms(String.valueOf(row.getOrDefault("source_chapter",""))+" "+
          String.valueOf(row.getOrDefault("source_lesson",""))+" "+content);
      int score=overlapScore(queryTerms,contentTerms);
      if(score>0&&!content.isBlank())scored.add(new ScoredBlock(
          String.valueOf(row.getOrDefault("source_lesson","Published lesson")),
          String.valueOf(row.getOrDefault("block_type","EXPLANATION")),content,score));
    }
    scored.sort(Comparator.comparingInt(ScoredBlock::score).reversed()
        .thenComparing(ScoredBlock::lesson,String.CASE_INSENSITIVE_ORDER));
    StringBuilder result=new StringBuilder();
    int added=0;
    for(ScoredBlock block:scored){
      if(added>=MAX_RESULTS||result.length()>=MAX_CONTEXT_CHARS)break;
      String excerpt=block.content().trim();
      int remaining=MAX_CONTEXT_CHARS-result.length();
      if(excerpt.length()>Math.min(450,remaining))excerpt=excerpt.substring(0,Math.max(0,Math.min(450,remaining)));
      if(excerpt.isBlank())continue;
      result.append("- Source: published course-mapped lesson \"").append(block.lesson())
          .append("\"; section: ").append(block.type()).append("\n  Content: ").append(excerpt).append("\n");
      added++;
    }
    String pages=retrieveStructuredTextbookPages(sessionId,institutionId,courseId,artifactId,language,
        queryText,subjectCode,classNumber,chapterTitle);
    if(!pages.isBlank()){if(result.length()>0)result.append("\n");result.append("APPROVED-SOURCE TEXTBOOK PAGE EXCERPTS:\n").append(pages);}
    return result.toString();
  }

  private String retrieveStructuredTextbookPages(long sessionId,long institutionId,long courseId,
      String artifactId,String language,String queryText,String subjectCode,int classNumber,String chapterTitle){
    String query=queryText.length()>400?queryText.substring(0,400):queryText;
    List<Map<String,Object>> pages=jdbc.queryForList("""
      select coalesce(nullif(sb.source_title,''),nullif(d.source_title,''),a.title) as source_title,
             coalesce(nullif(sb.edition,''),d.edition,'edition not recorded') as edition,
             coalesce(sb.source_url,d.source_url,'source URL not recorded') as source_url,
             pg.page_no,coalesce(ch.display_name,'chapter not mapped') as chapter_title,
             left(pg.extracted_text,650) as excerpt
      from tutor_session ts
      join curriculum_program p on p.id=ts.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=p.id
      join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id
        and s.active=true and lower(s.code)=lower(?)
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
      join curriculum_class c on c.id=s.class_id and c.active=true
        and regexp_replace(c.code,'[^0-9]','','g')=?
      join textbook_extracted_page pg on pg.subject_id=s.id and pg.class_id=s.class_id and pg.language=?
      left join learning_pdf_asset a on a.id=pg.pdf_asset_id
      left join textbook_source_book sb on sb.id=pg.source_book_id
      left join learning_document d on d.id=pg.learning_document_id
      left join curriculum_chapter ch on ch.id=pg.chapter_id
      where ts.id=? and ts.institution_id=? and ts.course_id=? and ts.structured_artifact_id=?
        and ts.ended_at is null
        and pg.quality_status='READY' and pg.extraction_method in ('PDF_TEXT','OCR')
        and length(trim(pg.extracted_text))>=35
        and ((sb.id is not null and sb.status='APPROVED')
          or (a.id is not null and a.review_status='APPROVED' and (
            (d.id is not null and d.status='PUBLISHED')
            or exists(select 1 from learning_book_intake i
              where i.pdf_asset_id=a.id and i.status='REVIEWED'))))
        and to_tsvector('simple',coalesce(pg.extracted_text,'')) @@ plainto_tsquery('simple',?)
      order by ts_rank_cd(to_tsvector('simple',coalesce(pg.extracted_text,'')),plainto_tsquery('simple',?)) desc,
        case when lower(coalesce(ch.display_name,''))=lower(?) then 0 else 1 end,pg.page_no
      limit 2
      """,subjectCode,String.valueOf(classNumber),language,sessionId,institutionId,courseId,artifactId,query,query,chapterTitle);
    StringBuilder context=new StringBuilder();
    for(Map<String,Object> row:pages){
      if(context.length()>=1500)break;
      String excerpt=String.valueOf(row.getOrDefault("excerpt","")).trim();
      if(excerpt.isBlank())continue;
      context.append("- Source: ").append(row.get("source_title")).append("; edition: ")
          .append(row.get("edition")).append("; source URL: ").append(row.get("source_url"))
          .append("; chapter: ").append(row.get("chapter_title")).append("; PDF page: ")
          .append(row.get("page_no")).append("\n  Content: ").append(excerpt).append("\n");
    }
    return context.toString();
  }

  private Object[] retrieverPageArgs(String language,long sessionId,Long institutionId,String query) {
    return institutionId==null
        ? new Object[]{language,sessionId,query,query}
        : new Object[]{language,sessionId,institutionId,query,query};
  }

  private Object[] retrieverArgs(Object first,Object second,Long institutionId,Object last) {
    return institutionId==null
        ? new Object[]{first,second,last}
        : new Object[]{first,second,institutionId,last};
  }

  private Object[] retrieverArgs(long sessionId,Long institutionId,int limit) {
    return institutionId==null
        ? new Object[]{sessionId,limit}
        : new Object[]{sessionId,institutionId,limit};
  }

  private String retrieveTextbookPages(long sessionId, Long institutionId, String language,
      String learnerMessage, String currentTopic) {
    String query = (learnerMessage + " " + currentTopic).trim();
    if (query.isBlank()) return "";
    if (query.length() > 400) query = query.substring(0, 400);
    List<Map<String, Object>> pages = jdbc.queryForList("""
      select coalesce(nullif(sb.source_title,''),nullif(d.source_title,''),a.title) as source_title,
             coalesce(nullif(sb.edition,''),d.edition,'edition not recorded') as edition,
             coalesce(sb.source_url,d.source_url,'source URL not recorded') as source_url,
             p.page_no,coalesce(ch.display_name,'chapter not mapped') as chapter_title,
             left(p.extracted_text,650) as excerpt
      from tutor_session ts
      join lesson current_lesson on current_lesson.id=ts.lesson_id
      join curriculum_chapter current_chapter on current_chapter.id=current_lesson.chapter_id
      join curriculum_subject s on s.id=current_chapter.subject_id and s.active=true
      join curriculum_program program on program.id=ts.course_id
        and program.status='ACTIVE' and program.code<>'GENERAL'
      join curriculum_program_subject course_subject on course_subject.program_id=program.id
        and course_subject.subject_id=s.id and course_subject.class_id=s.class_id
      join curriculum_class current_class on current_class.id=s.class_id and current_class.active=true
      join textbook_extracted_page p on p.subject_id=current_chapter.subject_id
        and p.class_id=s.class_id and p.language=?
      left join learning_pdf_asset a on a.id=p.pdf_asset_id
      left join textbook_source_book sb on sb.id=p.source_book_id
      left join learning_document d on d.id=p.learning_document_id
      left join curriculum_chapter ch on ch.id=p.chapter_id
      where ts.id=? and ts.ended_at is null __TENANT_SCOPE__
        and (s.program_id=program.id or (s.program_id is null and program.class_id=s.class_id))
        and p.quality_status='READY'
        and p.extraction_method in ('PDF_TEXT','OCR')
        and length(trim(p.extracted_text))>=35
        and (
          (sb.id is not null and sb.status='APPROVED')
          or (a.id is not null and a.review_status='APPROVED' and (
            (d.id is not null and d.status='PUBLISHED')
            or exists (
              select 1 from learning_book_intake i
              where i.pdf_asset_id=a.id and i.status='REVIEWED'
            )
          ))
        )
        and to_tsvector('simple',coalesce(p.extracted_text,'')) @@ plainto_tsquery('simple',?)
      order by case when p.chapter_id=current_lesson.chapter_id then 0 else 1 end,
        ts_rank_cd(to_tsvector('simple',coalesce(p.extracted_text,'')),plainto_tsquery('simple',?)) desc,
        p.page_no
      limit 1
      """.replace("__TENANT_SCOPE__", institutionId==null ? "" : "and ts.institution_id=?"),
        retrieverPageArgs(language,sessionId,institutionId,query));
    StringBuilder context = new StringBuilder();
    for (Map<String, Object> row : pages) {
      if (context.length() >= 800) break;
      String excerpt = String.valueOf(row.getOrDefault("excerpt", "")).trim();
      if (excerpt.isBlank()) continue;
      context.append("- Source: ").append(row.get("source_title"))
          .append("; edition: ").append(row.get("edition"))
          .append("; source URL: ").append(row.get("source_url"))
          .append("; chapter: ").append(row.get("chapter_title"))
          .append("; PDF page: ").append(row.get("page_no")).append("\n  Content: ")
          .append(excerpt).append("\n");
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
