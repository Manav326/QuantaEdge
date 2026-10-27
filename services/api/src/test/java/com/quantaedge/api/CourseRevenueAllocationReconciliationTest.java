package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CourseRevenueAllocationReconciliationTest {
  @Test
  void reconcilesSharedInstitutionAndSourceRemainder() {
    Map<String,Object> snapshot = snapshot(
        List.of(recipient(11L,"INSTITUTION",2L,"PERCENTAGE",1_000,0L,"Sunrise Public School")),
        "INSTITUTION",1L,"Quanta Edge");
    List<Map<String,Object>> allocations = List.of(
        allocation(100L,7L,"INSTITUTION",2L,10_000L,100_000L,0L,100_000L,10_000L,1L),
        allocation(101L,7L,"INSTITUTION",1L,90_000L,100_000L,0L,100_000L,90_000L,1L));

    CourseRevenueAllocationReconciliation.Result result =
        CourseRevenueAllocationReconciliation.reconcile(snapshot,7L,100_000L,0L,allocations);

    assertTrue(result.complete(),() -> String.join("; ",result.issues()));
    assertEquals(100_000L,result.expectedTotalPaise());
    assertEquals(100_000L,result.recordedTotalPaise());
    assertEquals(2,result.expectedRecipientCount());
    assertEquals(0L,result.unassignedRemainderPaise());
  }

  @Test
  void mergesExplicitShareAndRemainderWhenTheyNameTheSameRecipient() {
    Map<String,Object> snapshot = snapshot(
        List.of(recipient(11L,"INSTITUTION",2L,"PERCENTAGE",1_000,0L,"Quanta Edge")),
        "INSTITUTION",2L,"Quanta Edge");
    List<Map<String,Object>> allocations = List.of(
        allocation(100L,7L,"INSTITUTION",2L,100_000L,100_000L,0L,100_000L,100_000L,1L));

    CourseRevenueAllocationReconciliation.Result result =
        CourseRevenueAllocationReconciliation.reconcile(snapshot,7L,100_000L,0L,allocations);

    assertTrue(result.complete(),() -> String.join("; ",result.issues()));
    assertEquals(1,result.expectedRecipientCount());
    assertEquals(100_000L,result.expectedTotalPaise());
    assertEquals(90_000L,result.remainderPaise());
  }

  @Test
  void marksMissingRemainderAsUnassignedInsteadOfCallingSaleReconciled() {
    Map<String,Object> snapshot = snapshot(
        List.of(recipient(11L,"INSTITUTION",2L,"PERCENTAGE",1_000,0L,"Sunrise Public School")),
        "",0L,"");
    List<Map<String,Object>> allocations = List.of(
        allocation(100L,7L,"INSTITUTION",2L,10_000L,100_000L,0L,100_000L,10_000L,1L));

    CourseRevenueAllocationReconciliation.Result result =
        CourseRevenueAllocationReconciliation.reconcile(snapshot,7L,100_000L,0L,allocations);

    assertFalse(result.complete());
    assertEquals(90_000L,result.unassignedRemainderPaise());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("no valid institution or company")));
  }

  @Test
  void detectsMissingRecipientEvenWhenAnotherAllocationRowExists() {
    Map<String,Object> snapshot = snapshot(
        List.of(recipient(11L,"INSTITUTION",2L,"PERCENTAGE",1_000,0L,"Sunrise Public School")),
        "INSTITUTION",1L,"Quanta Edge");
    List<Map<String,Object>> allocations = List.of(
        allocation(100L,7L,"INSTITUTION",2L,10_000L,100_000L,0L,100_000L,10_000L,1L));

    CourseRevenueAllocationReconciliation.Result result =
        CourseRevenueAllocationReconciliation.reconcile(snapshot,7L,100_000L,0L,allocations);

    assertFalse(result.complete());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("Expected recipient INSTITUTION:1 has no allocation row")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("complete distributable pool")));
  }

  @Test
  void detectsEarnedLedgerEventMismatch() {
    Map<String,Object> snapshot = snapshot(
        List.of(recipient(11L,"INSTITUTION",2L,"PERCENTAGE",10_000,0L,"Quanta Edge")),
        "INSTITUTION",2L,"Quanta Edge");
    List<Map<String,Object>> allocations = List.of(
        allocation(100L,7L,"INSTITUTION",2L,100_000L,100_000L,0L,100_000L,99_999L,1L));

    CourseRevenueAllocationReconciliation.Result result =
        CourseRevenueAllocationReconciliation.reconcile(snapshot,7L,100_000L,0L,allocations);

    assertFalse(result.complete());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("matching EARNED ledger event")));
  }

  @Test
  void rejectsPolicySnapshotMismatch() {
    Map<String,Object> snapshot = snapshot(
        List.of(recipient(11L,"INSTITUTION",2L,"PERCENTAGE",10_000,0L,"Quanta Edge")),
        "INSTITUTION",2L,"Quanta Edge");

    CourseRevenueAllocationReconciliation.Result result =
        CourseRevenueAllocationReconciliation.reconcile(snapshot,8L,100_000L,0L,List.of());

    assertFalse(result.complete());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("policy ID does not match")));
  }

  private static Map<String,Object> snapshot(
      List<Map<String,Object>> recipients,String remainderType,long remainderId,String remainderName) {
    return Map.of(
        "policyId",7L,
        "recipients",recipients,
        "remainderRecipientType",remainderType,
        "remainderRecipientId",remainderId,
        "remainderRecipientName",remainderName);
  }

  private static Map<String,Object> recipient(
      long policyRecipientId,String type,long id,String calculationType,long basisPoints,long flat,String name) {
    return Map.of(
        "policyRecipientId",policyRecipientId,
        "recipientType",type,
        "recipientId",id,
        "calculationType",calculationType,
        "percentageBasisPoints",basisPoints,
        "flatAmountPaise",flat,
        "recipientName",name);
  }

  private static Map<String,Object> allocation(
      long id,long policyId,String type,long recipientId,long amount,long paid,long tax,long pool,
      long earnedAmount,long earnedCount) {
    return Map.of(
        "id",id,
        "policy_id",policyId,
        "recipient_type",type,
        "institution_id",recipientId,
        "allocated_amount_paise",amount,
        "actual_paid_paise",paid,
        "output_tax_paise",tax,
        "distributable_pool_paise",pool,
        "earned_event_amount_paise",earnedAmount,
        "earned_event_count",earnedCount);
  }
}
