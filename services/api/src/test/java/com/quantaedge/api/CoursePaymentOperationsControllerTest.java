package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CoursePaymentOperationsControllerTest {
  @Test
  void paymentOrderStatusFilterIsNormalizedAndAllowlisted() {
    assertEquals("PENDING",CoursePaymentOperationsController.normalizeStatus(" pending "));
    assertEquals("VERIFIED",CoursePaymentOperationsController.normalizeStatus("Verified"));
    assertEquals(null,CoursePaymentOperationsController.normalizeStatus(null));
    assertThrows(IllegalArgumentException.class,
        () -> CoursePaymentOperationsController.normalizeStatus("REFUND_ALL"));
  }

  @Test
  void verifiedOrderWithoutInvoiceIsEscalatedInsteadOfChargedAgain() {
    var resolution=CoursePaymentOperationsController.resolutionFor("VERIFIED",false,true,"");
    assertEquals("VERIFIED_INVOICE_REVIEW",resolution.code());
    assertTrue(resolution.message().contains("do not reissue automatically"));
  }

  @Test
  void pendingOrderWithProviderReferenceUsesSafeReconciliation() {
    var resolution=CoursePaymentOperationsController.resolutionFor("PENDING",false,true,"");
    assertEquals("PROVIDER_RECONCILIATION",resolution.code());
    assertTrue(resolution.action().contains("same order"));
  }

  @Test
  void pendingOrderWithoutProviderReferenceEscalatesToEngineering() {
    var resolution=CoursePaymentOperationsController.resolutionFor("PENDING",false,false,"");
    assertEquals("PROVIDER_REFERENCE_MISSING",resolution.code());
    assertTrue(resolution.message().contains("Do not create another payment"));
  }

  @Test
  void providerEventPageQuerySeparatesDynamicFiltersFromOrdering() {
    String sql=CoursePaymentOperationsController.providerEventsPageSql(
        " where true and e.course_payment_order_id=?");

    assertTrue(sql.contains("e.course_payment_order_id=?\norder by e.received_at desc,e.id desc"));
    assertFalse(sql.contains("?order by"));
  }

  @Test
  void statusTraceRequiresTaxAmountsToMatchTheSavedOrder() {
    Map<String,Object> consistent=Map.of(
        "snapshot_taxable_value_paise","900","taxable_value_paise",900L,
        "snapshot_output_tax_paise","100","output_tax_paise",100L);
    assertTrue(CoursePaymentOperationsController.snapshotNumberMatches(
        consistent,"snapshot_taxable_value_paise","taxable_value_paise"));
    assertTrue(CoursePaymentOperationsController.snapshotNumberMatches(
        consistent,"snapshot_output_tax_paise","output_tax_paise"));

    Map<String,Object> inconsistent=Map.of(
        "snapshot_taxable_value_paise","900","taxable_value_paise",900L,
        "snapshot_output_tax_paise","99","output_tax_paise",100L);
    assertTrue(CoursePaymentOperationsController.snapshotNumberMatches(
        inconsistent,"snapshot_taxable_value_paise","taxable_value_paise"));
    assertFalse(CoursePaymentOperationsController.snapshotNumberMatches(
        inconsistent,"snapshot_output_tax_paise","output_tax_paise"));

    Map<String,Object> missingSnapshot=Map.of("output_tax_paise",100L);
    assertFalse(CoursePaymentOperationsController.snapshotNumberMatches(
        missingSnapshot,"snapshot_output_tax_paise","output_tax_paise"));
  }

  @Test
  void finalizationFailureDiagnosticsArePreservedInTheStaffAuditPayload() {
    Map<String,Object> result=Map.of(
        "financeFinalizationAttempted",true,
        "financeFinalizationFailureType","DataIntegrityViolationException",
        "financeFinalizationFailureMessage","Invoice PDF asset insert failed");
    Map<String,Object> details=CoursePaymentOperationsController.diagnosticDetails(result);

    assertEquals(true,details.get("financeFinalizationAttempted"));
    assertEquals("DataIntegrityViolationException",details.get("financeFinalizationFailureType"));
    assertEquals("Invoice PDF asset insert failed",details.get("financeFinalizationFailureMessage"));
  }

  @Test
  void failedAndCreatingOrdersCannotBeAutoReconciled() {
    assertEquals("FAILED_ORDER_REVIEW",
        CoursePaymentOperationsController.resolutionFor("FAILED",false,true,"ORDER_CREATE_FAILED").code());
    assertEquals("ORDER_CREATION_INCOMPLETE",
        CoursePaymentOperationsController.resolutionFor("CREATING",false,false,"").code());
    assertEquals("COMPLETE",
        CoursePaymentOperationsController.resolutionFor("VERIFIED",true,true,"").code());
  }
}
