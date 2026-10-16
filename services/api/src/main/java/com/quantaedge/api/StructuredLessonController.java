package com.quantaedge.api;

import java.util.LinkedHashMap;
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

  public StructuredLessonController(JdbcTemplate jdbc, AuthorizationService authorization,
      StructuredLessonArtifactService artifacts, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.artifacts = artifacts;
    this.mapper = mapper;
  }

  /** Authenticated learner delivery. Drafts and unreviewed artifacts deliberately return 404. */
  @GetMapping("/{artifactId}")
  public ObjectNode lesson(@PathVariable String artifactId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String locale = normalizeLanguage(language);
    StructuredLessonArtifactService.PublishedArtifact artifact = artifacts.requirePublished(artifactId);
    ensureStudentTrackAccess(context.studentId(), artifact);
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
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String locale = normalizeLanguage(language);
    if (body == null || !body.containsKey("response")) {
      throw new IllegalArgumentException("A response is required.");
    }

    StructuredLessonArtifactService.PublishedArtifact artifact = artifacts.requirePublished(artifactId);
    ensureStudentTrackAccess(context.studentId(), artifact);
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
        (student_id, artifact_id, artifact_sha256, question_id, response,
         result_status, score, max_score, attempt_count, first_submitted_at, last_submitted_at)
      values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 1, now(), now())
      on conflict (student_id, artifact_id, artifact_sha256, question_id)
      do update set response=excluded.response,
        result_status=excluded.result_status, score=excluded.score, max_score=excluded.max_score,
        attempt_count=student_structured_question_response.attempt_count+1,
        last_submitted_at=now()
      """,
      context.studentId(), artifact.artifactId(), artifact.sha256(), questionId, serialized,
      evaluation.status().toUpperCase(Locale.ROOT), evaluation.score(), evaluation.maxScore());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("questionId", questionId);
    result.put("saved", true);
    result.put("autoGraded", evaluation.autoGraded());
    result.put("status", evaluation.status());
    if (evaluation.score() != null) result.put("score", evaluation.score());
    if (evaluation.maxScore() != null) result.put("maxScore", evaluation.maxScore());
    result.put("feedback", evaluation.feedback());
    return result;
  }

  private void ensureStudentTrackAccess(long studentId,
      StructuredLessonArtifactService.PublishedArtifact artifact) {
    Long allowed = jdbc.queryForObject("""
      select count(*)
      from student_track_enrollment ste
      join student st on st.id=ste.student_id and st.active=true
      join curriculum_subject s on s.id=ste.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      where ste.student_id=? and ste.status='ACTIVE'
        and lower(s.code)=lower(?)
        and regexp_replace(c.code, '[^0-9]', '', 'g')=?
      """, Long.class, studentId, artifacts.subjectCode(artifact),
        String.valueOf(artifacts.classNumber(artifact)));
    if (allowed == null || allowed < 1) {
      throw new SecurityException("Student access to this structured lesson is not permitted.");
    }
  }

  private String normalizeLanguage(String language) {
    String normalized = language == null ? "hi" : language.trim().toLowerCase(Locale.ROOT);
    if ("hi".equals(normalized) || "en".equals(normalized)) return normalized;
    throw new IllegalArgumentException("Language must be 'hi' or 'en'.");
  }
}
