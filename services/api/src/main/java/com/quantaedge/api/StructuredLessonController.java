package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/learning/structured-lessons")
public class StructuredLessonController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StructuredLessonArtifactService artifacts;
  private final ObjectMapper mapper;
  private final RelationalStructuredLessonService relationalLessons;
  private final LearningController learningController;

  public StructuredLessonController(JdbcTemplate jdbc, AuthorizationService authorization,
      StructuredLessonArtifactService artifacts, ObjectMapper mapper,
      RelationalStructuredLessonService relationalLessons, LearningController learningController) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.artifacts = artifacts;
    this.mapper = mapper;
    this.relationalLessons = relationalLessons;
    this.learningController = learningController;
  }

  /** Lists only published structured lessons mapped to the learner's selected institution course and optional subject. */
  @GetMapping
  public List<Map<String, Object>> catalogue(
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam(required = false) String subjectCode,
      @RequestParam String courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    long resolvedCourseId = resolveCourseId(courseId);
    ensureStudentEnrolledCourse(context.studentId(),resolvedCourseId,institutionId);
    String locale = normalizeLanguage(language);
    String requestedSubject = subjectCode == null ? "" : subjectCode.trim();
    List<Map<String, Object>> result = new ArrayList<>();
    // Published relational lessons reuse the canonical DB curriculum and share the same
    // API contract with packaged structured artifacts. Draft DB rows remain excluded.
    result.addAll(relationalLessons.catalogue(
        context.studentId(), institutionId, resolvedCourseId, locale, requestedSubject));
    for (StructuredLessonArtifactService.PublishedArtifact artifact : artifacts.listPublishedArtifacts()) {
      if (!requestedSubject.isBlank()
          && !requestedSubject.equalsIgnoreCase(artifacts.subjectCode(artifact))) continue;
      if (!studentHasCourseTrackAccess(context.studentId(),resolvedCourseId,artifact,institutionId)) continue;
      JsonNode source = artifact.source();
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("artifactId", artifact.artifactId());
      item.put("subjectCode", artifacts.subjectCode(artifact));
      item.put("chapterNumber", source.path("chapter").path("chapter_number").asInt(0));
      item.put("chapterTitle", localizedText(source.path("chapter").path("title_by_locale"), locale));
      item.put("topicId", source.path("topic").path("topic_id").asText(""));
      item.put("topicNumber", source.path("topic").path("topic_number").asInt(0));
      item.put("title", localizedText(source.path("topic").path("title_by_locale"), locale));
      item.put("estimatedMinutes", source.path("estimated_minutes").asInt(
          source.path("topic").path("estimated_minutes").asInt(0)));
      result.add(item);
    }
    return List.copyOf(result);
  }

  /** Authenticated learner delivery. Drafts and unreviewed artifacts deliberately return 404. */
  @GetMapping("/{artifactId}")
  public ObjectNode lesson(@PathVariable String artifactId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam String courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    String locale = normalizeLanguage(language);
    long resolvedCourseId = resolveCourseId(courseId);
    if (RelationalStructuredLessonService.isDatabaseLessonId(artifactId)) {
      RelationalStructuredLessonService.LoadedLesson lesson = relationalLessons.requirePublishedLesson(
          artifactId, context.studentId(), institutionId, resolvedCourseId);
      // Opening a relational structured lesson is a real lesson start. Reuse the canonical
      // lesson-progress path and stamp it with the exact institution/course already validated
      // by the adapter; do not create a second structured-only progress ledger.
      learningController.start(lesson.lessonId(), resolvedCourseId, context, tenant);
      ObjectNode payload = lesson.payload().deepCopy();
      payload.put("requested_language", locale);
      return payload;
    }
    StructuredLessonArtifactService.PublishedArtifact artifact = artifacts.requirePublished(artifactId);
    ensureStudentCourseTrackAccess(context.studentId(),resolvedCourseId,artifact,institutionId);
    ObjectNode payload = artifacts.toStudentPayload(artifact);
    payload.put("requested_language", locale);
    return payload;
  }

  /**
   * Stable string question IDs remain separate from numeric legacy DB question IDs.
   * Answers are stored server-side; short answers receive pending-review status.
   */
  @PostMapping("/{artifactId}/questions/{questionId}/responses")
  @Transactional
  public Map<String, Object> submitResponse(@PathVariable String artifactId,
      @PathVariable String questionId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam String courseId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    String locale = normalizeLanguage(language);
    if (body == null || !body.containsKey("response")) {
      throw new IllegalArgumentException("A response is required.");
    }

    long resolvedCourseId = resolveCourseId(courseId);
    if (RelationalStructuredLessonService.isDatabaseLessonId(artifactId)) {
      RelationalStructuredLessonService.LoadedLesson lesson = relationalLessons.requirePublishedLesson(
          artifactId, context.studentId(), institutionId, resolvedCourseId);
      Map<String, Object> validated = relationalLessons.validateStudentQuestionResponse(
          lesson, questionId, body.get("response"));
      @SuppressWarnings("unchecked")
      Map<String, Object> saved = learningController.answer(
          ((Number) validated.get("questionId")).longValue(), resolvedCourseId, locale,
          Map.of("answer", validated.get("response")), context, tenant);
      Boolean correct = saved.get("correct") instanceof Boolean value ? value : null;
      boolean autoGraded = Boolean.TRUE.equals(saved.get("autoGraded"));
      String status = !autoGraded || correct == null ? "pending_review" : (correct ? "correct" : "incorrect");
      String feedbackEn = autoGraded
          ? (Boolean.TRUE.equals(correct) ? "Correct. Review the explanation and continue." : "Not quite. Review the explanation and try again.")
          : "Your response was saved for review.";
      String feedbackHi = autoGraded
          ? (Boolean.TRUE.equals(correct) ? "सही। व्याख्या दोबारा देखें और आगे बढ़ें।" : "अभी सही नहीं है। व्याख्या देखें और फिर प्रयास करें।")
          : "आपका उत्तर जाँच के लिए सुरक्षित किया गया है।";
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("questionId", questionId);
      result.put("courseId", courseId);
      result.put("saved", true);
      result.put("autoGraded", autoGraded);
      result.put("status", status);
      if (autoGraded) {
        result.put("score", Boolean.TRUE.equals(correct) ? 1 : 0);
        result.put("maxScore", 1);
      }
      result.put("feedback", "en".equals(locale) ? feedbackEn : feedbackHi);
      result.put("feedbackByLocale", Map.of("en", feedbackEn, "hi", feedbackHi));
      return result;
    }
    StructuredLessonArtifactService.PublishedArtifact artifact = artifacts.requirePublished(artifactId);
    ensureStudentCourseTrackAccess(context.studentId(),resolvedCourseId,artifact,institutionId);
    JsonNode question = artifacts.questionForStudent(artifact, questionId);
    JsonNode response = artifacts.validateResponse(question, body.get("response"));
    StructuredLessonArtifactService.Evaluation evaluation = artifacts.evaluateResponse(question, response, locale);
    String serialized;
    try {
      serialized = mapper.writeValueAsString(response);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("The response could not be saved.", error);
    }

    jdbc.update("""
      insert into student_structured_question_response
        (institution_id,student_id,course_id,artifact_id,artifact_sha256,question_id,response,
         result_status, score, max_score, attempt_count, first_submitted_at, last_submitted_at)
      values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 1, now(), now())
      on conflict (institution_id,student_id,course_id,artifact_id,artifact_sha256,question_id)
      do update set response=excluded.response,
        result_status=excluded.result_status, score=excluded.score, max_score=excluded.max_score,
        attempt_count=student_structured_question_response.attempt_count+1,
        last_submitted_at=now()
      """,
      institutionId,context.studentId(),resolvedCourseId,artifact.artifactId(),artifact.sha256(),questionId,serialized,
      evaluation.status().toUpperCase(Locale.ROOT), evaluation.score(), evaluation.maxScore());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("questionId", questionId);
    result.put("courseId", courseId);
    result.put("saved", true);
    result.put("autoGraded", evaluation.autoGraded());
    result.put("status", evaluation.status());
    if (evaluation.score() != null) result.put("score", evaluation.score());
    if (evaluation.maxScore() != null) result.put("maxScore", evaluation.maxScore());
    result.put("feedback", evaluation.feedback());
    StructuredLessonArtifactService.Evaluation english = artifacts.evaluateResponse(question, response, "en");
    StructuredLessonArtifactService.Evaluation hindi = artifacts.evaluateResponse(question, response, "hi");
    result.put("feedbackByLocale", Map.of("en", english.feedback(), "hi", hindi.feedback()));
    return result;
  }

  /** Restore response and activity state by stable IDs, not translated labels or array order. */
  @GetMapping("/{artifactId}/responses")
  public Map<String, Object> savedResponses(@PathVariable String artifactId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam String courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    String locale = normalizeLanguage(language);
    long resolvedCourseId = resolveCourseId(courseId);
    if (RelationalStructuredLessonService.isDatabaseLessonId(artifactId)) {
      RelationalStructuredLessonService.LoadedLesson lesson = relationalLessons.requirePublishedLesson(
          artifactId, context.studentId(), institutionId, resolvedCourseId);
      return relationalLessons.savedResponses(lesson, context.studentId(), institutionId, locale);
    }
    StructuredLessonArtifactService.PublishedArtifact artifact = artifacts.requirePublished(artifactId);
    ensureStudentCourseTrackAccess(context.studentId(),resolvedCourseId,artifact,institutionId);
    Map<String, Object> questions = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList("""
      select question_id, response::text as response, result_status, score, max_score
      from student_structured_question_response
      where institution_id=? and student_id=? and course_id=? and artifact_id=? and artifact_sha256=?
      order by last_submitted_at, question_id
      """,institutionId,context.studentId(),resolvedCourseId,artifact.artifactId(),artifact.sha256())) {
      String id = String.valueOf(row.get("question_id"));
      try {
        JsonNode question = artifacts.questionForStudent(artifact, id);
        JsonNode response = mapper.readTree(String.valueOf(row.get("response")));
        var english = artifacts.evaluateResponse(question, response, "en");
        var hindi = artifacts.evaluateResponse(question, response, "hi");
        String state = String.valueOf(row.get("result_status")).toLowerCase(Locale.ROOT);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("response", response);
        value.put("status", state);
        value.put("autoGraded", !"pending_review".equals(state));
        value.put("feedback", "en".equals(locale) ? english.feedback() : hindi.feedback());
        value.put("feedbackByLocale", Map.of("en", english.feedback(), "hi", hindi.feedback()));
        if (row.get("score") instanceof Number score) value.put("score", score.intValue());
        if (row.get("max_score") instanceof Number max) value.put("maxScore", max.intValue());
        questions.put(id, value);
      } catch (Exception ignored) {
        // Removed or now-unapproved visual questions must not be restored to a learner.
      }
    }
    Map<String, Object> activities = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbc.queryForList("""
      select block_id, response::text as response
      from student_structured_activity_response
      where institution_id=? and student_id=? and course_id=? and artifact_id=? and artifact_sha256=?
      order by last_saved_at, block_id
      """,institutionId,context.studentId(),resolvedCourseId,artifact.artifactId(),artifact.sha256())) {
      String id = String.valueOf(row.get("block_id"));
      try {
        artifacts.activityBlockForStudent(artifact, id);
        activities.put(id, mapper.readTree(String.valueOf(row.get("response"))));
      } catch (Exception ignored) {
        // Exclude activity state for content which is no longer learner-visible.
      }
    }
    return Map.of("questions", questions, "activities", activities);
  }

  /** Persists activity response state without inventing an objective score. */
  @PostMapping("/{artifactId}/activities/{blockId}/responses")
  @Transactional
  public Map<String, Object> saveActivityResponse(@PathVariable String artifactId,
      @PathVariable String blockId, @RequestParam String courseId, @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    if (body == null || !body.containsKey("response")) {
      throw new IllegalArgumentException("An activity response is required.");
    }
    long resolvedCourseId = resolveCourseId(courseId);
    if (RelationalStructuredLessonService.isDatabaseLessonId(artifactId)) {
      RelationalStructuredLessonService.LoadedLesson lesson = relationalLessons.requirePublishedLesson(
          artifactId, context.studentId(), institutionId, resolvedCourseId);
      relationalLessons.saveActivity(lesson, context.studentId(), institutionId, blockId, body.get("response"));
      return Map.of("blockId", blockId, "saved", true, "courseId", courseId);
    }
    StructuredLessonArtifactService.PublishedArtifact artifact = artifacts.requirePublished(artifactId);
    ensureStudentCourseTrackAccess(context.studentId(),resolvedCourseId,artifact,institutionId);
    JsonNode response = artifacts.validateActivityResponse(artifact, blockId, body.get("response"));
    String serialized;
    try {
      serialized = mapper.writeValueAsString(response);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("The activity response could not be saved.", error);
    }
    jdbc.update("""
      insert into student_structured_activity_response
        (institution_id,student_id,course_id,artifact_id,artifact_sha256,block_id,response, attempt_count, first_saved_at, last_saved_at)
      values (?, ?, ?, ?, ?, ?, ?::jsonb, 1, now(), now())
      on conflict (institution_id,student_id,course_id,artifact_id,artifact_sha256,block_id)
      do update set response=excluded.response,
        attempt_count=student_structured_activity_response.attempt_count+1, last_saved_at=now()
      """,institutionId,context.studentId(),resolvedCourseId,artifact.artifactId(),artifact.sha256(),blockId,serialized);
    return Map.of("blockId", blockId, "saved", true, "courseId", courseId);
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if(tenant==null || tenant.institutionId()<1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before accessing structured lessons.");
    }
    return tenant.institutionId();
  }

  private void ensureStudentEnrolledCourse(long studentId,long courseId,long institutionId) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(
        select 1 from student st
        join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join curriculum_program p on p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
        join student_program_enrollment e on e.student_id=st.id and e.institution_id=m.institution_id
          and e.program_id=p.id and e.status='ACTIVE'
          and e.course_access_blocked=false
          and (e.course_expires_at is null or e.course_expires_at>statement_timestamp())
        join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
        join institution_course_release r on r.id=a.course_release_id and r.program_id=p.id
        where st.id=? and st.active=true
      )
      """,Boolean.class,institutionId,courseId,studentId);
    if(!Boolean.TRUE.equals(allowed))throw new SecurityException(
        "The selected course is not actively enrolled in this institution.");
  }

  /**
   * Public course IDs use a fixed 8-character alphanumeric format: three registered
   * prefix letters and five alphanumeric characters. Numeric row IDs remain accepted only
   * for backward-compatible deep links; new learner routes should use public_id.
   */
  private long resolveCourseId(String suppliedCourseId) {
    String value = suppliedCourseId == null ? "" : suppliedCourseId.trim().toUpperCase(Locale.ROOT);
    if (value.matches("[A-Z]{3}[A-Z0-9]{5}")) {
      List<Long> matches = jdbc.query(
          "select id from curriculum_program where public_id=? and status='ACTIVE' and code<>'GENERAL'",
          (result, row) -> result.getLong(1), value);
      if (matches.size() != 1) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "The selected course is not available.");
      }
      return matches.getFirst();
    }
    // Compatibility path for already-issued links; do not generate numeric links going forward.
    if (value.matches("[1-9][0-9]{0,17}")) {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException ignored) {
        // Reject values outside the positive long range below.
      }
    }
    throw new IllegalArgumentException(
        "Use the course's 8-character alphanumeric public ID to access structured lessons.");
  }

  private void ensureStudentCourseTrackAccess(long studentId,long courseId,
      StructuredLessonArtifactService.PublishedArtifact artifact,long institutionId) {
    if(!studentHasCourseTrackAccess(studentId,courseId,artifact,institutionId)) {
      throw new SecurityException("This structured lesson is not part of your selected course in this institution.");
    }
  }

  private boolean studentHasCourseTrackAccess(long studentId,long courseId,
      StructuredLessonArtifactService.PublishedArtifact artifact,long institutionId) {
    Long allowed=jdbc.queryForObject("""
      select count(*)
      from student st
      join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
      join institution_tenant tenant on tenant.id=m.institution_id and tenant.status='ACTIVE'
      join student_program_enrollment spe on spe.student_id=st.id and spe.institution_id=m.institution_id
        and spe.program_id=? and spe.status='ACTIVE'
         and spe.course_access_blocked=false
         and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE'
      join institution_course_assignment assignment on assignment.institution_id=m.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=p.id
      join curriculum_program_subject ps on ps.program_id=p.id
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and p.class_id=s.class_id and ps.class_id=s.class_id))
      join curriculum_class c on c.id=s.class_id and c.active=true
      where st.id=? and st.active=true and lower(s.code)=lower(?)
        and regexp_replace(c.code,'[^0-9]','','g')=?
      """,Long.class,institutionId,courseId,studentId,artifacts.subjectCode(artifact),
        String.valueOf(artifacts.classNumber(artifact)));
    return allowed!=null&&allowed>0;
  }

  private boolean studentHasTrackAccess(long studentId,
      StructuredLessonArtifactService.PublishedArtifact artifact,long institutionId) {
    Long allowed=jdbc.queryForObject("""
      select count(*)
      from student_track_enrollment ste
      join institution_membership m on m.student_id=ste.student_id
        and m.institution_id=ste.institution_id and m.status='ACTIVE'
      join institution_tenant tenant on tenant.id=m.institution_id and tenant.status='ACTIVE'
      join student st on st.id=ste.student_id and st.active=true
      join curriculum_subject s on s.id=ste.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      where ste.student_id=? and ste.institution_id=? and ste.status='ACTIVE'
        and lower(s.code)=lower(?)
        and regexp_replace(c.code,'[^0-9]','','g')=?
        and (
          (s.program_id is not null and exists(select 1 from student_program_enrollment spe
            where spe.institution_id=m.institution_id and spe.student_id=st.id
              and spe.program_id=s.program_id and spe.status='ACTIVE'))
          or (s.program_id is null and c.code=st.class_code and (
            c.code not in ('6','7','8') or exists(
              select 1 from curriculum_program class_course
              join student_program_enrollment spe on spe.program_id=class_course.id
                and spe.institution_id=m.institution_id and spe.student_id=st.id and spe.status='ACTIVE'
              join curriculum_program_subject cps on cps.program_id=class_course.id
                and cps.class_id=s.class_id and cps.subject_id=s.id
              join institution_course_assignment assignment on assignment.institution_id=m.institution_id
                and assignment.status='ASSIGNED'
              join institution_course_release release on release.id=assignment.course_release_id
                and release.program_id=class_course.id
              where class_course.class_id=c.id and class_course.code='CLASS_'||c.code
                and class_course.curriculum_version='CURRENT' and class_course.status='ACTIVE'
            )
          ))
        )
      """,Long.class,studentId,institutionId,artifacts.subjectCode(artifact),
        String.valueOf(artifacts.classNumber(artifact)));
    return allowed!=null&&allowed>0;
  }

  private String localizedText(JsonNode locales, String locale) {
    JsonNode value = locales.path(locale);
    return value.isTextual() ? value.asText("") : "";
  }

  private String normalizeLanguage(String language) {
    String normalized = language == null ? "hi" : language.trim().toLowerCase(Locale.ROOT);
    if ("hi".equals(normalized) || "en".equals(normalized)) return normalized;
    throw new IllegalArgumentException("Language must be 'hi' or 'en'.");
  }
}
