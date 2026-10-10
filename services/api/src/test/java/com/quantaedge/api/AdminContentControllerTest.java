package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import java.util.Set;

@ExtendWith(MockitoExtension.class)
class AdminContentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private ObjectMapper mapper;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;


  @Test
  void learnerPathRequiresExplanationPracticeAndRecap() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.validateLearnerLessonStages(Set.of("EXPLANATION", "GUIDED_PRACTICE")));

    assertTrue(error.getReason().contains("Recap"));
  }

  @Test
  void learnerPathAcceptsIndependentPracticeAndRecapAlias() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit);

    assertDoesNotThrow(() -> controller.validateLearnerLessonStages(
        Set.of("EXPLANATION", "INDEPENDENT_PRACTICE", "RECAP")));
  }

  @Test
  void learnerPathDoesNotAcceptPracticeWithoutCoreExplanation() {
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit);

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
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.markLessonPreviewChecked(7L, java.util.Map.of("contentRevision", 11L), author));

    assertTrue(error.getReason().contains("changed after this preview loaded"));
  }

  @Test
  void legacySharedTokenCannotAuthorizeContentCms() {
    when(authorization.requirePermission(null, "CONTENT_VIEW")).thenThrow(new SecurityException("View permission required"));
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit);

    assertThrows(SecurityException.class,
        () -> controller.chapter(7L, "legacy-shared-token", null));

    verify(authorization).requirePermission(null, "CONTENT_VIEW");
    verifyNoInteractions(jdbc);
  }

  @Test
  void studentSessionCannotReadAdminContentEvenWithLegacyToken() {
    AuthContext student = new AuthContext(10L, 20L, "STUDENT", "Student");
    when(authorization.requirePermission(student, "CONTENT_VIEW")).thenThrow(new SecurityException("View permission required"));
    AdminContentController controller = new AdminContentController(jdbc, mapper, authorization, staffAudit);

    assertThrows(SecurityException.class,
        () -> controller.chapter(7L, "legacy-shared-token", student));

    verify(authorization).requirePermission(student, "CONTENT_VIEW");
    verifyNoInteractions(jdbc);
  }
}
