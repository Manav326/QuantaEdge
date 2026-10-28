package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapts the canonical relational curriculum to the shared structured-lesson API contract.
 *
 * The relational lesson/chapter/question rows remain the source of truth. This adapter does
 * not import, duplicate, or rewrite them into packaged JSON. Explicitly published legacy rows
 * are serialized into the same stable payload shape consumed by Web and Android.
 */
@Service
public class RelationalStructuredLessonService {
  private static final String LESSON_PREFIX = "db-lesson-";
  private static final String QUESTION_PREFIX = "db-q-";
  private static final String BLOCK_PREFIX = "db-block-";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public record LoadedLesson(
      long lessonId, long courseId, String artifactId, String sha256, ObjectNode payload) {}

  public RelationalStructuredLessonService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public static boolean isDatabaseLessonId(String value) {
    return parseDatabaseLessonId(value, LESSON_PREFIX) != null;
  }

  public static Long parseDatabaseLessonId(String value) {
    return parseDatabaseLessonId(value, LESSON_PREFIX);
  }

  public static boolean isDatabaseQuestionId(String value) {
    return parseDatabaseLessonId(value, QUESTION_PREFIX) != null;
  }

  public static boolean isDatabaseBlockId(String value) {
    return parseDatabaseLessonId(value, BLOCK_PREFIX) != null;
  }

  private static Long parseDatabaseLessonId(String value, String prefix) {
    if (value == null || !value.startsWith(prefix)) return null;
    String numeric = value.substring(prefix.length());
    if (!numeric.matches("[1-9][0-9]{0,17}")) return null;
    try {
      return Long.parseLong(numeric);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  /** Catalogue rows are filtered through the exact institution, assigned course and published DB rows. */
  public List<Map<String, Object>> catalogue(
      long studentId, long institutionId, long courseId, String language, String subjectCode) {
    String subjectFilter = subjectCode == null ? "" : subjectCode.trim().toLowerCase(Locale.ROOT);
    if (!subjectFilter.isBlank() && !subjectFilter.matches("[a-z0-9][a-z0-9_-]{0,39}")) {
      throw new IllegalArgumentException("Choose a subject from the selected course.");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
      with numbered_chapters as (
        select ch.id,
               row_number() over (
                 partition by ch.subject_id
                 order by coalesce(ch.teaching_order,ch.sort_order),ch.id
               )::int as chapter_number
        from curriculum_chapter ch
        where ch.active=true and ch.content_status='PUBLISHED'
      ), numbered_lessons as (
        select l.id,
               row_number() over(partition by l.chapter_id order by l.sort_order,l.id)::int as topic_number
        from lesson l
        where l.active=true and l.status='PUBLISHED'
      )
      select l.id as lesson_id,l.code as lesson_code,
             coalesce(lte.title,l.title) as title_en,coalesce(lth.title,l.title) as title_hi,
             ch.id as chapter_id,nc.chapter_number,
             coalesce(che.display_name,ch.display_name) as chapter_title_en,
             coalesce(chh.display_name,ch.display_name) as chapter_title_hi,
             s.code as subject_code,
             nl.topic_number,l.estimated_minutes
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
        and ch.content_status='PUBLISHED'
      join numbered_chapters nc on nc.id=ch.id
      join numbered_lessons nl on nl.id=l.id
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join curriculum_program p on p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=p.id
        and cps.subject_id=s.id and cps.class_id=s.class_id
      join institution_membership m on m.student_id=? and m.institution_id=? and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join student_program_enrollment spe on spe.student_id=m.student_id
        and spe.institution_id=m.institution_id and spe.program_id=p.id and spe.status='ACTIVE'
        and spe.course_access_blocked=false
        and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      join institution_course_assignment assignment on assignment.institution_id=m.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=p.id
      left join lateral (
        select tr.title from lesson_translation tr
        where tr.lesson_id=l.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) lte on true
      left join lateral (
        select tr.title from lesson_translation tr
        where tr.lesson_id=l.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) lth on true
      left join lateral (
        select tr.display_name from curriculum_chapter_translation tr
        where tr.chapter_id=ch.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) che on true
      left join lateral (
        select tr.display_name from curriculum_chapter_translation tr
        where tr.chapter_id=ch.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) chh on true
      where l.active=true and l.status='PUBLISHED'
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
        and (?='' or lower(s.code)=?)
      order by s.sort_order,coalesce(ch.teaching_order,ch.sort_order),nl.topic_number,l.id
      """, courseId, studentId, institutionId, subjectFilter, subjectFilter);

    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      long lessonId = ((Number) row.get("lesson_id")).longValue();
      item.put("artifactId", LESSON_PREFIX + lessonId);
      item.put("subjectCode", text(row.get("subject_code")));
      item.put("chapterNumber", number(row.get("chapter_number")));
      item.put("chapterTitle", localized(row, language, "chapter_title"));
      item.put("topicId", text(row.get("lesson_code")));
      item.put("topicNumber", number(row.get("topic_number")));
      item.put("title", localized(row, language, "title"));
      item.put("estimatedMinutes", number(row.get("estimated_minutes")));
      result.add(item);
    }
    return List.copyOf(result);
  }

  public LoadedLesson requirePublishedLesson(
      String artifactId, long studentId, long institutionId, long courseId) {
    Long lessonId = parseDatabaseLessonId(artifactId);
    if (lessonId == null) throw notAvailable();
    List<Map<String, Object>> rows = jdbc.queryForList("""
      with numbered_chapters as (
        select ch.id,
               row_number() over (
                 partition by ch.subject_id
                 order by coalesce(ch.teaching_order,ch.sort_order),ch.id
               )::int as chapter_number
        from curriculum_chapter ch
        where ch.active=true and ch.content_status='PUBLISHED'
      ), numbered_lessons as (
        select l.id,
               row_number() over(partition by l.chapter_id order by l.sort_order,l.id)::int as topic_number
        from lesson l
        where l.active=true and l.status='PUBLISHED'
      )
      select l.id as lesson_id,l.code as lesson_code,l.title as lesson_title,l.summary as lesson_summary,
             l.estimated_minutes,l.sort_order as lesson_sort_order,nl.topic_number,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_title,
             ch.description as chapter_description,nc.chapter_number,
             s.id as subject_id,s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,c.display_name as class_name,
             coalesce(lte.title,l.title) as title_en,coalesce(lth.title,l.title) as title_hi,
             coalesce(lte.summary,l.summary) as summary_en,coalesce(lth.summary,l.summary) as summary_hi,
             coalesce(che.display_name,ch.display_name) as chapter_title_en,
             coalesce(chh.display_name,ch.display_name) as chapter_title_hi,
             coalesce(se.display_name,s.display_name) as subject_name_en,
             coalesce(sh.display_name,s.display_name) as subject_name_hi,
             coalesce(ce.display_name,c.display_name) as class_name_en,
             coalesce(cuh.display_name,c.display_name) as class_name_hi,
             o.id as objective_id,o.code as objective_code,o.title as objective_title,
             o.description as objective_description,
             coalesce(oe.title,o.title) as objective_title_en,coalesce(oh.title,o.title) as objective_title_hi,
             coalesce(oe.description,o.description) as objective_description_en,
             coalesce(oh.description,o.description) as objective_description_hi
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
        and ch.content_status='PUBLISHED'
      join numbered_chapters nc on nc.id=ch.id
      join numbered_lessons nl on nl.id=l.id
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join curriculum_program p on p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=p.id
        and cps.subject_id=s.id and cps.class_id=s.class_id
      join institution_membership m on m.student_id=? and m.institution_id=? and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join student_program_enrollment spe on spe.student_id=m.student_id
        and spe.institution_id=m.institution_id and spe.program_id=p.id and spe.status='ACTIVE'
        and spe.course_access_blocked=false
        and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      join institution_course_assignment assignment on assignment.institution_id=m.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=p.id
      left join learning_objective o on o.id=l.objective_id and o.active=true
      left join lateral (
        select tr.title,tr.summary from lesson_translation tr
        where tr.lesson_id=l.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) lte on true
      left join lateral (
        select tr.title,tr.summary from lesson_translation tr
        where tr.lesson_id=l.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('title',l.title,'summary',l.summary)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) lth on true
      left join lateral (
        select tr.display_name from curriculum_chapter_translation tr
        where tr.chapter_id=ch.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) che on true
      left join lateral (
        select tr.display_name from curriculum_chapter_translation tr
        where tr.chapter_id=ch.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) chh on true
      left join lateral (
        select tr.display_name from curriculum_subject_translation tr
        where tr.subject_id=s.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) se on true
      left join lateral (
        select tr.display_name from curriculum_subject_translation tr
        where tr.subject_id=s.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) sh on true
      left join lateral (
        select tr.display_name from curriculum_class_translation tr
        where tr.class_id=c.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',c.display_name)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) ce on true
      left join lateral (
        select tr.display_name from curriculum_class_translation tr
        where tr.class_id=c.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('display_name',c.display_name)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) cuh on true
      left join lateral (
        select tr.title,tr.description from learning_objective_translation tr
        where tr.objective_id=o.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('title',o.title,'description',o.description)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) oe on true
      left join lateral (
        select tr.title,tr.description from learning_objective_translation tr
        where tr.objective_id=o.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object('title',o.title,'description',o.description)::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) oh on true
      where l.id=? and l.active=true and l.status='PUBLISHED'
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
      """, courseId, studentId, institutionId, lessonId);
    if (rows.size() != 1) throw notAvailable();
    Map<String, Object> row = rows.getFirst();
    ObjectNode payload = buildPayload(row, courseId);
    return new LoadedLesson(lessonId, courseId, LESSON_PREFIX + lessonId, sha256(payload), payload);
  }

  public long requireQuestionId(LoadedLesson loaded, String questionId) {
    Long id = parseDatabaseLessonId(questionId, QUESTION_PREFIX);
    if (id == null) throw notAvailable();
    JsonNode questions = loaded.payload().path("practice").path("questions");
    for (JsonNode question : questions) {
      if (questionId.equals(question.path("id").asText())) return id;
    }
    // The payload is restricted to approved questions belonging to this exact lesson.
    throw notAvailable();
  }

  public Map<String, Object> validateStudentQuestionResponse(
      LoadedLesson loaded, String questionId, Object response) {
    long id = requireQuestionId(loaded, questionId);
    JsonNode question = null;
    for (JsonNode candidate : loaded.payload().path("practice").path("questions")) {
      if (questionId.equals(candidate.path("id").asText())) {
        question = candidate;
        break;
      }
    }
    if (question == null) throw notAvailable();
    String type = question.path("type").asText("");
    String legacyInteraction = question.path("legacy_interaction_type").asText("");
    if ("ORDER".equals(legacyInteraction)) {
      if (!(response instanceof List<?> ordered)) {
        throw new IllegalArgumentException("Arrange every item in the required order.");
      }
      Set<String> allowed = new LinkedHashSet<>();
      for (JsonNode option : question.path("options")) allowed.add(option.path("id").asText());
      Set<String> submitted = new LinkedHashSet<>();
      for (Object item : ordered) {
        if (!(item instanceof String key) || !allowed.contains(key) || !submitted.add(key)) {
          throw new IllegalArgumentException("The ordering response contains an unknown or repeated item.");
        }
      }
      if (allowed.size() < 2 || submitted.size() != allowed.size()) {
        throw new IllegalArgumentException("Arrange each item exactly once.");
      }
    } else if ("MATCH".equals(legacyInteraction)) {
      if (!(response instanceof Map<?, ?> pairs)) {
        throw new IllegalArgumentException("Provide a matching choice for each item.");
      }
      Set<String> leftIds = new LinkedHashSet<>();
      Set<String> rightIds = new LinkedHashSet<>();
      for (JsonNode item : question.path("left_items")) leftIds.add(item.path("id").asText());
      for (JsonNode item : question.path("right_items")) rightIds.add(item.path("id").asText());
      Set<String> submittedRight = new LinkedHashSet<>();
      if (leftIds.isEmpty() || rightIds.isEmpty() || pairs.size() != leftIds.size()) {
        throw new IllegalArgumentException("Complete every matching row.");
      }
      for (String left : leftIds) {
        Object value = pairs.get(left);
        if (!(value instanceof String right) || !rightIds.contains(right) || !submittedRight.add(right)) {
          throw new IllegalArgumentException("Choose a valid, unused matching option for every row.");
        }
      }
    } else if ("multiple_choice".equals(type)) {
      if (!(response instanceof String selected) || selected.isBlank()) {
        throw new IllegalArgumentException("Choose an answer option.");
      }
      boolean exists = false;
      for (JsonNode option : question.path("options")) {
        if (selected.equals(option.path("id").asText())) exists = true;
      }
      if (!exists) throw new IllegalArgumentException("Choose a valid answer option.");
    } else if (!(response instanceof String answer) || answer.isBlank() || answer.length() > 10000) {
      throw new IllegalArgumentException("Enter an answer of up to 10,000 characters.");
    }
    return Map.of("questionId", id, "type", type, "response", response);
  }

  @Transactional
  public void saveActivity(
      LoadedLesson loaded, long studentId, long institutionId, String blockId, Object submitted) {
    Long blockRowId = parseDatabaseLessonId(blockId, BLOCK_PREFIX);
    if (blockRowId == null || !(submitted instanceof String raw) || raw.isBlank() || raw.length() > 16000) {
      throw new IllegalArgumentException("The activity response is invalid.");
    }
    boolean allowed = false;
    for (JsonNode block : loaded.payload().path("explain").path("blocks")) {
      if (blockId.equals(block.path("id").asText())
          && ("activity".equals(block.path("type").asText())
              || "interactive".equals(block.path("type").asText()))) {
        allowed = true;
        break;
      }
    }
    if (!allowed) throw notAvailable();
    JsonNode response;
    try {
      response = mapper.readTree(raw);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("The activity response is not valid JSON.", error);
    }
    if (response == null || !response.isObject()) {
      throw new IllegalArgumentException("The activity response must be an object.");
    }
    String serialized;
    try {
      serialized = mapper.writeValueAsString(response);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("The activity response could not be saved.", error);
    }
    jdbc.update("""
      insert into student_structured_activity_response
        (institution_id,student_id,course_id,artifact_id,artifact_sha256,block_id,response,
         attempt_count,first_saved_at,last_saved_at)
      values (?,?,?,?,?,?,?::jsonb,1,now(),now())
      on conflict (institution_id,student_id,course_id,artifact_id,artifact_sha256,block_id)
      do update set response=excluded.response,
        attempt_count=student_structured_activity_response.attempt_count+1,
        last_saved_at=now()
      """, institutionId, studentId, loaded.courseId(), loaded.artifactId(),
        loaded.sha256(), blockId, serialized);
  }

  public Map<String, Object> savedResponses(
      LoadedLesson loaded, long studentId, long institutionId, String language) {
    Map<String, Object> questions = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList("""
      select distinct on (q.id) q.id as question_id,qa.selected_option,qa.answer_text,qa.correct
      from student_question_attempt qa
      join question q on q.id=qa.question_id
      where qa.student_id=? and qa.institution_id=? and qa.course_id=?
        and q.lesson_id=? and qa.activity_kind='LESSON_INLINE'
      order by q.id,qa.answered_at desc,qa.id desc
      """, studentId, institutionId, loaded.courseId(), loaded.lessonId())) {
      String id = QUESTION_PREFIX + row.get("question_id");
      Object answer = row.get("selected_option");
      if (answer == null) answer = row.get("answer_text");
      if (answer == null) continue;
      if (answer instanceof String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
          try { answer = mapper.readTree(trimmed); }
          catch (JacksonException ignored) { /* Keep a genuine free-text answer unchanged. */ }
        }
      }
      Object correctValue = row.get("correct");
      String status = correctValue instanceof Boolean b
          ? (b ? "correct" : "incorrect") : "pending_review";
      Map<String, Object> saved = new LinkedHashMap<>();
      saved.put("response", answer);
      saved.put("status", status);
      saved.put("autoGraded", correctValue instanceof Boolean);
      String feedbackEn = Boolean.TRUE.equals(correctValue)
          ? "Correct. Review the explanation and continue."
          : Boolean.FALSE.equals(correctValue) ? "Not quite. Review the explanation and try again."
          : "Your response was saved for review.";
      String feedbackHi = Boolean.TRUE.equals(correctValue)
          ? "सही। व्याख्या दोबारा देखें और आगे बढ़ें।"
          : Boolean.FALSE.equals(correctValue) ? "अभी सही नहीं है। व्याख्या देखें और फिर प्रयास करें।"
          : "आपका उत्तर जाँच के लिए सुरक्षित किया गया है।";
      saved.put("feedback", "en".equals(language) ? feedbackEn : feedbackHi);
      saved.put("feedbackByLocale", Map.of("en", feedbackEn, "hi", feedbackHi));
      if (correctValue instanceof Boolean b) {
        saved.put("score", b ? 1 : 0);
        saved.put("maxScore", 1);
      }
      questions.put(id, saved);
    }

    Map<String, Object> activities = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList("""
      select block_id,response::text as response
      from student_structured_activity_response
      where institution_id=? and student_id=? and course_id=? and artifact_id=? and artifact_sha256=?
      order by last_saved_at,block_id
      """, institutionId, studentId, loaded.courseId(), loaded.artifactId(), loaded.sha256())) {
      String id = text(row.get("block_id"));
      boolean inPayload = false;
      for (JsonNode block : loaded.payload().path("explain").path("blocks")) {
        if (id.equals(block.path("id").asText())
            && ("activity".equals(block.path("type").asText())
                || "interactive".equals(block.path("type").asText()))) {
          inPayload = true;
          break;
        }
      }
      if (!inPayload) continue;
      try {
        activities.put(id, mapper.readTree(text(row.get("response"))));
      } catch (JacksonException ignored) {
        // Malformed legacy response rows remain stored but are not exposed to the learner.
      }
    }
    return Map.of("questions", questions, "activities", activities);
  }

  private ObjectNode buildPayload(Map<String, Object> row, long courseId) {
    ObjectNode root = mapper.createObjectNode();
    root.put("schema_version", "quantaedge.structured-lesson.v1");
    root.put("artifact_type", "relational_lesson");
    root.put("artifact_id", LESSON_PREFIX + row.get("lesson_id"));
    root.put("estimated_minutes", number(row.get("estimated_minutes")));

    ObjectNode workflow = root.putObject("workflow");
    workflow.put("status", "PUBLISHED");
    workflow.put("publication_allowed", true);
    workflow.put("source", "RELATIONAL_DATABASE");
    workflow.put("source_lesson_id", number(row.get("lesson_id")));

    ObjectNode curriculum = root.putObject("curriculum");
    int classNumber = classNumber(text(row.get("class_code")));
    curriculum.put("class", classNumber);
    curriculum.put("course_id", courseId);
    curriculum.put("subject", text(row.get("subject_code")));
    curriculum.set("subject_by_locale", bilingual(text(row.get("subject_name_en")), text(row.get("subject_name_hi"))));
    curriculum.set("class_by_locale", bilingual(text(row.get("class_name_en")), text(row.get("class_name_hi"))));

    ObjectNode chapter = root.putObject("chapter");
    chapter.put("chapter_number", number(row.get("chapter_number")));
    chapter.put("chapter_code", text(row.get("chapter_code")));
    chapter.set("title_by_locale", bilingual(text(row.get("chapter_title_en")), text(row.get("chapter_title_hi"))));

    ObjectNode topic = root.putObject("topic");
    topic.put("topic_number", number(row.get("topic_number")));
    topic.put("topic_id", text(row.get("lesson_code")));
    topic.set("title_by_locale", bilingual(text(row.get("title_en")), text(row.get("title_hi"))));

    ArrayNode objectives = root.putArray("learning_objectives");
    if (row.get("objective_id") instanceof Number objectiveId) {
      ObjectNode objective = objectives.addObject();
      objective.put("id", "db-objective-" + objectiveId.longValue());
      objective.set("statement_by_locale", bilingual(
          nonBlank(text(row.get("objective_title_en")), text(row.get("objective_description_en")),
              text(row.get("objective_title"))),
          nonBlank(text(row.get("objective_title_hi")), text(row.get("objective_description_hi")),
              text(row.get("objective_title")))));
    }

    ObjectNode explain = root.putObject("explain");
    explain.set("title_by_locale", bilingual(text(row.get("title_en")), text(row.get("title_hi"))));
    explain.set("blocks", blocks(number(row.get("lesson_id"))));

    ObjectNode practice = root.putObject("practice");
    practice.set("title_by_locale", bilingual("Practice", "अब खुद करें"));
    practice.set("questions", questions(number(row.get("lesson_id"))));
    practice.put("question_count", practice.path("questions").size());
    return root;
  }

  private ArrayNode blocks(long lessonId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select b.id,b.sequence_no,b.block_type,
             coalesce(bte.content->'content',bte.content,b.content)::text as content_en,
             coalesce(bth.content->'content',bth.content,b.content)::text as content_hi,
             a.url as asset_url,a.title as asset_title,a.alt_text as asset_alt,a.asset_type,a.status as asset_status
      from lesson_block b
      left join content_asset a on a.id=b.asset_id and a.status='PUBLISHED'
      left join lateral (
        select tr.content from lesson_block_translation tr
        where tr.block_id=b.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(b.content::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) bte on true
      left join lateral (
        select tr.content from lesson_block_translation tr
        where tr.block_id=b.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(b.content::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) bth on true
      where b.lesson_id=? and b.active=true
      order by b.sequence_no,b.id
      """, lessonId);
    ArrayNode result = mapper.createArrayNode();
    for (Map<String, Object> row : rows) {
      String type = text(row.get("block_type")).toUpperCase(Locale.ROOT);
      JsonNode en = readJson(text(row.get("content_en")));
      JsonNode hi = readJson(text(row.get("content_hi")));
      String id = BLOCK_PREFIX + number(row.get("id"));
      if ("IMAGE".equals(type)) {
        String url = text(row.get("asset_url"));
        String altEn = nonBlank(text(en.path("alt").asText("")), text(row.get("asset_alt")));
        String altHi = nonBlank(text(hi.path("alt").asText("")), text(row.get("asset_alt")));
        String visibleAltEn = nonBlank(altEn, "Visual awaiting review");
        String visibleAltHi = nonBlank(altHi, "चित्र समीक्षा की प्रतीक्षा में");
        boolean renderable = url.startsWith("https://") && !altEn.isBlank() && !altHi.isBlank();
        ObjectNode block = result.addObject();
        block.put("id", id);
        block.put("type", "image");
        block.put("order", number(row.get("sequence_no")));
        block.set("alt_by_locale", bilingual(visibleAltEn, visibleAltHi));
        ObjectNode asset = block.putObject("asset");
        if (renderable) asset.put("url", url);
        asset.put("title", nonBlank(text(row.get("asset_title")), text(en.path("title").asText("")), "Learning visual"));
        asset.put("publication_allowed", renderable);
        asset.put("student_renderable", renderable);
        asset.put("review_status", renderable ? "APPROVED" : "REVIEW_PENDING");
        asset.set("alt_by_locale", bilingual(visibleAltEn, visibleAltHi));
        String captionEnglish = nonBlank(text(en.path("caption").asText("")),
            text(en.path("description").asText("")));
        String captionHindi = nonBlank(text(hi.path("caption").asText("")),
            text(hi.path("description").asText("")));
        asset.set("caption_by_locale", bilingual(captionEnglish, captionHindi));
        block.set("caption_by_locale", bilingual(captionEnglish, captionHindi));
        continue;
      }
      if ("DIAGRAM".equals(type)) {
        JsonNode dataEn = en.path("diagram_data").isObject() ? en.path("diagram_data") : en;
        JsonNode dataHi = hi.path("diagram_data").isObject() ? hi.path("diagram_data") : hi;
        String review = nonBlank(text(dataEn.path("review_status").asText("")),
            text(en.path("review_status").asText("")));
        boolean reviewApproved = dataEn.path("student_renderable").asBoolean(false)
            && ("APPROVED".equalsIgnoreCase(review)
                || "APPROVED_FOR_STUDENT_RENDERING".equalsIgnoreCase(review));
        String altEn = nonBlank(text(en.path("alt").asText("")), text(dataEn.path("alt").asText("")));
        String altHi = nonBlank(text(hi.path("alt").asText("")), text(dataHi.path("alt").asText("")));
        boolean diagramReady = reviewApproved && !altEn.isBlank() && !altHi.isBlank()
            && dataEn.path("nodes").isArray() && !dataEn.path("nodes").isEmpty();
        String visibleAltEn = nonBlank(altEn, "Diagram awaiting review");
        String visibleAltHi = nonBlank(altHi, "आरेख समीक्षा की प्रतीक्षा में");
        ObjectNode block = result.addObject();
        block.put("id", id);
        block.put("type", "diagram");
        block.put("order", number(row.get("sequence_no")));
        block.put("student_renderable", diagramReady);
        block.put("review_status", diagramReady ? review : "REVIEW_PENDING");
        block.set("alt_by_locale", bilingual(visibleAltEn, visibleAltHi));
        if (diagramReady) {
          block.set("diagram_data", stripPrivateFields(dataEn).deepCopy());
        } else {
          ObjectNode placeholder = block.putObject("diagram_data");
          placeholder.put("student_renderable", false);
          placeholder.put("review_status", "REVIEW_PENDING");
        }
        continue;
      }
      if ("SUMMARY".equals(type)) {
        ObjectNode block = result.addObject();
        block.put("id", id);
        block.put("type", "recap");
        block.put("order", number(row.get("sequence_no")));
        ObjectNode localizedContent = block.putObject("content_by_locale");
        localizedContent.set("en", recapContent(en));
        localizedContent.set("hi", recapContent(hi));
        continue;
      }
      if (List.of("CHALLENGE","INPUT","MATCH","ORDER","QUESTION","MCQ","TRUE_FALSE").contains(type)) {
        ObjectNode block = result.addObject();
        block.put("id", id);
        block.put("type", "activity");
        block.put("order", number(row.get("sequence_no")));
        String instructionsEnglish = nonBlank(
            text(en.path("prompt").asText("")), text(en.path("instructions").asText("")),
            text(en.path("title").asText("")), text(en.path("body").asText("")),
            "Activity content is available in this lesson.");
        String instructionsHindi = nonBlank(
            text(hi.path("prompt").asText("")), text(hi.path("instructions").asText("")),
            text(hi.path("title").asText("")), text(hi.path("body").asText("")),
            "इस पाठ में गतिविधि-सामग्री उपलब्ध है।");
        block.set("instructions_by_locale", bilingual(instructionsEnglish, instructionsHindi));
        block.set("configuration", stripPrivateFields(en).deepCopy());
        continue;
      }

      // Preserve any remaining block kind as learner-safe content; never skip a row
      // simply because the specialized renderer does not recognize its type.
      String fallbackLabel = type.replace('_', ' ').toLowerCase(Locale.ROOT);
      String headingEn = nonBlank(text(en.path("heading").asText("")), text(en.path("title").asText("")), fallbackLabel);
      String headingHi = nonBlank(text(hi.path("heading").asText("")), text(hi.path("title").asText("")), "पाठ सामग्री");
      List<String> paragraphEn = narrative(en);
      List<String> paragraphHi = narrative(hi);
      if (paragraphEn.isEmpty()) paragraphEn = fallbackNarrative(en);
      if (paragraphHi.isEmpty()) paragraphHi = fallbackNarrative(hi);
      ObjectNode block = result.addObject();
      block.put("id", id);
      block.put("type", "rich_text");
      block.put("order", number(row.get("sequence_no")));
      ObjectNode localizedText = block.putObject("text_by_locale");
      localizedText.set("en", richText(headingEn, paragraphEn));
      localizedText.set("hi", richText(headingHi, paragraphHi));

      String mediaUrlEn = nonBlank(text(en.path("url").asText("")), text(row.get("asset_url")));
      String mediaUrlHi = nonBlank(text(hi.path("url").asText("")), text(row.get("asset_url")), mediaUrlEn);
      if (List.of("VIDEO", "AUDIO", "ANIMATION").contains(type)) {
        ObjectNode mediaByLocale = block.putObject("media_by_locale");
        putMediaInfo(mediaByLocale.putObject("en"), type, mediaUrlEn, headingEn, en);
        putMediaInfo(mediaByLocale.putObject("hi"), type, mediaUrlHi, headingHi, hi);
      } else {
        ObjectNode resourceUrls = block.putObject("resource_url_by_locale");
        resourceUrls.put("en", mediaUrlEn.startsWith("https://") ? mediaUrlEn : "");
        resourceUrls.put("hi", mediaUrlHi.startsWith("https://") ? mediaUrlHi : "");
      }
    }
    return result;
  }

  ArrayNode questions(long lessonId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select q.id,q.question_type,q.prompt,q.explanation,q.review_status,q.answer_payload::text as answer_payload,
             coalesce(qte.prompt,q.prompt) as prompt_en,
             coalesce(qth.prompt,q.prompt) as prompt_hi,
             coalesce(qte.explanation,q.explanation,'') as explanation_en,
             coalesce(qth.explanation,q.explanation,'') as explanation_hi,
             case when qte.options is not null and qte.options<>'[]'::jsonb then qte.options::text
                  else base.options_json::text end as options_en,
             case when qth.options is not null and qth.options<>'[]'::jsonb then qth.options::text
                  else base.options_json::text end as options_hi
      from question q
      left join lateral (
        select tr.prompt,tr.explanation,tr.options from question_translation tr
        where tr.question_id=q.id and tr.language='en' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object(
            'prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
              order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags
          )::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) qte on true
      left join lateral (
        select tr.prompt,tr.explanation,tr.options from question_translation tr
        where tr.question_id=q.id and tr.language='hi' and tr.translation_status='PUBLISHED'
          and tr.source_hash::text=md5(jsonb_build_object(
            'prompt',q.prompt,'explanation',q.explanation,
            'options',coalesce((select jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
              order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb),
            'topic',q.topic,'subtopic',q.subtopic,'skill',q.skill,'tags',q.tags
          )::text)
        order by tr.version_no desc,tr.id desc limit 1
      ) qth on true
      cross join lateral (
        select coalesce(jsonb_agg(jsonb_build_object('key',qo.option_key,'label',qo.label)
          order by qo.sort_order),'[]'::jsonb) as options_json
        from question_option qo where qo.question_id=q.id
      ) base
      where q.lesson_id=? and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      order by q.sort_order,q.id
      """, lessonId);
    ArrayNode result = mapper.createArrayNode();
    for (Map<String, Object> row : rows) {
      String rawType = text(row.get("question_type")).toUpperCase(Locale.ROOT);
      Map<String, String> labelsEn = optionLabels(text(row.get("options_en")));
      Map<String, String> labelsHi = optionLabels(text(row.get("options_hi")));
      JsonNode answerPayload = readJson(text(row.get("answer_payload")));
      JsonNode answerValue = answerPayload.path("value");
      ArrayNode options = mapper.createArrayNode();
      ArrayNode leftItems = mapper.createArrayNode();
      ArrayNode rightItems = mapper.createArrayNode();
      String structuredType = "short_answer";
      String renderingContract = "qtype-short-answer.v1";
      String legacyInteraction = "";

      if ("MCQ".equals(rawType) || "TRUE_FALSE".equals(rawType)) {
        for (String key : labelsEn.keySet()) {
          ObjectNode option = options.addObject();
          option.put("id", key);
          option.set("text_by_locale", bilingual(labelsEn.get(key), labelsHi.getOrDefault(key, labelsEn.get(key))));
        }
        if (options.size() >= 2) {
          structuredType = "multiple_choice";
          renderingContract = "qtype-multiple-choice.v1";
        }
      } else if ("INPUT".equals(rawType) || "NUMERICAL".equals(rawType) || "NUMERIC".equals(rawType)) {
        structuredType = "fill_blank";
        renderingContract = "qtype-fill-blank.v1";
      } else if ("ORDER".equals(rawType)) {
        Set<String> orderIds = new TreeSet<>();
        if (answerValue.isArray()) {
          for (JsonNode item : answerValue) if (!item.asText("").isBlank()) orderIds.add(item.asText());
        }
        if (orderIds.isEmpty()) orderIds.addAll(labelsEn.keySet());
        // Expose only the item set, sorted independently from the expected sequence.
        for (String key : orderIds) {
          ObjectNode option = options.addObject();
          option.put("id", key);
          option.set("text_by_locale", bilingual(labelsEn.getOrDefault(key, key), labelsHi.getOrDefault(key, labelsEn.getOrDefault(key, key))));
        }
        if (options.size() >= 2) legacyInteraction = "ORDER";
      } else if ("MATCH".equals(rawType) && answerValue.isObject()) {
        Set<String> leftIds = new TreeSet<>();
        Set<String> rightIds = new TreeSet<>();
        var pairs = answerValue.properties().iterator();
        while (pairs.hasNext()) {
          var pair = pairs.next();
          String left = pair.getKey();
          String right = pair.getValue().asText("");
          if (!left.isBlank() && !right.isBlank()) {
            leftIds.add(left);
            rightIds.add(right);
          }
        }
        for (String key : leftIds) {
          ObjectNode item = leftItems.addObject();
          item.put("id", key);
          item.set("label_by_locale", bilingual(labelsEn.getOrDefault(key, key), labelsHi.getOrDefault(key, labelsEn.getOrDefault(key, key))));
        }
        for (String key : rightIds) {
          ObjectNode item = rightItems.addObject();
          item.put("id", key);
          item.set("label_by_locale", bilingual(labelsEn.getOrDefault(key, key), labelsHi.getOrDefault(key, labelsEn.getOrDefault(key, key))));
        }
        if (!leftIds.isEmpty() && !rightIds.isEmpty()) {
          structuredType = "matching";
          renderingContract = "qtype-matching.v1";
          legacyInteraction = "MATCH";
        }
      }

      ObjectNode question = result.addObject();
      question.put("id", QUESTION_PREFIX + number(row.get("id")));
      question.put("type", structuredType);
      question.put("rendering_contract_id", renderingContract);
      question.put("legacy_question_type", rawType);
      question.set("legacy_question_type_by_locale", bilingual(
          legacyQuestionTypeLabel(rawType, "en"), legacyQuestionTypeLabel(rawType, "hi")));
      if (!legacyInteraction.isBlank()) question.put("legacy_interaction_type", legacyInteraction);
      question.set("prompt_by_locale", bilingual(text(row.get("prompt_en")), text(row.get("prompt_hi"))));
      if (!options.isEmpty()) question.set("options", options);
      if ("matching".equals(structuredType)) {
        question.set("left_items", leftItems);
        question.set("right_items", rightItems);
      }
      // Deliberately omit answer_payload, correct answers, grading keys and rubric fields.
    }
    return result;
  }

  private String legacyQuestionTypeLabel(String rawType, String language) {
    boolean hi = "hi".equals(language);
    return switch (rawType) {
      case "MCQ" -> hi ? "बहुविकल्पीय प्रश्न" : "Multiple-choice question";
      case "TRUE_FALSE" -> hi ? "सही या गलत" : "True or false";
      case "INPUT" -> hi ? "उत्तर लिखें" : "Input answer";
      case "NUMERICAL", "NUMERIC" -> hi ? "संख्यात्मक प्रश्न" : "Numerical question";
      case "MATCH" -> hi ? "मिलान करें" : "Matching question";
      case "ORDER" -> hi ? "सही क्रम में रखें" : "Ordering question";
      case "ASSERTION_REASON" -> hi ? "कथन और कारण" : "Assertion and reason";
      case "CASE_BASED" -> hi ? "प्रकरण आधारित प्रश्न" : "Case-based question";
      case "SHORT_ANSWER" -> hi ? "लघु उत्तरीय प्रश्न" : "Short-answer question";
      case "LONG_ANSWER" -> hi ? "दीर्घ उत्तरीय प्रश्न" : "Long-answer question";
      case "DIAGRAM" -> hi ? "आरेख आधारित प्रश्न" : "Diagram question";
      case "MAP" -> hi ? "मानचित्र आधारित प्रश्न" : "Map question";
      case "SOURCE_BASED" -> hi ? "स्रोत आधारित प्रश्न" : "Source-based question";
      default -> hi ? "प्रश्न" : "Question";
    };
  }

  private ObjectNode bilingual(String en, String hi) {
    ObjectNode node = mapper.createObjectNode();
    node.put("en", nonBlank(en, hi, "Content unavailable"));
    node.put("hi", nonBlank(hi, en, "सामग्री उपलब्ध नहीं है"));
    return node;
  }

  private ObjectNode richText(String heading, List<String> paragraphs) {
    ObjectNode node = mapper.createObjectNode();
    if (!heading.isBlank()) node.put("heading", heading);
    ArrayNode array = node.putArray("paragraphs");
    for (String paragraph : paragraphs) if (paragraph != null && !paragraph.isBlank()) array.add(paragraph);
    if (heading.isBlank() && array.isEmpty()) node.putArray("paragraphs").add(" ");
    return node;
  }

  private JsonNode recapContent(JsonNode source) {
    ObjectNode node = mapper.createObjectNode();
    ArrayNode points = node.putArray("points");
    List<String> values = narrative(source);
    for (String value : values) if (!value.isBlank()) points.add(value);
    if (points.isEmpty()) {
      String value = nonBlank(text(source.path("summary").asText("")),
          text(source.path("body").asText("")), text(source.path("title").asText("")));
      if (!value.isBlank()) points.add(value);
    }
    return node;
  }

  private List<String> fallbackNarrative(JsonNode source) {
    List<String> values = new ArrayList<>();
    flattenDisplayText(source, "", values, 0);
    return values;
  }

  private void flattenDisplayText(JsonNode node, String label, List<String> target, int depth) {
    if (node == null || node.isNull() || depth > 5) return;
    if (node.isTextual() || node.isNumber() || node.isBoolean()) {
      String value = node.asText("").trim();
      if (!value.isBlank() && !value.startsWith("https://")) target.add(label.isBlank() ? value : label + ": " + value);
      return;
    }
    if (node.isArray()) {
      for (JsonNode item : node) flattenDisplayText(item, label, target, depth + 1);
      return;
    }
    if (!node.isObject()) return;
    node.properties().forEach(entry -> {
      String key = entry.getKey();
      String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
      if (List.of("id", "type", "order", "sequenceno", "url", "href", "src", "asseturl",
          "resourceurl", "mediaurl", "sourcepageurl", "html", "answer", "answers",
          "correctanswer", "correctanswerids", "correctpairs", "acceptedanswers", "grading",
          "solution", "modelanswer", "expectedanswer", "answerguidebylocale").contains(normalized)) return;
      String nextLabel = label.isBlank() ? key.replace('_', ' ') : label + " · " + key.replace('_', ' ');
      flattenDisplayText(entry.getValue(), nextLabel, target, depth + 1);
    });
  }

  private void putMediaInfo(ObjectNode target, String type, String rawUrl, String title, JsonNode source) {
    target.put("type", type.toLowerCase(Locale.ROOT));
    if (rawUrl != null && rawUrl.startsWith("https://")) target.put("url", rawUrl);
    target.put("title", nonBlank(text(source.path("title").asText("")), title));
    target.put("description", nonBlank(text(source.path("description").asText("")), text(source.path("body").asText(""))));
    target.put("caption", nonBlank(text(source.path("caption").asText(""))));
    target.put("alt", nonBlank(text(source.path("alt").asText(""))));
  }

  private List<String> narrative(JsonNode source) {
    List<String> values = new ArrayList<>();
    for (String key : List.of("body","text","content","description","prompt","explanation","hint","details","steps","points","keyPoints","caption","instructions")) {
      JsonNode value = source.path(key);
      if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText().trim());
      else if (value.isArray()) for (JsonNode item : value) {
        if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText().trim());
      }
      else if (value.isObject()) flattenObject(value, key, values);
    }
    JsonNode example = source.path("workedExample");
    if (example.isObject()) flattenObject(example, "Worked example", values);
    return values;
  }

  private void flattenObject(JsonNode node, String prefix, List<String> target) {
    node.properties().forEach(entry -> {
      JsonNode value = entry.getValue();
      if (value.isValueNode() && !value.isNull()) {
        String text = value.asText("").trim();
        if (!text.isBlank()) target.add(prefix + " — " + entry.getKey() + ": " + text);
      } else if (value.isArray()) {
        for (JsonNode item : value) {
          if (item.isValueNode() && !item.isNull() && !item.asText("").isBlank()) {
            target.add(prefix + " — " + item.asText().trim());
          }
        }
      }
    });
  }

  private JsonNode stripPrivateFields(JsonNode value) {
    if (value == null || value.isNull()) return mapper.createObjectNode();
    if (value.isArray()) {
      ArrayNode result = mapper.createArrayNode();
      for (JsonNode child : value) result.add(stripPrivateFields(child));
      return result;
    }
    if (!value.isObject()) return value.deepCopy();
    ObjectNode result = mapper.createObjectNode();
    value.properties().forEach(entry -> {
      String key = entry.getKey();
      String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
      if (List.of("answerpayload","correctanswer","correctanswerids","correctpairs","acceptedanswers",
          "answerguidebylocale","grading","iscorrect","correctoption","answerkey","solutionkey",
          "modelanswer","expectedanswer","explanationkey").contains(normalized)) return;
      result.set(key, stripPrivateFields(entry.getValue()));
    });
    return result;
  }

  private JsonNode readJson(String raw) {
    if (raw == null || raw.isBlank()) return mapper.createObjectNode();
    try {
      return mapper.readTree(raw);
    } catch (JacksonException ignored) {
      return mapper.createObjectNode();
    }
  }

  private Map<String, String> optionLabels(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    JsonNode parsed = readJson(raw);
    if (!parsed.isArray()) return result;
    for (JsonNode option : parsed) {
      String key = option.path("key").asText(option.path("option_key").asText(""));
      String label = option.path("label").asText(option.path("text").asText(""));
      if (!key.isBlank() && !label.isBlank()) result.put(key, label);
    }
    return result;
  }

  private String sha256(ObjectNode payload) {
    try {
      byte[] bytes = mapper.writeValueAsBytes(payload);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(64);
      for (byte item : digest) result.append(String.format("%02x", item));
      return result.toString();
    } catch (JacksonException | NoSuchAlgorithmException error) {
      throw new IllegalStateException("The published lesson could not be fingerprinted.", error);
    }
  }

  private ResponseStatusException notAvailable() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "This lesson is not available in the selected course.");
  }

  private int classNumber(String value) {
    String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
    try {
      int result = Integer.parseInt(digits);
      if (result > 0 && result <= 12) return result;
    } catch (NumberFormatException ignored) {
      // A course without a class number is not valid for this lesson adapter.
    }
    throw notAvailable();
  }

  private int number(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String nonBlank(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value.trim();
    return "";
  }

  private String localized(Map<String, Object> row, String language, String prefix) {
    return "en".equals(language)
        ? nonBlank(text(row.get(prefix + "_en")), text(row.get(prefix + "_hi")))
        : nonBlank(text(row.get(prefix + "_hi")), text(row.get(prefix + "_en")));
  }
}
