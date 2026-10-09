
package com.quantaedge.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/content")
public class AdminContentController {
  private static final Set<String> CHAPTER_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
  private static final Set<String> LESSON_STATUSES = Set.of("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED");
  private static final Set<String> BLOCK_TYPES = Set.of(
      "EXPLANATION", "IMAGE", "DIAGRAM", "VIDEO", "QUESTION", "MCQ", "TRUE_FALSE",
      "MATCH", "ORDER", "INPUT", "HINT", "AI_HELP", "SUMMARY", "CHALLENGE",
      "PREREQUISITE", "WORKED_EXAMPLE", "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE", "RECAP", "AUDIO", "ANIMATION");
  private static final Set<String> QUESTION_TYPES = Set.of(
      "MCQ", "TRUE_FALSE", "INPUT", "MATCH", "ORDER", "ASSERTION_REASON",
      "CASE_BASED", "SHORT_ANSWER", "LONG_ANSWER", "NUMERICAL", "DIAGRAM", "MAP", "SOURCE_BASED");
  private static final Set<String> DIFFICULTIES = Set.of("FOUNDATION", "CORE", "CHALLENGE");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AuthorizationService authorization;

  public AdminContentController(JdbcTemplate jdbc, ObjectMapper mapper, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.authorization = authorization;
  }

  @GetMapping
  public List<Map<String, Object>> list(
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorize(token, context, "CONTENT_VIEW");
    StringBuilder sql = new StringBuilder("""
      select c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name,
             ch.id as chapter_id, ch.code as chapter_code,
             ch.display_name as chapter_name, ch.description as chapter_description,
             ch.sort_order as chapter_sort_order, ch.content_status as chapter_status,
             ch.active as chapter_active, ch.curriculum_source,
             ch.curriculum_source_url, ch.curriculum_source_edition,
             ch.curriculum_source_pages, ch.curriculum_source_verified,
             l.id as lesson_id, l.code as lesson_code, l.title as lesson_title,
             l.summary as lesson_summary, l.estimated_minutes,
             l.sort_order as lesson_sort_order, l.status as lesson_status,
             l.active as lesson_active, l.alignment_source_title,
             l.alignment_source_url, l.alignment_source_edition,
             l.alignment_page_range, l.alignment_source_verified,
             (select count(*) from lesson_block b where b.lesson_id=l.id) as block_count,
             (select count(*) from question q where q.lesson_id=l.id) as question_count
      from curriculum_class c
      join curriculum_subject s on s.class_id=c.id
      left join curriculum_chapter ch on ch.subject_id=s.id
      left join lesson l on l.chapter_id=ch.id
      where 1=1
      """);
    List<Object> args = new ArrayList<>();
    if (classCode != null && !classCode.isBlank()) {
      sql.append(" and c.code=?");
      args.add(classCode.trim());
    }
    if (subjectCode != null && !subjectCode.isBlank()) {
      sql.append(" and s.code=?");
      args.add(subjectCode.trim());
    }
    if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
      sql.append(" and (upper(coalesce(l.status,''))=? or upper(coalesce(ch.content_status,''))=?)");
      args.add(status.trim().toUpperCase());
      args.add(status.trim().toUpperCase());
    }
    sql.append(" order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id");
    return jdbc.queryForList(sql.toString(), args.toArray());
  }

  @GetMapping("/chapters/{chapterId}")
  public Map<String, Object> chapter(
      @PathVariable long chapterId,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorize(token, context, "CONTENT_VIEW");
    return chapterById(chapterId);
  }

  @GetMapping("/lessons/{lessonId}")
  public Map<String, Object> lesson(
      @PathVariable long lessonId,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorize(token, context, "CONTENT_VIEW");
    return lessonById(lessonId);
  }

  @PostMapping("/chapters")
  @Transactional
  public Map<String, Object> createChapter(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorize(token, context, "CONTENT_CREATE");
    String classCode = requiredText(body.get("classCode"), 30);
    String subjectCode = requiredText(body.get("subjectCode"), 40);
    String code = requiredText(body.get("code"), 60).toLowerCase();
    if (!code.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw badRequest("Chapter code must use lowercase letters, numbers, and hyphens.");
    }
    String name = requiredText(body.get("displayName"), 160);
    String description = optionalText(body.get("description"), 500);
    String curriculumSource = optionalText(body.get("curriculumSource"), 500);
    String sourceUrl = optionalText(body.get("curriculumSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("curriculumSourceEdition"), 160);
    String sourcePages = optionalText(body.get("curriculumSourcePages"), 160);
    String status = String.valueOf(body.getOrDefault("status", "DRAFT")).trim().toUpperCase();
    if (!CHAPTER_STATUSES.contains(status)) throw badRequest("Chapter status must be DRAFT, PUBLISHED, or ARCHIVED.");
    if ("PUBLISHED".equals(status)) {
      throw badRequest("Create chapters as draft, verify the source and prepare reviewed lessons before publishing.");
    }
    int sortOrder = integerValue(body.getOrDefault("sortOrder", 1), 1, 10000, "sortOrder");
    List<Map<String, Object>> subjects = jdbc.queryForList("""
      select s.id from curriculum_subject s
      join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true
      """, classCode, subjectCode);
    if (subjects.isEmpty()) throw badRequest("The selected class and subject track does not exist or is inactive.");
    long subjectId = ((Number) subjects.getFirst().get("id")).longValue();
    Long chapterId = jdbc.queryForObject("""
      insert into curriculum_chapter(subject_id,code,display_name,description,sort_order,active,content_status,
                                     curriculum_source,curriculum_source_url,curriculum_source_edition,
                                     curriculum_source_pages,curriculum_source_verified)
      values(?,?,?,?,?,?,?,?,?,?,?,false)
      returning id
      """, Long.class, subjectId, code, name, description, sortOrder,
      !"ARCHIVED".equals(status), status, curriculumSource, sourceUrl, sourceEdition, sourcePages);
    return chapterById(chapterId);
  }

  @PostMapping("/lessons")
  @Transactional
  public Map<String, Object> createLesson(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorize(token, context, "CONTENT_CREATE");
    long chapterId = longValue(body.get("chapterId"), "chapterId");
    chapterById(chapterId);
    String code = requiredText(body.get("code"), 100).toLowerCase();
    if (!code.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw badRequest("Lesson code must use lowercase letters, numbers, and hyphens.");
    }
    String title = requiredText(body.get("title"), 240);
    String summary = optionalText(body.get("summary"), 800);
    int minutes = integerValue(body.getOrDefault("estimatedMinutes", 10), 1, 120, "estimatedMinutes");
    int sortOrder = integerValue(body.getOrDefault("sortOrder", 1), 1, 10000, "sortOrder");
    String sourceTitle = optionalText(body.get("alignmentSourceTitle"), 300);
    String sourceUrl = optionalText(body.get("alignmentSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("alignmentSourceEdition"), 160);
    String sourcePages = optionalText(body.get("alignmentPageRange"), 160);
    String status = String.valueOf(body.getOrDefault("status", "DRAFT")).trim().toUpperCase();
    if (!LESSON_STATUSES.contains(status)) throw badRequest("Lesson status must be DRAFT, REVIEW, PUBLISHED, or ARCHIVED.");
    if ("PUBLISHED".equals(status)) {
      throw badRequest("Create lessons as draft, add reviewed content and questions, then publish.");
    }
    Long lessonId = jdbc.queryForObject("""
      insert into lesson(chapter_id,code,title,summary,estimated_minutes,status,sort_order,active,
                         alignment_source_title,alignment_source_url,alignment_source_edition,
                         alignment_page_range,alignment_source_verified)
      values(?,?,?,?,?,?,?,?,?,?,?, ?,false)
      returning id
      """, Long.class, chapterId, code, title, summary, minutes, status, sortOrder,
      !"ARCHIVED".equals(status), sourceTitle, sourceUrl, sourceEdition, sourcePages);
    return lessonById(lessonId);
  }

  @PatchMapping("/chapters/{chapterId}")
  @Transactional
  public Map<String, Object> updateChapter(
      @PathVariable long chapterId,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAuth(context);
    Map<String,Object> current = chapterById(chapterId);
    String name = requiredText(body.get("displayName"), 160);
    String description = optionalText(body.get("description"), 500);
    String status = requiredText(body.get("status"), 20).toUpperCase();
    if (!CHAPTER_STATUSES.contains(status)) throw badRequest("Chapter status must be DRAFT, PUBLISHED, or ARCHIVED.");
    String curriculumSource = optionalText(body.get("curriculumSource"), 500);
    String sourceUrl = optionalText(body.get("curriculumSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("curriculumSourceEdition"), 160);
    String sourcePages = optionalText(body.get("curriculumSourcePages"), 160);
    boolean sourceVerified = booleanValue(body.get("curriculumSourceVerified"), false);
    int sortOrder = integerValue(body.get("sortOrder"), 1, 10000, "sortOrder");
    boolean fieldsChanged = !Objects.equals(name,current.get("chapter_name"))
        || !Objects.equals(description,current.get("chapter_description"))
        || sortOrder != ((Number)current.get("chapter_sort_order")).intValue()
        || !Objects.equals(curriculumSource,current.get("curriculum_source"))
        || !Objects.equals(sourceUrl,current.get("curriculum_source_url"))
        || !Objects.equals(sourceEdition,current.get("curriculum_source_edition"))
        || !Objects.equals(sourcePages,current.get("curriculum_source_pages"))
        || sourceVerified != Boolean.TRUE.equals(current.get("curriculum_source_verified"));
    String oldStatus=String.valueOf(current.get("chapter_status"));
    if (fieldsChanged) authorization.requirePermission(context,"CONTENT_EDIT");
    if (!status.equals(oldStatus) || "PUBLISHED".equals(status) || "ARCHIVED".equals(status)
        || "PUBLISHED".equals(oldStatus) || "ARCHIVED".equals(oldStatus)) {
      authorization.requirePermission(context,"CONTENT_PUBLISH");
    } else if (!fieldsChanged) {
      authorization.requirePermission(context,"CONTENT_EDIT");
    }
    if (sourceVerified) requireCompleteSourceReference(curriculumSource, sourceUrl, sourceEdition, sourcePages, "chapter");
    if ("PUBLISHED".equals(status)) {
      if (!sourceVerified) throw badRequest("A chapter cannot be published until its official source edition and page mapping are verified.");
      Long readyLessons = current.get("publish_ready_lesson_count") instanceof Number n
          ? n.longValue() : 0L;
      if (readyLessons == null || readyLessons == 0) {
        throw badRequest("Add at least one lesson with active teaching blocks and valid multiple-choice questions before publishing this chapter.");
      }
    }
    int changed = jdbc.update("""
      update curriculum_chapter
      set display_name=?, description=?, sort_order=?, content_status=?, active=?,
          curriculum_source=?, curriculum_source_url=?, curriculum_source_edition=?,
          curriculum_source_pages=?, curriculum_source_verified=?
      where id=?
      """, name, description, sortOrder, status, !"ARCHIVED".equals(status),
      curriculumSource, sourceUrl, sourceEdition, sourcePages, sourceVerified, chapterId);
    if (changed == 0) throw notFound("Chapter", chapterId);
    return chapterById(chapterId);
  }

  @PostMapping("/chapters/{chapterId}/publish")
  @Transactional
  public Map<String,Object> publishChapter(
      @PathVariable long chapterId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    Map<String,Object> current=chapterById(chapterId);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("displayName",current.get("chapter_name"));
    body.put("description",current.get("chapter_description"));
    body.put("status","PUBLISHED");
    body.put("sortOrder",((Number)current.get("chapter_sort_order")).intValue());
    body.put("curriculumSource",current.get("curriculum_source"));
    body.put("curriculumSourceUrl",current.get("curriculum_source_url"));
    body.put("curriculumSourceEdition",current.get("curriculum_source_edition"));
    body.put("curriculumSourcePages",current.get("curriculum_source_pages"));
    body.put("curriculumSourceVerified",Boolean.TRUE.equals(current.get("curriculum_source_verified")));
    return updateChapter(chapterId,body,null,context);
  }

  @PostMapping("/chapters/{chapterId}/unpublish")
  @Transactional
  public Map<String,Object> unpublishChapter(
      @PathVariable long chapterId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    Map<String,Object> current=chapterById(chapterId);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("displayName",current.get("chapter_name"));
    body.put("description",current.get("chapter_description"));
    body.put("status","DRAFT");
    body.put("sortOrder",((Number)current.get("chapter_sort_order")).intValue());
    body.put("curriculumSource",current.get("curriculum_source"));
    body.put("curriculumSourceUrl",current.get("curriculum_source_url"));
    body.put("curriculumSourceEdition",current.get("curriculum_source_edition"));
    body.put("curriculumSourcePages",current.get("curriculum_source_pages"));
    body.put("curriculumSourceVerified",Boolean.TRUE.equals(current.get("curriculum_source_verified")));
    return updateChapter(chapterId,body,null,context);
  }

  @PostMapping("/lessons/{lessonId}/submit")
  @Transactional
  public Map<String,Object> submitLessonForReview(
      @PathVariable long lessonId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context,"CONTENT_SUBMIT");
    Map<String,Object> current=lessonById(lessonId);
    String currentStatus=String.valueOf(current.get("lesson_status"));
    if ("PUBLISHED".equals(currentStatus)||"ARCHIVED".equals(currentStatus)) {
      throw badRequest("Unpublish or restore this lesson before submitting it for review.");
    }
    validateLessonForSubmission(lessonId);
    jdbc.update("update lesson set status='REVIEW', active=true, updated_at=now() where id=?",lessonId);
    jdbc.update("""
        update question
        set review_status='REVIEW',review_notes=null,reviewed_at=null,reviewed_by_staff_id=null
        where lesson_id=? and active=true and review_status not in ('APPROVED','PUBLISHED')
        """,lessonId);
    return lessonById(lessonId);
  }

  @PostMapping("/lessons/{lessonId}/publish")
  @Transactional
  public Map<String,Object> publishLesson(
      @PathVariable long lessonId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    Map<String,Object> current=lessonById(lessonId);
    String currentStatus=String.valueOf(current.get("lesson_status"));
    if (!"REVIEW".equals(currentStatus) && !"ARCHIVED".equals(currentStatus)) {
      throw badRequest("Submit the micro-topic for review before publishing it.");
    }
    validateLessonForPublishing(lessonId);
    jdbc.update("update lesson set status='PUBLISHED', active=true, updated_at=now() where id=?",lessonId);
    return lessonById(lessonId);
  }

  @PostMapping("/lessons/{lessonId}/unpublish")
  @Transactional
  public Map<String,Object> unpublishLesson(
      @PathVariable long lessonId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    lessonById(lessonId);
    jdbc.update("update lesson set status='DRAFT', active=true, updated_at=now() where id=?",lessonId);
    return lessonById(lessonId);
  }

  @PostMapping("/lessons/{lessonId}/questions/{questionId}/review")
  @Transactional
  public Map<String,Object> reviewQuestion(
      @PathVariable long lessonId,
      @PathVariable long questionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context,"CONTENT_REVIEW");
    String status=requiredText(body.get("status"),20).toUpperCase();
    if (!Set.of("DRAFT","REVIEW","APPROVED","REJECTED").contains(status)) {
      throw badRequest("Review status must be Draft, Needs review, Approved or Rejected.");
    }
    Map<String,Object> lessonForReview=lessonById(lessonId);
    if (!"REVIEW".equals(String.valueOf(lessonForReview.get("lesson_status")))) {
      throw badRequest("Submit the micro-topic for review before approving or returning its questions.");
    }
    String notes=optionalText(body.get("reviewNotes"),1200);
    if ("APPROVED".equals(status)) validateQuestionForReview(lessonId,questionId);
    int changed=jdbc.update("""
        update question
        set review_status=?, review_notes=?,
            reviewed_at=case when ?='APPROVED' then now() else null end,
            reviewed_by_staff_id=case when ?='APPROVED' then ? else null end
        where id=? and lesson_id=?
        """,status,notes,status,status,context.staffId(),questionId,lessonId);
    if (changed==0) throw notFound("Question",questionId);
    return jdbc.queryForMap("""
        select id,lesson_id,question_type,prompt,review_status,review_notes,reviewed_at,active
        from question where id=? and lesson_id=?
        """,questionId,lessonId);
  }

  @PatchMapping("/lessons/{lessonId}")
  @Transactional
  public Map<String, Object> updateLesson(
      @PathVariable long lessonId,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAuth(context);
    Map<String,Object> current = lessonById(lessonId);
    String title = requiredText(body.get("title"), 240);
    String summary = optionalText(body.get("summary"), 800);
    int minutes = integerValue(body.get("estimatedMinutes"), 1, 120, "estimatedMinutes");
    int sortOrder = integerValue(body.get("sortOrder"), 1, 10000, "sortOrder");
    String sourceTitle = optionalText(body.get("alignmentSourceTitle"), 300);
    String sourceUrl = optionalText(body.get("alignmentSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("alignmentSourceEdition"), 160);
    String sourcePages = optionalText(body.get("alignmentPageRange"), 160);
    boolean sourceVerified = booleanValue(body.get("alignmentSourceVerified"), false);
    String status = requiredText(body.get("status"), 20).toUpperCase();
    if (!LESSON_STATUSES.contains(status)) throw badRequest("Lesson status must be DRAFT, REVIEW, PUBLISHED, or ARCHIVED.");
    authorization.requirePermission(context,"CONTENT_EDIT");
    if ("REVIEW".equals(status) && !"REVIEW".equals(String.valueOf(current.get("lesson_status")))) {
      authorization.requirePermission(context,"CONTENT_SUBMIT");
    }
    String previousStatus=String.valueOf(current.get("lesson_status"));
    if ("PUBLISHED".equals(status) || "ARCHIVED".equals(status)
        || "PUBLISHED".equals(previousStatus)
        || "ARCHIVED".equals(previousStatus)) {
      authorization.requirePermission(context,"CONTENT_PUBLISH");
    }
    if ("PUBLISHED".equals(status) && !"PUBLISHED".equals(previousStatus)) {
      throw badRequest("Use the separate Publish action after review checks are complete.");
    }
    // Editing a currently published topic must remove the edited draft from learners until it is reviewed and republished.
    if ("PUBLISHED".equals(previousStatus)) status="DRAFT";
    if (sourceVerified) requireCompleteSourceReference(sourceTitle, sourceUrl, sourceEdition, sourcePages, "lesson");
    if ("PUBLISHED".equals(status) && !sourceVerified) {
      throw badRequest("A lesson cannot be published until its textbook/teacher-guide edition and page alignment are verified.");
    }

    if (body.containsKey("blocks")) replaceBlocks(lessonId, body.get("blocks"));
    boolean questionContentChanged = body.containsKey("questions") && updateQuestions(lessonId, body.get("questions"));
    if ("PUBLISHED".equals(status) && questionContentChanged) {
      status = "REVIEW";
    } else if ("PUBLISHED".equals(status)) {
      validateLessonForPublishing(lessonId);
    }

    jdbc.update("""
      update lesson
      set title=?, summary=?, estimated_minutes=?, sort_order=?, status=?, active=?,
          alignment_source_title=?, alignment_source_url=?, alignment_source_edition=?,
          alignment_page_range=?, alignment_source_verified=?
      where id=?
      """, title, summary, minutes, sortOrder, status, !"ARCHIVED".equals(status),
      sourceTitle, sourceUrl, sourceEdition, sourcePages, sourceVerified, lessonId);
    return lessonById(lessonId);
  }

  private void validateQuestionForReview(long lessonId,long questionId) {
    Map<String,Object> q;
    try {
      q=jdbc.queryForMap("""
          select question_type,prompt,source_kind,source_id,source_ref,source_year,
                 source_title,board,answer_payload::text as answer_payload
          from question where id=? and lesson_id=?
          """,questionId,lessonId);
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw notFound("Question",questionId);
    }
    String type=String.valueOf(q.get("question_type")).toUpperCase();
    if (!Set.of("MCQ","TRUE_FALSE","INPUT","NUMERICAL").contains(type)) {
      throw badRequest("This question type is not supported for automatic scoring and cannot be approved yet.");
    }
    if (q.get("prompt")==null||String.valueOf(q.get("prompt")).isBlank()) {
      throw badRequest("Add a question prompt before approval.");
    }
    Map<String,Object> answer;
    try {
      Object parsed=mapper.readValue(String.valueOf(q.get("answer_payload")),Object.class);
      if (!(parsed instanceof Map<?,?> map)) throw badRequest("Question answer key must be an object.");
      answer=new LinkedHashMap<>();
      for (Map.Entry<?,?> entry:map.entrySet()) {
        if (entry.getKey() instanceof String key) answer.put(key,entry.getValue());
      }
    } catch (JacksonException ex) {
      throw badRequest("Stored question answer data is invalid.");
    }
    if ("MCQ".equals(type)||"TRUE_FALSE".equals(type)) {
      List<Map<String,Object>> options=jdbc.queryForList("""
          select option_key,label,is_correct from question_option
          where question_id=? order by sort_order
          """,questionId);
      long correct=options.stream().filter(o->Boolean.TRUE.equals(o.get("is_correct"))).count();
      if (options.size()<2||correct!=1) {
        throw badRequest("Approval requires at least two answer options and exactly one correct answer.");
      }
      String correctKey=String.valueOf(options.stream().filter(o->Boolean.TRUE.equals(o.get("is_correct"))).findFirst().orElseThrow().get("option_key"));
      if (!"OPTION".equals(answer.get("kind"))||!correctKey.equals(String.valueOf(answer.get("value")))) {
        throw badRequest("The answer key does not match the marked correct option.");
      }
    } else if ("INPUT".equals(type)) {
      if (!"TEXT".equals(answer.get("kind"))||String.valueOf(answer.getOrDefault("value","")).isBlank()) {
        throw badRequest("Add the expected text answer before approval.");
      }
    } else if (!"NUMERIC".equals(answer.get("kind"))||answer.get("value")==null) {
      throw badRequest("Add a numeric answer key before approval.");
    }
    String sourceKind=String.valueOf(q.get("source_kind")).toUpperCase();
    if (!"AUTHOR_CREATED".equals(sourceKind)) {
      if (q.get("source_id")==null||q.get("source_ref")==null||String.valueOf(q.get("source_ref")).isBlank()
          ||q.get("source_year")==null||q.get("source_title")==null||q.get("board")==null) {
        throw badRequest("Add source title, board, year and exact page/reference before approving this sourced question.");
      }
      Long validSource=jdbc.queryForObject("""
          select count(*) from content_source
          where id=? and upper(status) in ('VERIFIED','APPROVED','PUBLISHED')
          """,Long.class,q.get("source_id"));
      if(validSource==null||validSource==0) {
        throw badRequest("Verify the referenced source before approving this question.");
      }
    }
  }

  private Map<String,Object> readBlockContent(Object raw) {
    if(raw==null)return Map.of();
    try {
      Object parsed=mapper.readValue(String.valueOf(raw),Object.class);
      if(!(parsed instanceof Map<?,?> values))return Map.of();
      Map<String,Object> result=new LinkedHashMap<>();
      for(Map.Entry<?,?> entry:values.entrySet()) {
        if(entry.getKey() instanceof String key)result.put(key,entry.getValue());
      }
      return result;
    } catch(JacksonException ex) {
      throw badRequest("Stored teaching block content is invalid JSON.");
    }
  }

  private boolean meaningfulTeachingText(Object value) {
    if(value==null)return false;
    String text=String.valueOf(value)
        .replaceAll("<[^>]*>"," ")
        .replace("&nbsp;"," ").replace("&#160;"," ").replace("&amp;","&")
        .replaceAll("\\s+"," ").trim().toLowerCase(java.util.Locale.ROOT);
    if(text.isBlank())return false;
    return !(text.startsWith("यहाँ अपना explanation लिखें")
        || text.startsWith("समस्या या प्रश्न यहाँ लिखें")
        || text.equals("पहला चरण लिखें") || text.equals("अगला चरण लिखें")
        || text.equals("पहला मुख्य विचार") || text.equals("दूसरा मुख्य विचार")
        || text.equals("याद रखने वाली बात")
        || text.equals("explain the core idea") || text.equals("show the important pattern")
        || text.equals("connect it to a real example") || text.equals("add teaching content")
        || text.equals("विद्यार्थी इस visual से क्या समझें?")
        || text.equals("इस media से विद्यार्थी क्या सीखेंगे?")
        || text.equals("अगला छोटा कदम क्या होगा?")
        || text.equals("विद्यार्थी के लिए अभ्यास निर्देश लिखें")
        || text.startsWith("ऐसा संकेत दें जो सोचने में मदद करे")
        || text.equals("write explanation here") || text.equals("write content here")
        || text.equals("untitled") || text.equals("answer choice"));
  }

  private Object firstBlockValue(Map<String,Object> content,String... keys) {
    for(String key:keys) {
      Object value=content.get(key);
      if(value!=null&&!String.valueOf(value).isBlank())return value;
    }
    return null;
  }

  private boolean everyMeaningfulItem(Object value) {
    if(!(value instanceof List<?> items)||items.isEmpty())return false;
    return items.stream().allMatch(this::meaningfulTeachingText);
  }

  private boolean blockHasPublishableContent(String type,Map<String,Object> content) {
    switch(type) {
      case "EXPLANATION":
      case "PREREQUISITE":
        return meaningfulTeachingText(firstBlockValue(content,"html","body","text"));
      case "WORKED_EXAMPLE":
        return meaningfulTeachingText(content.get("problem"))
            && everyMeaningfulItem(content.get("steps"))
            && meaningfulTeachingText(content.get("answer"));
      case "IMAGE":
      case "DIAGRAM":
      case "ANIMATION":
      case "VIDEO":
      case "AUDIO":
        return String.valueOf(content.getOrDefault("url","")).matches("(?i)^https://.+")
            && meaningfulTeachingText(content.get("description"));
      case "GUIDED_PRACTICE":
      case "INDEPENDENT_PRACTICE":
      case "CHALLENGE":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","instructions"));
      case "SUMMARY":
      case "RECAP":
        return everyMeaningfulItem(content.get("points"));
      case "HINT":
        return meaningfulTeachingText(firstBlockValue(content,"body","hint"));
      case "AI_HELP":
        return true;
      case "MCQ":
      case "TRUE_FALSE":
      case "QUESTION":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","question","body"))
            && content.get("options") instanceof List<?> options && options.size()>=2;
      case "MATCH":
      case "ORDER":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","instructions","body"));
      case "INPUT":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","question","body"));
      default:
        return meaningfulTeachingText(firstBlockValue(content,"prompt","body","description","text"));
    }
  }

  private void validateLessonForSubmission(long lessonId) {
    Map<String,Object> lesson=lessonById(lessonId);
    if(lesson.get("lesson_title")==null||String.valueOf(lesson.get("lesson_title")).isBlank()
        ||lesson.get("lesson_summary")==null||String.valueOf(lesson.get("lesson_summary")).isBlank()) {
      throw badRequest("Add a topic title and learner goal before submitting for review.");
    }
    Object minutes=lesson.get("estimated_minutes");
    if(!(minutes instanceof Number n)||n.intValue()<1||n.intValue()>120) {
      throw badRequest("Set a valid learning time from 1 to 120 minutes before submitting for review.");
    }
    Long parentReady=jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=? and l.active=true and ch.active=true
        and s.active=true and c.active=true
      """,Long.class,lessonId);
    if(parentReady==null||parentReady==0) {
      throw badRequest("Ensure the chapter, class and subject are active before submitting this micro-topic. The chapter may remain a draft during review.");
    }
    Long sourceReady=jdbc.queryForObject("""
      select count(*) from lesson
      where id=? and alignment_source_verified=true
        and nullif(btrim(alignment_source_title),'') is not null
        and lower(coalesce(alignment_source_url,'')) like 'https://%'
        and nullif(btrim(alignment_source_edition),'') is not null
        and nullif(btrim(alignment_page_range),'') is not null
      """,Long.class,lessonId);
    if(sourceReady==null||sourceReady==0) {
      throw badRequest("Complete and verify the textbook source title, HTTPS URL, edition and page range before submitting.");
    }
    List<Map<String,Object>> blocks=jdbc.queryForList("""
      select block_type,content::text as content
      from lesson_block where lesson_id=? and active=true order by sequence_no
      """,lessonId);
    if(blocks.isEmpty()) throw badRequest("Add at least one complete teaching block before submitting for review.");
    boolean hasCoreBlock=false;
    for(Map<String,Object> row:blocks) {
      String type=String.valueOf(row.get("block_type")).toUpperCase();
      if(!blockHasPublishableContent(type,readBlockContent(row.get("content")))) {
        throw badRequest("Complete the "+type.replace('_',' ').toLowerCase(java.util.Locale.ROOT)+
            " teaching block or deactivate it before submitting for review.");
      }
      if(!"AI_HELP".equals(type))hasCoreBlock=true;
    }
    if(!hasCoreBlock)throw badRequest("Add at least one complete teaching block beyond the AI tutor entry.");
    Long activeQuestions=jdbc.queryForObject(
        "select count(*) from question where lesson_id=? and active=true",Long.class,lessonId);
    if(activeQuestions==null||activeQuestions==0) {
      throw badRequest("Add at least one active practice question before submitting for review.");
    }
  }

  private void validateLessonForPublishing(long lessonId) {
    validateLessonForPublishing(lessonId,true);
  }

  private void validateLessonForPublishing(long lessonId,boolean requirePublishedChapter) {
    Long parentReady = jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=? and l.active=true
        and (not ? or (ch.active=true and ch.content_status='PUBLISHED'))
        and s.active=true and c.active=true
      """, Long.class, lessonId, requirePublishedChapter);
    if (parentReady == null || parentReady == 0) {
      throw badRequest("Publish the parent chapter first and ensure its class and subject are active.");
    }
    Long sourceReady = jdbc.queryForObject("""
      select count(*) from lesson
      where id=? and alignment_source_verified=true
        and nullif(btrim(alignment_source_title),'') is not null
        and lower(coalesce(alignment_source_url,'')) like 'https://%'
        and nullif(btrim(alignment_source_edition),'') is not null
        and nullif(btrim(alignment_page_range),'') is not null
      """,Long.class,lessonId);
    if(sourceReady==null||sourceReady==0) {
      throw badRequest("Complete and verify the textbook source title, HTTPS URL, edition and page range before publishing.");
    }

    List<Map<String,Object>> activeBlocks = jdbc.queryForList("""
      select block_type,content::text as content
      from lesson_block where lesson_id=? and active=true order by sequence_no
      """,lessonId);
    if(activeBlocks.isEmpty()) {
      throw badRequest("Add at least one active teaching block before publishing this lesson.");
    }
    boolean hasCoreTeachingBlock=false;
    for(Map<String,Object> row:activeBlocks) {
      String type=String.valueOf(row.get("block_type")).toUpperCase();
      Map<String,Object> content=readBlockContent(row.get("content"));
      if(!blockHasPublishableContent(type,content)) {
        throw badRequest("Complete the "+type.replace('_',' ').toLowerCase(java.util.Locale.ROOT)+
            " teaching block or deactivate it before publishing.");
      }
      if(!"AI_HELP".equals(type)) hasCoreTeachingBlock=true;
    }
    if(!hasCoreTeachingBlock) {
      throw badRequest("Add at least one complete teaching block beyond the AI tutor entry.");
    }
    Long questions = jdbc.queryForObject(
        "select count(*) from question where lesson_id=? and active=true", Long.class, lessonId);
    if (questions == null || questions == 0) {
      throw badRequest("Add at least one active practice question before publishing this lesson.");
    }
    Long invalidQuestions = jdbc.queryForObject("""
      select count(*) from question q
      where q.lesson_id=? and q.active=true
        and (
          q.question_type not in ('MCQ','TRUE_FALSE','INPUT','NUMERICAL')
          or q.review_status<>'APPROVED'
          or nullif(btrim(q.prompt),'') is null
          or (q.question_type in ('MCQ','TRUE_FALSE') and (
            (select count(*) from question_option qo where qo.question_id=q.id)<2
            or exists(select 1 from question_option qo where qo.question_id=q.id and nullif(btrim(qo.label),'') is null)
            or (select count(*) from question_option qo where qo.question_id=q.id and qo.is_correct)<>1
            or coalesce(q.answer_payload->>'kind','')<>'OPTION'
            or q.answer_payload->>'value' is distinct from (
              select qo.option_key from question_option qo where qo.question_id=q.id and qo.is_correct limit 1
            )
          ))
          or (q.question_type='INPUT' and (coalesce(q.answer_payload->>'kind','')<>'TEXT' or nullif(btrim(q.answer_payload->>'value'),'') is null))
          or (q.question_type='NUMERICAL' and (coalesce(q.answer_payload->>'kind','')<>'NUMERIC' or q.answer_payload->'value' is null or q.answer_payload->'value'='null'::jsonb))
          or (q.source_kind<>'AUTHOR_CREATED' and (
            q.source_id is null or nullif(btrim(q.source_ref),'') is null or q.source_year is null
            or nullif(btrim(q.source_title),'') is null or nullif(btrim(q.board),'') is null
            or not exists(select 1 from content_source src where src.id=q.source_id and upper(src.status) in ('VERIFIED','APPROVED','PUBLISHED'))
          ))
        )
      """, Long.class, lessonId);
    if (invalidQuestions != null && invalidQuestions > 0) {
      throw badRequest("Every published question must use a supported grading type and valid answer key: MCQ/true-false need exactly one correct option, input needs a TEXT key, numerical needs a NUMERIC key, and source-backed questions need verified provenance.");
    }
  }

  private Map<String, Object> chapterById(long chapterId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select ch.id as chapter_id, ch.code as chapter_code,
             ch.display_name as chapter_name, ch.description as chapter_description,
             ch.sort_order as chapter_sort_order, ch.content_status as chapter_status,
             ch.active as chapter_active, ch.curriculum_source,
             ch.curriculum_source_url, ch.curriculum_source_edition,
             ch.curriculum_source_pages, ch.curriculum_source_verified,
             c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name,
             (select count(*) from lesson l where l.chapter_id=ch.id) as lesson_count
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where ch.id=?
      """, chapterId);
    if (rows.isEmpty()) throw notFound("Chapter", chapterId);
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    List<Long> activeLessons=jdbc.queryForList(
        "select id from lesson where chapter_id=? and active=true order by sort_order,id",Long.class,chapterId);
    long publishReady=0;
    for(Long lessonId:activeLessons) {
      try {
        validateLessonForPublishing(lessonId,false);
        publishReady++;
      } catch(ResponseStatusException notReady) {
        // A chapter may contain draft topics, but at least one topic must be genuinely publishable.
      }
    }
    result.put("publish_ready_lesson_count",publishReady);
    return result;
  }

  private Map<String, Object> lessonById(long lessonId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select l.id as lesson_id, l.code as lesson_code, l.title as lesson_title,
             l.summary as lesson_summary, l.estimated_minutes,
             l.sort_order as lesson_sort_order, l.status as lesson_status,
             l.active as lesson_active, l.alignment_source_title,
             l.alignment_source_url, l.alignment_source_edition,
             l.alignment_page_range, l.alignment_source_verified,
             ch.id as chapter_id, ch.code as chapter_code,
             ch.display_name as chapter_name, ch.description as chapter_description,
             ch.content_status as chapter_status, ch.active as chapter_active,
             ch.curriculum_source, ch.curriculum_source_url, ch.curriculum_source_edition,
             ch.curriculum_source_pages, ch.curriculum_source_verified,
             c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=?
      """, lessonId);
    if (rows.isEmpty()) throw notFound("Lesson", lessonId);
    Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("blocks", jdbc.queryForList("""
      select sequence_no, block_type, content::text as content, active
      from lesson_block where lesson_id=? order by sequence_no
      """, lessonId));
    result.put("questions", jdbc.queryForList("""
      select q.id, q.question_type, q.prompt, q.explanation, q.difficulty,
             q.sort_order, q.active, q.review_status, q.review_notes, q.reviewed_at,
             q.marks, q.exam_format, q.source_kind, q.source_title, q.source_ref,
             q.source_year, q.source_id, q.board, q.topic, q.subtopic, q.skill,
             q.tags::text as tags, q.answer_payload::text as answer_payload,
             coalesce(
               (select jsonb_agg(jsonb_build_object(
                 'key',qo.option_key,'label',qo.label,'correct',qo.is_correct,
                 'sort_order',qo.sort_order
               ) order by qo.sort_order) from question_option qo where qo.question_id=q.id),
               '[]'::jsonb
             )::text as options
      from question q where q.lesson_id=? order by q.sort_order,q.id
      """, lessonId));
    return result;
  }

  private void replaceBlocks(long lessonId, Object value) {
    List<Map<String, Object>> blocks = objectList(value, "blocks");
    if (blocks.isEmpty() || blocks.size() > 100) throw badRequest("A lesson must contain between 1 and 100 content blocks.");
    Set<Integer> sequences = new HashSet<>();
    for (Map<String, Object> block : blocks) {
      int sequence = integerValue(first(block, "sequence_no", "sequenceNo"), 1, 10000, "sequence_no");
      if (!sequences.add(sequence)) throw badRequest("Content block sequence numbers must be unique.");
      String type = requiredText(first(block, "block_type", "blockType"), 40).toUpperCase();
      if (!BLOCK_TYPES.contains(type)) throw badRequest("Unsupported content block type: " + type);
      if (!(block.get("content") instanceof Map<?, ?>)) throw badRequest("Each block's content must be a JSON object.");
      toJson(block.get("content"));
    }
    jdbc.update("delete from lesson_block where lesson_id=?", lessonId);
    for (Map<String, Object> block : blocks) {
      jdbc.update("""
        insert into lesson_block(lesson_id,sequence_no,block_type,content,active)
        values(?,?,?,?::jsonb,?)
        """, lessonId,
        integerValue(first(block, "sequence_no", "sequenceNo"), 1, 10000, "sequence_no"),
        requiredText(first(block, "block_type", "blockType"), 40).toUpperCase(),
        toJson(block.get("content")), booleanValue(block.get("active"), true));
    }
  }

  private boolean updateQuestions(long lessonId, Object value) {
    List<Map<String, Object>> questions = objectList(value, "questions");
    List<Map<String, Object>> current = jdbc.queryForList("select id from question where lesson_id=?", lessonId);
    Set<Long> currentIds = new HashSet<>();
    current.forEach(row -> currentIds.add(((Number) row.get("id")).longValue()));
    Set<Long> submittedIds = new HashSet<>();
    boolean questionContentChanged = false;

    for (Map<String, Object> q : questions) {
      boolean create = q.get("id") == null;
      long id = create ? 0L : longValue(q.get("id"), "question id");
      if (!create && (!currentIds.contains(id) || submittedIds.contains(id))) {
        throw badRequest("Question IDs must be unique and belong to this lesson.");
      }
      String type = requiredText(first(q, "question_type", "questionType"), 30).toUpperCase();
      String difficulty = requiredText(q.get("difficulty"), 20).toUpperCase();
      String prompt = requiredText(q.get("prompt"), 1000);
      String explanation = optionalText(q.get("explanation"), 1200);
      String reviewStatus = "DRAFT";
      String reviewNotes = null; // Review state can only change through the permissioned review endpoint.
      int order = integerValue(first(q, "sort_order", "sortOrder"), 1, 10000, "sort_order");
      Integer marks = optionalInteger(first(q, "marks", "marks"), 1, 100, "marks");
      String examFormat = optionalText(first(q, "exam_format", "examFormat"), 40);
      String sourceKind = String.valueOf(first(q, "source_kind", "sourceKind") == null
          ? "AUTHOR_CREATED" : first(q, "source_kind", "sourceKind")).trim().toUpperCase();
      String sourceTitle = optionalText(first(q, "source_title", "sourceTitle"), 300);
      String sourceRef = optionalText(first(q, "source_ref", "sourceRef"), 500);
      Integer sourceYear = optionalInteger(first(q, "source_year", "sourceYear"), 1800, 2100, "sourceYear");
      Long sourceId = optionalLong(first(q, "source_id", "sourceId"), "sourceId");
      String board = optionalText(q.get("board"), 80);
      String topic = optionalText(q.get("topic"), 200);
      String subtopic = optionalText(q.get("subtopic"), 200);
      String skill = optionalText(q.get("skill"), 200);
      String tagsJson = jsonText(q.get("tags"), "tags", "[]");
      String answerPayload = jsonText(first(q, "answer_payload", "answerPayload"), "answer_payload", "{}");

      if (!QUESTION_TYPES.contains(type)) throw badRequest("Unsupported question type: " + type);
      if (!DIFFICULTIES.contains(difficulty)) throw badRequest("Unsupported question difficulty: " + difficulty);
      if (!Set.of("DRAFT","REVIEW","APPROVED","PUBLISHED","REJECTED").contains(reviewStatus)) {
        throw badRequest("Question review status must be DRAFT, REVIEW, APPROVED, PUBLISHED, or REJECTED.");
      }
      if (!Set.of("AUTHOR_CREATED","STATE_TEXTBOOK","BOARD_PAST_PAPER","TEXTBOOK_DERIVED").contains(sourceKind)) {
        throw badRequest("Unsupported question source kind.");
      }
      if (!"AUTHOR_CREATED".equals(sourceKind)) {
        if (sourceId == null || sourceRef == null || sourceYear == null || board == null || sourceTitle == null) {
          throw badRequest("Source-backed questions need a registered source, exact reference/page, year, board and source title.");
        }
        Long sourceCount = jdbc.queryForObject("select count(*) from content_source where id=?", Long.class, sourceId);
        if (sourceCount == null || sourceCount == 0) throw badRequest("Registered content source not found.");
        String sourceStatus = jdbc.queryForObject("select status from content_source where id=?", String.class, sourceId);
        if (Set.of("APPROVED","PUBLISHED").contains(reviewStatus)
            && !Set.of("VERIFIED","APPROVED","PUBLISHED").contains(String.valueOf(sourceStatus).toUpperCase())) {
          throw badRequest("This source is still a candidate/reference. Verify its edition and page mapping before approving source-backed questions.");
        }
      }

      List<Map<String, Object>> options = objectList(q.get("options"), "question options");
      if (("MCQ".equals(type) || "TRUE_FALSE".equals(type)) && options.size() < 2) {
        throw badRequest("Multiple-choice and true/false questions need at least two options.");
      }
      if (!Set.of("MCQ", "TRUE_FALSE").contains(type) && !options.isEmpty()) {
        throw badRequest("Only MCQ and true/false questions may use choice options in the current student UI.");
      }
      if (options.size() > 10) throw badRequest("A question cannot have more than 10 options.");
      Set<String> optionKeys = new HashSet<>();
      int correctCount = 0;
      String correctKey = null;
      for (Map<String, Object> option : options) {
        String key = requiredText(first(option, "key", "option_key"), 20);
        requiredText(option.get("label"), 500);
        if (!optionKeys.add(key)) throw badRequest("Option keys must be unique within a question.");
        if (booleanValue(first(option, "correct", "is_correct"), false)) {
          correctCount++;
          correctKey = key;
        }
      }
      if (("MCQ".equals(type) || "TRUE_FALSE".equals(type)) && correctCount != 1) {
        throw badRequest("Each multiple-choice question must have exactly one correct answer.");
      }
      if ("MCQ".equals(type) || "TRUE_FALSE".equals(type)) {
        answerPayload = jsonText(toJson(Map.of("kind", "OPTION", "value", correctKey)), "answer_payload", "{}");
      }

      if (create) {
        questionContentChanged = true;
        if ("APPROVED".equals(reviewStatus) || "PUBLISHED".equals(reviewStatus)) {
          reviewStatus = "DRAFT";
          reviewNotes = "New question requires a separate review and approval.";
        }
        Long createdId = jdbc.queryForObject("""
          insert into question(
            lesson_id,question_type,prompt,explanation,difficulty,sort_order,active,
            review_status,review_notes,reviewed_at,marks,exam_format,source_kind,source_title,
            source_ref,source_year,source_id,board,topic,subtopic,skill,tags,answer_payload
          )
          values(?,?,?,?,?,?,?, ?,?,null, ?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
          returning id
          """, Long.class, lessonId, type, prompt, explanation, difficulty, order,
          booleanValue(q.get("active"), true), reviewStatus, reviewNotes,
          marks, examFormat, sourceKind, sourceTitle, sourceRef, sourceYear,
          sourceId, board, topic, subtopic, skill, tagsJson, answerPayload);
        id = createdId;
      } else {
        Map<String, Object> existing = jdbc.queryForMap("""
          select question_type,prompt,explanation,difficulty,review_status,review_notes,marks,exam_format,
                 source_kind,source_title,source_ref,source_year,source_id,board,topic,subtopic,skill,
                 tags::text as tags,answer_payload::text as answer_payload
          from question where id=? and lesson_id=?
          """, id, lessonId);
        reviewStatus = String.valueOf(existing.get("review_status"));
        reviewNotes = existing.get("review_notes")==null?null:String.valueOf(existing.get("review_notes"));
        boolean changed = !type.equals(String.valueOf(existing.get("question_type")))
            || !prompt.equals(String.valueOf(existing.get("prompt")))
            || !java.util.Objects.equals(explanation, existing.get("explanation"))
            || !difficulty.equals(String.valueOf(existing.get("difficulty")))
            || !java.util.Objects.equals(marks, existing.get("marks") == null ? null : ((Number) existing.get("marks")).intValue())
            || !java.util.Objects.equals(examFormat, existing.get("exam_format"))
            || !sourceKind.equals(String.valueOf(existing.get("source_kind")))
            || !java.util.Objects.equals(sourceTitle, existing.get("source_title"))
            || !java.util.Objects.equals(sourceRef, existing.get("source_ref"))
            || !java.util.Objects.equals(sourceYear, existing.get("source_year") == null ? null : ((Number) existing.get("source_year")).intValue())
            || !java.util.Objects.equals(sourceId, existing.get("source_id") == null ? null : ((Number) existing.get("source_id")).longValue())
            || !java.util.Objects.equals(board, existing.get("board"))
            || !java.util.Objects.equals(topic, existing.get("topic"))
            || !java.util.Objects.equals(subtopic, existing.get("subtopic"))
            || !java.util.Objects.equals(skill, existing.get("skill"))
            || !tagsJson.equals(jsonText(existing.get("tags"), "tags", "[]"))
            || !answerPayload.equals(jsonText(existing.get("answer_payload"), "answer_payload", "{}"));

        List<Map<String, Object>> oldOptions = jdbc.queryForList("""
          select option_key,label,is_correct,sort_order from question_option
          where question_id=? order by sort_order
          """, id);
        if (oldOptions.size() != options.size()) changed = true;
        for (int i = 0; !changed && i < options.size(); i++) {
          Map<String, Object> incoming = options.get(i);
          Map<String, Object> previous = oldOptions.get(i);
          Object suppliedOrder = first(incoming, "sort_order", "sortOrder");
          int incomingOrder = suppliedOrder instanceof Number ? ((Number) suppliedOrder).intValue() : i + 1;
          changed = !requiredText(first(incoming, "key", "option_key"), 20).equals(String.valueOf(previous.get("option_key")))
              || !requiredText(incoming.get("label"), 500).equals(String.valueOf(previous.get("label")))
              || booleanValue(first(incoming, "correct", "is_correct"), false) != Boolean.TRUE.equals(previous.get("is_correct"))
              || incomingOrder != ((Number) previous.get("sort_order")).intValue();
        }
        if (changed) {
          questionContentChanged = true;
          if ("APPROVED".equals(String.valueOf(existing.get("review_status"))) || "PUBLISHED".equals(String.valueOf(existing.get("review_status")))) {
            reviewStatus = "DRAFT";
            reviewNotes = "Question content or provenance changed; review and approve again before publishing.";
          }
        }
        jdbc.update("""
          update question set question_type=?,prompt=?,explanation=?,difficulty=?,sort_order=?,active=?,
              review_status=?,review_notes=?,reviewed_at=case when ?='APPROVED' then coalesce(reviewed_at,now()) else null end,
              reviewed_by_staff_id=case when ?='APPROVED' then reviewed_by_staff_id else null end,
              marks=?,exam_format=?,source_kind=?,source_title=?,source_ref=?,source_year=?,source_id=?,board=?,
              topic=?,subtopic=?,skill=?,tags=?::jsonb,answer_payload=?::jsonb
          where id=? and lesson_id=?
          """, type, prompt, explanation, difficulty, order, booleanValue(q.get("active"), true),
          reviewStatus, reviewNotes, reviewStatus, reviewStatus, marks, examFormat, sourceKind, sourceTitle, sourceRef,
          sourceYear, sourceId, board, topic, subtopic, skill, tagsJson, answerPayload, id, lessonId);
      }
      submittedIds.add(id);
      jdbc.update("delete from question_option where question_id=?", id);
      int index = 1;
      for (Map<String, Object> option : options) {
        Object suppliedOrder = first(option, "sort_order", "sortOrder");
        int optionOrder = suppliedOrder instanceof Number ? integerValue(suppliedOrder, 1, 10000, "option sort_order") : index;
        jdbc.update("""
          insert into question_option(question_id,option_key,label,is_correct,sort_order)
          values(?,?,?,?,?)
          """, id, requiredText(first(option, "key", "option_key"), 20),
          requiredText(option.get("label"), 500),
          booleanValue(first(option, "correct", "is_correct"), false), optionOrder);
        index++;
      }
    }
    for (Long id : currentIds) {
      if (!submittedIds.contains(id)) jdbc.update("update question set active=false where id=? and lesson_id=?", id, lessonId);
    }
    return questionContentChanged;
  }

  private String jsonText(Object value, String name, String fallback) {
    if (value == null) return fallback;
    if (value instanceof String text) {
      if (text.isBlank()) return fallback;
      try { return mapper.readTree(text).toString(); }
      catch (JacksonException ex) { throw badRequest(name + " must contain valid JSON."); }
    }
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException ex) { throw badRequest(name + " must contain valid JSON."); }
  }

  private static Integer optionalInteger(Object value, int min, int max, String name) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    final int result;
    try { result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim()); }
    catch (NumberFormatException ex) { throw badRequest(name + " must be an integer."); }
    if (result < min || result > max) throw badRequest(name + " must be between " + min + " and " + max + ".");
    return result;
  }

  private static Long optionalLong(Object value, String name) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    try { long result = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value).trim()); if (result < 1) throw badRequest(name + " must be positive."); return result; }
    catch (NumberFormatException ex) { throw badRequest(name + " must be an integer."); }
  }

  private String toJson(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException ex) { throw badRequest("Content must contain valid JSON."); }
  }

  private void requireCompleteSourceReference(String title, String url, String edition, String pages, String type) {
    if (title == null || title.isBlank() || url == null || !url.startsWith("https://")
        || edition == null || edition.isBlank() || pages == null || pages.isBlank()) {
      throw badRequest("Verified " + type + " source requires an official HTTPS URL, exact edition/session and page range.");
    }
  }

  private void authorize(String suppliedToken, AuthContext context, String permission) {
    // Shared browser tokens never grant access. Every task is checked against the authenticated identity.
    authorization.requirePermission(context,permission);
  }

  private static List<Map<String, Object>> objectList(Object value, String name) {
    if (!(value instanceof List<?> list)) throw badRequest(name + " must be a JSON array.");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) throw badRequest("Each entry in " + name + " must be an object.");
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw badRequest("Invalid object key in " + name + ".");
        converted.put(key, entry.getValue());
      }
      result.add(converted);
    }
    return result;
  }

  private static Object first(Map<String, Object> map, String preferred, String alternate) {
    return map.containsKey(preferred) ? map.get(preferred) : map.get(alternate);
  }

  private static String requiredText(Object value, int maxLength) {
    if (!(value instanceof String text) || text.isBlank() || text.trim().length() > maxLength)
      throw badRequest("A non-empty text value no longer than " + maxLength + " characters is required.");
    return text.trim();
  }

  private static String optionalText(Object value, int maxLength) {
    if (value == null) return null;
    if (!(value instanceof String text) || text.trim().length() > maxLength)
      throw badRequest("Text must be no longer than " + maxLength + " characters.");
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int integerValue(Object value, int min, int max, String name) {
    if (!(value instanceof Number number)) throw badRequest(name + " must be a number.");
    int result = number.intValue();
    if (result < min || result > max) throw badRequest(name + " must be between " + min + " and " + max + ".");
    return result;
  }

  private static long longValue(Object value, String name) {
    if (!(value instanceof Number number)) throw badRequest(name + " must be numeric.");
    return number.longValue();
  }

  private static boolean booleanValue(Object value, boolean fallback) {
    return value instanceof Boolean bool ? bool : fallback;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String type, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " " + id + " was not found.");
  }
}
