package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TutorTextbookRetrievalTest {
  @Test
  void includesVerifiedTextbookSourceEditionChapterAndPage() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    Map<String, Object> page = Map.of(
        "source_title", "NCERT Mathematics",
        "edition", "2025–26",
        "chapter_title", "Fractions",
        "page_no", 37,
        "excerpt", "Equivalent fractions represent the same part of a whole.");
    doReturn(List.of(), List.of(page))
        .when(jdbc).queryForList(anyString(), any(Object[].class));

    TutorCurriculumRetriever retriever = new TutorCurriculumRetriever(jdbc);
    String context = retriever.retrieve(12L, "en", "Explain equivalent fractions", "Fractions");

    assertTrue(context.contains("NCERT Mathematics"));
    assertTrue(context.contains("edition: 2025–26"));
    assertTrue(context.contains("chapter: Fractions"));
    assertTrue(context.contains("PDF page: 37"));
    assertTrue(context.contains("Equivalent fractions represent the same part"));
  }

  @Test
  void leavesTextbookContextEmptyWhenNoRelevantPageExists() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    doReturn(List.of(), List.of()).when(jdbc).queryForList(anyString(), any(Object[].class));

    TutorCurriculumRetriever retriever = new TutorCurriculumRetriever(jdbc);
    String context = retriever.retrieve(12L, "hi", "समझाइए भिन्न", "भिन्न");

    assertTrue(context.isBlank());
  }
}
