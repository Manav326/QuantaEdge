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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
  private final PayoutSettlementPdfRenderer settlementPdfRenderer;
  private final FiscalDocumentStorage fiscalDocumentStorage;
  private final String keyId;
  private final String keySecret;
  private final String sourceAccountNumber;
  private final String webhookSecret;
  private final String baseUrl;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

  public FinancePayoutController(JdbcTemplate jdbc,AuthorizationService authorization,
      CourseOrderFinancialService financialService,ObjectMapper mapper,
      PayoutSettlementPdfRenderer settlementPdfRenderer,FiscalDocumentStorage fiscalDocumentStorage,
      @Value("\u0024{app.razorpayx.key-id:\u0024{app.razorpay.key-id:}}") String keyId,
      @Value("\u0024{app.razorpayx.key-secret:\u0024{app.razorpay.key-secret:}}") String keySecret,
      @Value("\u0024{app.razorpayx.account-number:}") String sourceAccountNumber,
      @Value("\u0024{app.razorpayx.webhook-secret:}") String webhookSecret,
      @Value("\u0024{app.razorpayx.base-url:\u0024{app.razorpay.base-url:https://api.razorpay.com/v1}}") String baseUrl) {
    this.jdbc=jdbc;this.authorization=authorization;this.financialService=financialService;this.mapper=mapper;
    this.settlementPdfRenderer=settlementPdfRenderer;this.fiscalDocumentStorage=fiscalDocumentStorage;
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
    Long collectionEntityId=nullableLong(body.get("paymentCollectionLegalEntityId"));
    Long fundingEntityId=nullableLong(body.get("razorpayxFundingLegalEntityId"));
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
    LocalDate nextBatchEnd=YearMonth.from(LocalDate.now(INDIA)).minusMonths(1).atEndOfMonth();
    out.put("unreconciledFees",count("""
      select count(distinct o.id) from course_payment_order o join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      left join course_payment_order_fee f on f.course_payment_order_id=o.id where o.status='VERIFIED' and f.id is null
      """));
    out.put("unreconciledFeesForNextBatch",count("""
      select count(distinct o.id) from course_payment_order o
      join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      left join course_payment_order_fee f on f.course_payment_order_id=o.id
      where o.status='VERIFIED' and f.id is null and e.created_at < (?::date + interval '1 day')
      """,nextBatchEnd));
    out.put("batchesAwaitingApproval",count("select count(*) from finance_payout_batch where status='PENDING_APPROVAL'"));
    out.put("payoutsInProgress",count("select count(*) from finance_payout where status in ('SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED')"));
    out.put("payoutsPaidPaise",amount("select coalesce(sum(amount_paise),0) from finance_payout where status='PROCESSED'"));
    out.put("payoutsReversedPaise",amount("select coalesce(sum(amount_paise),0) from finance_payout where status='REVERSED'"));
    out.put("payoutsFailed",count("select count(*) from finance_payout where status in ('FAILED','REJECTED')"));
    out.put("payoutFeeReconciliationPending",count("""
      select count(*) from finance_payout_batch b
      where b.provider_fee_reconciled_at is null
        and exists(select 1 from finance_payout p where p.batch_id=b.id)
        and not exists(select 1 from finance_payout p where p.batch_id=b.id
          and p.status not in ('PROCESSED','FAILED','REJECTED','REVERSED'))
      """));
    out.put("pendingFeeAdjustments",count("""
      select count(*) from course_payment_order_fee_adjustment q
      where not exists(select 1 from course_payment_order_fee_adjustment_review r where r.adjustment_id=q.id)
      """));
    out.put("pendingFeeAdjustmentsForNextBatch",count("""
      select count(distinct q.id) from course_payment_order_fee_adjustment q
      join course_payment_order o on o.id=q.course_payment_order_id and o.status='VERIFIED'
      join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      where e.created_at < (?::date + interval '1 day')
        and not exists(select 1 from course_payment_order_fee_adjustment_review r where r.adjustment_id=q.id)
      """,nextBatchEnd));
    out.put("providerConfigured",providerConfigured());
    return out;
  }

  @GetMapping("/payout-beneficiaries")
  public List<Map<String,Object>> listBeneficiaries(@RequestParam(value="status",required=false) String rawStatus,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);String status=rawStatus==null?"":rawStatus.trim().toUpperCase(Locale.ROOT);
    if(!status.isBlank()&&!List.of("PROVISIONING","PENDING_APPROVAL","ACTIVE","SUSPENDED","REJECTED","RECONCILIATION_REQUIRED").contains(status))
      throw bad("Choose a valid beneficiary status.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select b.id,b.public_id,b.beneficiary_type,b.institution_id,b.legal_entity_id,b.beneficiary_name,b.replaces_beneficiary_id,
        b.bank_ifsc,b.bank_account_last4,b.provider_contact_id,b.provider_fund_account_id,b.status,
        b.created_by_staff_id,maker.display_name as created_by_name,b.approved_by_staff_id,b.approved_at,b.rejection_reason,b.created_at,b.updated_at,
        t.display_name as institution_name,le.legal_name as company_name,
        coalesce(t.display_name,le.legal_name,b.beneficiary_name) as recipient_name
      from finance_payout_beneficiary b left join institution_tenant t on t.id=b.institution_id
      left join billing_legal_entity le on le.id=b.legal_entity_id
      left join staff_account maker on maker.id=b.created_by_staff_id
      where (?='' or b.status=?) order by case b.status when 'PENDING_APPROVAL' then 0
        when 'RECONCILIATION_REQUIRED' then 1 else 2 end,b.created_at desc,b.id desc limit 300
      """,status,status);
    boolean canReview=context.staffId()!=null&&hasPermission(context,"QE_FINANCE_PAYOUT_APPROVE");
    for(Map<String,Object> row:rows)row.put("canApprove",canReview&&number(row.get("created_by_staff_id"))!=number(context.staffId()));
    return rows;
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

  @PostMapping("/payout-beneficiaries/{beneficiaryId}/replacement")
  public ResponseEntity<Map<String,Object>> requestBeneficiaryReplacement(@PathVariable long beneficiaryId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);requireProviderConfiguration();
    Map<String,Object> old=one("select * from finance_payout_beneficiary where id=?",beneficiaryId);
    if(old.isEmpty())throw notFound("Existing beneficiary was not found.");
    if(!"ACTIVE".equals(text(old,"status")))throw bad("Only the currently approved bank account can start a replacement.");
    if(exists("select exists(select 1 from finance_payout_beneficiary where replaces_beneficiary_id=? and status<>'REJECTED')",beneficiaryId))
      throw bad("A bank-account replacement for this beneficiary is already pending or was already approved. Resolve that replacement record before starting another.");
    String name=requiredText(body.get("accountHolderName"),"account holder legal name");
    String accountNumber=requiredText(body.get("accountNumber"),"new bank account number");
    String ifsc=requiredText(body.get("ifsc"),"new IFSC").toUpperCase(Locale.ROOT);
    String email=optionalText(body.get("email")),phone=optionalText(body.get("phone"));
    if(name.length()>240||!accountNumber.matches("[0-9]{9,18}"))throw bad("Enter an account-holder name (up to 240 characters) and a 9–18 digit bank account number.");
    if(!ifsc.matches("^[A-Z]{4}0[A-Z0-9]{6}$"))throw bad("Enter a valid 11-character IFSC.");
    if(email==null&&phone==null)throw bad("Provide an email address or phone number for the RazorpayX contact.");
    if(email!=null&&(email.length()>320||!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))throw bad("Enter a valid email address.");
    if(phone!=null&&(phone.length()>20||!phone.matches("^[+0-9() -]{7,20}$")))throw bad("Enter a valid phone number.");
    String type=text(old,"beneficiary_type");Long institutionId=nullableLong(old.get("institution_id")),legalEntityId=nullableLong(old.get("legal_entity_id"));
    String publicId=UUID.randomUUID().toString();
    Long replacementId=jdbc.queryForObject("""
      insert into finance_payout_beneficiary(
        public_id,beneficiary_type,institution_id,legal_entity_id,beneficiary_name,bank_ifsc,bank_account_last4,
        status,created_by_staff_id,replaces_beneficiary_id
      ) values(?::uuid,?,?,?,?,?,?,'PROVISIONING',?,?) returning id
      """,Long.class,publicId,type,institutionId,legalEntityId,name,ifsc,accountNumber.substring(accountNumber.length()-4),context.staffId(),beneficiaryId);
    if(replacementId==null)throw new IllegalStateException("Bank-account replacement request could not be created.");
    String contactId=null;
    try {
      Map<String,Object> contactPayload=new LinkedHashMap<>();contactPayload.put("name",name);
      if(email!=null)contactPayload.put("email",email);if(phone!=null)contactPayload.put("contact",phone);
      contactPayload.put("type","vendor");contactPayload.put("reference_id",publicId);
      contactPayload.put("notes",Map.of("quantaedge_beneficiary_id",Long.toString(replacementId),
        "quantaedge_beneficiary_type",type,"replaces_beneficiary_id",Long.toString(beneficiaryId)));
      JsonNode contact=providerRequest("POST","/contacts",contactPayload,null);
      contactId=requiredProviderText(contact,"id");
      jdbc.update("update finance_payout_beneficiary set provider_contact_id=?,updated_at=now() where id=?",contactId,replacementId);
      JsonNode fund=providerRequest("POST","/fund_accounts",Map.of("contact_id",contactId,"account_type","bank_account",
        "bank_account",Map.of("name",name,"ifsc",ifsc,"account_number",accountNumber)),null);
      String fundId=requiredProviderText(fund,"id");
      int changed=jdbc.update("""
        update finance_payout_beneficiary set provider_fund_account_id=?,status='PENDING_APPROVAL',updated_at=now()
        where id=? and status='PROVISIONING' and replaces_beneficiary_id=?
        """,fundId,replacementId,beneficiaryId);
      if(changed!=1)throw bad("Replacement request changed during provider setup. Reconcile the existing provider record before retrying.");
      auditFinancial("PAYOUT_BENEFICIARY_REPLACEMENT_SUBMITTED",replacementId,type,
        "INSTITUTION".equals(type)?institutionId:legalEntityId,context.staffId(),
        Map.of("replacesBeneficiaryId",beneficiaryId,"providerContactId",contactId,"providerFundAccountId",fundId,
          "accountLast4",accountNumber.substring(accountNumber.length()-4),"ifsc",ifsc));
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",replacementId,"publicId",publicId,"status","PENDING_APPROVAL",
        "replacesBeneficiaryId",beneficiaryId,"message","Replacement bank account is registered and awaiting independent approval. The current approved account remains active until approval; any in-flight payout must finish before switching."));
    } catch(Exception ex) {
      jdbc.update("update finance_payout_beneficiary set status='RECONCILIATION_REQUIRED',updated_at=now() where id=? and status='PROVISIONING'",replacementId);
      auditFinancial("PAYOUT_BENEFICIARY_REPLACEMENT_SETUP_UNCERTAIN",replacementId,type,
        "INSTITUTION".equals(type)?institutionId:legalEntityId,context.staffId(),
        Map.of("replacesBeneficiaryId",beneficiaryId,"providerContactId",contactId==null?"":contactId,
          "failure","Replacement setup not confirmed; full bank data was not retained."));
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id",replacementId,"publicId",publicId,"status","RECONCILIATION_REQUIRED",
        "replacesBeneficiaryId",beneficiaryId,"message","Replacement setup is uncertain. The previous approved bank account remains active. Reconcile the saved provider reference before retrying; no new replacement will be created automatically."));
    }
  }

  @PostMapping("/payout-beneficiaries/{beneficiaryId}/reconcile-provider-setup")
  public ResponseEntity<Map<String,Object>> reconcileBeneficiaryProviderSetup(@PathVariable long beneficiaryId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);requireProviderConfiguration();
    Map<String,Object> local=one("select * from finance_payout_beneficiary where id=?",beneficiaryId);
    if(local.isEmpty())throw notFound("Beneficiary not found.");
    if(!List.of("PROVISIONING","RECONCILIATION_REQUIRED").contains(text(local,"status")))
      throw bad("Only an interrupted or uncertain provider setup can be reconciled this way.");
    String accountNumber=requiredText(body.get("accountNumber"),"bank account number");
    if(!accountNumber.matches("[0-9]{9,18}"))throw bad("Enter the original 9–18 digit account number for exact provider matching.");
    String publicId=text(local,"public_id"),beneficiaryName=text(local,"beneficiary_name");
    String localIfsc=text(local,"bank_ifsc").toUpperCase(Locale.ROOT);
    if(!publicId.matches("[A-Fa-f0-9-]{20,40}")||beneficiaryName.isBlank()||localIfsc.isBlank())
      throw bad("The saved beneficiary identity is incomplete. Do not retry setup; ask a platform owner to reconcile this record.");

    String contactId=optionalText(local.get("provider_contact_id"));
    JsonNode contact;
    if(contactId!=null) {
      if(!contactId.matches("cont_[A-Za-z0-9_-]{4,80}"))throw bad("The saved provider contact reference is invalid; manual review is required.");
      contact=providerRequest("GET","/contacts/"+contactId,null,null);
    } else {
      JsonNode contactCollection=providerRequest("GET","/contacts?reference_id="+java.net.URLEncoder.encode(publicId,StandardCharsets.UTF_8)+"&count=100&skip=0",null,null);
      JsonNode items=contactCollection.path("items");
      if(!items.isArray())throw new IllegalStateException("RazorpayX contact lookup returned an invalid collection.");
      if(items.size()>=100)throw bad("The provider contact search returned a full page. Manual review is required; no contact was created.");
      List<JsonNode> matchingContacts=new ArrayList<>();
      for(JsonNode item:items) {
        if(publicId.equals(item.path("reference_id").asText(""))
            &&beneficiaryName.equalsIgnoreCase(item.path("name").asText("").trim()))matchingContacts.add(item);
      }
      if(matchingContacts.size()!=1)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id",beneficiaryId,"status","RECONCILIATION_REQUIRED",
          "message",matchingContacts.isEmpty()
            ?"No existing RazorpayX contact matched this QuantaEdge reference. No new contact or fund account was created. Locate the provider record by the saved reference before retrying setup."
            :"More than one provider contact matched this reference. No record was linked; a platform owner must resolve the ambiguity."));
      contact=matchingContacts.getFirst();contactId=requiredProviderText(contact,"id");
    }
    if(!publicId.equals(contact.path("reference_id").asText(""))
        ||!beneficiaryName.equalsIgnoreCase(contact.path("name").asText("").trim()))
      throw bad("The provider contact does not match the saved QuantaEdge reference and beneficiary name. No record was linked.");

    List<JsonNode> matchingFunds=new ArrayList<>();
    boolean complete=false;
    for(int skip=0;skip<=400;skip+=100) {
      JsonNode collection=providerRequest("GET","/fund_accounts?contact_id="+java.net.URLEncoder.encode(contactId,StandardCharsets.UTF_8)+"&account_type=bank_account&count=100&skip="+skip,null,null);
      JsonNode items=collection.path("items");
      if(!items.isArray())throw new IllegalStateException("RazorpayX fund-account lookup returned an invalid collection.");
      for(JsonNode item:items) {
        JsonNode bank=item.path("bank_account");
        if(contactId.equals(item.path("contact_id").asText(""))
            &&"bank_account".equals(item.path("account_type").asText(""))
            &&localIfsc.equalsIgnoreCase(bank.path("ifsc").asText("").trim())
            &&beneficiaryName.equalsIgnoreCase(bank.path("name").asText("").trim())
            &&accountNumber.equals(bank.path("account_number").asText("")))matchingFunds.add(item);
      }
      if(items.size()<100){complete=true;break;}
    }
    if(!complete)throw bad("The provider fund-account search exceeded the safe reconciliation window. No record was linked; manual review is required.");
    if(matchingFunds.size()!=1)
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id",beneficiaryId,"status","RECONCILIATION_REQUIRED",
        "message",matchingFunds.isEmpty()
          ?"No exact existing RazorpayX fund-account record matched the re-entered account number, holder name and IFSC. No duplicate was created. Verify the provider record and retry reconciliation only after the mismatch is resolved."
          :"Multiple RazorpayX fund-account records matched the supplied bank details. No record was linked; a platform owner must resolve the ambiguity."));
    String fundAccountId=requiredProviderText(matchingFunds.getFirst(),"id");
    int changed=jdbc.update("""
      update finance_payout_beneficiary set provider_contact_id=?,provider_fund_account_id=?,status='PENDING_APPROVAL',updated_at=now()
      where id=? and status in ('PROVISIONING','RECONCILIATION_REQUIRED')
        and (provider_contact_id is null or provider_contact_id=?)
        and (provider_fund_account_id is null or provider_fund_account_id=?)
      """,contactId,fundAccountId,beneficiaryId,contactId,fundAccountId);
    if(changed!=1)throw bad("Beneficiary setup changed during reconciliation. Refresh the record and review it again.");
    auditFinancial("PAYOUT_BENEFICIARY_PROVIDER_SETUP_RECONCILED",beneficiaryId,text(local,"beneficiary_type"),beneficiaryId(local),
      context.staffId(),Map.of("providerContactId",contactId,"providerFundAccountId",fundAccountId,
        "accountLast4",accountNumber.substring(accountNumber.length()-4),"ifsc",localIfsc,
        "matchedExistingProviderRecords",true));
    return ResponseEntity.ok(Map.of("id",beneficiaryId,"status","PENDING_APPROVAL",
      "message","The existing RazorpayX contact and fund account were matched exactly and linked. A different authorized staff member must still approve this beneficiary."));
  }

  @PostMapping("/payout-beneficiaries/{beneficiaryId}/approve")
  @org.springframework.transaction.annotation.Transactional
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
    Long replacesBeneficiaryId=nullableLong(row.get("replaces_beneficiary_id"));
    Map<String,Object> previous=Map.of();
    if(replacesBeneficiaryId!=null) {
      previous=one("select * from finance_payout_beneficiary where id=? for update",replacesBeneficiaryId);
      if(previous.isEmpty()||!"ACTIVE".equals(text(previous,"status"))
          ||!text(previous,"beneficiary_type").equals(text(row,"beneficiary_type"))
          ||!java.util.Objects.equals(nullableLong(previous.get("institution_id")),nullableLong(row.get("institution_id")))
          ||!java.util.Objects.equals(nullableLong(previous.get("legal_entity_id")),nullableLong(row.get("legal_entity_id"))))
        throw bad("The bank account being replaced is no longer the active account for this recipient. Refresh and review the beneficiary chain.");
      if(exists("""
        select exists(select 1 from finance_payout where beneficiary_id=?
          and status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED'))
        """,replacesBeneficiaryId))
        throw bad("An existing payout still depends on the previous bank account. Resolve or finish that payout batch before approving the replacement.");
      int retired=jdbc.update("update finance_payout_beneficiary set status='SUSPENDED',updated_at=now() where id=? and status='ACTIVE'",replacesBeneficiaryId);
      if(retired!=1)throw bad("The previous bank account changed during replacement approval. Refresh and review again.");
    }
    int changed=jdbc.update("""
      update finance_payout_beneficiary set status='ACTIVE',approved_by_staff_id=?,approved_at=now(),updated_at=now()
      where id=? and status='PENDING_APPROVAL'
      """,context.staffId(),beneficiaryId);
    if(changed!=1)throw bad("Beneficiary changed during approval. Refresh and retry.");
    if(replacesBeneficiaryId!=null)
      auditFinancial("PAYOUT_BENEFICIARY_REPLACED_ACCOUNT_SUSPENDED",replacesBeneficiaryId,text(previous,"beneficiary_type"),beneficiaryId(previous),context.staffId(),
        Map.of("replacementBeneficiaryId",beneficiaryId,"reason","Approved replacement after checking no in-flight payouts remain."));
    auditFinancial(replacesBeneficiaryId==null?"PAYOUT_BENEFICIARY_APPROVED":"PAYOUT_BENEFICIARY_REPLACEMENT_APPROVED",
        beneficiaryId,text(row,"beneficiary_type"),beneficiaryId(row),context.staffId(),
        Map.of("providerFundAccountId",row.get("provider_fund_account_id"),"replacesBeneficiaryId",replacesBeneficiaryId==null?0:replacesBeneficiaryId));
    return Map.of("id",beneficiaryId,"status","ACTIVE","message",replacesBeneficiaryId==null
        ?"Beneficiary is approved for payout batches."
        :"Replacement bank account approved. The previous bank account is now suspended; future payout batches use the new account.");
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
  public List<Map<String,Object>> feeQueue(@RequestParam(value="limit",defaultValue="50") int suppliedLimit,
      @RequestParam(value="offset",defaultValue="0") int suppliedOffset,
      @RequestParam(value="q",defaultValue="") String rawQuery,
      @RequestParam(value="from",defaultValue="") String rawFrom,
      @RequestParam(value="through",defaultValue="") String rawThrough,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    int limit=Math.max(1,Math.min(suppliedLimit,200)),offset=Math.max(0,Math.min(suppliedOffset,1_000_000));
    String query=rawQuery==null?"":rawQuery.trim(),from=rawFrom==null?"":rawFrom.trim(),through=rawThrough==null?"":rawThrough.trim();
    if(query.length()>100)throw bad("Fee-queue search can contain at most 100 characters.");
    LocalDate fromDate=null,throughDate=null;
    try { if(!from.isBlank())fromDate=LocalDate.parse(from); }
    catch(java.time.format.DateTimeParseException ex) { throw bad("Enter the verification start date as YYYY-MM-DD."); }
    try { if(!through.isBlank())throughDate=LocalDate.parse(through); }
    catch(java.time.format.DateTimeParseException ex) { throw bad("Enter the verification end date as YYYY-MM-DD."); }
    if(fromDate!=null&&throughDate!=null&&fromDate.isAfter(throughDate))throw bad("The start date must not be after the end date.");
    return jdbc.queryForList("""
      select o.id as order_id,o.course_id,coalesce(nullif(offer.title_override,''),course.display_name) as course_title,
        tenant.display_name as source_institution,o.provider_payment_id,o.captured_amount_paise,o.output_tax_paise,o.verified_at,o.currency
      from course_payment_order o join revenue_share_allocation a on a.course_payment_order_id=o.id
      join institution_marketplace_offer offer on offer.id=o.institution_offer_id
      join curriculum_program course on course.id=o.course_id join institution_tenant tenant on tenant.id=o.institution_id
      left join course_payment_order_fee f on f.course_payment_order_id=o.id
      where o.status='VERIFIED' and f.id is null
        and (cast(? as date) is null or o.verified_at::date>=cast(? as date))
        and (cast(? as date) is null or o.verified_at::date<=cast(? as date))
        and (?='' or cast(o.id as text) ilike '%'||?||'%'
          or coalesce(nullif(offer.title_override,''),course.display_name) ilike '%'||?||'%'
          or tenant.display_name ilike '%'||?||'%' or o.provider_payment_id ilike '%'||?||'%')
      group by o.id,o.course_id,offer.title_override,course.display_name,tenant.display_name,o.provider_payment_id,
        o.captured_amount_paise,o.output_tax_paise,o.verified_at,o.currency
      order by o.verified_at,o.id limit ? offset ?
      """,fromDate,fromDate,throughDate,throughDate,query,query,query,query,query,limit,offset);
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


  @GetMapping("/course-order-fee-adjustment-candidates")
  public List<Map<String,Object>> feeAdjustmentCandidates(
      @RequestParam(value="limit",defaultValue="50") int suppliedLimit,
      @RequestParam(value="offset",defaultValue="0") int suppliedOffset,
      @RequestParam(value="q",defaultValue="") String rawQuery,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    int limit=Math.max(1,Math.min(suppliedLimit,200)),offset=Math.max(0,Math.min(suppliedOffset,1_000_000));
    String query=rawQuery==null?"":rawQuery.trim();
    if(query.length()>100)throw bad("Fee-adjustment search can contain at most 100 characters.");
    return jdbc.queryForList("""
      select o.id as order_id,o.currency,o.provider_payment_id,
        coalesce(nullif(offer.title_override,''),course.display_name) as course_title,tenant.display_name as source_institution,
        f.processing_fee_paise as recorded_processing_fee_paise,f.fee_tax_paise as recorded_fee_tax_paise,
        coalesce(approved.requested_processing_fee_paise,f.processing_fee_paise) as effective_processing_fee_paise,
        coalesce(approved.requested_fee_tax_paise,f.fee_tax_paise) as effective_fee_tax_paise,
        coalesce(approved.settlement_reference,f.settlement_reference) as effective_reference,
        coalesce(approved.source_type,f.source_type) as effective_source_type,
        pending.id as pending_adjustment_id,
        (pending.id is null and not exists(
          select 1 from finance_payout_item i join revenue_share_allocation a on a.id=i.allocation_id
          join finance_payout p on p.id=i.payout_id
          where a.course_payment_order_id=o.id
            and p.status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED')
        )) as can_adjust
      from course_payment_order_fee f
      join course_payment_order o on o.id=f.course_payment_order_id and o.status='VERIFIED'
      join institution_tenant tenant on tenant.id=o.institution_id
      left join institution_marketplace_offer offer on offer.id=o.institution_offer_id
      left join curriculum_program course on course.id=o.course_id
      left join lateral (
        select q.requested_processing_fee_paise,q.requested_fee_tax_paise,q.settlement_reference,q.source_type
        from course_payment_order_fee_adjustment q
        join course_payment_order_fee_adjustment_review r on r.adjustment_id=q.id and r.decision='APPROVED'
        where q.course_payment_order_id=o.id order by q.id desc limit 1
      ) approved on true
      left join lateral (
        select q.id from course_payment_order_fee_adjustment q
        where q.course_payment_order_id=o.id
          and not exists(select 1 from course_payment_order_fee_adjustment_review r where r.adjustment_id=q.id)
        order by q.id desc limit 1
      ) pending on true
      where o.currency='INR' and (?='' or cast(o.id as text) ilike '%'||?||'%'
        or coalesce(nullif(offer.title_override,''),course.display_name) ilike '%'||?||'%'
        or tenant.display_name ilike '%'||?||'%' or o.provider_payment_id ilike '%'||?||'%')
      order by o.id desc limit ? offset ?
      """,query,query,query,query,query,limit,offset);
  }

  @GetMapping("/course-order-fee-adjustments/pending")
  public List<Map<String,Object>> pendingFeeAdjustments(
      @RequestParam(value="limit",defaultValue="100") int suppliedLimit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);int limit=Math.max(1,Math.min(suppliedLimit,200));
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select q.id,q.course_payment_order_id as order_id,q.previous_processing_fee_paise,q.previous_fee_tax_paise,
        q.requested_processing_fee_paise,q.requested_fee_tax_paise,q.settlement_reference,q.source_type,q.reason,
        q.requested_by_staff_id,maker.display_name as requested_by_name,q.created_at,
        coalesce(nullif(offer.title_override,''),course.display_name) as course_title,o.provider_payment_id,o.currency
      from course_payment_order_fee_adjustment q
      join course_payment_order o on o.id=q.course_payment_order_id
      left join institution_marketplace_offer offer on offer.id=o.institution_offer_id
      left join curriculum_program course on course.id=o.course_id
      left join staff_account maker on maker.id=q.requested_by_staff_id
      where not exists(select 1 from course_payment_order_fee_adjustment_review r where r.adjustment_id=q.id)
      order by q.created_at,q.id limit ?
      """,limit);
    boolean mayReview=context.staffId()!=null&&hasPermission(context,"QE_FINANCE_PAYOUT_APPROVE");
    for(Map<String,Object> row:rows)row.put("canReview",mayReview&&number(row.get("requested_by_staff_id"))!=number(context.staffId()));
    return rows;
  }

  @PostMapping("/course-orders/{orderId}/fee-adjustments")
  @org.springframework.transaction.annotation.Transactional
  public ResponseEntity<Map<String,Object>> requestFeeAdjustment(@PathVariable long orderId,
      @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);
    long fee=requiredLong(body.get("processingFeePaise"),"processingFeePaise");
    long tax=requiredLong(body.get("feeTaxPaise"),"feeTaxPaise");
    if(fee<0||tax<0)throw bad("Corrected provider fee and fee tax cannot be negative.");
    try { Math.addExact(fee,tax); } catch(ArithmeticException ex) { throw bad("Corrected fee plus tax exceeds supported integer-paise precision."); }
    String reference=requiredText(body.get("settlementReference"),"settlement reference");
    String reason=requiredText(body.get("reason"),"adjustment reason");
    if(reference.length()<3||reference.length()>180)throw bad("Enter a valid provider settlement reference.");
    if(reason.length()<5||reason.length()>500)throw bad("Explain the reason for the correction in 5–500 characters.");
    String source=optionalText(body.get("sourceType"))==null?"MANUAL_RECONCILIATION":text(body,"sourceType").toUpperCase(Locale.ROOT);
    if(!List.of("SETTLEMENT_REPORT","PROVIDER_API","MANUAL_RECONCILIATION").contains(source))
      throw bad("Choose a supported correction evidence source.");
    String key=idempotencyKey==null?"":idempotencyKey.trim();
    if(!key.matches("[A-Za-z0-9_-]{10,180}"))throw bad("Idempotency-Key must contain 10–180 letters, digits, underscores or hyphens.");
    Map<String,Object> order=one("select id,status,currency from course_payment_order where id=? for update",orderId);
    if(order.isEmpty())throw notFound("Course payment order was not found.");
    if(!"VERIFIED".equals(text(order,"status")))throw bad("Only a verified captured sale can be corrected.");
    if(!"INR".equalsIgnoreCase(text(order,"currency")))throw bad("Fee correction is available for the INR settlement route only.");
    Map<String,Object> current=effectiveCourseOrderFee(orderId);
    if(current.isEmpty())throw bad("This order does not have an original confirmed fee record to correct.");
    Map<String,Object> duplicate=one("select * from course_payment_order_fee_adjustment where idempotency_key=?",key);
    if(!duplicate.isEmpty()) {
      boolean same=number(duplicate.get("course_payment_order_id"))==orderId
        &&number(duplicate.get("requested_processing_fee_paise"))==fee
        &&number(duplicate.get("requested_fee_tax_paise"))==tax
        &&reference.equals(text(duplicate,"settlement_reference"))
        &&source.equals(text(duplicate,"source_type"))&&reason.equals(text(duplicate,"reason"))
        &&number(duplicate.get("requested_by_staff_id"))==number(context.staffId());
      if(!same)throw bad("This idempotency key was already used for a different fee-adjustment request.");
      return ResponseEntity.ok(Map.of("id",number(duplicate.get("id")),"status",feeAdjustmentStatus(number(duplicate.get("id"))),
        "message","The original fee-adjustment request was found. No duplicate request was created."));
    }
    if(number(current.get("effective_processing_fee_paise"))==fee&&number(current.get("effective_fee_tax_paise"))==tax)
      throw bad("The corrected fee and tax are identical to the current approved values. No adjustment is needed.");
    if(exists("""
      select exists(select 1 from finance_payout_item i join revenue_share_allocation a on a.id=i.allocation_id
        join finance_payout p on p.id=i.payout_id where a.course_payment_order_id=?
        and p.status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED'))
      """,orderId))
      throw bad("A payout batch currently reserves this sale. Finish or safely resolve that batch before requesting a fee correction.");
    if(exists("select exists(select 1 from course_payment_order_fee_adjustment q where q.course_payment_order_id=? and not exists(select 1 from course_payment_order_fee_adjustment_review r where r.adjustment_id=q.id))",orderId))
      throw bad("A fee correction for this order is already awaiting independent review.");
    Long adjustmentId=jdbc.queryForObject("""
      insert into course_payment_order_fee_adjustment(
        course_payment_order_id,previous_processing_fee_paise,previous_fee_tax_paise,
        requested_processing_fee_paise,requested_fee_tax_paise,settlement_reference,source_type,reason,
        idempotency_key,requested_by_staff_id
      ) values(?,?,?,?,?,?,?,?,?,?) returning id
      """,Long.class,orderId,number(current.get("effective_processing_fee_paise")),number(current.get("effective_fee_tax_paise")),
        fee,tax,reference,source,reason,key,context.staffId());
    if(adjustmentId==null)throw new IllegalStateException("Fee correction request could not be recorded.");
    auditCourseOrderFeeAdjustment(orderId,adjustmentId,context.staffId(),"COURSE_ORDER_FEE_ADJUSTMENT_REQUESTED",
      Map.of("previousProcessingFeePaise",number(current.get("effective_processing_fee_paise")),
        "previousFeeTaxPaise",number(current.get("effective_fee_tax_paise")),"requestedProcessingFeePaise",fee,
        "requestedFeeTaxPaise",tax,"settlementReference",reference,"sourceType",source,"reason",reason));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",adjustmentId,"status","PENDING_APPROVAL",
      "previousProcessingFeePaise",number(current.get("effective_processing_fee_paise")),
      "previousFeeTaxPaise",number(current.get("effective_fee_tax_paise")),
      "requestedProcessingFeePaise",fee,"requestedFeeTaxPaise",tax,
      "message","Correction request recorded immutably. A different authorized staff member must review it before the allocation ledger changes."));
  }

  @PostMapping("/course-order-fee-adjustments/{adjustmentId}/review")
  @org.springframework.transaction.annotation.Transactional
  public ResponseEntity<Map<String,Object>> reviewFeeAdjustment(@PathVariable long adjustmentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);
    String decision=text(body,"decision").toUpperCase(Locale.ROOT);
    if(!List.of("APPROVED","REJECTED").contains(decision))throw bad("Choose approve or reject for this fee correction.");
    String reason=requiredText(body.get("reason"),"review rationale");
    if(reason.length()<5||reason.length()>500)throw bad("Review rationale must contain 5–500 characters.");
    Map<String,Object> request=one("select * from course_payment_order_fee_adjustment where id=?",adjustmentId);
    if(request.isEmpty())throw notFound("Fee-adjustment request was not found.");
    if(number(request.get("requested_by_staff_id"))==number(context.staffId()))
      throw bad("The person who requested this fee correction cannot review it.");
    long orderId=number(request.get("course_payment_order_id"));
    jdbc.queryForList("select id from course_payment_order where id=? for update",orderId);
    if(!one("select id from course_payment_order_fee_adjustment_review where adjustment_id=?",adjustmentId).isEmpty())
      throw bad("This fee correction already has a final review decision.");
    Map<String,Object> current=effectiveCourseOrderFee(orderId);
    if(current.isEmpty()
        ||number(current.get("effective_processing_fee_paise"))!=number(request.get("previous_processing_fee_paise"))
        ||number(current.get("effective_fee_tax_paise"))!=number(request.get("previous_fee_tax_paise")))
      throw bad("The fee values changed after this request was submitted. Do not apply a stale correction; refresh and submit a new request.");
    if(exists("""
      select exists(select 1 from finance_payout_item i join revenue_share_allocation a on a.id=i.allocation_id
        join finance_payout p on p.id=i.payout_id where a.course_payment_order_id=?
        and p.status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED'))
      """,orderId))
      throw bad("A payout batch now reserves this sale. Resolve the batch before reviewing the fee correction.");
    if("APPROVED".equals(decision)) {
      Map<String,Object> ledgerResult=financialService.applyApprovedProviderFeeAdjustment(adjustmentId,context.staffId());
      jdbc.update("""
        insert into course_payment_order_fee_adjustment_review(adjustment_id,decision,reason,reviewed_by_staff_id)
        values(?,'APPROVED',?,?)
        """,adjustmentId,reason,context.staffId());
      auditCourseOrderFeeAdjustment(orderId,adjustmentId,context.staffId(),"COURSE_ORDER_FEE_ADJUSTMENT_APPROVED",
        Map.of("reviewReason",reason,"ledgerImpact",ledgerResult));
      return ResponseEntity.ok(Map.of("id",adjustmentId,"status","APPROVED","ledgerImpact",ledgerResult,
        "message","The reviewed correction was appended to the allocation ledger. The original provider fee record remains unchanged."));
    }
    jdbc.update("""
      insert into course_payment_order_fee_adjustment_review(adjustment_id,decision,reason,reviewed_by_staff_id)
      values(?,'REJECTED',?,?)
      """,adjustmentId,reason,context.staffId());
    auditCourseOrderFeeAdjustment(orderId,adjustmentId,context.staffId(),"COURSE_ORDER_FEE_ADJUSTMENT_REJECTED",
      Map.of("reviewReason",reason));
    return ResponseEntity.ok(Map.of("id",adjustmentId,"status","REJECTED",
      "message","The correction request was rejected and retained in the immutable review history. No ledger entries changed."));
  }

  private Map<String,Object> effectiveCourseOrderFee(long orderId) {
    return one("""
      select f.processing_fee_paise as recorded_processing_fee_paise,f.fee_tax_paise as recorded_fee_tax_paise,
        coalesce(approved.requested_processing_fee_paise,f.processing_fee_paise) as effective_processing_fee_paise,
        coalesce(approved.requested_fee_tax_paise,f.fee_tax_paise) as effective_fee_tax_paise
      from course_payment_order_fee f
      left join lateral (
        select q.requested_processing_fee_paise,q.requested_fee_tax_paise
        from course_payment_order_fee_adjustment q
        join course_payment_order_fee_adjustment_review r on r.adjustment_id=q.id and r.decision='APPROVED'
        where q.course_payment_order_id=f.course_payment_order_id order by q.id desc limit 1
      ) approved on true
      where f.course_payment_order_id=?
      """,orderId);
  }

  private String feeAdjustmentStatus(long adjustmentId) {
    Map<String,Object> review=one("select decision from course_payment_order_fee_adjustment_review where adjustment_id=?",adjustmentId);
    return review.isEmpty()?"PENDING_APPROVAL":text(review,"decision");
  }

  private void auditCourseOrderFeeAdjustment(long orderId,long adjustmentId,long actorStaffId,String action,Map<String,Object> details) {
    jdbc.update("""
      insert into billing_financial_audit_log(legal_entity_id,institution_id,actor_staff_id,action,target_type,target_id,details)
      select d.legal_entity_id,o.institution_id,?,?, 'COURSE_ORDER_FEE_ADJUSTMENT',?,?::jsonb
      from course_payment_order o left join fiscal_document d on d.id=o.fiscal_document_id where o.id=?
      """,actorStaffId,action,Long.toString(adjustmentId),json(details),orderId);
  }

  @GetMapping("/payout-tax-overview")
  public Map<String,Object> payoutTaxOverview(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long verifiedSales=amount("""
      select coalesce(sum(captured_amount_paise),0) from course_payment_order where status='VERIFIED' and upper(currency)='INR'
      """);
    long verifiedOrders=count("select count(*) from course_payment_order where status='VERIFIED' and upper(currency)='INR'");
    long recordedOutputTax=amount("""
      select coalesce(sum(output_tax_paise),0) from course_payment_order where status='VERIFIED' and upper(currency)='INR'
      """);
    long processedCreditNoteTax=amount("""
      select coalesce(sum(d.tax_value_paise),0)
      from fiscal_document d
      join course_payment_refund r on r.credit_note_document_id=d.id
      where d.document_type='CREDIT_NOTE' and d.source_type='COURSE_ORDER' and r.status='PROCESSED' and upper(d.currency)='INR'
      """);
    long recordedProcessorFeeTax=amount("select coalesce(sum(fee_tax_paise),0) from course_payment_order_fee where upper(currency)='INR'");
    long netOutputTaxBeforeCredits=Math.subtractExact(recordedOutputTax,processedCreditNoteTax);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("currency","INR");
    result.put("verifiedSalesPaise",verifiedSales);
    result.put("nonInrCurrencies",jdbc.queryForList("""
      select distinct upper(currency) as currency from course_payment_order
      where status='VERIFIED' and upper(currency)<>'INR' order by upper(currency)
      """,String.class));
    result.put("verifiedOrderCount",verifiedOrders);
    result.put("recordedOutputTaxPaise",recordedOutputTax);
    result.put("processedCreditNoteTaxPaise",processedCreditNoteTax);
    result.put("netOutputTaxBeforeInputCreditsPaise",netOutputTaxBeforeCredits);
    result.put("recordedProcessorFeeTaxPaise",recordedProcessorFeeTax);
    result.put("approvedTaxProfiles",count("select count(*) from course_tax_profile where status='APPROVED'"));
    result.put("pendingTaxProfiles",count("select count(*) from course_tax_profile where status='PENDING_APPROVAL'"));
    // These are intentionally not reported as zero: no recipient-level TCS/TDS liability
    // ledger is implemented/approved, so no statutory withholding total can be asserted.
    result.put("gstTcsLedgerStatus","NOT_REPRESENTED");
    result.put("incomeTaxTdsLedgerStatus","NOT_REPRESENTED");
    result.put("finalGovernmentTaxPayableStatus","REVIEW_REQUIRED");
    result.put("finalTaxAmountIncludesInputCredits",false);
    return result;
  }

  @GetMapping("/payout-balances")
  public Map<String,Object> payoutBalances(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> settings=one("select refund_reserve_basis_points,payout_minimum_paise from billing_finance_payout_settings where id=1");
    int reserveBps=settings.get("refund_reserve_basis_points")==null?0:Math.toIntExact(number(settings.get("refund_reserve_basis_points")));
    long minimumPayoutPaise=settings.get("payout_minimum_paise")==null?0L:number(settings.get("payout_minimum_paise"));
    boolean providerReady=providerConfigured()&&!webhookSecret.isBlank();
    boolean legalArrangementReady=payoutReadyFromDatabase();
    List<Map<String,Object>> rows=jdbc.queryForList("""
      with recipients as (
        select 'INSTITUTION'::varchar as recipient_type,t.id as institution_id,
          null::bigint as legal_entity_id,t.display_name as recipient_name,t.status as recipient_status
        from institution_tenant t where t.status='ACTIVE'
        union
        select 'COMPANY'::varchar as recipient_type,null::bigint as institution_id,
          le.id as legal_entity_id,le.legal_name as recipient_name,le.status as recipient_status
        from billing_legal_entity le
        where le.status='ACTIVE' and le.entity_kind in ('PLATFORM','COMPANY')
        union
        -- Keep historic liabilities visible even after a recipient is suspended or deactivated.
        select a.recipient_type,a.institution_id,a.legal_entity_id,
          coalesce(t.display_name,le.legal_name,'Unresolved recipient') as recipient_name,
          coalesce(t.status,le.status,'UNKNOWN') as recipient_status
        from revenue_share_allocation a
        left join institution_tenant t on t.id=a.institution_id
        left join billing_legal_entity le on le.id=a.legal_entity_id
        where a.recipient_type in ('INSTITUTION','COMPANY')
      ), ledger as (
        select a.id as allocation_id,a.course_payment_order_id,a.recipient_type,a.institution_id,a.legal_entity_id,a.currency,
          coalesce(sum(e.amount_paise) filter(where e.event_type='EARNED'),0) as earned_paise,
          coalesce(sum(e.amount_paise) filter(where e.event_type='REFUNDED'),0) as refunded_paise,
          coalesce(sum(e.amount_paise) filter(where e.event_type='PROCESSING_FEE_DEDUCTED'),0)
            -coalesce(sum(e.amount_paise) filter(where e.event_type='PROCESSING_FEE_CREDITED'),0) as charges_paise,
          coalesce(sum(e.amount_paise) filter(where e.event_type='PAID'),0) as paid_paise,
          coalesce(sum(e.amount_paise) filter(where e.event_type='REVERSED'),0) as reversed_paise
        from revenue_share_allocation a
        join course_payment_order o on o.id=a.course_payment_order_id and o.status='VERIFIED'
        left join revenue_share_allocation_event e on e.allocation_id=a.id
        group by a.id,a.course_payment_order_id,a.recipient_type,a.institution_id,a.legal_entity_id,a.currency
      ), reservations as (
        select i.allocation_id,coalesce(sum(i.amount_paise),0) as reserved_paise
        from finance_payout_item i join finance_payout p on p.id=i.payout_id
        where p.status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','PENDING','QUEUED','PROCESSING','RECONCILIATION_REQUIRED')
        group by i.allocation_id
      ), balance_by_currency as (
        select l.recipient_type,l.institution_id,l.legal_entity_id,l.currency,
          coalesce(sum(l.earned_paise),0) as earned_paise,
          coalesce(sum(l.refunded_paise),0) as refunded_paise,
          coalesce(sum(l.charges_paise),0) as charges_paise,
          coalesce(sum(l.paid_paise),0) as paid_paise,
          coalesce(sum(l.reversed_paise),0) as reversed_paise,
          coalesce(sum(r.reserved_paise),0) as reserved_paise,
          count(distinct l.course_payment_order_id) filter(where l.earned_paise>0) as source_sale_count,
          count(distinct l.course_payment_order_id) filter(where l.earned_paise>0 and f.id is null) as unreconciled_fee_order_count
        from ledger l
        left join reservations r on r.allocation_id=l.allocation_id
        left join course_payment_order_fee f on f.course_payment_order_id=l.course_payment_order_id
        group by l.recipient_type,l.institution_id,l.legal_entity_id,l.currency
      )
      select r.recipient_type,r.institution_id,r.legal_entity_id,
        coalesce(fin.currency,'INR') as currency,r.recipient_name,r.recipient_status,
        coalesce(b.status,'MISSING') as beneficiary_status,b.bank_ifsc,b.bank_account_last4,
        coalesce(fin.earned_paise,0) as earned_paise,
        coalesce(fin.refunded_paise,0) as refunded_paise,
        coalesce(fin.charges_paise,0) as charges_paise,
        coalesce(fin.paid_paise,0) as paid_paise,
        coalesce(fin.reversed_paise,0) as reversed_paise,
        coalesce(fin.reserved_paise,0) as reserved_paise,
        coalesce(fin.source_sale_count,0) as source_sale_count,
        coalesce(fin.unreconciled_fee_order_count,0) as unreconciled_fee_order_count
      from recipients r
      left join balance_by_currency fin on fin.recipient_type=r.recipient_type
        and ((r.recipient_type='INSTITUTION' and fin.institution_id=r.institution_id)
          or (r.recipient_type='COMPANY' and fin.legal_entity_id=r.legal_entity_id))
      left join lateral (
        select fpb.status,fpb.bank_ifsc,fpb.bank_account_last4
        from finance_payout_beneficiary fpb
        where ((r.recipient_type='INSTITUTION' and fpb.beneficiary_type='INSTITUTION' and fpb.institution_id=r.institution_id)
          or (r.recipient_type='COMPANY' and fpb.beneficiary_type='COMPANY' and fpb.legal_entity_id=r.legal_entity_id))
        order by case fpb.status
          when 'ACTIVE' then 0 when 'PENDING_APPROVAL' then 1 when 'PROVISIONING' then 2
          when 'RECONCILIATION_REQUIRED' then 3 when 'SUSPENDED' then 4 else 5 end,
          fpb.created_at desc,fpb.id desc
        limit 1
      ) b on true
      order by lower(r.recipient_name),r.recipient_type,coalesce(fin.currency,'INR')
      """);
    long totalEarned=0L,totalRefunded=0L,totalCharges=0L,totalPaid=0L,totalReversed=0L,totalReserved=0L,totalPayable=0L,totalRecovery=0L,totalReserve=0L;
    Set<String> unsupportedCurrencies=new java.util.TreeSet<>();
    for(Map<String,Object> row:rows) {
      long earned=number(row.get("earned_paise"));
      long refunds=number(row.get("refunded_paise"));
      long charges=number(row.get("charges_paise"));
      long paid=number(row.get("paid_paise"));
      long reversed=number(row.get("reversed_paise"));
      long reserved=number(row.get("reserved_paise"));
      long netEarned=Math.subtractExact(Math.subtractExact(earned,refunds),charges);
      long netPaid=Math.subtractExact(paid,reversed);
      long refundReserve=percentageOf(Math.max(0L,netEarned),reserveBps);
      long payable=Math.max(0L,Math.subtractExact(Math.subtractExact(Math.subtractExact(netEarned,netPaid),reserved),refundReserve));
      // A pre-payout refund followed by later fee reconciliation can make net earned negative.
      // Do not call an unpaid accounting deficit a recovery; only recover money already transferred.
      long recovery=Math.max(0L,Math.subtractExact(netPaid,Math.max(0L,netEarned)));
      boolean payoutSupported="INR".equalsIgnoreCase(string(row.get("currency")));
      row.put("payout_supported",payoutSupported);
      row.put("payout_support_reason",payoutSupported?"":"RazorpayX payout batches are INR-only; foreign-exchange conversion is not configured.");
      row.put("net_earned_paise",netEarned);
      row.put("net_paid_paise",netPaid);
      row.put("refund_reserve_paise",refundReserve);
      row.put("payable_paise",payable);
      row.put("recovery_due_paise",recovery);
      long sourceSales=number(row.get("source_sale_count"));
      long unreconciledFeeOrders=number(row.get("unreconciled_fee_order_count"));
      String readinessStatus;
      String readinessReason;
      if(!payoutSupported) {
        readinessStatus="BLOCKED";
        readinessReason="This currency has no configured payout route.";
      } else if(recovery>0) {
        readinessStatus="BLOCKED";
        readinessReason="A recovery balance is due; reconcile the recovery before any further transfer.";
      } else if(payable<=0) {
        readinessStatus="NO_PAYABLE_BALANCE";
        readinessReason="No positive payable balance remains after refunds, payments, reservations and holdback.";
      } else if(!"ACTIVE".equals(string(row.get("recipient_status")))) {
        readinessStatus="BLOCKED";
        readinessReason="The recipient entity is not active. Its historical balance remains visible, but transfers are blocked until its status is reviewed.";
      } else if(!"ACTIVE".equals(string(row.get("beneficiary_status")))) {
        readinessStatus="BLOCKED";
        readinessReason="The recipient does not have an approved active bank beneficiary.";
      } else if(payable<minimumPayoutPaise) {
        readinessStatus="BELOW_MINIMUM";
        readinessReason="Below the configured minimum payout of "+minimumPayoutPaise+" paise.";
      } else if(unreconciledFeeOrders>0) {
        readinessStatus="BLOCKED";
        readinessReason=unreconciledFeeOrders+" source sale(s) still need actual collection-fee reconciliation.";
      } else if(!providerReady) {
        readinessStatus="BLOCKED";
        readinessReason="RazorpayX credentials, source-account identifier or payout webhook are not fully configured.";
      } else if(!legalArrangementReady) {
        readinessStatus="BLOCKED";
        readinessReason="The legal seller, collection entity and RazorpayX funding-owner arrangement is not verified.";
      } else {
        readinessStatus="READY_FOR_BATCH_REVIEW";
        readinessReason="Existing payout controls pass for batch preparation, but this status does not assess GST TCS or income-tax TDS. Confirm sale/recipient tax treatment before approving a live transfer; server-side batch checks and independent approval still apply.";
      }
      row.put("source_sale_count",sourceSales);
      row.put("unreconciled_fee_order_count",unreconciledFeeOrders);
      row.put("payout_readiness_status",readinessStatus);
      row.put("payout_readiness_reason",readinessReason);
      if(!payoutSupported) {
        // These values are retained only for reconciliation. The UI must not render non-INR
        // minor units as rupees or blend them into INR summary totals.
        if(Math.subtractExact(Math.subtractExact(netEarned,netPaid),reserved)>0)
          unsupportedCurrencies.add(string(row.get("currency")).toUpperCase(Locale.ROOT));
        continue;
      }
      totalEarned=Math.addExact(totalEarned,netEarned);
      totalRefunded=Math.addExact(totalRefunded,refunds);
      totalCharges=Math.addExact(totalCharges,charges);
      totalPaid=Math.addExact(totalPaid,netPaid);
      totalReversed=Math.addExact(totalReversed,reversed);
      totalReserved=Math.addExact(totalReserved,reserved);
      totalPayable=Math.addExact(totalPayable,payable);
      totalRecovery=Math.addExact(totalRecovery,recovery);
      totalReserve=Math.addExact(totalReserve,refundReserve);
    }
    Map<String,Object> totals=new LinkedHashMap<>();
    totals.put("netEarnedPaise",totalEarned);
    totals.put("refundReversalsPaise",totalRefunded);
    totals.put("confirmedChargesPaise",totalCharges);
    totals.put("netPaidPaise",totalPaid);
    totals.put("payoutReversalsPaise",totalReversed);
    totals.put("reservedPaise",totalReserved);
    totals.put("refundReservePaise",totalReserve);
    totals.put("payablePaise",totalPayable);
    totals.put("recoveryDuePaise",totalRecovery);
    totals.put("refundReserveBasisPoints",reserveBps);
    return Map.of("totals",totals,"beneficiaries",rows,"unsupportedCurrencies",List.copyOf(unsupportedCurrencies));
  }


  @GetMapping("/payout-balances/ledger-history")
  public List<Map<String,Object>> payoutBalanceLedgerHistory(
      @RequestParam String recipientType,@RequestParam long recipientId,
      @RequestParam(value="currency",defaultValue="INR") String rawCurrency,
      @RequestParam(value="limit",defaultValue="50") int suppliedLimit,
      @RequestParam(value="offset",defaultValue="0") int suppliedOffset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    String type=recipientType==null?"":recipientType.trim().toUpperCase(Locale.ROOT);
    String currency=rawCurrency==null?"":rawCurrency.trim().toUpperCase(Locale.ROOT);
    if(!List.of("INSTITUTION","COMPANY").contains(type))throw bad("Choose a valid finance recipient type.");
    if(recipientId<=0)throw bad("Choose a valid finance recipient.");
    if(!currency.matches("^[A-Z]{3}$"))throw bad("Choose a valid three-letter currency code.");
    int limit=Math.max(1,Math.min(suppliedLimit,100)),offset=Math.max(0,Math.min(suppliedOffset,1_000_000));
    return jdbc.queryForList("""
      select e.id,e.event_type,e.amount_paise,e.currency,e.payment_reference,e.created_at,
        a.id as allocation_id,a.course_payment_order_id as order_id,o.provider_payment_id,
        coalesce(nullif(offer.title_override,''),course.display_name) as course_title,
        marketplace_t.id as marketplace_institution_id,marketplace_t.display_name as marketplace_institution_name,
        source_release.source_institution_id,source_t.display_name as source_institution_name,
        p.id as payout_id,p.public_id as payout_public_id,p.status as payout_status,p.transfer_reference,
        b.id as payout_batch_id,b.period_start as payout_period_start,
        e.details->>'feeAdjustmentId' as fee_adjustment_id,
        coalesce(e.details->>'settlementReference',e.details->>'reference') as settlement_reference,
        e.details->>'reason' as reason
      from revenue_share_allocation a
      join revenue_share_allocation_event e on e.allocation_id=a.id
      join course_payment_order o on o.id=a.course_payment_order_id
      left join institution_marketplace_offer offer on offer.id=o.institution_offer_id and offer.institution_id=o.institution_id
      left join institution_course_release source_release
        on source_release.id=offer.course_release_id and source_release.program_id=o.course_id
      left join institution_tenant source_t on source_t.id=source_release.source_institution_id
      left join institution_tenant marketplace_t on marketplace_t.id=o.institution_id
      left join curriculum_program course on course.id=o.course_id
      left join finance_payout p on p.id::text=e.details->>'payoutId'
      left join finance_payout_batch b on b.id=p.batch_id
      where a.recipient_type=? and a.currency=?
        and ((?='INSTITUTION' and a.institution_id=?) or (?='COMPANY' and a.legal_entity_id=?))
      order by e.created_at desc,e.id desc limit ? offset ?
      """,type,currency,type,recipientId,type,recipientId,limit,offset);
  }

  @PostMapping("/payouts/{payoutId}/fees")
  @org.springframework.transaction.annotation.Transactional
  public Map<String,Object> confirmPayoutFees(@PathVariable long payoutId,@RequestBody Map<String,Object> body,
      @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireManage(context);
    long fee=requiredLong(body.get("processingFeePaise"),"processingFeePaise");
    long tax=requiredLong(body.get("feeTaxPaise"),"feeTaxPaise");
    if(fee<0||tax<0)throw bad("Payout fee and fee tax cannot be negative.");
    try { Math.addExact(fee,tax); }
    catch(ArithmeticException overflow) { throw bad("Payout fee plus tax exceeds supported integer-paise precision."); }
    String reference=requiredText(body.get("settlementReference"),"settlement reference");
    if(reference.length()<3||reference.length()>180)throw bad("Enter a valid payout-fee statement reference.");
    String source=optionalText(body.get("sourceType"))==null?"MANUAL_RECONCILIATION":text(body,"sourceType").toUpperCase(Locale.ROOT);
    if(!List.of("SETTLEMENT_REPORT","MANUAL_RECONCILIATION").contains(source))
      throw bad("Choose an actual settlement report or manual statement reconciliation as the evidence source.");
    String key=idempotencyKey==null?"":idempotencyKey.trim();
    if(!key.matches("[A-Za-z0-9_-]{10,180}"))throw bad("Idempotency-Key must contain 10–180 letters, digits, underscores or hyphens.");
    Map<String,Object> row=one("""
      select p.id,p.batch_id,p.status,p.provider_fee_paise,p.provider_fee_tax_paise,p.provider_fee_reference,
        p.provider_fee_source_type,b.provider_fee_reconciled_at
      from finance_payout p join finance_payout_batch b on b.id=p.batch_id where p.id=?
      """,payoutId);
    if(row.isEmpty())throw notFound("Payout not found.");
    if(!List.of("PROCESSED","FAILED","REJECTED","REVERSED").contains(string(row.get("status"))))
      throw bad("Payout fees may be confirmed only after the provider payout reaches a final state.");
    if(row.get("provider_fee_reconciled_at")!=null)throw bad("This batch's payout fees are already reconciled and cannot be overwritten.");
    if(row.get("provider_fee_paise")!=null||row.get("provider_fee_tax_paise")!=null) {
      if(number(row.get("provider_fee_paise"))==fee&&number(row.get("provider_fee_tax_paise"))==tax
          &&reference.equals(string(row.get("provider_fee_reference")))
          &&source.equals(string(row.get("provider_fee_source_type")))) {
        // Complete the batch roll-up as well when a prior request succeeded but its response was lost.
        reconcileBatchProviderFees(number(row.get("batch_id")));
        return payoutSummary(payoutId);
      }
      throw bad("This payout already has provider-confirmed fee values. Use a reviewed correction workflow rather than overwriting its financial facts.");
    }
    int changed=jdbc.update("""
      update finance_payout set provider_fee_paise=?,provider_fee_tax_paise=?,provider_fee_reference=?,
        provider_fee_source_type=?,provider_fee_confirmed_by_staff_id=?,provider_fee_confirmed_at=now(),updated_at=now()
      where id=? and provider_fee_paise is null and provider_fee_tax_paise is null
      """,fee,tax,reference,source,context.staffId(),payoutId);
    if(changed!=1)throw bad("Payout fee record changed while saving. Refresh and retry.");
    payoutEvent(payoutId,"FEES_RECONCILED","course-payout:"+payoutId+":fees:"+key,context.staffId(),null,
        Map.of("processingFeePaise",fee,"feeTaxPaise",tax,"reference",reference,"sourceType",source));
    long batchId=number(row.get("batch_id"));
    reconcileBatchProviderFees(batchId);
    return payoutSummary(payoutId);
  }

  @GetMapping("/payout-batches")
  public List<Map<String,Object>> listBatches(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select b.id,b.public_id,b.period_start,b.period_end,b.currency,b.status,b.beneficiary_count,b.total_amount_paise,
        b.provider_fee_total_paise,b.provider_fee_tax_total_paise,b.platform_borne_overage_paise,b.provider_fee_reconciled_at,
        b.created_by_staff_id,maker.display_name as created_by_name,b.approved_by_staff_id,checker.display_name as approved_by_name,
        b.approved_at,b.created_at,b.updated_at,
        (select count(*) from finance_payout p where p.batch_id=b.id and p.status='PROCESSED') as paid_count,
        (select count(*) from finance_payout p where p.batch_id=b.id and p.status in ('FAILED','REJECTED','REVERSED')) as exception_count
      from finance_payout_batch b
      left join staff_account maker on maker.id=b.created_by_staff_id
      left join staff_account checker on checker.id=b.approved_by_staff_id
      order by b.created_at desc,b.id desc limit 100
      """);
    boolean canReview=context.staffId()!=null&&hasPermission(context,"QE_FINANCE_PAYOUT_APPROVE");
    for(Map<String,Object> row:rows)row.put("canApprove",canReview&&number(row.get("created_by_staff_id"))!=number(context.staffId()));
    return rows;
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
    batch.put("canApprove",context.staffId()!=null&&hasPermission(context,"QE_FINANCE_PAYOUT_APPROVE")
        &&number(batch.get("created_by_staff_id"))!=number(context.staffId()));
    batch.put("paid_count",count("select count(*) from finance_payout where batch_id=? and status='PROCESSED'",batchId));
    batch.put("exception_count",count("select count(*) from finance_payout where batch_id=? and status in ('FAILED','REJECTED','REVERSED')",batchId));
    batch.put("payouts",jdbc.queryForList("""
      select p.id,p.public_id,p.beneficiary_id,p.amount_paise,p.currency,p.status,p.provider_status,p.provider_payout_id,
        p.provider_fee_paise,p.provider_fee_tax_paise,p.provider_fee_reference,p.provider_fee_source_type,p.provider_fee_confirmed_at,
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

    List<Map<String,Object>> pendingCorrections=jdbc.queryForList("""
      select distinct q.id,q.course_payment_order_id as order_id
      from course_payment_order_fee_adjustment q
      join course_payment_order o on o.id=q.course_payment_order_id and o.status='VERIFIED'
      join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      where e.created_at < (?::date + interval '1 day')
        and not exists(select 1 from course_payment_order_fee_adjustment_review r where r.adjustment_id=q.id)
      order by q.id limit 25
      """,end);
    if(!pendingCorrections.isEmpty())throw bad("Payouts are blocked because fee-correction requests affecting this period still need an independent decision: "
      +pendingCorrections.stream().map(r->"correction #"+number(r.get("id"))+" for order #"+number(r.get("order_id"))).toList()
      +". Review or reject these requests before preparing the month.");

    Map<String,Object> feeReconciliationBlocker=one("""
      select b.id from finance_payout_batch b
      where b.provider_fee_reconciled_at is null
        and exists(select 1 from finance_payout p where p.batch_id=b.id)
        and not exists(select 1 from finance_payout p where p.batch_id=b.id
          and p.status not in ('PROCESSED','FAILED','REJECTED','REVERSED'))
      order by b.period_start,b.id limit 1
      """);
    if(!feeReconciliationBlocker.isEmpty())
      throw bad("RazorpayX charges for completed payout batch #"+number(feeReconciliationBlocker.get("id"))+
        " are not yet reconciled. Confirm the actual provider fee and tax values before preparing another month.");

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
          coalesce(sum(amount_paise) filter(where event_type='PROCESSING_FEE_DEDUCTED'),0)
            -coalesce(sum(amount_paise) filter(where event_type='PROCESSING_FEE_CREDITED'),0) fee_paise,
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
      where a.currency='INR' and l.earned_paise>0 and l.first_earned_at < (?::date + interval '1 day')
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
      paidNet.merge(key,Math.subtractExact(number(row.get("paid_paise")),number(row.get("reversed_paise"))),Math::addExact);
      reservedNet.merge(key,number(row.get("reserved_paise")),Math::addExact);
    }
    int reserveBps=Math.toIntExact(number(settings.get("refund_reserve_basis_points")));
    long minimum=number(settings.get("payout_minimum_paise"));
    Map<String,Long> payoutAmounts=new LinkedHashMap<>();
    for(String key:byRecipient.keySet()) {
      long amount=Math.max(0L,Math.subtractExact(Math.subtractExact(
          Math.subtractExact(netEarned.get(key),percentageOf(Math.max(0L,netEarned.get(key)),reserveBps)),
          paidNet.getOrDefault(key,0L)),reservedNet.getOrDefault(key,0L)));
      Map<String,Object> beneficiary=beneficiaryByKey.get(key);
      if(amount<minimum)continue;
      if(beneficiary==null||number(beneficiary.get("beneficiary_id"))<=0)
        throw bad("A beneficiary has a payable balance but no approved bank account: "+key+". Set up and approve its payout profile.");
      long positiveBalance=0L;
      for(Map<String,Object> allocation:byRecipient.get(key)) {
        long allocationNetEarned=Math.subtractExact(Math.subtractExact(
            number(allocation.get("earned_paise")),number(allocation.get("refunded_paise"))),
            number(allocation.get("fee_paise")));
        long allocationNetPaid=Math.subtractExact(number(allocation.get("paid_paise")),number(allocation.get("reversed_paise")));
        long open=Math.subtractExact(Math.subtractExact(allocationNetEarned,allocationNetPaid),
            number(allocation.get("reserved_paise")));
        positiveBalance=Math.addExact(positiveBalance,Math.max(0L,open));
      }
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
        long netEarnedForAllocation=Math.subtractExact(Math.subtractExact(
            number(row.get("earned_paise")),number(row.get("refunded_paise"))),number(row.get("fee_paise")));
        long netPaidForAllocation=Math.subtractExact(number(row.get("paid_paise")),number(row.get("reversed_paise")));
        long open=Math.max(0L,Math.subtractExact(Math.subtractExact(netEarnedForAllocation,netPaidForAllocation),
            number(row.get("reserved_paise"))));
        long item=Math.min(open,remaining);if(item<=0)continue;
        jdbc.update("insert into finance_payout_item(payout_id,allocation_id,amount_paise) values(?,?,?)",
            payoutId,number(row.get("allocation_id")),item);remaining=Math.subtractExact(remaining,item);
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
      catch(Exception ex){markPayoutUnknown(id,context.staffId(),safePayoutFailure(ex));results.add(payoutSummary(id));}
    }
    refreshBatchStatus(batchId);
    return ResponseEntity.ok(Map.of("batchId",batchId,
        "status",text(one("select status from finance_payout_batch where id=?",batchId),"status"),
        "payouts",results,"message","Approval was recorded. Payout completion is shown only after RazorpayX confirms the transfer."));
  }

  @GetMapping("/payout-provider-events/unmatched")
  public List<Map<String,Object>> unmatchedProviderEvents(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    return jdbc.queryForList("""
      select e.provider_event_id,e.provider_payout_id,e.event_type,e.disposition,e.received_at,e.payload_sha256
      from finance_payout_provider_event e
      where e.disposition='UNMATCHED'
        and not exists(select 1 from finance_payout_event pe where pe.provider_event_id=e.provider_event_id)
      order by e.received_at desc,e.provider_event_id desc limit 100
      """);
  }

  @GetMapping("/payout-reconciliation-candidates")
  public List<Map<String,Object>> payoutReconciliationCandidates(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    return jdbc.queryForList("""
      select p.id,p.public_id,p.batch_id,p.amount_paise,p.currency,p.status,p.provider_payout_id,
        b.period_start,b.period_end,coalesce(t.display_name,le.legal_name,bf.beneficiary_name) as recipient_name,
        bf.bank_ifsc,bf.bank_account_last4
      from finance_payout p
      join finance_payout_batch b on b.id=p.batch_id
      join finance_payout_beneficiary bf on bf.id=p.beneficiary_id
      left join institution_tenant t on t.id=bf.institution_id
      left join billing_legal_entity le on le.id=bf.legal_entity_id
      where p.provider_payout_id is null and bf.status='ACTIVE'
        and p.status in ('PENDING_APPROVAL','APPROVED','SUBMITTING','RECONCILIATION_REQUIRED')
        and b.status in ('APPROVED','PROCESSING','PARTIAL')
      order by b.period_start desc,p.id desc limit 200
      """);
  }

  @PostMapping("/payout-provider-events/{providerEventId}/reconcile")
  public ResponseEntity<Map<String,Object>> reconcileUnmatchedProviderEvent(
      @PathVariable String providerEventId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);requirePayoutReady();
    if(providerEventId==null||!providerEventId.matches("[A-Za-z0-9._:-]{1,120}"))
      throw bad("Provider event ID is invalid.");
    Map<String,Object> event=one("""
      select provider_event_id,provider_payout_id,event_type,disposition
      from finance_payout_provider_event where provider_event_id=?
      """,providerEventId);
    if(event.isEmpty())throw notFound("Provider webhook event was not found.");
    if(!"UNMATCHED".equals(text(event,"disposition"))
        ||exists("select exists(select 1 from finance_payout_event where provider_event_id=?)",providerEventId))
      throw bad("This provider event has already been linked or requires review through its existing payout. Refresh the workspace.");
    long payoutId=requiredLong(body.get("payoutId"),"payoutId");
    if(payoutId<=0)throw bad("Choose a valid existing QuantaEdge payout.");
    String providerId=text(event,"provider_payout_id");
    if(!providerId.matches("[A-Za-z0-9_-]{4,80}"))throw bad("The unmatched webhook contains an invalid provider payout ID.");
    Map<String,Object> local=one("""
      select p.status,p.batch_id,p.provider_payout_id,p.amount_paise,p.currency,p.public_id,
        bf.provider_fund_account_id,b.status as batch_status,b.approved_by_staff_id as batch_approved_by_staff_id
      from finance_payout p join finance_payout_beneficiary bf on bf.id=p.beneficiary_id
      join finance_payout_batch b on b.id=p.batch_id where p.id=?
      """,payoutId);
    if(local.isEmpty())throw notFound("The selected QuantaEdge payout was not found.");
    if(!List.of("APPROVED","PROCESSING","PARTIAL").contains(text(local,"batch_status")))
      throw bad("Only a payout from an already-approved batch can be linked to an unmatched provider event.");
    if(!List.of("PENDING_APPROVAL","APPROVED","SUBMITTING","RECONCILIATION_REQUIRED").contains(text(local,"status")))
      throw bad("The selected payout is not eligible for provider-event reconciliation.");
    if(optionalText(local.get("provider_payout_id"))!=null&&!providerId.equals(string(local.get("provider_payout_id"))))
      throw bad("The selected payout already has a different provider payout ID.");
    if(exists("select exists(select 1 from finance_payout where provider_payout_id=? and id<>?)",providerId,payoutId))
      throw bad("This provider payout ID is already linked to another QuantaEdge payout.");
    JsonNode provider=providerRequest("GET","/payouts/"+providerId,null,null);
    String mismatch=providerDataMismatch(payoutId,provider,providerId,local,true);
    if(mismatch!=null)throw bad("The provider record does not match the selected payout. "+mismatch);
    int linked=jdbc.update("""
      update finance_payout set provider_payout_id=?,updated_at=now()
      where id=? and (provider_payout_id is null or provider_payout_id=?)
      """,providerId,payoutId,providerId);
    if(linked!=1)throw bad("Payout changed during reconciliation. Refresh the workspace and review the provider record again.");
    payoutEvent(payoutId,"RECONCILIATION_REQUIRED","course-payout:"+payoutId+":manual-link:"+providerEventId,
        context.staffId(),providerEventId,Map.of("providerEventId",providerEventId,"providerPayoutId",providerId,
          "manualAssociationAfterServerValidation",true,"providerAmountPaise",number(local.get("amount_paise")),
          "currency",string(local.get("currency"))));
    Map<String,Object> result=applyProviderStatus(payoutId,provider,context.staffId(),providerEventId);
    refreshBatchStatus(number(local.get("batch_id")));
    return ResponseEntity.ok(Map.of("payout",result,"message",
        "Provider event was linked after server-side amount, currency and beneficiary-account checks. Review the resulting payout status before considering it settled."));
  }

  @PostMapping("/payouts/{payoutId}/refresh")
  public ResponseEntity<Map<String,Object>> refreshPayout(@PathVariable long payoutId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);requireProviderConfiguration();
    Map<String,Object> payout=one("select * from finance_payout where id=?",payoutId);
    if(payout.isEmpty())throw notFound("Payout not found.");
    String providerId=optionalText(payout.get("provider_payout_id"));
    if(providerId==null){
      Map<String,Object> result=new LinkedHashMap<>(payoutSummary(payoutId));
      result.put("refreshResult","UNCONFIRMED");
      result.put("message","No RazorpayX payout ID is saved. The current status is not a fresh provider confirmation; reconcile the original provider request before retrying.");
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
    try {
      JsonNode provider=providerRequest("GET","/payouts/"+providerId,null,null);
      Map<String,Object> out=new LinkedHashMap<>(applyProviderStatus(payoutId,provider,context.staffId(),null));
      refreshBatchStatus(number(payout.get("batch_id")));
      out.put("refreshResult","REFRESHED");
      out.put("message","RazorpayX responded and the payout identity and status passed server-side checks.");
      out.put("refreshedAt",java.time.Instant.now().toString());
      return ResponseEntity.ok(out);
    } catch(Exception ex) {
      Map<String,Object> result=new LinkedHashMap<>(payoutSummary(payoutId));
      result.put("refreshResult","UNCONFIRMED");
      result.put("message","RazorpayX status could not be freshly confirmed. The saved status is shown only for reference; no new payment state was assumed. Retry status refresh or reconcile the provider record before taking another action.");
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
  }

  @PostMapping("/payouts/{payoutId}/retry-submission")
  public ResponseEntity<Map<String,Object>> retryPayout(@PathVariable long payoutId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireApprover(context);requirePayoutReady();
    Map<String,Object> row=one("""
      select p.*,b.status as batch_status,b.approved_by_staff_id as batch_approved_by_staff_id
      from finance_payout p join finance_payout_batch b on b.id=p.batch_id where p.id=?
      """,payoutId);
    if(row.isEmpty())throw notFound("Payout not found.");
    String status=text(row,"status");
    if(!List.of("RECONCILIATION_REQUIRED","SUBMITTING","APPROVED","PENDING_APPROVAL").contains(status))
      throw bad("Only an approved transfer with an uncertain or interrupted submission can be retried.");
    if("PENDING_APPROVAL".equals(status)
        &&!List.of("APPROVED","PROCESSING","PARTIAL").contains(text(row,"batch_status")))
      throw bad("This payout batch has not been approved. Do not send an unapproved transfer.");
    if(optionalText(row.get("provider_payout_id"))!=null)throw bad("A provider payout ID exists. Refresh its status instead of resubmitting.");
    return ResponseEntity.ok(submitApprovedPayout(payoutId,context.staffId()));
  }

  private Map<String,Object> submitApprovedPayout(long payoutId,long actorStaffId) {
    Map<String,Object> row=one("""
      select p.*,b.provider_fund_account_id,b.beneficiary_name,b.beneficiary_type,b.institution_id,b.legal_entity_id,
        b.status as beneficiary_status,batch.status as batch_status,
        batch.approved_by_staff_id as batch_approved_by_staff_id
      from finance_payout p
      join finance_payout_beneficiary b on b.id=p.beneficiary_id
      join finance_payout_batch batch on batch.id=p.batch_id
      where p.id=?
      """,payoutId);
    if(row.isEmpty())throw notFound("Payout not found.");
    String currentStatus=text(row,"status");
    if("PENDING_APPROVAL".equals(currentStatus)
        &&List.of("APPROVED","PROCESSING","PARTIAL").contains(text(row,"batch_status"))) {
      long batchApprover=number(row.get("batch_approved_by_staff_id"));
      if(batchApprover<=0)throw bad("The batch is marked approved but has no recorded approver. Finance review is required.");
      int recovered=jdbc.update("""
        update finance_payout set status='APPROVED',approved_by_staff_id=?,approved_at=coalesce(approved_at,now()),updated_at=now()
        where id=? and status='PENDING_APPROVAL'
        """,batchApprover,payoutId);
      if(recovered==1) {
        row.put("status","APPROVED");
        payoutEvent(payoutId,"APPROVED","course-payout:"+payoutId+":approved",batchApprover,null,
            Map.of("batchId",number(row.get("batch_id")),"recoveredAfterInterruptedApproval",true));
      } else {
        currentStatus=text(one("select status from finance_payout where id=?",payoutId),"status");
        if(!List.of("APPROVED","SUBMITTING","RECONCILIATION_REQUIRED").contains(currentStatus))
          return payoutSummary(payoutId);
        row.put("status",currentStatus);
      }
    }
    currentStatus=text(row,"status");
    if(!List.of("APPROVED","RECONCILIATION_REQUIRED","SUBMITTING").contains(currentStatus))
      throw bad("Payout is not approved for submission or recovery.");
    if(!"ACTIVE".equals(text(row,"beneficiary_status"))||optionalText(row.get("provider_fund_account_id"))==null)
      throw bad("The beneficiary account is not active.");
    if("SUBMITTING".equals(currentStatus)) {
      // Explicit recovery action replays the exact payload with the same saved provider idempotency key.
      // Do not replay if another worker already attached the provider payout ID.
      int retry=jdbc.update("update finance_payout set status_details=null,updated_at=now() where id=? and status='SUBMITTING' and provider_payout_id is null",payoutId);
      if(retry!=1)return payoutSummary(payoutId);
    } else {
      int claimed=jdbc.update("""
        update finance_payout set status='SUBMITTING',status_details=null,updated_at=now()
        where id=? and status in ('APPROVED','RECONCILIATION_REQUIRED')
        """,payoutId);
      if(claimed!=1) {
        String actual=text(one("select status from finance_payout where id=?",payoutId),"status");
        if(List.of("SUBMITTING","PENDING","QUEUED","PROCESSING","PROCESSED","REVERSED","FAILED","REJECTED").contains(actual))
          return payoutSummary(payoutId);
        throw bad("Payout changed before submission. Refresh it before retrying.");
      }
    }
    payoutEvent(payoutId,"SUBMITTED","course-payout:"+payoutId+":submitted:"+UUID.randomUUID(),actorStaffId,null,
        Map.of("providerIdempotencyKey",row.get("provider_idempotency_key")));
    Map<String,Object> payload=new LinkedHashMap<>();
    payload.put("account_number",sourceAccountNumber);payload.put("fund_account_id",text(row,"provider_fund_account_id"));
    payload.put("amount",number(row.get("amount_paise")));payload.put("currency","INR");payload.put("mode","IMPS");
    // RazorpayX documents this purpose value with a space; vendor_bill is not the API label.
    payload.put("purpose","vendor bill");payload.put("reference_id",text(row,"public_id"));
    payload.put("narration","QuantaEdge beneficiary payout");payload.put("queue_if_low_balance",true);
    payload.put("notes",Map.of("quantaedge_payout_id",Long.toString(payoutId),
        "quantaedge_batch_id",Long.toString(number(row.get("batch_id"))),
        "quantaedge_beneficiary_id",Long.toString(number(row.get("beneficiary_id")))));
    try {
      JsonNode provider=providerRequest("POST","/payouts",payload,text(row,"provider_idempotency_key"));
      return applyProviderStatus(payoutId,provider,actorStaffId,null);
    } catch(Exception ex) {
      markPayoutUnknown(payoutId,actorStaffId,safePayoutFailure(ex));
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
      Map<String,Object> applied=applyProviderStatus(localPayoutId,payout,null,eventId);
      Map<String,Object> updated=one("select status from finance_payout where id=?",localPayoutId);
      disposition=Boolean.TRUE.equals(applied.get("providerReviewRequired"))
          ||"RECONCILIATION_REQUIRED".equals(string(updated.get("status")))
          ?"RECONCILIATION_REQUIRED":"PROCESSED";
    }
    jdbc.update("""
      insert into finance_payout_provider_event(
        provider_event_id,provider_payout_id,event_type,payload_sha256,disposition,processed_at
      ) values(?,?,?,?,?,now())
      on conflict(provider_event_id) do nothing
      """,eventId,providerId,kind,payloadSha256,disposition);
  }

  private Map<String,Object> applyProviderStatus(long payoutId,JsonNode provider,Long actorStaffId,String providerEventId) {
    if(provider==null||!provider.isObject())
      throw new IllegalStateException("RazorpayX returned a non-object payout record.");
    String providerId=optionalText(provider.path("id").asText(null));
    String providerStatus=optionalText(provider.path("status").asText(null));
    Map<String,Object> current=one("""
      select p.status,p.batch_id,p.provider_payout_id,p.amount_paise,p.currency,p.public_id,
        b.provider_fund_account_id
      from finance_payout p
      join finance_payout_beneficiary b on b.id=p.beneficiary_id
      where p.id=?
      """,payoutId);
    if(current.isEmpty())throw notFound("Payout not found.");
    if(providerId==null||!providerId.matches("[A-Za-z0-9_-]{4,80}"))
      return markProviderReviewRequired(payoutId,current,null,providerStatus,actorStaffId,providerEventId,
          "RazorpayX returned a missing or invalid payout ID. Check the provider record; no paid ledger event was posted.");
    String identityProblem=providerDataMismatch(payoutId,provider,providerId,current,false);
    if(identityProblem==null&&(providerStatus==null||providerStatus.length()>32))
      identityProblem="RazorpayX returned a missing or invalid payout status. Refresh provider state before considering this transfer complete.";
    if(identityProblem!=null)
      return markProviderReviewRequired(payoutId,current,providerId,providerStatus,actorStaffId,providerEventId,identityProblem);
    String normalized=providerStatus.toLowerCase(Locale.ROOT);
    String status=switch(normalized){
      case "pending"->"PENDING";case "queued"->"QUEUED";case "initiated","processing"->"PROCESSING";
      case "processed"->"PROCESSED";case "reversed"->"REVERSED";case "failed"->"FAILED";
      case "rejected","cancelled"->"REJECTED";default->"RECONCILIATION_REQUIRED";
    };
    String prior=text(current,"status");
    if("PROCESSED".equals(prior)&&!"PROCESSED".equals(status)&&!"REVERSED".equals(status))return payoutSummary(payoutId);
    if("REVERSED".equals(prior)&&!"REVERSED".equals(status))return payoutSummary(payoutId);
    // RazorpayX webhook delivery order is not guaranteed. A later signed processed/reversed
    // event may legitimately supersede an earlier failed/rejected notification; never let a
    // delayed pending/processing event move a failed payout backward.
    if(List.of("FAILED","REJECTED").contains(prior)&&!prior.equals(status)
        &&!List.of("PROCESSED","REVERSED").contains(status))return payoutSummary(payoutId);
    String utr=optionalText(provider.path("utr").asText(null));
    String description=optionalText(provider.path("status_details").path("description").asText(null));
    if(description==null)description=optionalText(provider.path("failure_reason").asText(null));
    Long providerFee=optionalProviderAmount(provider,"fees");
    Long providerFeeTax=optionalProviderAmount(provider,"tax");
    // Never coerce malformed provider amounts to zero. The transfer status may still be valid;
    // incomplete fees remain visible for statement reconciliation.
    boolean feeTotalOverflow=false;
    if(providerFee!=null&&providerFeeTax!=null) {
      try { Math.addExact(providerFee,providerFeeTax); }
      catch(ArithmeticException overflow) { feeTotalOverflow=true; }
    }
    boolean providerFeeDataInvalid=(provider.hasNonNull("fees")&&providerFee==null)
        ||(provider.hasNonNull("tax")&&providerFeeTax==null)||feeTotalOverflow;
    boolean providerFeesComplete=providerFee!=null&&providerFeeTax!=null&&!feeTotalOverflow;
    jdbc.update("""
      update finance_payout set provider_payout_id=?,provider_status=?,status=?,transfer_reference=?,status_details=?,
        provider_fee_paise=case when ? then coalesce(?,provider_fee_paise) else provider_fee_paise end,
        provider_fee_tax_paise=case when ? then coalesce(?,provider_fee_tax_paise) else provider_fee_tax_paise end,
        provider_fee_reference=case when ? then coalesce(provider_fee_reference,?) else provider_fee_reference end,
        provider_fee_source_type=case when ? then coalesce(provider_fee_source_type,'PROVIDER_API') else provider_fee_source_type end,
        provider_fee_confirmed_at=case when ? then coalesce(provider_fee_confirmed_at,now()) else provider_fee_confirmed_at end,
        submitted_at=coalesce(submitted_at,now()),
        processed_at=case when ? in ('PROCESSED','REVERSED') then coalesce(processed_at,now()) else processed_at end,updated_at=now()
      where id=?
      """,providerId,normalized,status,utr,description,
        providerFeesComplete,providerFee,providerFeesComplete,providerFeeTax,
        providerFeesComplete,providerId,providerFeesComplete,providerFeesComplete,status,payoutId);
    String eventType=switch(status){case "PENDING"->"PROVIDER_PENDING";case "QUEUED"->"QUEUED";case "PROCESSING"->"PROCESSING";
      case "PROCESSED"->"PROCESSED";case "REVERSED"->"REVERSED";case "FAILED"->"FAILED";case "REJECTED"->"REJECTED";default->"RECONCILIATION_REQUIRED";};
    Map<String,Object> providerDetails=new LinkedHashMap<>();
    providerDetails.put("providerStatus",normalized);
    providerDetails.put("transferReference",utr==null?"":utr);
    providerDetails.put("statusDetails",description==null?"":description);
    if(providerFee!=null)providerDetails.put("providerFeePaise",providerFee);
    if(providerFeeTax!=null)providerDetails.put("providerFeeTaxPaise",providerFeeTax);
    if(providerFeeDataInvalid)providerDetails.put("providerFeeDataNeedsManualReconciliation",true);
    String providerEventKey=providerEventId==null
        ?"course-payout:"+payoutId+":provider:"+normalized+":"+providerId
        :"course-payout:"+payoutId+":webhook:"+providerEventId;
    payoutEvent(payoutId,eventType,providerEventKey,actorStaffId,providerEventId,providerDetails);
    if("PROCESSED".equals(status)&&!"PROCESSED".equals(prior)&&!"REVERSED".equals(prior))postPayoutLedgerEvents(payoutId,"PAID",actorStaffId);
    else if("REVERSED".equals(status)&&"PROCESSED".equals(prior))postPayoutLedgerEvents(payoutId,"REVERSED",actorStaffId);
    refreshBatchStatus(number(current.get("batch_id")));
    return payoutSummary(payoutId);
  }

  /**
   * Validate provider-to-ledger identity fields before accepting any payout status transition.
   * Mismatches are routed to review and never create a PAID allocation event.
   */
  private String providerDataMismatch(long payoutId,JsonNode provider,String providerId,Map<String,Object> current,
      boolean allowManualCorrelation) {
    String savedId=optionalText(current.get("provider_payout_id"));
    if(savedId!=null&&!savedId.equals(providerId))
      return "RazorpayX payout ID differs from the ID already linked to this transfer. Review the provider record.";
    if(exists("select exists(select 1 from finance_payout where provider_payout_id=? and id<>?)",providerId,payoutId))
      return "RazorpayX payout ID is already linked to a different QuantaEdge transfer. Review the provider record.";
    Long actualAmount=nonNegativeIntegral(provider.get("amount"));
    if(actualAmount==null)
      return "RazorpayX did not return an integer payout amount. Refresh provider state before considering this transfer complete.";
    if(actualAmount!=number(current.get("amount_paise")))
      return "RazorpayX payout amount does not match the approved QuantaEdge amount. No paid ledger event was posted.";
    String actualCurrency=optionalText(provider.path("currency").asText(null));
    if(actualCurrency==null||!actualCurrency.equalsIgnoreCase(string(current.get("currency"))))
      return "RazorpayX payout currency does not match the approved QuantaEdge currency. No paid ledger event was posted.";
    String expectedFundAccount=string(current.get("provider_fund_account_id"));
    String actualFundAccount=optionalText(provider.path("fund_account_id").asText(null));
    if(expectedFundAccount.isBlank()||actualFundAccount==null||!expectedFundAccount.equals(actualFundAccount))
      return "RazorpayX fund account does not match the approved beneficiary. No paid ledger event was posted.";
    String notedPayoutId=optionalText(provider.path("notes").path("quantaedge_payout_id").asText(null));
    String referenceId=optionalText(provider.path("reference_id").asText(null));
    if(notedPayoutId!=null&&!Long.toString(payoutId).equals(notedPayoutId))
      return "RazorpayX payout notes identify a different QuantaEdge payout. No paid ledger event was posted.";
    if(referenceId!=null&&!referenceId.equals(string(current.get("public_id"))))
      return "RazorpayX reference ID does not match this QuantaEdge payout. No paid ledger event was posted.";
    boolean correlated=providerId.equals(savedId)||Long.toString(payoutId).equals(notedPayoutId)
        ||string(current.get("public_id")).equals(referenceId);
    if(!correlated&&!allowManualCorrelation)
      return "RazorpayX response could not be linked to this QuantaEdge payout by provider ID, reference ID or payout note. Review the provider record.";
    return null;
  }

  private Map<String,Object> markProviderReviewRequired(long payoutId,Map<String,Object> current,String providerId,
      String providerStatus,Long actorStaffId,String providerEventId,String reason) {
    String prior=text(current,"status");
    boolean terminal=List.of("PROCESSED","REVERSED","FAILED","REJECTED").contains(prior);
    String safeProviderId=providerId==null?"":providerId.trim();
    boolean validProviderId=safeProviderId.matches("[A-Za-z0-9_-]{4,80}");
    boolean providerIdOwned=validProviderId
        &&exists("select exists(select 1 from finance_payout where provider_payout_id=? and id<>?)",safeProviderId,payoutId);
    String safeProviderStatus=providerStatus==null?"":providerStatus.substring(0,Math.min(32,providerStatus.length()));
    if(terminal) {
      // Preserve terminal state, but save a non-conflicting provider ID so the reviewer can refresh
      // and clear the exception after the provider record is confirmed.
      jdbc.update("""
        update finance_payout set
          provider_payout_id=case when provider_payout_id is null and ?=true and ?=false then ? else provider_payout_id end,
          status_details=?,updated_at=now()
        where id=?
        """,validProviderId,providerIdOwned,safeProviderId,reason,payoutId);
    } else {
      jdbc.update("""
        update finance_payout set
          provider_payout_id=case when provider_payout_id is null and ?=true and ?=false then ? else provider_payout_id end,
          provider_status=?,status='RECONCILIATION_REQUIRED',status_details=?,
          submitted_at=coalesce(submitted_at,now()),updated_at=now()
        where id=? and status not in ('PROCESSED','REVERSED','FAILED','REJECTED')
        """,validProviderId,providerIdOwned,safeProviderId,safeProviderStatus,reason,payoutId);
    }
    String reviewKey=providerEventId==null
        ?"course-payout:"+payoutId+":provider-review:"+safeProviderId+":"+Integer.toHexString(reason.hashCode())
        :"course-payout:"+payoutId+":webhook-review:"+providerEventId;
    payoutEvent(payoutId,"RECONCILIATION_REQUIRED",reviewKey,actorStaffId,providerEventId,
        Map.of("providerStatus",safeProviderStatus,"reason",reason));
    refreshBatchStatus(number(current.get("batch_id")));
    Map<String,Object> summary=new LinkedHashMap<>(payoutSummary(payoutId));
    summary.put("providerReviewRequired",true);
    return summary;
  }

  private static Long optionalProviderAmount(JsonNode provider,String key) {
    return provider==null||!provider.hasNonNull(key)?null:nonNegativeIntegral(provider.get(key));
  }

  private static Long nonNegativeIntegral(JsonNode value) {
    if(value==null||value.isNull()||!value.isIntegralNumber()||!value.canConvertToLong())return null;
    long amount=value.longValue();
    return amount<0?null:amount;
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

  private void markPayoutUnknown(long payoutId,long actorStaffId,String safeReason) {
    Map<String,Object> row=one("select batch_id,status from finance_payout where id=?",payoutId);
    String reason=safeReason==null||safeReason.isBlank()
        ?"RazorpayX did not confirm this transfer. Check provider state before retrying with the saved idempotency key."
        :safeReason.substring(0,Math.min(500,safeReason.length()));
    jdbc.update("""
      update finance_payout set status='RECONCILIATION_REQUIRED',status_details=?,updated_at=now()
      where id=? and status not in ('PROCESSED','REVERSED','FAILED','REJECTED')
      """,reason,payoutId);
    payoutEvent(payoutId,"RECONCILIATION_REQUIRED","course-payout:"+payoutId+":reconciliation:"+UUID.randomUUID(),actorStaffId,null,
        Map.of("message",reason,"balanceRemainsReserved",true));
    if(!row.isEmpty())refreshBatchStatus(number(row.get("batch_id")));
  }

  private static String safePayoutFailure(Exception ex) {
    String message=ex==null?"":string(ex.getMessage());
    java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("RazorpayX returned HTTP ([0-9]{3})").matcher(message);
    if(matcher.find()) {
      String code=matcher.group(1);
      if("401".equals(code)||"403".equals(code))
        return "RazorpayX rejected authentication/authorization (HTTP "+code+"). Verify server-side RazorpayX credentials, environment and source-account configuration. Check provider state before retrying; no paid ledger event is posted from this response.";
      if("400".equals(code)||"422".equals(code))
        return "RazorpayX rejected the payout request (HTTP "+code+"). Review the source account, fund account, amount, currency, mode and purpose in the provider dashboard before retrying with the same idempotency key.";
      return "RazorpayX returned HTTP "+code+". Check provider status before retrying; use only the saved idempotency key and do not create a duplicate payout.";
    }
    if(message.contains("no valid payout ID and status"))
      return "RazorpayX response did not contain a valid payout ID and status. Check the provider dashboard; if no provider payout ID was saved, retry only the same request with its existing idempotency key.";
    return "RazorpayX could not confirm the submission. Its outcome may be uncertain. Check provider state, then Refresh or Retry the same request with the saved idempotency key; do not create a duplicate payout.";
  }

  private Map<String,Object> payoutSummary(long payoutId) {
    return one("""
      select p.id,p.public_id,p.batch_id,p.beneficiary_id,p.amount_paise,p.currency,p.status,p.provider_status,p.provider_payout_id,
        p.provider_fee_paise,p.provider_fee_tax_paise,p.provider_fee_reference,p.provider_fee_source_type,p.provider_fee_confirmed_at,
        p.provider_idempotency_key,p.transfer_reference,p.status_details,p.created_at,p.submitted_at,p.processed_at,
        b.beneficiary_type,b.beneficiary_name,b.bank_ifsc,b.bank_account_last4,t.display_name as institution_name,
        le.legal_name as company_name,coalesce(t.display_name,le.legal_name,b.beneficiary_name) as recipient_name
      from finance_payout p join finance_payout_beneficiary b on b.id=p.beneficiary_id
      left join institution_tenant t on t.id=b.institution_id left join billing_legal_entity le on le.id=b.legal_entity_id where p.id=?
      """,payoutId);
  }

  private void reconcileBatchProviderFees(long batchId) {
    Map<String,Object> batch=one("select provider_fee_reconciled_at from finance_payout_batch where id=?",batchId);
    if(batch.isEmpty()||batch.get("provider_fee_reconciled_at")!=null)return;
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,status,provider_fee_paise,provider_fee_tax_paise
      from finance_payout where batch_id=? order by id
      """,batchId);
    if(rows.isEmpty()||rows.stream().anyMatch(r->!List.of("PROCESSED","FAILED","REJECTED","REVERSED").contains(string(r.get("status")))))return;
    if(rows.stream().anyMatch(r->r.get("provider_fee_paise")==null||r.get("provider_fee_tax_paise")==null))return;
    long totalFee=0L,totalTax=0L;
    for(Map<String,Object> row:rows) {
      totalFee=Math.addExact(totalFee,number(row.get("provider_fee_paise")));
      totalTax=Math.addExact(totalTax,number(row.get("provider_fee_tax_paise")));
    }
    long totalCharge=Math.addExact(totalFee,totalTax);
    List<Map<String,Object>> items=jdbc.queryForList("""
      select i.allocation_id,sum(i.amount_paise) as weight_paise,a.currency
      from finance_payout_item i join finance_payout p on p.id=i.payout_id
      join revenue_share_allocation a on a.id=i.allocation_id
      where p.batch_id=? group by i.allocation_id,a.currency order by i.allocation_id
      """,batchId);
    long weightTotal=items.stream().mapToLong(r->number(r.get("weight_paise"))).reduce(0L,Math::addExact);
    // Record the actual provider charge in full. Never leave a completed batch blocked just
    // because charges exceed beneficiary allocation weights; the platform absorbs the overage.
    long allocatableCharge=weightTotal>0?Math.min(totalCharge,weightTotal):0L;
    long platformBorneOverage=Math.subtractExact(totalCharge,allocatableCharge);
    long assigned=0L;
    for(int i=0;i<items.size();i++) {
      Map<String,Object> item=items.get(i);
      long allocationId=number(item.get("allocation_id"));
      long amount=i==items.size()-1?Math.subtractExact(allocatableCharge,assigned)
          :proportionalAmount(allocatableCharge,number(item.get("weight_paise")),weightTotal);
      if(amount<0)throw new IllegalStateException("Provider payout-fee allocation cannot be negative.");
      if(amount>allocatableCharge-assigned)amount=allocatableCharge-assigned;
      assigned=Math.addExact(assigned,amount);
      if(amount==0)continue;
      jdbc.update("""
        insert into revenue_share_allocation_event(
          allocation_id,event_type,amount_paise,currency,idempotency_key,details
        ) values(?,'PROCESSING_FEE_DEDUCTED',?,?,?,?::jsonb)
        on conflict(idempotency_key) do nothing
        """,allocationId,amount,string(item.get("currency")),
          "course-payout-batch:"+batchId+":fee-allocation:"+allocationId,
          json(Map.of("payoutBatchId",batchId,"payoutProcessingFeePaise",totalFee,
            "payoutFeeTaxPaise",totalTax,"totalProviderChargePaise",totalCharge,
            "platformBorneOveragePaise",platformBorneOverage,
            "allocationWeightPaise",number(item.get("weight_paise")),
            "allocationFeeSharePaise",amount,"basis","PROVIDER_CONFIRMED_MONTHLY_PAYOUT")));
    }
    if(assigned!=allocatableCharge)return;
    jdbc.update("""
      update finance_payout_batch set provider_fee_total_paise=?,provider_fee_tax_total_paise=?,
        platform_borne_overage_paise=?,provider_fee_reconciled_at=now(),updated_at=now()
      where id=? and provider_fee_reconciled_at is null
      """,totalFee,totalTax,platformBorneOverage,batchId);
  }

  private static long proportionalAmount(long total,long part,long whole) {
    if(total<0||part<0||whole<=0||part>whole)throw new IllegalArgumentException("Proportional allocation inputs are invalid.");
    return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
        .divide(BigDecimal.valueOf(whole),0,RoundingMode.HALF_UP).longValueExact();
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
    reconcileBatchProviderFees(batchId);
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
  private static String string(Object value){return value==null?"":String.valueOf(value).trim();}
  private static String text(Map<String,Object> m,String k){Object v=m==null?null:m.get(k);return v==null?"":String.valueOf(v).trim();}
  private static String requiredText(Object v,String label){String s=v==null?"":String.valueOf(v).trim();if(s.isBlank())throw bad("Enter "+label+".");return s;}
  private static long requiredLong(Object v,String label){try{return new BigDecimal(String.valueOf(v)).longValueExact();}catch(Exception e){throw bad("Enter an integer amount for "+label+".");}}
  private static String optionalText(Object v){if(v==null)return null;String s=String.valueOf(v).trim();return s.isBlank()?null:s;}
  private static Long nullableLong(Object v){if(v==null)return null;try{return new BigDecimal(String.valueOf(v)).longValueExact();}catch(Exception e){return null;}}
  private static long number(Object v){
    if(v==null)return 0L; // SQL NULL is an explicit optional-value default; malformed non-null values must fail closed.
    try{return new BigDecimal(String.valueOf(v)).longValueExact();}
    catch(Exception e){throw new IllegalStateException("A finance field expected to contain an integer was malformed.",e);}
  }
  private static long percentageOf(long amount,int bps){return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bps)).divide(BigDecimal.valueOf(10_000),0,RoundingMode.HALF_UP).longValueExact();}
  private static IllegalArgumentException bad(String m){return new IllegalArgumentException(m);}
  private static org.springframework.web.server.ResponseStatusException notFound(String m){return new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,m);}
}