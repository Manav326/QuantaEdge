package com.quantaedge.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Institution-first course lifecycle:
 * source course -> independent content quality review -> origin approval/shareability ->
 * HRMS assignment -> destination institution approval -> local publication.
 *
 * Institution-admin scope always comes from institution_admin_session. The client tenant header
 * is not used as authentication or authorization by this controller.
 */
@RestController
@RequestMapping("/api/v1")
public class InstitutionCourseLifecycleController {
  private final JdbcTemplate jdbc;
  private final InstitutionAuthService institutionAuth;
  private final AuthorizationService authorization;

  public InstitutionCourseLifecycleController(
      JdbcTemplate jdbc, InstitutionAuthService institutionAuth, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.institutionAuth = institutionAuth;
    this.authorization = authorization;
  }

  /** Create an institution-owned course master plus its first draft release. */
  @PostMapping("/institution/courses")
  @Transactional
  public Map<String,Object> createCourse(
      @RequestBody Map<String,Object> body, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    long classId = requiredPositiveLong(body, "classId");
    String displayName = requiredText(body, "displayName", 2, 200);
    List<Long> subjectIds = positiveLongList(body.get("subjectIds"), "subjectIds");
    if (subjectIds.isEmpty()) throw new IllegalArgumentException("Choose at least one subject for this course.");

    Long classCount = jdbc.queryForObject(
        "select count(*) from curriculum_class where id=? and active=true", Long.class, classId);
    if (classCount == null || classCount != 1) throw new IllegalArgumentException("Choose an active curriculum class.");

    for (Long subjectId : subjectIds) {
      Long validSubject = jdbc.queryForObject("""
          select count(*) from curriculum_subject
          where id=? and class_id=? and active=true
          """, Long.class, subjectId, classId);
      if (validSubject == null || validSubject != 1) {
        throw new IllegalArgumentException("Every selected subject must be active and belong to the selected class.");
      }
    }

    String code = "INS_" + principal.institutionId() + "_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    Long programId = jdbc.queryForObject("""
        insert into curriculum_program(
          class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix
        ) values(?,?,?,null,'CURRENT','ACTIVE',?, 'INS')
        returning id
        """, Long.class, classId, code, displayName,
        "Institution-origin course. Source institution id=" + principal.institutionId());

    int position = 0;
    for (Long subjectId : subjectIds) {
      jdbc.update("""
          insert into curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order)
          values(?,?,?,true,?)
          """, programId, classId, subjectId, position++);
    }

    String fingerprint = contentFingerprint(programId);
    Long releaseId = jdbc.queryForObject("""
        insert into institution_course_release(
          source_institution_id,program_id,release_version,release_state,content_fingerprint
        ) values(?,?,1,'DRAFT',?)
        returning id
        """, Long.class, principal.institutionId(), programId, fingerprint);
    auditInstitution(principal, "INSTITUTION_COURSE_CREATED", "COURSE_RELEASE",
        String.valueOf(releaseId), "{}");

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("courseId", programId);
    result.put("courseCode", code);
    result.put("coursePublicId", jdbc.queryForObject(
        "select public_id from curriculum_program where id=?", String.class, programId));
    result.put("releaseId", releaseId);
    result.put("releaseState", "DRAFT");
    result.put("message", "Course created. Add and publish its learning content, then submit the release for quality review.");
    return result;
  }

  /** Register another version only when this institution is the already-recorded course owner. */
  @PostMapping("/institution/courses/{courseId}/releases")
  @Transactional
  public Map<String,Object> createRelease(
      @PathVariable long courseId, @RequestBody(required=false) Map<String,Object> body,
      HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> program = firstRow("""
        select id,code,display_name,status from curriculum_program
        where id=? and code<>'GENERAL' and status='ACTIVE'
        """, courseId);
    if (program.isEmpty()) throw new IllegalArgumentException("An active course was not found.");
    Long foreignRelease = jdbc.queryForObject("""
        select count(*) from institution_course_release
        where program_id=? and source_institution_id<>?
        """, Long.class, courseId, principal.institutionId());
    if (foreignRelease != null && foreignRelease > 0) {
      throw new SecurityException("This course belongs to another source institution. HRMS must assign its shareable release.");
    }

    Integer nextVersion = jdbc.queryForObject("""
        select coalesce(max(release_version),0)+1 from institution_course_release
        where source_institution_id=? and program_id=?
        """, Integer.class, principal.institutionId(), courseId);
    int version = nextVersion == null ? 1 : nextVersion;
    String fingerprint = contentFingerprint(courseId);
    Long releaseId = jdbc.queryForObject("""
        insert into institution_course_release(
          source_institution_id,program_id,release_version,release_state,content_fingerprint
        ) values(?,?,?,'DRAFT',?)
        returning id
        """, Long.class, principal.institutionId(), courseId, version, fingerprint);
    auditInstitution(principal, "COURSE_RELEASE_CREATED", "COURSE_RELEASE",
        String.valueOf(releaseId), "{\"courseId\":" + courseId + ",\"version\":" + version + "}");
    return Map.of("releaseId", releaseId, "courseId", courseId, "releaseVersion", version, "releaseState", "DRAFT");
  }

  @GetMapping("/institution/courses/releases")
  public List<Map<String,Object>> originReleases(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    return jdbc.queryForList("""
        select r.id as release_id,r.public_id as release_public_id,r.program_id as course_id,
          p.public_id as course_public_id,p.code as course_code,p.display_name as course_name,
          p.status as course_status,r.release_version,r.release_state,r.content_fingerprint,
          r.submitted_at,r.quality_reviewed_at,r.quality_review_notes,r.origin_approved_at,
          r.shareable_at,r.created_at,r.updated_at,r.legacy_migration
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id
        where r.source_institution_id=?
        order by r.updated_at desc,r.id desc
        """, principal.institutionId());
  }

  /** Institution-origin submission is separate from content-quality review. */
  @PostMapping("/institution/courses/releases/{releaseId}/submit")
  @Transactional
  public Map<String,Object> submitOriginRelease(
      @PathVariable long releaseId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> release = sourceRelease(principal, releaseId);
    String state = String.valueOf(release.get("release_state"));
    if (!Set.of("DRAFT","REJECTED").contains(state)) {
      throw new IllegalArgumentException("Only a draft or rejected release can be submitted for quality review.");
    }
    String fingerprint = contentFingerprint(((Number)release.get("program_id")).longValue());
    int updated = jdbc.update("""
        update institution_course_release
        set release_state='IN_QUALITY_REVIEW',content_fingerprint=?,submitted_by_institution_admin_id=?,
          submitted_at=now(),quality_reviewed_by_staff_id=null,quality_reviewed_at=null,
          quality_review_notes=null,origin_approved_by_admin_id=null,origin_approved_at=null,
          shareable_at=null,updated_at=now()
        where id=? and source_institution_id=? and release_state in ('DRAFT','REJECTED')
        """, fingerprint, principal.adminId(), releaseId, principal.institutionId());
    if (updated != 1) throw new IllegalArgumentException("The release changed concurrently. Refresh and retry.");
    auditInstitution(principal, "COURSE_RELEASE_SUBMITTED_FOR_QUALITY_REVIEW", "COURSE_RELEASE",
        String.valueOf(releaseId), "{}");
    return Map.of("releaseId", releaseId, "releaseState", "IN_QUALITY_REVIEW");
  }

  /** Source institution gives final approval only after independent content-quality approval. */
  @PostMapping("/institution/courses/releases/{releaseId}/share")
  @Transactional
  public Map<String,Object> approveShareableRelease(
      @PathVariable long releaseId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> release = sourceRelease(principal, releaseId);
    if (!"QUALITY_APPROVED".equals(String.valueOf(release.get("release_state")))) {
      throw new IllegalArgumentException("The existing content-quality reviewer must approve this release first.");
    }
    long courseId = ((Number)release.get("program_id")).longValue();
    String actualFingerprint = contentFingerprint(courseId);
    String reviewedFingerprint = String.valueOf(release.get("content_fingerprint"));
    if (!actualFingerprint.equals(reviewedFingerprint)) {
      throw new IllegalArgumentException("Course content changed after quality review. Submit a new review before sharing.");
    }
    int changed = jdbc.update("""
        update institution_course_release
        set release_state='SHAREABLE',origin_approved_by_admin_id=?,origin_approved_at=now(),
          shareable_at=now(),updated_at=now()
        where id=? and source_institution_id=? and release_state='QUALITY_APPROVED'
          and content_fingerprint=?
        """, principal.adminId(), releaseId, principal.institutionId(), actualFingerprint);
    if (changed != 1) throw new IllegalArgumentException("The release changed concurrently. Refresh and retry.");
    auditInstitution(principal, "COURSE_RELEASE_APPROVED_FOR_SHARING", "COURSE_RELEASE",
        String.valueOf(releaseId), "{}");
    return Map.of("releaseId", releaseId, "releaseState", "SHAREABLE",
        "message", "HRMS can now assign this approved release to another institution.");
  }

  @GetMapping("/institution/courses/assignments")
  public List<Map<String,Object>> destinationAssignments(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    return jdbc.queryForList("""
        select a.id as assignment_id,a.status as assignment_status,a.assigned_at,
          r.id as release_id,r.public_id as release_public_id,r.release_version,r.release_state,
          r.source_institution_id,source.tenant_code as source_tenant_code,
          p.id as course_id,p.public_id as course_public_id,p.code as course_code,
          p.display_name as course_name,offer.id as offer_id,offer.public_id as offer_public_id,
          offer.status as offer_status,offer.price_paise,offer.currency,
          offer.title_override,offer.description,offer.approved_at,offer.published_at
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join institution_tenant source on source.id=r.source_institution_id
        join curriculum_program p on p.id=r.program_id
        left join institution_marketplace_offer offer
          on offer.institution_id=a.institution_id and offer.assignment_id=a.id
        where a.institution_id=?
        order by a.assigned_at desc,a.id desc
        """, principal.institutionId());
  }

  /** Saving a destination-local offer never submits, approves, or publishes it. */
  @PutMapping("/institution/courses/assignments/{assignmentId}/offer")
  @Transactional
  public Map<String,Object> saveLocalOffer(
      @PathVariable long assignmentId, @RequestBody Map<String,Object> body,
      HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> assignment = firstRow("""
        select a.id,a.institution_id,a.status as assignment_status,r.id as release_id,
          r.program_id as course_id,r.release_state,r.source_institution_id
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        where a.id=? and a.institution_id=?
        """, assignmentId, principal.institutionId());
    if (assignment.isEmpty()) throw new SecurityException("This course assignment does not belong to the signed-in institution.");
    if (!"ASSIGNED".equals(String.valueOf(assignment.get("assignment_status")))) {
      throw new IllegalArgumentException("This assignment is revoked and cannot have an active offer.");
    }
    long courseId = ((Number)assignment.get("course_id")).longValue();
    long releaseId = ((Number)assignment.get("release_id")).longValue();
    long pricePaise = requiredNonNegativeLong(body, "pricePaise");
    String title = optionalText(body.get("titleOverride"), 200);
    String description = optionalText(body.get("description"), 10000);

    Map<String,Object> existing = firstRow("""
        select id,status from institution_marketplace_offer
        where institution_id=? and assignment_id=?
        """, principal.institutionId(), assignmentId);
    if (!existing.isEmpty()) {
      String existingStatus = String.valueOf(existing.get("status"));
      if (!Set.of("DRAFT","REJECTED","ARCHIVED").contains(existingStatus)) {
        throw new IllegalArgumentException("Only a draft, rejected, or archived offer can be edited. Resolve its review first.");
      }
    }

    Long offerId = jdbc.queryForObject("""
        insert into institution_marketplace_offer(
          institution_id,assignment_id,course_release_id,course_id,title_override,description,
          price_paise,currency,status,legacy_migration,legacy_migration_note
        ) values(?,?,?,?,?,?,?,'INR','DRAFT',false,null)
        on conflict(institution_id,assignment_id) do update set
          title_override=excluded.title_override,description=excluded.description,
          price_paise=excluded.price_paise,currency='INR',status='DRAFT',
          submitted_by_admin_id=null,submitted_at=null,approved_by_admin_id=null,approved_at=null,
          published_by_admin_id=null,published_at=null,rejected_by_admin_id=null,rejected_at=null,
          rejection_reason=null,legacy_migration=false,legacy_migration_note=null,updated_at=now()
        returning id
        """, Long.class, principal.institutionId(), assignmentId, releaseId, courseId,
        title, description, pricePaise);
    auditInstitution(principal, "LOCAL_COURSE_OFFER_SAVED", "MARKETPLACE_OFFER",
        String.valueOf(offerId), "{\"courseId\":" + courseId + ",\"pricePaise\":" + pricePaise + "}");
    return Map.of("offerId", offerId, "courseId", courseId, "institutionId", principal.institutionId(),
        "pricePaise", pricePaise, "currency", "INR", "status", "DRAFT");
  }

  @PostMapping("/institution/courses/offers/{offerId}/submit")
  @Transactional
  public Map<String,Object> submitLocalOffer(@PathVariable long offerId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> offer = ownedOffer(principal, offerId);
    requireAssignmentActive(offer, principal);
    requireOfferReleaseEligibleForLocalInstitution(offer, principal);
    String status = String.valueOf(offer.get("status"));
    if (!Set.of("DRAFT","REJECTED").contains(status)) {
      throw new IllegalArgumentException("Only a draft or rejected offer can be submitted for approval.");
    }
    int changed = jdbc.update("""
        update institution_marketplace_offer
        set status='IN_REVIEW',submitted_by_admin_id=?,submitted_at=now(),
          approved_by_admin_id=null,approved_at=null,published_by_admin_id=null,published_at=null,
          rejected_by_admin_id=null,rejected_at=null,rejection_reason=null,updated_at=now()
        where id=? and institution_id=? and status in ('DRAFT','REJECTED')
        """, principal.adminId(), offerId, principal.institutionId());
    if (changed != 1) throw new IllegalArgumentException("The offer changed concurrently. Refresh and retry.");
    auditInstitution(principal, "LOCAL_COURSE_OFFER_SUBMITTED", "MARKETPLACE_OFFER", String.valueOf(offerId), "{}");
    return Map.of("offerId", offerId, "status", "IN_REVIEW");
  }

  @PostMapping("/institution/courses/offers/{offerId}/approve")
  @Transactional
  public Map<String,Object> approveLocalOffer(@PathVariable long offerId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> offer = ownedOffer(principal, offerId);
    requireAssignmentActive(offer, principal);
    requireOfferReleaseEligibleForLocalInstitution(offer, principal);
    if (!"IN_REVIEW".equals(String.valueOf(offer.get("status")))) {
      throw new IllegalArgumentException("Only an offer in review can be approved.");
    }
    int changed = jdbc.update("""
        update institution_marketplace_offer
        set status='APPROVED',approved_by_admin_id=?,approved_at=now(),updated_at=now()
        where id=? and institution_id=? and status='IN_REVIEW'
        """, principal.adminId(), offerId, principal.institutionId());
    if (changed != 1) throw new IllegalArgumentException("The offer changed concurrently. Refresh and retry.");
    auditInstitution(principal, "LOCAL_COURSE_OFFER_APPROVED", "MARKETPLACE_OFFER", String.valueOf(offerId), "{}");
    return Map.of("offerId", offerId, "status", "APPROVED");
  }

  @PostMapping("/institution/courses/offers/{offerId}/publish")
  @Transactional
  public Map<String,Object> publishLocalOffer(@PathVariable long offerId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> offer = ownedOffer(principal, offerId);
    requireAssignmentActive(offer, principal);
    requireOfferReleaseEligibleForLocalInstitution(offer, principal);
    if (!"APPROVED".equals(String.valueOf(offer.get("status")))) {
      throw new IllegalArgumentException("Only a locally approved offer can be published.");
    }
    int changed = jdbc.update("""
        update institution_marketplace_offer
        set status='PUBLISHED',published_by_admin_id=?,published_at=now(),updated_at=now()
        where id=? and institution_id=? and status='APPROVED'
          and exists (
            select 1 from institution_course_assignment a
            where a.id=institution_marketplace_offer.assignment_id
              and a.institution_id=institution_marketplace_offer.institution_id and a.status='ASSIGNED'
          )
        """, principal.adminId(), offerId, principal.institutionId());
    if (changed != 1) throw new IllegalArgumentException("The assignment or offer changed concurrently. Refresh and retry.");
    auditInstitution(principal, "LOCAL_COURSE_OFFER_PUBLISHED", "MARKETPLACE_OFFER", String.valueOf(offerId), "{}");
    return Map.of("offerId", offerId, "status", "PUBLISHED",
        "message", "This offer is visible only in this institution's marketplace.");
  }

  @PostMapping("/institution/courses/offers/{offerId}/reject")
  @Transactional
  public Map<String,Object> rejectLocalOffer(
      @PathVariable long offerId, @RequestBody Map<String,Object> body, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    Map<String,Object> offer = ownedOffer(principal, offerId);
    String status = String.valueOf(offer.get("status"));
    if (!Set.of("IN_REVIEW","APPROVED").contains(status)) {
      throw new IllegalArgumentException("Only an offer in review or awaiting publication can be rejected.");
    }
    String reason = requiredText(body, "reason", 3, 1000);
    jdbc.update("""
        update institution_marketplace_offer
        set status='REJECTED',rejected_by_admin_id=?,rejected_at=now(),rejection_reason=?,updated_at=now()
        where id=? and institution_id=? and status in ('IN_REVIEW','APPROVED')
        """, principal.adminId(), reason, offerId, principal.institutionId());
    auditInstitution(principal, "LOCAL_COURSE_OFFER_REJECTED", "MARKETPLACE_OFFER", String.valueOf(offerId), "{}");
    return Map.of("offerId", offerId, "status", "REJECTED");
  }

  @PostMapping("/institution/courses/offers/{offerId}/archive")
  @Transactional
  public Map<String,Object> archiveLocalOffer(@PathVariable long offerId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requireInstitutionAdmin(request);
    ownedOffer(principal, offerId);
    int changed = jdbc.update("""
        update institution_marketplace_offer set status='ARCHIVED',updated_at=now()
        where id=? and institution_id=? and status<>'ARCHIVED'
        """, offerId, principal.institutionId());
    if (changed != 1) throw new IllegalArgumentException("The offer is already archived or no longer available.");
    auditInstitution(principal, "LOCAL_COURSE_OFFER_ARCHIVED", "MARKETPLACE_OFFER", String.valueOf(offerId), "{}");
    return Map.of("offerId", offerId, "status", "ARCHIVED");
  }

  /** Existing content QA permission is separate from institution-origin final approval. */
  @PostMapping("/admin/institution-course-releases/{releaseId}/quality-review")
  @Transactional
  public Map<String,Object> qualityReviewRelease(
      @PathVariable long releaseId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    AuthContext reviewer = authorization.requirePermission(context, "CONTENT_REVIEW");
    Object rawApproved = body == null ? null : body.get("approved");
    if (!(rawApproved instanceof Boolean approved)) throw new IllegalArgumentException("The approved field must be true or false.");
    String notes = optionalText(body == null ? null : body.get("notes"), 5000);
    Map<String,Object> release = firstRow("""
        select r.id,r.program_id,r.source_institution_id,r.release_state,r.content_fingerprint
        from institution_course_release r where r.id=?
        """, releaseId);
    if (release.isEmpty()) throw new IllegalArgumentException("Course release was not found.");
    if (!"IN_QUALITY_REVIEW".equals(String.valueOf(release.get("release_state")))) {
      throw new IllegalArgumentException("Only a release submitted for quality review can be reviewed.");
    }
    long courseId = ((Number)release.get("program_id")).longValue();
    if (approved) assertCourseContentQualityReady(courseId);
    String fingerprint = contentFingerprint(courseId);
    int changed = jdbc.update("""
        update institution_course_release
        set release_state=?,content_fingerprint=?,quality_reviewed_by_staff_id=?,quality_reviewed_at=now(),
          quality_review_notes=?,updated_at=now()
        where id=? and release_state='IN_QUALITY_REVIEW'
        """, approved ? "QUALITY_APPROVED" : "REJECTED", fingerprint, reviewer.staffId(),
        notes, releaseId);
    if (changed != 1) throw new IllegalArgumentException("The release changed concurrently. Refresh and retry.");
    jdbc.update("""
        insert into institution_audit_log(institution_id,actor_platform_staff_id,action,target_type,target_id,details)
        values(?,?,'COURSE_RELEASE_QUALITY_REVIEWED','COURSE_RELEASE',?,jsonb_build_object('approved',?,'notes',?))
        """, ((Number)release.get("source_institution_id")).longValue(), reviewer.staffId(),
        String.valueOf(releaseId), approved, notes == null ? "" : notes);
    return Map.of("releaseId", releaseId, "releaseState", approved ? "QUALITY_APPROVED" : "REJECTED",
        "reviewedByStaffId", reviewer.staffId());
  }

  @GetMapping("/hrms/platform/course-releases/shareable")
  public List<Map<String,Object>> shareableReleaseCatalog(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    authorization.requirePermission(context, "QE_PLATFORM_OWNER");
    return jdbc.queryForList("""
        select r.id as release_id,r.public_id as release_public_id,r.release_version,
          r.source_institution_id,t.tenant_code as source_tenant_code,
          coalesce(b.display_name,t.display_name) as source_institution_name,
          r.program_id as course_id,p.public_id as course_public_id,p.code as course_code,
          p.display_name as course_name,p.curriculum_version,r.content_fingerprint,
          r.quality_reviewed_at,r.origin_approved_at,r.shareable_at
        from institution_course_release r
        join institution_tenant t on t.id=r.source_institution_id and t.status='ACTIVE'
        left join institution_branding b on b.institution_id=t.id
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where r.release_state='SHAREABLE'
        order by r.shareable_at desc,r.id desc
        """);
  }

  /** HRMS assignment and destination publication are separate operations. */
  @PutMapping("/hrms/platform/institutions/{institutionId}/course-releases/{releaseId}")
  @Transactional
  public Map<String,Object> assignShareableRelease(
      @PathVariable long institutionId, @PathVariable long releaseId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    AuthContext owner = authorization.requirePermission(context, "QE_PLATFORM_OWNER");
    Object rawEnabled = body == null ? null : body.get("enabled");
    if (!(rawEnabled instanceof Boolean enabled)) throw new IllegalArgumentException("The enabled field must be true or false.");
    Map<String,Object> target = firstRow("select id,tenant_code,status from institution_tenant where id=?", institutionId);
    if (target.isEmpty() || !"ACTIVE".equals(String.valueOf(target.get("status")))) {
      throw new IllegalArgumentException("The destination institution must exist and be active.");
    }
    Map<String,Object> release = firstRow("""
        select id,program_id,source_institution_id,release_state
        from institution_course_release where id=?
        """, releaseId);
    if (release.isEmpty()) throw new IllegalArgumentException("Course release was not found.");
    long courseId = ((Number)release.get("program_id")).longValue();
    if (enabled && !"SHAREABLE".equals(String.valueOf(release.get("release_state")))) {
      throw new IllegalArgumentException("Only a source-approved shareable release can be assigned to another institution.");
    }

    if (enabled) {
      jdbc.update("""
          insert into institution_course_assignment(institution_id,course_release_id,status,assignment_source,assigned_by_staff_id)
          values(?,?,'ASSIGNED','HRMS',?)
          on conflict(institution_id,course_release_id) do update set
            status='ASSIGNED',assignment_source='HRMS',assigned_by_staff_id=excluded.assigned_by_staff_id,
            assigned_at=now(),revoked_by_staff_id=null,revoked_at=null,updated_at=now()
          """, institutionId, releaseId, owner.staffId());
      jdbc.update("""
          insert into institution_course_entitlement(institution_id,program_id,status,valid_from,granted_by_staff_id)
          values(?,?,'ACTIVE',now(),?)
          on conflict(institution_id,program_id) do update set
            status='ACTIVE',valid_from=now(),valid_until=null,
            granted_by_staff_id=excluded.granted_by_staff_id,revoked_by_staff_id=null,updated_at=now()
          """, institutionId, courseId, owner.staffId());
    } else {
      jdbc.update("""
          update institution_course_assignment
          set status='REVOKED',revoked_by_staff_id=?,revoked_at=now(),updated_at=now()
          where institution_id=? and course_release_id=? and status='ASSIGNED'
          """, owner.staffId(), institutionId, releaseId);
      jdbc.update("""
          update institution_course_entitlement set status='REVOKED',revoked_by_staff_id=?,updated_at=now()
          where institution_id=? and program_id=?
          """, owner.staffId(), institutionId, courseId);
    }
    jdbc.update("""
        insert into institution_membership_event(institution_id,event_type,actor_platform_staff_id,details)
        values(?,'COURSE_ENTITLEMENT_CHANGED',?,jsonb_build_object('programId',?,'releaseId',?,'enabled',?))
        """, institutionId, owner.staffId(), courseId, releaseId, enabled);
    jdbc.update("""
        insert into institution_audit_log(institution_id,actor_platform_staff_id,action,target_type,target_id,details)
        values(?,?,'COURSE_RELEASE_ASSIGNMENT_CHANGED','COURSE_RELEASE',?,jsonb_build_object('courseId',?,'enabled',?))
        """, institutionId, owner.staffId(), String.valueOf(releaseId), courseId, enabled);
    return Map.of("institutionId", institutionId, "releaseId", releaseId, "courseId", courseId,
        "enabled", enabled, "destinationOfferApprovalRequired", enabled);
  }

  @GetMapping("/hrms/platform/institutions/{institutionId}/course-assignments")
  public List<Map<String,Object>> listInstitutionAssignments(
      @PathVariable long institutionId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    authorization.requirePermission(context, "QE_PLATFORM_OWNER");
    return jdbc.queryForList("""
        select a.id as assignment_id,a.status as assignment_status,a.assigned_at,
          r.id as release_id,r.release_version,r.release_state,r.source_institution_id,
          p.id as course_id,p.code as course_code,p.display_name as course_name,
          o.id as offer_id,o.status as offer_status,o.price_paise,o.currency,o.published_at
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join curriculum_program p on p.id=r.program_id
        left join institution_marketplace_offer o on o.institution_id=a.institution_id and o.assignment_id=a.id
        where a.institution_id=?
        order by a.assigned_at desc,a.id desc
        """, institutionId);
  }

  private InstitutionAuthService.InstitutionPrincipal requireInstitutionAdmin(HttpServletRequest request) {
    String token = cookieValue(request, AuthService.INSTITUTION_COOKIE);
    InstitutionAuthService.InstitutionPrincipal principal = institutionAuth.current(token);
    if (principal == null) throw new SecurityException("Institution authentication required.");
    if (principal.mustChangePassword()) throw new SecurityException("Change the initial institution password before continuing.");
    return principal;
  }

  private static String cookieValue(HttpServletRequest request, String name) {
    if (request.getCookies() == null) return null;
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) return cookie.getValue();
    }
    return null;
  }

  private Map<String,Object> sourceRelease(InstitutionAuthService.InstitutionPrincipal principal, long releaseId) {
    Map<String,Object> release = firstRow("""
        select r.id,r.program_id,r.release_version,r.release_state,r.content_fingerprint
        from institution_course_release r
        where r.id=? and r.source_institution_id=?
        """, releaseId, principal.institutionId());
    if (release.isEmpty()) throw new SecurityException("This release does not belong to the signed-in source institution.");
    return release;
  }

  private Map<String,Object> ownedOffer(InstitutionAuthService.InstitutionPrincipal principal, long offerId) {
    Map<String,Object> offer = firstRow("""
        select o.id,o.institution_id,o.assignment_id,o.course_release_id,o.course_id,o.status,
          o.price_paise,o.currency,r.release_state,r.source_institution_id
        from institution_marketplace_offer o
        join institution_course_release r on r.id=o.course_release_id
        where o.id=? and o.institution_id=?
        """, offerId, principal.institutionId());
    if (offer.isEmpty()) throw new SecurityException("This offer does not belong to the signed-in institution.");
    return offer;
  }

  private void requireAssignmentActive(Map<String,Object> offer, InstitutionAuthService.InstitutionPrincipal principal) {
    Boolean active = jdbc.queryForObject("""
        select exists(select 1 from institution_course_assignment
          where id=? and institution_id=? and status='ASSIGNED')
        """, Boolean.class, ((Number)offer.get("assignment_id")).longValue(), principal.institutionId());
    if (!Boolean.TRUE.equals(active)) throw new IllegalArgumentException("The assigned course has been revoked.");
  }

  private void requireOfferReleaseEligibleForLocalInstitution(
      Map<String,Object> offer, InstitutionAuthService.InstitutionPrincipal principal) {
    String releaseState = String.valueOf(offer.get("release_state"));
    long sourceInstitutionId = ((Number)offer.get("source_institution_id")).longValue();
    if ("SHAREABLE".equals(releaseState)) return;
    if ("LEGACY_MIGRATED".equals(releaseState) && sourceInstitutionId == principal.institutionId()) return;
    throw new IllegalArgumentException("The source institution has not approved this release for sharing.");
  }

  private void assertCourseContentQualityReady(long courseId) {
    Long subjectCount = jdbc.queryForObject(
        "select count(*) from curriculum_program_subject where program_id=?", Long.class, courseId);
    if (subjectCount == null || subjectCount == 0) {
      throw new IllegalArgumentException("The course must have at least one mapped subject before quality review.");
    }
    Long subjectWithoutPublishedLessons = jdbc.queryForObject("""
        select count(*) from curriculum_program_subject ps
        where ps.program_id=? and not exists (
          select 1 from curriculum_chapter ch
          join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
          where ch.subject_id=ps.subject_id and ch.active=true
        )
        """, Long.class, courseId);
    if (subjectWithoutPublishedLessons != null && subjectWithoutPublishedLessons > 0) {
      throw new IllegalArgumentException("Every mapped subject must have an active chapter with published lessons before release approval.");
    }
  }

  private String contentFingerprint(long courseId) {
    String fingerprint = jdbc.queryForObject("""
        select md5(concat_ws('|',p.id::text,p.code,p.display_name,p.curriculum_version,
          coalesce((select string_agg(
            ps.subject_id::text || ':' || ps.is_required::text || ':' || ps.sort_order::text,
            ',' order by ps.subject_id
          ) from curriculum_program_subject ps where ps.program_id=p.id),''),
          coalesce((select string_agg(
            ch.id::text || ':' || ch.code || ':' || ch.active::text || ':' ||
              coalesce(l.id::text,'0') || ':' || coalesce(l.status,'NONE') || ':' || coalesce(l.active::text,'false'),
            ',' order by ch.id,l.id
          )
          from curriculum_program_subject ps
          join curriculum_chapter ch on ch.subject_id=ps.subject_id
          left join lesson l on l.chapter_id=ch.id
          where ps.program_id=p.id),'')
        ))
        from curriculum_program p where p.id=?
        """, String.class, courseId);
    if (fingerprint == null || !fingerprint.matches("^[0-9a-f]{32}$")) {
      throw new IllegalArgumentException("Course content could not be fingerprinted.");
    }
    return fingerprint;
  }

  private Map<String,Object> firstRow(String sql, Object... args) {
    List<Map<String,Object>> rows = jdbc.queryForList(sql, args);
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  private void auditInstitution(
      InstitutionAuthService.InstitutionPrincipal principal, String action,
      String targetType, String targetId, String detailsJson) {
    jdbc.update("""
        insert into institution_audit_log(institution_id,actor_institution_admin_id,action,target_type,target_id,details)
        values(?,?,?,?,?,?::jsonb)
        """, principal.institutionId(), principal.adminId(), action, targetType, targetId,
        detailsJson == null ? "{}" : detailsJson);
  }

  private static long requiredPositiveLong(Map<String,Object> body, String field) {
    Object raw = body == null ? null : body.get(field);
    if (!(raw instanceof Number number) || number.longValue() <= 0) {
      throw new IllegalArgumentException(field + " must be a positive integer.");
    }
    return number.longValue();
  }

  private static long requiredNonNegativeLong(Map<String,Object> body, String field) {
    Object raw = body == null ? null : body.get(field);
    if (!(raw instanceof Number number) || number.longValue() < 0) {
      throw new IllegalArgumentException(field + " must be a non-negative integer in paise.");
    }
    return number.longValue();
  }

  private static String requiredText(Map<String,Object> body, String field, int min, int max) {
    String value = optionalText(body == null ? null : body.get(field), max);
    if (value == null || value.length() < min) throw new IllegalArgumentException(field + " is required.");
    return value;
  }

  private static String optionalText(Object raw, int max) {
    if (raw == null) return null;
    String value = raw.toString().trim();
    if (value.isEmpty()) return null;
    if (value.length() > max) throw new IllegalArgumentException("Text exceeds " + max + " characters.");
    return value;
  }

  private static List<Long> positiveLongList(Object raw, String field) {
    if (!(raw instanceof List<?> values)) throw new IllegalArgumentException(field + " must be an array.");
    Set<Long> ids = new LinkedHashSet<>();
    for (Object item : values) {
      if (!(item instanceof Number number) || number.longValue() <= 0) {
        throw new IllegalArgumentException(field + " must contain positive integer IDs.");
      }
      ids.add(number.longValue());
    }
    return new ArrayList<>(ids);
  }
}
