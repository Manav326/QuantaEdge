package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseRefundLedgerServiceProviderStateTest {
  @Test
  void pendingProviderResponseReservesStatusButDoesNotIssueCreditNote() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    stubRefundReads(jdbc,"PROCESSING");
    CourseRefundLedgerService service=service(jdbc);

    Map<String,Object> result=service.applyProviderResult(
        11L,"rfnd_TEST01","pay_test_001",5_900L,"INR","pending",
        Map.of("id","rfnd_TEST01","status","pending"),5L);

    assertEquals("PENDING",result.get("status"));
    verify(jdbc).update(contains("update course_payment_refund set status='PENDING'"),any(Object[].class));
    verify(jdbc,never()).queryForObject(contains("insert into fiscal_document("),eq(Long.class),any(Object[].class));
  }

  @Test
  void mismatchedProviderPaymentMovesRefundToReconciliationAndDoesNotIssueCreditNote() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    stubRefundReads(jdbc,"PROCESSING");
    CourseRefundLedgerService service=service(jdbc);

    Map<String,Object> result=service.applyProviderResult(
        11L,"rfnd_TEST01","pay_different",5_900L,"INR","processed",
        Map.of("id","rfnd_TEST01","status","processed"),5L);

    assertEquals("RECONCILIATION_REQUIRED",result.get("status"));
    verify(jdbc).update(contains("update course_payment_refund set status='RECONCILIATION_REQUIRED'"),
        any(Object[].class));
    verify(jdbc,never()).queryForObject(contains("insert into fiscal_document("),eq(Long.class),any(Object[].class));
  }

  @Test
  void newRefundIsBlockedForPermanentlyTransferredEntitlement() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    doAnswer(invocation->1).when(jdbc).queryForObject(
        contains("join course_entitlement ce"),eq(Integer.class),any(Object[].class));
    CourseRefundLedgerService service=service(jdbc);

    IllegalStateException failure=assertThrows(IllegalStateException.class,
        ()->service.assertRefundAllowedForEntitlement(70L));
    assertTrue(failure.getMessage().contains("permanently transferred"));
  }

  @Test
  void newRefundIsAllowedByTransferGuardWhenEntitlementHasNotBeenTransferred() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    doAnswer(invocation->0).when(jdbc).queryForObject(
        contains("join course_entitlement ce"),eq(Integer.class),any(Object[].class));
    CourseRefundLedgerService service=service(jdbc);

    assertDoesNotThrow(()->service.assertRefundAllowedForEntitlement(70L));
  }

  private CourseRefundLedgerService service(JdbcTemplate jdbc) {
    return new CourseRefundLedgerService(
        jdbc,new ObjectMapper(),mock(FiscalDocumentRenderer.class),mock(FiscalDocumentStorage.class));
  }

  private void stubRefundReads(JdbcTemplate jdbc,String initialStatus) {
    Map<String,String> state=new HashMap<>();
    state.put("status",initialStatus);
    doAnswer(invocation->{
      String sql=invocation.getArgument(0);
      if(sql.contains("set status='PENDING'")) state.put("status","PENDING");
      if(sql.contains("set status='RECONCILIATION_REQUIRED'")) state.put("status","RECONCILIATION_REQUIRED");
      return 1;
    }).when(jdbc).update(anyString(),any(Object[].class));

    doAnswer(invocation->{
      String sql=invocation.getArgument(0);
      String current=state.get("status");
      if(sql.contains("from course_payment_refund where id=? for update")) {
        Map<String,Object> row=new HashMap<>();
        row.put("id",11L);
        row.put("course_payment_order_id",70L);
        row.put("request_idempotency_key","refund-request-0001");
        row.put("provider_idempotency_key","QE-REFUND-provider-key-0001");
        row.put("provider_payment_id","pay_test_001");
        row.put("provider_refund_id",null);
        row.put("requested_amount_paise",5_900L);
        row.put("currency","INR");
        row.put("reason","Approved refund for course access");
        row.put("status",current);
        row.put("provider_status",null);
        row.put("credit_note_document_id",null);
        row.put("requested_by_staff_id",5L);
        return List.of(row);
      }
      if(sql.contains("select status,provider_response_snapshot::text as response_json")) {
        return List.of(Map.of("status",current,"response_json","{}"));
      }
      if(sql.contains("select r.id,r.public_id,r.course_payment_order_id")) {
        Map<String,Object> view=new HashMap<>();
        view.put("id",11L);
        view.put("public_id","refund-public-id");
        view.put("course_payment_order_id",70L);
        view.put("requested_amount_paise",5_900L);
        view.put("currency","INR");
        view.put("reason","Approved refund for course access");
        view.put("status",current);
        view.put("provider_status",current.equals("PROCESSING")?null:current.toLowerCase());
        view.put("provider_refund_id",null);
        view.put("requested_at","2026-10-20T10:00:00Z");
        view.put("settled_at",null);
        view.put("credit_note_document_id",null);
        view.put("credit_note_number",null);
        view.put("provider_payment_id","pay_test_001");
        return List.of(view);
      }
      return List.of();
    }).when(jdbc).queryForList(anyString(),any(Object[].class));
  }
}
