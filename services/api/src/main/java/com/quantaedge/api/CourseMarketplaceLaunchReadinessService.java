package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * One server-authoritative checklist for launching institution course offers.
 * HRMS can inspect the checks; the tenant offer publish transition enforces these same checks.
 */
@Service
public class CourseMarketplaceLaunchReadinessService {
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000L);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final String razorpayKeyId;
  private final String razorpayKeySecret;
  private final String razorpayWebhookSecret;

  public CourseMarketplaceLaunchReadinessService(
      JdbcTemplate jdbc, ObjectMapper mapper,
      @Value("\${app.razorpay.key-id:}") String razorpayKeyId,
      @Value("\${app.razorpay.key-secret:}") String razorpayKeySecret,
      @Value("\${app.razorpay.webhook-secret:}") String razorpayWebhookSecret) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.razorpayKeyId = clean(razorpayKeyId);
    this.razorpayKeySecret = clean(razorpayKeySecret);
    this.razorpayWebhookSecret = clean(razorpayWebhookSecret);
  }

  public Map<String,Object> inspect(long offerId, Long institutionScope) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select o.id as offer_id,o.institution_id,o.assignment_id,o.course_release_id,o.course_id,
          o.title_override,o.description,o.price_paise,o.currency,o.status as offer_status,
          t.display_name as institution_name,t.status as institution_status,
          a.status as assignment_status,r.release_state,r.content_fingerprint,
          p.status as course_status,p.code as course_code,p.display_name as course_name,
          (select count(distinct ps.subject_id)
             from curriculum_program_subject ps
             join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
               and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
            where ps.program_id=p.id) as subject_count,
          (select count(distinct ch.id)
             from curriculum_program_subject ps
             join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
               and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
             join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
               and ch.content_status='PUBLISHED'
            where ps.program_id=p.id) as published_chapter_count,
          (select count(distinct l.id)
             from curriculum_program_subject ps
             join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
               and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
             join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
               and ch.content_status='PUBLISHED'
             join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where ps.program_id=p.id) as published_lesson_count
        from institution_marketplace_offer o
        join institution_tenant t on t.id=o.institution_id
        join institution_course_assignment a on a.id=o.assignment_id and a.institution_id=o.institution_id
        join institution_course_release r on r.id=o.course_release_id and r.program_id=o.course_id
        join curriculum_program p on p.id=o.course_id
        where o.id=?
        """, offerId);
    if (rows.isEmpty()) throw new IllegalArgumentException("The marketplace offer was not found.");
    Map<String,Object> offer = rows.getFirst();
    long institutionId = number(offer.get("institution_id"));
    if (institutionScope != null && institutionScope.longValue() != institutionId) {
      throw new SecurityException("This marketplace offer is outside the current institution.");
    }

    List<Map<String,Object>> checks = new ArrayList<>();
    add(checks,"INSTITUTION_ACTIVE","Institution is active",
        "ACTIVE".equals(text(offer.get("institution_status"))),
        "Activate or resolve the institution in HRMS before launching its course.");
    add(checks,"COURSE_ACTIVE","Course is active",
        "ACTIVE".equals(text(offer.get("course_status")))
            && !"GENERAL".equalsIgnoreCase(text(offer.get("course_code"))),
        "The course must be an active, independently selectable course product.");
    add(checks,"ASSIGNMENT_AND_RELEASE","Course release is approved for sharing",
        "ASSIGNED".equals(text(offer.get("assignment_status")))
            && "SHAREABLE".equals(text(offer.get("release_state"))),
        "The institution needs an active assignment to a platform-reviewed SHAREABLE course release.");
    long courseId = number(offer.get("course_id"));
    String savedFingerprint = text(offer.get("content_fingerprint"));
    boolean contentUnchanged = !savedFingerprint.isBlank()
        && savedFingerprint.equals(contentFingerprint(courseId));
    add(checks,"CONTENT_REVIEW_CURRENT","Reviewed course content is current",contentUnchanged,
        "The course content has changed since quality review or has no reviewed fingerprint. Resubmit the release for platform quality review.");
    long subjectCount = number(offer.get("subject_count"));
    long chapterCount = number(offer.get("published_chapter_count"));
    long lessonCount = number(offer.get("published_lesson_count"));
    add(checks,"PUBLISHED_LEARNING_CONTENT","Published subjects, chapters and lessons exist",
        subjectCount > 0 && chapterCount > 0 && lessonCount > 0,
        "Publish at least one active subject, chapter and lesson before listing the course.");

    String courseName = text(offer.get("title_override")).isBlank()
        ? text(offer.get("course_name")) : text(offer.get("title_override"));
    long pricePaise = number(offer.get("price_paise"));
    String currency = text(offer.get("currency")).toUpperCase(Locale.ROOT);
    add(checks,"OFFER_TERMS","Offer title, price and currency are valid",
        !courseName.isBlank() && pricePaise >= 0 && "INR".equals(currency),
        "Set a valid title and an explicit non-negative INR price. Use zero only for an intentionally free offer.");

    if (pricePaise > 0) {
      boolean paymentReady = !razorpayKeyId.isBlank() && !razorpayKeySecret.isBlank();
      add(checks,"RAZORPAY_CREDENTIALS","Platform Razorpay checkout is configured",paymentReady,
          "HRMS/deployment must configure the Razorpay key ID and secret before a paid course can launch.");
      add(checks,"RAZORPAY_WEBHOOK","Razorpay course-payment webhook is configured",
          !razorpayWebhookSecret.isBlank(),
          "Configure the signed course-payment webhook and its secret before launching paid courses.");

      FinancialState financial = inspectFinancialReadiness(
          offerId,institutionId,pricePaise,currency,checks);
      long afterTaxPool = financial.afterTaxPoolPaise();
      ShareReadiness shares = inspectSharePolicy(
          offerId,currency,financial.hasValidTaxQuote()?afterTaxPool:pricePaise);
      add(checks,"REVENUE_SHARE_POLICY","One effective, approved revenue-share policy has valid recipients",
          shares.ready(),shares.message());
      if(!financial.hasValidTaxQuote()) {
        add(checks,"SHARE_POOL_VALIDATION","Revenue shares fit the approved after-tax pool",false,
            "HRMS must finish tax readiness before the course can pass the final allocation-pool check.");
      } else {
        add(checks,"SHARE_POOL_VALIDATION","Revenue shares fit the approved after-tax pool",
            shares.ready(),shares.message());
      }
    }

    List<String> blockers = checks.stream().filter(check -> !Boolean.TRUE.equals(check.get("passed")))
        .map(check -> text(check.get("label"))+": "+text(check.get("message"))).toList();
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("offerId",offerId);
    result.put("institutionId",institutionId);
    result.put("institutionName",text(offer.get("institution_name")));
    result.put("courseId",courseId);
    result.put("courseName",courseName);
    result.put("offerStatus",text(offer.get("offer_status")));
    result.put("pricePaise",pricePaise);
    result.put("currency",currency);
    result.put("ready",blockers.isEmpty());
    result.put("checks",checks);
    result.put("blockingIssues",blockers);
    result.put("message",blockers.isEmpty()
        ? "All required course-launch checks have passed."
        : "Marketplace launch is blocked until HRMS resolves the required checks.");
    return result;
  }

  public void requireReady(long offerId,long institutionScope) {
    Map<String,Object> result = inspect(offerId,institutionScope);
    if (Boolean.TRUE.equals(result.get("ready"))) return;
    @SuppressWarnings("unchecked")
    List<String> blockers = (List<String>) result.get("blockingIssues");
    String detail = blockers == null ? "" : String.join(" ",blockers);
    if (detail.length() > 1800) detail = detail.substring(0,1800);
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "HRMS marketplace launch pre-check failed. No publication occurred. "+detail);
  }

  private FinancialState inspectFinancialReadiness(
      long offerId,long institutionId,long pricePaise,String currency,List<Map<String,Object>> checks) {
    LocalDate today = LocalDate.now(INDIA);
    List<Map<String,Object>> profiles = jdbc.queryForList("""
        select tp.id as tax_profile_id,tp.legal_entity_id,tp.tax_treatment,tp.gst_rate_basis_points,
          tp.cess_rate_basis_points,tp.price_includes_tax,tp.place_of_supply_rule,
          tp.tax_components::text as tax_components_json,
          le.einvoice_applicability,le.einvoice_applicability_reviewed_by_staff_id,
          le.einvoice_applicability_reviewed_at,le.einvoice_applicability_review_note,
          le.legal_name,le.status as legal_entity_status,le.entity_kind,
          gr.id as gst_registration_id,gr.gstin as supplier_gstin,gr.state_code as supplier_state_code
        from course_tax_profile tp
        join institution_marketplace_offer o on o.id=tp.institution_offer_id and o.institution_id=?
        join billing_legal_entity le on le.id=tp.legal_entity_id and le.status='ACTIVE'
          and (le.entity_kind='PLATFORM' or le.institution_id=o.institution_id)
        left join lateral (
          select g.id,g.gstin,g.state_code
          from billing_gst_registration g
          where g.legal_entity_id=le.id and g.status='ACTIVE'
            and g.effective_from<=? and (g.effective_until is null or g.effective_until>=?)
          order by g.effective_from desc,g.id desc limit 1
        ) gr on true
        where tp.institution_offer_id=? and tp.status='APPROVED'
          and tp.effective_from<=now() and (tp.effective_until is null or tp.effective_until>now())
        order by tp.version desc,tp.id desc limit 2
        """,institutionId,today,today,offerId);

    if (profiles.size()!=1) {
      add(checks,"APPROVED_TAX_PROFILE","Exactly one effective approved tax profile and active legal seller exist",
          false,profiles.isEmpty()
              ? "HRMS must activate the legal seller and approve one effective tax profile for this exact offer."
              : "More than one approved tax profile is effective. HRMS must resolve the overlap before launch.");
      add(checks,"EINVOICE_REVIEW","Seller e-invoice applicability is reviewed",false,
          "Select the active legal seller, record the tax-adviser review and resolve its e-invoice applicability in HRMS.");
      add(checks,"GST_REGISTRATION","Required seller GST registration is active",false,
          "An effective approved tax profile must be linked to its active seller; taxable treatment also requires a verified GST registration.");
      add(checks,"PUBLISHED_INVOICE_TEMPLATE","An invoice template is published",hasPublishedTemplate(null),
          "Publish an approved platform or seller invoice template in HRMS Invoice Design Studio.");
      return new FinancialState(false,0L);
    }

    Map<String,Object> profile=profiles.getFirst();
    add(checks,"APPROVED_TAX_PROFILE","Exactly one effective approved tax profile and active legal seller exist",
        true,"An active legal seller and one effective approved tax profile are configured.");

    String applicability=text(profile.get("einvoice_applicability"));
    boolean reviewed=("NOT_REQUIRED".equals(applicability)||"REQUIRED".equals(applicability))
        && profile.get("einvoice_applicability_reviewed_by_staff_id")!=null
        && profile.get("einvoice_applicability_reviewed_at")!=null;
    boolean invoiceModeSupported=reviewed && "NOT_REQUIRED".equals(applicability);
    String invoiceMessage=!reviewed
        ? "HRMS must record a reasoned e-invoice applicability review for the seller."
        : "REQUIRED".equals(applicability)
            ? "This seller requires e-invoicing; launch remains blocked until IRP/IRN submission and signed-QR verification are integrated."
            : "Seller applicability review is recorded as NOT_REQUIRED.";
    add(checks,"EINVOICE_REVIEW","Seller e-invoice applicability is reviewed and supported",
        invoiceModeSupported,invoiceMessage);

    boolean taxOk = true;
    String taxError = "";
    CourseTaxCalculator.Result quote = null;
    try {
      CourseTaxCalculator.Treatment treatment =
          CourseTaxCalculator.Treatment.valueOf(text(profile.get("tax_treatment")));
      int gstRate = (int) number(profile.get("gst_rate_basis_points"));
      int cessRate = (int) number(profile.get("cess_rate_basis_points"));
      boolean includesTax = Boolean.TRUE.equals(profile.get("price_includes_tax"));
      quote = CourseTaxCalculator.quote(pricePaise,0L,treatment,gstRate,cessRate,includesTax);
      if(treatment==CourseTaxCalculator.Treatment.TAXABLE) {
        String gstin=text(profile.get("supplier_gstin"));
        String stateCode=text(profile.get("supplier_state_code"));
        boolean gstRegistrationOk=profile.get("gst_registration_id")!=null
            && !gstin.isBlank() && !stateCode.isBlank() && gstin.startsWith(stateCode);
        if(!gstRegistrationOk) {
          taxOk=false;
          taxError="Taxable treatment requires an effective, HRMS-verified GST registration whose GSTIN matches its state code.";
        }
      }
      if("CA_APPROVED_CUSTOM".equals(text(profile.get("place_of_supply_rule")))
          && !hasApprovedCustomState(profile.get("tax_components_json"))) {
        taxOk=false;
        taxError="The custom place-of-supply rule needs a valid CA-approved state code in the tax-component configuration.";
      }
    } catch(RuntimeException invalidTax) {
      taxOk=false;
      taxError=invalidTax.getMessage()==null?"The saved tax setup is invalid.":invalidTax.getMessage();
    }
    add(checks,"TAX_CALCULATION","Approved tax setup can calculate a valid quote",
        taxOk, taxOk ? "The approved tax treatment and rates pass the server-side quote checks." : taxError);
    boolean needsGst="TAXABLE".equals(text(profile.get("tax_treatment")));
    boolean gstReady=!needsGst || (profile.get("gst_registration_id")!=null
        && !text(profile.get("supplier_gstin")).isBlank()
        && text(profile.get("supplier_gstin")).startsWith(text(profile.get("supplier_state_code"))));
    add(checks,"GST_REGISTRATION","Required seller GST registration is active",gstReady,
        needsGst
          ? "Taxable treatment requires an effective, HRMS-verified GST registration whose GSTIN matches its state code."
          : "The approved tax treatment does not require a GST registration for this launch check.");

    Long legalEntityId = profile.get("legal_entity_id") instanceof Number n ? n.longValue() : null;
    boolean templateReady=hasPublishedTemplate(legalEntityId);
    add(checks,"PUBLISHED_INVOICE_TEMPLATE","An invoice template is published",templateReady,
        templateReady ? "A published platform or seller template is available for newly issued sale documents."
            : "Publish an approved platform-wide or seller-specific invoice template in HRMS Invoice Design Studio.");

    if(quote==null||!taxOk||!invoiceModeSupported) return new FinancialState(false,0L);
    return new FinancialState(true,quote.customerPayablePaise()-quote.outputTaxPaise());
  }

  private ShareReadiness inspectSharePolicy(long offerId,String currency,long poolPaise) {
    List<Map<String,Object>> policies=jdbc.queryForList("""
        select id,policy_version from revenue_share_policy
        where institution_offer_id=? and status='APPROVED' and currency=?
          and effective_from<=now() and (effective_until is null or effective_until>now())
        order by policy_version desc,id desc limit 2
        """,offerId,currency);
    if(policies.size()!=1) {
      return new ShareReadiness(false,policies.isEmpty()
          ? "HRMS must approve one effective revenue-share policy in the offer currency."
          : "More than one approved revenue-share policy is effective; HRMS must resolve the overlap.");
    }
    long policyId=number(policies.getFirst().get("id"));
    List<Map<String,Object>> recipients=jdbc.queryForList("""
        select r.institution_id,t.status as institution_status,r.calculation_type,
          r.percentage_basis_points,r.flat_amount_paise
        from revenue_share_policy_recipient r
        join institution_tenant t on t.id=r.institution_id
        where r.policy_id=? order by r.institution_id
        """,policyId);
    if(recipients.isEmpty()) return new ShareReadiness(false,"The approved revenue-share policy needs at least one institution recipient.");
    long allocated=0L;
    long percentageBps=0L;
    try {
      for(Map<String,Object> recipient:recipients) {
        if(!"ACTIVE".equals(text(recipient.get("institution_status"))))
          return new ShareReadiness(false,"Every revenue-share recipient must be an active institution.");
        String type=text(recipient.get("calculation_type"));
        long share;
        if("PERCENTAGE".equals(type)) {
          int bps=(int) number(recipient.get("percentage_basis_points"));
          if(bps<1||bps>10_000||recipient.get("flat_amount_paise")!=null)
            return new ShareReadiness(false,"A percentage recipient has invalid percentage/flat fields.");
          percentageBps=Math.addExact(percentageBps,bps);
          if(percentageBps>10_000) return new ShareReadiness(false,"Percentage revenue shares exceed 100% of the distributable pool.");
          share=percentageOf(poolPaise,bps);
        } else if("FLAT".equals(type)) {
          long flat=number(recipient.get("flat_amount_paise"));
          if(flat<=0||recipient.get("percentage_basis_points")!=null)
            return new ShareReadiness(false,"A flat-share recipient must have a positive amount and no percentage.");
          share=flat;
        } else return new ShareReadiness(false,"The approved policy contains an unsupported recipient calculation type.");
        allocated=Math.addExact(allocated,share);
      }
    } catch(RuntimeException invalidPolicy) {
      return new ShareReadiness(false,"The revenue-share policy cannot be calculated safely: "+clean(invalidPolicy.getMessage()));
    }
    if(allocated>poolPaise)
      return new ShareReadiness(false,"Institution allocations exceed the current after-tax distributable pool at this offer price.");
    return new ShareReadiness(true,"The approved policy has active recipients and its current allocations fit the distributable pool.");
  }

  private boolean hasPublishedTemplate(Long legalEntityId) {
    if(legalEntityId==null) {
      Boolean platform=jdbc.queryForObject("""
          select exists(select 1 from invoice_template t
            join invoice_template_version v on v.template_id=t.id and v.status='PUBLISHED'
            where t.scope='PLATFORM' and t.legal_entity_id is null)
          """,Boolean.class);
      return Boolean.TRUE.equals(platform);
    }
    Boolean ready=jdbc.queryForObject("""
        select exists(select 1 from invoice_template t
          join invoice_template_version v on v.template_id=t.id and v.status='PUBLISHED'
          where (t.scope='SELLER' and t.legal_entity_id=?)
             or (t.scope='PLATFORM' and t.legal_entity_id is null))
        """,Boolean.class,legalEntityId);
    return Boolean.TRUE.equals(ready);
  }

  private boolean hasApprovedCustomState(Object rawJson) {
    try {
      JsonNode config=mapper.readTree(text(rawJson));
      String state=config.path("caApprovedPlaceOfSupplyStateCode").asText("");
      return state.matches("[0-9]{2}");
    } catch(Exception ignored) {
      return false;
    }
  }

  private String contentFingerprint(long programId) {
    String value=jdbc.queryForObject("""
        select md5(coalesce(string_agg(entity_type||':'||entity_id::text||':'||row_json,E'\n'
            order by entity_type,entity_id),'')) as fingerprint
        from (
          select 'program'::text entity_type,p.id entity_id,to_jsonb(p)::text row_json
          from curriculum_program p where p.id=?
          union all
          select 'subject',s.id,to_jsonb(ps)::text||to_jsonb(s)::text
          from curriculum_program_subject ps
          join curriculum_program p on p.id=ps.program_id
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
            and (s.program_id=ps.program_id or s.program_id is null)
          where ps.program_id=?
          union all
          select 'chapter',ch.id,to_jsonb(ch)::text
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
          join curriculum_chapter ch on ch.subject_id=s.id where ps.program_id=?
          union all
          select 'lesson',l.id,to_jsonb(l)::text
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
          join curriculum_chapter ch on ch.subject_id=s.id
          join lesson l on l.chapter_id=ch.id where ps.program_id=?
        ) content_rows
        """,String.class,programId,programId,programId,programId);
    return value==null?"d41d8cd98f00b204e9800998ecf8427e":value.trim();
  }

  private static long percentageOf(long amountPaise,int basisPoints) {
    return BigDecimal.valueOf(amountPaise).multiply(BigDecimal.valueOf(basisPoints))
        .divide(BASIS_POINTS,0,RoundingMode.HALF_UP).longValueExact();
  }

  private static void add(List<Map<String,Object>> checks,String code,String label,boolean passed,String message) {
    Map<String,Object> item=new LinkedHashMap<>();
    item.put("code",code);
    item.put("label",label);
    item.put("passed",passed);
    item.put("required",true);
    item.put("message",message==null?"":message);
    checks.add(item);
  }

  private static long number(Object raw) {
    if(raw instanceof Number n) return n.longValue();
    try{return Long.parseLong(String.valueOf(raw));}
    catch(RuntimeException ignored){return 0L;}
  }
  private static String text(Object raw){return raw==null?"":String.valueOf(raw);}
  private static String clean(String raw){return raw==null?"":raw.trim();}

  private record FinancialState(boolean hasValidTaxQuote,long afterTaxPoolPaise) {}
  private record ShareReadiness(boolean ready,String message) {}
}
