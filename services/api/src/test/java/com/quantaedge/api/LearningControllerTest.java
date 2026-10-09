package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LearningControllerTest {
  @Mock
  private JdbcTemplate jdbc;

  @Test
  void publicQuestionDeliveryDoesNotSelectCorrectAnswerFlags() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    LearningController controller = new LearningController(jdbc);

    controller.questions(42L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(42L));
    assertTrue(sql.getValue().contains("'label', qo.label"));
    assertFalse(sql.getValue().contains("'correct'"));
    assertFalse(sql.getValue().contains("qo.is_correct"));
  }

  @Test
  void answerIsGradedServerSideAndSavedForLocalPreviewStudent() {
    when(jdbc.queryForList(contains("from student"))).thenReturn(List.of(Map.of("id", 7L)));
    when(jdbc.queryForList(contains("select q.id, q.explanation"), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "id", 42L,
            "explanation", "Subtract four from both sides.",
            "correct", true,
            "option_exists", true)));
    when(jdbc.update(contains("insert into student_question_attempt"), any(Object[].class))).thenReturn(1);

    LearningController controller = new LearningController(jdbc);
    Map<String, Object> result = controller.answer(42L, Map.of("selectedOption", "B"));

    assertEquals(true, result.get("correct"));
    assertEquals(true, result.get("saved"));
    assertEquals("Subtract four from both sides.", result.get("explanation"));
    verify(jdbc).update(contains("insert into student_question_attempt"), eq(7L), eq(42L), eq("B"), eq(true));
  }

  @Test
  void answerRejectsAnOptionThatDoesNotBelongToTheQuestion() {
    when(jdbc.queryForList(contains("from student"))).thenReturn(List.of(Map.of("id", 7L)));
    when(jdbc.queryForList(contains("select q.id, q.explanation"), any(Object[].class)))
        .thenReturn(List.of(Map.of(
            "id", 42L,
            "explanation", "Explanation",
            "correct", false,
            "option_exists", false)));

    LearningController controller = new LearningController(jdbc);
    assertThrows(IllegalArgumentException.class,
        () -> controller.answer(42L, Map.of("selectedOption", "Z")));
    verify(jdbc, never()).update(contains("insert into student_question_attempt"), any(Object[].class));
  }
}
