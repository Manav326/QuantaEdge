package com.quantaedge.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HRMS platform-owner refund controls. A refund is not considered complete merely because
 * the provider accepted the request: only a provider state of "processed" issues the credit note.
 */
@RestController
@RequestMapping("/api/v1/hrms/platform/finance")
public class CourseRefundController {
  private static final TypeReference<Map<String,Object>> MAP_TYPE=new TypeReference<>() {};
  private static final Pattern PROVIDER_ID=Pattern.compile("[A-Za-z0-9_-]{4,80}");

  private final CourseRefundLedgerService ledger;
  private final AuthorizationService authorization;
  private final ObjectMapper mapper;
  private final String keyId;
  private final String keySecret;
  private final String baseUrl;
  private final HttpClient httpClient=HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build();

  public CourseRefundController(
      CourseRefundLedgerService ledger,AuthorizationService authorization,ObjectMapper mapper,
      @Value("${app.razorpay.key-id:}") String keyId,
      @Value("${app.razorpay.key-secret:}") String keySecret,
      @Value("${app.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
    this.ledger=ledger;
    this.authorization=authorization;
    this.mapper=mapper;
    this.keyId=keyId==null?"":keyId.trim();
    this.keySecret=keySecret==null?"":keySecret.trim();
    this.baseUrl=(baseUrl==null?"https://api.razorpay.com/v1":baseUrl.trim()).replaceAll("/+$","");
  }

  @PostMapping("/orders/{orderId}/refunds")
  public ResponseEntity<Map<String,Object>> requestRefund(
      @PathVariable long orderId,
      @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
      @RequestBody RefundRequest request,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    if(request==null||request.amountPaise()==null)
      throw new IllegalArgumentException("Supply the refund amount in integer paise.");
    CourseRefundLedgerService.PreparedRefund prepared=ledger.prepareRefund(
        orderId,request.amountPaise(),request.reason(),idempotencyKey,context.staffId());
    if("PROCESSED".equals(prepared.status())||"FAILED".equals(prepared.status()))
      return ResponseEntity.ok(ledger.refundView(prepared.id()));

    try {
      Map<String,Object> payload=new LinkedHashMap<>();
      payload.put("amount",prepared.amountPaise());
      payload.put("receipt","QE-RFND-"+prepared.id());
      Map<String,String> notes=Map.of(
          "quantaedge_refund_id",Long.toString(prepared.id()),
          "course_payment_order_id",Long.toString(prepared.orderId()),
          "reason",prepared.reason().length()>200?prepared.reason().substring(0,200):prepared.reason());
      payload.put("notes",notes);
      JsonNode provider=providerRequest("POST",
          "/payments/"+prepared.providerPaymentId()+"/refund",mapper.writeValueAsString(payload),
          prepared.providerIdempotencyKey());
      return handleProviderResponse(prepared,provider,context.staffId());
    } catch(Exception providerFailure) {
      Map<String,Object> state=ledger.markReconciliationRequired(
          prepared.id(),context.staffId(),
          "Razorpay response was unavailable or inconclusive. Retry the exact same request using the same Idempotency-Key; the provider refund uses a saved idempotency key.");
      Map<String,Object> body=new LinkedHashMap<>(state);
      body.put("message","Refund outcome is not confirmed yet. The refundable balance remains reserved. Retry with the same Idempotency-Key; do not submit a new refund request.");
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
  }

  @PostMapping("/orders/{orderId}/refunds/{refundId}/retry")
  public ResponseEntity<Map<String,Object>> retryRefund(
      @PathVariable long orderId,@PathVariable long refundId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    CourseRefundLedgerService.PreparedRefund prepared=ledger.prepareRefundForRefresh(refundId,context.staffId());
    if(prepared.orderId()!=orderId)
      throw new IllegalArgumentException("This refund does not belong to the selected course order.");
    if("PROCESSED".equals(prepared.status())||"FAILED".equals(prepared.status()))
      return ResponseEntity.ok(ledger.refundView(refundId));
    try {
      Map<String,Object> payload=new LinkedHashMap<>();
      payload.put("amount",prepared.amountPaise());
      payload.put("receipt","QE-RFND-"+prepared.id());
      payload.put("notes",Map.of(
          "quantaedge_refund_id",Long.toString(prepared.id()),
          "course_payment_order_id",Long.toString(prepared.orderId()),
          "reason",prepared.reason().length()>200?prepared.reason().substring(0,200):prepared.reason()));
      JsonNode provider=providerRequest("POST",
          "/payments/"+prepared.providerPaymentId()+"/refund",mapper.writeValueAsString(payload),
          prepared.providerIdempotencyKey());
      return handleProviderResponse(prepared,provider,context.staffId());
    } catch(Exception providerFailure) {
      Map<String,Object> state=ledger.markReconciliationRequired(
          prepared.id(),context.staffId(),
          "Razorpay response remains inconclusive. This retry reused the saved provider idempotency key.");
      Map<String,Object> body=new LinkedHashMap<>(state);
      body.put("message","Refund outcome is still not confirmed. The refundable balance remains reserved; retry again with this same saved request.");
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
  }

  @GetMapping("/orders/{orderId}/refunds")
  public java.util.List<Map<String,Object>> listOrderRefunds(
      @PathVariable long orderId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    return ledger.refundsForOrder(orderId);
  }

  /** Refreshes a provider-known refund without creating another refund request. */
  @GetMapping("/orders/{orderId}/refunds/{refundId}")
  public ResponseEntity<Map<String,Object>> getOrderRefund(
      @PathVariable long orderId,@PathVariable long refundId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    Map<String,Object> view=ledger.refundView(refundId);
    if(number(view.get("course_payment_order_id"))!=orderId)
      throw new IllegalArgumentException("This refund does not belong to the selected course order.");
    String status=String.valueOf(view.get("status"));
    String providerRefundId=view.get("provider_refund_id")==null?null:String.valueOf(view.get("provider_refund_id"));
    if(providerRefundId==null||"PROCESSED".equals(status)||"FAILED".equals(status))
      return ResponseEntity.ok(view);
    if(!PROVIDER_ID.matcher(providerRefundId).matches())
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    try {
      JsonNode provider=providerRequest("GET","/refunds/"+providerRefundId,null,null);
      CourseRefundLedgerService.PreparedRefund saved=ledger.prepareRefundForRefresh(refundId,context.staffId());
      return handleProviderResponse(saved,provider,context.staffId());
    } catch(Exception inconclusive) {
      Map<String,Object> state=ledger.markReconciliationRequired(refundId,context.staffId(),
          "The provider's current refund status could not be confirmed. The reserved balance remains locked.");
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(state);
    }
  }

  private ResponseEntity<Map<String,Object>> handleProviderResponse(
      CourseRefundLedgerService.PreparedRefund prepared,JsonNode response,long actorStaffId) {
    String providerRefundId=text(response,"id");
    String paymentId=text(response,"payment_id");
    long amount=response.path("amount").asLong(-1);
    String currency=text(response,"currency");
    String status=text(response,"status");
    Map<String,Object> snapshot=mapper.convertValue(response,MAP_TYPE);
    Map<String,Object> result=ledger.applyProviderResult(
        prepared.id(),providerRefundId,paymentId,amount,currency,status,snapshot,actorStaffId);
    String refundStatus=String.valueOf(result.get("status"));
    if("PENDING".equals(refundStatus)||"RECONCILIATION_REQUIRED".equals(refundStatus)
        ||"PROCESSING".equals(refundStatus))
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    return ResponseEntity.ok(result);
  }

  private JsonNode providerRequest(String method,String path,String body,String idempotencyKey) {
    if(keyId.isBlank()||keySecret.isBlank())
      throw new IllegalStateException("Razorpay refund credentials are not configured for HRMS.");
    try {
      String basic=Base64.getEncoder().encodeToString(
          (keyId+":"+keySecret).getBytes(StandardCharsets.UTF_8));
      HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(baseUrl+path))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Authorization","Basic "+basic)
          .header("Accept","application/json");
      if("GET".equalsIgnoreCase(method)) {
        builder.GET();
      } else if("POST".equalsIgnoreCase(method)) {
        builder.header("Content-Type","application/json");
        if(idempotencyKey!=null&&!idempotencyKey.isBlank())
          builder.header("X-Refund-Idempotency",idempotencyKey);
        builder.POST(HttpRequest.BodyPublishers.ofString(body==null?"":body,StandardCharsets.UTF_8));
      } else {
        throw new IllegalArgumentException("Unsupported refund provider request method.");
      }
      HttpResponse<String> response=httpClient.send(builder.build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if(response.statusCode()<200||response.statusCode()>=300) {
        // Even a provider error can follow a lost successful response. Keep the reserved
        // refundable balance and reconcile/retry with the exact same provider idempotency key.
        throw new IllegalStateException("Razorpay refund status is not confirmed (HTTP "+response.statusCode()+").");
      }
      JsonNode json=mapper.readTree(response.body());
      if(json==null||!json.isObject()) throw new IllegalStateException("The provider returned an invalid refund response.");
      return json;
    } catch(IllegalStateException ex) {
      throw ex;
    } catch(Exception ex) {
      throw new IllegalStateException("Razorpay refund status could not be confirmed.",ex);
    }
  }

  private static String text(JsonNode node,String field) {
    JsonNode value=node==null?null:node.get(field);
    return value==null||value.isNull()?"":value.asText("");
  }

  private static long number(Object raw) {
    if(raw instanceof Number n)return n.longValue();
    try { return Long.parseLong(String.valueOf(raw)); }
    catch(Exception ignored) { return 0L; }
  }

  public record RefundRequest(Long amountPaise,String reason) {}
}
