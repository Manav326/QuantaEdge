package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
      @Value("${app.razorpay.key-id:}") String razorpayKeyId,
      @Value("${app.razorpay.key-secret:}") String razorpayKeySecret,
      @Value("${app.razorpay.webhook-secret:}") String razorpayWebhookSecret) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.razorpayKeyId = clean(razorpayKeyId);
    this.razorpayKeySecret = clean(razorpayKeySecret);
    this.razorpayWebhookSecret = clean(razorpayWebhookSecret);
  }

  public Map<String,Object> inspectAssignment(long assignmentId,long institutionScope) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select a.id as assignment_id,a.institution_id,a.status as assignment_status,
          r.id as release_id,r.release_state,r.content_fingerprint,r.updated_at as release_updated_at,
          t.display_name as institution_name,t.status as institution_status,
          p.id as course_id,p.code as course_code,p.display_name as course_name,p.status as course_status,p.course_type_code,
          coalesce((select delivery_mode from curriculum_program_delivery_mode where program_id=p.id),'LEARNING_CONTENT') as delivery_mode,
          (select count(distinct ps.subject_id)
             from curriculum_program_subject ps join curriculum_subject s
               on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
               and (s.program_id=ps.program_id or s.program_id is null)
            where ps.program_id=p.id) as subject_count,
          (select count(distinct ps.subject_id)
             from curriculum_program_subject ps
             join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
               and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
             join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
               and ch.content_status='PUBLISHED'
             join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where ps.program_id=p.id) as subjects_with_published_lessons,
          (select count(distinct ch.id)
             from curriculum_program_subject ps join curriculum_subject s
               on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
               and (s.program_id=ps.program_id or s.program_id is null)
             join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
            where ps.program_id=p.id) as published_chapter_count,
          (select count(distinct l.id)
             from curriculum_program_subject ps join curriculum_subject s
               on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
               and (s.program_id=ps.program_id or s.program_id is null)
             join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
             join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where ps.program_id=p.id) as published_lesson_count,
          o.id as offer_id
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join curriculum_program p on p.id=r.program_id
        join institution_tenant t on t.id=a.institution_id
        left join institution_marketplace_offer o on o.institution_id=a.institution_id
          and o.assignment_id=a.id
        where a.id=? and a.institution_id=?
        """,assignmentId,institutionScope);
    if(rows.isEmpty()) throw new IllegalArgumentException("The course assignment was not found for this institution.");
    Map<String,Object> row=rows.getFirst();
    if(row.get("offer_id") instanceof Number offerId) return inspect(offerId.longValue(),institutionScope);

    List<Map<String,Object>> checks=new ArrayList<>();
    add(checks,"INSTITUTION_ACTIVE","Institution is active",
        "ACTIVE".equals(text(row.get("institution_status"))),
        "Activate or resolve the institution in HRMS before launching its course.");
    add(checks,"COURSE_ACTIVE","Course is active",
        "ACTIVE".equals(text(row.get("course_status")))&&!"GENERAL".equalsIgnoreCase(text(row.get("course_code"))),
        "The course must be an active, independently selectable course product.");
    String releaseState=text(row.get("release_state"));
    boolean qualityApproved=List.of("QUALITY_APPROVED","SHAREABLE").contains(releaseState);
    add(checks,"CONTENT_QUALITY_REVIEW","HRMS content-quality review is complete",qualityApproved,
        "IN_QUALITY_REVIEW".equals(releaseState)
            ? "HRMS must record the content-quality decision in this institution's Marketplace launch checklist."
            : "The source institution must submit the course release and pass HRMS content-quality review before it can be shared.");
    boolean originApproved="SHAREABLE".equals(releaseState);
    add(checks,"ORIGIN_RELEASE_APPROVAL","Origin institution approved this release for sharing",originApproved,
        "QUALITY_APPROVED".equals(releaseState)
            ? "Quality review passed. The originating institution admin must approve the release in its Institution portal before HRMS can publish it."
            : "The course is not yet a shareable release. Complete HRMS content-quality review, then obtain origin-institution approval.");
    add(checks,"ASSIGNMENT_AND_RELEASE","Active assignment to a shareable course release exists",
        "ASSIGNED".equals(text(row.get("assignment_status")))&&originApproved,
        "The institution needs an active assignment to a platform-reviewed, origin-approved SHAREABLE course release.");
    long courseId=number(row.get("course_id"));
    String savedFingerprint=text(row.get("content_fingerprint"));
    // Origin assignments and legacy releases are not eligible for listing until their lifecycle
    // gates pass. Defer the full graph hash until HRMS quality approval; the dedicated quality
    // review queue still verifies the submitted fingerprint before a reviewer can approve it.
    boolean fingerprintApplicable=List.of("QUALITY_APPROVED","SHAREABLE").contains(releaseState);
    boolean contentUnchanged=fingerprintApplicable
        &&contentFingerprintMatches(courseId,savedFingerprint,row.get("release_updated_at"));
    add(checks,"CONTENT_REVIEW_CURRENT","Reviewed course content is current",contentUnchanged,
        fingerprintApplicable
            ? "The course content has changed since quality review or has no reviewed fingerprint. Resubmit the release for platform quality review."
            : "This release has not completed HRMS quality approval. Content-fingerprint comparison is deferred until quality review passes.");
    addPackageRightsCheck(checks,courseId);
    long subjectCount=number(row.get("subject_count"));
    long subjectsWithLessons=number(row.get("subjects_with_published_lessons"));
    long chapterCount=number(row.get("published_chapter_count"));
    long lessonCount=number(row.get("published_lesson_count"));
    String courseTypeCode=text(row.get("course_type_code"));
    boolean assessmentOnly="ASSESSMENT".equals(courseTypeCode)
        &&"ASSESSMENT_ONLY".equals(text(row.get("delivery_mode")));
    boolean k12Course="K12".equals(courseTypeCode)
        &&"LEARNING_CONTENT".equals(text(row.get("delivery_mode")));
    boolean requiredContentReady=Boolean.TRUE.equals(jdbc.queryForObject(
        "select course_marketplace_content_type_ready(?)",Boolean.class,courseId));
    add(checks,"PUBLISHED_LEARNING_CONTENT",
        assessmentOnly?"All test-pack assessments are published and reviewed"
            : k12Course?"Every active mapped subject has published lessons":"Course-type Marketplace readiness is not supported",
        requiredContentReady,
        assessmentOnly
            ? "Publish every assessment in every test pack after human review of questions and answer keys before listing this course."
            : k12Course
                ? "Publish reviewed lessons in every active subject mapped to this course."
                : "Keep this course unlisted until its registered hierarchy has a complete importer and server-side publication-readiness contract.");
    add(checks,"OFFER_TERMS","Marketplace offer is configured",false,
        "Create a draft marketplace offer and explicitly set its listing title, description and INR price.");
    List<String> blockers=checks.stream().filter(check->!Boolean.TRUE.equals(check.get("passed")))
        .map(check->text(check.get("label"))+": "+text(check.get("message"))).toList();
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("assignmentId",assignmentId);
    result.put("offerId",null);
    result.put("releaseId",number(row.get("release_id")));
    result.put("releaseState",text(row.get("release_state")));
    result.put("institutionId",institutionScope);
    result.put("institutionName",text(row.get("institution_name")));
    result.put("courseId",courseId);
    result.put("courseName",text(row.get("course_name")));
    result.put("listingTitle",text(row.get("course_name")));
    result.put("description","");
    result.put("offerStatus","NOT_CONFIGURED");
    result.put("pricePaise",null);
    result.put("currency","INR");
    result.put("ready",false);
    result.put("checks",checks);
    result.put("blockingIssues",blockers);
    result.put("message","A marketplace draft must be created before the complete launch checklist can be evaluated.");
    return result;
  }

  public Map<String,Object> inspect(long offerId, Long institutionScope) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select o.id as offer_id,o.institution_id,o.assignment_id,o.course_release_id,o.course_id,
          o.title_override,o.description,o.price_paise,o.currency,o.status as offer_status,
          o.hrms_published_by_staff_id,
          qe_current_course_offer_revision(o.id) as current_offer_version,
          t.display_name as institution_name,t.status as institution_status,
          a.status as assignment_status,r.release_state,r.content_fingerprint,r.updated_at as release_updated_at,
          p.status as course_status,coalesce(identity.stable_course_key,p.code) as course_code,
          p.display_name as course_name,p.course_type_code,
          identity.public_id::text as course_identity_public_id,
          course_marketplace_content_type_ready(p.id) as course_type_publish_ready,
          coalesce((select delivery_mode from curriculum_program_delivery_mode where program_id=p.id),'LEARNING_CONTENT') as delivery_mode,
          (select count(distinct ps.subject_id)
             from curriculum_program_subject ps
             join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
               and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
            where ps.program_id=p.id) as subject_count,
          (select count(distinct ps.subject_id)
             from curriculum_program_subject ps
             join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
               and s.active=true and (s.program_id=ps.program_id or s.program_id is null)
             join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
               and ch.content_status='PUBLISHED'
             join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where ps.program_id=p.id) as subjects_with_published_lessons,
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
        left join institution_course_identity identity on identity.id=p.course_identity_id
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
    add(checks,"CURRENT_OFFER_REVISION","Offer belongs to the current eligible release",
        Boolean.TRUE.equals(offer.get("current_offer_version")),
        "This offer is obsolete or no longer points to the current eligible release. Use the current HRMS assignment and create or configure its offer instead.");
    String releaseState=text(offer.get("release_state"));
    boolean qualityApproved=List.of("QUALITY_APPROVED","SHAREABLE").contains(releaseState);
    add(checks,"CONTENT_QUALITY_REVIEW","HRMS content-quality review is complete",qualityApproved,
        "IN_QUALITY_REVIEW".equals(releaseState)
            ? "HRMS must record the content-quality decision in this institution's Marketplace launch checklist."
            : "The source institution must submit the course release and pass HRMS content-quality review before it can be shared.");
    boolean originApproved="SHAREABLE".equals(releaseState);
    add(checks,"ORIGIN_RELEASE_APPROVAL","Origin institution approved this release for sharing",originApproved,
        "QUALITY_APPROVED".equals(releaseState)
            ? "Quality review passed. The originating institution admin must approve the release in its Institution portal before HRMS can publish it."
            : "The course is not yet a shareable release. Complete HRMS content-quality review, then obtain origin-institution approval.");
    add(checks,"ASSIGNMENT_AND_RELEASE","Active assignment to a shareable course release exists",
        "ASSIGNED".equals(text(offer.get("assignment_status")))&&originApproved,
        "The institution needs an active assignment to a platform-reviewed, origin-approved SHAREABLE course release.");
    long courseId = number(offer.get("course_id"));
    String savedFingerprint = text(offer.get("content_fingerprint"));
    // Fingerprints are only authoritative after HRMS quality approval. Legacy-migrated,
    // draft, and in-review releases already fail the preceding lifecycle gate, so recomputing
    // a full content graph fingerprint here adds no publication safety and can exhaust database
    // memory for large historical K-12 courses.
    boolean fingerprintApplicable = List.of("QUALITY_APPROVED","SHAREABLE").contains(releaseState);
    boolean contentUnchanged = fingerprintApplicable
        && contentFingerprintMatches(courseId,savedFingerprint,offer.get("release_updated_at"));
    add(checks,"CONTENT_REVIEW_CURRENT","Reviewed course content is current",contentUnchanged,
        fingerprintApplicable
            ? "The course content has changed since quality review or has no reviewed fingerprint. Resubmit the release for platform quality review."
            : "This release has not completed HRMS quality approval. Content-fingerprint comparison is deferred until quality review passes.");
    addPackageRightsCheck(checks,courseId);
    long subjectCount = number(offer.get("subject_count"));
    long subjectsWithLessons = number(offer.get("subjects_with_published_lessons"));
    long chapterCount = number(offer.get("published_chapter_count"));
    long lessonCount = number(offer.get("published_lesson_count"));
    String courseTypeCode=text(offer.get("course_type_code"));
    boolean assessmentOnly="ASSESSMENT".equals(courseTypeCode)
        &&"ASSESSMENT_ONLY".equals(text(offer.get("delivery_mode")));
    boolean k12Course="K12".equals(courseTypeCode)
        &&"LEARNING_CONTENT".equals(text(offer.get("delivery_mode")));
    boolean requiredContentReady=Boolean.TRUE.equals(jdbc.queryForObject(
        "select course_marketplace_content_type_ready(?)",Boolean.class,number(offer.get("course_id"))));
    add(checks,"PUBLISHED_LEARNING_CONTENT",
        assessmentOnly?"All test-pack assessments are published and reviewed"
            : k12Course?"Every active mapped subject has published lessons":"Course-type Marketplace readiness is not supported",
        requiredContentReady,
        assessmentOnly
            ? "Publish every assessment in every test pack after human review of questions and answer keys before listing this course."
            : k12Course
                ? "Publish reviewed lessons in every active subject mapped to this course."
                : "Keep this course unlisted until its registered hierarchy has a complete importer and server-side publication-readiness contract.");

    String courseName = text(offer.get("title_override")).isBlank()
        ? text(offer.get("course_name")) : text(offer.get("title_override"));
    long pricePaise = number(offer.get("price_paise"));
    String currency = text(offer.get("currency")).toUpperCase(Locale.ROOT);
    add(checks,"OFFER_TERMS","Offer title, description, price and currency are valid",
        !courseName.isBlank() && !text(offer.get("description")).isBlank()
            && pricePaise >= 0 && "INR".equals(currency),
        "Set a non-blank listing title and description, and an explicit non-negative INR price. Use zero only for an intentionally free offer.");

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
      } else if(!financial.discountPoolCompatible()) {
        add(checks,"SHARE_POOL_VALIDATION","Revenue shares fit the approved after-tax pool",false,
            "One or more approved discounts make the distributable pool invalid or below the configured flat-share commitments.");
      } else {
        add(checks,"SHARE_POOL_VALIDATION","Revenue shares fit the approved after-tax pool",
            shares.ready(),shares.message());
      }
    }

    List<String> blockers = checks.stream().filter(check -> !Boolean.TRUE.equals(check.get("passed")))
        .map(check -> text(check.get("label"))+": "+text(check.get("message"))).toList();
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("assignmentId",number(offer.get("assignment_id")));
    result.put("offerId",offerId);
    result.put("releaseId",number(offer.get("course_release_id")));
    result.put("releaseState",text(offer.get("release_state")));
    result.put("institutionId",institutionId);
    result.put("institutionName",text(offer.get("institution_name")));
    result.put("courseId",courseId);
    result.put("courseCode",text(offer.get("course_code")));
    result.put("courseName",courseName);
    result.put("courseIdentityPublicId",text(offer.get("course_identity_public_id")));
    result.put("listingTitle",courseName);
    result.put("description",text(offer.get("description")));
    result.put("offerStatus",text(offer.get("offer_status")));
    result.put("pricePaise",pricePaise);
    result.put("currency",currency);
    result.put("ready",blockers.isEmpty());
    result.put("marketplaceVisible",
        "PUBLISHED".equals(text(offer.get("offer_status")))
            &&offer.get("hrms_published_by_staff_id") instanceof Number
            &&"ACTIVE".equals(text(offer.get("institution_status")))
            &&"ACTIVE".equals(text(offer.get("course_status")))
            &&!"GENERAL".equalsIgnoreCase(text(offer.get("course_code")))
            &&"ASSIGNED".equals(text(offer.get("assignment_status")))
            &&"SHAREABLE".equals(releaseState)
            &&contentUnchanged
            &&Boolean.TRUE.equals(offer.get("current_offer_version"))
            &&Boolean.TRUE.equals(offer.get("course_type_publish_ready")));
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
      add(checks,"PUBLISHED_INVOICE_TEMPLATE","An invoice template is published",hasPublishedTemplateForInstitution(institutionId),
          "Publish an approved platform or seller invoice template in HRMS Invoice Design Studio.");
      add(checks,"DISCOUNT_POOL_COMPATIBILITY","Approved discounts fit the verified tax/share model",false,
          "HRMS must complete seller/tax readiness before approved coupon amounts can be checked against the distributable pool.");
      return new FinancialState(false,0L,false);
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
    CourseTaxCalculator.Treatment treatment = null;
    int gstRate = 0;
    int cessRate = 0;
    boolean includesTax = Boolean.TRUE.equals(profile.get("price_includes_tax"));
    try {
      treatment = CourseTaxCalculator.Treatment.valueOf(text(profile.get("tax_treatment")));
      gstRate = (int) number(profile.get("gst_rate_basis_points"));
      cessRate = (int) number(profile.get("cess_rate_basis_points"));
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

    if(quote==null||treatment==null||!taxOk||!invoiceModeSupported) {
      add(checks,"DISCOUNT_POOL_COMPATIBILITY","Approved discounts fit the verified tax/share model",false,
          "HRMS must correct the tax quote before active approved discounts can be checked against the revenue-share pool.");
      return new FinancialState(false,0L,false);
    }
    DiscountPoolReadiness discountReadiness=inspectDiscountPoolCompatibility(
        offerId,currency,pricePaise,treatment,gstRate,cessRate,includesTax);
    add(checks,"DISCOUNT_POOL_COMPATIBILITY","Approved discounts fit the verified tax/share model",
        discountReadiness.compatible(),discountReadiness.message());
    return new FinancialState(true,discountReadiness.minimumAfterTaxPoolPaise(),discountReadiness.compatible());
  }

  private DiscountPoolReadiness inspectDiscountPoolCompatibility(
      long offerId,String currency,long listedPricePaise,CourseTaxCalculator.Treatment treatment,
      int gstRate,int cessRate,boolean priceIncludesTax) {
    CourseTaxCalculator.Result standardQuote=CourseTaxCalculator.quote(
        listedPricePaise,0L,treatment,gstRate,cessRate,priceIncludesTax);
    long minimumPool=standardQuote.customerPayablePaise()-standardQuote.outputTaxPaise();
    List<Map<String,Object>> ambiguousCodes=jdbc.queryForList("""
        select upper(code) as normalized_code,count(*) as effective_versions
        from course_discount_offer
        where institution_offer_id=? and currency=? and status='APPROVED'
          and effective_from<=now() and (effective_until is null or effective_until>now())
        group by upper(code)
        having count(*)>1
        order by upper(code)
        limit 1
        """,offerId,currency.toUpperCase(Locale.ROOT));
    if(!ambiguousCodes.isEmpty()) {
      return new DiscountPoolReadiness(false,minimumPool,
          "More than one approved version of discount code "+text(ambiguousCodes.getFirst().get("normalized_code"))
              +" is effective. HRMS must resolve the overlap before launch because checkout cannot choose a unique version.");
    }
    List<Map<String,Object>> discounts=jdbc.queryForList("""
        select d.id,d.code,d.version,d.discount_type,d.percentage_basis_points,
          d.flat_amount_paise,d.max_discount_paise,d.min_listed_price_paise,d.max_redemptions,
          (select count(*) from course_discount_redemption red
            where red.discount_offer_id=d.id and red.status in ('RESERVED','REDEEMED')) as redemption_count
        from course_discount_offer d
        where d.institution_offer_id=? and d.currency=? and d.status='APPROVED'
          and d.effective_from<=now() and (d.effective_until is null or d.effective_until>now())
        order by upper(d.code),d.version desc,d.id desc
        """,offerId,currency.toUpperCase(Locale.ROOT));
    for(Map<String,Object> discount:discounts) {
      long minimum=number(discount.get("min_listed_price_paise"));
      if(listedPricePaise<minimum) continue;
      Object maxRaw=discount.get("max_redemptions");
      if(maxRaw instanceof Number max
          && number(discount.get("redemption_count"))>=max.longValue()) continue;
      long amount;
      try {
        amount=CourseDiscountCalculator.calculate(
            listedPricePaise,text(discount.get("discount_type")),
            discount.get("percentage_basis_points") instanceof Number b?(int)b.longValue():null,
            discount.get("flat_amount_paise") instanceof Number flat?flat.longValue():null,
            discount.get("max_discount_paise") instanceof Number cap?cap.longValue():null);
      } catch(RuntimeException invalidDiscount) {
        return new DiscountPoolReadiness(false,minimumPool,
            "Approved discount "+text(discount.get("code"))+" has invalid terms: "+clean(invalidDiscount.getMessage()));
      }
      if(amount<=0||amount>=listedPricePaise) {
        return new DiscountPoolReadiness(false,minimumPool,
            "Approved discount "+text(discount.get("code"))+" leaves no positive payable balance. Correct or retire that discount in HRMS.");
      }
      CourseTaxCalculator.Result discountedQuote=CourseTaxCalculator.quote(
          listedPricePaise,amount,treatment,gstRate,cessRate,priceIncludesTax);
      long pool=discountedQuote.customerPayablePaise()-discountedQuote.outputTaxPaise();
      minimumPool=Math.min(minimumPool,pool);
    }
    ShareReadiness shareReadiness=inspectSharePolicy(offerId,currency,minimumPool);
    if(!shareReadiness.ready()) {
      return new DiscountPoolReadiness(false,minimumPool,
          "The smallest active approved-discount pool is not compatible with current revenue shares: "+shareReadiness.message());
    }
    return new DiscountPoolReadiness(true,minimumPool,
        "The list-price pool and every currently usable approved-discount pool preserve the configured after-tax revenue-share commitments.");
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

  boolean hasPublishedTemplateForInstitution(long institutionId) {
    Boolean ready=jdbc.queryForObject("""
        select exists(
          select 1
          from invoice_template t
          join invoice_template_version v on v.template_id=t.id and v.status='PUBLISHED'
          where (t.scope='PLATFORM' and t.legal_entity_id is null)
             or (t.scope='SELLER' and exists(
               select 1 from billing_legal_entity le
               where le.id=t.legal_entity_id and le.status='ACTIVE'
                 and (le.entity_kind='PLATFORM' or le.institution_id=?)
             ))
        )
        """,Boolean.class,institutionId);
    return Boolean.TRUE.equals(ready);
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

  public boolean contentFingerprintMatches(long programId,String savedFingerprint) {
    return savedFingerprint!=null&&!savedFingerprint.isBlank()
        &&savedFingerprint.trim().equals(contentFingerprint(programId));
  }

  /**
   * Validate with the release verification marker when possible. V180 freezes the graph once it
   * enters review; V196 refreshed current fingerprints and updated_at. Older rows fall back to
   * the canonical full comparison so pre-existing stale releases remain blocked.
   */
  public boolean contentFingerprintMatches(long programId,String savedFingerprint,Object releaseUpdatedAt) {
    if(savedFingerprint==null||savedFingerprint.isBlank()||releaseUpdatedAt==null)return false;
    Boolean matches=jdbc.queryForObject(
        "select course_release_fingerprint_current(?,?,?)",Boolean.class,
        programId,savedFingerprint.trim(),releaseUpdatedAt);
    return Boolean.TRUE.equals(matches);
  }

  private String contentFingerprint(long programId) {
    String value=jdbc.queryForObject("select course_content_fingerprint(?)",String.class,programId);
    return value==null?"d41d8cd98f00b204e9800998ecf8427e":value.trim();
  }

  private static long percentageOf(long amountPaise,int basisPoints) {
    return BigDecimal.valueOf(amountPaise).multiply(BigDecimal.valueOf(basisPoints))
        .divide(BASIS_POINTS,0,RoundingMode.HALF_UP).longValueExact();
  }

  /**
   * If a course originated from the nested Course Studio package importer, HRMS must verify its
   * source-rights status before Marketplace distribution. Legacy courses without an import ledger
   * retain the existing review path; an imported package with missing rights is not cleared.
   */
  private void addPackageRightsCheck(List<Map<String,Object>> checks,long courseId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select imported_package.package_id,imported_package.package_version,
        coalesce(imported_package.editorial_metadata->>'source_rights_status','UNCONFIRMED') as source_rights_status,
        imported_package.editorial_metadata->>'rights_evidence_url' as rights_evidence_url,
        imported_package.rights_reviewed_by_staff_id,
        imported_package.rights_reviewed_at,
        imported_package.rights_review_notes
      from institution_course_package_import imported_package
      join curriculum_program source_program on source_program.id=imported_package.course_id
      where imported_package.state='IMPORTED'
        and exists(
          select 1 from curriculum_program current_program
          where current_program.id=? and (
            current_program.id=imported_package.course_id
            or (current_program.course_identity_id is not null
              and current_program.course_identity_id=source_program.course_identity_id)
          )
        )
      order by imported_package.imported_at desc,imported_package.id desc
      limit 1
      """,courseId);
    if(rows.isEmpty())return;
    Map<String,Object> row=rows.getFirst();
    String status=text(row.get("source_rights_status"));
    boolean cleared=isVerifiedPackageRightsClearance(status,
        text(row.get("rights_evidence_url")),row.get("rights_reviewed_by_staff_id"),
        row.get("rights_reviewed_at"),text(row.get("rights_review_notes")));
    String message=cleared
        ?"HRMS recorded a cleared rights decision with reviewer attribution, notes, and valid HTTPS evidence."
        :"Package "+text(row.get("package_id"))+" version "+text(row.get("package_version"))
            +" has rights state "+(status.isBlank()?"UNCONFIRMED":status)
            +" without a complete evidence-backed HRMS review. HRMS must record a cleared decision, reviewer attribution, notes, and valid HTTPS evidence before Marketplace distribution.";
    add(checks,"SOURCE_RIGHTS_CLEARANCE","Course package distribution rights are cleared",cleared,message);
  }

  static boolean isVerifiedPackageRightsClearance(
      String status,String evidenceUrl,Object reviewerId,Object reviewedAt,String reviewNotes) {
    if(!List.of("CLEARED_FOR_INTERNAL_USE","CLEARED_FOR_PUBLIC_REPOSITORY").contains(status)
        ||!(reviewerId instanceof Number reviewer&&reviewer.longValue()>0L)
        ||reviewedAt==null||reviewNotes==null||reviewNotes.trim().length()<5) {
      return false;
    }
    try {
      URI evidence=URI.create(evidenceUrl==null?"":evidenceUrl.trim());
      return "https".equalsIgnoreCase(evidence.getScheme())
          &&evidence.getHost()!=null&&evidence.getUserInfo()==null;
    } catch(IllegalArgumentException ex) {
      return false;
    }
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

  private record FinancialState(boolean hasValidTaxQuote,long afterTaxPoolPaise,boolean discountPoolCompatible) {}
  private record DiscountPoolReadiness(boolean compatible,long minimumAfterTaxPoolPaise,String message) {}
  private record ShareReadiness(boolean ready,String message) {}
}
