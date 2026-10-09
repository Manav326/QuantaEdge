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
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
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

    controller.questions(42L, student);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(42L));
    assertTrue(sql.getValue().contains("'label',qo.label"));
    assertFalse(sql.getValue().contains("'correct'"));
    assertFalse(sql.getValue().contains("qo.is_correct"));
    assertFalse(sql.getValue().contains("answer_payload"));
  }

  @Test
  void lessonBlocksStripAnswerKeysRecursively() {
    when(jdbc.queryForList(contains("select l.id,l.code,l.title"), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "class_code", "7", "subject_code", "maths",
            "chapter_code", "algebraic-expressions", "title", "Expressions")));
    when(jdbc.queryForList(contains("strip_answer_keys(b.content)"), eq(42L))).thenReturn(List.of());
    when(jdbc.queryForList(contains("select q.id,q.question_type"), eq(42L))).thenReturn(List.of());

    Map<String, Object> lesson = controller.lesson(42L, student);

    verify(jdbc).queryForList(contains("strip_answer_keys(content)"), eq(42L));
    assertTrue(lesson.containsKey("blocks"));
    assertTrue(lesson.containsKey("questions"));
  }

  @Test
  void answerIsGradedServerSideAndPersistedWithoutExposingAnswerPayload() {
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "MCQ",
            "explanation", "Subtract four from both sides.", "answer_payload", "{\"kind\":\"OPTION\",\"value\":\"B\"}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(1L);
    when(answerService.evaluate(anyString(), eq("B"))).thenReturn(
        new QuestionAnswerService.Evaluation(true, true, "OPTION"));

    Map<String, Object> result = controller.answer(42L, Map.of("answer", "B"), student);

    assertEquals(true, result.get("correct"));
    assertEquals(true, result.get("autoGraded"));
    assertEquals("Subtract four from both sides.", result.get("explanation"));
    assertFalse(result.containsKey("answer_payload"));
    assertFalse(result.containsKey("correctAnswer"));
    verify(state).recordAttempt(7L, 42L, 9L, "B", true, true);
  }

  @Test
  void answerRejectsQuestionsOutsideThePublishedStudentTrack() {
    when(jdbc.queryForList(contains("select q.id,q.lesson_id,q.question_type"), eq(42L)))
        .thenReturn(List.of(Map.of(
            "id", 42L, "lesson_id", 9L, "question_type", "MCQ",
            "explanation", "Explanation", "answer_payload", "{\"kind\":\"OPTION\",\"value\":\"B\"}")));
    when(jdbc.queryForObject(contains("select count(*) from lesson l"), eq(Long.class), eq(7L), eq(9L)))
        .thenReturn(0L);

    assertThrows(SecurityException.class,
        () -> controller.answer(42L, Map.of("answer", "Z"), student));
    verify(state, never()).recordAttempt(eq(7L), eq(42L), eq(9L),
        anyString(), anyBoolean(), any());
  }

  @Test
  void anonymousRequestsAreRejectedBeforeReadingQuestions() {
    when(authorization.requireStudent(null)).thenThrow(new SecurityException("Authentication required"));

    assertThrows(SecurityException.class, () -> controller.questions(42L, null));
    verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
  }
}
