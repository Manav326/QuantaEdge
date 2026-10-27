package com.quantaedge.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Authoritative refund ledger. A credit note and institutional allocation reversals are only
 * posted after the provider confirms that the refund is processed.
 */
@Service
public class CourseRefundLedgerService {
  private static final ZoneId INDIA=ZoneId.of("Asia/Kolkata");
  private static final TypeReference<Map<String,Object>> MAP_TYPE=new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final FiscalDocumentRenderer renderer;
  private final FiscalDocumentStorage storage;

  public record PreparedRefund(
      long id,long orderId,String providerPaymentId,String providerIdempotencyKey,
      long amountPaise,String currency,String reason,String status,String providerRefundId,
      Long creditNoteDocumentId) {}

  public CourseRefundLedgerService(
      JdbcTemplate jdbc,ObjectMapper mapper,FiscalDocumentRenderer renderer,FiscalDocumentStorage storage) {
    this.jdbc=jdbc;
    this.mapper=mapper;
    this.renderer=renderer;
    this.storage=storage;
  }

  @Transactional
  public PreparedRefund prepareRefund(
      long orderId,long amountPaise,String reason,String requestIdempotencyKey,long actorStaffId) {
    String requestKey=requiredKey(requestIdempotencyKey);
    String normalizedReason=reason==null?"":reason.trim();
    if(normalizedReason.length()<10||normalizedReason.length()>500)
      throw new IllegalArgumentException("Refund reason must contain 10–500 characters.");
    if(amountPaise<100)
      throw new IllegalArgumentException("A Razorpay course refund must be at least ₹1.00.");

    Map<String,Object> order=one("""
      select id,status,provider_payment_id,captured_amount_paise,currency,fiscal_document_id,
        institution_id,course_id,student_id,purchaser_user_id
      from course_payment_order where id=? for update
      """,orderId);
    if(order.isEmpty()) throw new IllegalArgumentException("The course payment order was not found.");
    PreparedRefund previous=findByRequestKey(requestKey);
    if(previous!=null) {
      if(previous.orderId()!=orderId||previous.amountPaise()!=amountPaise
          ||!previous.reason().equals(normalizedReason))
        throw new IllegalArgumentException("This idempotency key was already used for a different refund request.");
      return previous;
    }
    // Recheck after locking the order: concurrent duplicate submissions for the same order
    // serialize here before calculating remaining refundable balance.
    previous=findByRequestKey(requestKey);
    if(previous!=null) {
      if(previous.orderId()!=orderId||previous.amountPaise()!=amountPaise
          ||!previous.reason().equals(normalizedReason))
        throw new IllegalArgumentException("This idempotency key was already used for a different refund request.");
      return previous;
    }
    if(!"VERIFIED".equals(String.valueOf(order.get("status"))))
      throw new IllegalStateException("Only a verified course payment can be refunded.");
    String paymentId=nullableText(order.get("provider_payment_id"));
    if(paymentId==null) throw new IllegalStateException("The verified course order has no provider payment reference.");
    Long captured=nullableLong(order.get("captured_amount_paise"));
    Long originalDocument=nullableLong(order.get("fiscal_document_id"));
    if(captured==null||captured<=0||originalDocument==null)
      throw new IllegalStateException("The captured payment or original fiscal document is missing; refund is blocked.");
    Map<String,Object> invoice=one("""
      select id,document_type,total_value_paise,amount_paid_paise
      from fiscal_document where id=?
      """,originalDocument);
    if(invoice.isEmpty()||!("TAX_INVOICE".equals(string(invoice.get("document_type")))
        ||"BILL_OF_SUPPLY".equals(string(invoice.get("document_type"))))
        ||number(invoice.get("total_value_paise"))!=captured
        ||number(invoice.get("amount_paid_paise"))!=captured)
      throw new IllegalStateException("The original sale document does not reconcile to the captured amount.");

    assertRefundAllowedForEntitlement(orderId);

    Long alreadyReserved=jdbc.queryForObject("""
      select coalesce(sum(requested_amount_paise),0)
      from course_payment_refund
      where course_payment_order_id=?
        and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED','PROCESSED')
      """,Long.class,orderId);
    long reserved=alreadyReserved==null?0L:alreadyReserved;
    long remaining;
    try { remaining=Math.subtractExact(captured,reserved); }
    catch(ArithmeticException ex) { throw new IllegalStateException("Refund balance is inconsistent.",ex); }
    if(amountPaise>remaining)
      throw new IllegalArgumentException("This refund exceeds the remaining refundable balance of ₹"
          +BigDecimal.valueOf(Math.max(remaining,0L),2).toPlainString()+".");

    String providerIdempotencyKey="QE-REFUND-"+UUID.randomUUID().toString().replace("-","");
    Long refundId=jdbc.queryForObject("""
      insert into course_payment_refund(
        course_payment_order_id,request_idempotency_key,provider_idempotency_key,
        provider_payment_id,requested_amount_paise,currency,reason,status,requested_by_staff_id
      ) values(?,?,?,?,?,'INR',?,'PROCESSING',?) returning id
      """,Long.class,orderId,requestKey,providerIdempotencyKey,paymentId,amountPaise,
        normalizedReason,actorStaffId);
    if(refundId==null) throw new IllegalStateException("The refund request could not be recorded.");
    event(refundId,"REQUESTED","course-refund:"+refundId+":requested",actorStaffId,
        Map.of("orderId",orderId,"amountPaise",amountPaise,"currency","INR"));
    return refundById(refundId);
  }

  /** Blocks automated refunds for purchases whose course entitlement was permanently transferred. */
  void assertRefundAllowedForEntitlement(long orderId) {
    Integer transferred=jdbc.queryForObject("""
      select count(*) from course_purchase cp
      join course_entitlement ce on ce.source_purchase_id=cp.id
      where cp.order_id=? and ce.transfer_count=1
      """,Integer.class,orderId);
    if(transferred!=null&&transferred>0)
      throw new IllegalStateException("This course entitlement has been permanently transferred. Automatic refund is blocked until HRMS reviews the refund/access policy.");
  }

  @Transactional
  public Map<String,Object> applyProviderResult(
      long refundId,String providerRefundId,String providerPaymentId,long providerAmountPaise,
      String providerCurrency,String providerStatus,Map<String,Object> providerSnapshot,long actorStaffId) {
    return applyProviderResultInternal(refundId,providerRefundId,providerPaymentId,providerAmountPaise,
        providerCurrency,providerStatus,providerSnapshot,Long.valueOf(actorStaffId));
  }

  /** Provider-signed webhook path. A provider event has no HRMS staff actor; ledger events allow null actors. */
  @Transactional
  public Map<String,Object> applyProviderResultFromWebhook(
      long refundId,String providerRefundId,String providerPaymentId,long providerAmountPaise,
      String providerCurrency,String providerStatus,Map<String,Object> providerSnapshot) {
    return applyProviderResultInternal(refundId,providerRefundId,providerPaymentId,providerAmountPaise,
        providerCurrency,providerStatus,providerSnapshot,null);
  }

  private Map<String,Object> applyProviderResultInternal(
      long refundId,String providerRefundId,String providerPaymentId,long providerAmountPaise,
      String providerCurrency,String providerStatus,Map<String,Object> providerSnapshot,Long actorStaffId) {
    Map<String,Object> refund=one("""
      select id,course_payment_order_id,request_idempotency_key,provider_idempotency_key,
        provider_payment_id,provider_refund_id,requested_amount_paise,currency,reason,status,
        provider_status,credit_note_document_id,requested_by_staff_id
      from course_payment_refund where id=? for update
      """,refundId);
    if(refund.isEmpty()) throw new IllegalArgumentException("Refund request not found.");
    String currentStatus=string(refund.get("status"));
    if("PROCESSED".equals(currentStatus)||"FAILED".equals(currentStatus)) return refundView(refundId);
    String expectedPayment=string(refund.get("provider_payment_id"));
    long expectedAmount=number(refund.get("requested_amount_paise"));
    if(providerRefundId==null||!providerRefundId.matches("[A-Za-z0-9_-]{4,80}")
        ||!expectedPayment.equals(providerPaymentId)||expectedAmount!=providerAmountPaise
        ||!"INR".equalsIgnoreCase(providerCurrency)) {
      markReconciliationRequiredInternal(refundId,actorStaffId,
          "Provider response did not match the saved payment, amount, currency and refund identity.");
      return refundView(refundId);
    }
    if(refund.get("provider_refund_id")!=null
        &&!String.valueOf(refund.get("provider_refund_id")).equals(providerRefundId)) {
      markReconciliationRequiredInternal(refundId,actorStaffId,
          "Provider returned a different refund ID for the same idempotency key.");
      return refundView(refundId);
    }

    String normalizedStatus=providerStatus==null?"":providerStatus.trim().toLowerCase(Locale.ROOT);
    String snapshotJson=json(providerSnapshot==null?Map.of():providerSnapshot);
    if("created".equals(normalizedStatus)) normalizedStatus="pending";
    if("pending".equals(normalizedStatus)) {
      jdbc.update("""
        update course_payment_refund set status='PENDING',provider_refund_id=?,provider_status=?,
          provider_response_snapshot=?::jsonb,updated_at=now()
        where id=? and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
        """,providerRefundId,normalizedStatus,snapshotJson,refundId);
      event(refundId,"PROVIDER_PENDING","course-refund:"+refundId+":provider:pending:"+providerRefundId,
          actorStaffId,Map.of("providerRefundId",providerRefundId));
      return refundView(refundId);
    }
    if("failed".equals(normalizedStatus)) {
      jdbc.update("""
        update course_payment_refund set status='FAILED',provider_refund_id=?,provider_status=?,
          provider_response_snapshot=?::jsonb,settled_at=now(),updated_at=now()
        where id=? and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
        """,providerRefundId,normalizedStatus,snapshotJson,refundId);
      event(refundId,"PROVIDER_FAILED","course-refund:"+refundId+":provider:failed:"+providerRefundId,
          actorStaffId,Map.of("providerRefundId",providerRefundId));
      return refundView(refundId);
    }
    if(!"processed".equals(normalizedStatus)) {
      markReconciliationRequiredInternal(refundId,actorStaffId,
          "Provider returned an unknown refund state; it must be reconciled before another refund is submitted.");
      return refundView(refundId);
    }

    long orderId=number(refund.get("course_payment_order_id"));
    Map<String,Object> order=one("""
      select id,status,provider_payment_id,captured_amount_paise,currency,fiscal_document_id,
        institution_id,course_id,student_id,purchaser_user_id
      from course_payment_order where id=? for update
      """,orderId);
    if(order.isEmpty()||!"VERIFIED".equals(string(order.get("status")))
        ||!expectedPayment.equals(string(order.get("provider_payment_id")))) {
      markReconciliationRequiredInternal(refundId,actorStaffId,
          "The original captured order changed while the provider refund was being processed.");
      return refundView(refundId);
    }
    // Record the provider-confirmed event before the compensating fiscal document and allocation events.
    event(refundId,"PROVIDER_PROCESSED","course-refund:"+refundId+":provider:processed:"+providerRefundId,
        actorStaffId,Map.of("providerRefundId",providerRefundId,"amountPaise",expectedAmount,"currency","INR"));
    long creditNoteId=issueCreditNote(refund,order,providerRefundId,actorStaffId);
    reverseInstitutionAllocations(refund,order,providerRefundId,creditNoteId,actorStaffId);
    jdbc.update("""
      update course_payment_refund set status='PROCESSED',provider_refund_id=?,provider_status='processed',
        provider_response_snapshot=?::jsonb,credit_note_document_id=?,settled_at=now(),updated_at=now()
      where id=? and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
      """,providerRefundId,snapshotJson,creditNoteId,refundId);
    return refundView(refundId);
  }

  @Transactional(readOnly=true)
  public PreparedRefund prepareRefundForRefresh(long refundId,long actorStaffId) {
    return refundById(refundId);
  }

  @Transactional
  public Map<String,Object> markReconciliationRequired(long refundId,long actorStaffId,String safeReason) {
    Map<String,Object> row=one("select id,status from course_payment_refund where id=? for update",refundId);
    if(row.isEmpty()) throw new IllegalArgumentException("Refund request not found.");
    if(!List.of("PROCESSED","FAILED").contains(string(row.get("status"))))
      markReconciliationRequiredInternal(refundId,actorStaffId,
          safeReason==null?"The provider result is uncertain and needs reconciliation.":safeReason);
    return refundView(refundId);
  }

  @Transactional
  public Map<String,Object> markProviderRejected(long refundId,long actorStaffId,String safeReason) {
    Map<String,Object> row=one("select id,status from course_payment_refund where id=? for update",refundId);
    if(row.isEmpty()) throw new IllegalArgumentException("Refund request not found.");
    if(!List.of("PROCESSED","FAILED").contains(string(row.get("status")))) {
      String reason=safeReason==null?"Provider rejected the refund request.":safeReason;
      jdbc.update("""
        update course_payment_refund set status='FAILED',provider_status='rejected',
          provider_response_snapshot=?::jsonb,settled_at=now(),updated_at=now()
        where id=? and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
        """,json(Map.of("failureCategory","PROVIDER_REJECTED","message",safe(reason,300))),refundId);
      event(refundId,"PROVIDER_FAILED","course-refund:"+refundId+":provider:rejected",
          actorStaffId,Map.of("message",safe(reason,300)));
    }
    return refundView(refundId);
  }

  @Transactional(readOnly=true)
  public Map<String,Object> refundView(long refundId) {
    Map<String,Object> row=one("""
      select r.id,r.public_id,r.course_payment_order_id,r.requested_amount_paise,r.currency,r.reason,
        r.status,r.provider_status,r.provider_refund_id,r.requested_at,r.settled_at,
        r.credit_note_document_id,d.document_number as credit_note_number,
        r.provider_payment_id
      from course_payment_refund r
      left join fiscal_document d on d.id=r.credit_note_document_id
      where r.id=?
      """,refundId);
    if(row.isEmpty()) throw new IllegalArgumentException("Refund request not found.");
    return row;
  }

  @Transactional(readOnly=true)
  public List<Map<String,Object>> refundsForOrder(long orderId) {
    return jdbc.queryForList("""
      select r.id,r.public_id,r.course_payment_order_id,r.requested_amount_paise,r.currency,r.reason,
        r.status,r.provider_status,r.provider_refund_id,r.requested_at,r.settled_at,
        r.credit_note_document_id,d.document_number as credit_note_number
      from course_payment_refund r
      left join fiscal_document d on d.id=r.credit_note_document_id
      where r.course_payment_order_id=?
      order by r.requested_at desc,r.id desc
      """,orderId);
  }

  private void markReconciliationRequiredInternal(long refundId,Long actorStaffId,String reason) {
    Map<String,Object> row=one("select status,provider_response_snapshot::text as response_json from course_payment_refund where id=?",refundId);
    if(row.isEmpty()||List.of("PROCESSED","FAILED").contains(string(row.get("status")))) return;
    Map<String,Object> snapshot=parseObject(row.get("response_json"));
    snapshot.put("reconciliationReason",safe(reason,400));
    jdbc.update("""
      update course_payment_refund set status='RECONCILIATION_REQUIRED',
        provider_response_snapshot=?::jsonb,updated_at=now()
      where id=? and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
      """,json(snapshot),refundId);
    event(refundId,"RECONCILIATION_REQUIRED","course-refund:"+refundId+":reconciliation",
        actorStaffId,Map.of("reason",safe(reason,400)));
  }

  private long issueCreditNote(
      Map<String,Object> refund,Map<String,Object> order,String providerRefundId,Long actorStaffId) {
    long refundId=number(refund.get("id"));
    long orderId=number(refund.get("course_payment_order_id"));
    long amount=number(refund.get("requested_amount_paise"));
    Long originalDocValue=nullableLong(order.get("fiscal_document_id"));
    if(originalDocValue==null) throw new IllegalStateException("The original sale document is missing.");
    long originalDocId=originalDocValue;
    Map<String,Object> original=one("""
      select d.id,d.legal_entity_id,d.gst_registration_id,d.document_type,d.financial_year,
        d.series_code,d.document_number,d.currency,d.supplier_snapshot::text as supplier_json,
        d.recipient_snapshot::text as recipient_json,d.transaction_snapshot::text as transaction_json,
        d.tax_summary_snapshot::text as tax_summary_json,d.taxable_value_paise,d.tax_value_paise,
        d.total_value_paise,d.amount_paid_paise,d.template_version_id,
        coalesce(o.tax_calculation_snapshot->'invoiceTemplateDesignSnapshot',tv.design_definition,'{}'::jsonb)::text as design_json
      from fiscal_document d
      left join invoice_template_version tv on tv.id=d.template_version_id
      left join course_payment_order o on o.fiscal_document_id=d.id
      where d.id=? for update of d
      """,originalDocId);
    if(original.isEmpty()||!("TAX_INVOICE".equals(string(original.get("document_type")))
        ||"BILL_OF_SUPPLY".equals(string(original.get("document_type")))))
      throw new IllegalStateException("The original fiscal document is not an issued sale invoice.");
    long originalTotal=number(original.get("total_value_paise"));
    long originalTax=number(original.get("tax_value_paise"));
    if(originalTotal<=0||originalTax<0||originalTax>originalTotal)
      throw new IllegalStateException("The original document totals are not valid for a credit note.");

    Long priorRefundValue=jdbc.queryForObject("""
      select coalesce(sum(requested_amount_paise),0) from course_payment_refund
      where course_payment_order_id=? and status='PROCESSED' and id<>?
      """,Long.class,orderId,refundId);
    long priorRefund=priorRefundValue==null?0L:priorRefundValue;
    long cumulativeRefund=Math.addExact(priorRefund,amount);
    if(cumulativeRefund>originalTotal)
      throw new IllegalStateException("Provider-confirmed refunds exceed the original sale total.");

    Long priorTaxValue=jdbc.queryForObject("""
      select coalesce(sum(d.tax_value_paise),0)
      from course_payment_refund r join fiscal_document d on d.id=r.credit_note_document_id
      where r.course_payment_order_id=? and r.status='PROCESSED' and r.id<>?
      """,Long.class,orderId,refundId);
    long priorTax=priorTaxValue==null?0L:priorTaxValue;
    long targetCumulativeTax=proportional(originalTax,cumulativeRefund,originalTotal);
    long refundTax=Math.subtractExact(targetCumulativeTax,priorTax);
    if(refundTax<0||refundTax>amount)
      throw new IllegalStateException("Cumulative refund tax allocation is inconsistent.");
    long refundTaxable=amount-refundTax;

    Map<String,Object> supplier=parseObject(original.get("supplier_json"));
    Map<String,Object> recipient=parseObject(original.get("recipient_json"));
    Map<String,Object> originalTransaction=parseObject(original.get("transaction_json"));
    Map<String,Object> originalTaxSummary=parseObject(original.get("tax_summary_json"));
    List<Map<String,Object>> originalComponents=asMapList(originalTaxSummary.get("components"));
    List<Map<String,Object>> priorCreditNotes=jdbc.queryForList("""
      select d.tax_summary_snapshot::text as tax_summary_json
      from course_payment_refund r join fiscal_document d on d.id=r.credit_note_document_id
      where r.course_payment_order_id=? and r.status='PROCESSED' and r.id<>?
      order by r.id
      """,orderId,refundId);
    Map<String,Long> previousComponentAmounts=new HashMap<>();
    for(Map<String,Object> priorNote:priorCreditNotes) {
      Map<String,Object> priorSummary=parseObject(priorNote.get("tax_summary_json"));
      for(Map<String,Object> component:asMapList(priorSummary.get("components"))) {
        String name=string(component.get("name"));
        previousComponentAmounts.merge(name,number(component.get("amountPaise")),Math::addExact);
      }
    }
    List<Map<String,Object>> refundComponents=new ArrayList<>();
    long targetComponentSum=0;
    for(int i=0;i<originalComponents.size();i++) {
      Map<String,Object> originalComponent=originalComponents.get(i);
      String name=string(originalComponent.get("name"));
      long target=i==originalComponents.size()-1
          ?targetCumulativeTax-targetComponentSum
          :proportional(number(originalComponent.get("amountPaise")),cumulativeRefund,originalTotal);
      targetComponentSum=Math.addExact(targetComponentSum,target);
      long previous=previousComponentAmounts.getOrDefault(name,0L);
      long current=Math.subtractExact(target,previous);
      if(current<0)
        throw new IllegalStateException("Cumulative tax-component refunds are inconsistent.");
      Map<String,Object> component=new LinkedHashMap<>();
      component.put("name",name);
      component.put("rateBasisPoints",number(originalComponent.get("rateBasisPoints")));
      component.put("amountPaise",current);
      refundComponents.add(component);
    }
    if(!originalComponents.isEmpty()&&targetComponentSum!=targetCumulativeTax)
      throw new IllegalStateException("The credit note tax breakdown does not reconcile.");
    if(originalComponents.isEmpty()&&refundTax!=0)
      throw new IllegalStateException("The original taxable sale has no tax-component breakdown.");

    Map<String,Object> taxSummary=new LinkedHashMap<>(originalTaxSummary);
    taxSummary.put("components",refundComponents);
    taxSummary.put("creditNote",true);
    taxSummary.put("originalDocumentNumber",string(original.get("document_number")));
    taxSummary.put("refundId",refundId);

    Map<String,Object> transaction=new LinkedHashMap<>(originalTransaction);
    String courseTitle=value(originalTransaction,"courseTitle","Course access");
    transaction.put("coursePaymentRefundId",refundId);
    transaction.put("originalDocumentId",originalDocId);
    transaction.put("originalDocumentNumber",string(original.get("document_number")));
    transaction.put("refundReference",providerRefundId);
    transaction.put("paymentReference",providerRefundId);
    transaction.put("paymentStatus","REFUND PROCESSED");
    transaction.put("listedPricePaise",amount);
    transaction.put("discountValuePaise",0L);
    transaction.put("taxableValuePaise",refundTaxable);
    transaction.put("taxValuePaise",refundTax);
    transaction.put("amountPaidPaise",amount);
    transaction.put("currency",string(original.get("currency")));

    Map<String,Object> line=new LinkedHashMap<>();
    line.put("description","Refund — "+courseTitle);
    line.put("serviceClassificationCode",originalTaxSummary.get("serviceClassificationCode"));
    line.put("quantity",BigDecimal.ONE);
    line.put("unitPricePaise",amount);
    line.put("discountValuePaise",0L);
    line.put("taxableValuePaise",refundTaxable);
    line.put("taxComponentsSnapshot",taxSummary);
    line.put("taxValuePaise",refundTax);
    line.put("lineTotalPaise",amount);

    LocalDate issueDate=LocalDate.now(INDIA);
    String fy=financialYear(issueDate);
    String series=value(supplier,"invoiceSeriesCode","QE").toUpperCase(Locale.ROOT);
    if(!series.matches("[A-Z0-9_-]{1,3}"))
      throw new IllegalStateException("The original seller invoice series is invalid.");
    jdbc.update("""
      insert into fiscal_document_sequence(legal_entity_id,document_type,financial_year,series_code,next_sequence_no)
      values(?,'CREDIT_NOTE',?,?,1)
      on conflict(legal_entity_id,document_type,financial_year,series_code) do nothing
      """,number(original.get("legal_entity_id")),fy,series);
    Long next=jdbc.queryForObject("""
      select next_sequence_no from fiscal_document_sequence
      where legal_entity_id=? and document_type='CREDIT_NOTE' and financial_year=? and series_code=?
      for update
      """,Long.class,number(original.get("legal_entity_id")),fy,series);
    if(next==null||next<=0||next>999_999L)
      throw new IllegalStateException("The credit-note sequence is unavailable or exhausted.");
    jdbc.update("""
      update fiscal_document_sequence set next_sequence_no=?,updated_at=now()
      where legal_entity_id=? and document_type='CREDIT_NOTE' and financial_year=? and series_code=?
      """,Math.addExact(next,1),number(original.get("legal_entity_id")),fy,series);
    String shortFy=String.format(Locale.ROOT,"%02d-%02d",
        issueDate.getMonthValue()>=4?issueDate.getYear()%100:(issueDate.getYear()-1)%100,
        issueDate.getMonthValue()>=4?(issueDate.getYear()+1)%100:issueDate.getYear()%100);
    String docNumber=series+"/"+shortFy+"/"+String.format(Locale.ROOT,"%06d",next);
    if(docNumber.length()>16) throw new IllegalStateException("The credit-note number exceeds the 16-character limit.");
    String idempotency="course-order:"+orderId+":refund:"+refundId+":credit-note:v1";
    Long templateVersionId=nullableLong(original.get("template_version_id"));
    Long newDocId=jdbc.queryForObject("""
      insert into fiscal_document(
        legal_entity_id,gst_registration_id,document_type,financial_year,series_code,sequence_no,document_number,
        source_type,source_record_key,idempotency_key,original_document_id,currency,supplier_snapshot,
        recipient_snapshot,transaction_snapshot,tax_summary_snapshot,taxable_value_paise,discount_value_paise,
        tax_value_paise,total_value_paise,amount_paid_paise,template_version_id
      ) values(?,?,'CREDIT_NOTE',?,?,?,?,'COURSE_ORDER',?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
      """,Long.class,number(original.get("legal_entity_id")),original.get("gst_registration_id"),
        fy,series,next,docNumber,orderId+":refund:"+refundId,idempotency,originalDocId,
        string(original.get("currency")),json(supplier),json(recipient),json(transaction),json(taxSummary),
        refundTaxable,0L,refundTax,amount,amount,templateVersionId);
    if(newDocId==null) throw new IllegalStateException("Credit-note metadata could not be recorded.");

    jdbc.update("""
      insert into fiscal_document_line(
        fiscal_document_id,line_no,product_reference,description_snapshot,service_classification_code,
        quantity,unit_price_paise,discount_value_paise,taxable_value_paise,tax_components_snapshot,
        tax_value_paise,line_total_paise
      ) values(?,1,?,?,?,?,?,?,?,?::jsonb,?,?)
      """,newDocId,"COURSE-"+number(originalTransaction.get("courseId")),line.get("description"),
        line.get("serviceClassificationCode"),line.get("quantity"),amount,0L,refundTaxable,
        json(taxSummary),refundTax,amount);

    Map<String,Object> design=parseObject(original.get("design_json"));
    FiscalDocumentRenderer.RenderedFiscalDocument rendered=renderer.render(
        "CREDIT_NOTE",docNumber,issueDate,supplier,recipient,transaction,taxSummary,List.of(line),design);
    FiscalDocumentStorage.StoredDocument asset=storage.storePdf(rendered.pdf());
    if(TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) {
          if(status!=STATUS_COMMITTED) storage.deleteQuietly(asset.storageKey());
        }
      });
    }
    jdbc.update("""
      insert into fiscal_document_asset(fiscal_document_id,asset_type,storage_key,mime_type,sha256,byte_length)
      values(?,'PDF',?,'application/pdf',?,?)
      """,newDocId,asset.storageKey(),asset.sha256(),asset.sizeBytes());
    jdbc.update("""
      insert into fiscal_document_event(fiscal_document_id,event_type,idempotency_key,actor_staff_id,details)
      values(?,'CREDIT_NOTE_ISSUED',?,?,?::jsonb)
      on conflict(idempotency_key) do nothing
      """,newDocId,idempotency+":issued",actorStaffId,
        json(Map.of("refundId",refundId,"originalDocumentId",originalDocId,
            "originalDocumentNumber",string(original.get("document_number")),"providerRefundId",providerRefundId)));
    jdbc.update("""
      insert into fiscal_document_event(fiscal_document_id,event_type,idempotency_key,actor_staff_id,details)
      values(?,'REFUND_LINKED',?,?,?::jsonb)
      on conflict(idempotency_key) do nothing
      """,originalDocId,"course-refund:"+refundId+":original-linked",actorStaffId,
        json(Map.of("refundId",refundId,"creditNoteDocumentId",newDocId,"creditNoteNumber",docNumber)));
    event(refundId,"CREDIT_NOTE_ISSUED","course-refund:"+refundId+":credit-note-issued",actorStaffId,
        Map.of("creditNoteDocumentId",newDocId,"creditNoteNumber",docNumber,
            "originalDocumentId",originalDocId,"amountPaise",amount,
            "taxableValuePaise",refundTaxable,"taxValuePaise",refundTax));
    return newDocId;
  }

  private void reverseInstitutionAllocations(
      Map<String,Object> refund,Map<String,Object> order,String providerRefundId,
      long creditNoteDocumentId,Long actorStaffId) {
    long refundId=number(refund.get("id"));
    long orderId=number(refund.get("course_payment_order_id"));
    long currentAmount=number(refund.get("requested_amount_paise"));
    Long priorRefundValue=jdbc.queryForObject("""
      select coalesce(sum(requested_amount_paise),0) from course_payment_refund
      where course_payment_order_id=? and status='PROCESSED' and id<>?
      """,Long.class,orderId,refundId);
    Long priorTaxValue=jdbc.queryForObject("""
      select coalesce(sum(d.tax_value_paise),0)
      from course_payment_refund r join fiscal_document d on d.id=r.credit_note_document_id
      where r.course_payment_order_id=? and r.status='PROCESSED' and r.id<>?
      """,Long.class,orderId,refundId);
    long cumulativeRefund=Math.addExact(priorRefundValue==null?0L:priorRefundValue,currentAmount);
    long originalDocId=number(order.get("fiscal_document_id"));
    Long originalTaxValue=jdbc.queryForObject("select tax_value_paise from fiscal_document where id=?",Long.class,originalDocId);
    Long originalTotalValue=jdbc.queryForObject("select total_value_paise from fiscal_document where id=?",Long.class,originalDocId);
    long originalTax=originalTaxValue==null?0L:originalTaxValue;
    long originalTotal=originalTotalValue==null?0L:originalTotalValue;
    long targetCumulativeTax=proportional(originalTax,cumulativeRefund,originalTotal);
    long cumulativeNetRefund=cumulativeRefund-targetCumulativeTax;
    long currentTax=targetCumulativeTax-(priorTaxValue==null?0L:priorTaxValue);
    long currentNetRefund=currentAmount-currentTax;

    List<Map<String,Object>> allocations=jdbc.queryForList("""
      select a.id,a.institution_id,a.legal_entity_id,a.recipient_type,a.allocated_amount_paise,
        a.distributable_pool_paise,a.currency,
        coalesce(fee.fee_deducted_paise,0) as fee_deducted_paise,
        coalesce(order_fee.total_fee_deducted_paise,0) as total_fee_deducted_paise
      from revenue_share_allocation a
      left join lateral (
        select coalesce(sum(e.amount_paise),0) as fee_deducted_paise
        from revenue_share_allocation_event e
        where e.allocation_id=a.id and e.event_type='PROCESSING_FEE_DEDUCTED'
      ) fee on true
      left join lateral (
        select coalesce(sum(e.amount_paise),0) as total_fee_deducted_paise
        from revenue_share_allocation a2
        join revenue_share_allocation_event e on e.allocation_id=a2.id
          and e.event_type='PROCESSING_FEE_DEDUCTED'
        where a2.course_payment_order_id=a.course_payment_order_id
      ) order_fee on true
      where a.course_payment_order_id=? order by a.recipient_type,a.institution_id,a.legal_entity_id,a.id
      """,orderId);
    for(Map<String,Object> allocation:allocations) {
      long allocationId=number(allocation.get("id"));
      long grossAllocated=number(allocation.get("allocated_amount_paise"));
      long grossPool=number(allocation.get("distributable_pool_paise"));
      long allocated=grossAllocated-number(allocation.get("fee_deducted_paise"));
      long pool=grossPool-number(allocation.get("total_fee_deducted_paise"));
      if(allocated<0||pool<0)
        throw new IllegalStateException("Confirmed processor charges exceed the saved course allocation. Finance reconciliation is required before this refund can be posted.");
      if(pool==0) {
        if(allocated>0) throw new IllegalStateException("Revenue-share allocation has a zero net distributable pool.");
        continue;
      }
      // Processor charges remain an expense even on a full refund. Reverse all net
      // beneficiary earnings when the net refund reaches/exceeds the post-fee pool,
      // but never create a negative beneficiary allocation or over-reversal.
      long target=cumulativeNetRefund>=pool?allocated:proportional(allocated,cumulativeNetRefund,pool);
      Long alreadyReversedValue=jdbc.queryForObject("""
        select coalesce(sum(amount_paise),0) from revenue_share_allocation_event
        where allocation_id=? and event_type='REFUNDED'
        """,Long.class,allocationId);
      long alreadyReversed=alreadyReversedValue==null?0L:alreadyReversedValue;
      long delta=target-alreadyReversed;
      if(delta<0||target>allocated)
        throw new IllegalStateException("Revenue-share refund reversal exceeds the original allocation.");
      if(delta==0) continue;
      jdbc.update("""
        insert into revenue_share_allocation_event(
          allocation_id,event_type,amount_paise,currency,idempotency_key,actor_staff_id,details
        ) values(?,'REFUNDED',?,?,?,?,?::jsonb)
        on conflict(idempotency_key) do nothing
        """,allocationId,delta,string(allocation.get("currency")),
          "course-refund:"+refundId+":allocation:"+allocationId+":reversed",actorStaffId,
          json(Map.ofEntries(
              Map.entry("coursePaymentRefundId",refundId),
              Map.entry("coursePaymentOrderId",orderId),
              Map.entry("providerRefundId",providerRefundId),
              Map.entry("creditNoteDocumentId",creditNoteDocumentId),
              Map.entry("refundAmountPaise",currentAmount),
              Map.entry("taxCreditPaise",currentTax),
              Map.entry("taxableCreditPaise",currentNetRefund),
              Map.entry("cumulativeNetRefundPaise",cumulativeNetRefund),
              Map.entry("grossAllocationPaise",grossAllocated),
              Map.entry("confirmedFeeDeductionPaise",number(allocation.get("fee_deducted_paise"))),
              Map.entry("netAllocationPaise",allocated),
              Map.entry("netDistributablePoolPaise",pool),
              Map.entry("allocationReversalPaise",delta))));
    }
    event(refundId,"ALLOCATIONS_REVERSED","course-refund:"+refundId+":allocations-reversed",actorStaffId,
        Map.of("creditNoteDocumentId",creditNoteDocumentId,"refundAmountPaise",currentAmount,
            "taxableCreditPaise",currentNetRefund,"taxCreditPaise",currentTax,
            "institutionsWithReversal",allocations.size()));
  }

  private PreparedRefund findByRequestKey(String key) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,course_payment_order_id,provider_payment_id,provider_idempotency_key,
        requested_amount_paise,currency,reason,status,provider_refund_id,credit_note_document_id
      from course_payment_refund where request_idempotency_key=?
      """,key);
    return rows.isEmpty()?null:toPrepared(rows.getFirst());
  }

  private PreparedRefund refundById(long id) {
    Map<String,Object> row=one("""
      select id,course_payment_order_id,provider_payment_id,provider_idempotency_key,
        requested_amount_paise,currency,reason,status,provider_refund_id,credit_note_document_id
      from course_payment_refund where id=?
      """,id);
    if(row.isEmpty()) throw new IllegalArgumentException("Refund request not found.");
    return toPrepared(row);
  }

  private PreparedRefund toPrepared(Map<String,Object> row) {
    return new PreparedRefund(number(row.get("id")),number(row.get("course_payment_order_id")),
        string(row.get("provider_payment_id")),string(row.get("provider_idempotency_key")),
        number(row.get("requested_amount_paise")),string(row.get("currency")),string(row.get("reason")),
        string(row.get("status")),nullableText(row.get("provider_refund_id")),
        nullableLong(row.get("credit_note_document_id")));
  }

  private void event(long refundId,String type,String idempotencyKey,Long actorStaffId,Map<String,Object> details) {
    jdbc.update("""
      insert into course_payment_refund_event(refund_id,event_type,idempotency_key,actor_staff_id,details)
      values(?,?,?,?,?::jsonb)
      on conflict(idempotency_key) do nothing
      """,refundId,type,idempotencyKey,actorStaffId,json(details));
  }

  static long proportional(long total,long numerator,long denominator) {
    if(total<0||numerator<0||denominator<=0||numerator>denominator)
      throw new IllegalArgumentException("Refund proration inputs are outside the original transaction.");
    return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(numerator))
        .divide(BigDecimal.valueOf(denominator),0,RoundingMode.HALF_UP).longValueExact();
  }

  private static String requiredKey(String raw) {
    String key=raw==null?"":raw.trim();
    if(!key.matches("[A-Za-z0-9_-]{10,180}"))
      throw new IllegalArgumentException("Refund Idempotency-Key must contain 10–180 letters, digits, underscores or hyphens.");
    return key;
  }

  private static String financialYear(LocalDate date) {
    int start=date.getMonthValue()>=4?date.getYear():date.getYear()-1;
    return String.format(Locale.ROOT,"%04d-%02d",start,(start+1)%100);
  }

  private static String safe(String text,int max) {
    if(text==null)return "";
    String normalized=text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]"," ").trim();
    return normalized.length()>max?normalized.substring(0,max):normalized;
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value==null?Map.of():value); }
    catch(Exception ex) { throw new IllegalStateException("Refund accounting snapshot could not be serialized.",ex); }
  }

  private Map<String,Object> parseObject(Object value) {
    if(value==null)return new LinkedHashMap<>();
    try {
      if(value instanceof Map<?,?> map) {
        Map<String,Object> out=new LinkedHashMap<>();
        map.forEach((k,v)->out.put(String.valueOf(k),v));
        return out;
      }
      String text=String.valueOf(value);
      return text.isBlank()?new LinkedHashMap<>():mapper.readValue(text,MAP_TYPE);
    } catch(Exception ex) {
      throw new IllegalStateException("A stored fiscal snapshot could not be read safely.",ex);
    }
  }

  private List<Map<String,Object>> asMapList(Object value) {
    if(value instanceof List<?> list) {
      List<Map<String,Object>> out=new ArrayList<>();
      for(Object entry:list) if(entry instanceof Map<?,?> map) {
        Map<String,Object> row=new LinkedHashMap<>();
        map.forEach((k,v)->row.put(String.valueOf(k),v));
        out.add(row);
      }
      return out;
    }
    return List.of();
  }

  private Map<String,Object> one(String sql,Object... args) {
    List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
    return rows.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(rows.getFirst());
  }

  private static String value(Map<String,Object> map,String key,String fallback) {
    Object raw=map==null?null:map.get(key);
    return raw==null||String.valueOf(raw).isBlank()?fallback:safe(String.valueOf(raw),500);
  }

  private static long number(Object raw) {
    if(raw instanceof Number n)return n.longValue();
    try { return Long.parseLong(String.valueOf(raw)); }
    catch(Exception ignored) { return 0L; }
  }

  private static Long nullableLong(Object raw) {
    if(raw==null)return null;
    try { return raw instanceof Number n?n.longValue():Long.parseLong(String.valueOf(raw)); }
    catch(Exception ignored) { return null; }
  }

  private static String nullableText(Object raw) {
    if(raw==null)return null;
    String value=String.valueOf(raw).trim();
    return value.isEmpty()?null:value;
  }

  private static String string(Object raw) { return raw==null?"":String.valueOf(raw); }
}
