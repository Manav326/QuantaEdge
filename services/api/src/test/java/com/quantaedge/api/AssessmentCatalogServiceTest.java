package com.quantaedge.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AssessmentCatalogServiceTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private QuestionHistoryService questionHistory;

  private AssessmentCatalogService service;

  @BeforeEach
  void setUp() {
    service = new AssessmentCatalogService(jdbc, questionHistory);
  }

  @Test
  void chapterRefreshArchivesGeneratedTestsAfterChapterUnpublish() {
    when(jdbc.queryForList(contains("select id from lesson where chapter_id=?"), eq(Long.class), eq(9L)))
        .thenReturn(List.of(41L));
    when(jdbc.queryForList(contains("select l.id as lesson_id"), eq(41L)))
        .thenReturn(List.of(Map.ofEntries(
            Map.entry("lesson_id", 41L),
            Map.entry("lesson_title", "Fractions"),
            Map.entry("lesson_status", "PUBLISHED"),
            Map.entry("lesson_active", true),
            Map.entry("chapter_id", 9L),
            Map.entry("chapter_name", "Fractions"),
            Map.entry("chapter_status", "DRAFT"),
            Map.entry("chapter_active", true),
            Map.entry("subject_id", 3L),
            Map.entry("subject_name", "Mathematics"),
            Map.entry("subject_active", true),
            Map.entry("class_active", true))));
    service.refreshGeneratedAssessmentsForChapter(9L);

    verify(jdbc).queryForList(contains("select l.id as lesson_id"), eq(41L));
    verify(jdbc).queryForList(contains("select id from assessment where assessment_type=? and is_generated=true and is_current=true and lesson_id=?"),
        eq("TOPIC_END"), eq(41L));
    verify(jdbc).queryForList(contains("select id from assessment where assessment_type=? and is_generated=true and is_current=true and chapter_id=?"),
        eq("CHAPTER_END"), eq(9L));
  }

  @Test
  void doesNotMaterializeTopicOrChapterTestsBelowTheirQuestionMinimums() {
    Map<String,Object> lesson = Map.of(
        "lesson_id", 41L,
        "lesson_title", "Fractions",
        "chapter_id", 9L,
        "chapter_name", "Fractions",
        "subject_id", 3L,
        "subject_name", "Mathematics");

    when(jdbc.queryForList(contains("select l.id as lesson_id"), eq("6")))
        .thenReturn(List.of(lesson));
    when(jdbc.queryForList(contains("select q.id from question q"), eq(Long.class), eq(41L), eq(10)))
        .thenReturn(List.of(101L, 102L));
    when(jdbc.queryForList(contains("select eligible.id from"), eq(Long.class), eq(9L), eq(20)))
        .thenReturn(List.of(101L, 102L, 103L, 104L));

    when(jdbc.queryForList(contains("select id from assessment where assessment_type=? and is_generated=true and is_current=true and lesson_id=?"),
        eq("TOPIC_END"), eq(41L))).thenReturn(List.of());
    when(jdbc.queryForList(contains("select id from assessment where assessment_type=? and is_generated=true and is_current=true and chapter_id=?"),
        eq("CHAPTER_END"), eq(9L))).thenReturn(List.of());

    service.ensureGeneratedAssessmentsForClass("6");

    verify(jdbc, never()).queryForObject(
        contains("insert into assessment("), eq(Long.class), any(Object[].class));
    verify(jdbc, never()).update(contains("insert into assessment_question"), any(), any(), any(), any(), any());
  }
}
