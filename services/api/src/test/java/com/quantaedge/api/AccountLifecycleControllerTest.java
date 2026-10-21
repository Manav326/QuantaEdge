package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AccountLifecycleControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;

  private final AuthContext learner = new AuthContext(90L, 70L, "LEARNER", "Adult Learner");
  private AccountLifecycleController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireStudent(learner)).thenReturn(learner);
    controller = new AccountLifecycleController(jdbc, authorization, staffAudit);
  }

  @Test
  void parentTransitionPreservesTheSameUserAndStudentIdentityAndRevokesSessions() {
    when(jdbc.queryForList(contains("join student_adult_verification"), eq(70L)))
        .thenReturn(List.of(Map.of("user_account_id", 90L, "role", "LEARNER")));
    when(jdbc.update(contains("update user_account set role='PARENT'"), eq(90L))).thenReturn(1);

    Map<String,Object> result = controller.transitionToParent(
        Map.of("confirmParentTransition", true), learner);

    assertTrue(Boolean.TRUE.equals(result.get("transitioned")));
    assertEquals("PARENT", result.get("role"));
    assertEquals(90L, result.get("userId"));
    assertEquals(true, result.get("requiresReauthentication"));
    verify(jdbc).update(contains("update auth_session set revoked_at=now()"), eq(70L), eq(90L));
    verify(jdbc).update(contains("insert into account_lifecycle_audit"), eq(70L), eq(90L), eq(90L), eq(70L));
  }

  @Test
  void parentTransitionRejectsAccountsWithoutCurrentVerifiedEligibility() {
    when(jdbc.queryForList(contains("join student_adult_verification"), eq(70L)))
        .thenReturn(List.of());

    assertThrows(SecurityException.class, () -> controller.transitionToParent(
        Map.of("confirmParentTransition", true), learner));
    verify(jdbc, never()).update(contains("update user_account set role='PARENT'"), any(), any());
  }

  @Test
  void parentTransitionRequiresExplicitConfirmation() {
    assertThrows(SecurityException.class, () -> controller.transitionToParent(
        Map.of("confirmParentTransition", false), learner));
  }
}
