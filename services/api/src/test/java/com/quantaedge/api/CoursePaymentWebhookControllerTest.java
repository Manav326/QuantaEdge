package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CoursePaymentWebhookControllerTest {
  @Test
  void signatureMustMatchExactRawBodyBytes() throws Exception {
    byte[] body="{\"event\":\"payment.captured\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
    String signature=sign(body,"sandbox-webhook-secret");
    assertTrue(CoursePaymentWebhookController.verifySignatureForTest(body,signature,"sandbox-webhook-secret"));
    assertFalse(CoursePaymentWebhookController.verifySignatureForTest(body,signature,"wrong-secret"));
    assertFalse(CoursePaymentWebhookController.verifySignatureForTest(
        "{\"event\":\"payment.failed\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8),
        signature,"sandbox-webhook-secret"));
  }

  @Test
  void malformedOrMissingSignaturesFailClosed() throws Exception {
    byte[] body="{}".getBytes(StandardCharsets.UTF_8);
    assertFalse(CoursePaymentWebhookController.verifySignatureForTest(body,null,"configured-secret"));
    assertFalse(CoursePaymentWebhookController.verifySignatureForTest(body,"not-a-signature","configured-secret"));
    assertFalse(CoursePaymentWebhookController.verifySignatureForTest(body,"a".repeat(64),""));
    assertFalse(CoursePaymentWebhookController.verifySignatureForTest(body,sign(body,"configured-secret"),""));
  }

  @Test
  void webhookBodyReaderStopsAtTheLimitPlusOneByte() throws Exception {
    byte[] oversized=new byte[1_048_576+100];
    byte[] bounded=CoursePaymentWebhookController.readBoundedBody(new ByteArrayInputStream(oversized));
    assertEquals(1_048_577,bounded.length);
  }

  @Test
  void delayedRefundEventsCannotDowngradeTerminalLedgerState() {
    assertEquals("IGNORED",CoursePaymentWebhookController.refundTerminalEventDisposition("PROCESSED","created"));
    assertEquals("IGNORED",CoursePaymentWebhookController.refundTerminalEventDisposition("FAILED","pending"));
    assertEquals("PROCESSED",CoursePaymentWebhookController.refundTerminalEventDisposition("PROCESSED","processed"));
    assertEquals("PROCESSED",CoursePaymentWebhookController.refundTerminalEventDisposition("FAILED","failed"));
  }

  @Test
  void contradictoryTerminalRefundEventRequiresReconciliation() {
    assertEquals("RECONCILIATION_REQUIRED",
        CoursePaymentWebhookController.refundTerminalEventDisposition("FAILED","processed"));
    assertEquals("RECONCILIATION_REQUIRED",
        CoursePaymentWebhookController.refundTerminalEventDisposition("PROCESSED","failed"));
    assertEquals("APPLY",CoursePaymentWebhookController.refundTerminalEventDisposition("PENDING","processed"));
  }

  private static String sign(byte[] body,String secret) throws Exception {
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    StringBuilder hex=new StringBuilder();
    for(byte b:mac.doFinal(body))hex.append(String.format("%02x",b&0xff));
    return hex.toString();
  }
}
