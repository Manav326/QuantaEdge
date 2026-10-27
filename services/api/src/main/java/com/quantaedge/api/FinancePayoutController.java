package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * HRMS payout controls integrated with the existing course allocation/refund ledger.
 * Full bank account numbers are sent to RazorpayX server-to-server and are never persisted.
 */
@RestController
@RequestMapping("/api/v1/hrms/platform/finance")
public class FinancePayoutController {
  private static final ZoneId INDIA=ZoneId.of("Asia/Kolkata");
  private static final Set<String> RESERVED_STATES=Set.of(
      "PENDING_APPROVAL","APPROVED","SUBMITTING","PENDING","QUEUED","PROCESSING","RECONCILIATION_REQUIRED");
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final CourseOrderFinancialService financialService;
  private final ObjectMapper mapper;
  private final String keyId;
  private final String keySecret;
  private final String sourceAccountNumber;
  private final String webhookSecret;
  private final String baseUrl;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

  public FinancePayoutController(JdbcTemplate jdbc,AuthorizationService authorization,
      CourseOrderFinancialService financialService,ObjectMapper mapper,
      @Value("\u0024{app.razorpayx.key-id:}") String keyId,
      @Value("\u0024{app.razorpayx.key-secret:}") String keySecret,
      @Value("\u0024{app.razorpayx.account-number:}") String sourceAccountNumber,
      @Value("\u0024{app.razorpayx.webhook-secret:}") String webhookSecret,
      @Value("\u0024{app.razorpayx.base-url:https://api.razorpay.com/v1}") String baseUrl) {
    this.jdbc=jdbc;this.authorization=authorization;this.financialService=financialService;this.mapper=mapper;
    this.keyId=keyId==null?"":keyId.trim();this.keySecret=keySecret==null?"":keySecret.trim();
    this.sourceAccountNumber=sourceAccountNumber==null?"":sourceAccountNumber.trim();
    this.webhookSecret=webhookSecret==null?"":webhookSecret.trim();
    this.baseUrl=(baseUrl==null?"https://api.razorpay.com/v1":baseUrl.trim()).replaceAll("/+$","");
  }

  @GetMapping("/payout-catalog")
  public Map<String,Object> payoutCatalog(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    List<Map<String,Object>> institutions=jdbc.queryForList("""
      select id,tenant_code,display_name,status from institution_tenant
      where status='ACTIVE' order by lower(display_name),id
      """);
    List<Map<String,Object>> companies=jdbc.queryForList("""
      select id,entity_kind,institution_id,legal_name,trade_name,status from billing_legal_entity
      where status='ACTIVE'
      order by lower(legal_name),id
      """);
    return Map.of("institutions",institutions,"legalEntities",companies);
  }

  @GetMapping("/payout-settings")
  public Map<String,Object> payoutSettings(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> row=one("""
      select s.default_remainder_recipient_type,s.default_remainder_institution_id,s.default_remainder_legal_entity_id,
        s.payment_collection_legal_entity_id,s.razorpayx_funding_legal_entity_id,
        s.collection_arrangement_status,s.collection_arrangement_reference,
        s.collection_arrangement_verified_by_staff_id,s.collection_arrangement_verified_at,
        s.payout_frequency,s.payout_minimum_paise,s.refund_reserve_basis_points,s.updated_at,
        t.display_name as default_institution_name,le.legal_name as default_company_name,
        pc.legal_name as payment_collection_legal_name,rf.legal_name as razorpayx_funding_legal_name
      from billing_finance_payout_settings s
      left join institution_tenant t on t.id=s.default_remainder_institution_id
      left join billing_legal_entity le on le.id=s.default_remainder_legal_entity_id
      left join billing_legal_entity pc on pc.id=s.payment_collection_legal_entity_id
      left join billing_legal_entity rf on rf.id=s.razorpayx_funding_legal_entity_id
      where s.id=1
      """);
    row.put("configured",!row.isEmpty()&&row.get("payout_minimum_paise")!=null&&row.get("refund_reserve_basis_points")!=null);
    row.put("providerConfigured",providerConfigured());
    row.put("webhookConfigured",!webhookSecret.isBlank());
    row.put("provider","RAZORPAYX");
    row.put("sourceAccountConfigured",!sourceAccountNumber.isBlank());
    row.put("canVerifyCollectionArrangement",hasPermission(context,"QE_PLATFORM_OWNER"));
    row.put("collectionArrangementVerified","VERIFIED".equals(string(row.get("collection_arrangement_status")))
        &&row.get("collection_arrangement_verified_at")!=null);
    row.put("payoutReady",payoutReadyFromDatabase()&&providerConfigured()&&!webhookSecret.isBlank());
    return row;
  }

  @PutMapping("/payout-settings")
  public Map<String,Object> savePayoutSettings(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);
    String type=text(body,"defaultRemainderRecipientType").toUpperCase(Locale.ROOT);
    long recipientId=requiredLong(body.get("defaultRemainderRecipientId"),"defaultRemainderRecipientId");
    if(!List.of("INSTITUTION","COMPANY").contains(type))throw bad("Choose an institution or company for the remaining balance.");
    if("INSTITUTION".equals(type)&&!exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",recipientId))
      throw bad("The remainder institution must be active.");
    if("COMPANY".equals(type)&&!exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",recipientId))
      throw bad("The remainder company must be an active company legal entity.");
    long minimum=requiredLong(body.get("payoutMinimumPaise"),"payoutMinimumPaise");
    if(minimum<100)throw bad("Minimum payout must be at least ₹1.00.");
    int reserve=Math.toIntExact(requiredLong(body.get("refundReserveBasisPoints"),"refundReserveBasisPoints"));
    if(reserve<0||reserve>10_000)throw bad("Refund reserve must be between 0% and 100%.");
    jdbc.update("""
      insert into billing_finance_payout_settings(
        id,default_remainder_recipient_type,default_remainder_institution_id,default_remainder_legal_entity_id,
        payout_frequency,payout_minimum_paise,refund_reserve_basis_points,updated_by_staff_id,updated_at
      ) values(1,?,?,?,'MONTHLY',?,?,?,now())
      on conflict(id) do update set default_remainder_recipient_type=excluded.default_remainder_recipient_type,
        default_remainder_institution_id=excluded.default_remainder_institution_id,
        default_remainder_legal_entity_id=excluded.default_remainder_legal_entity_id,
        payout_frequency='MONTHLY',payout_minimum_paise=excluded.payout_minimum_paise,
        refund_reserve_basis_points=excluded.refund_reserve_basis_points,
        updated_by_staff_id=excluded.updated_by_staff_id,updated_at=now()
      """,type,"INSTITUTION".equals(type)?recipientId:null,"COMPANY".equals(type)?recipientId:null,
        minimum,reserve,context.staffId());
    auditFinancial("PAYOUT_SETTINGS_UPDATED",null,type,recipientId,context.staffId(),
        Map.of("defaultRemainderRecipientType",type,"defaultRemainderRecipientId",recipientId,
            "payoutFrequency","MONTHLY","payoutMinimumPaise",minimum,"refundReserveBasisPoints",reserve));
    return Map.of("configured",true,"defaultRemainderRecipientType",type,"defaultRemainderRecipientId",recipientId,
        "payoutFrequency","MONTHLY","payoutMinimumPaise",minimum,"refundReserveBasisPoints",reserve,
        "providerConfigured",providerConfigured());
  }

  @PostMapping("/payout-settings/collection-arrangement")
  @org.springframework.transaction.annotation.Transactional
  public Map<String,Object> reviewCollectionArrangement(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requirePermission(context,"QE_PLATFORM_OWNER");
    String status=text(body,"status").toUpperCase(Locale.ROOT);
    if(!List.of("PENDING","VERIFIED","BLOCKED").contains(status))
      throw bad("Choose Pending, Verified or Blocked for the legal/collection-account review.");
    Map<String,Object> current=one("select id from billing_finance_payout_settings where id=1");
    if(current.isEmpty())throw bad("Save the monthly payout rules first, then record the legal/collection-account review.");
    Long collectionEntityId=optionalLong(body.get("paymentCollectionLegalEntityId"));
    Long fundingEntityId=optionalLong(body.get("razorpayxFundingLegalEntityId"));
    String reference=optionalText(body.get("evidenceReference"));
    if(collectionEntityId==null||fundingEntityId==null)
      throw bad("Select both the legal entity receiving course-payment collections and the legal entity owning the RazorpayX payout funding account.");
    if(reference!=null&&reference.length()>180)throw bad("Review reference cannot exceed 180 characters.");
    Map<String,Object> collection=one("select id,status,entity_kind from billing_legal_entity where id=?",collectionEntityId);
    if(collection.isEmpty()||!"ACTIVE".equals(string(collection.get("status"))))
      throw bad("The legal entity receiving payment collections must be active.");
    Map<String,Object> funding=one("select id,status,entity_kind from billing_legal_entity where id=?",fundingEntityId);
    if(funding.isEmpty()||!"ACTIVE".equals(string(funding.get("status")))
        ||!List.of("PLATFORM","COMPANY").contains(string(funding.get("entity_kind"))))
      throw bad("The RazorpayX payout funding owner must be an active platform or company legal entity.");
    if("VERIFIED".equals(status)) {
      if(!Boolean.TRUE.equals(body.get("confirm")))
        throw bad("Confirm that the legal seller and collection-account arrangement has been reviewed with Razorpay and the responsible legal/tax adviser.");
      if(reference==null||reference.length()<3)
        throw bad("Record a review reference, such as the approved agreement or provider case reference.");
    }
    int changed=jdbc.update("""
      update billing_finance_payout_settings set
        payment_collection_legal_entity_id=?,razorpayx_funding_legal_entity_id=?,
        collection_arrangement_status=?,collection_arrangement_reference=?,
        collection_arrangement_verified_by_staff_id=?,collection_arrangement_verified_at=?,
        updated_by_staff_id=?,updated_at=now()
      where id=1
      """,collectionEntityId,fundingEntityId,status,reference,
        "VERIFIED".equals(status)?context.staffId():null,
        "VERIFIED".equals(status)?java.time.OffsetDateTime.now(ZoneId.of("UTC")):null,
        context.staffId());
    if(changed!=1)throw bad("Collection arrangement review could not be saved. Refresh and retry.");
    jdbc.update("""
      insert into billing_financial_audit_log(legal_entity_id,actor_staff_id,action,target_type,target_id,details)
      values(?,?,?,?,?,?::jsonb)
      """,collectionEntityId,context.staffId(),"COLLECTION_ARRANGEMENT_"+status,
        "FINANCE_COLLECTION_ARRANGEMENT","1",
        json(Map.of("status",status,
          "paymentCollectionLegalEntityId",collectionEntityId==null?0:collectionEntityId,
          "razorpayxFundingLegalEntityId",fundingEntityId==null?0:fundingEntityId,
          "evidenceReference",reference==null?"":reference)));
    return Map.of("status",status,"paymentCollectionLegalEntityId",collectionEntityId==null?0:collectionEntityId,
        "razorpayxFundingLegalEntityId",fundingEntityId==null?0:fundingEntityId,
        "evidenceReference",reference==null?"":reference,
        "payoutReady",payoutReadyFromDatabase()&&providerConfigured()&&!webhookSecret.isBlank(),
        "message","Legal/collection arrangement review saved. Transfers remain blocked unless the review is Verified and the RazorpayX API and webhook are configured.");
  }

  @GetMapping("/payout-overview")
  public Map<String,Object> payoutOverview(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("beneficiariesAwaitingApproval",count("select count(*) from finance_payout_beneficiary where status='PENDING_APPROVAL'"));
    out.put("beneficiariesNeedingReview",count("select count(*) from finance_payout_beneficiary where status in ('PROVISIONING','RECONCILIATION_REQUIRED')"));
    out.put("unreconciledFees",count("""
      select count(distinct o.id) from course_payment_order o join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      left join course_payment_order_fee f on f.course_payment_order_id=o.id where o.status='VERIFIED' and f.id is null
      """));
    out.put("batchesAwaitingApproval",count("select count(*) from finance_payout_batch where status='PENDING_APPROVAL'"));
    out.put("payoutsInProgress",count("select count(*) from finance_payout where status in ('SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED')"));
    out.put("payoutsPaidPaise",amount("select coalesce(sum(amount_paise),0) from finance_payout where status='PROCESSED'"));
    out.put("payoutsReversedPaise",amount("select coalesce(sum(amount_paise),0) from finance_payout where status='REVERSED'"));
    out.put("payoutsFailed",count("select count(*) from finance_payout where status in ('FAILED','REJECTED')"));
    out.put("providerConfigured",providerConfigured());
    return out;
  }

  @GetMapping("/payout-beneficiaries")
  public List<Map<String,Object>> listBeneficiaries(@RequestParam(value="status",required=false) String rawStatus,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);String status=rawStatus==null?"":rawStatus.trim().toUpperCase(Locale.ROOT);
    if(!status.isBlank()&&!List.of("PROVISIONING","PENDING_APPROVAL","ACTIVE","SUSPENDED","REJECTED","RECONCILIATION_REQUIRED").contains(status))
      throw bad("Choose a valid beneficiary status.");
    return jdbc.queryForList("""
      select b.id,b.public_id,b.beneficiary_type,b.institution_id,b.legal_entity_id,b.beneficiary_name,
        b.bank_ifsc,b.bank_account_last4,b.provider_contact_id,b.provider_fund_account_id,b.status,
        b.created_by_staff_id,b.approved_by_staff_id,b.approved_at,b.rejection_reason,b.created_at,b.updated_at,
        t.display_name as institution_name,le.legal_name as company_name,
        coalesce(t.display_name,le.legal_name,b.beneficiary_name) as recipient_name
      from finance_payout_beneficiary b left join institution_tenant t on t.id=b.institution_id
      left join billing_legal_entity le on le.id=b.legal_entity_id
      where (?='' or b.status=?) order by case b.status when 'PENDING_APPROVAL' then 0
        when 'RECONCILIATION_REQUIRED' then 1 else 2 end,b.created_at desc,b.id desc limit 300
      """,status,status);
  }

  @PostMapping("/payout-beneficiaries")
  public ResponseEntity<Map<String,Object>> createBeneficiary(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);requireProviderConfiguration();
    String type=text(body,"beneficiaryType").toUpperCase(Locale.ROOT);
    if(!List.of("INSTITUTION","COMPANY").contains(type))throw bad("Choose an institution or company.");
    long recipientId=requiredLong(body.get("beneficiaryId"),"beneficiaryId");
    String name=requiredText(body.get("accountHolderName"),"account holder name");
    String accountNumber=requiredText(body.get("accountNumber"),"bank account number");
    String ifsc=requiredText(body.get("ifsc"),"IFSC").toUpperCase(Locale.ROOT);
    String email=optionalText(body.get("email")),phone=optionalText(body.get("phone"));
    if(!accountNumber.matches("[0-9]{9,18}"))throw bad("Enter a bank account number containing 9–18 digits.");
    if(!ifsc.matches("^[A-Z]{4}0[A-Z0-9]{6}$"))throw bad("Enter a valid 11-character IFSC.");
    if(email==null&&phone==null)throw bad("Provide an email address or phone number for the RazorpayX contact.");
    if(email!=null&&(email.length()>320||!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))throw bad("Enter a valid email address.");
    if(phone!=null&&(phone.length()>20||!phone.matches("^[+0-9() -]{7,20}$")))throw bad("Enter a valid phone number.");
    if("INSTITUTION".equals(type)&&!exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",recipientId))
      throw bad("Select an active institution.");
    if("COMPANY".equals(type)&&!exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",recipientId))
      throw bad("Select an active company legal entity.");
    if(exists("""
      select exists(select 1 from finance_payout_beneficiary where status<>'REJECTED'
        and ((?='INSTITUTION' and institution_id=?) or (?='COMPANY' and legal_entity_id=?)))
      """,type,recipientId,type,recipientId))throw bad("A beneficiary profile already exists for this recipient.");
    String publicId=UUID.randomUUID().toString();
    Long id=jdbc.queryForObject("""
      insert into finance_payout_beneficiary(
        public_id,beneficiary_type,institution_id,legal_entity_id,beneficiary_name,bank_ifsc,
        bank_account_last4,status,created_by_staff_id
      ) values(?::uuid,?,?,?,?,?,?,'PROVISIONING',?) returning id
      """,Long.class,publicId,type,"INSTITUTION".equals(type)?recipientId:null,
        "COMPANY".equals(type)?recipientId:null,name,ifsc,accountNumber.substring(accountNumber.length()-4),context.staffId());
    if(id==null)throw new IllegalStateException("Beneficiary profile could not be saved.");
    String contactId=null;
    try {
      Map<String,Object> contactPayload=new LinkedHashMap<>();contactPayload.put("name",name);
      if(email!=null)contactPayload.put("email",email);if(phone!=null)contactPayload.put("contact",phone);
      contactPayload.put("type","vendor");contactPayload.put("reference_id",publicId);
      contactPayload.put("notes",Map.of("quantaedge_beneficiary_id",Long.toString(id),"quantaedge_beneficiary_type",type));
      JsonNode contact=providerRequest("POST","/contacts",contactPayload,null);
      contactId=requiredProviderText(contact,"id");
      jdbc.update("update finance_payout_beneficiary set provider_contact_id=?,updated_at=now() where id=?",contactId,id);
      Map<String,Object> fundPayload=new LinkedHashMap<>();fundPayload.put("contact_id",contactId);
      fundPayload.put("account_type","bank_account");
      fundPayload.put("bank_account",Map.of("name",name,"ifsc",ifsc,"account_number",accountNumber));
      JsonNode fund=providerRequest("POST","/fund_accounts",fundPayload,null);
      String fundId=requiredProviderText(fund,"id");
      jdbc.update("""
        update finance_payout_beneficiary set provider_fund_account_id=?,status='PENDING_APPROVAL',updated_at=now()
        where id=? and status='PROVISIONING'
        """,fundId,id);
      auditFinancial("PAYOUT_BENEFICIARY_SUBMITTED",id,type,recipientId,context.staffId(),
          Map.of("providerContactId",contactId,"providerFundAccountId",fundId,"accountLast4",accountNumber.substring(accountNumber.length()-4),"ifsc",ifsc));
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",id,"publicId",publicId,"status","PENDING_APPROVAL",
          "beneficiaryType",type,"recipientName",name,"bankIfsc",ifsc,"bankAccountLast4",accountNumber.substring(accountNumber.length()-4),
          "message","Bank details were sent to RazorpayX. Another authorized staff member must approve this beneficiary."));
    } catch(Exception ex) {
      jdbc.update("update finance_payout_beneficiary set status='RECONCILIATION_REQUIRED',updated_at=now() where id=? and status='PROVISIONING'",id);
      auditFinancial("PAYOUT_BENEFICIARY_PROVIDER_SETUP_UNCERTAIN",id,type,recipientId,context.staffId(),
          Map.of("providerContactId",contactId==null?"":contactId,"failure","Provider setup not confirmed; full bank data was not retained."));
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id",id,"publicId",publicId,"status","RECONCILIATION_REQUIRED",
          "message","RazorpayX beneficiary setup is uncertain. Check the provider contact/fund-account records before retrying; the full account number was not retained."));
    }
  }

  @PostMapping("/payout-beneficiaries/{beneficiaryId}/approve")
  public Map<String,Object> approveBeneficiary(@PathVariable long beneficiaryId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);
    Map<String,Object> row=one("select * from finance_payout_beneficiary where id=?",beneficiaryId);
    if(row.isEmpty())throw notFound("Beneficiary not found.");
    if(!"PENDING_APPROVAL".equals(text(row,"status")))throw bad("Only beneficiaries awaiting review can be approved.");
    if(number(row.get("created_by_staff_id"))==number(context.staffId()))throw bad("The submitter cannot approve their own beneficiary.");
    boolean active="INSTITUTION".equals(text(row,"beneficiary_type"))
        ?exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",row.get("institution_id"))
        :exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",row.get("legal_entity_id"));
    if(!active||optionalText(row.get("provider_fund_account_id"))==null||optionalText(row.get("provider_contact_id"))==null)
      throw bad("Beneficiary identity or provider fund account is not ready for approval.");
    int changed=jdbc.update("""
      update finance_payout_beneficiary set status='ACTIVE',approved_by_staff_id=?,approved_at=now(),updated_at=now()
      where id=? and status='PENDING_APPROVAL'
      """,context.staffId(),beneficiaryId);
    if(changed!=1)throw bad("Beneficiary changed during approval. Refresh and retry.");
    auditFinancial("PAYOUT_BENEFICIARY_APPROVED",beneficiaryId,text(row,"beneficiary_type"),beneficiaryId(row),context.staffId(),
        Map.of("providerFundAccountId",row.get("provider_fund_account_id")));
    return Map.of("id",beneficiaryId,"status","ACTIVE","message","Beneficiary is approved for payout batches.");
  }

  @PostMapping("/payout-beneficiaries/{beneficiaryId}/reject")
  public Map<String,Object> rejectBeneficiary(@PathVariable long beneficiaryId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);String reason=requiredText(body.get("reason"),"rejection reason");
    if(reason.length()>500)throw bad("Reason can contain at most 500 characters.");
    Map<String,Object> row=one("select * from finance_payout_beneficiary where id=?",beneficiaryId);
    if(row.isEmpty())throw notFound("Beneficiary not found.");
    if(number(row.get("created_by_staff_id"))==number(context.staffId()))throw bad("The submitter cannot review their own beneficiary.");
    int changed=jdbc.update("""
      update finance_payout_beneficiary set status='REJECTED',rejection_reason=?,updated_at=now()
      where id=? and status in ('PROVISIONING','PENDING_APPROVAL','RECONCILIATION_REQUIRED')
      """,reason,beneficiaryId);
    if(changed!=1)throw bad("Only a pending beneficiary can be rejected.");
    auditFinancial("PAYOUT_BENEFICIARY_REJECTED",beneficiaryId,text(row,"beneficiary_type"),beneficiaryId(row),context.staffId(),Map.of("reason",reason));
    return Map.of("id",beneficiaryId,"status","REJECTED","message","Beneficiary rejected. Check the provider record before creating another profile.");
  }

  @GetMapping("/fee-reconciliation")
  public List<Map<String,Object>> feeQueue(@RequestParam(value="limit",defaultValue="100") int suppliedLimit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);int limit=Math.max(1,Math.min(suppliedLimit,200));
    return jdbc.queryForList("""
      select o.id as order_id,o.course_id,coalesce(nullif(offer.title_override,''),course.display_name) as course_title,
        tenant.display_name as source_institution,o.provider_payment_id,o.captured_amount_paise,o.output_tax_paise,o.verified_at,o.currency
      from course_payment_order o join revenue_share_allocation a on a.course_payment_order_id=o.id
      join institution_marketplace_offer offer on offer.id=o.institution_offer_id
      join curriculum_program course on course.id=o.course_id join institution_tenant tenant on tenant.id=o.institution_id
      left join course_payment_order_fee f on f.course_payment_order_id=o.id
      where o.status='VERIFIED' and f.id is null
      group by o.id,o.course_id,offer.title_override,course.display_name,tenant.display_name,o.provider_payment_id,
        o.captured_amount_paise,o.output_tax_paise,o.verified_at,o.currency
      order by o.verified_at,o.id limit ?
      """,limit);
  }

  @PostMapping("/course-orders/{orderId}/processing-fees")
  public Map<String,Object> recordProcessingFees(@PathVariable long orderId,
      @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);
    long fee=requiredLong(body.get("processingFeePaise"),"processingFeePaise");
    long feeTax=requiredLong(body.get("feeTaxPaise"),"feeTaxPaise");
    String reference=requiredText(body.get("settlementReference"),"settlement reference");
    String source=optionalText(body.get("sourceType"))==null?"MANUAL_RECONCILIATION":text(body,"sourceType").toUpperCase(Locale.ROOT);
    return financialService.reconcileProviderFees(orderId,fee,feeTax,reference,source,idempotencyKey,context.staffId());
  }

  @GetMapping("/payout-batches")
  public List<Map<String,Object>> listBatches(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    return jdbc.queryForList("""
      select b.id,b.public_id,b.period_start,b.period_end,b.currency,b.status,b.beneficiary_count,b.total_amount_paise,
        b.created_by_staff_id,b.approved_by_staff_id,b.approved_at,b.created_at,b.updated_at,
        (select count(*) from finance_payout p where p.batch_id=b.id and p.status='PROCESSED') as paid_count,
        (select count(*) from finance_payout p where p.batch_id=b.id and p.status in ('FAILED','REJECTED','REVERSED')) as exception_count
      from finance_payout_batch b order by b.created_at desc,b.id desc limit 100
      """);
  }

  @GetMapping("/payout-batches/{batchId}")
  public Map<String,Object> batchDetail(@PathVariable long batchId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> batch=one("""
      select b.*,maker.display_name as created_by_name,checker.display_name as approved_by_name
      from finance_payout_batch b left join staff_account maker on maker.id=b.created_by_staff_id
      left join staff_account checker on checker.id=b.approved_by_staff_id where b.id=?
      """,batchId);
    if(batch.isEmpty())throw notFound("Payout batch was not found.");
    batch.put("payouts",jdbc.queryForList("""
      select p.id,p.public_id,p.beneficiary_id,p.amount_paise,p.currency,p.status,p.provider_status,p.provider_payout_id,
        p.transfer_reference,p.status_details,p.created_at,p.submitted_at,p.processed_at,b.beneficiary_type,b.beneficiary_name,
        b.bank_ifsc,b.bank_account_last4,t.display_name as institution_name,le.legal_name as company_name,
        coalesce(t.display_name,le.legal_name,b.beneficiary_name) as recipient_name,
        p.created_by_staff_id,p.approved_by_staff_id,p.approved_at
      from finance_payout p join finance_payout_beneficiary b on b.id=p.beneficiary_id
      left join institution_tenant t on t.id=b.institution_id left join billing_legal_entity le on le.id=b.legal_entity_id
      where p.batch_id=? order by recipient_name,p.id
      """,batchId));
    return batch;
  }

  @PostMapping("/payout-batches")
  @org.springframework.transaction.annotation.Transactional
  public ResponseEntity<Map<String,Object>> createMonthlyBatch(@RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);requirePayoutReady();
    Map<String,Object> settings=one("select payout_minimum_paise,refund_reserve_basis_points,payout_frequency from billing_finance_payout_settings where id=1");
    if(settings.isEmpty()||settings.get("payout_minimum_paise")==null||settings.get("refund_reserve_basis_points")==null)
      throw bad("Complete Finance payout settings, including minimum payout and refund reserve, before preparing a batch.");
    YearMonth previous=YearMonth.from(LocalDate.now(INDIA)).minusMonths(1);
    LocalDate start=body==null||body.get("periodStart")==null?previous.atDay(1):LocalDate.parse(text(body,"periodStart"));
    LocalDate end=body==null||body.get("periodEnd")==null?previous.atEndOfMonth():LocalDate.parse(text(body,"periodEnd"));
    YearMonth month=YearMonth.from(start);
    if(!start.equals(month.atDay(1))||!end.equals(month.atEndOfMonth()))throw bad("A payout batch must cover one complete calendar month.");
    if(end.isAfter(previous.atEndOfMonth()))throw bad("Only a completed month can be paid.");
    List<Map<String,Object>> missingFees=jdbc.queryForList("""
      select distinct o.id as order_id from course_payment_order o
      join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      left join course_payment_order_fee f on f.course_payment_order_id=o.id
      where o.status='VERIFIED' and e.created_at < (?::date + interval '1 day') and f.id is null
      order by o.id limit 25
      """,end);
    if(!missingFees.isEmpty())throw bad("Payouts are blocked because confirmed processor charges are missing for captured order(s): "+
        missingFees.stream().map(r->number(r.get("order_id"))).toList()+". Record actual Razorpay settlement fees first; do not use estimates.");

    List<Map<String,Object>> balances=jdbc.queryForList("""
      select a.id as allocation_id,a.recipient_type,a.institution_id,a.legal_entity_id,a.currency,a.course_payment_order_id,
        coalesce(l.earned_paise,0) as earned_paise,coalesce(l.refunded_paise,0) as refunded_paise,
        coalesce(l.fee_paise,0) as fee_paise,coalesce(l.paid_paise,0) as paid_paise,coalesce(l.reversed_paise,0) as reversed_paise,
        coalesce(r.reserved_paise,0) as reserved_paise,l.first_earned_at,b.id as beneficiary_id,b.status as beneficiary_status,
        b.provider_fund_account_id,coalesce(t.display_name,le.legal_name) as recipient_name
      from revenue_share_allocation a join course_payment_order o on o.id=a.course_payment_order_id and o.status='VERIFIED'
      join lateral (
        select coalesce(sum(amount_paise) filter(where event_type='EARNED'),0) earned_paise,
          coalesce(sum(amount_paise) filter(where event_type='REFUNDED'),0) refunded_paise,
          coalesce(sum(amount_paise) filter(where event_type='PROCESSING_FEE_DEDUCTED'),0) fee_paise,
          coalesce(sum(amount_paise) filter(where event_type='PAID'),0) paid_paise,
          coalesce(sum(amount_paise) filter(where event_type='REVERSED'),0) reversed_paise,
          min(created_at) filter(where event_type='EARNED') first_earned_at
        from revenue_share_allocation_event where allocation_id=a.id
      ) l on true
      left join lateral (
        select coalesce(sum(i.amount_paise),0) reserved_paise
        from finance_payout_item i join finance_payout p on p.id=i.payout_id
        where i.allocation_id=a.id and p.status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED')
      ) r on true
      left join finance_payout_beneficiary b on b.status='ACTIVE' and (
        (a.recipient_type='INSTITUTION' and b.beneficiary_type='INSTITUTION' and b.institution_id=a.institution_id)
        or (a.recipient_type='COMPANY' and b.beneficiary_type='COMPANY' and b.legal_entity_id=a.legal_entity_id))
      left join institution_tenant t on t.id=a.institution_id left join billing_legal_entity le on le.id=a.legal_entity_id
      where l.earned_paise>0 and l.first_earned_at < (?::date + interval '1 day')
      order by l.first_earned_at,a.id
      """,end);
    if(balances.isEmpty())throw bad("No captured course-sale allocations are ready for payout in this period.");
    Map<String,List<Map<String,Object>>> byRecipient=new LinkedHashMap<>();
    Map<String,Map<String,Object>> beneficiaryByKey=new LinkedHashMap<>();
    Map<String,Long> netEarned=new LinkedHashMap<>(),paidNet=new LinkedHashMap<>(),reservedNet=new LinkedHashMap<>();
    for(Map<String,Object> row:balances) {
      String type=text(row,"recipient_type");
      long id="COMPANY".equals(type)?number(row.get("legal_entity_id")):number(row.get("institution_id"));
      String key=type+":"+id;
      byRecipient.computeIfAbsent(key,unused->new ArrayList<>()).add(row);
      beneficiaryByKey.putIfAbsent(key,row);
      long net=Math.subtractExact(Math.subtractExact(number(row.get("earned_paise")),number(row.get("refunded_paise"))),number(row.get("fee_paise")));
      netEarned.merge(key,net,Math::addExact);
      paidNet.merge(key,number(row.get("paid_paise"))-number(row.get("reversed_paise")),Math::addExact);
      reservedNet.merge(key,number(row.get("reserved_paise")),Math::addExact);
    }
    int reserveBps=Math.toIntExact(number(settings.get("refund_reserve_basis_points")));
    long minimum=number(settings.get("payout_minimum_paise"));
    Map<String,Long> payoutAmounts=new LinkedHashMap<>();
    for(String key:byRecipient.keySet()) {
      long amount=Math.max(0L,netEarned.get(key)-percentageOf(netEarned.get(key),reserveBps)
          -paidNet.getOrDefault(key,0L)-reservedNet.getOrDefault(key,0L));
      Map<String,Object> beneficiary=beneficiaryByKey.get(key);
      if(amount<minimum)continue;
      if(beneficiary==null||number(beneficiary.get("beneficiary_id"))<=0)
        throw bad("A beneficiary has a payable balance but no approved bank account: "+key+". Set up and approve its payout profile.");
      long positiveBalance=byRecipient.get(key).stream().mapToLong(row->Math.max(0L,
          number(row.get("earned_paise"))-number(row.get("refunded_paise"))-number(row.get("fee_paise"))
          -number(row.get("paid_paise"))+number(row.get("reversed_paise"))-number(row.get("reserved_paise")))).sum();
      amount=Math.min(amount,positiveBalance);
      if(amount>=minimum)payoutAmounts.put(key,amount);
    }
    if(payoutAmounts.isEmpty())throw bad("No beneficiary balance meets the minimum payout after the refund reserve and existing payout reservations.");
    if(exists("select exists(select 1 from finance_payout_batch where period_start=? and period_end=? and status not in ('FAILED','CANCELLED'))",start,end))
      throw bad("A payout batch already exists for this month. Open the existing batch instead.");
    long total=0;for(long amount:payoutAmounts.values())total=Math.addExact(total,amount);
    Long batchId=jdbc.queryForObject("""
      insert into finance_payout_batch(period_start,period_end,currency,status,beneficiary_count,total_amount_paise,created_by_staff_id)
      values(?,?,'INR','PENDING_APPROVAL',?,?,?) returning id
      """,Long.class,start,end,payoutAmounts.size(),total,context.staffId());
    if(batchId==null)throw new IllegalStateException("Payout batch could not be recorded.");
    for(Map.Entry<String,Long> entry:payoutAmounts.entrySet()) {
      String key=entry.getKey();Map<String,Object> beneficiary=beneficiaryByKey.get(key);long payoutAmount=entry.getValue();
      String idem="QEPO-"+UUID.randomUUID().toString().replace("-","");
      Long payoutId=jdbc.queryForObject("""
        insert into finance_payout(batch_id,beneficiary_id,amount_paise,currency,provider_idempotency_key,status,created_by_staff_id)
        values(?,?,?,'INR',?,'PENDING_APPROVAL',?) returning id
        """,Long.class,batchId,number(beneficiary.get("beneficiary_id")),payoutAmount,idem,context.staffId());
      if(payoutId==null)throw new IllegalStateException("Beneficiary payout could not be recorded.");
      long remaining=payoutAmount;List<Map<String,Object>> allocations=new ArrayList<>(byRecipient.get(key));
      allocations.sort(Comparator.comparing(r->String.valueOf(r.get("first_earned_at"))));
      for(Map<String,Object> row:allocations) {
        if(remaining<=0)break;
        long open=Math.max(0L,number(row.get("earned_paise"))-number(row.get("refunded_paise"))-number(row.get("fee_paise"))
          -number(row.get("paid_paise"))+number(row.get("reversed_paise"))-number(row.get("reserved_paise")));
        long item=Math.min(open,remaining);if(item<=0)continue;
        jdbc.update("insert into finance_payout_item(payout_id,allocation_id,amount_paise) values(?,?,?)",
            payoutId,number(row.get("allocation_id")),item);remaining-=item;
      }
      if(remaining!=0)throw new IllegalStateException("Payout allocation changed while preparing the batch. No transfer was initiated.");
      payoutEvent(payoutId,"CREATED","course-payout:"+payoutId+":created",context.staffId(),null,
          Map.of("batchId",batchId,"beneficiaryKey",key,"amountPaise",payoutAmount,"periodStart",start.toString(),"periodEnd",end.toString()));
      auditFinancial("PAYOUT_PREPARED",payoutId,text(beneficiary,"recipient_type"),
          "COMPANY".equals(text(beneficiary,"recipient_type"))?number(beneficiary.get("legal_entity_id")):number(beneficiary.get("institution_id")),
          context.staffId(),Map.of("batchId",batchId,"amountPaise",payoutAmount,"periodStart",start.toString(),"periodEnd",end.toString()));
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",batchId,"status","PENDING_APPROVAL",
        "periodStart",start.toString(),"periodEnd",end.toString(),"beneficiaryCount",payoutAmounts.size(),"totalAmountPaise",total,
        "message","Batch prepared. A different authorized staff member must approve it before transfers are sent."));
  }

  @PostMapping("/payout-batches/{batchId}/approve")
  public ResponseEntity<Map<String,Object>> approveBatch(@PathVariable long batchId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);requirePayoutReady();
    Map<String,Object> batch=one("select * from finance_payout_batch where id=?",batchId);
    if(batch.isEmpty())throw notFound("Payout batch was not found.");
    if(!"PENDING_APPROVAL".equals(text(batch,"status")))throw bad("Only a batch awaiting approval can be approved.");
    if(number(batch.get("created_by_staff_id"))==number(context.staffId()))throw bad("The batch preparer cannot approve their own batch.");
    int changed=jdbc.update("""
      update finance_payout_batch set status='APPROVED',approved_by_staff_id=?,approved_at=now(),updated_at=now()
      where id=? and status='PENDING_APPROVAL'
      """,context.staffId(),batchId);
    if(changed!=1)throw bad("Batch changed during approval. Refresh and retry.");
    List<Map<String,Object>> payouts=jdbc.queryForList("select id from finance_payout where batch_id=? and status='PENDING_APPROVAL' order by id",batchId);
    for(Map<String,Object> row:payouts) {
      long id=number(row.get("id"));
      jdbc.update("update finance_payout set status='APPROVED',approved_by_staff_id=?,approved_at=now(),updated_at=now() where id=? and status='PENDING_APPROVAL'",
          context.staffId(),id);
      payoutEvent(id,"APPROVED","course-payout:"+id+":approved",context.staffId(),null,Map.of("batchId",batchId));
    }
    List<Map<String,Object>> results=new ArrayList<>();
    for(Map<String,Object> row:payouts) {
      long id=number(row.get("id"));
      try{results.add(submitApprovedPayout(id,context.staffId()));}
      catch(Exception ex){markPayoutUnknown(id,context.staffId());results.add(payoutSummary(id));}
    }
    refreshBatchStatus(batchId);
    return ResponseEntity.ok(Map.of("batchId",batchId,
        "status",text(one("select status from finance_payout_batch where id=?",batchId),"status"),
        "payouts",results,"message","Approval was recorded. Payout completion is shown only after RazorpayX confirms the transfer."));
  }

  @GetMapping("/payouts/{payoutId}/refresh")
  public ResponseEntity<Map<String,Object>> refreshPayout(@PathVariable long payoutId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);requireProviderConfiguration();
    Map<String,Object> payout=one("select * from finance_payout where id=?",payoutId);
    if(payout.isEmpty())throw notFound("Payout not found.");
    String providerId=optionalText(payout.get("provider_payout_id"));
    if(providerId==null)return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id",payoutId,"status",text(payout,"status"),
        "message","RazorpayX did not return a payout ID. Review the provider record before reusing the same idempotency key."));
    try {
      JsonNode provider=providerRequest("GET","/payouts/"+providerId,null,null);
      Map<String,Object> out=applyProviderStatus(payoutId,provider,context.staffId());
      refreshBatchStatus(number(payout.get("batch_id")));
      return ResponseEntity.ok(out);
    } catch(Exception ex) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(payoutSummary(payoutId));
    }
  }

  @PostMapping("/payouts/{payoutId}/retry-submission")
  public ResponseEntity<Map<String,Object>> retryPayout(@PathVariable long payoutId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);requirePayoutReady();
    Map<String,Object> row=one("select * from finance_payout where id=?",payoutId);
    if(row.isEmpty())throw notFound("Payout not found.");
    if(!"RECONCILIATION_REQUIRED".equals(text(row,"status")))throw bad("Only an uncertain payout submission can be retried.");
    if(optionalText(row.get("provider_payout_id"))!=null)throw bad("A provider payout ID exists. Refresh its status instead of resubmitting.");
    return ResponseEntity.ok(submitApprovedPayout(payoutId,context.staffId()));
  }

  private Map<String,Object> submitApprovedPayout(long payoutId,long actorStaffId) {
    Map<String,Object> row=one("""
      select p.*,b.provider_fund_account_id,b.beneficiary_name,b.beneficiary_type,b.institution_id,b.legal_entity_id,
        b.status as beneficiary_status
      from finance_payout p join finance_payout_beneficiary b on b.id=p.beneficiary_id where p.id=?
      """,payoutId);
    if(row.isEmpty())throw notFound("Payout not found.");
    if(!List.of("APPROVED","RECONCILIATION_REQUIRED").contains(text(row,"status")))throw bad("Payout is not approved for submission.");
    if(!"ACTIVE".equals(text(row,"beneficiary_status"))||optionalText(row.get("provider_fund_account_id"))==null)
      throw bad("The beneficiary account is not active.");
    jdbc.update("update finance_payout set status='SUBMITTING',status_details=null,updated_at=now() where id=? and status in ('APPROVED','RECONCILIATION_REQUIRED')",payoutId);
    payoutEvent(payoutId,"SUBMITTED","course-payout:"+payoutId+":submitted:"+UUID.randomUUID(),actorStaffId,null,
        Map.of("providerIdempotencyKey",row.get("provider_idempotency_key")));
    Map<String,Object> payload=new LinkedHashMap<>();
    payload.put("account_number",sourceAccountNumber);payload.put("fund_account_id",text(row,"provider_fund_account_id"));
    payload.put("amount",number(row.get("amount_paise")));payload.put("currency","INR");payload.put("mode","IMPS");
    payload.put("purpose","vendor_bill");payload.put("reference_id",text(row,"public_id"));
    payload.put("narration","QuantaEdge beneficiary payout");payload.put("queue_if_low_balance",true);
    payload.put("notes",Map.of("quantaedge_payout_id",Long.toString(payoutId),
        "quantaedge_batch_id",Long.toString(number(row.get("batch_id"))),
        "quantaedge_beneficiary_id",Long.toString(number(row.get("beneficiary_id")))));
    try {
      JsonNode provider=providerRequest("POST","/payouts",payload,text(row,"provider_idempotency_key"));
      return applyProviderStatus(payoutId,provider,actorStaffId);
    } catch(Exception ex) {
      markPayoutUnknown(payoutId,actorStaffId);
      return payoutSummary(payoutId);
    }
  }

  /**
   * Applies a signature-verified RazorpayX webhook. A provider payout ID or our saved payout ID
   * in provider notes identifies the transfer; unmatched events are retained for reconciliation.
   */
  @org.springframework.transaction.annotation.Transactional
  public void processPayoutWebhook(JsonNode payout,String providerEventId,String eventType,String payloadSha256) {
    String eventId=providerEventId==null?"":providerEventId.trim();
    String kind=eventType==null?"":eventType.trim();
    String providerId=payout==null?"":payout.path("id").asText("").trim();
    if(!eventId.matches("[A-Za-z0-9._:-]{1,120}")||kind.isBlank()||kind.length()>80
        ||!providerId.matches("[A-Za-z0-9_-]{4,80}")
        ||payloadSha256==null||!payloadSha256.matches("[0-9a-f]{64}"))
      throw bad("RazorpayX payout webhook is missing a valid event ID, payout ID or event type.");
    if(exists("select exists(select 1 from finance_payout_provider_event where provider_event_id=?)",eventId))return;

    Map<String,Object> local=one("select id,batch_id from finance_payout where provider_payout_id=?",providerId);
    if(local.isEmpty()) {
      Long notedId=nullableLong(payout.path("notes").path("quantaedge_payout_id").asText(null));
      if(notedId!=null&&notedId>0)local=one("select id,batch_id from finance_payout where id=?",notedId);
    }
    String disposition="UNMATCHED";
    Long localPayoutId=local.isEmpty()?null:number(local.get("id"));
    if(localPayoutId!=null) {
      applyProviderStatus(localPayoutId,payout,null);
      Map<String,Object> updated=one("select status from finance_payout where id=?",localPayoutId);
      disposition="RECONCILIATION_REQUIRED".equals(string(updated.get("status")))
          ?"RECONCILIATION_REQUIRED":"PROCESSED";
    }
    jdbc.update("""
      insert into finance_payout_provider_event(
        provider_event_id,provider_payout_id,event_type,payload_sha256,disposition,processed_at
      ) values(?,?,?,?,?,now())
      on conflict(provider_event_id) do nothing
      """,eventId,providerId,kind,payloadSha256,disposition);
  }

  private Map<String,Object> applyProviderStatus(long payoutId,JsonNode provider,Long actorStaffId) {
    String providerId=optionalText(provider==null?null:provider.path("id").asText(null));
    String providerStatus=optionalText(provider==null?null:provider.path("status").asText(null));
    if(providerId==null||!providerId.matches("[A-Za-z0-9_-]{4,80}")||providerStatus==null)
      throw new IllegalStateException("RazorpayX response has no valid payout ID and status.");
    String normalized=providerStatus.toLowerCase(Locale.ROOT);
    String status=switch(normalized){
      case "pending"->"PENDING";case "queued"->"QUEUED";case "initiated","processing"->"PROCESSING";
      case "processed"->"PROCESSED";case "reversed"->"REVERSED";case "failed"->"FAILED";
      case "rejected","cancelled"->"REJECTED";default->"RECONCILIATION_REQUIRED";
    };
    Map<String,Object> current=one("select status,batch_id from finance_payout where id=?",payoutId);
    if(current.isEmpty())throw notFound("Payout not found.");
    String prior=text(current,"status");
    if("PROCESSED".equals(prior)&&!"PROCESSED".equals(status)&&!"REVERSED".equals(status))return payoutSummary(payoutId);
    if("REVERSED".equals(prior)&&!"REVERSED".equals(status))return payoutSummary(payoutId);
    if(List.of("FAILED","REJECTED").contains(prior)&&!prior.equals(status))return payoutSummary(payoutId);
    String utr=optionalText(provider.path("utr").asText(null));
    String description=optionalText(provider.path("status_details").path("description").asText(null));
    if(description==null)description=optionalText(provider.path("failure_reason").asText(null));
    jdbc.update("""
      update finance_payout set provider_payout_id=?,provider_status=?,status=?,transfer_reference=?,status_details=?,
        submitted_at=coalesce(submitted_at,now()),
        processed_at=case when ? in ('PROCESSED','REVERSED') then coalesce(processed_at,now()) else processed_at end,updated_at=now()
      where id=?
      """,providerId,normalized,status,utr,description,status,payoutId);
    String eventType=switch(status){case "PENDING"->"PROVIDER_PENDING";case "QUEUED"->"QUEUED";case "PROCESSING"->"PROCESSING";
      case "PROCESSED"->"PROCESSED";case "REVERSED"->"REVERSED";case "FAILED"->"FAILED";case "REJECTED"->"REJECTED";default->"RECONCILIATION_REQUIRED";};
    payoutEvent(payoutId,eventType,"course-payout:"+payoutId+":provider:"+normalized+":"+providerId,actorStaffId,providerId,
        Map.of("providerStatus",normalized,"transferReference",utr==null?"":utr,"statusDetails",description==null?"":description));
    if("PROCESSED".equals(status)&&!"PROCESSED".equals(prior)&&!"REVERSED".equals(prior))postPayoutLedgerEvents(payoutId,"PAID",actorStaffId);
    else if("REVERSED".equals(status)&&"PROCESSED".equals(prior))postPayoutLedgerEvents(payoutId,"REVERSED",actorStaffId);
    refreshBatchStatus(number(current.get("batch_id")));
    return payoutSummary(payoutId);
  }

  private void postPayoutLedgerEvents(long payoutId,String eventType,Long actorStaffId) {
    List<Map<String,Object>> items=jdbc.queryForList("""
      select i.allocation_id,i.amount_paise,a.currency,a.course_payment_order_id
      from finance_payout_item i join revenue_share_allocation a on a.id=i.allocation_id
      where i.payout_id=? order by i.allocation_id
      """,payoutId);
    for(Map<String,Object> item:items) {
      long allocationId=number(item.get("allocation_id"));
      jdbc.update("""
        insert into revenue_share_allocation_event(allocation_id,event_type,amount_paise,currency,idempotency_key,actor_staff_id,details)
        values(?,?,?,?,?,?,?::jsonb) on conflict(idempotency_key) do nothing
        """,allocationId,eventType,number(item.get("amount_paise")),string(item.get("currency")),
          "course-payout:"+payoutId+":allocation:"+allocationId+":"+eventType.toLowerCase(Locale.ROOT),
          actorStaffId,json(Map.of("payoutId",payoutId,"coursePaymentOrderId",number(item.get("course_payment_order_id")))));
    }
  }

  private void markPayoutUnknown(long payoutId,long actorStaffId) {
    Map<String,Object> row=one("select batch_id,status from finance_payout where id=?",payoutId);
    jdbc.update("""
      update finance_payout set status='RECONCILIATION_REQUIRED',
        status_details='RazorpayX did not confirm this request. Refresh provider state or retry with the same saved idempotency key.',
        updated_at=now()
      where id=? and status not in ('PROCESSED','REVERSED','FAILED','REJECTED')
      """,payoutId);
    payoutEvent(payoutId,"RECONCILIATION_REQUIRED","course-payout:"+payoutId+":reconciliation:"+UUID.randomUUID(),actorStaffId,null,
        Map.of("message","Provider result is uncertain; payout balance remains reserved."));
    if(!row.isEmpty())refreshBatchStatus(number(row.get("batch_id")));
  }

  private Map<String,Object> payoutSummary(long payoutId) {
    return one("""
      select p.id,p.public_id,p.batch_id,p.beneficiary_id,p.amount_paise,p.currency,p.status,p.provider_status,p.provider_payout_id,
        p.provider_idempotency_key,p.transfer_reference,p.status_details,p.created_at,p.submitted_at,p.processed_at,
        b.beneficiary_type,b.beneficiary_name,b.bank_ifsc,b.bank_account_last4,t.display_name as institution_name,
        le.legal_name as company_name,coalesce(t.display_name,le.legal_name,b.beneficiary_name) as recipient_name
      from finance_payout p join finance_payout_beneficiary b on b.id=p.beneficiary_id
      left join institution_tenant t on t.id=b.institution_id left join billing_legal_entity le on le.id=b.legal_entity_id where p.id=?
      """,payoutId);
  }

  private void refreshBatchStatus(long batchId) {
    if(batchId<=0)return;List<Map<String,Object>> rows=jdbc.queryForList("select status from finance_payout where batch_id=?",batchId);
    if(rows.isEmpty())return;
    long processed=rows.stream().filter(r->"PROCESSED".equals(text(r,"status"))).count();
    long terminal=rows.stream().filter(r->List.of("PROCESSED","FAILED","REJECTED","REVERSED").contains(text(r,"status"))).count();
    long failures=rows.stream().filter(r->List.of("FAILED","REJECTED","REVERSED").contains(text(r,"status"))).count();
    String status=terminal==rows.size()?(failures==0?"COMPLETED":processed>0?"PARTIAL":"FAILED")
        :(processed>0||failures>0?"PARTIAL":"PROCESSING");
    jdbc.update("update finance_payout_batch set status=?,updated_at=now() where id=? and status<>'CANCELLED'",status,batchId);
  }

  private void payoutEvent(long payoutId,String eventType,String idempotencyKey,Long staffId,String providerEventId,Map<String,Object> details) {
    jdbc.update("""
      insert into finance_payout_event(payout_id,event_type,idempotency_key,actor_staff_id,provider_event_id,details)
      values(?,?,?,?,?,?::jsonb) on conflict(idempotency_key) do nothing
      """,payoutId,eventType,idempotencyKey,staffId,providerEventId,json(details));
  }

  private void auditFinancial(String action,Long targetId,String recipientType,Long recipientId,long actorStaffId,Map<String,Object> details) {
    Long institutionId=null,legalEntityId=null;
    if(recipientId!=null&&"INSTITUTION".equals(recipientType))institutionId=recipientId;
    else if(recipientId!=null&&"COMPANY".equals(recipientType))legalEntityId=recipientId;
    if(institutionId==null&&legalEntityId==null&&targetId!=null) {
      Map<String,Object> row=one("select institution_id,legal_entity_id from finance_payout_beneficiary where id=?",targetId);
      if(row.isEmpty())row=one("select b.institution_id,b.legal_entity_id from finance_payout p join finance_payout_beneficiary b on b.id=p.beneficiary_id where p.id=?",targetId);
      institutionId=nullableLong(row.get("institution_id"));legalEntityId=nullableLong(row.get("legal_entity_id"));
    }
    if(institutionId==null&&legalEntityId==null)return;
    jdbc.update("""
      insert into billing_financial_audit_log(legal_entity_id,institution_id,actor_staff_id,action,target_type,target_id,details)
      values(?,?,?,?,?,?,?::jsonb)
      """,legalEntityId,institutionId,actorStaffId,action,action.startsWith("PAYOUT_BATCH")?"FINANCE_PAYOUT_BATCH":"FINANCE_PAYOUT",
        targetId==null?null:Long.toString(targetId),json(details));
  }

  private JsonNode providerRequest(String method,String path,Map<String,Object> payload,String idempotencyKey) {
    requireProviderConfiguration();
    try {
      String auth=Base64.getEncoder().encodeToString((keyId+":"+keySecret).getBytes(StandardCharsets.UTF_8));
      HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(java.time.Duration.ofSeconds(20))
          .header("Authorization","Basic "+auth).header("Accept","application/json");
      if("GET".equalsIgnoreCase(method))b.GET();
      else if("POST".equalsIgnoreCase(method)) {
        b.header("Content-Type","application/json");
        if(idempotencyKey!=null&&!idempotencyKey.isBlank())b.header("X-Payout-Idempotency",idempotencyKey);
        b.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload==null?Map.of():payload),StandardCharsets.UTF_8));
      } else throw new IllegalArgumentException("Unsupported RazorpayX request method.");
      HttpResponse<String> response=http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if(response.statusCode()<200||response.statusCode()>=300)
        throw new IllegalStateException("RazorpayX returned HTTP "+response.statusCode()+". Reconcile provider state before creating another payout.");
      JsonNode node=mapper.readTree(response.body());
      if(node==null||!node.isObject())throw new IllegalStateException("RazorpayX returned an invalid response.");
      return node;
    } catch(IllegalStateException ex){throw ex;}
    catch(Exception ex){throw new IllegalStateException("RazorpayX response could not be confirmed. Do not create a new payout.",ex);}
  }

  private boolean payoutReadyFromDatabase() {
    Map<String,Object> settings=one("""
      select payment_collection_legal_entity_id,razorpayx_funding_legal_entity_id,collection_arrangement_status
      from billing_finance_payout_settings where id=1
      """);
    Long collectionId=nullableLong(settings.get("payment_collection_legal_entity_id"));
    Long fundingId=nullableLong(settings.get("razorpayx_funding_legal_entity_id"));
    if(collectionId==null||fundingId==null||!"VERIFIED".equals(string(settings.get("collection_arrangement_status"))))return false;
    return exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE')",collectionId)
        &&exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",fundingId);
  }
  private void requirePayoutReady() {
    requireProviderConfiguration();
    if(webhookSecret.isBlank())
      throw bad("Configure the RazorpayX payout webhook secret before enabling transfers.");
    if(!payoutReadyFromDatabase())
      throw bad("Payouts are blocked. An authorized platform owner must verify the legal seller and collection-account arrangement, including the legal collection entity and RazorpayX funding owner.");
  }
  private boolean hasPermission(AuthContext context,String permission) {
    try { authorization.requirePermission(context,permission);return true; }
    catch(SecurityException ex) { return false; }
  }

  private void requireProviderConfiguration() {
    if(!providerConfigured())throw new IllegalStateException("RazorpayX credentials and source account number must be configured on the API server.");
  }
  private boolean providerConfigured(){return !keyId.isBlank()&&!keySecret.isBlank()&&!sourceAccountNumber.isBlank();}
  private AuthContext requireManage(AuthContext c){return authorization.requirePermission(c,"QE_FINANCE_PAYOUT_MANAGE");}
  private AuthContext requireApprover(AuthContext c){
    c=authorization.requirePermission(c,"QE_FINANCE_PAYOUT_APPROVE");
    if(c.staffId()==null)throw new SecurityException("An authorized HRMS staff identity is required to approve payouts.");
    return c;
  }
  private Long beneficiaryId(Map<String,Object> row){return "COMPANY".equals(text(row,"beneficiary_type"))?nullableLong(row.get("legal_entity_id")):nullableLong(row.get("institution_id"));}
  private String requiredProviderText(JsonNode node,String key){
    String v=node==null?"":node.path(key).asText("").trim();
    if(!v.matches("[A-Za-z0-9_-]{4,80}"))throw new IllegalStateException("RazorpayX did not return a valid "+key+".");return v;
  }
  private Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(rows.getFirst());}
  private long count(String sql,Object... args){Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n;}
  private long amount(String sql,Object... args){Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n;}
  private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
  private String json(Object v){try{return mapper.writeValueAsString(v==null?Map.of():v);}catch(Exception e){throw new IllegalStateException("Finance details could not be serialized.",e);}}
  private static String text(Map<String,Object> m,String k){Object v=m==null?null:m.get(k);return v==null?"":String.valueOf(v).trim();}
  private static String requiredText(Object v,String label){String s=v==null?"":String.valueOf(v).trim();if(s.isBlank())throw bad("Enter "+label+".");return s;}
  private static long requiredLong(Object v,String label){try{return new BigDecimal(String.valueOf(v)).longValueExact();}catch(Exception e){throw bad("Enter an integer amount for "+label+".");}}
  private static String optionalText(Object v){if(v==null)return null;String s=String.valueOf(v).trim();return s.isBlank()?null:s;}
  private static Long nullableLong(Object v){if(v==null)return null;try{return new BigDecimal(String.valueOf(v)).longValueExact();}catch(Exception e){return null;}}
  private static long number(Object v){if(v instanceof Number n)return n.longValue();try{return Long.parseLong(String.valueOf(v));}catch(Exception e){return 0;}}
  private static long percentageOf(long amount,int bps){return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bps)).divide(BigDecimal.valueOf(10_000),0,RoundingMode.HALF_UP).longValueExact();}
  private static IllegalArgumentException bad(String m){return new IllegalArgumentException(m);}
  private static org.springframework.web.server.ResponseStatusException notFound(String m){return new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,m);}
}