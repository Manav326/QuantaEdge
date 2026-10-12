package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exercises the durable assessment-attempt path against the real migrated PostgreSQL schema.
 * Fixtures are transaction-scoped and are rolled back after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AssessmentLifecycleIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AssessmentController controller;

  @Test
  void topicTestCannotStartDirectlyUntilTheLessonIsCompleted() {
    Fixture fixture = fixture("IN_PROGRESS");

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.startOrResumeAttempt(fixture.assessmentId(), "hi", fixture.student()));

    assertEquals(409, error.getStatusCode().value());
    assertEquals(0L, jdbc.queryForObject(
        "select count(*) from student_assessment_attempt where assessment_id=? and student_id=?",
        Long.class, fixture.assessmentId(), fixture.studentId()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void completedStudentCanStartSaveArchiveResumeSubmitAndFetchReleasedResult() {
    Fixture fixture = fixture("COMPLETED");

    Map<String,Object> started = controller.startOrResumeAttempt(
        fixture.assessmentId(), "hi", fixture.student());
    long attemptId = ((Number) started.get("attempt_id")).longValue();
    long attemptQuestionId = jdbc.queryForObject("""
      select id from student_assessment_attempt_question where attempt_id=?
      """, Long.class, attemptId);
    Map<String,Object> firstQuestion=((List<Map<String,Object>>)started.get("questions")).getFirst();
    List<Map<String,Object>> safeOptions=(List<Map<String,Object>>)firstQuestion.get("options");
    assertEquals("Option A",safeOptions.getFirst().get("label"),
        "Public question options must accept legacy text labels without exposing correctness flags.");

    Map<String,Object> saved = controller.saveAnswer(
        attemptId, attemptQuestionId, Map.of("answer", "B"), fixture.student());
    assertEquals(true, saved.get("saved"));

    // A governance archive removes the old paper from new starts, but must not strand this
    // student's immutable in-progress paper or the answer already autosaved to it.
    jdbc.update("""
      update assessment set status='ARCHIVED',is_current=false,updated_at=now() where id=?
      """, fixture.assessmentId());
    Map<String,Object> catalogue = controller.studentAssessmentCatalog(
        null, "hi", 24, 0, fixture.student());
    List<Map<String,Object>> visible = (List<Map<String,Object>>) catalogue.get("items");
    assertTrue(visible.stream().anyMatch(row ->
        ((Number) row.get("assessment_id")).longValue() == fixture.assessmentId()));

    Map<String,Object> resumed = controller.startOrResumeAttempt(
        fixture.assessmentId(), "hi", fixture.student());
    assertEquals(attemptId, ((Number) resumed.get("attempt_id")).longValue());
    assertEquals("IN_PROGRESS", resumed.get("status"));

    Map<String,Object> submitted = controller.submitAttempt(attemptId, fixture.student());
    assertEquals("RELEASED", submitted.get("status"));
    assertEquals(true, submitted.get("resultReleased"));

    Map<String,Object> savedAnswer = jdbc.queryForMap("""
      select ans.answer_status,ans.is_correct,ans.awarded_marks
      from student_assessment_answer ans
      join student_assessment_attempt_question aq on aq.id=ans.attempt_question_id
      where aq.attempt_id=?
      """, attemptId);
    assertEquals("AUTO_GRADED", savedAnswer.get("answer_status"));
    assertEquals(true, savedAnswer.get("is_correct"));
    assertEquals(new BigDecimal("1.00"), new BigDecimal(savedAnswer.get("awarded_marks").toString()));

    List<Map<String,Object>> afterSubmit = (List<Map<String,Object>>) controller
        .studentAssessmentCatalog(null, "hi", 24, 0, fixture.student()).get("items");
    assertFalse(afterSubmit.stream().anyMatch(row ->
        ((Number) row.get("assessment_id")).longValue() == fixture.assessmentId()),
        "An archived test must no longer appear as an actionable test after its attempt is submitted.");

    Map<String,Object> results = controller.studentResults("hi", 25, 0, fixture.student());
    List<Map<String,Object>> resultItems = (List<Map<String,Object>>) results.get("items");
    assertEquals(1, resultItems.size());
    assertEquals(fixture.assessmentId(),
        ((Number) resultItems.getFirst().get("assessment_id")).longValue());
    assertEquals("RELEASED", resultItems.getFirst().get("status"));
    assertEquals(new BigDecimal("1.00"), new BigDecimal(resultItems.getFirst().get("best_score").toString()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void explicitlyEnabledOfflineAttemptStoresNoAnswerKeyAndCanSyncAfterItsTimerWithinGrace() {
    Fixture fixture=fixture("COMPLETED");
    jdbc.update("update assessment set assessment_type='ADMIN_CREATED',is_generated=false,lesson_id=null,offline_enabled=true where id=?",fixture.assessmentId());
    Map<String,Object> started=controller.startOrResumeAttempt(fixture.assessmentId(),"hi",true,fixture.student());
    long attemptId=((Number)started.get("attempt_id")).longValue();
    String packageToken=String.valueOf(started.get("offlinePackageToken"));
    assertFalse(packageToken.isBlank());
    assertEquals(true,started.get("offlineMode"));
    Map<String,Object> firstQuestion=((List<Map<String,Object>>)started.get("questions")).getFirst();
    assertFalse(firstQuestion.containsKey("answerKey"));
    assertFalse(firstQuestion.containsKey("correctAnswer"));
    assertEquals("OPTION",firstQuestion.get("response_mode"));
    jdbc.update("update student_assessment_attempt set deadline_at=now()-interval '1 hour' where id=?",attemptId);
    long attemptQuestionId=((Number)firstQuestion.get("attemptQuestionId")).longValue();
    Map<String,Object> saved=controller.saveAnswer(attemptId,attemptQuestionId,
        Map.of("answer","B","offlinePackageToken",packageToken),fixture.student());
    assertEquals(true,saved.get("saved"));
    Map<String,Object> submitted=controller.submitAttempt(attemptId,
        Map.of("offlinePackageToken",packageToken),fixture.student());
    assertEquals("RELEASED",submitted.get("status"));
    assertEquals(true,submitted.get("resultReleased"));
  }

  @Test
  void offlineAttemptCannotStartUnlessEditorExplicitlyEnabledIt() {
    Fixture fixture=fixture("COMPLETED");
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.startOrResumeAttempt(fixture.assessmentId(),"hi",true,fixture.student()));
    assertEquals(409,error.getStatusCode().value());
    assertEquals(0L,jdbc.queryForObject(
        "select count(*) from student_assessment_attempt where assessment_id=? and student_id=?",
        Long.class,fixture.assessmentId(),fixture.studentId()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resumingOfflineAttemptDoesNotRotateTokenButExplicitRecoveryDoes() {
    Fixture fixture=fixture("COMPLETED");
    jdbc.update("update assessment set assessment_type='ADMIN_CREATED',is_generated=false,lesson_id=null,offline_enabled=true where id=?",
        fixture.assessmentId());

    Map<String,Object> started=controller.startOrResumeAttempt(
        fixture.assessmentId(),"hi",true,fixture.student());
    long attemptId=((Number)started.get("attempt_id")).longValue();
    String originalToken=String.valueOf(started.get("offlinePackageToken"));
    String originalHash=jdbc.queryForObject(
        "select offline_package_token_hash from student_assessment_attempt where id=?",
        String.class,attemptId);

    Map<String,Object> resumed=controller.startOrResumeAttempt(
        fixture.assessmentId(),"hi",true,fixture.student());
    assertEquals(attemptId,((Number)resumed.get("attempt_id")).longValue());
    assertEquals(originalHash,jdbc.queryForObject(
        "select offline_package_token_hash from student_assessment_attempt where id=?",
        String.class,attemptId));

    String refreshedToken=String.valueOf(
        controller.refreshOfflinePackageToken(attemptId,fixture.student()).get("offlinePackageToken"));
    assertFalse(refreshedToken.isBlank());
    assertFalse(originalToken.equals(refreshedToken));
    long attemptQuestionId=((Number)((List<Map<String,Object>>)started.get("questions")).getFirst()
        .get("attemptQuestionId")).longValue();

    ResponseStatusException rejected=assertThrows(ResponseStatusException.class,
        ()->controller.saveAnswer(attemptId,attemptQuestionId,
            Map.of("answer","B","offlinePackageToken",originalToken),fixture.student()));
    assertEquals(403,rejected.getStatusCode().value());

    Map<String,Object> saved=controller.saveAnswer(attemptId,attemptQuestionId,
        Map.of("answer","B","offlinePackageToken",refreshedToken),fixture.student());
    assertEquals(true,saved.get("saved"));
  }

  private Fixture fixture(String progressStatus) {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String classCode = "E2E" + suffix;
    Long classId = jdbc.queryForObject("""
      insert into curriculum_class(code,display_name,board,sort_order,active)
      values(?,?,'E2E Bihar Board',9999,true) returning id
      """, Long.class, classCode, "Assessment lifecycle " + suffix);
    Long subjectId = jdbc.queryForObject("""
      insert into curriculum_subject(class_id,code,display_name,sort_order,active)
      values(?,'maths','Mathematics',1,true) returning id
      """, Long.class, classId);
    Long chapterId = jdbc.queryForObject("""
      insert into curriculum_chapter(
        subject_id,code,display_name,description,sort_order,active,content_status)
      values(?,'assessment-topic','Assessment lifecycle topic','CI fixture',1,true,'PUBLISHED')
      returning id
      """, Long.class, subjectId);
    Long lessonId = jdbc.queryForObject("""
      insert into lesson(
        chapter_id,code,title,summary,estimated_minutes,status,sort_order,active)
      values(?,'assessment-lesson','Assessment lifecycle lesson','CI fixture',10,'PUBLISHED',1,true)
      returning id
      """, Long.class, chapterId);
    Long studentId = jdbc.queryForObject("""
      insert into student(public_id,display_name,class_code,board,language,environment,active)
      values(?,?,?,'E2E Bihar Board','hi','PRODUCTION',true) returning id
      """, Long.class, UUID.randomUUID(), "Assessment CI student", classCode);
    jdbc.update("""
      insert into student_track_enrollment(student_id,subject_id,status) values(?,?,'ACTIVE')
      """, studentId, subjectId);
    jdbc.update("""
      insert into student_lesson_progress(student_id,lesson_id,status,progress_percent)
      values(?,?,?,?)
      """, studentId, lessonId, progressStatus, "COMPLETED".equals(progressStatus) ? 100 : 25);

    Long assessmentId = jdbc.queryForObject("""
      insert into assessment(
        title,description,subject_id,chapter_id,lesson_id,duration_minutes,max_attempts,status,
        assessment_type,is_generated,is_current,generation_version,published_at)
      values('Assessment lifecycle test','CI fixture',?,?,?,15,2,'PUBLISHED',
        'TOPIC_END',true,true,1,now()) returning id
      """, Long.class, subjectId, chapterId, lessonId);
    String snapshot = """
      {
        "id": 1,
        "question_type": "MCQ",
        "prompt": "Which option is correct?",
        "explanation": "Option B is correct.",
        "answer_payload": "{\\"kind\\":\\"OPTION\\",\\"value\\":\\"B\\"}",
        "options": [
          {"option_key":"A","text":"Option A","is_correct":false},
          {"option_key":"B","label":"Option B","is_correct":true}
        ]
      }
      """;
    jdbc.update("""
      insert into assessment_question(assessment_id,source_question_id,sequence_no,max_marks,question_snapshot)
      values(?,null,1,1,?::jsonb)
      """, assessmentId, snapshot);
    return new Fixture(studentId, assessmentId,
        new AuthContext(null, studentId, "STUDENT", "Assessment CI student"));
  }

  private record Fixture(Long studentId, Long assessmentId, AuthContext student) {}
}
