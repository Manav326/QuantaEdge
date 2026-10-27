package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RazorpayXPayoutWebhookControllerTest {
  private static final String SECRET="razorpayx-test-webhook-secret";

  @Test
  void signatureMustMatchExactRawBodyBytes() throws Exception {
    byte[] body="{ \"event\": \"payout.processed\", \"payload\": {} }".getBytes(StandardCharsets.UTF_8);
    String signature=sign(body,SECRET);
    assertEquals(true,RazorpayXPayoutWebhookController.verifySignatureForTest(body,signature,SECRET));
    assertEquals(false,RazorpayXPayoutWebhookController.verifySignatureForTest(body,signature,"wrong-secret"));
    assertEquals(false,RazorpayXPayoutWebhookController.verifySignatureForTest(
        "{\"event\":\"payout.processed\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8),signature,SECRET));
    assertEquals(false,RazorpayXPayoutWebhookController.verifySignatureForTest(body,null,SECRET));
    assertEquals(false,RazorpayXPayoutWebhookController.verifySignatureForTest(body,"not-a-signature",SECRET));
    assertEquals(false,RazorpayXPayoutWebhookController.verifySignatureForTest(body,signature,""));
  }

  @Test
  void acceptsSignedPayoutEventsAndDelegatesToExistingFinanceLedger() throws Exception {
    byte[] body=("{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{"+
        "\"id\":\"pout_1234\",\"status\":\"processed\",\"notes\":{\"quantaedge_payout_id\":\"1\"}}}}}")
        .getBytes(StandardCharsets.UTF_8);
    FinancePayoutController payouts=mock(FinancePayoutController.class);
    RazorpayXPayoutWebhookController controller=new RazorpayXPayoutWebhookController(new ObjectMapper(),payouts,SECRET);

    var response=controller.receive(body,sign(body,SECRET),"evt_payout_123");
    assertEquals(HttpStatus.OK.value(),response.getStatusCode().value());
    verify(payouts).processPayoutWebhook(any(JsonNode.class),eq("evt_payout_123"),eq("payout.processed"),anyString());
  }

  @Test
  void invalidSignatureNeverReachesFinanceLedger() throws Exception {
    byte[] body=("{\"event\":\"payout.processed\",\"payload\":{\"payout\":{\"entity\":{"+
        "\"id\":\"pout_1234\",\"status\":\"processed\"}}}}")
        .getBytes(StandardCharsets.UTF_8);
    FinancePayoutController payouts=mock(FinancePayoutController.class);
    RazorpayXPayoutWebhookController controller=new RazorpayXPayoutWebhookController(new ObjectMapper(),payouts,SECRET);

    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.receive(body,"0".repeat(64),"evt_payout_124"));
    assertEquals(HttpStatus.UNAUTHORIZED.value(),error.getStatusCode().value());
    verify(payouts,never()).processPayoutWebhook(any(JsonNode.class),anyString(),anyString(),anyString());
  }

  @Test
  void webhookFailsClosedWhenSecretIsMissingOrRequestIsOversized() throws Exception {
    FinancePayoutController payouts=mock(FinancePayoutController.class);
    RazorpayXPayoutWebhookController unconfigured=new RazorpayXPayoutWebhookController(new ObjectMapper(),payouts,"");
    ResponseStatusException notConfigured=assertThrows(ResponseStatusException.class,
        ()->unconfigured.receive("{}".getBytes(StandardCharsets.UTF_8),"0".repeat(64),"evt_payout_125"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(),notConfigured.getStatusCode().value());

    RazorpayXPayoutWebhookController configured=new RazorpayXPayoutWebhookController(new ObjectMapper(),payouts,SECRET);
    ResponseStatusException tooLarge=assertThrows(ResponseStatusException.class,
        ()->configured.receive(new byte[1_048_577],null,"evt_payout_126"));
    assertEquals(HttpStatus.BAD_REQUEST.value(),tooLarge.getStatusCode().value());
    verify(payouts,never()).processPayoutWebhook(any(JsonNode.class),anyString(),anyString(),anyString());
  }

  @Test
  void nonPayoutRazorpayEventsAreAcknowledgedWithoutChangingPayoutState() throws Exception {
    byte[] body="{\"event\":\"payment.captured\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
    FinancePayoutController payouts=mock(FinancePayoutController.class);
    RazorpayXPayoutWebhookController controller=new RazorpayXPayoutWebhookController(new ObjectMapper(),payouts,SECRET);

    var response=controller.receive(body,sign(body,SECRET),"evt_payment_123");
    assertEquals(HttpStatus.OK.value(),response.getStatusCode().value());
    verify(payouts,never()).processPayoutWebhook(any(JsonNode.class),anyString(),anyString(),anyString());
  }

  private static String sign(byte[] body,String secret) throws Exception {
    Mac mac=Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }
}
