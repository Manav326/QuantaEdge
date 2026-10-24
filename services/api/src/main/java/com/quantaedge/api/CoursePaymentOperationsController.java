package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-owner payment search, diagnosis, reconciliation and immutable action history. */
@RestController
@RequestMapping("/api/v1/hrms/platform/finance/course-orders")
public class CoursePaymentOperationsController {
  private static final List<String> ORDER_STATUSES = List.of("CREATING","PENDING","VERIFIED","FAILED","EXPIRED");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final CourseMarketplaceController marketplace;
  private final CoursePaymentOperationsAuditService audit;

  public CoursePaymentOperationsController(JdbcTemplate jdbc, AuthorizationService authorization,
      CourseMarketplaceController marketplace, CoursePaymentOperationsAuditService audit) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.marketplace = marketplace;
    this.audit = audit;
  }

  @GetMapping
  public Map<String,Object> listOrders(
      @RequestParam(value="status",required=false) String suppliedStatus,
      @RequestParam(value="search",required=false) String suppliedSearch,
      @RequestParam(value="limit",defaultValue="50") int requestedLimit,
      @RequestParam(value="offset",defaultValue="0") int requestedOffset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    String status = normalizeStatus(suppliedStatus);
    String search = suppliedSearch == null ? "" : suppliedSearch.trim();
    if (search.length() > 120) throw new IllegalArgumentException("Payment search can contain at most 120 characters.");
    int limit = Math.max(1,Math.min(requestedLimit,100));
    int offset = Math.max(0,Math.min(requestedOffset,100000));

    String fromAndWhere = """
        from course_payment_order o
        left join institution_tenant t on t.id=o.institution_id
        join curriculum_program p on p.id=o.course_id
        left join student s on s.id=o.student_id
        left join institution_marketplace_offer offer
          on offer.id=o.institution_offer_id and offer.institution_id=o.institution_id
        left join fiscal_document d on d.id=o.fiscal_document_id
        left join lateral (
          select e.event_type,e.resolution_code,e.message,e.created_at
          from course_payment_operations_event e
          where e.course_payment_order_id=o.id
          order by e.created_at desc,e.id desc limit 1
        ) latest on true
        where true
        """;
    List<Object> filterArgs = new ArrayList<>();
    StringBuilder filters = new StringBuilder();
    if (status != null) {
      filters.append(" and o.status=?");
      filterArgs.add(status);
    }
    if (!search.isBlank()) {
      filters.append("""
           and lower(concat_ws(' ',o.id::text,t.display_name,p.display_name,
             s.display_name,o.provider_order_id,o.provider_payment_id,o.failure_code,
             o.discount_reference)) like ?
          """);
      filterArgs.add("%"+search.toLowerCase(Locale.ROOT)+"%");
    }

    Long totalValue = jdbc.queryForObject("select count(*) "+fromAndWhere+filters,Long.class,filterArgs.toArray());
    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<Map<String,Object>> items = jdbc.queryForList("""
        select o.id as local_order_id,o.institution_id,coalesce(t.display_name,'[Institution scope missing]') as institution_name,
          o.course_id,coalesce(nullif(offer.title_override,''),p.display_name) as course_title,
          o.student_id,s.display_name as learner_name,o.purchaser_user_id,o.target_student_id,o.recipient_type,
          o.status,o.failure_code,o.currency,o.listed_price_paise,coalesce(o.discount_amount_paise,0) as discount_amount_paise,
          o.discount_reference,o.offer_price_paise,o.captured_amount_paise,o.captured_at,
          o.provider_order_id,o.provider_payment_id,o.created_at,o.updated_at,o.verified_at,
          o.fiscal_document_id,d.document_number as invoice_number,
          latest.event_type as last_operation_event,latest.resolution_code as last_resolution_code,
          latest.message as last_operation_message,latest.created_at as last_operation_at
        """+fromAndWhere+filters+"""
        order by o.created_at desc,o.id desc limit ? offset ?
        """,pageArgs.toArray());
    for(Map<String,Object> item:items) {
      Resolution resolution=resolutionFor(
          String.valueOf(item.get("status")),
          item.get("fiscal_document_id")!=null,
          item.get("provider_order_id")!=null&&!String.valueOf(item.get("provider_order_id")).isBlank(),
          item.get("failure_code")==null?"":String.valueOf(item.get("failure_code")));
      item.put("resolution_code",resolution.code());
      item.put("recommended_action",resolution.action());
      item.put("resolution_message",resolution.message());
    }
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("items",items);
    response.put("total",totalValue==null?0L:totalValue);
    response.put("limit",limit);
    response.put("offset",offset);
    return response;
  }

  @GetMapping("/{orderId}/events")
  public List<Map<String,Object>> orderEvents(
      @PathVariable long orderId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    Integer count=jdbc.queryForObject("select count(*) from course_payment_order where id=?",Integer.class,orderId);
    if(count==null||count!=1) throw new IllegalArgumentException("Course payment order not found.");
    return jdbc.queryForList("""
        select id,event_type,resolution_code,message,provider_order_id,provider_status,
          details::text as details_json,actor_staff_id,created_at
        from course_payment_operations_event where course_payment_order_id=?
        order by created_at desc,id desc limit 100
        """,orderId);
  }

  /** Signature-verified provider messages, including unmatched events that need finance review. */
  @GetMapping("/provider-events")
  public Map<String,Object> providerEvents(
      @RequestParam(value="status",required=false) String suppliedStatus,
      @RequestParam(value="search",required=false) String suppliedSearch,
      @RequestParam(value="orderId",required=false) Long orderId,
      @RequestParam(value="limit",defaultValue="50") int requestedLimit,
      @RequestParam(value="offset",defaultValue="0") int requestedOffset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    List<String> allowed=List.of("PROCESSING","PROCESSED","IGNORED","UNMATCHED","RECONCILIATION_REQUIRED");
    String status=suppliedStatus==null||suppliedStatus.isBlank()?null:suppliedStatus.trim().toUpperCase(Locale.ROOT);
    if(status!=null&&!allowed.contains(status)) throw new IllegalArgumentException("Select a valid provider-event status.");
    String search=suppliedSearch==null?"":suppliedSearch.trim();
    if(search.length()>160) throw new IllegalArgumentException("Provider-event search can contain at most 160 characters.");
    int limit=Math.max(1,Math.min(requestedLimit,100));
    int offset=Math.max(0,Math.min(requestedOffset,100000));
    if(!providerEventSchemaReady()) {
      Map<String,Object> unavailable=new LinkedHashMap<>();
      unavailable.put("items",List.of());
      unavailable.put("total",0L);
      unavailable.put("limit",limit);
      unavailable.put("offset",offset);
      unavailable.put("schemaReady",false);
      unavailable.put("warning","Razorpay provider-event storage is not available in this database. Apply Flyway migration V164__course_payment_provider_webhook_events.sql to the database used by this API, then restart the API. This is not evidence that the order has no webhooks; its HRMS operation history remains available.");
      return unavailable;
    }
    String where=" where true ";
    List<Object> args=new ArrayList<>();
    if(status!=null){where+=" and e.processing_status=?";args.add(status);}
    if(orderId!=null){where+=" and e.course_payment_order_id=?";args.add(orderId);}
    if(!search.isBlank()) {
      where+=" and lower(concat_ws(' ',e.provider_event_id,e.event_type,e.processing_status,e.status_message,e.provider_order_id,e.provider_payment_id,e.provider_refund_id)) like ?";
      args.add("%"+search.toLowerCase(Locale.ROOT)+"%");
    }
    Long total=jdbc.queryForObject("select count(*) from course_payment_provider_event e"+where,Long.class,args.toArray());
    List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(limit);pageArgs.add(offset);
    List<Map<String,Object>> items=jdbc.queryForList("""
        select e.id,e.provider_event_id,e.event_type,e.course_payment_order_id as local_order_id,
          e.course_payment_refund_id,e.provider_order_id,e.provider_payment_id,e.provider_refund_id,
          e.payload_sha256,e.processing_status,e.status_message,e.details::text as details_json,
          e.received_at,e.processed_at
        from course_payment_provider_event e
        """+where+"""
        order by e.received_at desc,e.id desc limit ? offset ?
        """,pageArgs.toArray());
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("items",items);result.put("total",total==null?0L:total);result.put("limit",limit);result.put("offset",offset);
    return result;
  }

  private boolean providerEventSchemaReady() {
    Boolean ready=jdbc.queryForObject("""
        select to_regclass('public.course_payment_provider_event') is not null
          and (select count(distinct column_name)=14
            from information_schema.columns
            where table_schema='public' and table_name='course_payment_provider_event'
              and column_name in (
                'id','provider_event_id','event_type','course_payment_order_id',
                'course_payment_refund_id','provider_order_id','provider_payment_id',
                'provider_refund_id','payload_sha256','processing_status','status_message',
                'details','received_at','processed_at'))
        """,Boolean.class);
    return Boolean.TRUE.equals(ready);
  }

  @PostMapping("/{orderId}/reconcile")
  public ResponseEntity<Map<String,Object>> reconcileOrder(
      @PathVariable long orderId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    if(context.staffId()==null) throw new SecurityException("A verified HRMS staff identity is required for payment operations.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id,status,provider_order_id,provider_payment_id,failure_code
        from course_payment_order where id=?
        """,orderId);
    if(rows.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
        "status","NOT_FOUND","resolutionCode","ORDER_NOT_FOUND","message","Course payment order not found."));
    Map<String,Object> order=rows.getFirst();
    String currentStatus=String.valueOf(order.get("status"));
    String providerOrderId=text(order.get("provider_order_id"));
    if(!List.of("PENDING","EXPIRED").contains(currentStatus)) {
      String message="Automatic provider reconciliation is blocked for order state "+currentStatus+
          ". Review the saved failure code and provider reference; never mark an order paid manually.";
      audit.record(orderId,context.staffId(),"ACTION_BLOCKED","ORDER_STATE_NOT_RECONCILABLE",
          message,providerOrderId,null,Map.of("orderStatus",currentStatus,"failureCode",text(order.get("failure_code"))));
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
          "status","ACTION_BLOCKED","resolutionCode","ORDER_STATE_NOT_RECONCILABLE","message",message));
    }

    audit.record(orderId,context.staffId(),"RECONCILIATION_REQUESTED","PROVIDER_CHECK_STARTED",
        "HRMS requested a provider check and safe reconciliation of the existing order.",
        providerOrderId,null,Map.of("orderStatus",currentStatus));
    try {
      Map<String,Object> result=marketplace.reconcileOrderForHrms(orderId,context);
      String outcome=String.valueOf(result.getOrDefault("status","UNKNOWN")).toUpperCase(Locale.ROOT);
      boolean canRetry=Boolean.TRUE.equals(result.get("canRetryCheckout"));
      String eventType;
      String code;
      String message;
      if("VERIFIED".equals(outcome) && Boolean.TRUE.equals(result.get("financialSnapshotsRepaired"))) {
        eventType="FINANCIAL_SNAPSHOT_REPAIRED_AND_RECONCILED";
        code="TEST_MODE_FINANCIAL_SNAPSHOT_REPAIRED";
        String basis=text(result.get("financialSnapshotRepairBasis"));
        String basisText="CURRENT_APPROVED_TERMS_FOR_TEST_MODE_RECOVERY".equals(basis)
            ? "using current approved HRMS financial settings, explicitly marked as test-mode recovery"
            : "using approved financial terms effective at the original order creation time";
        message="The existing Razorpay test payment was verified, missing financial snapshots were rebuilt "+basisText
            +", and the existing order was finalized. No replacement order was created.";
      } else if("VERIFIED".equals(outcome)) {
        eventType="RECONCILIATION_SUCCEEDED";
        code="PAYMENT_VERIFIED";
        message="Provider payment is captured and the existing course order is verified. Review its invoice and entitlement references.";
      } else if("RECONCILIATION_REQUIRED".equals(outcome)) {
        eventType="RECONCILIATION_FAILED";
        code="PROVIDER_PAID_NO_EXACT_PAYMENT";
        message=text(result.get("message"));
      } else if(canRetry) {
        eventType="RECONCILIATION_RETRYABLE";
        code="PROVIDER_CONFIRMED_UNPAID";
        message="No authorised or captured payment was found for this saved order. The learner may restart checkout from the marketplace; HRMS must not create a charge.";
      } else {
        eventType="RECONCILIATION_PENDING";
        code="PROVIDER_STILL_PROCESSING";
        message="Provider status is not final. Keep this order and do not ask the learner to pay again.";
      }
      result.put("resolutionCode",code);
      result.put("resolutionMessage",message);
      Map<String,Object> details=diagnosticDetails(result);
      if(Boolean.TRUE.equals(result.get("financialSnapshotsRepaired"))) {
        details.put("financialSnapshotsRepaired",true);
        details.put("financialSnapshotRepairMode",text(result.get("financialSnapshotRepairMode")));
        details.put("financialSnapshotRepairBasis",text(result.get("financialSnapshotRepairBasis")));
      }
      audit.record(orderId,context.staffId(),eventType,code,message,
          text(result.get("providerOrderId")),text(result.get("providerOrderStatus")),details);
      return ResponseEntity.ok(result);
    } catch(RuntimeException exception) {
      String rawMessage=exception instanceof org.springframework.web.server.ResponseStatusException statusException
          ? statusException.getReason() : exception.getMessage();
      String message=safeError(rawMessage);
      boolean snapshotRepairBlocked=rawMessage!=null
          && (rawMessage.toLowerCase(Locale.ROOT).contains("snapshot")
              || rawMessage.toLowerCase(Locale.ROOT).contains("financial terms"));
      String eventType=snapshotRepairBlocked?"FINANCIAL_SNAPSHOT_REPAIR_BLOCKED":"RECONCILIATION_FAILED";
      String resolutionCode=snapshotRepairBlocked?"SNAPSHOT_REPAIR_BLOCKED":"PROVIDER_CHECK_FAILED";
      if(snapshotRepairBlocked&&!message.toLowerCase(Locale.ROOT).contains("do not pay again"))
        message=message+" Keep this saved order; do not pay again until the financial issue is resolved.";
      Map<String,Object> details=new LinkedHashMap<>();
      details.put("failureCode",text(order.get("failure_code")));
      details.put("orderStatus",currentStatus);
      details.put("snapshotRepairBlocked",snapshotRepairBlocked);
      audit.record(orderId,context.staffId(),eventType,resolutionCode,
          message,providerOrderId,null,details);
      Map<String,Object> response=new LinkedHashMap<>();
      response.put("status","RECONCILIATION_REQUIRED");
      response.put("resolutionCode",resolutionCode);
      response.put("resolutionMessage",message);
      response.put("message",message);
      response.put("canRetryCheckout",false);
      response.put("providerOrderId",providerOrderId);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
  }

  static String normalizeStatus(String status) {
    if(status==null||status.isBlank()) return null;
    String normalized=status.trim().toUpperCase(Locale.ROOT);
    if(!ORDER_STATUSES.contains(normalized)) throw new IllegalArgumentException("Select a valid course payment status.");
    return normalized;
  }

  static Resolution resolutionFor(String status,boolean hasInvoice,boolean hasProviderOrder,String failureCode) {
    String normalized=status==null?"":status.trim().toUpperCase(Locale.ROOT);
    if("VERIFIED".equals(normalized)&&!hasInvoice)
      return new Resolution("VERIFIED_INVOICE_REVIEW","Open the financial snapshot and reconcile the missing document; do not charge again.",
          "Payment is verified but the sale invoice reference is missing. Escalate to finance; do not reissue automatically.");
    if("VERIFIED".equals(normalized))
      return new Resolution("COMPLETE","No payment action required.","Payment is verified. Confirm the customer invoice and course entitlement if the customer reports a problem.");
    if("PENDING".equals(normalized)||"EXPIRED".equals(normalized)) {
      if(!hasProviderOrder)
        return new Resolution("PROVIDER_REFERENCE_MISSING","Escalate the order-creation reference to engineering.",
            "The local order has no saved Razorpay order ID. Do not create another payment or mark this one paid.");
      return new Resolution("PROVIDER_RECONCILIATION","Check provider status and reconcile this same order.",
          "Query Razorpay for this saved order and its payments. If captured, finalize the existing order; if still processing, wait; if confirmed unpaid, the learner may restart checkout.");
    }
    if("CREATING".equals(normalized))
      return new Resolution("ORDER_CREATION_INCOMPLETE","Inspect order-creation logs and saved provider references.",
          "Order creation did not reach a stable state. Verify no provider order or payment exists before allowing a new attempt.");
    if("FAILED".equals(normalized))
      return new Resolution("FAILED_ORDER_REVIEW","Review the saved failure code and provider references.",
          "This order is marked failed and is not eligible for automatic reconciliation. Confirm provider state before advising another attempt.");
    return new Resolution("MANUAL_REVIEW","Review the payment ledger before taking action.",
        "An unfamiliar order state requires finance/engineering review. Do not edit payment state manually.");
  }

  private static Map<String,Object> diagnosticDetails(Map<String,Object> result) {
    Map<String,Object> details=new LinkedHashMap<>();
    for(String key:List.of("providerOrderStatus","providerAmountPaidPaise","providerAttemptCount","paymentAttempts","entitled","canRetryCheckout"))
      if(result.containsKey(key)) details.put(key,result.get(key));
    return details;
  }

  private static String safeError(String raw) {
    if(raw==null||raw.isBlank()) return "Provider status could not be confirmed. Keep the order reserved and retry reconciliation; do not take another payment.";
    String safe=raw.replaceAll("[\\p{Cntrl}&&[^\\t]]"," ").replaceAll("\\s+"," ").trim();
    if(safe.length()>1000)safe=safe.substring(0,1000);
    return safe;
  }

  private static String text(Object value) { return value==null?"":String.valueOf(value); }

  record Resolution(String code,String action,String message) {}
}
