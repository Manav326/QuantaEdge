package com.quantaedge.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Server-side course-sale financial orchestration.
 *
 * It snapshots the approved seller/tax/share/template policies at checkout and, only after
 * exact Razorpay capture has been verified, issues the fiscal document and posts share facts.
 * This service intentionally does not decide statutory tax treatment or fetch a rate from
 * client input. Those values must already be approved in HRMS.
 */
@Service
public class CourseOrderFinancialService {
  private static final ZoneId INDIA=ZoneId.of("Asia/Kolkata");
  private static final TypeReference<Map<String,Object>> MAP_TYPE=new TypeReference<>() {};
  private static final TypeReference<List<Map<String,Object>>> LIST_MAP_TYPE=new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final FiscalDocumentRenderer renderer;
  private final FiscalDocumentStorage storage;

  public record CheckoutSnapshot(
      long listedPricePaise,
      long discountPaise,
      Long discountOfferId,
      String discountCode,
      long payablePaise,
      long taxableValuePaise,
      long outputTaxPaise,
      long taxProfileId,
      long legalEntityId,
      Long gstRegistrationId,
      long revenueSharePolicyId,
      long templateVersionId,
      Map<String,Object> taxCalculationSnapshot,
      Map<String,Object> revenueSharePolicySnapshot,
      Map<String,Object> discountSnapshot) {}

  public record FinalizedSale(long fiscalDocumentId,String documentNumber,long allocatedToInstitutionsPaise,
      long platformRemainderPaise) {}

  public CourseOrderFinancialService(
      JdbcTemplate jdbc,ObjectMapper mapper,FiscalDocumentRenderer renderer,FiscalDocumentStorage storage) {
    this.jdbc=jdbc;
    this.mapper=mapper;
    this.renderer=renderer;
    this.storage=storage;
  }

  /** Compatibility overload for callers that do not provide a code. */
  public CheckoutSnapshot prepareCheckout(
      long offerId,long institutionId,long listedPricePaise,String currency,
      String courseTitle,Map<String,Object> buyerSnapshot) {
    return prepareCheckout(offerId,institutionId,listedPricePaise,currency,courseTitle,buyerSnapshot,null);
  }

  /** Resolve only currently effective approved terms; never trust client-supplied amounts. */
  public CheckoutSnapshot prepareCheckout(
      long offerId,long institutionId,long listedPricePaise,String currency,
      String courseTitle,Map<String,Object> buyerSnapshot,String requestedDiscountCode) {
    return prepareCheckout(offerId,institutionId,listedPricePaise,currency,courseTitle,buyerSnapshot,
        requestedDiscountCode,OffsetDateTime.now(ZoneOffset.UTC));
  }

  /**
   * Rebuild a legacy order only against approved terms effective at its original creation time.
   * This is used by guarded HRMS recovery; normal checkouts resolve against the current instant.
   */
  public CheckoutSnapshot prepareCheckout(
      long offerId,long institutionId,long listedPricePaise,String currency,
      String courseTitle,Map<String,Object> buyerSnapshot,String requestedDiscountCode,
      OffsetDateTime asOf) {
    if(asOf==null) asOf=OffsetDateTime.now(ZoneOffset.UTC);
    final OffsetDateTime effectiveAt=asOf;
    if(listedPricePaise<=0) throw new IllegalArgumentException("A paid course needs a positive listed price.");
    if(!"INR".equalsIgnoreCase(currency)) throw new IllegalArgumentException("Financial checkout currently supports INR only.");
    List<Map<String,Object>> products=jdbc.queryForList("""
      select p.id as course_id,p.code as course_code,p.course_type_code
      from institution_marketplace_offer o
      join curriculum_program p on p.id=o.course_id
      where o.id=? and o.institution_id=? and o.course_id=p.id
      """,offerId,institutionId);
    if(products.size()!=1) throw new IllegalStateException(
        "The institution offer does not resolve to exactly one course product. No checkout snapshot was created.");
    Map<String,Object> product=products.getFirst();
    String normalizedCode=normalizeDiscountCode(requestedDiscountCode);
    Long discountOfferId=null;
    long discountPaise=0L;
    Map<String,Object> discountSnapshot=new LinkedHashMap<>();
    discountSnapshot.put("snapshotVersion",1);
    discountSnapshot.put("discountType","NONE");
    discountSnapshot.put("discountPaise",0L);
    if(normalizedCode!=null) {
      List<Map<String,Object>> discounts=jdbc.queryForList("""
        select id,code,version,discount_type,percentage_basis_points,flat_amount_paise,
          max_discount_paise,min_listed_price_paise,max_redemptions,max_redemptions_per_purchaser,
          currency,effective_from,effective_until
        from course_discount_offer
        where institution_offer_id=? and upper(code)=? and currency=? and status='APPROVED'
          and effective_from<=? and (effective_until is null or effective_until>?)
        order by version desc,id desc limit 2
        """,offerId,normalizedCode,currency.toUpperCase(Locale.ROOT),effectiveAt,effectiveAt);
      if(discounts.isEmpty()) throw new IllegalArgumentException(
          "This offer code is not valid for the selected course or is not currently active.");
      if(discounts.size()>1) throw new IllegalStateException(
          "More than one approved version of this code is effective for this course. HRMS must resolve the overlap.");
      Map<String,Object> offer=discounts.getFirst();
      long minimum=number(offer.get("min_listed_price_paise"));
      if(listedPricePaise<minimum) throw new IllegalArgumentException("This offer code requires a higher course list price.");
      String type=string(offer.get("discount_type"));
      Integer bps=nullableInteger(offer.get("percentage_basis_points"));
      Long flat=nullableLong(offer.get("flat_amount_paise"));
      Long cap=nullableLong(offer.get("max_discount_paise"));
      discountPaise=CourseDiscountCalculator.calculate(listedPricePaise,type,bps,flat,cap);
      if(discountPaise<=0||discountPaise>=listedPricePaise)
        throw new IllegalArgumentException("This offer code must leave a positive payable balance. Ask HRMS to review the rule.");
      discountOfferId=number(offer.get("id"));
      discountSnapshot.put("discountOfferId",discountOfferId);
      discountSnapshot.put("code",string(offer.get("code")));
      discountSnapshot.put("version",number(offer.get("version")));
      discountSnapshot.put("discountType",type);
      discountSnapshot.put("percentageBasisPoints",bps);
      discountSnapshot.put("flatAmountPaise",flat);
      discountSnapshot.put("maxDiscountPaise",cap);
      discountSnapshot.put("minListedPricePaise",minimum);
      discountSnapshot.put("maxRedemptions",nullableLong(offer.get("max_redemptions")));
      discountSnapshot.put("maxRedemptionsPerPurchaser",nullableInteger(offer.get("max_redemptions_per_purchaser")));
      discountSnapshot.put("discountPaise",discountPaise);
      discountSnapshot.put("effectiveFrom",String.valueOf(offer.get("effective_from")));
      discountSnapshot.put("effectiveUntil",offer.get("effective_until")==null?null:String.valueOf(offer.get("effective_until")));
    }

    LocalDate effectiveDate=effectiveAt.atZoneSameInstant(INDIA).toLocalDate();
    List<Map<String,Object>> profiles=jdbc.queryForList("""
      select tp.id as tax_profile_id,tp.version as tax_profile_version,tp.legal_entity_id,
        tp.service_classification_code,tp.tax_treatment,tp.gst_rate_basis_points,tp.cess_rate_basis_points,
        tp.price_includes_tax,tp.place_of_supply_rule,tp.tax_components::text as tax_components_json,
        le.entity_kind,le.institution_id as seller_institution_id,le.legal_name,le.trade_name,
        le.registered_address::text as registered_address_json,le.country_code,le.invoice_series_code,
        le.einvoice_applicability,le.einvoice_applicability_reviewed_by_staff_id,le.einvoice_applicability_reviewed_at,
        gr.id as gst_registration_id,gr.gstin as supplier_gstin,gr.state_code as supplier_state_code,
        gr.registration_type as gst_registration_type,gr.registered_address::text as gst_address_json
      from course_tax_profile tp
      join institution_marketplace_offer o on o.id=tp.institution_offer_id and o.institution_id=?
      join billing_legal_entity le on le.id=tp.legal_entity_id and le.status='ACTIVE'
        and (le.entity_kind='PLATFORM' or le.institution_id=o.institution_id)
      left join lateral (
        select g.id,g.gstin,g.state_code,g.registration_type,g.registered_address
        from billing_gst_registration g
        where g.legal_entity_id=le.id and g.status='ACTIVE'
          and g.effective_from<=? and (g.effective_until is null or g.effective_until>=?)
        order by g.effective_from desc,g.id desc limit 1
      ) gr on true
      where tp.institution_offer_id=? and tp.status='APPROVED'
        and tp.effective_from<=? and (tp.effective_until is null or tp.effective_until>?)
      order by tp.version desc,tp.id desc
      limit 2
      """,institutionId,effectiveDate,effectiveDate,offerId,effectiveAt,effectiveAt);
    if(profiles.isEmpty()) throw new IllegalStateException(
        "Checkout is not configured: HRMS must activate the legal seller and approve an effective tax profile for this course.");
    if(profiles.size()>1) throw new IllegalStateException(
        "Checkout is paused because more than one approved tax profile is effective for this course. HRMS must resolve the overlap.");
    Map<String,Object> profile=profiles.getFirst();

    String eInvoiceApplicability=nullableString(profile.get("einvoice_applicability"));
    if ((!"NOT_REQUIRED".equals(eInvoiceApplicability) && !"REQUIRED".equals(eInvoiceApplicability))
        || nullableLong(profile.get("einvoice_applicability_reviewed_by_staff_id"))==null
        || profile.get("einvoice_applicability_reviewed_at")==null) {
      throw new IllegalStateException(
          "Checkout is paused: HRMS must record an e-invoice applicability review for this legal seller before any course sale.");
    }
    if ("REQUIRED".equals(eInvoiceApplicability)) {
      throw new IllegalStateException(
          "Checkout is paused: this legal seller requires e-invoicing. Sales remain blocked until IRP/IRN submission and signed-QR verification are integrated and enabled.");
    }

    String treatmentText=string(profile.get("tax_treatment"));
    CourseTaxCalculator.Treatment treatment;
    try { treatment=CourseTaxCalculator.Treatment.valueOf(treatmentText); }
    catch(Exception ex) { throw new IllegalStateException("The approved tax profile uses an unsupported tax treatment."); }
    int gstRate=integer(profile.get("gst_rate_basis_points"));
    int cessRate=integer(profile.get("cess_rate_basis_points"));
    boolean priceIncludesTax=Boolean.TRUE.equals(profile.get("price_includes_tax"));
    Long gstRegistrationId=nullableLong(profile.get("gst_registration_id"));
    String supplierStateCode=nullableString(profile.get("supplier_state_code"));
    String supplierGstin=nullableString(profile.get("supplier_gstin"));
    if(treatment==CourseTaxCalculator.Treatment.TAXABLE) {
      if(gstRegistrationId==null||supplierGstin==null||supplierStateCode==null)
        throw new IllegalStateException("Taxable checkout requires an active HRMS-verified GST registration for the legal seller.");
      if(!supplierGstin.startsWith(supplierStateCode))
        throw new IllegalStateException("The active seller GSTIN does not match the registration state. HRMS must correct it before sales.");
    }

    Map<String,Object> profileTaxComponents=parseObject(profile.get("tax_components_json"));
    String placeRule=string(profile.get("place_of_supply_rule"));
    String buyerStateCode=resolveStateCode(buyerSnapshot,profileTaxComponents,placeRule,supplierStateCode,treatment);
    if(treatment==CourseTaxCalculator.Treatment.TAXABLE && buyerStateCode==null)
      throw new IllegalStateException("A billing state is required to determine the GST place of supply. Update the purchaser's billing details before checkout.");
    if("CA_APPROVED_CUSTOM".equals(placeRule)
        && !validStateCode(profileTaxComponents.get("caApprovedPlaceOfSupplyStateCode"))) {
      throw new IllegalStateException("This tax profile uses a custom place-of-supply rule that has not been configured for automated checkout.");
    }

    CourseTaxCalculator.Result quote=CourseTaxCalculator.quote(
        listedPricePaise,discountPaise,treatment,gstRate,cessRate,priceIncludesTax);
    List<Map<String,Object>> taxComponents=taxComponents(
        quote,profileTaxComponents,placeRule,supplierStateCode,buyerStateCode);
    Map<String,Object> supplierSnapshot=new LinkedHashMap<>();
    supplierSnapshot.put("legalEntityId",number(profile.get("legal_entity_id")));
    supplierSnapshot.put("entityKind",string(profile.get("entity_kind")));
    supplierSnapshot.put("legalName",string(profile.get("legal_name")));
    supplierSnapshot.put("tradeName",nullableString(profile.get("trade_name")));
    supplierSnapshot.put("registeredAddress",parseObject(profile.get("registered_address_json")));
    supplierSnapshot.put("countryCode",string(profile.get("country_code")));
    supplierSnapshot.put("invoiceSeriesCode",string(profile.get("invoice_series_code")));
    supplierSnapshot.put("gstRegistrationId",gstRegistrationId);
    supplierSnapshot.put("gstin",supplierGstin);
    supplierSnapshot.put("stateCode",supplierStateCode);
    supplierSnapshot.put("registrationType",nullableString(profile.get("gst_registration_type")));
    if(profile.get("gst_address_json")!=null)
      supplierSnapshot.put("gstRegisteredAddress",parseObject(profile.get("gst_address_json")));

    List<Map<String,Object>> templateRows=jdbc.queryForList("""
      select v.id as template_version_id,v.design_definition::text as design_definition_json,
        t.scope,t.internal_name
      from invoice_template t
      join invoice_template_version v on v.template_id=t.id
        and v.published_at is not null and v.published_at<=?
      where (t.scope='SELLER' and t.legal_entity_id=?)
         or (t.scope='PLATFORM' and t.legal_entity_id is null)
      order by case when t.scope='SELLER' then 0 else 1 end,v.published_at desc,v.id desc
      limit 1
      """,effectiveAt,number(profile.get("legal_entity_id")));
    if(templateRows.isEmpty()) throw new IllegalStateException(
        "Checkout is not ready: HRMS must publish an invoice template before this course can be sold.");
    Map<String,Object> selectedTemplate=templateRows.getFirst();
    long templateVersionId=number(selectedTemplate.get("template_version_id"));
    Map<String,Object> designSnapshot=parseObject(selectedTemplate.get("design_definition_json"));

    List<Map<String,Object>> policies=jdbc.queryForList("""
      select id,policy_version,currency,effective_from,effective_until
      from revenue_share_policy
      where institution_offer_id=? and status='APPROVED'
        and currency=? and effective_from<=? and (effective_until is null or effective_until>?)
      order by policy_version desc,id desc limit 2
      """,offerId,currency.toUpperCase(Locale.ROOT),effectiveAt,effectiveAt);
    if(policies.isEmpty()) throw new IllegalStateException(
        "Checkout is not ready: HRMS must approve an effective revenue-share policy for this course.");
    if(policies.size()>1) throw new IllegalStateException(
        "Checkout is paused because multiple approved revenue-share policies are effective. HRMS must resolve the overlap.");
    Map<String,Object> policy=policies.getFirst();
    long policyId=number(policy.get("id"));
    List<Map<String,Object>> recipientRows=jdbc.queryForList("""
      select r.id as policy_recipient_id,r.institution_id,t.display_name as institution_name,t.status as institution_status,
        r.calculation_type,r.percentage_basis_points,r.flat_amount_paise
      from revenue_share_policy_recipient r
      join institution_tenant t on t.id=r.institution_id
      where r.policy_id=? order by t.display_name,r.id
      """,policyId);
    if(recipientRows.isEmpty()) throw new IllegalStateException("The approved revenue-share policy has no institution recipients.");
    List<Map<String,Object>> recipientSnapshots=new ArrayList<>();
    List<RevenueDistributionCalculator.Share> validationShares=new ArrayList<>();
    long percentageBasisPoints=0;
    for(Map<String,Object> recipient:recipientRows) {
      if(!"ACTIVE".equals(string(recipient.get("institution_status"))))
        throw new IllegalStateException("An approved share recipient is not an active institution. HRMS must update the policy.");
      long institutionRecipientId=number(recipient.get("institution_id"));
      String calculationType=string(recipient.get("calculation_type"));
      Integer bps=nullableInteger(recipient.get("percentage_basis_points"));
      Long flat=nullableLong(recipient.get("flat_amount_paise"));
      Map<String,Object> snapshot=new LinkedHashMap<>();
      snapshot.put("policyRecipientId",number(recipient.get("policy_recipient_id")));
      snapshot.put("institutionId",institutionRecipientId);
      snapshot.put("institutionName",string(recipient.get("institution_name")));
      snapshot.put("calculationType",calculationType);
      snapshot.put("percentageBasisPoints",bps);
      snapshot.put("flatAmountPaise",flat);
      recipientSnapshots.add(snapshot);
      if("PERCENTAGE".equals(calculationType)) {
        if(bps==null||bps<1||bps>10_000) throw new IllegalStateException("Approved revenue-share percentage is invalid.");
        percentageBasisPoints=Math.addExact(percentageBasisPoints,bps);
        validationShares.add(RevenueDistributionCalculator.Share.percentage(institutionRecipientId,bps));
      } else if("FLAT".equals(calculationType)) {
        if(flat==null||flat<=0) throw new IllegalStateException("Approved flat revenue share is invalid.");
        validationShares.add(RevenueDistributionCalculator.Share.flat(institutionRecipientId,flat));
      } else throw new IllegalStateException("Approved revenue-share calculation type is unsupported.");
    }
    if(percentageBasisPoints>10_000)
      throw new IllegalStateException("The approved percentage shares exceed 100% of the distributable pool.");

    long pool=quote.customerPayablePaise()-quote.outputTaxPaise();
    long estimatedAllocated=0;
    for(RevenueDistributionCalculator.Share share:validationShares) {
      long amount=share.calculationType()==RevenueDistributionCalculator.CalculationType.PERCENTAGE
          ?percentageOf(pool,share.percentageBasisPoints())
          :share.flatAmountPaise();
      estimatedAllocated=Math.addExact(estimatedAllocated,amount);
    }
    if(estimatedAllocated>pool)
      throw new IllegalStateException("The approved flat and percentage shares exceed the after-tax pool for this course price. HRMS must adjust the policy.");

    Map<String,Object> taxSnapshot=new LinkedHashMap<>();
    taxSnapshot.put("snapshotVersion",1);
    taxSnapshot.put("taxProfileId",number(profile.get("tax_profile_id")));
    taxSnapshot.put("taxProfileVersion",number(profile.get("tax_profile_version")));
    taxSnapshot.put("taxTreatment",treatment.name());
    taxSnapshot.put("serviceClassificationCode",nullableString(profile.get("service_classification_code")));
    taxSnapshot.put("gstRateBasisPoints",gstRate);
    taxSnapshot.put("cessRateBasisPoints",cessRate);
    taxSnapshot.put("priceIncludesTax",priceIncludesTax);
    taxSnapshot.put("placeOfSupplyRule",placeRule);
    taxSnapshot.put("supplierStateCode",supplierStateCode);
    taxSnapshot.put("buyerStateCode",buyerStateCode);
    taxSnapshot.put("listedPricePaise",quote.listedPricePaise());
    taxSnapshot.put("discountOfferId",discountOfferId==null?0L:discountOfferId);
    taxSnapshot.put("discountCode",normalizedCode);
    taxSnapshot.put("discountPaise",quote.discountPaise());
    taxSnapshot.put("discountedPricePaise",quote.discountedPricePaise());
    taxSnapshot.put("taxableValuePaise",quote.taxableValuePaise());
    taxSnapshot.put("outputTaxPaise",quote.outputTaxPaise());
    taxSnapshot.put("customerPayablePaise",quote.customerPayablePaise());
    taxSnapshot.put("components",taxComponents);
    taxSnapshot.put("courseId",number(product.get("course_id")));
    taxSnapshot.put("courseCode",string(product.get("course_code")));
    taxSnapshot.put("courseTypeCode",string(product.get("course_type_code")));
    taxSnapshot.put("courseTitle",courseTitle);
    // Tax treatment and SAC remain the explicit approved HRMS offer/tax-profile decision;
    // course type is immutable invoice provenance, not an implicit tax-rate selector.
    taxSnapshot.put("institutionId",institutionId);
    taxSnapshot.put("institutionOfferId",offerId);
    taxSnapshot.put("institutionName",institutionName(institutionId));
    taxSnapshot.put("supplierSnapshot",supplierSnapshot);
    taxSnapshot.put("invoiceTemplateVersionId",templateVersionId);
    taxSnapshot.put("invoiceTemplateDesignSnapshot",designSnapshot);
    taxSnapshot.put("invoiceTemplateScope",string(selectedTemplate.get("scope")));
    taxSnapshot.put("invoiceTemplateName",string(selectedTemplate.get("internal_name")));

    Map<String,Object> shareSnapshot=new LinkedHashMap<>();
    shareSnapshot.put("snapshotVersion",1);
    shareSnapshot.put("policyId",policyId);
    shareSnapshot.put("policyVersion",number(policy.get("policy_version")));
    shareSnapshot.put("currency",string(policy.get("currency")));
    shareSnapshot.put("effectiveFrom",String.valueOf(policy.get("effective_from")));
    shareSnapshot.put("effectiveUntil",policy.get("effective_until")==null?null:String.valueOf(policy.get("effective_until")));
    shareSnapshot.put("recipients",recipientSnapshots);

    return new CheckoutSnapshot(
        listedPricePaise,discountPaise,discountOfferId,normalizedCode,quote.customerPayablePaise(),
        quote.taxableValuePaise(),quote.outputTaxPaise(),number(profile.get("tax_profile_id")),
        number(profile.get("legal_entity_id")),gstRegistrationId,policyId,templateVersionId,
        taxSnapshot,shareSnapshot,discountSnapshot);
  }

  /**
   * Atomically reserves an approved discount against concurrent checkouts. The policy row lock
   * serializes redemption-cap checks for that code before a reservation is inserted.
   */
  @Transactional
  public void reserveDiscountRedemption(
      long discountOfferId,long localOrderId,long purchaserUserAccountId,long institutionOfferId,
      String code,long listedPricePaise,long discountAmountPaise) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,institution_offer_id,code,currency,discount_type,percentage_basis_points,
        flat_amount_paise,max_discount_paise,min_listed_price_paise,max_redemptions,
        max_redemptions_per_purchaser
      from course_discount_offer
      where id=? and status='APPROVED'
        and effective_from<=now() and (effective_until is null or effective_until>now())
      for update
      """,discountOfferId);
    if(rows.size()!=1) throw new IllegalStateException(
        "This offer code is no longer active. Refresh the marketplace and retry checkout.");
    Map<String,Object> rule=rows.getFirst();
    String normalized=code==null?"":code.trim().toUpperCase(Locale.ROOT);
    if(number(rule.get("institution_offer_id"))!=institutionOfferId
        || !normalized.equals(string(rule.get("code")))
        || !"INR".equalsIgnoreCase(string(rule.get("currency")))) {
      throw new IllegalStateException("The approved discount no longer matches this course order.");
    }
    long minimum=number(rule.get("min_listed_price_paise"));
    if(listedPricePaise<minimum) throw new IllegalArgumentException(
        "This offer code requires a higher course list price.");
    long expectedDiscount=CourseDiscountCalculator.calculate(
        listedPricePaise,string(rule.get("discount_type")),
        nullableInteger(rule.get("percentage_basis_points")),nullableLong(rule.get("flat_amount_paise")),
        nullableLong(rule.get("max_discount_paise")));
    if(expectedDiscount!=discountAmountPaise||discountAmountPaise<=0||discountAmountPaise>=listedPricePaise) {
      throw new IllegalStateException("The discount amount does not match the currently approved offer terms.");
    }
    Long maxRedemptions=nullableLong(rule.get("max_redemptions"));
    Integer maxPerPurchaser=nullableInteger(rule.get("max_redemptions_per_purchaser"));
    if(maxRedemptions!=null) {
      Long used=jdbc.queryForObject("""
        select count(*) from course_discount_redemption
        where discount_offer_id=? and status in ('RESERVED','REDEEMED')
        """,Long.class,discountOfferId);
      if(used!=null&&used>=maxRedemptions) throw new IllegalArgumentException(
          "This offer code has reached its redemption limit.");
    }
    if(maxPerPurchaser!=null) {
      Long usedByPurchaser=jdbc.queryForObject("""
        select count(*) from course_discount_redemption
        where discount_offer_id=? and purchaser_user_account_id=?
          and status in ('RESERVED','REDEEMED')
        """,Long.class,discountOfferId,purchaserUserAccountId);
      if(usedByPurchaser!=null&&usedByPurchaser>=maxPerPurchaser) throw new IllegalArgumentException(
          "You have already used the maximum number of times allowed for this offer code.");
    }
    jdbc.update("""
      insert into course_discount_redemption(
        discount_offer_id,course_payment_order_id,purchaser_user_account_id,institution_offer_id,
        code_snapshot,discount_amount_paise,status
      ) values(?,?,?,?,?,?,'RESERVED')
      """,discountOfferId,localOrderId,purchaserUserAccountId,institutionOfferId,normalized,discountAmountPaise);
  }

  /**
   * Commit paid course access independently of invoice/share bookkeeping. Caller must have
   * verified the exact Razorpay order/payment/amount/currency and the target learner's institution
   * membership before calling this method. A later fiscal failure must never roll back access.
   */
  public record CapturedAccessGrant(long purchaseId,long courseId,long institutionId,
      long targetStudentId,Long purchaserUserId,String recipientType) {}

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CapturedAccessGrant grantAccessForCapturedPayment(
      long localOrderId,String providerOrderId,String paymentId,String signature,long capturedAmountPaise) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,student_id,purchaser_user_id,recipient_type,course_id,institution_id,
        institution_offer_id,course_identity_id,identity_contract_version,offer_price_paise,
        currency,provider_order_id,provider_payment_id,status
      from course_payment_order where id=? for update
      """,localOrderId);
    if(rows.isEmpty()) throw new IllegalStateException("The saved course order no longer exists.");
    Map<String,Object> order=rows.getFirst();
    long targetStudentId=number(order.get("student_id"));
    long courseId=number(order.get("course_id"));
    long institutionId=number(order.get("institution_id"));
    String currency=string(order.get("currency")).toUpperCase(Locale.ROOT);
    String status=string(order.get("status"));
    String savedOrderId=string(order.get("provider_order_id"));
    String savedPaymentId=string(order.get("provider_payment_id"));
    if(!providerOrderId.equals(savedOrderId)
        ||number(order.get("offer_price_paise"))!=capturedAmountPaise
        ||!currency.equals("INR")
        ||!currency.equalsIgnoreCase(string(order.get("currency")))) {
      throw new IllegalStateException("The captured payment no longer matches this exact saved course order.");
    }
    if(!savedPaymentId.isBlank()&&!savedPaymentId.equals(paymentId))
      throw new IllegalStateException("A different provider payment is already linked to this course order.");
    if(!List.of("PENDING","EXPIRED","VERIFIED").contains(status))
      throw new IllegalStateException("The course order is not in a state that can grant paid access.");
    if(order.get("institution_offer_id")==null)
      throw new IllegalStateException("The saved order has no institution-specific offer.");
    Long purchaserUserId=order.get("purchaser_user_id")==null?null:number(order.get("purchaser_user_id"));
    String recipientType=string(order.get("recipient_type"));
    long purchaseId;
    List<Map<String,Object>> existingForOrder=jdbc.queryForList("""
      select id,provider_payment_id from course_purchase
      where institution_id=? and order_id=?
      """,institutionId,localOrderId);
    if(!existingForOrder.isEmpty()) {
      Map<String,Object> existing=existingForOrder.getFirst();
      if(!paymentId.equals(string(existing.get("provider_payment_id"))))
        throw new IllegalStateException("This course order is already linked to a different purchase payment.");
      purchaseId=number(existing.get("id"));
    } else {
      try {
        purchaseId=jdbc.queryForObject("""
          insert into course_purchase(
            student_id,course_id,order_id,provider_payment_id,amount_paise,currency,purchaser_user_id,
            institution_id,institution_offer_id,course_identity_id,identity_contract_version
          ) values(?,?,?,?,?,?,?,?,?,?,?) returning id
          """,Long.class,targetStudentId,courseId,localOrderId,paymentId,capturedAmountPaise,currency,
          purchaserUserId,institutionId,number(order.get("institution_offer_id")),
          order.get("course_identity_id") instanceof Number identity?identity.longValue():null,
          order.get("identity_contract_version") instanceof Number version?version.intValue():0);
      } catch(DuplicateKeyException duplicate) {
        List<Map<String,Object>> prior=jdbc.queryForList("""
          select id,provider_payment_id from course_purchase
          where institution_id=? and (order_id=? or (student_id=? and course_id=?))
          order by id limit 1
          """,institutionId,localOrderId,targetStudentId,courseId);
        if(prior.isEmpty()||!paymentId.equals(string(prior.getFirst().get("provider_payment_id"))))
          throw new IllegalStateException("This learner's ownership already has a different recorded purchase. Finance must resolve the duplicate charge; the saved order remains auditable.",duplicate);
        purchaseId=number(prior.getFirst().get("id"));
      }
    }

    Long entitlementOwnerUser=purchaserUserId!=null&&"STUDENT".equals(recipientType)?purchaserUserId:null;
    jdbc.update("""
      insert into course_entitlement(
        course_id,source_purchase_id,holder_user_id,holder_student_id,granted_by_user_id,institution_id,
        course_identity_id,identity_contract_version
      ) values(?,?,?,?,?,?,?,?)
      on conflict(source_purchase_id) do nothing
      """,courseId,purchaseId,entitlementOwnerUser,targetStudentId,purchaserUserId,institutionId,
      order.get("course_identity_id") instanceof Number identity?identity.longValue():null,
      order.get("identity_contract_version") instanceof Number version?version.intValue():0);
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',now(),null,now(),now())
      on conflict(institution_id,student_id,program_id) do update
        set status='ACTIVE',
          enrolled_at=case when student_program_enrollment.status='ACTIVE'
            then student_program_enrollment.enrolled_at else now() end,
          ended_at=null,updated_at=now()
      """,targetStudentId,courseId,institutionId);
    jdbc.update("""
      insert into student_track_enrollment(student_id,subject_id,institution_id,status)
      select ?,s.id,?,'ACTIVE'
      from curriculum_program_subject ps
      join curriculum_program course_scope_program
        on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or
          (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      where ps.program_id=?
      on conflict(institution_id,student_id,subject_id) do update
        set status='ACTIVE',updated_at=now()
      """,targetStudentId,institutionId,courseId);
    return new CapturedAccessGrant(purchaseId,courseId,institutionId,targetStudentId,purchaserUserId,recipientType);
  }

  /** Parent request housekeeping is separate so it can never roll back paid course access. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markParentPurchaseRequestPurchased(
      long purchaserUserId,long targetStudentId,long institutionId,long courseId) {
    jdbc.update("""
      update parent_course_purchase_request
      set status='PURCHASED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
      where guardian_user_id=? and student_id=? and institution_id=? and course_id=? and status='PENDING'
      """,purchaserUserId,purchaserUserId,targetStudentId,institutionId,courseId);
  }

  /**
   * Finalize the fiscal document and revenue-share allocations in a separate transaction after
   * course access is already committed. Failure here must not undo an exact captured payment's access.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public FinalizedSale finalizeCapturedSale(long localOrderId,String paymentId,String signature,long capturedAmountPaise) {
    List<Map<String,Object>> orders=jdbc.queryForList("""
      select id,student_id,purchaser_user_id,target_student_id,recipient_type,course_id,institution_id,
        institution_offer_id,offer_price_paise,currency,listed_price_paise,discount_offer_id,discount_reference,
        discount_amount_paise,discount_snapshot::text as discount_snapshot_json,
        tax_profile_id,taxable_value_paise,output_tax_paise,tax_calculation_snapshot::text as tax_snapshot_json,
        buyer_snapshot::text as buyer_snapshot_json,revenue_share_policy_id,
        revenue_share_policy_snapshot::text as share_snapshot_json,fiscal_document_id
      from course_payment_order where id=? for update
      """,localOrderId);
    if(orders.isEmpty()) throw new IllegalStateException("The captured course order could not be found.");
    Map<String,Object> order=orders.getFirst();
    long payable=number(order.get("offer_price_paise"));
    if(capturedAmountPaise!=payable)
      throw new IllegalArgumentException("The captured payment does not equal this course order's server-calculated payable amount.");
    Map<String,Object> taxSnapshot=parseObject(order.get("tax_snapshot_json"));
    Map<String,Object> shareSnapshot=parseObject(order.get("share_snapshot_json"));
    Map<String,Object> buyerSnapshot=parseObject(order.get("buyer_snapshot_json"));
    if(taxSnapshot.isEmpty()||shareSnapshot.isEmpty())
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
          "This order has no locked tax/share policy snapshot. Do not issue a document against incomplete financial facts.");
    if(number(taxSnapshot.get("customerPayablePaise"))!=capturedAmountPaise
        ||number(taxSnapshot.get("taxProfileId"))!=number(order.get("tax_profile_id"))
        ||number(shareSnapshot.get("policyId"))!=number(order.get("revenue_share_policy_id"))) {
      throw new IllegalStateException("Course order financial snapshots do not match the captured order.");
    }

    long listed=number(order.get("listed_price_paise"));
    long discount=number(order.get("discount_amount_paise"));
    Long storedOfferId=nullableLong(order.get("discount_offer_id"));
    String storedCode=nullableString(order.get("discount_reference"));
    Map<String,Object> storedDiscountSnapshot=parseObject(order.get("discount_snapshot_json"));
    if(storedOfferId==null) {
      if(discount!=0||storedCode!=null||number(taxSnapshot.get("discountOfferId"))!=0)
        throw new IllegalStateException("This course order has an inconsistent discount snapshot.");
    } else if(number(taxSnapshot.get("discountOfferId"))!=storedOfferId
        ||!string(taxSnapshot.get("discountCode")).equals(storedCode)
        ||number(storedDiscountSnapshot.get("discountOfferId"))!=storedOfferId
        ||!string(storedDiscountSnapshot.get("code")).equals(storedCode)
        ||number(storedDiscountSnapshot.get("discountPaise"))!=discount) {
      throw new IllegalStateException("The approved discount snapshot does not match the saved course order.");
    }
    CourseTaxCalculator.Treatment treatment=CourseTaxCalculator.Treatment.valueOf(string(taxSnapshot.get("taxTreatment")));
    int gstRate=integer(taxSnapshot.get("gstRateBasisPoints"));
    int cessRate=integer(taxSnapshot.get("cessRateBasisPoints"));
    boolean includesTax=Boolean.TRUE.equals(taxSnapshot.get("priceIncludesTax"));
    CourseTaxCalculator.Result verified=CourseTaxCalculator.calculate(
        listed,discount,treatment,gstRate,cessRate,includesTax,capturedAmountPaise);
    long storedTax=number(order.get("output_tax_paise"));
    long storedTaxable=number(order.get("taxable_value_paise"));
    if(verified.outputTaxPaise()!=storedTax||verified.taxableValuePaise()!=storedTaxable
        ||number(taxSnapshot.get("outputTaxPaise"))!=storedTax
        ||number(taxSnapshot.get("taxableValuePaise"))!=storedTaxable)
      throw new IllegalStateException("Captured payment does not reconcile to the stored, approved tax calculation.");

    List<Map<String,Object>> recipientSnapshots=asMapList(shareSnapshot.get("recipients"));
    List<RevenueDistributionCalculator.Share> shares=new ArrayList<>();
    Map<Long,Map<String,Object>> recipientByInstitution=new LinkedHashMap<>();
    for(Map<String,Object> recipient:recipientSnapshots) {
      long institutionId=number(recipient.get("institutionId"));
      String type=string(recipient.get("calculationType"));
      RevenueDistributionCalculator.Share share;
      if("PERCENTAGE".equals(type))
        share=RevenueDistributionCalculator.Share.percentage(institutionId,integer(recipient.get("percentageBasisPoints")));
      else if("FLAT".equals(type))
        share=RevenueDistributionCalculator.Share.flat(institutionId,number(recipient.get("flatAmountPaise")));
      else throw new IllegalStateException("Stored revenue-share snapshot contains an unsupported share type.");
      shares.add(share);
      if(recipientByInstitution.put(institutionId,recipient)!=null)
        throw new IllegalStateException("Stored revenue-share snapshot contains a duplicate institution.");
    }
    RevenueDistributionCalculator.Result distribution=RevenueDistributionCalculator.calculate(
        RevenueDistributionCalculator.PaymentConfirmation.VERIFIED_CAPTURED,
        capturedAmountPaise,storedTax,shares);

    String currency=string(order.get("currency")).toUpperCase(Locale.ROOT);
    long orderId=number(order.get("id"));
    long policyId=number(shareSnapshot.get("policyId"));
    for(RevenueDistributionCalculator.Allocation allocation:distribution.allocations()) {
      Map<String,Object> recipient=recipientByInstitution.get(allocation.institutionId());
      if(recipient==null) throw new IllegalStateException("The revenue allocation recipient snapshot is incomplete.");
      long recipientRowId=number(recipient.get("policyRecipientId"));
      String idem="course-order:"+orderId+":revenue-share:"+allocation.institutionId()+":v1";
      List<Map<String,Object>> existing=jdbc.queryForList("""
        select id,policy_id,policy_recipient_id,allocated_amount_paise,actual_paid_paise,output_tax_paise
        from revenue_share_allocation where course_payment_order_id=? and institution_id=?
        """,orderId,allocation.institutionId());
      long allocationId;
      if(!existing.isEmpty()) {
        Map<String,Object> row=existing.getFirst();
        if(number(row.get("policy_id"))!=policyId
            ||number(row.get("policy_recipient_id"))!=recipientRowId
            ||number(row.get("allocated_amount_paise"))!=allocation.amountPaise()
            ||number(row.get("actual_paid_paise"))!=capturedAmountPaise
            ||number(row.get("output_tax_paise"))!=storedTax)
          throw new IllegalStateException("An existing institutional allocation conflicts with this captured sale.");
        allocationId=number(row.get("id"));
      } else {
        Map<String,Object> calc=new LinkedHashMap<>();
        calc.put("policyId",policyId);
        calc.put("policyVersion",shareSnapshot.get("policyVersion"));
        calc.put("institutionId",allocation.institutionId());
        calc.put("calculationType",allocation.calculationType().name());
        calc.put("percentageBasisPoints",recipient.get("percentageBasisPoints"));
        calc.put("flatAmountPaise",recipient.get("flatAmountPaise"));
        calc.put("actualPaidPaise",capturedAmountPaise);
        calc.put("outputTaxPaise",storedTax);
        calc.put("distributablePoolPaise",distribution.distributablePoolPaise());
        calc.put("allocatedAmountPaise",allocation.amountPaise());
        calc.put("platformRemainderPaise",distribution.platformRemainderPaise());
        allocationId=jdbc.queryForObject("""
          insert into revenue_share_allocation(
            course_payment_order_id,policy_id,policy_recipient_id,institution_id,currency,
            actual_paid_paise,output_tax_paise,distributable_pool_paise,total_institution_allocated_paise,
            allocated_amount_paise,platform_remainder_paise,calculation_snapshot,idempotency_key
          ) values(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) returning id
          """,Long.class,orderId,policyId,recipientRowId,allocation.institutionId(),currency,
            capturedAmountPaise,storedTax,distribution.distributablePoolPaise(),
            distribution.totalInstitutionAllocatedPaise(),allocation.amountPaise(),
            distribution.platformRemainderPaise(),json(calc),idem);
      }
      jdbc.update("""
        insert into revenue_share_allocation_event(
          allocation_id,event_type,amount_paise,currency,idempotency_key,details
        ) values(?,'EARNED',?,?,?,?::jsonb)
        on conflict(idempotency_key) do nothing
        """,allocationId,allocation.amountPaise(),currency,idem+":earned",
          json(Map.of("coursePaymentOrderId",orderId,"paymentId",paymentId,"policyId",policyId)));
    }

    long fiscalDocumentId=number(order.get("fiscal_document_id"));
    String documentNumber="";
    if(fiscalDocumentId>0) {
      Map<String,Object> existing=one("select id,document_number from fiscal_document where id=?",fiscalDocumentId);
      if(existing.isEmpty()) throw new IllegalStateException("The order points to a missing fiscal document.");
      fiscalDocumentId=number(existing.get("id"));documentNumber=string(existing.get("document_number"));
    } else {
      FinalizedSale invoice=createFiscalDocument(order,paymentId,capturedAmountPaise,
          verified,distribution,taxSnapshot,buyerSnapshot,currency,orderId);
      fiscalDocumentId=invoice.fiscalDocumentId();documentNumber=invoice.documentNumber();
    }
    int finalizedOrderRows=jdbc.update("""
      update course_payment_order
      set status='VERIFIED',provider_payment_id=?,provider_signature=?,fiscal_document_id=?,failure_code=null,
        verified_at=coalesce(verified_at,now()),captured_amount_paise=?,captured_at=coalesce(captured_at,now()),updated_at=now()
      where id=? and institution_id=? and offer_price_paise=?
        and (provider_payment_id is null or provider_payment_id=?)
        and status in ('PENDING','EXPIRED','VERIFIED')
      """,paymentId,signature,fiscalDocumentId,capturedAmountPaise,orderId,
        number(order.get("institution_id")),capturedAmountPaise,paymentId);
    if(finalizedOrderRows!=1)
      throw new IllegalStateException("The fiscal document was not linked to the exact captured course order.");
    return new FinalizedSale(fiscalDocumentId,documentNumber,
        distribution.totalInstitutionAllocatedPaise(),distribution.platformRemainderPaise());
  }

  private FinalizedSale createFiscalDocument(
      Map<String,Object> order,String paymentId,long captured,
      CourseTaxCalculator.Result taxResult,RevenueDistributionCalculator.Result distribution,
      Map<String,Object> taxSnapshot,Map<String,Object> buyerSnapshot,String currency,long orderId) {
    Map<String,Object> supplier=asMap(taxSnapshot.get("supplierSnapshot"));
    Map<String,Object> taxSummary=new LinkedHashMap<>();
    taxSummary.put("taxTreatment",taxSnapshot.get("taxTreatment"));
    taxSummary.put("serviceClassificationCode",taxSnapshot.get("serviceClassificationCode"));
    taxSummary.put("gstRateBasisPoints",taxSnapshot.get("gstRateBasisPoints"));
    taxSummary.put("cessRateBasisPoints",taxSnapshot.get("cessRateBasisPoints"));
    taxSummary.put("components",asMapList(taxSnapshot.get("components")));
    taxSummary.put("placeOfSupplyRule",taxSnapshot.get("placeOfSupplyRule"));
    taxSummary.put("buyerStateCode",taxSnapshot.get("buyerStateCode"));
    taxSummary.put("supplierStateCode",taxSnapshot.get("supplierStateCode"));

    long legalEntityId=number(supplier.get("legalEntityId"));
    Long gstRegistrationId=nullableLong(supplier.get("gstRegistrationId"));
    if(legalEntityId<=0) throw new IllegalStateException("The locked supplier snapshot has no legal entity.");
    String treatment=string(taxSnapshot.get("taxTreatment"));
    String documentType="TAXABLE".equals(treatment)?"TAX_INVOICE":"BILL_OF_SUPPLY";
    OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
    LocalDate issueDate=LocalDate.now(INDIA);
    String fy=financialYear(issueDate);
    String series=value(supplier,"invoiceSeriesCode","QE").toUpperCase(Locale.ROOT);
    if(!series.matches("[A-Z0-9_-]{1,3}")) throw new IllegalStateException("The seller invoice series code must be 1 to 3 characters.");

    jdbc.update("""
      insert into fiscal_document_sequence(
        legal_entity_id,document_type,financial_year,series_code,next_sequence_no
      ) values(?,?,?,?,1)
      on conflict(legal_entity_id,document_type,financial_year,series_code) do nothing
      """,legalEntityId,documentType,fy,series);
    Long next=jdbc.queryForObject("""
      select next_sequence_no from fiscal_document_sequence
      where legal_entity_id=? and document_type=? and financial_year=? and series_code=?
      for update
      """,Long.class,legalEntityId,documentType,fy,series);
    if(next==null||next<=0) throw new IllegalStateException("The invoice numbering sequence is unavailable.");
    if(next>999_999L) throw new IllegalStateException("This invoice series reached its 999,999-document limit for the financial year. Approve a new series prefix before issuing another document.");
    jdbc.update("""
      update fiscal_document_sequence set next_sequence_no=?,updated_at=now()
      where legal_entity_id=? and document_type=? and financial_year=? and series_code=?
      """,Math.addExact(next,1),legalEntityId,documentType,fy,series);
    String printedFinancialYear=String.format(Locale.ROOT,"%02d-%02d",
        issueDate.getMonthValue()>=4?issueDate.getYear()%100:(issueDate.getYear()-1)%100,
        issueDate.getMonthValue()>=4?(issueDate.getYear()+1)%100:issueDate.getYear()%100);
    String docNumber=series+"/"+printedFinancialYear+"/"+String.format(Locale.ROOT,"%06d",next);
    if(docNumber.length()>16) throw new IllegalStateException("The configured invoice serial would exceed the 16-character statutory limit.");
    String idempotency="course-order:"+orderId+":fiscal-document:v1";

    Long templateId=nullableLong(taxSnapshot.get("invoiceTemplateVersionId"));
    Map<String,Object> transaction=new LinkedHashMap<>();
    transaction.put("coursePaymentOrderId",orderId);
    transaction.put("orderReference","QE-ORDER-"+orderId);
    transaction.put("courseId",number(order.get("course_id")));
    transaction.put("courseCode",value(taxSnapshot,"courseCode",""));
    transaction.put("courseTypeCode",value(taxSnapshot,"courseTypeCode",""));
    transaction.put("courseTitle",value(taxSnapshot,"courseTitle","Course access"));
    transaction.put("institutionId",number(taxSnapshot.get("institutionId")));
    transaction.put("institutionName",value(taxSnapshot,"institutionName",""));
    transaction.put("purchaserUserId",order.get("purchaser_user_id"));
    transaction.put("beneficiaryStudentId",number(order.get("student_id")));
    transaction.put("paymentReference",paymentId);
    transaction.put("listedPricePaise",taxResult.listedPricePaise());
    transaction.put("discountValuePaise",taxResult.discountPaise());
    transaction.put("taxableValuePaise",taxResult.taxableValuePaise());
    transaction.put("taxValuePaise",taxResult.outputTaxPaise());
    transaction.put("amountPaidPaise",captured);
    transaction.put("currency",currency);

    List<Map<String,Object>> lineRows=new ArrayList<>();
    Map<String,Object> line=new LinkedHashMap<>();
    line.put("description",value(taxSnapshot,"courseTitle","Course access"));
    line.put("serviceClassificationCode",taxSnapshot.get("serviceClassificationCode"));
    line.put("quantity",BigDecimal.ONE);
    line.put("unitPricePaise",taxResult.listedPricePaise());
    line.put("discountValuePaise",taxResult.discountPaise());
    line.put("taxableValuePaise",taxResult.taxableValuePaise());
    line.put("taxComponentsSnapshot",taxSummary);
    line.put("taxValuePaise",taxResult.outputTaxPaise());
    line.put("lineTotalPaise",captured);
    lineRows.add(line);

    Map<String,Object> transactionJson= new LinkedHashMap<>(transaction);
    transactionJson.put("taxProfileId",taxSnapshot.get("taxProfileId"));
    transactionJson.put("taxProfileVersion",taxSnapshot.get("taxProfileVersion"));
    transactionJson.put("revenueSharePolicyId",number(order.get("revenue_share_policy_id")));
    transactionJson.put("revenueSharePolicyVersion",taxSnapshot.get("revenueSharePolicyVersion"));

    Long docId=jdbc.queryForObject("""
      insert into fiscal_document(
        legal_entity_id,gst_registration_id,document_type,financial_year,series_code,sequence_no,document_number,
        source_type,source_record_key,idempotency_key,currency,supplier_snapshot,recipient_snapshot,
        transaction_snapshot,tax_summary_snapshot,taxable_value_paise,discount_value_paise,tax_value_paise,
        total_value_paise,amount_paid_paise,template_version_id
      ) values(?,?,?,?,?,?,?,'COURSE_ORDER',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?) returning id
      """,Long.class,legalEntityId,gstRegistrationId,documentType,fy,series,next,docNumber,
        Long.toString(orderId),idempotency,currency,json(supplier),json(buyerSnapshot),json(transactionJson),
        json(taxSummary),taxResult.taxableValuePaise(),taxResult.discountPaise(),taxResult.outputTaxPaise(),
        captured,captured,templateId);

    jdbc.update("""
      insert into fiscal_document_line(
        fiscal_document_id,line_no,product_reference,description_snapshot,service_classification_code,
        quantity,unit_price_paise,discount_value_paise,taxable_value_paise,tax_components_snapshot,
        tax_value_paise,line_total_paise
      ) values(?,1,?,?,?,?,?,?,?,?::jsonb,?,?)
      """,docId,"COURSE-"+number(order.get("course_id")),line.get("description"),
        line.get("serviceClassificationCode"),line.get("quantity"),taxResult.listedPricePaise(),
        taxResult.discountPaise(),taxResult.taxableValuePaise(),json(taxSummary),
        taxResult.outputTaxPaise(),captured);

    Map<String,Object> templateDesign=asMap(taxSnapshot.get("invoiceTemplateDesignSnapshot"));
    FiscalDocumentRenderer.RenderedFiscalDocument rendered=renderer.render(
        documentType,docNumber,issueDate,supplier,buyerSnapshot,transaction,taxSummary,lineRows,templateDesign);
    FiscalDocumentStorage.StoredDocument asset=storage.storePdf(rendered.pdf());
    // PDF storage is outside the SQL transaction. Remove the file if any later database
    // write rolls back, so an unissued invoice cannot leave an orphaned artifact behind.
    if(TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) {
          if(status!=STATUS_COMMITTED) storage.deleteQuietly(asset.storageKey());
        }
      });
    }
    jdbc.update("""
      insert into fiscal_document_asset(
        fiscal_document_id,asset_type,storage_key,mime_type,sha256,byte_length
      ) values(?,'PDF',?,'application/pdf',?,?)
      """,docId,asset.storageKey(),asset.sha256(),asset.sizeBytes());
    jdbc.update("""
      insert into fiscal_document_event(fiscal_document_id,event_type,idempotency_key,details)
      values(?,'ISSUED',?,?::jsonb)
      on conflict(idempotency_key) do nothing
      """,docId,idempotency+":issued",json(Map.of("coursePaymentOrderId",orderId,"paymentReference",paymentId)));
    jdbc.update("""
      insert into fiscal_document_event(fiscal_document_id,event_type,idempotency_key,details)
      values(?,'PDF_RENDERED',?,?::jsonb)
      on conflict(idempotency_key) do nothing
      """,docId,idempotency+":pdf",json(Map.of("sha256",asset.sha256(),"byteLength",asset.sizeBytes())));
    return new FinalizedSale(docId,docNumber,distribution.totalInstitutionAllocatedPaise(),distribution.platformRemainderPaise());
  }

  public record DownloadableInvoice(long documentId,String documentNumber,String storageKey,String sha256,long sizeBytes) {}

  public DownloadableInvoice invoiceDownload(long courseOrderId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select d.id,d.document_number,a.storage_key,a.sha256,a.byte_length
      from course_payment_order o join fiscal_document d on d.id=o.fiscal_document_id
      join fiscal_document_asset a on a.fiscal_document_id=d.id and a.asset_type='PDF'
      where o.id=? and o.status='VERIFIED'
      order by a.created_at desc,a.id desc limit 1
      """,courseOrderId);
    if(rows.isEmpty()) throw new IllegalArgumentException("An issued invoice PDF is not available for this verified course order.");
    Map<String,Object> row=rows.getFirst();
    return new DownloadableInvoice(number(row.get("id")),string(row.get("document_number")),
        string(row.get("storage_key")),string(row.get("sha256")),number(row.get("byte_length")));
  }

  public DownloadableInvoice fiscalDocumentDownload(long fiscalDocumentId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select d.id,d.document_number,a.storage_key,a.sha256,a.byte_length
      from fiscal_document d
      join fiscal_document_asset a on a.fiscal_document_id=d.id and a.asset_type='PDF'
      where d.id=?
      order by a.created_at desc,a.id desc limit 1
      """,fiscalDocumentId);
    if(rows.isEmpty()) throw new IllegalArgumentException("An issued fiscal-document PDF is not available.");
    Map<String,Object> row=rows.getFirst();
    return new DownloadableInvoice(number(row.get("id")),string(row.get("document_number")),
        string(row.get("storage_key")),string(row.get("sha256")),number(row.get("byte_length")));
  }

  public byte[] readInvoice(DownloadableInvoice document) {
    byte[] bytes=storage.readPdf(document.storageKey());
    if(!FiscalDocumentStorage.sha256(bytes).equals(document.sha256())||bytes.length!=document.sizeBytes())
      throw new IllegalStateException("The stored invoice did not pass its integrity check.");
    return bytes;
  }

  private static String normalizeDiscountCode(String raw) {
    if(raw==null||raw.isBlank()) return null;
    String code=raw.trim().toUpperCase(Locale.ROOT);
    if(!code.matches("[A-Z0-9][A-Z0-9_-]{2,39}"))
      throw new IllegalArgumentException("Offer codes must be 3–40 letters, digits, hyphens or underscores.");
    return code;
  }

  private long offerIdForOrder(long orderId) {
    Long id=jdbc.queryForObject("select institution_offer_id from course_payment_order where id=?",Long.class,orderId);
    if(id==null)throw new IllegalStateException("Course order is missing its offer link.");
    return id;
  }

  private String courseTitle(long institutionOfferId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select coalesce(nullif(o.title_override,''),p.display_name) as title
      from institution_marketplace_offer o
      join curriculum_program p on p.id=o.course_id
      where o.id=?
      """,institutionOfferId);
    if(rows.isEmpty()) throw new IllegalStateException("The sold course offer was not found in its institution scope.");
    return string(rows.getFirst().get("title"));
  }

  private String institutionName(long institutionId) {
    String name=jdbc.queryForObject("""
      select coalesce(nullif(b.display_name,''),t.display_name)
      from institution_tenant t left join institution_branding b on b.institution_id=t.id
      where t.id=? and t.status='ACTIVE'
      """,String.class,institutionId);
    if(name==null||name.isBlank())throw new IllegalStateException("The course institution branding could not be resolved.");
    return name;
  }

  private static List<Map<String,Object>> taxComponents(
      CourseTaxCalculator.Result result,Map<String,Object> profileComponents,String placeRule,
      String supplierStateCode,String buyerStateCode) {
    List<Map<String,Object>> components=new ArrayList<>();
    if(result.treatment()!=CourseTaxCalculator.Treatment.TAXABLE) return components;
    long cess=percentageOf(result.taxableValuePaise(),result.cessRateBasisPoints());
    long gst=result.outputTaxPaise()-cess;
    if(gst<0) throw new IllegalStateException("Tax breakdown is inconsistent with the approved inclusive tax rate.");
    String supplyState="RECIPIENT_LOCATION".equals(placeRule)?buyerStateCode:
        "SUPPLIER_LOCATION".equals(placeRule)?supplierStateCode:
        validStateCode(profileComponents.get("caApprovedPlaceOfSupplyStateCode"))
            ?string(profileComponents.get("caApprovedPlaceOfSupplyStateCode")):null;
    if(supplyState==null) throw new IllegalStateException("The approved place-of-supply rule could not be resolved.");
    if(supplyState.equals(supplierStateCode)) {
      int cgstRate=result.gstRateBasisPoints()/2;
      int sgstRate=result.gstRateBasisPoints()-cgstRate;
      long cgst=gst/2;
      long sgst=gst-cgst;
      if(cgst>0||cgstRate>0) components.add(component("CGST",cgstRate,cgst));
      if(sgst>0||sgstRate>0) components.add(component("SGST",sgstRate,sgst));
    } else {
      components.add(component("IGST",result.gstRateBasisPoints(),gst));
    }
    if(cess>0||result.cessRateBasisPoints()>0)
      components.add(component("CESS",result.cessRateBasisPoints(),cess));
    return components;
  }

  private static Map<String,Object> component(String name,int rateBasisPoints,long amountPaise) {
    return Map.of("name",name,"rateBasisPoints",rateBasisPoints,"amountPaise",amountPaise);
  }

  private static String resolveStateCode(
      Map<String,Object> buyer,Map<String,Object> taxComponents,String placeRule,
      String supplierStateCode,CourseTaxCalculator.Treatment treatment) {
    if(treatment!=CourseTaxCalculator.Treatment.TAXABLE) {
      Object explicit=buyer==null?null:buyer.get("gstStateCode");
      if(validStateCode(explicit))return string(explicit);
      return stateCodeFromAddress(buyer==null?null:asMap(buyer.get("billingAddress")).get("state"));
    }
    return switch(placeRule) {
      case "SUPPLIER_LOCATION" -> supplierStateCode;
      case "RECIPIENT_LOCATION" -> {
        Object explicit=buyer==null?null:buyer.get("gstStateCode");
        if(validStateCode(explicit))yield string(explicit);
        yield stateCodeFromAddress(buyer==null?null:asMap(buyer.get("billingAddress")).get("state"));
      }
      case "CA_APPROVED_CUSTOM" -> {
        Object configured=taxComponents.get("caApprovedPlaceOfSupplyStateCode");
        yield validStateCode(configured)?string(configured):null;
      }
      default -> null;
    };
  }

  private static String stateCodeFromAddress(Object raw) {
    String state=raw==null?"":String.valueOf(raw).trim().toUpperCase(Locale.ROOT)
        .replaceAll("[.,]","").replaceAll("\\s+"," ");
    return switch(state) {
      case "JAMMU AND KASHMIR","JAMMU & KASHMIR","J&K" -> "01";
      case "HIMACHAL PRADESH" -> "02";
      case "PUNJAB" -> "03";
      case "CHANDIGARH" -> "04";
      case "UTTARAKHAND","UTTARANCHAL" -> "05";
      case "HARYANA" -> "06";
      case "DELHI","NCT OF DELHI" -> "07";
      case "RAJASTHAN" -> "08";
      case "UTTAR PRADESH","UP" -> "09";
      case "BIHAR" -> "10";
      case "SIKKIM" -> "11";
      case "ARUNACHAL PRADESH" -> "12";
      case "NAGALAND" -> "13";
      case "MANIPUR" -> "14";
      case "MIZORAM" -> "15";
      case "TRIPURA" -> "16";
      case "MEGHALAYA" -> "17";
      case "ASSAM" -> "18";
      case "WEST BENGAL" -> "19";
      case "JHARKHAND" -> "20";
      case "ODISHA","ORISSA" -> "21";
      case "CHHATTISGARH" -> "22";
      case "MADHYA PRADESH" -> "23";
      case "GUJARAT" -> "24";
      case "DAMAN AND DIU","DAMAN & DIU" -> "25";
      case "DADRA AND NAGAR HAVELI AND DAMAN AND DIU","DADRA AND NAGAR HAVELI" -> "26";
      case "MAHARASHTRA" -> "27";
      case "ANDHRA PRADESH OLD" -> "28";
      case "KARNATAKA" -> "29";
      case "GOA" -> "30";
      case "LAKSHADWEEP" -> "31";
      case "KERALA" -> "32";
      case "TAMIL NADU" -> "33";
      case "PUDUCHERRY","PONDICHERRY" -> "34";
      case "ANDAMAN AND NICOBAR ISLANDS","ANDAMAN & NICOBAR ISLANDS" -> "35";
      case "TELANGANA" -> "36";
      case "ANDHRA PRADESH" -> "37";
      case "LADAKH" -> "38";
      case "OTHER TERRITORY" -> "97";
      default -> null;
    };
  }

  private static boolean validStateCode(Object value) {
    return value!=null&&String.valueOf(value).trim().matches("[0-9]{2}");
  }
  private static long percentageOf(long amountPaise,int rateBasisPoints) {
    return BigDecimal.valueOf(amountPaise).multiply(BigDecimal.valueOf(rateBasisPoints))
        .divide(BigDecimal.valueOf(10_000),0,RoundingMode.HALF_UP).longValueExact();
  }
  private static String financialYear(LocalDate date) {
    int start=date.getMonthValue()>=4?date.getYear():date.getYear()-1;
    return start+"-"+String.format(Locale.ROOT,"%02d",(start+1)%100);
  }
  private static int integer(Object raw) {
    try { return raw instanceof Number n?Math.toIntExact(n.longValue()):Integer.parseInt(String.valueOf(raw)); }
    catch(Exception ex) { throw new IllegalStateException("An approved financial snapshot contains a malformed integer."); }
  }
  private static long number(Object raw) {
    try { return raw instanceof Number n?new BigDecimal(n.toString()).longValueExact():Long.parseLong(String.valueOf(raw)); }
    catch(Exception ex) { return 0; }
  }
  private static Long nullableLong(Object raw) {
    return raw==null?null:number(raw);
  }
  private static Integer nullableInteger(Object raw) {
    return raw==null?null:integer(raw);
  }
  private static String nullableString(Object raw) {
    if(raw==null)return null;String s=String.valueOf(raw).trim();return s.isEmpty()?null:s;
  }
  private static String string(Object raw) {
    return raw==null?"":String.valueOf(raw).trim();
  }
  private static String value(Map<String,Object> m,String k,String fallback) {
    String v=nullableString(m==null?null:m.get(k));return v==null?fallback:v;
  }
  private Map<String,Object> parseObject(Object raw) {
    if(raw==null)return new LinkedHashMap<>();
    if(raw instanceof Map<?,?> map)return asMap(map);
    String json=String.valueOf(raw);
    try { return mapper.readValue(json,MAP_TYPE); }
    catch(Exception ex) { throw new IllegalStateException("A financial JSON snapshot is malformed.",ex); }
  }
  private static Map<String,Object> asMap(Object raw) {
    Map<String,Object> out=new LinkedHashMap<>();
    if(raw instanceof Map<?,?> m)m.forEach((k,v)->out.put(String.valueOf(k),v));
    return out;
  }
  private static List<Map<String,Object>> asMapList(Object raw) {
    if(!(raw instanceof List<?> list))return List.of();
    List<Map<String,Object>> out=new ArrayList<>();
    for(Object item:list)if(item instanceof Map<?,?> m)out.add(asMap(m));
    return out;
  }
  private String json(Object value) {
    try { return mapper.writeValueAsString(value==null?Map.of():value); }
    catch(Exception ex) { throw new IllegalStateException("A financial snapshot could not be serialized.",ex); }
  }
  private Map<String,Object> one(String sql,Object... args) {
    List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
    return rows.isEmpty()?Map.of():rows.getFirst();
  }
}
