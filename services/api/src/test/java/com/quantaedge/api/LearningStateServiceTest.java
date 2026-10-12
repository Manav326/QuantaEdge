package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
class LearningStateServiceTest {
  @Mock private JdbcTemplate jdbc;

  private LearningStateService service;

  @BeforeEach
  void setUp() {
    service = new LearningStateService(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("jsonb_array_elements(ps.topic_selections)")) {
        return List.of(Map.of("lesson_id", 9L));
      }
      return List.of(Map.of("question_id", 42L));
    });
  }

  @Test
  void practiceAnswerProgressSyncAvoidsJsonbOperatorBeingParsedAsBindParameter() {
    // Every question type uses this same persistence path. Keep this answer ungraded so
    // the test exercises the shared session/progress writes without requiring mastery data.
    service.recordAttempt(7L, 42L, 9L, "written response", false, null, 5L);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeastOnce()).queryForList(sql.capture(), any(Object[].class));

    String sourceLessonQuery = sql.getAllValues().stream()
        .filter(statement -> statement.contains("jsonb_array_elements(ps.topic_selections)"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected the practice session source-lesson query."));

    assertTrue(sourceLessonQuery.contains("jsonb_exists(item, 'sourceLessonId')"));
    assertFalse(sourceLessonQuery.contains("item ? 'sourceLessonId'"));
    verify(jdbc).update(
        org.mockito.ArgumentMatchers.contains("insert into student_question_attempt"),
        any(), any(), any(), any(), any(), any(), any());
  }
}
