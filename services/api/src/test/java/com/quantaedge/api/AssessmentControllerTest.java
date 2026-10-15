package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AssessmentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private QuestionHistoryService questionHistory;
  @Mock private QuestionAnswerService answerService;
  @Mock private StaffAuditService staffAudit;
  @Mock private AssessmentCatalogService assessmentCatalog;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private final AuthContext author = new AuthContext(100L, null, 42L, "CONTENT_AUTHOR", "Author");
  private final AuthContext admin = new AuthContext(101L, null, 99L, "ADMIN", "Administrator");
  private AssessmentController controller;

  @BeforeEach
  void setUp() {
    controller = new AssessmentController(
        jdbc, authorization, questionHistory, answerService, staffAudit, new ObjectMapper(), assessmentCatalog);
  }

  @Test
  void offlinePolicyRequiresContentEditAndRejectsGeneratedTests() {
    when(authorization.requireAuth(author)).thenReturn(author);
    when(authorization.requirePermission(author,"CONTENT_EDIT")).thenReturn(author);
    when(jdbc.queryForList(contains("select id,title,status,is_generated,offline_enabled"),
        eq(88L))).thenReturn(List.of(Map.of(
          "id",88L,"title","Generated test","status","PUBLISHED","is_generated",true,"offline_enabled",false)));
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.updateOfflinePolicy(88L,Map.of("offlineEnabled",true),author));
    assertEquals(400,error.getStatusCode().value());
    verify(jdbc,never()).update(contains("update assessment set offline_enabled"),any(Object[].class));
  }

  @Test
  void studentCatalogueRequiresCompletedLessonsAndHidesSupersededGeneratedTestsExceptForResumes() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select a.id as assessment_id"), any(Object[].class)))
        .thenReturn(List.of());

    controller.studentAssessmentCatalog(null, "hi", 24, 0, student);

    verify(jdbc).queryForList(contains("tp.status='COMPLETED'"), any(Object[].class));
    verify(jdbc).queryForList(contains("required_lesson.chapter_id=ch.id"), any(Object[].class));
  }

  @Test
  void studentCatalogueEligibilityAllowsOnlyAnOwnedInProgressAttemptToBypassContentRefresh() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select a.id as assessment_id"), any(Object[].class)))
        .thenReturn(List.of());

    controller.studentAssessmentCatalog(null, "hi", 24, 0, student);

    verify(jdbc).queryForList(contains("resumable.student_id=st.id"), any(Object[].class));
    verify(jdbc).queryForList(contains("resumable.status='IN_PROGRESS'"), any(Object[].class));
    verify(jdbc).queryForList(contains("a.is_current=true"), any(Object[].class));
  }

  @Test
  void directAttemptStartCannotBypassLessonCompletionGate() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select a.id from assessment a"), eq(19L), eq(7L)))
        .thenReturn(List.of(Map.of("id", 19L)));
    when(jdbc.queryForList(contains("coalesce(max(at.title),a.title)"),
        eq(7L), eq("hi"), eq("hi"), eq("hi"), eq(19L)))
        .thenReturn(List.of(Map.of("id", 19L)));
    when(jdbc.queryForList(contains("select id,offline_mode from student_assessment_attempt"),
        eq(19L), eq(7L))).thenReturn(List.of());
    when(jdbc.queryForList(contains("and a.status='PUBLISHED' and"),
        eq(7L), eq(19L))).thenReturn(List.of());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.startOrResumeAttempt(19L, "hi", student));

    assertEquals(409, error.getStatusCode().value());
  }

  @Test
  void publishedAssessmentCannotSkipTheArchiveAndRestoreDraftLifecycle() {
    when(authorization.requireAuth(admin)).thenReturn(admin);
    when(authorization.requirePermission(admin, "CONTENT_EDIT")).thenReturn(admin);
    when(jdbc.queryForList(contains("select a.id,a.title,a.status"), eq(42L)))
        .thenReturn(List.of(Map.of("id", 42L, "title", "Fractions test", "status", "PUBLISHED",
            "is_generated", false, "subject_id", 3L, "chapter_id", 9L,
            "subject_code", "maths", "class_code", "7", "question_count", 5L)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.changeAssessmentStatus(42L, Map.of("status", "DRAFT"), admin));

    assertEquals(400, error.getStatusCode().value());
    verifyNoInteractions(staffAudit);
  }

  @Test
  void generatedAssessmentCannotBeManuallyArchived() {
    when(authorization.requireAuth(admin)).thenReturn(admin);
    when(authorization.requirePermission(admin, "CONTENT_PUBLISH")).thenReturn(admin);
    when(jdbc.queryForList(contains("select a.id,a.title,a.status"), eq(43L)))
        .thenReturn(List.of(Map.of("id", 43L, "title", "Topic end", "status", "PUBLISHED",
            "is_generated", true, "subject_id", 3L, "chapter_id", 9L,
            "subject_code", "maths", "class_code", "7", "question_count", 5L)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.changeAssessmentStatus(43L, Map.of("status", "ARCHIVED"), admin));

    assertEquals(400, error.getStatusCode().value());
    verify(jdbc, never()).update(contains("update assessment set status=?"), any(), any(), any(), any(), any());
  }

  @Test
  void formalTestFillInAndWrittenAnswersAreNotAutoGraded() {
    assertFalse(AssessmentController.isAutoGradedType("INPUT"));
    assertFalse(AssessmentController.isAutoGradedType("SHORT_ANSWER"));
    assertFalse(AssessmentController.isAutoGradedType("LONG_ANSWER"));
    assertTrue(AssessmentController.isAutoGradedType("MCQ"));
    assertTrue(AssessmentController.isAutoGradedType("TRUE_FALSE"));
    assertTrue(AssessmentController.isAutoGradedType("NUMERICAL"));
  }

  @Test
  void savingAResponseRequiresTheOwningStudentsInProgressAttempt() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select att.id,att.status,att.deadline_at"), eq(5L), eq(25L)))
        .thenReturn(List.of(Map.of(
            "id", 5L, "status", "IN_PROGRESS",
            "deadline_at", OffsetDateTime.now().plusMinutes(15), "student_id", 7L)));

    Map<String, Object> result = controller.saveAnswer(
        5L, 25L, Map.of("answer", "My saved answer"), student);

    assertEquals(true, result.get("saved"));
    verify(jdbc).update(contains("update student_assessment_answer set response_payload"),
        eq("\"My saved answer\""), eq(25L), eq(25L), eq(5L));
  }

  @Test
  void anotherStudentsAttemptCannotBeReadOrChanged() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select att.id,att.status,att.deadline_at"), eq(5L), eq(25L)))
        .thenReturn(List.of(Map.of(
            "id", 5L, "status", "IN_PROGRESS",
            "deadline_at", OffsetDateTime.now().plusMinutes(15), "student_id", 8L)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.saveAnswer(5L, 25L, Map.of("answer", "intrusion"), student));

    assertEquals(404, error.getStatusCode().value());
    verify(jdbc, never()).update(contains("update student_assessment_answer set response_payload"),
        any(), any(), any(), any());
  }

  @Test
  void invalidAssessmentWithNoSelectedQuestionsDoesNotWriteToDatabase() {
    when(authorization.requirePermission(author, "CONTENT_CREATE")).thenReturn(author);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.createAssessment(Map.of(
            "title", "Fractions test", "classCode", "7", "subjectCode", "maths"), author));

    assertEquals(400, error.getStatusCode().value());
    verifyNoInteractions(jdbc);
  }

  @Test
  void gradingIsRestrictedToTheAssignedStaffMember() {
    when(authorization.requireAuth(author)).thenReturn(author);
    when(authorization.requirePermission(author, "ASSESSMENT_GRADE")).thenReturn(author);
    when(jdbc.queryForList(contains("select id,assessment_id,status,auto_score,max_score"),
        eq(81L))).thenReturn(List.of(Map.of(
            "id", 81L, "assessment_id", 21L, "status", "AWAITING_REVIEW",
            "auto_score", "2.00", "max_score", "5.00")));
    when(jdbc.queryForObject(contains("from assessment_staff_assignment asa"),
        eq(Long.class), eq(21L), eq(42L))).thenReturn(0L);

    assertThrows(SecurityException.class,
        () -> controller.gradeAttempt(81L, Map.of("answers", List.of()), author));

    verify(jdbc, never()).update(contains("update student_assessment_answer"), any(), any(), any(), any());
    verifyNoInteractions(staffAudit);
  }

  @Test
  void studentQuestionSnapshotNeverExposesAnswerKeyOrCorrectOptionFlags() {
    when(authorization.requireStudent(student)).thenReturn(student);
    Map<String, Object> attemptRow = new LinkedHashMap<>();
    attemptRow.put("attempt_id", 5L);
    attemptRow.put("assessment_id", 20L);
    attemptRow.put("student_id", 7L);
    attemptRow.put("attempt_number", 1);
    attemptRow.put("status", "IN_PROGRESS");
    attemptRow.put("started_at", OffsetDateTime.now().minusMinutes(1));
    attemptRow.put("deadline_at", OffsetDateTime.now().plusMinutes(20));
    attemptRow.put("submitted_at", null);
    attemptRow.put("graded_at", null);
    attemptRow.put("released_at", null);
    attemptRow.put("auto_score", "0.00");
    attemptRow.put("final_score", null);
    attemptRow.put("max_score", "2.00");
    attemptRow.put("assessment_snapshot", "{}");
    attemptRow.put("student_name", "Preview Student");
    attemptRow.put("class_code", "7");
    attemptRow.put("assessment_title", "Fractions test");
    attemptRow.put("subject_code", "maths");
    attemptRow.put("subject_name", "Mathematics");
    attemptRow.put("chapter_name", "Fractions");
    when(jdbc.queryForList(contains("select att.id as attempt_id"), eq(5L)))
        .thenReturn(List.of(attemptRow));

    Map<String, Object> questionRow = new LinkedHashMap<>();
    questionRow.put("attempt_question_id", 501L);
    questionRow.put("sequence_no", 1);
    questionRow.put("max_marks", "2.00");
    questionRow.put("question_snapshot", """
        {"id":44,"question_type":"MCQ","prompt":"Which answer is correct?",
         "answer_payload":"{\\\"kind\\\":\\\"OPTION\\\",\\\"value\\\":\\\"B\\\"}",
         "options":[{"option_key":"A","label":"Option A","is_correct":false},
                    {"option_key":"B","label":"Option B","is_correct":true}]}
        """);
    questionRow.put("answer_id", 600L);
    questionRow.put("response_payload", "\"A\"");
    questionRow.put("answer_status", "NOT_ANSWERED");
    questionRow.put("is_correct", null);
    questionRow.put("awarded_marks", null);
    questionRow.put("teacher_feedback", null);
    when(jdbc.queryForList(contains("select aq.id as attempt_question_id"), eq(5L)))
        .thenReturn(List.of(questionRow));

    Map<String, Object> result = controller.studentAttempt(5L, student);
    @SuppressWarnings("unchecked")
    Map<String, Object> question = ((List<Map<String, Object>>) result.get("questions")).getFirst();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> options = (List<Map<String, Object>>) question.get("options");

    assertFalse(question.containsKey("answer_payload"));
    assertEquals(2, options.size());
    assertFalse(options.getFirst().containsKey("is_correct"));
    assertFalse(options.get(1).containsKey("is_correct"));
    assertFalse(question.containsKey("isCorrect"));
    assertFalse(question.containsKey("correctAnswer"));
    assertTrue(Boolean.FALSE.equals(result.get("resultReleased")) || result.containsKey("resultReleased"));
  }

  @Test
  void releasedStudentResultExposesNormalizedCorrectAnswerForFeedbackOnlyAfterRelease() {
    when(authorization.requireStudent(student)).thenReturn(student);
    Map<String, Object> attemptRow = new LinkedHashMap<>();
    attemptRow.put("attempt_id", 6L);
    attemptRow.put("assessment_id", 20L);
    attemptRow.put("student_id", 7L);
    attemptRow.put("attempt_number", 1);
    attemptRow.put("status", "RELEASED");
    attemptRow.put("started_at", OffsetDateTime.now().minusMinutes(5));
    attemptRow.put("deadline_at", OffsetDateTime.now().minusMinutes(1));
    attemptRow.put("submitted_at", OffsetDateTime.now().minusMinutes(1));
    attemptRow.put("graded_at", OffsetDateTime.now().minusMinutes(1));
    attemptRow.put("released_at", OffsetDateTime.now().minusMinutes(1));
    attemptRow.put("auto_score", "0.00");
    attemptRow.put("final_score", "0.00");
    attemptRow.put("max_score", "2.00");
    attemptRow.put("assessment_snapshot", "{}");
    attemptRow.put("student_name", "Preview Student");
    attemptRow.put("class_code", "7");
    attemptRow.put("assessment_title", "Fractions test");
    attemptRow.put("subject_code", "maths");
    attemptRow.put("subject_name", "Mathematics");
    attemptRow.put("chapter_name", "Fractions");
    when(jdbc.queryForList(contains("select att.id as attempt_id"), eq(6L)))
        .thenReturn(List.of(attemptRow));

    Map<String, Object> questionRow = new LinkedHashMap<>();
    questionRow.put("attempt_question_id", 601L);
    questionRow.put("sequence_no", 1);
    questionRow.put("max_marks", "2.00");
    questionRow.put("question_snapshot", """
        {"id":44,"question_type":"MCQ","prompt":"Which answer is correct?",
         "answer_payload":"{\\"kind\\":\\"OPTION\\",\\"value\\":\\"B\\"}",
         "explanation":"Option B is correct.",
         "options":[{"option_key":"A","label":"Option A","is_correct":false},
                    {"option_key":"B","label":"Option B","is_correct":true}]}
        """);
    questionRow.put("answer_id", 6010L);
    questionRow.put("response_payload", "\"A\"");
    questionRow.put("answer_status", "AUTO_GRADED");
    questionRow.put("is_correct", false);
    questionRow.put("awarded_marks", "0.00");
    questionRow.put("teacher_feedback", null);
    when(jdbc.queryForList(contains("select aq.id as attempt_question_id"), eq(6L)))
        .thenReturn(List.of(questionRow));

    Map<String, Object> result = controller.studentAttempt(6L, student);
    @SuppressWarnings("unchecked")
    Map<String, Object> question = ((List<Map<String, Object>>) result.get("questions")).getFirst();
    @SuppressWarnings("unchecked")
    Map<String, Object> correctAnswer = (Map<String, Object>) question.get("correctAnswer");

    assertTrue(Boolean.TRUE.equals(result.get("resultReleased")));
    assertEquals("OPTION", correctAnswer.get("kind"));
    assertEquals("B", correctAnswer.get("value"));
    assertEquals(false, question.get("isCorrect"));
    assertFalse(question.containsKey("answer_payload"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> options = (List<Map<String, Object>>) question.get("options");
    assertFalse(options.get(1).containsKey("is_correct"));
  }


  @Test
  void studentResultsGroupAttemptsAndReturnLatestThreeWithBestScore() {
    when(authorization.requireStudent(student)).thenReturn(student);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("attempt_id", 103L);
    row.put("assessment_id", 20L);
    row.put("attempt_number", 3);
    row.put("status", "RELEASED");
    row.put("submitted_at", OffsetDateTime.now().minusMinutes(5));
    row.put("released_at", OffsetDateTime.now().minusMinutes(4));
    row.put("final_score", "7.00");
    row.put("max_score", "10.00");
    row.put("assessment_type", "CHAPTER_END");
    row.put("assessment_title", "Fractions test");
    row.put("subject_code", "maths");
    row.put("subject_name", "Mathematics");
    row.put("chapter_name", "Fractions");
    row.put("question_count", 5);
    row.put("best_score", "9.00");
    row.put("attempt_count", 3L);
    row.put("attempts_json", """
        [
          {"attempt_id":103,"attempt_number":3,"status":"RELEASED","final_score":7.0,"max_score":10.0},
          {"attempt_id":102,"attempt_number":2,"status":"RELEASED","final_score":9.0,"max_score":10.0},
          {"attempt_id":101,"attempt_number":1,"status":"RELEASED","final_score":6.0,"max_score":10.0}
        ]
        """);
    when(jdbc.queryForList(contains("with ranked as"), eq("hi"), eq("hi"), eq("hi"),
        eq(7L), eq(25), eq(0))).thenReturn(List.of(row));
    when(jdbc.queryForObject(contains("count(distinct att.assessment_id)"), eq(Long.class), eq(7L)))
        .thenReturn(1L);

    Map<String, Object> result = controller.studentResults("hi", 25, 0, student);

    assertEquals(1L, result.get("total"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    assertEquals(1, items.size(), "the assessment should occupy exactly one result row");
    assertEquals("9.00", items.getFirst().get("best_score"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> attempts = (List<Map<String, Object>>) items.getFirst().get("attempts");
    assertEquals(3, attempts.size());
    assertEquals(103, ((Number) attempts.getFirst().get("attempt_id")).intValue());
    assertEquals(102, ((Number) attempts.get(1).get("attempt_id")).intValue());
    assertEquals(101, ((Number) attempts.get(2).get("attempt_id")).intValue());
  }


  @Test
  void leaderboardReturnsTopTwentyAndTheCurrentStudentsRankUsingReleasedBestAttempts() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForObject(contains("join student_track_enrollment ste"), eq(Long.class),
        eq(7L), eq(20L))).thenReturn(1L);
    when(jdbc.queryForList(contains("select a.id as assessment_id"), eq("en"), eq(20L)))
        .thenReturn(List.of(Map.of(
            "assessment_id", 20L,
            "assessment_title", "Fractions test",
            "max_score", new BigDecimal("20.00"))));

    List<Map<String, Object>> rankingRows = new ArrayList<>();
    for (int rank = 1; rank <= 20; rank++) {
      rankingRows.add(Map.of(
          "leaderboard_rank", rank,
          "participant_count", 21L,
          "student_id", 100L + rank,
          "student_name", "Student " + rank,
          "score", new BigDecimal(21 - rank),
          "max_score", new BigDecimal("20.00"),
          "time_taken_seconds", 240L + rank,
          "submitted_at", OffsetDateTime.now()));
    }
    rankingRows.add(Map.of(
        "leaderboard_rank", 21L,
        "participant_count", 21L,
        "student_id", 7L,
        "student_name", "Preview Student",
        "score", new BigDecimal("0.50"),
        "max_score", new BigDecimal("20.00"),
        "time_taken_seconds", 1600L,
        "submitted_at", OffsetDateTime.now()));
    when(jdbc.queryForList(contains("with released_attempts as"), eq(20L), eq(7L)))
        .thenReturn(rankingRows);

    Map<String, Object> result = controller.assessmentLeaderboard(20L, "en", student);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    @SuppressWarnings("unchecked")
    Map<String, Object> currentStudent = (Map<String, Object>) result.get("current_student");

    assertEquals(20, items.size());
    assertEquals(1, ((Number) items.getFirst().get("rank")).intValue());
    assertEquals(20, ((Number) items.getLast().get("rank")).intValue());
    assertFalse(items.getFirst().containsKey("student_id"));
    assertEquals(21L, result.get("participant_count"));
    assertEquals(21, ((Number) currentStudent.get("rank")).intValue());
    assertEquals(true, currentStudent.get("is_current_student"));
    assertFalse(currentStudent.containsKey("student_id"));
  }

  @Test
  void leaderboardDoesNotRevealAssessmentsOutsideTheStudentsEnrolledCatalogue() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForObject(contains("join student_track_enrollment ste"), eq(Long.class),
        eq(7L), eq(999L))).thenReturn(0L);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.assessmentLeaderboard(999L, "en", student));

    assertEquals(404, error.getStatusCode().value());
    verify(jdbc, never()).queryForList(contains("with released_attempts as"), any(Object[].class));
  }

}
