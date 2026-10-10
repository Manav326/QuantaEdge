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

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private final AuthContext author = new AuthContext(100L, null, 42L, "CONTENT_AUTHOR", "Author");
  private final AuthContext admin = new AuthContext(101L, null, 99L, "ADMIN", "Administrator");
  private AssessmentController controller;

  @BeforeEach
  void setUp() {
    controller = new AssessmentController(
        jdbc, authorization, questionHistory, answerService, staffAudit, new ObjectMapper());
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
    assertTrue(Boolean.FALSE.equals(result.get("resultReleased")) || result.containsKey("resultReleased"));
  }
}
