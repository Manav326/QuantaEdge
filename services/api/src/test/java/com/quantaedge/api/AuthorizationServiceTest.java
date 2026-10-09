package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void adminStaffIdentityReceivesCatalogPermissionsWithoutGrantLookup() {
    AuthorizationService authorization = new AuthorizationService(jdbc);
    AuthContext admin = new AuthContext(null, null, 7L, "ADMIN", "Admin");

    assertTrue(authorization.hasPermission(admin, "CONTENT_PUBLISH"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void employeeMustHaveAnExplicitActivePermissionGrant() {
    AuthorizationService authorization = new AuthorizationService(jdbc);
    AuthContext author = new AuthContext(null, null, 19L, "CONTENT_AUTHOR", "Author");
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(19L), eq("CONTENT_EDIT")))
        .thenReturn(true);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(19L), eq("CONTENT_PUBLISH")))
        .thenReturn(false);

    assertTrue(authorization.hasPermission(author, "CONTENT_EDIT"));
    assertFalse(authorization.hasPermission(author, "CONTENT_PUBLISH"));
    verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq(19L), eq("CONTENT_EDIT"));
    verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq(19L), eq("CONTENT_PUBLISH"));
  }

  @Test
  void parentAndStudentIdentitiesCannotBorrowStaffPermissions() {
    AuthorizationService authorization = new AuthorizationService(jdbc);
    AuthContext parent = new AuthContext(12L, null, "PARENT", "Parent");
    AuthContext student = new AuthContext(12L, 21L, "STUDENT", "Student");

    assertFalse(authorization.hasPermission(parent, "CONTENT_VIEW"));
    assertFalse(authorization.hasPermission(student, "CONTENT_REVIEW"));
    assertThrows(SecurityException.class, () -> authorization.requirePermission(parent, "CONTENT_VIEW"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void missingIdentityCannotPassAPermissionCheck() {
    AuthorizationService authorization = new AuthorizationService(jdbc);

    assertFalse(authorization.hasPermission(null, "CONTENT_VIEW"));
    assertThrows(SecurityException.class, () -> authorization.requirePermission(null, "CONTENT_PUBLISH"));
    verifyNoInteractions(jdbc);
  }
}
