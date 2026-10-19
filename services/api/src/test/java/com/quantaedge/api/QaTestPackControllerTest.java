package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class QaTestPackControllerTest {
  @Mock private AuthorizationService authorization;
  @Mock private QaTestPackService qa;

  @Test
  void rejectsUnauthenticatedStatusRequestsAtTheServerBoundary() {
    when(authorization.requirePermission(null, "QE_PLATFORM_OWNER"))
        .thenThrow(new SecurityException("Authentication required."));
    QaTestPackController controller = new QaTestPackController(authorization, qa);

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.status(null));

    assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    verify(authorization).requirePermission(null, "QE_PLATFORM_OWNER");
  }

  @Test
  void ownerStatusRequestUsesServerGateRatherThanClientSuppliedTarget() {
    AuthContext owner = new AuthContext(9L, null, 11L, "ADMIN", "QA Owner", "HRMS");
    Map<String,Object> expected = Map.of(
        "featureEnabled", false,
        "writeAllowed", false,
        "reason", "APP_QA_ENABLED is false; QA mutations are disabled by default.",
        "configuredTargetDatabase", "quantaedge_qa");
    when(authorization.requirePermission(owner, "QE_PLATFORM_OWNER")).thenReturn(owner);
    when(qa.environmentStatus()).thenReturn(expected);
    QaTestPackController controller = new QaTestPackController(authorization, qa);

    Map<String,Object> actual = controller.status(owner);

    assertSame(expected, actual);
    verify(authorization).requirePermission(owner, "QE_PLATFORM_OWNER");
  }
}
