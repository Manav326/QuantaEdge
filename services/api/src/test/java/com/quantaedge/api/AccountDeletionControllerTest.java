package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
class AccountDeletionControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;
  @Mock private AccountDeletionDependencyAssessmentService dependencyAssessment;

  private final AuthContext parent = new AuthContext(101L, null, null, "PARENT", "Parent", "PARENT");
  private AccountDeletionController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireAuth(parent)).thenReturn(parent);
    controller = new AccountDeletionController(jdbc, authorization, staffAudit);
  }


  @Test
  void dependencyAssessmentIsReviewerOnlyAndNeverClaimsErasure() {
    AuthContext reviewer = new AuthContext(null, null, 77L, "ADMIN", "Reviewer", "EDUCATION_ADMIN");
    java.util.UUID requestId = java.util.UUID.randomUUID();
    Map<String,Object> report = Map.of(
        "requestId", requestId.toString(),
        "automaticFulfillmentAllowed", false,
        "canMarkCompleted", false,
        "erasureExecuted", false,
        "summary", Map.of("affectedRelationships", 3));
    when(authorization.requirePermission(reviewer, "ACCOUNT_LIFECYCLE_REVIEW")).thenReturn(reviewer);
    when(dependencyAssessment.assess(requestId)).thenReturn(report);
    AccountDeletionController reviewerController =
        new AccountDeletionController(jdbc, authorization, staffAudit, dependencyAssessment);

    Map<String,Object> result = reviewerController.dependencyAssessment(requestId, reviewer);

    assertEquals(false, result.get("automaticFulfillmentAllowed"));
    assertEquals(false, result.get("canMarkCompleted"));
    assertEquals(false, result.get("erasureExecuted"));
    verify(dependencyAssessment).assess(requestId);
    verify(staffAudit).recordAction(eq(reviewer), contains("/assessment"), contains("affected FK relationships=3"));
  }

  @Test
  void requiresExplicitConfirmationBeforeCreatingDeletionRequest() {
    when(jdbc.queryForList(contains("select id,public_user_id,role"), eq(101L)))
        .thenReturn(List.of(Map.of("id", 101L, "public_user_id", "USER00000101", "role", "PARENT")));
    when(jdbc.queryForList(contains("select id,public_user_id from student"), eq(101L)))
        .thenReturn(List.of());

    boolean rejected = false;
    try {
      controller.requestDeletion(Map.of("confirmation", "DELETE ACCOUNT", "source", "WEB"), parent);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }

    assertTrue(rejected);
  }

  @Test
  void requiresAnExplicitDecisionForLinkedChildren() {
    when(jdbc.queryForList(contains("select id,public_user_id,role"), eq(101L)))
        .thenReturn(List.of(Map.of("id", 101L, "public_user_id", "USER00000101", "role", "PARENT")));
    when(jdbc.queryForList(contains("select id,public_user_id from student"), eq(101L)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("from account_deletion_request"), eq(101L)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("select distinct st.id as student_id"), eq(101L), eq(101L)))
        .thenReturn(List.of(Map.of("student_id", 202L, "public_user_id", "CHILD0000202")));

    Map<String,Object> result = controller.requestDeletion(
        Map.of("confirmation", "DELETE", "source", "WEB", "includeLinkedChildren", false), parent);

    assertFalse(Boolean.TRUE.equals(result.get("accepted")));
    assertTrue(Boolean.TRUE.equals(result.get("requiresLinkedChildrenConfirmation")));
    assertEquals(1, result.get("linkedChildrenCount"));
  }

  @Test
  void acceptedRequestIsRecordedForTheAuthenticatedAccount() {
    when(jdbc.queryForList(contains("select id,public_user_id,role"), eq(101L)))
        .thenReturn(List.of(Map.of("id", 101L, "public_user_id", "USER00000101", "role", "PARENT")));
    when(jdbc.queryForList(contains("select id,public_user_id from student"), eq(101L)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("from account_deletion_request"), eq(101L)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("select distinct st.id as student_id"), eq(101L), eq(101L)))
        .thenReturn(List.of());

    Map<String,Object> result = controller.requestDeletion(
        Map.of("confirmation", "DELETE", "source", "WEB", "includeLinkedChildren", false), parent);

    assertTrue(Boolean.TRUE.equals(result.get("accepted")));
    assertEquals("REQUESTED", result.get("status"));
    verify(jdbc).update(contains("insert into account_deletion_request"),
        any(), any(), any(), any(), any(), any(), any(), any());
  }
}
