package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AccountDeletionDependencyAssessmentServiceTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void assessmentCountsRuntimeReferencesAndNeverClaimsErasure() {
    UUID requestId = UUID.randomUUID();
    Map<String,Object> request = new LinkedHashMap<>();
    request.put("request_id", requestId);
    request.put("subject_user_id", 101L);
    request.put("subject_user_public_id", "USER00000101");
    request.put("subject_student_id", null);
    request.put("subject_student_public_user_id", null);
    request.put("status", "UNDER_REVIEW");
    request.put("includes_linked_children", false);
    request.put("linked_children_count", 0);
    when(jdbc.queryForList(contains("from account_deletion_request where request_id"), eq(requestId)))
        .thenReturn(List.of(request));
    when(jdbc.queryForList(contains("from account_deletion_request_child"), eq(requestId)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("from student st"), eq(101L), eq(101L)))
        .thenReturn(List.of());
    when(jdbc.queryForList(contains("from pg_constraint"))).thenReturn(List.of(Map.of(
        "source_schema", "public",
        "source_table", "guardian_student",
        "constraint_name", "guardian_student_guardian_user_id_fkey",
        "target_table", "user_account",
        "source_columns", "guardian_user_id",
        "target_columns", "id",
        "column_count", 1L,
        "delete_action", "c"
    )));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

    Map<String,Object> report = new AccountDeletionDependencyAssessmentService(jdbc).assess(requestId);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> dependencies = (List<Map<String,Object>>) report.get("dependencies");
    @SuppressWarnings("unchecked")
    Map<String,Object> summary = (Map<String,Object>) report.get("summary");

    assertEquals("UNDER_REVIEW", report.get("requestStatus"));
    assertEquals(1, summary.get("foreignKeyRelationshipsInspected"));
    assertEquals(1, summary.get("relationshipsWithRows"));
    assertEquals(2L, summary.get("relatedRowReferences"));
    assertEquals("guardian_student", dependencies.getFirst().get("table"));
    assertEquals(2L, dependencies.getFirst().get("rowCount"));
    assertFalse(Boolean.TRUE.equals(report.get("automaticFulfillmentAllowed")));
    assertFalse(Boolean.TRUE.equals(report.get("canMarkCompleted")));
    assertFalse(Boolean.TRUE.equals(report.get("erasureExecuted")));
    assertTrue(Boolean.TRUE.equals(dependencies.getFirst().get("manualReviewRequired")));
  }

  @Test
  void unknownTableClassificationDoesNotMasqueradeAsAApprovedCategory() {
    assertEquals("UNKNOWN", AccountDeletionDependencyAssessmentService.classify("unmapped_identity_blob"));
    assertEquals("CERTIFICATE_RECORD", AccountDeletionDependencyAssessmentService.classify("student_certificate"));
    assertEquals("FINANCIAL_OR_PURCHASE_RECORD", AccountDeletionDependencyAssessmentService.classify("course_payment_order"));
  }
}
