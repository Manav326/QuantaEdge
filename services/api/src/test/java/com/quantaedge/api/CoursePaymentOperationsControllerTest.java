package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void failedAndCreatingOrdersCannotBeAutoReconciled() {
    assertEquals("FAILED_ORDER_REVIEW",
        CoursePaymentOperationsController.resolutionFor("FAILED",false,true,"ORDER_CREATE_FAILED").code());
    assertEquals("ORDER_CREATION_INCOMPLETE",
        CoursePaymentOperationsController.resolutionFor("CREATING",false,false,"").code());
    assertEquals("COMPLETE",
        CoursePaymentOperationsController.resolutionFor("VERIFIED",true,true,"").code());
  }
}
