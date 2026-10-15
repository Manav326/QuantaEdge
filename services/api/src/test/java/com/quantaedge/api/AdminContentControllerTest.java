package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class AdminContentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private ObjectMapper mapper;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;
  @Mock private QuestionHistoryService questionHistory;
  @Mock private AssessmentCatalogService assessmentCatalog;


  @Test
  void blockReadinessMatchesContentRenderedToLearners() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    assertTrue(controller.blockHasPublishableContent(
        "EXPLANATION", java.util.Map.of("description", "Visible explanation")));
    assertFalse(controller.blockHasPublishableContent(
        "EXPLANATION", java.util.Map.of("text", "Legacy text that the learner renderer ignores")));
    assertTrue(controller.blockHasPublishableContent(
        "GUIDED_PRACTICE", java.util.Map.of("body", "Try this step")));
    assertFalse(controller.blockHasPublishableContent(
        "GUIDED_PRACTICE", java.util.Map.of("instructions", "Not rendered as a practice prompt")));
    assertTrue(controller.blockHasPublishableContent(
        "WORKED_EXAMPLE", java.util.Map.of(
            "prompt", "Find the sum", "steps", java.util.List.of("Add the numbers"), "answer", "12")));
  }

  @Test
  void learnerPathRequiresExplanationPracticeAndRecap() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.validateLearnerLessonStages(Set.of("EXPLANATION", "GUIDED_PRACTICE")));

    assertTrue(error.getReason().contains("Recap"));
  }

  @Test
  void learnerPathAcceptsIndependentPracticeAndRecapAlias() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    assertDoesNotThrow(() -> controller.validateLearnerLessonStages(
        Set.of("EXPLANATION", "INDEPENDENT_PRACTICE", "RECAP")));
  }

  @Test
  void learnerPathDoesNotAcceptPracticeWithoutCoreExplanation() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.validateLearnerLessonStages(
            Set.of("WORKED_EXAMPLE", "INDEPENDENT_PRACTICE", "SUMMARY")));

    assertTrue(error.getReason().contains("Explanation"));
  }

  @Test
  void previewConfirmationRejectsAStaleContentRevision() {
    AuthContext author = new AuthContext(null, null, 42L, "CONTENT_AUTHOR", "Content author");
    when(jdbc.queryForObject("select content_revision from lesson where id=?", Long.class, 7L))
        .thenReturn(12L);
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.markLessonPreviewChecked(7L, java.util.Map.of("contentRevision", 11L), author));

    assertTrue(error.getReason().contains("changed after this preview loaded"));
  }

  @Test
  void questionReviewRequiresReviewerPermissionBeforeReadingOrChangingAQuestion() {
    AuthContext author = new AuthContext(100L, null, 42L, "CONTENT_AUTHOR", "Content author");
    when(authorization.requirePermission(author, "CONTENT_REVIEW"))
        .thenThrow(new SecurityException("Question review permission required"));
    AdminContentController controller =
        new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    assertThrows(SecurityException.class,
        () -> controller.reviewQuestion(7L, 9L, java.util.Map.of("status", "APPROVED"), author));

    verify(authorization).requirePermission(author, "CONTENT_REVIEW");
    verifyNoInteractions(jdbc, questionHistory, staffAudit);
  }

  @Test
  void questionReviewSummaryCountsAllStatusesAndSeparatesInactiveQuestions() {
    AuthContext reviewer = new AuthContext(100L, null, 42L, "CONTENT_AUTHOR", "Content author");
    when(authorization.requireAuth(reviewer)).thenReturn(reviewer);
    when(authorization.hasPermission(reviewer, "CONTENT_VIEW")).thenReturn(true);
    when(jdbc.queryForList(anyString())).thenReturn(java.util.List.of(
        Map.of("review_status", "DRAFT", "active", true, "count", 4L),
        Map.of("review_status", "REVIEW", "active", true, "count", 3L),
        Map.of("review_status", "REJECTED", "active", true, "count", 2L),
        Map.of("review_status", "APPROVED", "active", true, "count", 8L),
        Map.of("review_status", "PUBLISHED", "active", true, "count", 5L),
        Map.of("review_status", "DRAFT", "active", false, "count", 6L)));

    AdminContentController controller =
        new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    Map<String,Object> summary = controller.questionReviewSummary(reviewer);

    assertEquals(28L, summary.get("totalQuestions"));
    assertEquals(22L, summary.get("activeQuestions"));
    assertEquals(6L, summary.get("inactiveQuestions"));
    assertEquals(6, ((java.util.List<?>)summary.get("byStatus")).size());
  }

  @Test
  void textbookVisualPublicationRequiresClassificationRightsAltAndProvenance() {
    Map<String,Object> candidate = new java.util.LinkedHashMap<>();
    candidate.put("source_candidate_id", "v3-image-abcd1234");
    candidate.put("visual_kind", "DIAGRAM");
    candidate.put("visual_review_status", "APPROVED");
    candidate.put("rights_status", "LICENSE_CONFIRMED");
    candidate.put("alt", "A labelled diagram showing the parts of a flower.");
    candidate.put("source_book_id", "ncert-c6-english-fecu1");
    candidate.put("source_pdf_sha256", "a".repeat(64));
    candidate.put("source_asset_sha256", "b".repeat(64));
    candidate.put("source_pdf_page_number", 31);
    candidate.put("url", "https://raw.githubusercontent.com/Manav326/QuantaEdge/main/visual.jpg");

    assertNull(AdminContentController.visualPublicationValidationError("IMAGE", candidate));

    candidate.put("visual_review_status", "NEEDS_REVIEW");
    assertTrue(AdminContentController.visualPublicationValidationError("IMAGE", candidate).contains("approve"));

    candidate.put("visual_review_status", "APPROVED");
    candidate.put("rights_status", "SOURCE_REFERENCE_ONLY");
    assertTrue(AdminContentController.visualPublicationValidationError("IMAGE", candidate).contains("rights"));

    candidate.put("rights_status", "LICENSE_CONFIRMED");
    candidate.put("visual_kind", "TEXTBOOK_FIGURE");
    assertTrue(AdminContentController.visualPublicationValidationError("IMAGE", candidate).contains("Classify"));

    candidate.put("visual_kind", "DIAGRAM");
    candidate.put("alt", "");
    assertTrue(AdminContentController.visualPublicationValidationError("IMAGE", candidate).contains("alternative text"));

    candidate.put("alt", "A labelled diagram.");
    candidate.put("source_pdf_sha256", "bad-hash");
    assertTrue(AdminContentController.visualPublicationValidationError("IMAGE", candidate).contains("provenance"));
  }

  @Test
  void legacyMediaBlocksWithoutVisualCandidateMetadataKeepExistingRules() {
    assertNull(AdminContentController.visualPublicationValidationError(
        "IMAGE", Map.of("url", "https://example.com/image.png", "description", "Existing lesson image")));
  }

  @Test
  void numericAnswerKeyRequiresValidExpectedNumberAndNonNegativeTolerance() {
    assertNull(AdminContentController.numericAnswerKeyValidationError(
        Map.of("kind", "NUMERIC", "value", "10.5", "tolerance", "0.25")));
    assertNull(AdminContentController.numericAnswerKeyValidationError(
        Map.of("kind", "NUMERIC", "value", new BigDecimal("10.5"))));
    assertTrue(AdminContentController.numericAnswerKeyValidationError(
        Map.of("kind", "NUMERIC", "value", "ten")).contains("valid number"));
    assertTrue(AdminContentController.numericAnswerKeyValidationError(
        Map.of("kind", "NUMERIC", "value", "10", "tolerance", "-0.1")).contains("cannot be negative"));
    assertTrue(AdminContentController.numericAnswerKeyValidationError(
        Map.of("kind", "OPTION", "value", "A")).contains("numeric answer key"));
  }

  @Test
  void legacySharedTokenCannotAuthorizeContentCms() {
    when(authorization.requirePermission(null, "CONTENT_VIEW")).thenThrow(new SecurityException("View permission required"));
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    assertThrows(SecurityException.class,
        () -> controller.chapter(7L, "legacy-shared-token", null));

    verify(authorization).requirePermission(null, "CONTENT_VIEW");
    verifyNoInteractions(jdbc);
  }

  @Test
  void studentSessionCannotReadAdminContentEvenWithLegacyToken() {
    AuthContext student = new AuthContext(10L, 20L, "STUDENT", "Student");
    when(authorization.requirePermission(student, "CONTENT_VIEW")).thenThrow(new SecurityException("View permission required"));
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit, questionHistory, assessmentCatalog);

    assertThrows(SecurityException.class,
        () -> controller.chapter(7L, "legacy-shared-token", student));

    verify(authorization).requirePermission(student, "CONTENT_VIEW");
    verifyNoInteractions(jdbc);
  }
}
