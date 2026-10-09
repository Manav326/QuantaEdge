package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AdminContentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private ObjectMapper mapper;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;

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
