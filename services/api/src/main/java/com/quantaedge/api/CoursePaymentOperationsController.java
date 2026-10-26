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

  /**
   * Read-only delivery-chain diagnosis for any saved course-payment order. It combines local
   * checkout, financial snapshots, access, distribution and invoice facts with a read-only
   * Razorpay lookup. It never changes the order or advances any transaction stage.
   */
  @GetMapping("/{orderId}/status-trace")
  public Map<String,Object> statusTrace(
      @PathVariable long orderId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    List<Map<String,Object>> orders=jdbc.queryForList("""
        select o.id as local_order_id,o.institution_id,o.course_id,o.student_id,o.purchaser_user_id,
          o.target_student_id,o.recipient_type,o.status,o.failure_code,o.currency,o.offer_price_paise,
          o.listed_price_paise,o.discount_offer_id,o.discount_amount_paise,o.discount_reference,o.tax_profile_id,
          o.revenue_share_policy_id,o.taxable_value_paise,o.output_tax_paise,
          o.tax_calculation_snapshot::text as tax_calculation_snapshot_json,
          o.revenue_share_policy_snapshot::text as revenue_share_policy_snapshot_json,
          o.discount_snapshot::text as discount_snapshot_json,
          o.buyer_snapshot::text as buyer_snapshot_json,
          o.tax_calculation_snapshot->>'customerPayablePaise' as snapshot_payable_paise,
          o.tax_calculation_snapshot->>'listedPricePaise' as snapshot_listed_price_paise,
          o.tax_calculation_snapshot->>'taxProfileId' as snapshot_tax_profile_id,
          o.tax_calculation_snapshot->>'taxableValuePaise' as snapshot_taxable_value_paise,
          o.tax_calculation_snapshot->>'outputTaxPaise' as snapshot_output_tax_paise,
          o.tax_calculation_snapshot->>'discountOfferId' as tax_snapshot_discount_offer_id,
          o.tax_calculation_snapshot->>'discountCode' as tax_snapshot_discount_code,
          o.tax_calculation_snapshot->>'discountPaise' as tax_snapshot_discount_paise,
          o.discount_snapshot->>'discountOfferId' as discount_snapshot_offer_id,
          o.discount_snapshot->>'code' as discount_snapshot_code,
          o.discount_snapshot->>'discountPaise' as discount_snapshot_discount_paise,
          o.revenue_share_policy_snapshot->>'policyId' as snapshot_share_policy_id,
          case when jsonb_typeof(o.revenue_share_policy_snapshot->'recipients')='array'
            then jsonb_array_length(o.revenue_share_policy_snapshot->'recipients') else 0 end as share_recipient_count,
          o.provider_order_id,o.provider_payment_id,o.captured_amount_paise,o.captured_at,
          o.created_at,o.updated_at,o.verified_at,o.fiscal_document_id,
          coalesce(nullif(offer.title_override,''),p.display_name) as course_title,
          t.display_name as institution_name
        from course_payment_order o
        join curriculum_program p on p.id=o.course_id
        left join institution_tenant t on t.id=o.institution_id
        left join institution_marketplace_offer offer
          on offer.id=o.institution_offer_id and offer.institution_id=o.institution_id
        where o.id=?
        """,orderId);
    if(orders.isEmpty()) throw new IllegalArgumentException("Course payment order not found.");
    Map<String,Object> order=orders.getFirst();
    String localStatus=text(order.get("status")).toUpperCase(Locale.ROOT);
    long courseId=number(order.get("course_id"));
    long institutionId=number(order.get("institution_id"));
    long payablePaise=number(order.get("offer_price_paise"));
    String currency=text(order.get("currency"));
    String providerOrderId=text(order.get("provider_order_id"));
    List<Map<String,Object>> steps=new ArrayList<>();

    steps.add(traceStep("CHECKOUT_INITIATED","1. Checkout initiated","COMPLETE",
        "QuantaEdge saved order QE-ORDER-"+orderId+" for "+text(order.get("course_title")!=null?order.get("course_title"):"course #"+courseId)+".",
        List.of("Local order status: "+localStatus,"Institution: "+text(order.get("institution_name")),
            "Created: "+text(order.get("created_at")),"Last updated: "+text(order.get("updated_at"))),
        order.get("created_at")));

    boolean financialSnapshotsPresent=CourseMarketplaceController.hasLockedFinancialSnapshots(order);
    boolean payableSnapshotMatches=numberOrMinusOne(order.get("snapshot_payable_paise"))==payablePaise;
    boolean listedPriceSnapshotMatches=numberOrMinusOne(order.get("snapshot_listed_price_paise"))==number(order.get("listed_price_paise"));
    boolean taxProfileSnapshotMatches=numberOrMinusOne(order.get("snapshot_tax_profile_id"))==number(order.get("tax_profile_id"));
    boolean taxableValueSnapshotMatches=snapshotNumberMatches(order,"snapshot_taxable_value_paise","taxable_value_paise");
    boolean outputTaxSnapshotMatches=snapshotNumberMatches(order,"snapshot_output_tax_paise","output_tax_paise");
    boolean sharePolicySnapshotMatches=numberOrMinusOne(order.get("snapshot_share_policy_id"))==number(order.get("revenue_share_policy_id"));
    boolean buyerSnapshotPresent=presentJsonObject(order.get("buyer_snapshot_json"));
    boolean discountSnapshotMatches=discountSnapshotMatches(order);
    boolean financialFactsValid=financialSnapshotsPresent&&payableSnapshotMatches&&listedPriceSnapshotMatches
        &&taxProfileSnapshotMatches&&taxableValueSnapshotMatches&&outputTaxSnapshotMatches
        &&sharePolicySnapshotMatches&&discountSnapshotMatches&&buyerSnapshotPresent;
    String financialStatus=financialFactsValid?"COMPLETE":"BLOCKED";
    List<String> financialDetails=new ArrayList<>();
    financialDetails.add("Tax profile reference: "+(order.get("tax_profile_id")==null?"missing":String.valueOf(order.get("tax_profile_id"))));
    financialDetails.add("Revenue-share policy reference: "+(order.get("revenue_share_policy_id")==null?"missing":String.valueOf(order.get("revenue_share_policy_id"))));
    financialDetails.add("Snapshot payable matches saved order: "+payableSnapshotMatches);
    financialDetails.add("Snapshot listed price matches saved order: "+listedPriceSnapshotMatches);
    financialDetails.add("Tax snapshot references match: "+taxProfileSnapshotMatches);
    financialDetails.add("Snapshot taxable value matches saved order: "+taxableValueSnapshotMatches);
    financialDetails.add("Snapshot output tax matches saved order: "+outputTaxSnapshotMatches);
    financialDetails.add("Revenue-share snapshot references match: "+sharePolicySnapshotMatches);
    financialDetails.add("Discount snapshot matches saved order: "+discountSnapshotMatches);
    financialDetails.add("Original buyer snapshot present: "+buyerSnapshotPresent);
    if(!financialFactsValid) financialDetails.add("The HRMS configuration screen does not itself backfill locked financial facts on an already-created order.");
    steps.add(traceStep("FINANCIAL_SNAPSHOTS","2. Financial snapshots locked",financialStatus,
        financialFactsValid
            ?"Tax, revenue-share and buyer snapshots are present and their references/payable amount match this order."
            :"The saved order's financial snapshots are missing or inconsistent. This is a QuantaEdge-side blocker; inspect the exact missing facts before any finalization.",
        financialDetails,null));

    Map<String,Object> provider=marketplace.inspectProviderStatusForHrms(
        providerOrderId,payablePaise,currency);
    boolean providerQueryAvailable=Boolean.TRUE.equals(provider.get("providerQueryAvailable"));
    boolean providerOrderMatches=Boolean.TRUE.equals(provider.get("providerOrderMatches"));
    String providerStatus=text(provider.get("providerStatus")).toUpperCase(Locale.ROOT);
    boolean providerAmountMatch=Boolean.TRUE.equals(provider.get("providerAmountCurrencyMatch"));
    String providerMode=text(provider.get("matchedProviderMode"));
    List<String> providerDetails=new ArrayList<>();
    providerDetails.add("Saved Razorpay order: "+(providerOrderId.isBlank()?"missing":providerOrderId));
    providerDetails.add("Credential mode used: "+(providerMode.isBlank()?text(provider.get("configuredProviderMode")):providerMode));
    providerDetails.add("Provider order status: "+text(provider.get("providerOrderStatus")));
    providerDetails.add("Provider amount / currency match: "+providerAmountMatch);
    providerDetails.add("Provider attempts: "+text(provider.get("providerAttemptCount")));
    if(provider.get("message")!=null) providerDetails.add(text(provider.get("message")));
    String providerOrderStage;
    String providerOrderSummary;
    if("NO_PROVIDER_REFERENCE".equals(providerStatus)) {
      providerOrderStage="BLOCKED";
      providerOrderSummary="The local checkout has no saved Razorpay order reference.";
    } else if(providerQueryAvailable&&providerOrderMatches&&providerAmountMatch) {
      providerOrderStage="COMPLETE";
      providerOrderSummary="The exact saved Razorpay order was found and its amount/currency match the local order.";
    } else if(providerQueryAvailable&&providerOrderMatches) {
      providerOrderStage="BLOCKED";
      providerOrderSummary="Razorpay returned this order, but its amount or currency differs from the saved order.";
    } else {
      providerOrderStage="UNKNOWN";
      providerOrderSummary="The saved Razorpay reference could not be authoritatively confirmed with the configured credentials.";
    }
    steps.add(traceStep("PROVIDER_ORDER","3. Razorpay order linked",providerOrderStage,
        providerOrderSummary,providerDetails,null));

    boolean capturedProviderPayment=providerQueryAvailable&&"CAPTURED".equals(providerStatus);
    boolean authorizedProviderPayment=providerQueryAvailable&&"AUTHORIZED".equals(providerStatus);
    boolean providerPaymentProcessing=providerQueryAvailable&&"PROCESSING".equals(providerStatus);
    String paymentStage;
    String paymentSummary;
    if(capturedProviderPayment) {
      paymentStage="COMPLETE";
      paymentSummary="Razorpay reports an exact captured payment for this order.";
    } else if(authorizedProviderPayment) {
      paymentStage="PENDING";
      paymentSummary="Razorpay reports an authorised payment; capture/final verification is not complete.";
    } else if(providerPaymentProcessing) {
      paymentStage="PENDING";
      paymentSummary="Razorpay still has a payment attempt in progress.";
    } else if(providerQueryAvailable&&"NOT_PAID".equals(providerStatus)) {
      paymentStage="PENDING";
      paymentSummary="No exact captured/authorised payment was found for the saved Razorpay order.";
    } else if(providerQueryAvailable&&"PAID_NO_EXACT_PAYMENT".equals(providerStatus)) {
      paymentStage="BLOCKED";
      paymentSummary="Razorpay reports money paid, but there is no exact matching payment attempt in the returned list. Do not pay again.";
    } else if(providerQueryAvailable&&"ORDER_AMOUNT_CURRENCY_MISMATCH".equals(providerStatus)) {
      paymentStage="BLOCKED";
      paymentSummary="The Razorpay amount or currency does not match QuantaEdge's saved order. Do not retry payment.";
    } else {
      paymentStage="UNKNOWN";
      paymentSummary="The payment provider state is not confirmed. This is not evidence that payment failed.";
    }
    List<String> paymentDetails=new ArrayList<>();
    paymentDetails.add("Local order status: "+localStatus);
    paymentDetails.add("Saved payment ID: "+(order.get("provider_payment_id")==null?"not recorded":text(order.get("provider_payment_id"))));
    paymentDetails.add("Saved captured amount: "+(order.get("captured_amount_paise")==null?"not recorded":String.valueOf(order.get("captured_amount_paise"))+" paise"));
    paymentDetails.add("Provider amount paid: "+text(provider.get("providerAmountPaidPaise"))+" paise");
    paymentDetails.add("Provider attempt count: "+text(provider.get("providerAttemptCount")));
    steps.add(traceStep("PROVIDER_PAYMENT","4. Payment confirmed by Razorpay",paymentStage,
        paymentSummary,paymentDetails,order.get("captured_at")));

    String verificationStage;
    String verificationSummary;
    if("VERIFIED".equals(localStatus)) {
      verificationStage="COMPLETE";
      verificationSummary="QuantaEdge finalized the saved order and recorded its verification timestamp.";
    } else if(capturedProviderPayment&&!financialFactsValid) {
      verificationStage="BLOCKED";
      verificationSummary="Razorpay indicates payment was captured, but QuantaEdge cannot finalize this order because the saved financial snapshots are missing or inconsistent.";
    } else if(capturedProviderPayment||authorizedProviderPayment) {
      verificationStage="BLOCKED";
      verificationSummary="A provider payment exists, but the local order is not VERIFIED. Inspect the financial-snapshot and finalization blockers before retrying.";
    } else if("CREATING".equals(localStatus)||"PENDING".equals(localStatus)||"EXPIRED".equals(localStatus)) {
      verificationStage="PENDING";
      verificationSummary="QuantaEdge has not recorded successful payment verification for this order.";
    } else {
      verificationStage="BLOCKED";
      verificationSummary="The local order is in "+localStatus+" and did not reach verified settlement.";
    }
    steps.add(traceStep("QE_VERIFICATION","5. QuantaEdge verifies and finalizes payment",verificationStage,
        verificationSummary,List.of("Order status: "+localStatus,
            "Verified at: "+text(order.get("verified_at")),
            "Captured at: "+text(order.get("captured_at"))),order.get("verified_at")));

    List<Map<String,Object>> purchases=jdbc.queryForList("""
        select id,provider_payment_id,amount_paise,currency,purchased_at
        from course_purchase where order_id=? order by id limit 2
        """,orderId);
    Map<String,Object> purchase=purchases.isEmpty()?Map.of():purchases.getFirst();
    Long purchaseId=purchases.isEmpty()?null:number(purchase.get("id"));
    boolean purchaseExists=purchaseId!=null;
    String purchaseStage=purchaseExists?"COMPLETE":("VERIFIED".equals(localStatus)?"BLOCKED":"NOT_REACHED");
    steps.add(traceStep("COURSE_PURCHASE","6. Course purchase recorded",purchaseStage,
        purchaseExists?"The local course_purchase ledger contains this order."
            :"VERIFIED".equals(localStatus)
                ?"The order is verified, but its matching course_purchase ledger row is missing."
                :"The purchase ledger is created during finalization and has not yet been recorded.",
        purchaseExists?List.of("Purchase record: #"+purchaseId,
            "Recorded payment ID: "+text(purchase.get("provider_payment_id")),
            "Amount: "+text(purchase.get("amount_paise"))+" "+text(purchase.get("currency")),
            "Purchased at: "+text(purchase.get("purchased_at"))):List.of(),purchase.get("purchased_at")));

    long entitlementCount=0;
    if(purchaseId!=null) entitlementCount=scalarCount(
        "select count(*) from course_entitlement where source_purchase_id=? and course_id=?",purchaseId,courseId);
    String entitlementStage=entitlementCount>0?"COMPLETE":purchaseExists?"BLOCKED":"NOT_REACHED";
    steps.add(traceStep("COURSE_ENTITLEMENT","7. Course entitlement granted",entitlementStage,
        entitlementCount>0?"A course_entitlement row links this purchase to a current holder."
            :purchaseExists?"The purchase exists, but its normalized course entitlement is missing."
                :"No entitlement is expected until the purchase ledger is finalized.",
        List.of("Matching entitlements: "+entitlementCount),null));

    List<Map<String,Object>> enrollments=jdbc.queryForList("""
        select status,enrolled_at,ended_at
        from student_program_enrollment
        where institution_id=? and student_id=? and program_id=?
        order by updated_at desc limit 2
        """,institutionId,order.get("student_id"),courseId);
    boolean activeEnrollment=enrollments.stream().anyMatch(e->"ACTIVE".equalsIgnoreCase(text(e.get("status"))));
    boolean pausedEnrollment=enrollments.stream().anyMatch(e->"PAUSED".equalsIgnoreCase(text(e.get("status"))));
    String enrollmentStage=activeEnrollment?"COMPLETE":purchaseExists?"BLOCKED":"NOT_REACHED";
    List<String> enrollmentDetails=new ArrayList<>();
    enrollmentDetails.add("Active enrollment: "+activeEnrollment);
    enrollmentDetails.add("Paused enrollment: "+pausedEnrollment);
    if(!enrollments.isEmpty()) enrollmentDetails.add("Latest enrollment status: "+text(enrollments.getFirst().get("status")));
    steps.add(traceStep("LEARNING_ENROLLMENT","8. Learning access activated",enrollmentStage,
        activeEnrollment?"The beneficiary has an ACTIVE student-program enrollment."
            :pausedEnrollment?"The beneficiary's course enrollment is PAUSED."
                :purchaseExists?"The purchase exists but no active course enrollment was found."
                    :"Enrollment is created only after the course purchase is finalized.",
        enrollmentDetails,enrollments.isEmpty()?null:enrollments.getFirst().get("enrolled_at")));

    Map<String,Object> allocations=jdbc.queryForMap("""
        select count(*) as allocation_count,
          coalesce(sum(allocated_amount_paise),0) as allocated_amount_paise,
          count(distinct institution_id) as recipient_count
        from revenue_share_allocation where course_payment_order_id=?
        """,orderId);
    long allocationCount=number(allocations.get("allocation_count"));
    long expectedRecipients=number(order.get("share_recipient_count"));
    String allocationStage;
    if(allocationCount>0&&(!financialSnapshotsPresent||allocationCount>=expectedRecipients&&expectedRecipients>0)) {
      allocationStage="COMPLETE";
    } else if("VERIFIED".equals(localStatus)) {
      allocationStage="BLOCKED";
    } else {
      allocationStage="NOT_REACHED";
    }
    steps.add(traceStep("REVENUE_SHARE","9. Revenue-share ledger allocated",allocationStage,
        allocationStage.equals("COMPLETE")?"Institutional allocations are recorded against this saved course order."
            :allocationStage.equals("BLOCKED")?"The order is verified but the expected revenue-share allocations are missing or incomplete."
                :"Revenue share is allocated only after payment finalization.",
        List.of("Recorded allocations: "+allocationCount,
            "Expected recipients from saved policy snapshot: "+expectedRecipients,
            "Total allocated: "+number(allocations.get("allocated_amount_paise"))+" paise",
            "Distinct allocation recipients: "+number(allocations.get("recipient_count"))),null));

    List<Map<String,Object>> invoiceRows=jdbc.queryForList("""
        select d.id,d.document_number,d.document_type,d.issued_at,d.total_value_paise,d.tax_value_paise,
          (select count(*) from fiscal_document_line line where line.fiscal_document_id=d.id) as line_count,
          (select count(*) from fiscal_document_asset asset where asset.fiscal_document_id=d.id
            and asset.asset_type in ('PDF','SIGNED_PDF')) as pdf_asset_count,
          (select coalesce(sum(asset.byte_length),0) from fiscal_document_asset asset
            where asset.fiscal_document_id=d.id and asset.asset_type in ('PDF','SIGNED_PDF')) as pdf_bytes
        from fiscal_document d
        where d.id=? or (d.source_type='COURSE_ORDER' and d.source_record_key=? and d.document_type in ('TAX_INVOICE','BILL_OF_SUPPLY'))
        order by case when d.id=? then 0 else 1 end,d.id desc limit 1
        """,order.get("fiscal_document_id"),String.valueOf(orderId),order.get("fiscal_document_id"));
    Map<String,Object> invoice=invoiceRows.isEmpty()?Map.of():invoiceRows.getFirst();
    boolean invoiceExists=!invoice.isEmpty();
    long pdfCount=invoiceExists?number(invoice.get("pdf_asset_count")):0;
    String invoiceStage=invoiceExists&&pdfCount>0?"COMPLETE"
        :invoiceExists?"BLOCKED":"VERIFIED".equals(localStatus)?"BLOCKED":"NOT_REACHED";
    steps.add(traceStep("INVOICE_GENERATED","10. Invoice generated and stored",invoiceStage,
        invoiceStage.equals("COMPLETE")?"A fiscal invoice and its PDF asset are recorded."
            :invoiceStage.equals("BLOCKED")&&invoiceExists?"The fiscal document exists, but no PDF/SIGNED_PDF asset is recorded."
                :invoiceStage.equals("BLOCKED")?"The order is verified but no invoice document is linked or found for this order."
                    :"The fiscal invoice is generated during finalization and has not yet been recorded.",
        invoiceExists?List.of("Document: "+text(invoice.get("document_number"))+" (#"+text(invoice.get("id"))+")",
            "Type: "+text(invoice.get("document_type")),
            "Lines: "+text(invoice.get("line_count")),
            "PDF assets: "+pdfCount+" · bytes: "+text(invoice.get("pdf_bytes")),
            "Issued at: "+text(invoice.get("issued_at"))):List.of(),invoice.get("issued_at")));

    List<Map<String,Object>> blockers=steps.stream()
        .filter(step->"BLOCKED".equals(text(step.get("status"))))
        .toList();
    List<Map<String,Object>> pending=steps.stream()
        .filter(step->"PENDING".equals(text(step.get("status"))))
        .toList();
    String overall= !blockers.isEmpty()?"BLOCKED":!pending.isEmpty()?"PENDING"
        :steps.stream().anyMatch(step->"UNKNOWN".equals(text(step.get("status"))))?"UNKNOWN":"COMPLETE";
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("orderId",orderId);
    result.put("courseId",courseId);
    result.put("courseTitle",text(order.get("course_title")));
    result.put("institutionName",text(order.get("institution_name")));
    result.put("localOrderStatus",localStatus);
    result.put("createdAt",order.get("created_at"));
    result.put("checkedAt",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
    result.put("overallStatus",overall);
    result.put("provider",provider);
    result.put("financialFacts",Map.of(
        "listedPricePaise",number(order.get("listed_price_paise")),
        "discountAmountPaise",number(order.get("discount_amount_paise")),
        "payablePaise",payablePaise,
        "capturedAmountPaise",order.get("captured_amount_paise")==null?0L:number(order.get("captured_amount_paise")),
        "currency",currency,
        "financialSnapshotsPresent",financialSnapshotsPresent,
        "buyerSnapshotPresent",buyerSnapshotPresent));
    result.put("steps",steps);
    result.put("blockers",blockers);
    result.put("pendingSteps",pending);
    result.put("readOnly",true);
    result.put("message","Read-only status trace. No payment was created, captured, refunded, reconciled or otherwise changed.");
    return result;
  }

  private static boolean discountSnapshotMatches(Map<String,Object> order) {
    long savedDiscount=number(order.get("discount_amount_paise"));
    long savedOfferId=numberOrMinusOne(order.get("discount_offer_id"));
    String savedCode=text(order.get("discount_reference"));
    long taxDiscountOffer=numberOrMinusOne(order.get("tax_snapshot_discount_offer_id"));
    long taxDiscountAmount=numberOrMinusOne(order.get("tax_snapshot_discount_paise"));
    long snapshotDiscountAmount=numberOrMinusOne(order.get("discount_snapshot_discount_paise"));
    if(savedOfferId<0) {
      return savedDiscount==0&&savedCode.isBlank()
          &&taxDiscountOffer==0&&taxDiscountAmount==0&&snapshotDiscountAmount==0;
    }
    return savedOfferId>0&&savedDiscount>0&&!savedCode.isBlank()
        &&numberOrMinusOne(order.get("discount_snapshot_offer_id"))==savedOfferId
        &&numberOrMinusOne(order.get("tax_snapshot_discount_offer_id"))==savedOfferId
        &&savedCode.equals(text(order.get("discount_snapshot_code")))
        &&savedCode.equals(text(order.get("tax_snapshot_discount_code")))
        &&savedDiscount==snapshotDiscountAmount&&savedDiscount==taxDiscountAmount;
  }

  private long scalarCount(String sql,Object...args) {
    Long value=jdbc.queryForObject(sql,Long.class,args);
    return value==null?0L:value;
  }

  private static Map<String,Object> traceStep(String key,String title,String status,
      String summary,List<String> details,Object occurredAt) {
    Map<String,Object> step=new LinkedHashMap<>();
    step.put("key",key);step.put("title",title);step.put("status",status);step.put("summary",summary);
    step.put("details",details==null?List.of():details);
    step.put("occurredAt",occurredAt);
    return step;
  }

  private static boolean presentJsonObject(Object raw) {
    if(raw==null) return false;
    String value=String.valueOf(raw).trim();
    return !value.isBlank()&&!"{}".equals(value)&&!"null".equalsIgnoreCase(value);
  }

  private static long numberOrMinusOne(Object value) {
    if(value==null) return -1;
    try { return Long.parseLong(String.valueOf(value)); } catch(Exception ignored) { return -1; }
  }

  static boolean snapshotNumberMatches(Map<String,Object> row,String snapshotKey,String savedKey) {
    return numberOrMinusOne(row.get(snapshotKey))==number(row.get(savedKey));
  }

  private static long number(Object value) {
    if(value instanceof Number number) return number.longValue();
    try { return value==null?0:Long.parseLong(String.valueOf(value)); } catch(Exception ignored) { return 0; }
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
      } else if("PAYMENT_VERIFIED_ACCESS_GRANTED".equals(outcome)
          && Boolean.TRUE.equals(result.get("financeReconciliationRequired"))) {
        eventType="COURSE_ACCESS_GRANTED_FINANCE_PENDING";
        code="ACCESS_GRANTED_FINANCE_RECONCILIATION_PENDING";
        message=text(result.get("message"));
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
