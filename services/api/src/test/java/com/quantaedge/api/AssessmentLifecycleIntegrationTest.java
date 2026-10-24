package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
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
  
  private static final InstitutionTenantContext TEST_TENANT =
      new InstitutionTenantContext(1L, "TEST", "Test Institution");
@Autowired private JdbcTemplate jdbc;
  @Autowired private AssessmentController controller;

  @Test
  void topicTestCannotStartDirectlyUntilTheLessonIsCompleted() {
    Fixture fixture = fixture("IN_PROGRESS");

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.startOrResumeAttempt(fixture.assessmentId(), fixture.courseId(), "hi", false, fixture.student(), fixture.tenant()));

    assertEquals(409, error.getStatusCode().value());
    assertEquals(0L, jdbc.queryForObject(
        "select count(*) from student_assessment_attempt where assessment_id=? and student_id=?",
        Long.class, fixture.assessmentId(), fixture.studentId()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void completedStudentCanStartSaveArchiveResumeSubmitAndFetchReleasedResult() {
    Fixture fixture = fixture("COMPLETED");

    Map<String,Object> started = controller.startOrResumeAttempt(fixture.assessmentId(), fixture.courseId(), "hi", false, fixture.student(), fixture.tenant());
    long attemptId = ((Number) started.get("attempt_id")).longValue();
    List<Map<String,Object>> activeQuestions=(List<Map<String,Object>>)started.get("questions");
    assertFalse(activeQuestions.getFirst().containsKey("explanation"),
        "Question explanations must remain hidden before result release.");
    long attemptQuestionId = jdbc.queryForObject("""
      select id from student_assessment_attempt_question where attempt_id=?
      """, Long.class, attemptId);
    Map<String,Object> firstQuestion=((List<Map<String,Object>>)started.get("questions")).getFirst();
    List<Map<String,Object>> safeOptions=(List<Map<String,Object>>)firstQuestion.get("options");
    assertEquals("Option A",safeOptions.getFirst().get("label"),
        "Public question options must accept legacy text labels without exposing correctness flags.");

    Map<String,Object> saved = controller.saveAnswer(
        attemptId, attemptQuestionId, fixture.courseId(), Map.of("answer", "B"), fixture.student(), fixture.tenant());
    assertEquals(true, saved.get("saved"));

    // A governance archive removes the old paper from new starts, but must not strand this
    // student's immutable in-progress paper or the answer already autosaved to it.
    jdbc.update("""
      update assessment set status='ARCHIVED',is_current=false,updated_at=now() where id=?
      """, fixture.assessmentId());
    Map<String,Object> catalogue = controller.studentAssessmentCatalog(
        null, fixture.courseId(), "hi", 24, 0, fixture.student(), fixture.tenant());
    List<Map<String,Object>> visible = (List<Map<String,Object>>) catalogue.get("items");
    assertTrue(visible.stream().anyMatch(row ->
        ((Number) row.get("assessment_id")).longValue() == fixture.assessmentId()));

    Map<String,Object> resumed = controller.startOrResumeAttempt(fixture.assessmentId(), fixture.courseId(), "hi", false, fixture.student(), fixture.tenant());
    assertEquals(attemptId, ((Number) resumed.get("attempt_id")).longValue());
    assertEquals("IN_PROGRESS", resumed.get("status"));

    Map<String,Object> submitted = controller.submitAttempt(attemptId, fixture.courseId(), Map.of(), fixture.student(), fixture.tenant());
    assertEquals("RELEASED", submitted.get("status"));
    assertEquals(true, submitted.get("resultReleased"));
    List<Map<String,Object>> releasedQuestions=(List<Map<String,Object>>)submitted.get("questions");
    assertEquals("Option B is correct.", releasedQuestions.getFirst().get("explanation"),
        "Question explanations should be returned for the released answer-review flow.");

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
        .studentAssessmentCatalog(null, fixture.courseId(), "hi", 24, 0, fixture.student(), fixture.tenant()).get("items");
    assertFalse(afterSubmit.stream().anyMatch(row ->
        ((Number) row.get("assessment_id")).longValue() == fixture.assessmentId()),
        "An archived test must no longer appear as an actionable test after its attempt is submitted.");

    Map<String,Object> results = controller.studentResults("hi", fixture.courseId(), 25, 0, fixture.student(), fixture.tenant());
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
    Fixture fixture=fixture("COMPLETED",true);

    Map<String,Object> started=controller.startOrResumeAttempt(fixture.assessmentId(),fixture.courseId(),"hi",true,fixture.student(), fixture.tenant());
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
    Map<String,Object> saved=controller.saveAnswer(attemptId,attemptQuestionId,fixture.courseId(),
        Map.of("answer","B","offlinePackageToken",packageToken),fixture.student(), fixture.tenant());
    assertEquals(true,saved.get("saved"));
    Map<String,Object> submitted=controller.submitAttempt(attemptId,fixture.courseId(),
        Map.of("offlinePackageToken",packageToken),fixture.student(), fixture.tenant());
    assertEquals("RELEASED",submitted.get("status"));
    assertEquals(true,submitted.get("resultReleased"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void courseAnalyticsUsesReleasedAttemptsAndExecutesAgainstTheMigratedSchema() {
    Fixture fixture=fixture("COMPLETED");
    Map<String,Object> started=controller.startOrResumeAttempt(
        fixture.assessmentId(),fixture.courseId(),"hi",false,fixture.student(),fixture.tenant());
    long attemptId=((Number)started.get("attempt_id")).longValue();
    long attemptQuestionId=jdbc.queryForObject("""
      select id from student_assessment_attempt_question where attempt_id=?
      """,Long.class,attemptId);
    controller.saveAnswer(attemptId,attemptQuestionId,fixture.courseId(),
        Map.of("answer","B"),fixture.student(),fixture.tenant());
    Map<String,Object> submitted=controller.submitAttempt(attemptId,fixture.courseId(),
        Map.of(),fixture.student(),fixture.tenant());
    assertEquals("RELEASED",submitted.get("status"));

    Long analyticsStaffId=jdbc.queryForObject("""
      insert into staff_account(public_id,mobile_e164,display_name,role,active)
      values(gen_random_uuid(),null,'Assessment analytics reviewer','ADMIN',true)
      returning id
      """,Long.class);
    jdbc.update("""
      insert into staff_permission_grant(staff_id,workspace_key,permission_key)
      values(?,'EDUCATION_ADMIN','CONTENT_VIEW')
      on conflict(staff_id,workspace_key,permission_key) do nothing
      """,analyticsStaffId);
    AuthContext reviewer=new AuthContext(101L,null,analyticsStaffId,"ADMIN","Assessment analytics reviewer");
    Map<String,Object> analytics=controller.courseAssessmentAnalytics(
        fixture.courseId(),null,reviewer,fixture.tenant());
    Map<String,Object> summary=(Map<String,Object>)analytics.get("summary");
    assertEquals(1L,((Number)summary.get("assessment_count")).longValue());
    assertEquals(1L,((Number)summary.get("attempt_count")).longValue());
    assertEquals(1L,((Number)summary.get("released_attempt_count")).longValue());
    assertEquals(1L,((Number)summary.get("learner_count")).longValue());
    assertTrue(new BigDecimal(String.valueOf(summary.get("average_released_score_percent")))
        .compareTo(new BigDecimal("100"))==0);
    assertEquals("RELEASED_ATTEMPTS_ONLY",
        ((Map<String,Object>)analytics.get("dataPolicy")).get("scoreAnalyticsStatus"));
    assertFalse(analytics.containsKey("student_id"));
    List<Map<String,Object>> questions=(List<Map<String,Object>>)analytics.get("questionPerformance");
    assertEquals(1,questions.size());
    assertEquals(1L,((Number)questions.getFirst().get("exposures")).longValue());
    assertEquals("Which option is correct?",questions.getFirst().get("prompt"));
    List<Map<String,Object>> selections=(List<Map<String,Object>>)analytics.get("optionSelections");
    assertTrue(selections.stream().anyMatch(row -> "B".equals(String.valueOf(row.get("selected_value")))));
  }


  @Test
  @SuppressWarnings("unchecked")
  void releasedIncorrectQuestionsCanBeRetriedWithoutChangingTheOfficialScore() {
    Fixture fixture=fixture("COMPLETED");
    Map<String,Object> started=controller.startOrResumeAttempt(
        fixture.assessmentId(),fixture.courseId(),"hi",false,fixture.student(),fixture.tenant());
    long attemptId=((Number)started.get("attempt_id")).longValue();
    List<Map<String,Object>> startedQuestions=(List<Map<String,Object>>)started.get("questions");
    long questionId=((Number)startedQuestions.getFirst().get("attemptQuestionId")).longValue();

    controller.saveAnswer(attemptId,questionId,fixture.courseId(),Map.of("answer","A"),
        fixture.student(),fixture.tenant());
    Map<String,Object> released=controller.submitAttempt(attemptId,fixture.courseId(),Map.of(),
        fixture.student(),fixture.tenant());
    assertEquals("RELEASED",released.get("status"));
    assertEquals(false,((List<Map<String,Object>>)released.get("questions")).getFirst().get("isCorrect"));
    assertEquals("Algorithms",((List<Map<String,Object>>)released.get("questions")).getFirst().get("topic"));
    assertEquals(List.of("Sorting"),((List<Map<String,Object>>)released.get("questions")).getFirst().get("subtopic_tags"));
    Object officialScore=released.get("final_score");

    Map<String,Object> progress=controller.studentIncorrectPracticeProgress(
        attemptId,fixture.courseId(),fixture.student(),fixture.tenant());
    List<Map<String,Object>> incorrect=(List<Map<String,Object>>)progress.get("items");
    assertEquals(1,incorrect.size());
    assertEquals(true,incorrect.getFirst().get("practiceable"));
    assertEquals(0L,((Number)incorrect.getFirst().get("practice_count")).longValue());

    Map<String,Object> retry=controller.submitIncorrectPracticeAnswer(attemptId,fixture.courseId(),
        Map.of("attemptQuestionId",questionId,"answer","B"),fixture.student(),fixture.tenant());
    assertEquals(true,retry.get("last_correct"));
    assertEquals(1L,((Number)retry.get("practice_count")).longValue());
    assertEquals(true,retry.get("ever_correct"));

    Map<String,Object> refreshed=controller.studentIncorrectPracticeProgress(
        attemptId,fixture.courseId(),fixture.student(),fixture.tenant());
    List<Map<String,Object>> updated=(List<Map<String,Object>>)refreshed.get("items");
    assertEquals(true,updated.getFirst().get("last_correct"));
    assertEquals(true,updated.getFirst().get("ever_correct"));
    assertEquals(1L,((Number)updated.getFirst().get("practice_count")).longValue());

    Map<String,Object> officialAfterPractice=controller.studentAttempt(
        attemptId,fixture.courseId(),fixture.student(),fixture.tenant());
    assertEquals(officialScore,officialAfterPractice.get("final_score"),
        "Practice answers must never change the released score or negative-marking result.");
  }


  @Test
  @SuppressWarnings("unchecked")
  void subjectlessAssessmentCourseCanOpenReleasedResultsAndIncorrectPractice() {
    Fixture fixture=fixture("COMPLETED",false,"ASSESSMENT");
    Map<String,Object> started=controller.startOrResumeAttempt(
        fixture.assessmentId(),fixture.courseId(),"hi",false,fixture.student(),fixture.tenant());
    long attemptId=((Number)started.get("attempt_id")).longValue();
    long questionId=((Number)((List<Map<String,Object>>)started.get("questions")).getFirst()
        .get("attemptQuestionId")).longValue();
    controller.saveAnswer(attemptId,questionId,fixture.courseId(),Map.of("answer","A"),
        fixture.student(),fixture.tenant());
    Map<String,Object> released=controller.submitAttempt(attemptId,fixture.courseId(),Map.of(),
        fixture.student(),fixture.tenant());
    assertEquals("RELEASED",released.get("status"));
    assertEquals(false,((List<Map<String,Object>>)released.get("questions")).getFirst().get("isCorrect"));

    Map<String,Object> resultPage=controller.studentResults(
        "hi",fixture.courseId(),null,25,0,fixture.student(),fixture.tenant());
    assertEquals(1L,((Number)resultPage.get("total")).longValue(),
        "Subjectless assessment-only courses must be counted in student result pagination.");
    List<Map<String,Object>> resultItems=(List<Map<String,Object>>)resultPage.get("items");
    assertEquals(1,resultItems.size());

    Map<String,Object> reopened=controller.studentAttempt(
        attemptId,fixture.courseId(),fixture.student(),fixture.tenant());
    assertEquals("RELEASED",reopened.get("status"));
    assertEquals("Algorithms",((List<Map<String,Object>>)reopened.get("questions")).getFirst().get("topic"));
    Map<String,Object> retry=controller.submitIncorrectPracticeAnswer(attemptId,fixture.courseId(),
        Map.of("attemptQuestionId",questionId,"answer","B"),fixture.student(),fixture.tenant());
    assertEquals(true,retry.get("last_correct"));
    assertEquals(new BigDecimal("0.00"),new BigDecimal(String.valueOf(reopened.get("final_score"))));
  }

  @Test
  void offlineAttemptCannotStartUnlessEditorExplicitlyEnabledIt() {
    Fixture fixture=fixture("COMPLETED");
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.startOrResumeAttempt(fixture.assessmentId(),fixture.courseId(),"hi",true,fixture.student(), fixture.tenant()));
    assertEquals(409,error.getStatusCode().value());
    assertEquals(0L,jdbc.queryForObject(
        "select count(*) from student_assessment_attempt where assessment_id=? and student_id=?",
        Long.class,fixture.assessmentId(),fixture.studentId()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resumingOfflineAttemptDoesNotRotateTokenButExplicitRecoveryDoes() {
    Fixture fixture=fixture("COMPLETED",true);


    Map<String,Object> started=controller.startOrResumeAttempt(
        fixture.assessmentId(),fixture.courseId(),"hi",true,fixture.student(), fixture.tenant());
    long attemptId=((Number)started.get("attempt_id")).longValue();
    String originalToken=String.valueOf(started.get("offlinePackageToken"));
    String originalHash=jdbc.queryForObject(
        "select offline_package_token_hash from student_assessment_attempt where id=?",
        String.class,attemptId);

    Map<String,Object> resumed=controller.startOrResumeAttempt(
        fixture.assessmentId(),fixture.courseId(),"hi",true,fixture.student(), fixture.tenant());
    assertEquals(attemptId,((Number)resumed.get("attempt_id")).longValue());
    assertEquals(originalHash,jdbc.queryForObject(
        "select offline_package_token_hash from student_assessment_attempt where id=?",
        String.class,attemptId));

    String refreshedToken=String.valueOf(
        controller.refreshOfflinePackageToken(attemptId,fixture.courseId(),fixture.student(), fixture.tenant()).get("offlinePackageToken"));
    assertFalse(refreshedToken.isBlank());
    assertFalse(originalToken.equals(refreshedToken));
    long attemptQuestionId=((Number)((List<Map<String,Object>>)started.get("questions")).getFirst()
        .get("attemptQuestionId")).longValue();

    ResponseStatusException rejected=assertThrows(ResponseStatusException.class,
        ()->controller.saveAnswer(attemptId,attemptQuestionId,fixture.courseId(),
            Map.of("answer","B","offlinePackageToken",originalToken),fixture.student(), fixture.tenant()));
    assertEquals(403,rejected.getStatusCode().value());

    Map<String,Object> saved=controller.saveAnswer(attemptId,attemptQuestionId,fixture.courseId(),
        Map.of("answer","B","offlinePackageToken",refreshedToken),fixture.student(), fixture.tenant());
    assertEquals(true,saved.get("saved"));
  }

  private Fixture fixture(String progressStatus) { return fixture(progressStatus,false,"K12"); }

  private Fixture fixture(String progressStatus,boolean offlineEnabled) { return fixture(progressStatus,offlineEnabled,"K12"); }

  private Fixture fixture(String progressStatus,boolean offlineEnabled,String courseType) {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String classCode = "E2E" + suffix;
    Long classId = jdbc.queryForObject("""
      insert into curriculum_class(code,display_name,board,sort_order,active)
      values(?,?,'E2E Bihar Board',9999,true) returning id
      """, Long.class, classCode, "Assessment lifecycle " + suffix);
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant
      where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    Long subjectId = jdbc.queryForObject("""
      insert into curriculum_subject(class_id,code,display_name,sort_order,active)
      values(?,'maths','Mathematics',1,true) returning id
      """, Long.class, classId);
    Long courseId = jdbc.queryForObject("""
      insert into curriculum_program(
        class_id,code,display_name,curriculum_version,status,notes,public_id_prefix,course_type_code)
      values(?,?,'Assessment lifecycle course','CURRENT','ACTIVE','CI fixture course','INS',?)
      returning id
      """, Long.class, classId, "ASSESSMENT_" + suffix.toUpperCase(Locale.ROOT),courseType);
    Long identityId = jdbc.queryForObject("""
      insert into institution_course_identity(
        source_institution_id,stable_course_key,display_name,course_type_code)
      values(?,?,?,?) returning id
      """, Long.class, institutionId, "assessment-lifecycle-" + suffix,
      "Assessment lifecycle course",courseType);
    jdbc.update("update curriculum_program set course_identity_id=? where id=?", identityId, courseId);
    jdbc.update("""
      insert into curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order)
      values(?,?,?,true,1)
      """, courseId, classId, subjectId);
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
      insert into institution_membership(
        institution_id,student_id,join_code_id,status,joined_at,left_at,created_at,updated_at)
      values(?,?,null,'ACTIVE',now(),null,now(),now())
      """, institutionId, studentId);
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,created_at,updated_at)
      values(?,?,?,'ACTIVE',now(),now(),now())
      """, studentId, courseId, institutionId);
    jdbc.update("""
      insert into student_track_enrollment(institution_id,student_id,subject_id,status)
      values(?,?,?,'ACTIVE')
      """, institutionId, studentId, subjectId);
    jdbc.update("""
      insert into student_lesson_progress(
        institution_id,student_id,lesson_id,status,progress_percent)
      values(?,?,?,?,?)
      """, institutionId, studentId, lessonId, progressStatus,
      "COMPLETED".equals(progressStatus) ? 100 : 25);

    boolean subjectlessAssessment = "ASSESSMENT".equals(courseType);
    Long assessmentId = jdbc.queryForObject("""
      insert into assessment(
        title,description,course_id,subject_id,chapter_id,lesson_id,duration_minutes,max_attempts,status,
        assessment_type,is_generated,is_current,generation_version,published_at,offline_enabled)
      values('Assessment lifecycle test','CI fixture',?,?,?,?,15,2,'PUBLISHED',
        ?,?,true,1,now(),?) returning id
      """, Long.class, courseId, subjectlessAssessment ? null : subjectId,
      subjectlessAssessment ? null : chapterId,
      offlineEnabled || subjectlessAssessment ? null : lessonId,
      offlineEnabled || subjectlessAssessment ? "ADMIN_CREATED" : "TOPIC_END",
      !offlineEnabled && !subjectlessAssessment, offlineEnabled);
    String snapshot = """
      {
        "id": 1,
        "question_type": "MCQ",
        "topic": "Algorithms",
        "topic_tags": ["Algorithms"],
        "subtopic_tags": ["Sorting"],
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
    // Use explicit reviewer and source-owner identities: SHAREABLE releases must carry
    // both quality-review and source-origin approval evidence under the production constraints.
    Long reviewerStaffId = jdbc.queryForObject("""
      insert into staff_account(public_id,mobile_e164,display_name,role,active)
      values(gen_random_uuid(),null,'Assessment lifecycle CI reviewer','ADMIN',true)
      returning id
      """, Long.class);
    Long sourceAdminId = jdbc.queryForObject("""
      insert into institution_admin_account(institution_id,username,display_name,password_hash,active)
      values(?,?,?,'ci-test-password-hash',true)
      returning id
      """, Long.class, institutionId, "assessment_" + suffix, "Assessment lifecycle CI publisher");
    // Finish the graph before release: released course content is immutable in place.
    Long releaseId = jdbc.queryForObject("""
      insert into institution_course_release(
        source_institution_id,program_id,release_version,release_state,content_fingerprint,
        quality_reviewed_by_staff_id,quality_reviewed_at,quality_review_notes,
        origin_approved_by_admin_id,origin_approved_at,shareable_at)
      values(?,?,1,'SHAREABLE',course_content_fingerprint(?),?,now(),
        'Synthetic CI assessment content review',?,now(),now()) returning id
      """, Long.class, institutionId, courseId, courseId, reviewerStaffId, sourceAdminId);
    jdbc.update("""
      insert into institution_course_assignment(institution_id,course_release_id,status,assignment_source)
      values(?,?,'ASSIGNED','HRMS')
      """, institutionId, releaseId);
    return new Fixture(studentId, assessmentId, courseId,
        new AuthContext(null, studentId, "STUDENT", "Assessment CI student"),
        new InstitutionTenantContext(institutionId, "QUANTA-EDGE", "QuantaEdge"));
  }

  private record Fixture(Long studentId, Long assessmentId, Long courseId, AuthContext student,
      InstitutionTenantContext tenant) {}
}
