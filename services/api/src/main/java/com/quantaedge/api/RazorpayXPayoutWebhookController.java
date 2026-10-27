package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HMAC-verified RazorpayX payout webhook adapter; state changes flow through the existing payout ledger. */
@RestController
@RequestMapping("/api/v1/webhooks/razorpayx")
public class RazorpayXPayoutWebhookController {
  private static final int MAX_BODY_BYTES=1_048_576;
  private final ObjectMapper mapper;
  private final FinancePayoutController payouts;
  private final String webhookSecret;

  public RazorpayXPayoutWebhookController(ObjectMapper mapper,FinancePayoutController payouts,
      @Value("${app.razorpayx.webhook-secret:}") String webhookSecret) {
    this.mapper=mapper;
    this.payouts=payouts;
    this.webhookSecret=webhookSecret==null?"":webhookSecret.trim();
  }

  @PostMapping(path="/payouts",consumes="application/json")
  public ResponseEntity<?> receive(
      HttpServletRequest request,
      @RequestHeader(value="X-Razorpay-Signature",required=false) String signature,
      @RequestHeader(value="X-Razorpay-Event-Id",required=false) String eventId) throws java.io.IOException {
    if(webhookSecret.isBlank())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RazorpayX payout webhook verification is not configured.");
    // Read the exact inbound bytes; never deserialize/re-serialize before checking the HMAC.
    byte[] rawBody=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
    if(rawBody.length==0||rawBody.length>MAX_BODY_BYTES)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Webhook body is empty or exceeds the supported size.");
    if(signature==null||!verify(rawBody,signature.trim(),webhookSecret))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"RazorpayX payout webhook signature is invalid.");
    if(eventId==null||!eventId.trim().matches("[A-Za-z0-9._:-]{1,120}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"RazorpayX event ID is missing or invalid.");

    try {
      JsonNode root=mapper.readTree(rawBody);
      String eventType=root.path("event").asText("").trim();
      if(eventType.isBlank()||eventType.length()>80)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Webhook event type is missing or invalid.");
      // This endpoint is configured for payout events only; a misconfigured non-payout event is safely ignored.
      if(!eventType.startsWith("payout."))
        return ResponseEntity.ok(java.util.Map.of("accepted",true,"disposition","IGNORED"));
      JsonNode payout=root.path("payload").path("payout").path("entity");
      if(!payout.isObject())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Payout webhook does not contain a payout entity.");
      payouts.processPayoutWebhook(payout,eventId,eventType,sha256(rawBody));
      return ResponseEntity.ok(java.util.Map.of("accepted",true));
    } catch(ResponseStatusException ex) {
      throw ex;
    } catch(Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"RazorpayX payout webhook could not be safely processed.",ex);
    }
  }

  private static boolean verify(byte[] body,String supplied,String secret) {
    if(!supplied.matches("(?i)[0-9a-f]{64}"))return false;
    try {
      Mac mac=Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      byte[] expected=mac.doFinal(body);
      byte[] actual=HexFormat.of().parseHex(supplied);
      return MessageDigest.isEqual(expected,actual);
    } catch(Exception ex) {
      return false;
    }
  }

  private static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch(Exception ex) {
      throw new IllegalStateException("Webhook payload hash could not be calculated.",ex);
    }
  }
}
