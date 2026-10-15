package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LearningControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private LearningStateService state;
  @Mock private QuestionAnswerService answerService;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private LearningController controller;

  @BeforeEach
  void setUp() {
    lenient().when(authorization.requireStudent(student)).thenReturn(student);
    controller = new LearningController(jdbc, authorization, state, answerService);
  }

  @Test
  void publicQuestionDeliveryDoesNotSelectCorrectAnswerFlags() {
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(42L)))
        .thenReturn(1L);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

    controller.questions(42L, "hi", student);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(7L), eq("hi"), eq(42L));
    assertTrue(sql.getValue().contains("as attempted"));
    assertTrue(sql.getValue().contains("'label',qo.label"));
    assertFalse(sql.getValue().contains("'correct'"));
    assertFalse(sql.getValue().contains("qo.is_correct"));
    assertFalse(sql.getValue().contains("answer_payload"));
  }


  @Test
  void lessonCatalogueIncludesDurableMcqAndPracticeHistoryForEachTopic() {
    when(jdbc.queryForObject("select class_code from student where id=? and active=true", String.class, 7L))
        .thenReturn("7");
    lenient().when(jdbc.queryForList(contains("practice_best_correct_count"), any(Object[].class))).thenReturn(List.of(Map.of(
        "id", 99L, "title", "Fractions", "mcq_count", 3L, "mcq_answered_count", 3L,
        "practice_completed_attempts", 2L, "practice_best_correct_count", 8L,
        "practice_best_graded_count", 10L, "practice_best_percent", 80)));

    List<Map<String,Object>> rows = controller.lessons("7", "maths", "hi", student);

    assertEquals(1, rows.size());
    assertEquals(3L, rows.getFirst().get("mcq_count"));
    assertEquals(3L, rows.getFirst().get("mcq_answered_count"));
    assertEquals(2L, rows.getFirst().get("practice_completed_attempts"));
    assertEquals(8L, rows.getFirst().get("practice_best_correct_count"));
    assertEquals(10L, rows.getFirst().get("practice_best_graded_count"));
    assertEquals(80, rows.getFirst().get("practice_best_percent"));
    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(7L), eq("hi"), eq("hi"), eq("hi"), eq("hi"), eq("7"), eq("maths"));
    assertTrue(sql.getValue().contains("student_question_attempt"));
    assertTrue(sql.getValue().contains("student_practice_session"));
    assertTrue(sql.getValue().contains("status='COMPLETED'"));
    assertTrue(sql.getValue().contains("status='IN_PROGRESS'"));
  }

  @Test
  void lessonBlocksStripAnswerKeysRecursively() {
    when(jdbc.queryForList(contains("select l.id,l.code,coalesce(lt.title,l.title) as title"), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "class_code", "7", "subject_code", "maths",
            "chapter_code", "algebraic-expressions", "title", "Expressions",
            "class_translated", true, "lesson_translated", true, "chapter_translated", true,
            "subject_translated", true, "objective_translated", true)));
    when(jdbc.queryForList(contains("strip_answer_keys(coalesce(bt.content,b.content))::text as content"), eq("hi"), eq(42L))).thenReturn(List.of());
    when(jdbc.queryForList(contains("select q.id,q.question_type"), any(Object[].class))).thenReturn(List.of());

    Map<String, Object> lesson = controller.lesson(42L, "hi", student);

    verify(jdbc).queryForList(contains("strip_answer_keys(coalesce(bt.content,b.content))::text as content"), eq("hi"), eq(42L));
    verify(jdbc).queryForList(contains("order by b.sequence_no,b.id"), eq("hi"), eq(42L));
    assertTrue(lesson.containsKey("blocks"));
    assertTrue(lesson.containsKey("questions"));
    assertEquals(true, lesson.get("translation_complete"));
    assertEquals(List.of(), lesson.get("translation_gaps"));
    assertEquals(0L, ((Map<?,?>) lesson.get("translation_coverage")).get("blocks_published"));
  }

  @Test
  void answerIsGradedServerSideAndPersistedWithoutExposingAnswerPayload() {
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq("hi"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "MCQ",
            "explanation", "Subtract four from both sides.", "answer_payload", "{\"kind\":\"OPTION\",\"value\":\"B\"}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(1L);
    when(answerService.evaluate(anyString(), eq("B"))).thenReturn(
        new QuestionAnswerService.Evaluation(true, true, "OPTION"));

    Map<String, Object> result = controller.answer(42L, "hi", Map.of("answer", "B"), student);

    assertEquals(true, result.get("correct"));
    assertEquals(true, result.get("autoGraded"));
    assertEquals("Subtract four from both sides.", result.get("explanation"));
    assertFalse(result.containsKey("answer_payload"));
    assertFalse(result.containsKey("correctAnswer"));
    verify(state).recordAttempt(7L, 42L, 9L, "B", true, true);
  }

  @Test
  void answerRejectsQuestionsOutsideThePublishedStudentTrack() {
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq("hi"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "MCQ",
            "explanation", "Explanation", "answer_payload", "{\"kind\":\"OPTION\",\"value\":\"B\"}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(0L);

    assertThrows(SecurityException.class,
        () -> controller.answer(42L, "hi", Map.of("answer", "Z"), student));
    verify(state, never()).recordAttempt(eq(7L), eq(42L), eq(9L),
        anyString(), anyBoolean(), any());
  }

  @Test
  void nullAnswerIsHandledAsAnEmptyAnswerInsteadOfThrowing() {
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq("hi"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "MCQ",
            "explanation", "Explanation", "answer_payload", "{}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(1L);
    when(answerService.evaluate("{}", "")).thenReturn(
        new QuestionAnswerService.Evaluation(false, null, ""));

    Map<String, Object> body = new java.util.HashMap<>();
    body.put("answer", null);

    Map<String, Object> result = controller.answer(42L, "hi", body, student);

    assertEquals(42L, result.get("questionId"));
    verify(state).recordAttempt(eq(7L), eq(42L), eq(9L), eq(""), eq(false), isNull());
  }

  @Test
  void practiceAnswerLocksTheSessionQuestionBeforeGrading() {
    when(jdbc.queryForList(contains("for update of psq,ps"), eq(5L), eq(7L), eq(42L)))
        .thenReturn(List.of(Map.of("question_id", 42L)));
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq("hi"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "MCQ",
            "explanation", "Explanation", "answer_payload", "{}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(1L);
    when(answerService.evaluate("{}", "A")).thenReturn(
        new QuestionAnswerService.Evaluation(true, true, "OPTION"));

    Map<String, Object> result = controller.answer(42L, "hi",
        Map.of("answer", "A", "practiceSessionId", 5L), student);

    assertEquals(true, result.get("correct"));
    verify(jdbc).queryForList(contains("for update of psq,ps"), eq(5L), eq(7L), eq(42L));
    verify(state).recordAttempt(7L, 42L, 9L, "A", true, true, 5L);
  }

  @Test
  void structuredOrderPracticeAnswerIsSerializedBeforePersistence() {
    List<String> submitted = List.of("A", "B");
    when(jdbc.queryForList(contains("for update of psq,ps"), eq(5L), eq(7L), eq(42L)))
        .thenReturn(List.of(Map.of("question_id", 42L)));
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq("hi"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "ORDER",
            "explanation", "Put the steps in order.",
            "answer_payload", "{\"kind\":\"ORDER\",\"value\":[\"A\",\"B\"]}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(1L);
    when(answerService.serializeForPersistence(eq(submitted))).thenReturn("[\"A\",\"B\"]");
    when(answerService.evaluate(anyString(), eq(submitted))).thenReturn(
        new QuestionAnswerService.Evaluation(true, true, "ORDER"));

    Map<String, Object> result = controller.answer(42L, "hi",
        Map.of("answer", submitted, "practiceSessionId", 5L), student);

    assertEquals(true, result.get("correct"));
    verify(answerService).serializeForPersistence(eq(submitted));
    verify(state).recordAttempt(7L, 42L, 9L, "[\"A\",\"B\"]", true, true, 5L);
  }

  @Test
  void anonymousRequestsAreRejectedBeforeReadingQuestions() {
    when(authorization.requireStudent(null)).thenThrow(new SecurityException("Authentication required"));

    assertThrows(SecurityException.class, () -> controller.questions(42L, "hi", null));
    verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
  }
}
