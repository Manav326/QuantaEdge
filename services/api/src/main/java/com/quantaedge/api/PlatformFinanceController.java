package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HRMS-owned financial configuration. This controller deliberately does not issue invoices,
 * create payout instructions, or infer statutory tax treatment from user-entered data.
 */
@RestController
@RequestMapping("/api/v1/hrms/platform/finance")
public class PlatformFinanceController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;
  private final InvoicePreviewPdfRenderer invoicePreviewPdfRenderer;
  private final FiscalDocumentStorage fiscalDocumentStorage;

  public PlatformFinanceController(JdbcTemplate jdbc, AuthorizationService authorization, ObjectMapper objectMapper,
      InvoicePreviewPdfRenderer invoicePreviewPdfRenderer,FiscalDocumentStorage fiscalDocumentStorage) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.objectMapper=objectMapper;
    this.invoicePreviewPdfRenderer=invoicePreviewPdfRenderer;
    this.fiscalDocumentStorage=fiscalDocumentStorage;
  }

  @GetMapping("/overview")
  public Map<String,Object> overview(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    Map<String,Object> r=new LinkedHashMap<>();
    r.put("legalEntities",count("select count(*) from billing_legal_entity"));
    r.put("activeTaxProfiles",count("select count(*) from course_tax_profile where status='APPROVED'"));
    r.put("pendingTaxProfiles",count("select count(*) from course_tax_profile where status='PENDING_APPROVAL'"));
    r.put("approvedSharePolicies",count("select count(*) from revenue_share_policy where status='APPROVED'"));
    r.put("pendingSharePolicies",count("select count(*) from revenue_share_policy where status='PENDING_APPROVAL'"));
    r.put("issuedDocuments",count("select count(*) from fiscal_document"));
    r.put("allocatedPaise",jdbc.queryForObject("""
      select coalesce(sum(total_amount),0) from (
        select course_payment_order_id,max(total_institution_allocated_paise) as total_amount
        from revenue_share_allocation group by course_payment_order_id
      ) allocated
      """,Long.class));

    // Financial position uses verified captures and provider-confirmed refunds only.
    // Output tax is shown net of issued course-sale credit notes, before input-tax credits
    // or other adjustments that require the organisation's tax adviser.
    long captured=coalesceAmount("""
      select coalesce(sum(captured_amount_paise),0)
      from course_payment_order where status='VERIFIED'
      """);
    long processedRefunds=coalesceAmount("""
      select coalesce(sum(requested_amount_paise),0)
      from course_payment_refund where status='PROCESSED'
      """);
    long pendingRefunds=coalesceAmount("""
      select coalesce(sum(requested_amount_paise),0)
      from course_payment_refund where status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
      """);
    long invoicedOutputTax=coalesceAmount("""
      select coalesce(sum(output_tax_paise),0)
      from course_payment_order where status='VERIFIED'
      """);
    long processedCreditTax=coalesceAmount("""
      select coalesce(sum(d.tax_value_paise),0)
      from fiscal_document d join course_payment_refund r on r.credit_note_document_id=d.id
      where d.document_type='CREDIT_NOTE' and d.source_type='COURSE_ORDER' and r.status='PROCESSED'
      """);
    long confirmedProcessorFees=coalesceAmount("""
      select coalesce(sum(processing_fee_paise),0) from course_payment_order_fee
      """);
    long confirmedProcessorFeeTax=coalesceAmount("""
      select coalesce(sum(fee_tax_paise),0) from course_payment_order_fee
      """);
    long netOutputTax=Math.subtractExact(invoicedOutputTax,processedCreditTax);
    long netCollected=Math.subtractExact(captured,processedRefunds);
    long poolAfterTaxRefundsAndFees=Math.subtractExact(
        Math.subtractExact(netCollected,netOutputTax),
        Math.addExact(confirmedProcessorFees,confirmedProcessorFeeTax));
    r.put("capturedSalesPaise",captured);
    r.put("processedRefundsPaise",processedRefunds);
    r.put("pendingRefundsPaise",pendingRefunds);
    r.put("pendingRefundCount",count("""
      select count(*) from course_payment_refund
      where status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED')
      """));
    r.put("netCollectedAfterRefundsPaise",netCollected);
    r.put("invoicedOutputTaxPaise",invoicedOutputTax);
    r.put("processedCreditNoteTaxPaise",processedCreditTax);
    r.put("netOutputTaxBeforeInputCreditsPaise",netOutputTax);
    r.put("confirmedProcessorFeesPaise",confirmedProcessorFees);
    r.put("confirmedProcessorFeeTaxPaise",confirmedProcessorFeeTax);
    r.put("netPoolAfterTaxRefundsAndFeesPaise",poolAfterTaxRefundsAndFees);
    r.put("paymentFeeReconciliationPending",count("""
      select count(distinct o.id) from course_payment_order o
      join revenue_share_allocation a on a.course_payment_order_id=o.id
      join revenue_share_allocation_event e on e.allocation_id=a.id and e.event_type='EARNED'
      left join course_payment_order_fee f on f.course_payment_order_id=o.id
      where o.status='VERIFIED' and f.id is null
      """));
    return r;
  }

  @GetMapping("/catalog")
  public Map<String,Object> catalog(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    List<Map<String,Object>> institutions=jdbc.queryForList("""
      select id,tenant_code,display_name,status from institution_tenant
      where status in ('ACTIVE','SUSPENDED') order by lower(display_name),id
      """);
    List<Map<String,Object>> offers=jdbc.queryForList("""
      select o.id as offer_id,o.public_id,o.institution_id,t.display_name as institution_name,
        identity.public_id as course_identity_public_id,identity.stable_course_key,
        p.content_version_no,assignment.id as assignment_id,assignment.assignment_source,
        release.id as release_id,release.release_version,release.release_state,
        qe_current_course_offer_revision(o.id) as current_candidate,
        qe_marketplace_offer_publicly_sellable(o.id) as publicly_sellable,
        coalesce(identity.stable_course_key,p.code) as course_code,
        coalesce(nullif(o.title_override,''),p.display_name) as course_name,
        o.price_paise,o.currency,o.status as offer_status,
        le.id as legal_entity_id,le.legal_name as legal_seller_name,
        tp.id as latest_tax_profile_id,tp.status as tax_profile_status,tp.tax_treatment,
        (select rp.status from revenue_share_policy rp where rp.institution_offer_id=o.id
          order by rp.policy_version desc limit 1) as latest_share_policy_status
      from institution_marketplace_offer o
      join institution_tenant t on t.id=o.institution_id
      join institution_course_assignment assignment
        on assignment.id=o.assignment_id and assignment.institution_id=o.institution_id
       and assignment.status='ASSIGNED' and assignment.assignment_source in ('ORIGIN','HRMS')
      join institution_course_release release
        on release.id=o.course_release_id and release.program_id=o.course_id
       and release.release_state='SHAREABLE' and release.legacy_migration=false
      join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_course_identity identity
        on identity.id=p.course_identity_id and identity.status='ACTIVE'
      left join lateral (
        select tax.id,tax.status,tax.tax_treatment,tax.legal_entity_id
        from course_tax_profile tax where tax.institution_offer_id=o.id
        order by tax.version desc limit 1
      ) tp on true
      left join billing_legal_entity le on le.id=tp.legal_entity_id
      where o.status<>'ARCHIVED' and (qe_current_course_offer_revision(o.id) or qe_marketplace_offer_publicly_sellable(o.id))
      order by t.display_name,identity.stable_course_key,p.content_version_no desc,release.release_version desc,o.id
      """);
    List<Map<String,Object>> registrations=jdbc.queryForList("""
      select r.id,r.legal_entity_id,r.gstin,r.legal_name_snapshot,r.state_code,r.registration_type,
        r.status,r.effective_from,r.effective_until,e.legal_name as legal_entity_name
      from billing_gst_registration r join billing_legal_entity e on e.id=r.legal_entity_id
      order by e.legal_name,r.created_at desc,r.id desc
      """);
    return Map.of("institutions",institutions,"legalEntities",legalEntityRows(),
        "offers",offers,"registrations",registrations);
  }

  @PostMapping("/legal-entities")
  @Transactional
  public Map<String,Object> createLegalEntity(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    String kind=requiredText(body,"entityKind").toUpperCase(Locale.ROOT);
    if(!List.of("PLATFORM","INSTITUTION","COMPANY").contains(kind)) throw bad("Choose PLATFORM, COMPANY or INSTITUTION as the legal-entity scope.");
    Long institutionId=optionalLong(body.get("institutionId"));
    if(!"INSTITUTION".equals(kind) && institutionId!=null) throw bad("A platform/company legal entity must not be attached to a tenant institution.");
    if("INSTITUTION".equals(kind) && (institutionId==null || !exists(
        "select exists(select 1 from institution_tenant where id=?)",institutionId))) {
      throw bad("Choose an existing institution for this legal entity.");
    }
    String name=requiredText(body,"legalName");
    String invoiceSeries=optionalText(body.get("invoiceSeriesCode"));
    if(invoiceSeries==null) invoiceSeries="QE";
    invoiceSeries=invoiceSeries.toUpperCase(Locale.ROOT);
    if(!invoiceSeries.matches("[A-Z0-9_-]{1,3}")) throw bad("Invoice series prefix must be 1–3 uppercase letters, digits, underscores or hyphens so the complete invoice serial stays within 16 characters.");
    long id=jdbc.queryForObject("""
      insert into billing_legal_entity(entity_kind,institution_id,legal_name,trade_name,contact_email,contact_phone,
        registered_address,invoice_series_code,status,created_by_staff_id)
      values(?,?,?,?,?,?,?::jsonb,?,'DRAFT',?) returning id
      """,Long.class,kind,institutionId,name,optionalText(body.get("tradeName")),
        optionalText(body.get("contactEmail")),optionalText(body.get("contactPhone")),
        jsonObject(body.get("registeredAddress")),invoiceSeries,context.staffId());
    audit(id,institutionId,context,"LEGAL_ENTITY_CREATED","BILLING_LEGAL_ENTITY",Long.toString(id),
        Map.of("entityKind",kind,"legalName",name));
    return Map.of("id",id,"status","DRAFT","message","Draft saved. Activate only after the legal identity and tax details are reviewed.");
  }

  @PutMapping("/legal-entities/{legalEntityId}")
  @Transactional
  public Map<String,Object> updateDraftLegalEntity(@PathVariable long legalEntityId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> current=one("""
      select id,institution_id,status,legal_name,trade_name,contact_email,contact_phone,
        registered_address->>'line1' as address_line1,registered_address->>'city' as address_city,
        registered_address->>'state' as address_state,registered_address->>'postalCode' as address_postal_code,
        registered_address->>'country' as address_country,invoice_series_code
      from billing_legal_entity where id=? for update
      """,legalEntityId);
    if(current.isEmpty()) throw notFound("Legal entity not found.");
    if(!"DRAFT".equals(String.valueOf(current.get("status"))))
      throw bad("Core legal details can only be edited while the entity is DRAFT. Activated entities retain an immutable legal identity for audit and issued-invoice history.");
    String legalName=requiredText(body,"legalName");
    String tradeName=optionalText(body.get("tradeName"));
    String contactEmail=optionalText(body.get("contactEmail"));
    String contactPhone=optionalText(body.get("contactPhone"));
    String series=requiredText(body,"invoiceSeriesCode").toUpperCase(Locale.ROOT);
    if(!series.matches("[A-Z0-9_-]{1,3}"))
      throw bad("Invoice series prefix must be 1–3 uppercase letters, digits, underscores or hyphens.");
    Object rawAddress=body.get("registeredAddress");
    if(!(rawAddress instanceof Map<?,?> suppliedAddress))
      throw bad("registeredAddress is required.");
    Map<String,Object> address=new LinkedHashMap<>();
    address.put("line1",requiredText(suppliedAddress.get("line1"),"registeredAddress.line1"));
    address.put("city",requiredText(suppliedAddress.get("city"),"registeredAddress.city"));
    address.put("state",requiredText(suppliedAddress.get("state"),"registeredAddress.state"));
    address.put("postalCode",requiredText(suppliedAddress.get("postalCode"),"registeredAddress.postalCode"));
    String country=optionalText(suppliedAddress.get("country"));
    address.put("country",country==null?"India":country);
    int changed=jdbc.update("""
      update billing_legal_entity
      set legal_name=?,trade_name=?,contact_email=?,contact_phone=?,registered_address=?::jsonb,
          invoice_series_code=?,updated_by_staff_id=?,updated_at=now()
      where id=? and status='DRAFT'
      """,legalName,tradeName,contactEmail,contactPhone,jsonObject(address),series,context.staffId(),legalEntityId);
    if(changed!=1) throw bad("The legal entity changed while being edited. Reload it and try again.");
    Long institutionId=optionalLong(current.get("institution_id"));
    Map<String,Object> details=new LinkedHashMap<>();
    details.put("previousLegalName",String.valueOf(current.get("legal_name")));
    details.put("legalName",legalName);
    details.put("previousInvoiceSeries",String.valueOf(current.get("invoice_series_code")));
    details.put("invoiceSeries",series);
    List<String> changedFields=new ArrayList<>();
    if(!java.util.Objects.equals(current.get("legal_name"),legalName)) changedFields.add("legalName");
    if(!java.util.Objects.equals(current.get("trade_name"),tradeName)) changedFields.add("tradeName");
    if(!java.util.Objects.equals(current.get("contact_email"),contactEmail)) changedFields.add("contactEmail");
    if(!java.util.Objects.equals(current.get("contact_phone"),contactPhone)) changedFields.add("contactPhone");
    if(!java.util.Objects.equals(current.get("address_line1"),address.get("line1"))
        || !java.util.Objects.equals(current.get("address_city"),address.get("city"))
        || !java.util.Objects.equals(current.get("address_state"),address.get("state"))
        || !java.util.Objects.equals(current.get("address_postal_code"),address.get("postalCode"))
        || !java.util.Objects.equals(current.get("address_country"),address.get("country"))) changedFields.add("registeredAddress");
    if(!java.util.Objects.equals(current.get("invoice_series_code"),series)) changedFields.add("invoiceSeriesCode");
    details.put("changedFields",changedFields);
    audit(legalEntityId,institutionId,context,"LEGAL_ENTITY_DETAILS_UPDATED","BILLING_LEGAL_ENTITY",
        Long.toString(legalEntityId),details);
    return Map.of("id",legalEntityId,"status","DRAFT","message","Draft legal entity details updated.");
  }

  @PostMapping("/legal-entities/{legalEntityId}/activate")
  @Transactional
  public Map<String,Object> activateLegalEntity(@PathVariable long legalEntityId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> e=one("select id,entity_kind,institution_id,status,legal_name from billing_legal_entity where id=?",legalEntityId);
    if(e.isEmpty()) throw notFound("Legal entity not found.");
    Long institutionId=optionalLong(e.get("institution_id"));
    if(!exists("""
      select exists(select 1 from billing_legal_entity
        where id=?
          and nullif(btrim(registered_address->>'line1'),'') is not null
          and nullif(btrim(registered_address->>'city'),'') is not null
          and nullif(btrim(registered_address->>'state'),'') is not null
          and nullif(btrim(registered_address->>'postalCode'),'') is not null)
      """,legalEntityId)) {
      throw bad("Complete the registered address (line, city, state and postal code) before activating the legal seller.");
    }
    if("INSTITUTION".equals(String.valueOf(e.get("entity_kind"))) &&
        !exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",institutionId)) {
      throw bad("The institution must be active before its legal entity can be activated.");
    }
    jdbc.update("update billing_legal_entity set status='ACTIVE',updated_by_staff_id=?,updated_at=now() where id=?",
        context.staffId(),legalEntityId);
    audit(legalEntityId,institutionId,context,"LEGAL_ENTITY_ACTIVATED","BILLING_LEGAL_ENTITY",
        Long.toString(legalEntityId),Map.of("legalName",String.valueOf(e.get("legal_name"))));
    return Map.of("id",legalEntityId,"status","ACTIVE");
  }

  @PutMapping("/legal-entities/{legalEntityId}/invoice-series")
  @Transactional
  public Map<String,Object> updateInvoiceSeries(
      @PathVariable long legalEntityId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    String series=requiredText(body,"invoiceSeriesCode").toUpperCase(Locale.ROOT);
    if(!series.matches("[A-Z0-9_-]{1,3}")) throw bad("Invoice series prefix must be 1–3 uppercase letters, digits, underscores or hyphens so the complete invoice serial stays within 16 characters.");
    Map<String,Object> entity=one("select id,institution_id,legal_name,invoice_series_code from billing_legal_entity where id=?",legalEntityId);
    if(entity.isEmpty()) throw notFound("Legal entity not found.");
    jdbc.update("update billing_legal_entity set invoice_series_code=?,updated_by_staff_id=?,updated_at=now() where id=?",
        series,context.staffId(),legalEntityId);
    Long institutionId=optionalLong(entity.get("institution_id"));
    audit(legalEntityId,institutionId,context,"INVOICE_SERIES_UPDATED","BILLING_LEGAL_ENTITY",
        Long.toString(legalEntityId),Map.of("oldSeries",String.valueOf(entity.get("invoice_series_code")),"newSeries",series));
    return Map.of("id",legalEntityId,"invoiceSeriesCode",series);
  }

  @PutMapping("/legal-entities/{legalEntityId}/einvoice-applicability")
  @Transactional
  public Map<String,Object> reviewEinvoiceApplicability(
      @PathVariable long legalEntityId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    String applicability=requiredText(body,"applicability").toUpperCase(Locale.ROOT);
    if(!List.of("NOT_REQUIRED","REQUIRED").contains(applicability))
      throw bad("Choose either NOT_REQUIRED or REQUIRED after the legal seller's e-invoice applicability has been reviewed.");
    String reviewNote=requiredText(body,"reviewNote");
    if(reviewNote.length()<20 || reviewNote.length()>1000)
      throw bad("Record a review rationale or tax-adviser reference between 20 and 1,000 characters.");
    Map<String,Object> entity=one(
        "select id,institution_id,legal_name,einvoice_applicability from billing_legal_entity where id=?",
        legalEntityId);
    if(entity.isEmpty()) throw notFound("Legal entity not found.");
    Long institutionId=optionalLong(entity.get("institution_id"));
    int changed=jdbc.update("""
      update billing_legal_entity
      set einvoice_applicability=?,einvoice_applicability_reviewed_by_staff_id=?,
          einvoice_applicability_reviewed_at=now(),einvoice_applicability_review_note=?,
          updated_by_staff_id=?,updated_at=now()
      where id=?
      """,applicability,context.staffId(),reviewNote,context.staffId(),legalEntityId);
    if(changed!=1) throw bad("The legal seller changed while the e-invoice review was being saved. Reload and retry.");
    audit(legalEntityId,institutionId,context,"EINVOICE_APPLICABILITY_REVIEWED",
        "BILLING_LEGAL_ENTITY",Long.toString(legalEntityId),
        Map.of("previousApplicability",String.valueOf(entity.get("einvoice_applicability")),
            "applicability",applicability,"reviewNote",reviewNote));
    return Map.of("id",legalEntityId,"einvoiceApplicability",applicability,
        "reviewedByStaffId",context.staffId(),"message",
        "Human review recorded. This does not validate applicability with a GST portal or replace professional tax advice.");
  }

  @PostMapping("/legal-entities/{legalEntityId}/gst-registrations")
  @Transactional
  public Map<String,Object> addGstRegistration(@PathVariable long legalEntityId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> e=one("select id,institution_id from billing_legal_entity where id=?",legalEntityId);
    if(e.isEmpty()) throw notFound("Legal entity not found.");
    String gstin=requiredText(body,"gstin").toUpperCase(Locale.ROOT);
    if(!gstin.matches("[0-9A-Z]{15}")) throw bad("Enter a GSTIN in the expected 15-character format.");
    String stateCode=requiredText(body,"stateCode");
    if(!stateCode.matches("[0-9]{2}")) throw bad("GST state code must contain two digits.");
    if(!gstin.startsWith(stateCode)) throw bad("GSTIN prefix must match the registration state code.");
    java.time.LocalDate start=localDate(body,"effectiveFrom");
    java.time.LocalDate end=optionalLocalDate(body.get("effectiveUntil"));
    if(end!=null && end.isBefore(start)) throw bad("GST registration end date cannot precede its start date.");
    String regType=optionalText(body.get("registrationType"));
    if(regType==null) regType="REGULAR";
    regType=regType.toUpperCase(Locale.ROOT);
    if(!List.of("REGULAR","COMPOSITION","SEZ","OTHER").contains(regType)) throw bad("Unsupported GST registration type.");
    long id=jdbc.queryForObject("""
      insert into billing_gst_registration(legal_entity_id,gstin,legal_name_snapshot,state_code,registered_address,
        registration_type,status,effective_from,effective_until,created_by_staff_id)
      values(?,?,?,?,?::jsonb,?,'PENDING',?,?,?) returning id
      """,Long.class,legalEntityId,gstin,requiredText(body,"legalName"),stateCode,jsonObject(body.get("registeredAddress")),
        regType,start,end,context.staffId());
    audit(legalEntityId,optionalLong(e.get("institution_id")),context,"GST_REGISTRATION_CREATED",
        "BILLING_GST_REGISTRATION",Long.toString(id),Map.of("gstinSuffix",gstin.substring(11),"status","PENDING"));
    return Map.of("id",id,"status","PENDING","message","Registration saved pending HRMS verification.");
  }

  @PostMapping("/gst-registrations/{registrationId}/verify")
  @Transactional
  public Map<String,Object> verifyGstRegistration(@PathVariable long registrationId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select r.legal_entity_id,r.gstin,e.institution_id from billing_gst_registration r
      join billing_legal_entity e on e.id=r.legal_entity_id where r.id=?
      """,registrationId);
    if(row.isEmpty()) throw notFound("GST registration not found.");
    int changed=jdbc.update("""
      update billing_gst_registration set status='ACTIVE',verified_by_staff_id=?,verified_at=now()
      where id=? and status='PENDING'
      """,context.staffId(),registrationId);
    if(changed!=1) throw bad("Only a pending GST registration can be marked verified.");
    audit(((Number)row.get("legal_entity_id")).longValue(),optionalLong(row.get("institution_id")),context,
        "GST_REGISTRATION_VERIFIED","BILLING_GST_REGISTRATION",Long.toString(registrationId),
        Map.of("gstinSuffix",String.valueOf(row.get("gstin")).substring(11),"verificationMethod","HRMS_REVIEW"));
    return Map.of("id",registrationId,"status","ACTIVE");
  }

  @GetMapping("/offers/{offerId}/tax-profiles")
  public List<Map<String,Object>> taxProfiles(@PathVariable long offerId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    return jdbc.queryForList("""
      select tp.id,tp.institution_offer_id,tp.legal_entity_id,
        le.legal_name as legal_seller_name,le.status as legal_seller_status,
        tp.version,tp.service_classification_code,tp.tax_treatment,
        tp.gst_rate_basis_points,tp.cess_rate_basis_points,tp.price_includes_tax,tp.place_of_supply_rule,
        tp.rounding_rule,tp.tax_components::text as tax_components_json,tp.status,
        tp.effective_from,tp.effective_until,tp.approved_at
      from course_tax_profile tp
      left join billing_legal_entity le on le.id=tp.legal_entity_id
      where tp.institution_offer_id=? order by tp.version desc
      """,offerId);
  }

  @PostMapping("/offers/{offerId}/tax-profiles")
  @Transactional
  public Map<String,Object> createTaxProfile(@PathVariable long offerId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    long institutionId=offerInstitution(offerId);
    long entityId=requiredLong(body,"legalEntityId");
    if(!exists("""
      select exists(select 1 from billing_legal_entity e
        where e.id=? and (e.entity_kind in ('PLATFORM','COMPANY')
          or (e.entity_kind='INSTITUTION' and e.institution_id=?)))
      """,entityId,institutionId)) {
      throw bad("Select the platform legal seller or the legal entity belonging to this course offer's institution.");
    }
    String treatment=requiredText(body,"taxTreatment").toUpperCase(Locale.ROOT);
    if(!List.of("TAXABLE","EXEMPT","NIL_RATED","NON_GST","COMPOSITION").contains(treatment)) throw bad("Unsupported tax treatment.");
    int gst=integer(body,"gstRateBasisPoints",0),cess=integer(body,"cessRateBasisPoints",0);
    if(gst<0||gst>10_000||cess<0||cess>10_000) throw bad("Rates must be basis points between 0 and 10000.");
    if("TAXABLE".equals(treatment) && gst==0) throw bad("A taxable profile needs a positive, CA-approved GST rate.");
    if(!"TAXABLE".equals(treatment) && (gst!=0||cess!=0)) throw bad("Non-taxable treatments cannot carry GST or cess rates.");
    String place=requiredText(body,"placeOfSupplyRule").toUpperCase(Locale.ROOT);
    if(!List.of("RECIPIENT_LOCATION","SUPPLIER_LOCATION","CA_APPROVED_CUSTOM").contains(place)) throw bad("Unsupported place-of-supply rule.");
    Instant start=instant(body,"effectiveFrom"),end=optionalInstant(body.get("effectiveUntil"));
    if(end!=null&&!end.isAfter(start)) throw bad("Tax profile end must be after its start.");
    Integer version=jdbc.queryForObject("select coalesce(max(version),0)+1 from course_tax_profile where institution_offer_id=?",Integer.class,offerId);
    long id=jdbc.queryForObject("""
      insert into course_tax_profile(institution_offer_id,legal_entity_id,version,service_classification_code,
        tax_treatment,gst_rate_basis_points,cess_rate_basis_points,price_includes_tax,place_of_supply_rule,
        tax_components,status,effective_from,effective_until,created_by_staff_id)
      values(?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',?,?,?) returning id
      """,Long.class,offerId,entityId,version,optionalText(body.get("serviceClassificationCode")),
        treatment,gst,cess,bool(body,"priceIncludesTax",true),place,jsonObject(body.get("taxComponents")),
        dbInstant(start),dbInstant(end),context.staffId());
    audit(entityId,institutionId,context,"TAX_PROFILE_CREATED","COURSE_TAX_PROFILE",Long.toString(id),
        Map.of("offerId",offerId,"version",version,"treatment",treatment));
    return Map.of("id",id,"version",version,"status","DRAFT");
  }

  @PostMapping("/tax-profiles/{profileId}/submit")
  @Transactional
  public Map<String,Object> submitTaxProfile(@PathVariable long profileId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("select institution_offer_id,legal_entity_id,status from course_tax_profile where id=?",profileId);
    if(row.isEmpty()) throw notFound("Tax profile not found.");
    int n=jdbc.update("update course_tax_profile set status='PENDING_APPROVAL' where id=? and status='DRAFT'",profileId);
    if(n!=1) throw bad("Only a draft tax profile can be submitted.");
    long offerId=((Number)row.get("institution_offer_id")).longValue();
    audit(((Number)row.get("legal_entity_id")).longValue(),offerInstitution(offerId),context,
        "TAX_PROFILE_SUBMITTED","COURSE_TAX_PROFILE",Long.toString(profileId),Map.of());
    return Map.of("id",profileId,"status","PENDING_APPROVAL");
  }

  @PostMapping("/tax-profiles/{profileId}/approve")
  @Transactional
  public Map<String,Object> approveTaxProfile(@PathVariable long profileId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select id,institution_offer_id,legal_entity_id,tax_treatment,effective_from,effective_until,status
      from course_tax_profile where id=? for update
      """,profileId);
    if(row.isEmpty()) throw notFound("Tax profile not found.");
    String currentStatus=String.valueOf(row.get("status")).trim().toUpperCase(Locale.ROOT);
    if(!"PENDING_APPROVAL".equals(currentStatus))
      throw bad("Tax profile #"+profileId+" is currently "+currentStatus+" in the database. Refresh saved profiles; only PENDING_APPROVAL profiles can be approved.");
    long offerId=((Number)row.get("institution_offer_id")).longValue(),entityId=((Number)row.get("legal_entity_id")).longValue();
    Instant start=asInstant(row.get("effective_from")),end=row.get("effective_until")==null?null:asInstant(row.get("effective_until"));
    if(!exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE')",entityId)) {
      throw bad("Activate the legal seller before approving its tax profile.");
    }
    boolean overlap=Boolean.TRUE.equals(jdbc.queryForObject("""
      select exists(select 1 from course_tax_profile where institution_offer_id=? and status='APPROVED' and id<>?
        and (?::timestamptz is null or effective_from < ?::timestamptz)
        and (effective_until is null or effective_until > ?::timestamptz))
      """,Boolean.class,offerId,profileId,dbInstant(end),dbInstant(end),dbInstant(start)));
    if(overlap) throw bad("Another approved tax profile overlaps this effective period.");
    if("TAXABLE".equals(String.valueOf(row.get("tax_treatment")))) {
      java.time.LocalDate date=java.time.LocalDate.ofInstant(start,java.time.ZoneId.of("Asia/Kolkata"));
      if(!exists("""
        select exists(select 1 from billing_gst_registration where legal_entity_id=? and status='ACTIVE'
          and effective_from<=? and (effective_until is null or effective_until>=?))
        """,entityId,date,date)) {
        throw bad("Taxable supplies require an active HRMS-verified GST registration covering the effective date.");
      }
    }
    int changed=jdbc.update("""
      update course_tax_profile set status='APPROVED',approved_by_staff_id=?,approved_at=now()
      where id=? and upper(btrim(status))='PENDING_APPROVAL'
      """,context.staffId(),profileId);
    if(changed!=1) throw bad("Tax profile #"+profileId+" changed while approval was being saved. Refresh the saved profiles and try again.");
    audit(entityId,offerInstitution(offerId),context,"TAX_PROFILE_APPROVED","COURSE_TAX_PROFILE",
        Long.toString(profileId),Map.of("offerId",offerId,"effectiveFrom",start.toString()));
    return Map.of("id",profileId,"status","APPROVED");
  }

  @GetMapping("/discounts")
  public List<Map<String,Object>> discountOffers(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    return jdbc.queryForList("""
      select d.id,d.public_id,d.institution_offer_id,d.code,d.version,d.currency,d.discount_type,
        d.percentage_basis_points,d.flat_amount_paise,d.max_discount_paise,d.min_listed_price_paise,
        d.max_redemptions,d.max_redemptions_per_purchaser,d.effective_from,d.effective_until,d.status,
        d.created_at,d.approved_at,d.retired_at,o.institution_id,t.display_name as institution_name,
        coalesce(nullif(o.title_override,''),p.display_name) as course_name,o.price_paise as offer_price_paise,
        (select count(*) from course_discount_redemption r
          where r.discount_offer_id=d.id and r.status in ('RESERVED','REDEEMED')) as redemption_count
      from course_discount_offer d
      join institution_marketplace_offer o on o.id=d.institution_offer_id
      join institution_tenant t on t.id=o.institution_id
      join curriculum_program p on p.id=o.course_id
      order by d.created_at desc,d.id desc
      """);
  }

  @PostMapping("/discounts")
  @Transactional
  public Map<String,Object> createDiscountOffer(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    long offerId=requiredLong(body,"institutionOfferId");
    long institutionId=offerInstitution(offerId);
    Map<String,Object> offer=one("""
      select o.id,o.price_paise,o.currency,o.status
      from institution_marketplace_offer o
      join institution_tenant t on t.id=o.institution_id and t.status='ACTIVE'
      where o.id=?
      """,offerId);
    if(offer.isEmpty()||"ARCHIVED".equals(String.valueOf(offer.get("status"))))
      throw notFound("Course offer not found or archived.");
    String currency=optionalText(body.get("currency"))==null
        ?String.valueOf(offer.get("currency")).toUpperCase(Locale.ROOT)
        :requiredText(body,"currency").toUpperCase(Locale.ROOT);
    if(!"INR".equals(currency)||!currency.equalsIgnoreCase(String.valueOf(offer.get("currency"))))
      throw bad("Course discounts currently support INR only and must use the offer currency.");
    String code=requiredText(body,"code").toUpperCase(Locale.ROOT);
    if(!code.matches("[A-Z0-9][A-Z0-9_-]{2,39}"))
      throw bad("Offer codes must be 3–40 letters, digits, hyphens or underscores.");
    String type=requiredText(body,"discountType").toUpperCase(Locale.ROOT);
    Integer percentageBps=null;
    Long flatPaise=null;
    if("PERCENTAGE".equals(type)) {
      java.math.BigDecimal percentage;
      try { percentage=new java.math.BigDecimal(requiredText(body,"percentage")); }
      catch(Exception ex) { throw bad("Enter a valid discount percentage."); }
      try { percentageBps=percentage.multiply(java.math.BigDecimal.valueOf(100)).intValueExact(); }
      catch(Exception ex) { throw bad("Percentage must use no more than two decimal places."); }
      if(percentageBps<1||percentageBps>10_000) throw bad("Discount percentage must be greater than 0 and no more than 100.");
    } else if("FLAT".equals(type)) {
      flatPaise=moneyPaise(body.get("flatAmountRupees"),"flatAmountRupees",false);
    } else throw bad("Discount type must be PERCENTAGE or FLAT.");
    Long cap=moneyPaise(body.get("maxDiscountRupees"),"maxDiscountRupees",false);
    Long minimum=moneyPaise(body.get("minListedPriceRupees"),"minListedPriceRupees",true);
    if(minimum==null) minimum=0L;
    Long maxRedemptions=optionalLong(body.get("maxRedemptions"));
    if(maxRedemptions!=null&&maxRedemptions<=0) throw bad("Maximum redemptions must be positive when configured.");
    Integer maxPer=optionalPositiveInteger(body.get("maxRedemptionsPerPurchaser"),"maxRedemptionsPerPurchaser");
    Instant starts=optionalText(body.get("effectiveFrom"))==null?Instant.now():instant(body,"effectiveFrom");
    Instant ends=optionalInstant(body.get("effectiveUntil"));
    if(ends!=null&&!ends.isAfter(starts)) throw bad("Offer end must be after its start.");
    Integer version=jdbc.queryForObject("""
      select coalesce(max(version),0)+1 from course_discount_offer
      where institution_offer_id=? and upper(code)=?
      """,Integer.class,offerId,code);
    long listedPrice=number(offer.get("price_paise"));
    if(listedPrice>0&&listedPrice>=minimum) {
      long discount=CourseDiscountCalculator.calculate(listedPrice,type,percentageBps,flatPaise,cap);
      if(discount>=listedPrice) throw bad("At the current course price this discount would make the balance zero. Reduce the discount or add a valid minimum price.");
    }
    long id=jdbc.queryForObject("""
      insert into course_discount_offer(
        institution_offer_id,code,version,currency,discount_type,percentage_basis_points,
        flat_amount_paise,max_discount_paise,min_listed_price_paise,max_redemptions,
        max_redemptions_per_purchaser,effective_from,effective_until,status,created_by_staff_id
      ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?) returning id
      """,Long.class,offerId,code,version,currency,type,percentageBps,flatPaise,cap,minimum,
        maxRedemptions,maxPer,dbInstant(starts),dbInstant(ends),context.staffId());
    audit(null,institutionId,context,"COURSE_DISCOUNT_CREATED","COURSE_DISCOUNT_OFFER",
        Long.toString(id),Map.of("offerId",offerId,"code",code,"version",version,"discountType",type));
    return Map.of("id",id,"version",version,"status","DRAFT","code",code);
  }

  @PostMapping("/discounts/{discountId}/submit")
  @Transactional
  public Map<String,Object> submitDiscountOffer(@PathVariable long discountId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select d.id,d.institution_offer_id,d.code,d.version,d.status,o.institution_id
      from course_discount_offer d join institution_marketplace_offer o on o.id=d.institution_offer_id
      where d.id=?
      """,discountId);
    if(row.isEmpty()) throw notFound("Discount offer not found.");
    if(!"DRAFT".equals(String.valueOf(row.get("status"))))
      throw bad("Only a draft discount offer can be submitted for approval.");
    int changed=jdbc.update("""
      update course_discount_offer set status='PENDING_APPROVAL',updated_at=now()
      where id=? and status='DRAFT'
      """,discountId);
    if(changed!=1) throw bad("The discount changed while being submitted. Reload and retry.");
    audit(null,number(row.get("institution_id")),context,"COURSE_DISCOUNT_SUBMITTED",
        "COURSE_DISCOUNT_OFFER",Long.toString(discountId),
        Map.of("code",String.valueOf(row.get("code")),"version",number(row.get("version"))));
    return Map.of("id",discountId,"status","PENDING_APPROVAL");
  }

  @PostMapping("/discounts/{discountId}/approve")
  @Transactional
  public Map<String,Object> approveDiscountOffer(@PathVariable long discountId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select d.*,o.institution_id,o.price_paise as offer_price_paise
      from course_discount_offer d
      join institution_marketplace_offer o on o.id=d.institution_offer_id
      join institution_tenant t on t.id=o.institution_id and t.status='ACTIVE'
      where d.id=? for update of d
      """,discountId);
    if(row.isEmpty()) throw notFound("Discount offer not found.");
    if(!"PENDING_APPROVAL".equals(String.valueOf(row.get("status"))))
      throw bad("Only a pending discount offer can be approved.");
    // Serialize approvals for the same course offer so two concurrent approvals cannot
    // both pass an overlap check against an empty set.
    jdbc.queryForObject("select id from institution_marketplace_offer where id=? for update",
        Long.class,number(row.get("institution_offer_id")));
    Instant starts=asInstant(row.get("effective_from"));
    Instant ends=row.get("effective_until")==null?null:asInstant(row.get("effective_until"));
    Boolean overlap=jdbc.queryForObject("""
      select exists(
        select 1 from course_discount_offer other
        where other.institution_offer_id=? and upper(other.code)=upper(?) and other.currency=?
          and other.status='APPROVED' and other.id<>?
          and other.effective_from < coalesce(?::timestamptz,'infinity'::timestamptz)
          and coalesce(other.effective_until,'infinity'::timestamptz)>?::timestamptz
      )
      """,Boolean.class,row.get("institution_offer_id"),row.get("code"),row.get("currency"),
        discountId,dbInstant(ends),dbInstant(starts));
    if(Boolean.TRUE.equals(overlap))
      throw bad("Another approved version of this code overlaps the requested effective period. Retire it or adjust the dates.");
    long listed=number(row.get("offer_price_paise"));
    long minimum=number(row.get("min_listed_price_paise"));
    if(listed>0&&listed>=minimum) {
      long discount=CourseDiscountCalculator.calculate(listed,String.valueOf(row.get("discount_type")),
          nullableInteger(row.get("percentage_basis_points")),nullableLong(row.get("flat_amount_paise")),
          nullableLong(row.get("max_discount_paise")));
      if(discount<=0||discount>=listed)
        throw bad("At the current course price the discount must leave a positive payable balance.");
    }
    int changed=jdbc.update("""
      update course_discount_offer set status='APPROVED',approved_by_staff_id=?,approved_at=now(),updated_at=now()
      where id=? and status='PENDING_APPROVAL'
      """,context.staffId(),discountId);
    if(changed!=1) throw bad("The discount changed while being approved. Reload and retry.");
    audit(null,number(row.get("institution_id")),context,"COURSE_DISCOUNT_APPROVED",
        "COURSE_DISCOUNT_OFFER",Long.toString(discountId),
        Map.of("code",String.valueOf(row.get("code")),"version",number(row.get("version"))));
    return Map.of("id",discountId,"status","APPROVED");
  }

  @PostMapping("/discounts/{discountId}/retire")
  @Transactional
  public Map<String,Object> retireDiscountOffer(@PathVariable long discountId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select d.id,d.code,d.version,d.status,o.institution_id
      from course_discount_offer d join institution_marketplace_offer o on o.id=d.institution_offer_id
      where d.id=?
      """,discountId);
    if(row.isEmpty()) throw notFound("Discount offer not found.");
    String oldStatus=String.valueOf(row.get("status"));
    if(!List.of("DRAFT","PENDING_APPROVAL","APPROVED").contains(oldStatus))
      throw bad("This discount offer cannot be retired from its current status.");
    int changed=jdbc.update("""
      update course_discount_offer set status='RETIRED',retired_by_staff_id=?,retired_at=now(),updated_at=now()
      where id=? and status=?
      """,context.staffId(),discountId,oldStatus);
    if(changed!=1) throw bad("The discount changed while being retired. Reload and retry.");
    audit(null,number(row.get("institution_id")),context,"COURSE_DISCOUNT_RETIRED",
        "COURSE_DISCOUNT_OFFER",Long.toString(discountId),
        Map.of("previousStatus",oldStatus,"code",String.valueOf(row.get("code"))));
    return Map.of("id",discountId,"status","RETIRED");
  }

  @GetMapping("/revenue-share/policies")
  public List<Map<String,Object>> policies(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    return jdbc.queryForList("""
      select p.id,p.institution_offer_id,p.policy_version,p.currency,p.status,p.effective_from,p.effective_until,
        p.contractual_reference,p.notes,p.submitted_at,p.approved_at,o.institution_id,
        t.display_name as institution_name,coalesce(nullif(o.title_override,''),course.display_name) as course_name,
        (select count(*) from revenue_share_policy_recipient r where r.policy_id=p.id) as recipient_count
      from revenue_share_policy p join institution_marketplace_offer o on o.id=p.institution_offer_id
      join institution_tenant t on t.id=o.institution_id join curriculum_program course on course.id=o.course_id
      order by p.created_at desc,p.id desc
      """);
  }

  @GetMapping("/revenue-share/policies/{policyId}")
  public Map<String,Object> policyDetail(@PathVariable long policyId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    Map<String,Object> policy=one("""
      select p.*,o.institution_id,t.display_name as institution_name,
        coalesce(nullif(o.title_override,''),course.display_name) as course_name
      from revenue_share_policy p join institution_marketplace_offer o on o.id=p.institution_offer_id
      join institution_tenant t on t.id=o.institution_id join curriculum_program course on course.id=o.course_id
      where p.id=?
      """,policyId);
    if(policy.isEmpty()) throw notFound("Revenue-share policy not found.");
    policy.put("recipients",jdbc.queryForList("""
      select r.id,r.recipient_type,r.institution_id,r.legal_entity_id,
        coalesce(t.display_name,nullif(le.trade_name,''),le.legal_name) as recipient_name,
        r.calculation_type,r.percentage_basis_points,r.flat_amount_paise
      from revenue_share_policy_recipient r
      left join institution_tenant t on t.id=r.institution_id
      left join billing_legal_entity le on le.id=r.legal_entity_id
      where r.policy_id=? order by recipient_name,r.id
      """,policyId));
    String remainderType=string(policy.get("remainder_recipient_type"));
    Long remainderInstitution=optionalLong(policy.get("remainder_institution_id"));
    Long remainderEntity=optionalLong(policy.get("remainder_legal_entity_id"));
    if("INSTITUTION".equals(remainderType)&&remainderInstitution!=null) {
      policy.put("remainder_recipient_name",value(one("select display_name as name from institution_tenant where id=?",remainderInstitution),"name","Institution #"+remainderInstitution));
    } else if("COMPANY".equals(remainderType)&&remainderEntity!=null) {
      policy.put("remainder_recipient_name",value(one("select legal_name as name from billing_legal_entity where id=?",remainderEntity),"name","Company #"+remainderEntity));
    } else {
      policy.put("remainder_recipient_name","Not assigned");
    }
    return policy;
  }

  @PostMapping("/revenue-share/offers/{offerId}/policies")
  @Transactional
  public Map<String,Object> createPolicy(@PathVariable long offerId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    long institutionId=offerInstitution(offerId);
    Map<String,Object> offer=one("select currency from institution_marketplace_offer where id=?",offerId);
    String offerCurrency=optionalText(offer.get("currency"))==null
        ?"INR":string(offer.get("currency")).toUpperCase(Locale.ROOT);
    String currency=optionalText(body.get("currency"))==null?offerCurrency:requiredText(body,"currency").toUpperCase(Locale.ROOT);
    // Checkout, existing money columns and Razorpay capture amounts are integer paise and INR-only.
    // Reject a policy that would never match the supported checkout/settlement unit.
    if(!"INR".equals(offerCurrency)||!"INR".equals(currency)||!currency.equals(offerCurrency))
      throw bad("Revenue-share policies currently support INR only and must match the course offer currency. The financial ledger stores integer paise; no FX conversion is performed.");
    Map<String,Object> defaults=one("""
      select default_remainder_recipient_type,default_remainder_institution_id,default_remainder_legal_entity_id
      from billing_finance_payout_settings where id=1
      """);
    if(defaults.isEmpty()) throw bad("Set the default remainder recipient in Finance settings before creating a revenue-share policy.");
    Instant start=body.get("effectiveFrom")==null?Instant.now():instant(body,"effectiveFrom");
    Instant end=optionalInstant(body.get("effectiveUntil"));
    if(end!=null&&!end.isAfter(start)) throw bad("Policy end must be after its start.");
    Integer version=jdbc.queryForObject("select coalesce(max(policy_version),0)+1 from revenue_share_policy where institution_offer_id=?",Integer.class,offerId);
    long id=jdbc.queryForObject("""
      insert into revenue_share_policy(institution_offer_id,policy_version,currency,status,effective_from,
        effective_until,contractual_reference,notes,created_by_staff_id,remainder_recipient_type,
        remainder_institution_id,remainder_legal_entity_id)
      values(?,?,?,'DRAFT',?,?,?,?,?,?,?,?) returning id
      """,Long.class,offerId,version,currency,dbInstant(start),dbInstant(end),optionalText(body.get("contractualReference")),
        optionalText(body.get("notes")),context.staffId(),defaults.get("default_remainder_recipient_type"),
        defaults.get("default_remainder_institution_id"),defaults.get("default_remainder_legal_entity_id"));
    audit(null,institutionId,context,"REVENUE_SHARE_POLICY_CREATED","REVENUE_SHARE_POLICY",Long.toString(id),
        Map.of("offerId",offerId,"version",version));
    return Map.of("id",id,"policyVersion",version,"status","DRAFT");
  }

  @PutMapping("/revenue-share/policies/{policyId}/recipients")
  @Transactional
  public Map<String,Object> saveRecipients(@PathVariable long policyId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> policy=one("select institution_offer_id,status,remainder_recipient_type,remainder_institution_id,remainder_legal_entity_id from revenue_share_policy where id=?",policyId);
    if(policy.isEmpty()) throw notFound("Revenue-share policy not found.");
    if(!"DRAFT".equals(String.valueOf(policy.get("status")))) throw bad("Only draft policies can be edited.");
    if(!(body.get("recipients") instanceof List<?> raw)) throw bad("Recipients must be a list.");
    List<Map<String,Object>> normalized=new ArrayList<>();
    Set<String> recipientKeys=new HashSet<>();
    long combinedPercentageBasisPoints=0L;
    for(Object item:raw) {
      if(!(item instanceof Map<?,?> map)) throw bad("Each recipient must be an object.");
      String recipientType=optionalText(map.get("recipientType"))==null?"INSTITUTION"
          :requiredText(map.get("recipientType"),"recipientType").toUpperCase(Locale.ROOT);
      long recipientId=requiredLong(map.get("recipientId")!=null?map.get("recipientId")
          :("COMPANY".equals(recipientType)?map.get("legalEntityId"):map.get("institutionId")),"recipientId");
      if(!List.of("INSTITUTION","COMPANY").contains(recipientType))
        throw bad("Recipient must be an institution or company.");
      if(!recipientKeys.add(recipientType+":"+recipientId))
        throw bad("A beneficiary can appear only once in a revenue-share policy.");
      if("INSTITUTION".equals(recipientType)) {
        if(!exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",recipientId))
          throw bad("Every institution recipient must be active.");
      } else if(!exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",recipientId)) {
        throw bad("Every company recipient must be an active company legal entity.");
      }
      String type=requiredText(map.get("calculationType"),"calculationType").toUpperCase(Locale.ROOT);
      Integer bps=null;Long flat=null;
      if("PERCENTAGE".equals(type)) {
        bps=Math.toIntExact(requiredLong(map.get("percentageBasisPoints"),"percentageBasisPoints"));
        RevenueDistributionCalculator.BeneficiaryShare.percentage(recipientType,recipientId,bps);
        combinedPercentageBasisPoints=Math.addExact(combinedPercentageBasisPoints,bps);
      } else if("FLAT".equals(type)) {
        flat=requiredLong(map.get("flatAmountPaise"),"flatAmountPaise");
        RevenueDistributionCalculator.BeneficiaryShare.flat(recipientType,recipientId,flat);
      } else throw bad("Recipient calculation type must be PERCENTAGE or FLAT.");
      Map<String,Object> normalizedRecipient=new LinkedHashMap<>();
      normalizedRecipient.put("recipientType",recipientType);
      normalizedRecipient.put("recipientId",recipientId);
      normalizedRecipient.put("calculationType",type);
      normalizedRecipient.put("percentageBasisPoints",bps);
      normalizedRecipient.put("flatAmountPaise",flat);
      normalized.add(normalizedRecipient);
    }
    if(combinedPercentageBasisPoints>10_000) throw bad("Combined percentage shares cannot exceed 100% of the distributable pool.");
    String remainderType=optionalText(body.get("remainderRecipientType"))==null
        ?string(policy.get("remainder_recipient_type")):requiredText(body,"remainderRecipientType").toUpperCase(Locale.ROOT);
    Long remainderId=optionalLong(body.get("remainderRecipientId"));
    if(remainderId==null) remainderId="COMPANY".equals(remainderType)
        ?optionalLong(policy.get("remainder_legal_entity_id")):optionalLong(policy.get("remainder_institution_id"));
    if("INSTITUTION".equals(remainderType)) {
      if(remainderId==null||!exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",remainderId))
        throw bad("Select an active institution to receive the remaining balance.");
    } else if("COMPANY".equals(remainderType)) {
      if(remainderId==null||!exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",remainderId))
        throw bad("Select an active company to receive the remaining balance.");
    } else throw bad("The remainder must be assigned to an institution or company.");
    long sourceInstitution=offerInstitution(((Number)policy.get("institution_offer_id")).longValue());
    jdbc.update("""
      update revenue_share_policy set remainder_recipient_type=?,remainder_institution_id=?,remainder_legal_entity_id=?
      where id=? and status='DRAFT'
      """,remainderType,"INSTITUTION".equals(remainderType)?remainderId:null,
        "COMPANY".equals(remainderType)?remainderId:null,policyId);
    jdbc.update("delete from revenue_share_policy_recipient where policy_id=?",policyId);
    for(Map<String,Object> r:normalized) {
      String recipientType=String.valueOf(r.get("recipientType"));long recipientId=((Number)r.get("recipientId")).longValue();
      jdbc.update("""
        insert into revenue_share_policy_recipient(
          policy_id,recipient_type,institution_id,legal_entity_id,calculation_type,percentage_basis_points,flat_amount_paise
        ) values(?,?,?,?,?,?,?)
        """,policyId,recipientType,"INSTITUTION".equals(recipientType)?recipientId:null,
          "COMPANY".equals(recipientType)?recipientId:null,r.get("calculationType"),r.get("percentageBasisPoints"),r.get("flatAmountPaise"));
    }
    audit(null,sourceInstitution,context,"REVENUE_SHARE_RECIPIENTS_SAVED","REVENUE_SHARE_POLICY",
        Long.toString(policyId),Map.of("recipientCount",normalized.size()));
    return Map.of("policyId",policyId,"recipientCount",normalized.size(),"status","DRAFT");
  }

  @PostMapping("/revenue-share/policies/{policyId}/submit")
  @Transactional
  public Map<String,Object> submitPolicy(@PathVariable long policyId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select institution_offer_id,status,remainder_recipient_type,remainder_institution_id,remainder_legal_entity_id
      from revenue_share_policy where id=?
      """,policyId);
    if(row.isEmpty()) throw notFound("Revenue-share policy not found.");
    String remainderType=string(row.get("remainder_recipient_type"));
    Long remainderId="INSTITUTION".equals(remainderType)?optionalLong(row.get("remainder_institution_id"))
        :"COMPANY".equals(remainderType)?optionalLong(row.get("remainder_legal_entity_id")):null;
    boolean validRemainder=remainderId!=null&&("INSTITUTION".equals(remainderType)
        ?exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",remainderId)
        : "COMPANY".equals(remainderType)&&exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY') and institution_id is null)",remainderId));
    if(!validRemainder) throw bad("Choose an active institution or company to receive the remaining balance before submitting this policy.");
    int n=jdbc.update("""
      update revenue_share_policy set status='PENDING_APPROVAL',submitted_by_staff_id=?,submitted_at=now()
      where id=? and status='DRAFT'
      """,context.staffId(),policyId);
    if(n!=1) throw bad("Only a draft policy can be submitted.");
    audit(null,offerInstitution(((Number)row.get("institution_offer_id")).longValue()),context,
        "REVENUE_SHARE_POLICY_SUBMITTED","REVENUE_SHARE_POLICY",Long.toString(policyId),Map.of());
    return Map.of("id",policyId,"status","PENDING_APPROVAL");
  }

  @PostMapping("/revenue-share/policies/{policyId}/approve")
  @Transactional
  public Map<String,Object> approvePolicy(@PathVariable long policyId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select institution_offer_id,status,effective_from,effective_until,policy_version,
        remainder_recipient_type,remainder_institution_id,remainder_legal_entity_id
      from revenue_share_policy where id=?
      """,policyId);
    if(row.isEmpty()) throw notFound("Revenue-share policy not found.");
    if(!"PENDING_APPROVAL".equals(String.valueOf(row.get("status")))) throw bad("Submit the policy before approval.");
    List<Map<String,Object>> policyRecipients=jdbc.queryForList("""
      select recipient_type,institution_id,legal_entity_id,calculation_type,percentage_basis_points,flat_amount_paise
      from revenue_share_policy_recipient where policy_id=?
      """,policyId);
    for(Map<String,Object> recipient:policyRecipients) {
      String recipientType=string(recipient.get("recipient_type"));
      boolean active="INSTITUTION".equals(recipientType)
          ?exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",recipient.get("institution_id"))
          :exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY'))",recipient.get("legal_entity_id"));
      if(!active) throw bad("Every revenue recipient must remain active when a policy is approved.");
    }
    String remainderType=string(row.get("remainder_recipient_type"));
    Long remainderId="INSTITUTION".equals(remainderType)?optionalLong(row.get("remainder_institution_id"))
        :"COMPANY".equals(remainderType)?optionalLong(row.get("remainder_legal_entity_id")):null;
    boolean validRemainder=remainderId!=null&&("INSTITUTION".equals(remainderType)
        ?exists("select exists(select 1 from institution_tenant where id=? and status='ACTIVE')",remainderId)
        : "COMPANY".equals(remainderType)&&exists("select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE' and entity_kind in ('PLATFORM','COMPANY') and institution_id is null)",remainderId));
    if(!validRemainder) throw bad("Revenue-share policy must assign its remainder to an active institution or company before approval.");
    long percentageBasisPoints=policyRecipients.stream()
      .filter(r -> "PERCENTAGE".equals(String.valueOf(r.get("calculation_type"))))
      .mapToLong(r -> ((Number)r.get("percentage_basis_points")).longValue()).sum();
    if(percentageBasisPoints>10_000) throw bad("The combined percentage allocation cannot exceed 100% of the distributable pool.");
    long offerId=((Number)row.get("institution_offer_id")).longValue();
    long sourceInstitution=offerInstitution(offerId);
    Instant start=asInstant(row.get("effective_from")),end=row.get("effective_until")==null?null:asInstant(row.get("effective_until"));
    // Finance configuration can be approved before the offer is published. Keep the
    // exact-revision guard, but do not confuse the offer's lifecycle status with seller readiness.
    boolean currentOffer=exists("""
      select exists(select 1 from institution_marketplace_offer o
        where o.id=? and o.status<>'ARCHIVED'
          and (qe_current_course_offer_revision(o.id) or qe_marketplace_offer_publicly_sellable(o.id)))
      """,offerId);
    if(!currentOffer) throw bad("This revenue-share policy is attached to an archived or obsolete course-offer revision. Select the current course offer before approval.");

    long effectiveTaxProfileCount=count("""
      select count(*) from course_tax_profile tax
      join institution_marketplace_offer o on o.id=tax.institution_offer_id
      join billing_legal_entity le on le.id=tax.legal_entity_id and le.status='ACTIVE'
        and (le.entity_kind in ('PLATFORM','COMPANY') or (le.entity_kind='INSTITUTION' and le.institution_id=o.institution_id))
      where o.id=? and tax.status='APPROVED'
        and tax.effective_from<=? and (tax.effective_until is null or tax.effective_until>?)
      """,offerId,dbInstant(start),dbInstant(start));
    if(effectiveTaxProfileCount==0) {
      throw bad("No approved tax profile effective on this policy's start date is linked to an active legal seller for this exact course offer. Activate the seller, then submit and approve a tax profile for this offer using that seller. Finance setup does not publish the course.");
    }
    if(effectiveTaxProfileCount>1) {
      throw bad("More than one approved tax profile is effective for this course offer at the policy start date. Resolve the overlap before approving revenue share.");
    }
    Map<String,Object> tax=one("""
      select tax.legal_entity_id,tax.tax_treatment from course_tax_profile tax
      join institution_marketplace_offer o on o.id=tax.institution_offer_id
      join billing_legal_entity le on le.id=tax.legal_entity_id and le.status='ACTIVE'
        and (le.entity_kind in ('PLATFORM','COMPANY') or (le.entity_kind='INSTITUTION' and le.institution_id=o.institution_id))
      where o.id=? and tax.status='APPROVED'
        and tax.effective_from<=? and (tax.effective_until is null or tax.effective_until>?)
      order by tax.version desc limit 1
      """,offerId,dbInstant(start),dbInstant(start));
    if("TAXABLE".equals(String.valueOf(tax.get("tax_treatment")))) {
      java.time.LocalDate date=java.time.LocalDate.ofInstant(start,java.time.ZoneId.of("Asia/Kolkata"));
      if(!exists("""
        select exists(select 1 from billing_gst_registration where legal_entity_id=? and status='ACTIVE'
          and effective_from<=? and (effective_until is null or effective_until>=?))
        """,((Number)tax.get("legal_entity_id")).longValue(),date,date)) {
        throw bad("The selected tax profile is linked to an active legal seller, but no active, HRMS-verified GST registration covers the policy start date.");
      }
    }
    boolean overlap=Boolean.TRUE.equals(jdbc.queryForObject("""
      select exists(select 1 from revenue_share_policy where institution_offer_id=? and status='APPROVED' and id<>?
        and (?::timestamptz is null or effective_from < ?::timestamptz)
        and (effective_until is null or effective_until > ?::timestamptz))
      """,Boolean.class,offerId,policyId,dbInstant(end),dbInstant(end),dbInstant(start)));
    if(overlap) throw bad("Another approved revenue-share policy overlaps this effective period.");
    int n=jdbc.update("""
      update revenue_share_policy set status='APPROVED',approved_by_staff_id=?,approved_at=now()
      where id=? and status='PENDING_APPROVAL'
      """,context.staffId(),policyId);
    if(n!=1) throw bad("Policy changed during approval. Reload and try again.");
    audit(null,sourceInstitution,context,"REVENUE_SHARE_POLICY_APPROVED","REVENUE_SHARE_POLICY",
        Long.toString(policyId),Map.of("offerId",offerId,"policyVersion",row.get("policy_version")));
    return Map.of("id",policyId,"status","APPROVED");
  }

  @PostMapping(value="/invoice-template-preview.pdf",produces=MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> previewInvoiceTemplate(
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    Map<String,Object> design=normalizeDesign(body==null?null:body.get("designDefinition"));
    byte[] pdf=invoicePreviewPdfRenderer.renderPreview(design).pdf();
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_PDF)
      .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=quantaedge-invoice-preview.pdf")
      .header(HttpHeaders.CACHE_CONTROL,"no-store, max-age=0")
      .body(pdf);
  }

  @GetMapping("/invoice-templates")
  public List<Map<String,Object>> templates(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    return jdbc.queryForList("""
      select t.id,t.scope,t.legal_entity_id,t.internal_name,v.id as template_version_id,
        v.version_no,v.status as version_status,v.design_definition::text as design_definition_json,
        v.preview_asset_key,v.published_at
      from invoice_template t left join lateral (
        select iv.id,iv.version_no,
          case
            when iv.status in ('PUBLISHED','RETIRED') then iv.status
            when review.review_status='PENDING_APPROVAL' then 'PENDING_APPROVAL'
            when review.review_status='APPROVED' then 'APPROVED'
            else iv.status
          end as status,
          iv.design_definition,iv.preview_asset_key,iv.published_at
        from invoice_template_version iv
        left join invoice_template_version_review review on review.template_version_id=iv.id
        where iv.template_id=t.id order by iv.version_no desc limit 1
      ) v on true order by t.created_at desc,t.id desc
      """);
  }

  @PostMapping("/invoice-templates")
  @Transactional
  public Map<String,Object> createTemplate(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    String scope=requiredText(body,"scope").toUpperCase(Locale.ROOT);
    if(!List.of("PLATFORM","SELLER").contains(scope)) throw bad("Template scope must be PLATFORM or SELLER.");
    Long entityId=optionalLong(body.get("legalEntityId"));
    if("SELLER".equals(scope) && (entityId==null || !exists(
        "select exists(select 1 from billing_legal_entity where id=?)",entityId))) {
      throw bad("Choose a legal seller for a seller-specific invoice template.");
    }
    if("PLATFORM".equals(scope) && entityId!=null) throw bad("A PLATFORM template must not be pinned to a seller entity.");
    Map<String,Object> design=normalizeDesign(body.get("designDefinition"));
    String name=optionalText(body.get("internalName"));
    if(name==null) name="QuantaEdge Course Invoice";
    long templateId=jdbc.queryForObject("""
      insert into invoice_template(scope,legal_entity_id,internal_name,created_by_staff_id)
      values(?,?,?,?) returning id
      """,Long.class,scope,entityId,name,context.staffId());
    long versionId=jdbc.queryForObject("""
      insert into invoice_template_version(template_id,version_no,status,design_definition,created_by_staff_id)
      values(?,1,'DRAFT',?::jsonb,?) returning id
      """,Long.class,templateId,json(design),context.staffId());
    audit(entityId,null,context,"INVOICE_TEMPLATE_CREATED","INVOICE_TEMPLATE",Long.toString(templateId),
        Map.of("scope",scope,"version",1));
    return Map.of("id",templateId,"versionId",versionId,"versionNo",1,"status","DRAFT","designDefinition",design);
  }

  @PostMapping("/invoice-templates/{templateId}/versions")
  @Transactional
  public Map<String,Object> createTemplateVersion(@PathVariable long templateId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> template=one("select scope,legal_entity_id,internal_name from invoice_template where id=?",templateId);
    if(template.isEmpty()) throw notFound("Invoice template not found.");
    Map<String,Object> latest=one("""
      select iv.design_definition::text as definition,iv.status,
        review.review_status
      from invoice_template_version iv
      left join invoice_template_version_review review on review.template_version_id=iv.id
      where iv.template_id=? order by iv.version_no desc limit 1
      """,templateId);
    if(latest.isEmpty()) throw bad("Template has no saved design version to copy.");
    if(!List.of("PUBLISHED","RETIRED").contains(String.valueOf(latest.get("status")))) {
      throw bad("Finish publishing the current template version before creating another draft.");
    }
    String definition=String.valueOf(latest.get("definition"));
    Integer version=jdbc.queryForObject("select coalesce(max(version_no),0)+1 from invoice_template_version where template_id=?",Integer.class,templateId);
    long versionId=jdbc.queryForObject("""
      insert into invoice_template_version(template_id,version_no,status,design_definition,created_by_staff_id)
      values(?,?,'DRAFT',?::jsonb,?) returning id
      """,Long.class,templateId,version,definition,context.staffId());
    Long entityId=optionalLong(template.get("legal_entity_id"));
    audit(entityId,null,context,"INVOICE_TEMPLATE_VERSION_CREATED","INVOICE_TEMPLATE",Long.toString(templateId),Map.of("version",version));
    return Map.of("id",templateId,"versionId",versionId,"versionNo",version,"status","DRAFT");
  }

  @PutMapping("/invoice-templates/{templateId}/versions/{versionNo}")
  @Transactional
  public Map<String,Object> saveTemplateDraft(@PathVariable long templateId,@PathVariable int versionNo,
      @RequestBody Map<String,Object> body,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select v.id,v.status,t.legal_entity_id,review.review_status from invoice_template_version v
      join invoice_template t on t.id=v.template_id
      left join invoice_template_version_review review on review.template_version_id=v.id
      where t.id=? and v.version_no=?
      """,templateId,versionNo);
    if(row.isEmpty()) throw notFound("Invoice-template version not found.");
    if(!"DRAFT".equals(String.valueOf(row.get("status"))) || row.get("review_status")!=null)
      throw bad("A submitted or approved template version is locked. Create a new draft after publication.");
    Map<String,Object> design=normalizeDesign(body.get("designDefinition"));
    jdbc.update("update invoice_template_version set design_definition=?::jsonb,preview_asset_key=null,preview_sha256=null where id=?",
        json(design),row.get("id"));
    audit(optionalLong(row.get("legal_entity_id")),null,context,"INVOICE_TEMPLATE_DRAFT_SAVED",
        "INVOICE_TEMPLATE",Long.toString(templateId),Map.of("version",versionNo));
    return Map.of("id",templateId,"versionNo",versionNo,"status","DRAFT","designDefinition",design);
  }

  @PostMapping("/invoice-templates/{templateId}/versions/{versionNo}/submit")
  @Transactional
  public Map<String,Object> submitTemplate(@PathVariable long templateId,@PathVariable int versionNo,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select v.id,v.status,t.legal_entity_id,v.design_definition::text as definition
      from invoice_template_version v join invoice_template t on t.id=v.template_id
      where t.id=? and v.version_no=?
      """,templateId,versionNo);
    if(row.isEmpty()) throw notFound("Invoice-template version not found.");
    if(!"DRAFT".equals(String.valueOf(row.get("status")))) throw bad("Only a draft template version can be submitted.");
    if(exists("select exists(select 1 from invoice_template_version_review where template_version_id=?)",row.get("id")))
      throw bad("This template version already has a review record and cannot be submitted again.");
    long reviewId=jdbc.queryForObject("""
      insert into invoice_template_version_review(
        template_version_id,review_status,submitted_by_staff_id,design_definition_snapshot
      ) values(?,'PENDING_APPROVAL',?,?::jsonb) returning id
      """,Long.class,row.get("id"),context.staffId(),row.get("definition"));
    jdbc.update("""
      insert into invoice_template_version_review_event(review_id,event_type,actor_staff_id,details)
      values(?,'SUBMITTED',?,?::jsonb)
      """,reviewId,context.staffId(),json(Map.of("templateId",templateId,"versionNo",versionNo)));
    audit(optionalLong(row.get("legal_entity_id")),null,context,"INVOICE_TEMPLATE_SUBMITTED",
        "INVOICE_TEMPLATE",Long.toString(templateId),Map.of("version",versionNo,"reviewId",reviewId));
    return Map.of("id",templateId,"versionNo",versionNo,"status","PENDING_APPROVAL");
  }

  @PostMapping("/invoice-templates/{templateId}/versions/{versionNo}/approve")
  @Transactional
  public Map<String,Object> approveTemplate(@PathVariable long templateId,@PathVariable int versionNo,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select v.id,v.status,t.scope,t.legal_entity_id,
        r.id as review_id,r.review_status,r.design_definition_snapshot=v.design_definition as design_matches
      from invoice_template_version v join invoice_template t on t.id=v.template_id
      join invoice_template_version_review r on r.template_version_id=v.id
      where t.id=? and v.version_no=?
      """,templateId,versionNo);
    if(row.isEmpty()) throw notFound("Invoice template review not found.");
    if(!"DRAFT".equals(String.valueOf(row.get("status")))
        || !"PENDING_APPROVAL".equals(String.valueOf(row.get("review_status"))))
      throw bad("Only a pending template review can be approved.");
    if(!Boolean.TRUE.equals(row.get("design_matches")))
      throw bad("The saved template changed after submission. It cannot be approved.");
    Long entityId=optionalLong(row.get("legal_entity_id"));
    if("SELLER".equals(String.valueOf(row.get("scope"))) && !exists(
        "select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE')",entityId)) {
      throw bad("Activate the legal seller before approving its invoice template.");
    }
    int changed=jdbc.update("""
      update invoice_template_version_review
      set review_status='APPROVED',approved_by_staff_id=?,approved_at=now()
      where id=? and review_status='PENDING_APPROVAL'
      """,context.staffId(),row.get("review_id"));
    if(changed!=1) throw bad("Template review changed during approval. Reload and retry.");
    jdbc.update("""
      insert into invoice_template_version_review_event(review_id,event_type,actor_staff_id,details)
      values(?,'APPROVED',?,?::jsonb)
      """,row.get("review_id"),context.staffId(),json(Map.of("templateId",templateId,"versionNo",versionNo)));
    audit(entityId,null,context,"INVOICE_TEMPLATE_APPROVED","INVOICE_TEMPLATE",Long.toString(templateId),
        Map.of("version",versionNo,"reviewId",row.get("review_id")));
    return Map.of("id",templateId,"versionNo",versionNo,"status","APPROVED");
  }

  @PostMapping("/invoice-templates/{templateId}/versions/{versionNo}/publish")
  @Transactional
  public Map<String,Object> publishTemplate(@PathVariable long templateId,@PathVariable int versionNo,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select v.id,v.status,t.scope,t.legal_entity_id,r.id as review_id,r.review_status,
        r.design_definition_snapshot=v.design_definition as design_matches
      from invoice_template_version v join invoice_template t on t.id=v.template_id
      join invoice_template_version_review r on r.template_version_id=v.id
      where t.id=? and v.version_no=?
      """,templateId,versionNo);
    if(row.isEmpty()) throw notFound("Approved invoice-template version not found.");
    if(!"DRAFT".equals(String.valueOf(row.get("status")))
        || !"APPROVED".equals(String.valueOf(row.get("review_status"))))
      throw bad("Approve the template version before publishing.");
    if(!Boolean.TRUE.equals(row.get("design_matches")))
      throw bad("The saved template no longer matches the approved design snapshot.");
    Long entityId=optionalLong(row.get("legal_entity_id"));
    if("SELLER".equals(String.valueOf(row.get("scope"))) && !exists(
        "select exists(select 1 from billing_legal_entity where id=? and status='ACTIVE')",entityId)) {
      throw bad("Activate the legal seller before publishing its invoice template.");
    }
    jdbc.update("update invoice_template_version set status='RETIRED' where template_id=? and status='PUBLISHED'",templateId);
    int changed=jdbc.update("""
      update invoice_template_version set status='PUBLISHED',published_by_staff_id=?,published_at=now()
      where id=? and status='DRAFT'
      """,context.staffId(),row.get("id"));
    if(changed!=1) throw bad("Template changed during publication. Reload and retry.");
    audit(entityId,null,context,"INVOICE_TEMPLATE_PUBLISHED","INVOICE_TEMPLATE",Long.toString(templateId),
        Map.of("version",versionNo,"reviewId",row.get("review_id")));
    return Map.of("id",templateId,"versionNo",versionNo,"status","PUBLISHED");
  }

  @GetMapping("/documents")
  public List<Map<String,Object>> documents(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    return jdbc.queryForList("""
      select d.id,d.public_id,d.document_type,d.document_number,d.issued_at,d.currency,
        d.taxable_value_paise,d.discount_value_paise,d.tax_value_paise,d.total_value_paise,d.amount_paid_paise,
        d.source_type,d.source_record_key,
        exists(select 1 from fiscal_document_asset a where a.fiscal_document_id=d.id
          and a.asset_type in ('PDF','SIGNED_PDF')) as pdf_available
      from fiscal_document d order by d.issued_at desc,d.id desc limit 250
      """);
  }

  @GetMapping(value="/documents/{documentId}/download",produces=MediaType.APPLICATION_PDF_VALUE)
  @Transactional
  public ResponseEntity<byte[]> downloadFiscalDocument(
      @PathVariable long documentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireOwner(context);
    Map<String,Object> row=one("""
      select d.document_number,a.storage_key,a.sha256,a.byte_length
      from fiscal_document d join fiscal_document_asset a
        on a.fiscal_document_id=d.id and a.asset_type='PDF'
      where d.id=? order by a.created_at desc,a.id desc limit 1
      """,documentId);
    if(row.isEmpty()) throw notFound("The issued PDF for this fiscal document is not available.");
    String key=String.valueOf(row.get("storage_key"));
    byte[] bytes=fiscalDocumentStorage.readPdf(key);
    if(bytes.length!=((Number)row.get("byte_length")).longValue()
        || !FiscalDocumentStorage.sha256(bytes).equals(String.valueOf(row.get("sha256")))) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"The stored invoice did not pass its integrity check.");
    }
    jdbc.update("""
      insert into fiscal_document_event(fiscal_document_id,event_type,idempotency_key,actor_staff_id,details)
      values(?,'DOWNLOAD_AUDITED',?,?,?::jsonb)
      """,documentId,"hrms-invoice-download:"+UUID.randomUUID(),context.staffId(),
        json(Map.of("actorType","QE_PLATFORM_OWNER","actorStaffId",context.staffId())));
    String safeName=String.valueOf(row.get("document_number")).replaceAll("[^A-Za-z0-9_-]","-");
    return ResponseEntity.ok()
      .contentType(MediaType.APPLICATION_PDF)
      .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+safeName+".pdf\"")
      .header(HttpHeaders.CACHE_CONTROL,"private, no-store, max-age=0")
      .header("X-Content-Type-Options","nosniff")
      .body(bytes);
  }

  @GetMapping("/documents/{documentId}")
  public Map<String,Object> documentDetail(@PathVariable long documentId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireOwner(context);
    Map<String,Object> doc=one("""
      select id,public_id,legal_entity_id,gst_registration_id,document_type,document_number,issued_at,currency,
        source_type,source_record_key,supplier_snapshot::text as supplier_snapshot_json,
        recipient_snapshot::text as recipient_snapshot_json,transaction_snapshot::text as transaction_snapshot_json,
        tax_summary_snapshot::text as tax_summary_snapshot_json,taxable_value_paise,discount_value_paise,
        tax_value_paise,total_value_paise,amount_paid_paise,template_version_id
      from fiscal_document where id=?
      """,documentId);
    if(doc.isEmpty()) throw notFound("Fiscal document not found.");
    doc.put("lines",jdbc.queryForList("""
      select line_no,product_reference,description_snapshot,service_classification_code,quantity,unit_price_paise,
        discount_value_paise,taxable_value_paise,tax_components_snapshot::text as tax_components_snapshot_json,
        tax_value_paise,line_total_paise from fiscal_document_line
      where fiscal_document_id=? order by line_no
      """,documentId));
    return doc;
  }

  private List<Map<String,Object>> legalEntityRows() {
    return jdbc.queryForList("""
      select e.id,e.entity_kind,e.institution_id,t.display_name as institution_name,e.legal_name,e.trade_name,
        e.contact_email,e.contact_phone,e.invoice_series_code,e.einvoice_applicability,
        e.einvoice_applicability_reviewed_at,e.einvoice_applicability_review_note,
        e.registered_address::text as registered_address_json,e.status,e.created_at,
        (select count(*) from billing_gst_registration g where g.legal_entity_id=e.id and g.status='ACTIVE') as active_gst_registrations
      from billing_legal_entity e left join institution_tenant t on t.id=e.institution_id
      order by e.created_at desc,e.id desc
      """);
  }

  private Map<String,Object> normalizeDesign(Object raw) {
    Map<?,?> in=raw instanceof Map<?,?> map?map:Map.of();
    String primary=color(in.get("primaryColor"),"#F97316"),accent=color(in.get("accentColor"),"#0F766E");
    String surface=optionalText(in.get("surfaceStyle")); if(surface==null) surface="WARM_PAPER"; surface=surface.toUpperCase(Locale.ROOT);
    if(!List.of("WARM_PAPER","CLEAN_WHITE","SOFT_SLATE").contains(surface)) throw bad("Unsupported invoice surface style.");
    String layout=optionalText(in.get("layout")); if(layout==null) layout="EDITORIAL"; layout=layout.toUpperCase(Locale.ROOT);
    if(!List.of("CLASSIC","EDITORIAL").contains(layout)) throw bad("Unsupported invoice layout.");
    String footer=optionalText(in.get("footerText")); if(footer==null) footer="Thank you for learning with us.";
    if(footer.length()>500) throw bad("Invoice footer cannot exceed 500 characters.");
    return Map.of("schemaVersion",1,"primaryColor",primary,"accentColor",accent,
        "surfaceStyle",surface,"layout",layout,"showLogo",bool(in,"showLogo",true),"footerText",footer);
  }

  private String color(Object raw,String fallback) {
    String value=optionalText(raw); if(value==null) return fallback;
    if(!value.matches("#[0-9A-Fa-f]{6}")) throw bad("Invoice colours must be six-digit hexadecimal values.");
    return value.toUpperCase(Locale.ROOT);
  }

  private AuthContext requireOwner(AuthContext context) {
    return authorization.requirePermission(context,"QE_PLATFORM_OWNER");
  }
  private long offerInstitution(long offerId) {
    Long id=jdbc.queryForObject("select institution_id from institution_marketplace_offer where id=?",Long.class,offerId);
    if(id==null) throw notFound("Course offer not found.");
    return id;
  }
  private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
  private long coalesceAmount(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0L:n; }
  private long count(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0L:n; }
  private Map<String,Object> one(String sql,Object... args) {
    List<Map<String,Object>> rows=jdbc.queryForList(sql,args); return rows.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(rows.getFirst());
  }
  private void audit(Long entityId,Long institutionId,AuthContext actor,String action,String targetType,String targetId,Map<String,Object> details) {
    jdbc.update("""
      insert into billing_financial_audit_log(legal_entity_id,institution_id,actor_staff_id,action,target_type,target_id,details)
      values(?,?,?,?,?,?,?::jsonb)
      """,entityId,institutionId,actor.staffId(),action,targetType,targetId,json(details));
  }
  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value==null?Map.of():value); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Financial configuration could not be serialized.",ex); }
  }
  private String jsonObject(Object value) {
    if(value==null) return "{}";
    if(!(value instanceof Map<?,?>)) throw bad("Expected a JSON object.");
    return json(value);
  }
  private static String requiredText(Map<String,Object> body,String key) { return requiredText(body==null?null:body.get(key),key); }
  private static String requiredText(Object raw,String key) {
    String value=raw==null?"":String.valueOf(raw).trim(); if(value.isBlank()) throw bad(key+" is required."); return value;
  }
  private static String optionalText(Object raw) { if(raw==null)return null; String v=String.valueOf(raw).trim(); return v.isBlank()?null:v; }
  private static long requiredLong(Map<String,Object> body,String key) { return requiredLong(body==null?null:body.get(key),key); }
  private static long requiredLong(Object raw,String key) {
    if(raw instanceof Number n) {
      try { long value=new java.math.BigDecimal(n.toString()).longValueExact(); if(value>0)return value; }
      catch(Exception ignored) { }
    }
    throw bad(key+" must be a positive integer.");
  }
  private static Long moneyPaise(Object raw,String key,boolean allowZero) {
    if(raw==null||String.valueOf(raw).trim().isEmpty()) return null;
    try {
      java.math.BigDecimal amount=new java.math.BigDecimal(String.valueOf(raw).trim()).setScale(2,java.math.RoundingMode.UNNECESSARY);
      if(amount.signum()<0||(!allowZero&&amount.signum()<=0)) throw new ArithmeticException();
      return amount.movePointRight(2).longValueExact();
    } catch(Exception ex) {
      throw bad(key+(allowZero?" must be a non-negative amount with at most two decimals.":" must be a positive amount with at most two decimals."));
    }
  }
  private static Integer optionalPositiveInteger(Object raw,String key) {
    if(raw==null||String.valueOf(raw).trim().isEmpty()) return null;
    try {
      int value=new java.math.BigDecimal(String.valueOf(raw).trim()).intValueExact();
      if(value>0) return value;
    } catch(Exception ignored) { }
    throw bad(key+" must be a positive whole number when configured.");
  }
  private static long number(Object raw) {
    if(raw instanceof Number n) {
      try { return new java.math.BigDecimal(n.toString()).longValueExact(); }
      catch(Exception ex) { throw bad("Expected an integer numeric value."); }
    }
    try { return new java.math.BigDecimal(String.valueOf(raw).trim()).longValueExact(); }
    catch(Exception ex) { throw bad("Expected an integer numeric value."); }
  }
  private static Long nullableLong(Object raw) {
    if(raw==null || String.valueOf(raw).trim().isEmpty()) return null;
    return number(raw);
  }
  private static Integer nullableInteger(Object raw) {
    if(raw==null || String.valueOf(raw).trim().isEmpty()) return null;
    try { return new java.math.BigDecimal(String.valueOf(raw).trim()).intValueExact(); }
    catch(Exception ex) { throw bad("Expected a whole-number integer."); }
  }
  private static Long optionalLong(Object raw) {
    if(raw==null)return null;
    if(raw instanceof Number n) return new java.math.BigDecimal(n.toString()).longValueExact();
    String v=String.valueOf(raw).trim(); return v.isEmpty()?null:Long.valueOf(v);
  }
  private static int integer(Map<?,?> body,String key,int fallback) {
    Object raw=body==null?null:body.get(key); if(raw==null)return fallback;
    try { return new java.math.BigDecimal(raw.toString()).intValueExact(); }
    catch(Exception ex) { throw bad(key+" must be an integer."); }
  }
  private static boolean bool(Map<?,?> body,String key,boolean fallback) {
    Object raw=body==null?null:body.get(key); if(raw==null)return fallback;
    if(raw instanceof Boolean b)return b;
    String v=String.valueOf(raw);
    if("true".equalsIgnoreCase(v))return true;
    if("false".equalsIgnoreCase(v))return false;
    throw bad(key+" must be true or false.");
  }
  private static Instant instant(Map<String,Object> body,String key) {
    try { return Instant.parse(requiredText(body,key)); }
    catch(Exception ex) { throw bad(key+" must be an ISO-8601 UTC timestamp."); }
  }
  private static Instant optionalInstant(Object raw) {
    if(optionalText(raw)==null)return null;
    try{return Instant.parse(String.valueOf(raw));}catch(Exception ex){throw bad("Effective end must be an ISO-8601 UTC timestamp.");}
  }
  private static java.time.OffsetDateTime dbInstant(Instant value) {
    return value == null ? null : java.time.OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC);
  }
  private static Instant asInstant(Object raw) {
    if(raw instanceof java.sql.Timestamp t)return t.toInstant();
    if(raw instanceof java.time.OffsetDateTime d)return d.toInstant();
    return Instant.parse(String.valueOf(raw));
  }
  private static java.time.LocalDate localDate(Map<String,Object> body,String key) {
    try{return java.time.LocalDate.parse(requiredText(body,key));}catch(Exception ex){throw bad(key+" must be an ISO date.");}
  }
  private static java.time.LocalDate optionalLocalDate(Object raw) {
    if(optionalText(raw)==null)return null;
    try{return java.time.LocalDate.parse(String.valueOf(raw));}catch(Exception ex){throw bad("Effective end must be an ISO date.");}
  }
  private static String string(Object raw) {
    return raw==null?"":String.valueOf(raw).trim();
  }
  private static String value(Map<String,Object> row,String key,String fallback) {
    String actual=optionalText(row==null?null:row.get(key));
    return actual==null?fallback:actual;
  }
  private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
  private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND,message); }
}
