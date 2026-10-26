package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class CourseOrderFinancialServiceAccessGrantTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private FiscalDocumentRenderer renderer;
  @Mock private FiscalDocumentStorage storage;

  private CourseOrderFinancialService service;

  @BeforeEach
  void setUp() {
    service = new CourseOrderFinancialService(jdbc, new ObjectMapper(), renderer, storage);
  }

  @Test
  void exactCapturedStudentPaymentCommitsPurchaseEntitlementAndLearnerAccessBeforeFinance() {
    when(jdbc.queryForList(contains("from course_payment_order where id=? for update"), any(Object[].class)))
        .thenReturn(List.of(order("STUDENT", null)));
    when(jdbc.queryForList(contains("select id,provider_payment_id from course_purchase"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForObject(contains("insert into course_purchase"), eq(Long.class), any(Object[].class)))
        .thenReturn(91L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    CourseOrderFinancialService.CapturedAccessGrant grant =
        service.grantAccessForCapturedPayment(12L, "order_123", "pay_456", "a".repeat(64), 100000L);

    assertEquals(91L, grant.purchaseId());
    assertEquals(6L, grant.courseId());
    assertEquals(2L, grant.institutionId());
    assertEquals(122L, grant.targetStudentId());
    assertNull(grant.purchaserUserId());
    verify(jdbc).update(contains("insert into course_entitlement"), any(Object[].class));
    verify(jdbc).update(contains("insert into student_program_enrollment"), any(Object[].class));
    verify(jdbc).update(contains("insert into student_track_enrollment"), any(Object[].class));
    verify(jdbc).update(contains("failure_code='FINANCIAL_RECONCILIATION_REQUIRED'"), any(Object[].class));
  }

  @Test
  void exactCapturedParentChildPaymentPreservesPurchaserAndChildOwnership() {
    when(jdbc.queryForList(contains("from course_payment_order where id=? for update"), any(Object[].class)))
        .thenReturn(List.of(order("CHILD", 1L)));
    when(jdbc.queryForList(contains("select id,provider_payment_id from course_purchase"), any(Object[].class)))
        .thenReturn(List.of());
    when(jdbc.queryForObject(contains("insert into course_purchase"), eq(Long.class), any(Object[].class)))
        .thenReturn(92L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    CourseOrderFinancialService.CapturedAccessGrant grant =
        service.grantAccessForCapturedPayment(5L, "order_123", "pay_456", "b".repeat(64), 100000L);

    assertEquals(122L, grant.targetStudentId());
    assertEquals(Long.valueOf(1L), grant.purchaserUserId());
    assertEquals("CHILD", grant.recipientType());
    verify(jdbc).update(contains("update parent_course_purchase_request"), any(Object[].class));
  }

  @Test
  void rejectsProviderOrderMismatchBeforeCreatingAnyAccess() {
    when(jdbc.queryForList(contains("from course_payment_order where id=? for update"), any(Object[].class)))
        .thenReturn(List.of(order("STUDENT", null)));

    assertThrows(IllegalStateException.class,
        () -> service.grantAccessForCapturedPayment(12L, "order_other", "pay_456", "a".repeat(64), 100000L));

    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verifyNoInteractions(renderer, storage);
  }

  @Test
  void accessAndFinancialFinalizationUseIndependentTransactions() throws Exception {
    Transactional accessTx = CourseOrderFinancialService.class
        .getMethod("grantAccessForCapturedPayment", long.class, String.class, String.class, String.class, long.class)
        .getAnnotation(Transactional.class);
    Transactional financeTx = CourseOrderFinancialService.class
        .getMethod("finalizeCapturedSale", long.class, String.class, long.class)
        .getAnnotation(Transactional.class);

    assertNotNull(accessTx);
    assertNotNull(financeTx);
    assertEquals(Propagation.REQUIRES_NEW, accessTx.propagation());
    assertEquals(Propagation.REQUIRES_NEW, financeTx.propagation());
  }

  private static Map<String,Object> order(String recipient, Long purchaserUserId) {
    Map<String,Object> order = new LinkedHashMap<>();
    order.put("id", 12L);
    order.put("student_id", 122L);
    order.put("purchaser_user_id", purchaserUserId);
    order.put("recipient_type", recipient);
    order.put("course_id", 6L);
    order.put("institution_id", 2L);
    order.put("institution_offer_id", 11L);
    order.put("course_identity_id", 3L);
    order.put("identity_contract_version", 1);
    order.put("offer_price_paise", 100000L);
    order.put("currency", "INR");
    order.put("provider_order_id", "order_123");
    order.put("provider_payment_id", null);
    order.put("status", "PENDING");
    return order;
  }
}
