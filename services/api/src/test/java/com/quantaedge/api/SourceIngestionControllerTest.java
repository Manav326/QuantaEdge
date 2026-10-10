package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
class SourceIngestionControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;
  @Mock private SourceIngestionWorker worker;

  private SourceIngestionController controller;
  private final AuthContext reviewer = new AuthContext(1L, null, 9L, "CONTENT_REVIEWER", "Reviewer");

  @BeforeEach
  void setUp() {
    controller = new SourceIngestionController(jdbc, authorization, staffAudit, worker);
  }

  @Test
  void sourceInventoryRequiresContentReviewPermission() {
    when(authorization.requirePermission(null, "CONTENT_REVIEW"))
        .thenThrow(new SecurityException("Permission required: CONTENT_REVIEW"));

    assertThrows(SecurityException.class, () -> controller.listSources(null));
    verifyNoInteractions(jdbc);
  }

  @Test
  void finalApprovalCanExcludeRejectedChapterCandidates() {
    Map<String, Object> approved = Map.of("status", "APPROVED", "chapter_id", 12L);
    Map<String, Object> rejected = Map.of("status", "REJECTED", "chapter_id", 13L);

    List<Map<String, Object>> ready = SourceIngestionController.chaptersReadyForPublishing(
        List.of(approved, rejected));

    assertEquals(1, ready.size());
    assertTrue(ready.contains(approved));
  }

  @Test
  void finalApprovalRequiresEveryChapterToHaveAnExplicitDecision() {
    assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> SourceIngestionController.chaptersReadyForPublishing(
            List.of(Map.of("status", "APPROVED"), Map.of("status", "NEEDS_REVIEW"))));
  }

  @Test
  void finalApprovalRequiresAtLeastOneApprovedChapter() {
    assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> SourceIngestionController.chaptersReadyForPublishing(
            List.of(Map.of("status", "REJECTED"))));
  }

  @Test
  void chapterMappingsAreReadFromExistingSourceRelationshipsAndRelatedPdfAssignments() {
    when(authorization.requirePermission(reviewer, "CONTENT_REVIEW")).thenReturn(reviewer);
    when(jdbc.queryForList(contains("from chapter_source cs"), eq(77L))).thenReturn(List.of());

    List<Map<String, Object>> rows = controller.listExistingChapterMappings(77L, reviewer);

    assertEquals(0, rows.size());
    verify(jdbc).queryForList(
        contains("a.source_content_id=src.id"), eq(77L));
  }
}
