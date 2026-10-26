package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-to-server Razorpay webhook inbox. HMAC verification uses the exact raw request bytes.
 * A webhook never supplies authoritative price, buyer, tenant, tax or entitlement data.
 */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
public class CoursePaymentWebhookController {
  private static final int MAX_BODY_BYTES=1_048_576;
  private static final Pattern EVENT_ID=Pattern.compile("[A-Za-z0-9._:-]{1,160}");
  private static final Pattern ORDER_ID=Pattern.compile("order_[A-Za-z0-9]+");
  private static final Pattern PAYMENT_ID=Pattern.compile("pay_[A-Za-z0-9]+");
  private static final Pattern REFUND_ID=Pattern.compile("[A-Za-z0-9_-]{4,80}");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final CourseMarketplaceController marketplace;
  private final CourseRefundLedgerService refundLedger;
  private final String webhookSecret;
  private final String previousWebhookSecret;

  public CoursePaymentWebhookController(
      JdbcTemplate jdbc,ObjectMapper mapper,CourseMarketplaceController marketplace,
      CourseRefundLedgerService refundLedger,
      @Value("${app.razorpay.webhook-secret:}") String webhookSecret,
      @Value("${app.razorpay.webhook-secret-previous:}") String previousWebhookSecret) {
    this.jdbc=jdbc;
    this.mapper=mapper;
    this.marketplace=marketplace;
    this.refundLedger=refundLedger;
    this.webhookSecret=webhookSecret==null?"":webhookSecret.trim();
    this.previousWebhookSecret=previousWebhookSecret==null?"":previousWebhookSecret.trim();
  }

  @PostMapping(value="/course-payments",consumes=MediaType.APPLICATION_JSON_VALUE,
      produces=MediaType.APPLICATION_JSON_VALUE)
  @Transactional
  public ResponseEntity<Map<String,Object>> receiveCoursePaymentEvent(
      @RequestHeader(value="X-Razorpay-Signature",required=false) String signature,
      @RequestHeader(value="X-Razorpay-Event-Id",required=false) String eventId,
      HttpServletRequest request) throws IOException {
    if(webhookSecret.isBlank()) return response(HttpStatus.SERVICE_UNAVAILABLE,"WEBHOOK_NOT_CONFIGURED",
        "Razorpay webhook signing secret is not configured on the server.");
    // Reject declared oversized bodies before reading and cap streamed/chunked bodies too.
    // @RequestBody byte[] would buffer an untrusted request in full before the size check.
    if(request.getContentLengthLong()>MAX_BODY_BYTES)
      return response(HttpStatus.PAYLOAD_TOO_LARGE,"BODY_TOO_LARGE","Webhook body exceeds the allowed size.");
    byte[] rawBody=readBoundedBody(request.getInputStream());
    if(rawBody.length==0) return response(HttpStatus.BAD_REQUEST,"EMPTY_BODY","Webhook body is empty.");
    if(rawBody.length>MAX_BODY_BYTES) return response(HttpStatus.PAYLOAD_TOO_LARGE,"BODY_TOO_LARGE","Webhook body exceeds the allowed size.");
    String normalizedEventId=eventId==null?"":eventId.trim();
    if(!EVENT_ID.matcher(normalizedEventId).matches())
      return response(HttpStatus.BAD_REQUEST,"EVENT_ID_REQUIRED","A valid X-Razorpay-Event-Id header is required.");
    if(!isValidSignature(rawBody,signature))
      return response(HttpStatus.UNAUTHORIZED,"INVALID_SIGNATURE","Razorpay webhook signature verification failed.");

    JsonNode root;
    try {
      root=mapper.readTree(rawBody);
    } catch(Exception invalidJson) {
      return response(HttpStatus.BAD_REQUEST,"INVALID_JSON","Signed webhook body is not valid JSON.");
    }
    if(root==null||!root.isObject())
      return response(HttpStatus.BAD_REQUEST,"INVALID_JSON","Signed webhook body must be a JSON object.");

    String eventType=safe(root.path("event").asText(""),80).toLowerCase(Locale.ROOT);
    if(eventType.isBlank()) return response(HttpStatus.BAD_REQUEST,"EVENT_TYPE_REQUIRED","The signed event has no type.");
    JsonNode entity=entityFor(root,eventType);
    String providerOrderId=providerOrderId(entity,eventType);
    String providerPaymentId=validOrNull(entity.path("id").asText(""),PAYMENT_ID);
    String providerRefundId=eventType.startsWith("refund.")
        ? validOrNull(entity.path("id").asText(""),REFUND_ID) : null;
    String paymentIdFromRefund=eventType.startsWith("refund.")
        ? validOrNull(entity.path("payment_id").asText(""),PAYMENT_ID) : providerPaymentId;
    Long refundId=eventType.startsWith("refund.")?resolveRefundId(entity,providerRefundId):null;
    Long orderId=eventType.startsWith("refund.")
        ?orderIdForRefund(refundId):resolveCourseOrderId(providerOrderId,paymentIdFromRefund);
    String digest=sha256(rawBody);
    Map<String,Object> details=safeDetails(eventType,entity,root,providerPaymentId,providerRefundId);

    int inserted=jdbc.update("""
        insert into course_payment_provider_event(
          provider_event_id,event_type,course_payment_order_id,course_payment_refund_id,
          provider_order_id,provider_payment_id,provider_refund_id,payload_sha256,
          processing_status,status_message,details
        ) values(?,?,?,?,?,?,?,?,'PROCESSING','Signature verified; event received.',?::jsonb)
        on conflict(provider_event_id) do nothing
        """,normalizedEventId,eventType,orderId,refundId,providerOrderId,paymentIdFromRefund,
          providerRefundId,digest,json(details));
    if(inserted==0) {
      List<Map<String,Object>> prior=jdbc.queryForList("""
          select payload_sha256,processing_status,status_message
          from course_payment_provider_event where provider_event_id=?
          """,normalizedEventId);
      if(prior.isEmpty()) return response(HttpStatus.SERVICE_UNAVAILABLE,"EVENT_RETRY_REQUIRED",
          "The event could not be resolved safely. Razorpay should retry this delivery.");
      Map<String,Object> saved=prior.getFirst();
      if(!digest.equals(String.valueOf(saved.get("payload_sha256")).trim()))
        return response(HttpStatus.CONFLICT,"EVENT_ID_PAYLOAD_MISMATCH",
            "This provider event ID was previously recorded with a different payload digest. Manual reconciliation is required.");
      Map<String,Object> duplicate=new LinkedHashMap<>();
      duplicate.put("received",true);duplicate.put("duplicate",true);
      duplicate.put("eventId",normalizedEventId);
      duplicate.put("status",saved.get("processing_status"));
      duplicate.put("message",saved.get("status_message"));
      return ResponseEntity.ok(duplicate);
    }

    Outcome outcome=processVerifiedEvent(eventType,entity,root,providerOrderId,paymentIdFromRefund,
        providerRefundId,refundId,orderId);
    Map<String,Object> merged=new LinkedHashMap<>(details);
    merged.putAll(outcome.details());
    int changed=jdbc.update("""
        update course_payment_provider_event
        set processing_status=?,status_message=?,details=?::jsonb,processed_at=now()
        where provider_event_id=? and processing_status='PROCESSING'
        """,outcome.status(),safe(outcome.message(),1200),json(merged),normalizedEventId);
    if(changed!=1) throw new IllegalStateException("The webhook event did not transition to its terminal outcome.");
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("received",true);response.put("duplicate",false);response.put("eventId",normalizedEventId);
    response.put("status",outcome.status());response.put("message",safe(outcome.message(),1200));
    return ResponseEntity.ok(response);
  }

  private Outcome processVerifiedEvent(String eventType,JsonNode entity,JsonNode root,
      String providerOrderId,String paymentId,String providerRefundId,Long refundId,Long localOrderId) {
    if("payment.captured".equals(eventType)||"order.paid".equals(eventType)) {
      if(providerOrderId==null||localOrderId==null)
        return new Outcome("UNMATCHED","No saved course order matches this verified provider event. Finance must investigate before another payment is attempted.",
            Map.of("eventKind","CAPTURE_OR_ORDER_PAID"));
      try {
        Map<String,Object> result=marketplace.reconcileOrderFromProviderEvent(providerOrderId);
        String state=string(result.get("status")).toUpperCase(Locale.ROOT);
        Map<String,Object> eventDetails=new LinkedHashMap<>();
        copyIfPresent(result,eventDetails,"providerOrderStatus","providerOrderStatus");
        copyIfPresent(result,eventDetails,"providerAmountPaidPaise","providerAmountPaidPaise");
        copyIfPresent(result,eventDetails,"providerAttemptCount","providerAttemptCount");
        copyIfPresent(result,eventDetails,"paymentAttempts","paymentAttempts");
        copyIfPresent(result,eventDetails,"invoiceNumber","invoiceNumber");
        if("VERIFIED".equals(state))
          return new Outcome("PROCESSED","Provider event matched the saved order and server-side reconciliation confirmed capture. Existing purchase, invoice and entitlement were finalized idempotently.",eventDetails);
        if("PAYMENT_VERIFIED_ACCESS_GRANTED".equals(state)) {
          eventDetails.put("accessGranted",Boolean.TRUE.equals(result.get("entitled")));
          eventDetails.put("financeReconciliationRequired",Boolean.TRUE.equals(result.get("financeReconciliationRequired")));
          eventDetails.put("reconciliationStatus",state);
          return new Outcome("RECONCILIATION_REQUIRED",
              "Razorpay confirmed the captured payment and course access was granted. Invoice/revenue-share bookkeeping still needs HRMS reconciliation; do not ask the learner to pay again.",
              eventDetails);
        }
        if("UNMATCHED".equals(state)) return new Outcome("UNMATCHED",safe(result.get("message"),1000),eventDetails);
        eventDetails.put("reconciliationStatus",state);
        return new Outcome("RECONCILIATION_REQUIRED",
            "The signed provider event was received, but server-side order/payment reconciliation did not confirm a complete sale. Do not ask the learner to pay again; resolve this saved order from HRMS.",
            eventDetails);
      } catch(RuntimeException failure) {
        return new Outcome("RECONCILIATION_REQUIRED",
            "The signed provider event was saved, but the authoritative provider check/finalization did not complete. Keep the order reserved and reconcile it from HRMS; do not collect another payment.",
            Map.of("resolutionCode","SERVER_RECONCILIATION_FAILED","errorCategory",failure.getClass().getSimpleName()));
      }
    }
    if("payment.authorized".equals(eventType)) {
      return new Outcome(localOrderId==null?"UNMATCHED":"RECONCILIATION_REQUIRED",
          "The provider reports an authorization, not a confirmed captured sale. No course access or invoice was granted by this event. HRMS must reconcile the saved order before advising the learner.",
          Map.of("providerPaymentStatus",safe(entity.path("status").asText("authorized"),32)));
    }
    if("payment.failed".equals(eventType)) {
      return new Outcome(localOrderId==null?"UNMATCHED":"IGNORED",
          "This is one failed payment attempt, not proof that every attempt for the order failed. The local order was not marked paid or forcibly expired; reconcile provider state before asking for another payment.",
          safePaymentFailure(entity));
    }
    if(eventType.startsWith("refund.")) {
      if(refundId==null||providerRefundId==null||paymentId==null)
        return new Outcome("UNMATCHED","No saved refund request matches this signed provider event. Do not issue a credit note or change the original invoice.",
            Map.of("eventKind","REFUND_EVENT"));
      long amount=entity.path("amount").asLong(-1);
      String currency=safe(entity.path("currency").asText(""),3).toUpperCase(Locale.ROOT);
      String providerStatus=safe(entity.path("status").asText(eventType.substring("refund.".length())),24);
      Map<String,Object> snapshot=new LinkedHashMap<>();
      snapshot.put("source","RAZORPAY_SIGNED_WEBHOOK");snapshot.put("eventType",eventType);
      snapshot.put("providerRefundId",providerRefundId);snapshot.put("providerPaymentId",paymentId);
      snapshot.put("providerStatus",providerStatus);snapshot.put("amountPaise",amount);snapshot.put("currency",currency);
      Map<String,Object> refund=refundLedger.applyProviderResultFromWebhook(
          refundId,providerRefundId,paymentId,amount,currency,providerStatus,snapshot);
      String refundState=string(refund.get("status")).toUpperCase(Locale.ROOT);
      Map<String,Object> eventDetails=new LinkedHashMap<>();
      eventDetails.put("refundState",refundState);
      if(refund.get("credit_note_document_id")!=null)eventDetails.put("creditNoteDocumentId",refund.get("credit_note_document_id"));
      if(refund.get("credit_note_number")!=null)eventDetails.put("creditNoteNumber",refund.get("credit_note_number"));
      if("RECONCILIATION_REQUIRED".equals(refundState))
        return new Outcome("RECONCILIATION_REQUIRED","Refund event was signature-verified, but its provider IDs/amount/status did not safely settle the saved refund. Refresh the refund in HRMS; no speculative credit note was issued.",eventDetails);
      String terminalDisposition=refundTerminalEventDisposition(refundState,providerStatus);
      if("RECONCILIATION_REQUIRED".equals(terminalDisposition))
        return new Outcome("RECONCILIATION_REQUIRED",
            "The signed provider refund event conflicts with the saved terminal refund result. The original refund record is immutable; verify the current refund in Razorpay and resolve the accounting discrepancy before issuing any manual adjustment.",
            Map.of("resolutionCode","TERMINAL_REFUND_STATUS_CONFLICT","savedRefundState",refundState,
                "incomingProviderStatus",safe(providerStatus,24),"refundId",refundId));
      if("IGNORED".equals(terminalDisposition))
        return new Outcome("IGNORED",
            "A delayed non-terminal refund event arrived after the refund was already terminal. The saved refund result was preserved and no financial state was changed.",
            Map.of("resolutionCode","STALE_NONTERMINAL_REFUND_EVENT","savedRefundState",refundState,
                "incomingProviderStatus",safe(providerStatus,24),"refundId",refundId));
      if("PROCESSED".equals(refundState))
        return new Outcome("PROCESSED","Signed refund event matches the saved processed refund. Its credit note and allocation reversals were already recorded idempotently.",eventDetails);
      if("FAILED".equals(refundState))
        return new Outcome("PROCESSED","Signed refund failure event matches the saved failed refund. No credit note or allocation reversal was issued.",eventDetails);
      return new Outcome("PROCESSED","Signed refund event was applied against the saved refund request. A credit note and allocation reversal exist only when the refund ledger confirmed provider status processed.",eventDetails);
    }
    return new Outcome("IGNORED","The signature is valid, but this event type is not part of the QuantaEdge course-payment lifecycle. No order or fiscal state was changed.",
        Map.of("eventType",eventType));
  }

  private Long resolveCourseOrderId(String providerOrderId,String paymentId) {
    List<Map<String,Object>> rows=List.of();
    if(providerOrderId!=null) rows=jdbc.queryForList(
        "select id from course_payment_order where provider_order_id=?",providerOrderId);
    if(rows.isEmpty()&&paymentId!=null)
      rows=jdbc.queryForList("select id from course_payment_order where provider_payment_id=?",paymentId);
    return rows.isEmpty()?null:((Number)rows.getFirst().get("id")).longValue();
  }

  private Long resolveRefundId(JsonNode entity,String providerRefundId) {
    String raw=entity.path("notes").path("quantaedge_refund_id").asText("");
    if(raw.matches("[0-9]{1,18}")) {
      try {
        long id=Long.parseLong(raw);
        List<Map<String,Object>> rows=jdbc.queryForList("select id from course_payment_refund where id=?",id);
        if(!rows.isEmpty()) return id;
      } catch(NumberFormatException ignored) { /* Fall through to the immutable provider ID lookup. */ }
    }
    if(providerRefundId==null)return null;
    List<Map<String,Object>> rows=jdbc.queryForList(
        "select id from course_payment_refund where provider_refund_id=?",providerRefundId);
    return rows.isEmpty()?null:((Number)rows.getFirst().get("id")).longValue();
  }

  private Long orderIdForRefund(Long refundId) {
    if(refundId==null)return null;
    List<Map<String,Object>> rows=jdbc.queryForList(
        "select course_payment_order_id from course_payment_refund where id=?",refundId);
    return rows.isEmpty()?null:((Number)rows.getFirst().get("course_payment_order_id")).longValue();
  }

  private static JsonNode entityFor(JsonNode root,String eventType) {
    if(eventType.startsWith("payment."))return root.path("payload").path("payment").path("entity");
    if(eventType.startsWith("order."))return root.path("payload").path("order").path("entity");
    if(eventType.startsWith("refund."))return root.path("payload").path("refund").path("entity");
    return root.path("payload");
  }

  private static String providerOrderId(JsonNode entity,String eventType) {
    String raw=eventType.startsWith("order.")?entity.path("id").asText(""):entity.path("order_id").asText("");
    return validOrNull(raw,ORDER_ID);
  }

  private static String validOrNull(String value,Pattern pattern) {
    String normalized=value==null?"":value.trim();
    return pattern.matcher(normalized).matches()?normalized:null;
  }

  private boolean isValidSignature(byte[] rawBody,String signature) {
    // Missing configuration is not a cryptographic failure: fail closed without attempting
    // to initialize HMAC with an invalid empty key. The endpoint itself returns 503 before
    // signature verification when the secret is not configured.
    if(webhookSecret.isBlank())return false;
    if(signature==null||!signature.trim().matches("(?i)[0-9a-f]{64}"))return false;
    byte[] provided;
    try { provided=hexBytes(signature.trim()); } catch(IllegalArgumentException invalidHex) { return false; }
    return signatureMatches(rawBody,provided,webhookSecret)
        ||(!previousWebhookSecret.isBlank()&&signatureMatches(rawBody,provided,previousWebhookSecret));
  }

  private static boolean signatureMatches(byte[] rawBody,byte[] provided,String secret) {
    try {
      Mac mac=Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      return MessageDigest.isEqual(mac.doFinal(rawBody),provided);
    } catch(Exception cryptoFailure) {
      throw new IllegalStateException("Webhook signature verification is unavailable.",cryptoFailure);
    }
  }

  static byte[] readBoundedBody(InputStream input) throws IOException {
    return input.readNBytes(MAX_BODY_BYTES+1);
  }

  static boolean verifySignatureForTest(byte[] body,String signature,String secret) {
    return new CoursePaymentWebhookController(
        null,new ObjectMapper(),null,null,secret,"").isValidSignature(body,signature);
  }

  private static byte[] hexBytes(String value) {
    byte[] bytes=new byte[value.length()/2];
    for(int i=0;i<bytes.length;i++) {
      int high=Character.digit(value.charAt(i*2),16),low=Character.digit(value.charAt(i*2+1),16);
      if(high<0||low<0)throw new IllegalArgumentException("Malformed signature");
      bytes[i]=(byte)((high<<4)|low);
    }
    return bytes;
  }

  private static String sha256(byte[] rawBody) {
    try {
      byte[] digest=MessageDigest.getInstance("SHA-256").digest(rawBody);
      StringBuilder out=new StringBuilder(64);
      for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&0xff));
      return out.toString();
    } catch(NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.",impossible);
    }
  }

  private Map<String,Object> safeDetails(String eventType,JsonNode entity,JsonNode root,
      String paymentId,String refundProviderId) {
    Map<String,Object> detail=new LinkedHashMap<>();
    detail.put("eventType",eventType);
    detail.put("providerEventTime",safe(root.path("created_at").asText(""),40));
    String status=entity.path("status").asText("");
    if(!status.isBlank())detail.put("providerEntityStatus",safe(status,32));
    if(paymentId!=null)detail.put("providerPaymentId",paymentId);
    if(refundProviderId!=null)detail.put("providerRefundId",refundProviderId);
    if(entity.has("error_code"))detail.put("errorCode",safe(entity.path("error_code").asText(""),80));
    if(entity.has("error_reason"))detail.put("errorReason",safe(entity.path("error_reason").asText(""),120));
    if(entity.has("error_description"))detail.put("errorDescription",safe(entity.path("error_description").asText(""),240));
    return detail;
  }

  /**
   * Compare a newly delivered refund event with an already-terminal immutable refund ledger row.
   * APPLY means normal ledger settlement should be trusted; IGNORED means stale pending/created
   * delivery; a terminal-state disagreement must remain visible for human reconciliation.
   */
  static String refundTerminalEventDisposition(String savedState,String incomingProviderStatus) {
    String saved=savedState==null?"":savedState.trim().toUpperCase(Locale.ROOT);
    String incoming=incomingProviderStatus==null?"":incomingProviderStatus.trim().toLowerCase(Locale.ROOT);
    if(!Set.of("PROCESSED","FAILED").contains(saved))return "APPLY";
    if(Set.of("created","pending").contains(incoming))return "IGNORED";
    if("PROCESSED".equals(saved)&&"processed".equals(incoming))return "PROCESSED";
    if("FAILED".equals(saved)&&Set.of("failed","rejected").contains(incoming))return "PROCESSED";
    return "RECONCILIATION_REQUIRED";
  }

  private static Map<String,Object> safePaymentFailure(JsonNode entity) {
    Map<String,Object> detail=new LinkedHashMap<>();
    String code=safe(entity.path("error_code").asText(""),80);
    String reason=safe(entity.path("error_reason").asText(""),120);
    String description=safe(entity.path("error_description").asText(""),240);
    if(!code.isBlank())detail.put("errorCode",code);
    if(!reason.isBlank())detail.put("errorReason",reason);
    if(!description.isBlank())detail.put("errorDescription",description);
    return detail;
  }

  private static void copyIfPresent(Map<String,Object> source,Map<String,Object> target,String sourceKey,String targetKey) {
    if(source.containsKey(sourceKey)&&source.get(sourceKey)!=null) target.put(targetKey,source.get(sourceKey));
  }

  private static String safe(Object raw,int limit) {
    if(raw==null)return "";
    String value=String.valueOf(raw).replaceAll("[\\p{Cntrl}&&[^\\t]]"," ").replaceAll("\\s+"," ").trim();
    return value.length()<=limit?value:value.substring(0,limit);
  }

  private String json(Map<String,Object> value) {
    try { return mapper.writeValueAsString(value==null?Map.of():value); }
    catch(JsonProcessingException failure) { throw new IllegalStateException("Webhook event details could not be serialized.",failure); }
  }

  private static String string(Object value) { return value==null?"":String.valueOf(value); }

  private static ResponseEntity<Map<String,Object>> response(HttpStatus status,String code,String message) {
    return ResponseEntity.status(status).body(Map.of("received",false,"code",code,"message",message));
  }

  private record Outcome(String status,String message,Map<String,Object> details) {}
}
