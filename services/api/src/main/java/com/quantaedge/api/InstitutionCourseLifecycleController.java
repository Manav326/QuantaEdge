package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OBSOLETE ROUTES ARE RETAINED FOR POST-CUTOVER REMOVAL.
 * Legacy institution-side Marketplace offer setup/review/publication endpoints remain explicit
 * deprecated responses only; they must not be reactivated. HRMS is the sole Marketplace publisher.
 * Institution scope is taken only from the authenticated institution_admin_session.
 */
@RestController
@RequestMapping("/api/v1/institution")
public class InstitutionCourseLifecycleController {
  private final JdbcTemplate jdbc;
  private final InstitutionAuthService institutionAuth;
  private final ObjectMapper objectMapper;
  private final CourseMarketplaceLaunchReadinessService launchReadiness;
  private final CourseAuthoringService courseAuthoringService;
  private final CourseVersionLearnerMigrationService versionLearnerMigration;

  public InstitutionCourseLifecycleController(
      JdbcTemplate jdbc, InstitutionAuthService institutionAuth, ObjectMapper objectMapper,
      CourseMarketplaceLaunchReadinessService launchReadiness, CourseAuthoringService courseAuthoringService,
      CourseVersionLearnerMigrationService versionLearnerMigration) {
    this.jdbc = jdbc;
    this.institutionAuth = institutionAuth;
    this.objectMapper = objectMapper;
    this.launchReadiness = launchReadiness;
    this.courseAuthoringService = courseAuthoringService;
    this.versionLearnerMigration = versionLearnerMigration;
  }

  @GetMapping("/courses")
  public List<Map<String,Object>> courses(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select a.id as assignment_id,a.assignment_source,a.status as assignment_status,
          r.id as release_id,r.release_version,r.release_state,r.legacy_migration,
          r.quality_reviewed_at,r.quality_review_notes,r.origin_approved_at,
          p.id as course_id,p.public_id,p.code as course_code,p.display_name,p.curriculum_version,p.class_id
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join curriculum_program p on p.id=r.program_id
        where a.institution_id=? and a.status='ASSIGNED'
        order by p.display_name,p.id,r.release_version desc,a.id
        """,principal.institutionId());
    for(Map<String,Object> row:rows) {
      long courseId=((Number)row.get("course_id")).longValue();
      row.put("subjects",jdbc.queryForList("""
          select s.id,s.code as subject_code,s.display_name as subject_name,ps.is_required,ps.sort_order,
            count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          and (s.program_id=ps.program_id or s.program_id is null)
          left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
          left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
          where ps.program_id=?
          group by s.id,s.code,s.display_name,ps.is_required,ps.sort_order,s.sort_order
          order by ps.sort_order,s.sort_order,s.id
          """,courseId));
    }
    return rows;
  }

  /** Creates the institution's course master and a draft release over its own content graph. */
  /**
   * @deprecated Legacy institution-portal creation endpoint delegates to the canonical course
   * authoring service. New Education Admin UI should call /api/v1/admin/content/courses.
   */
  @Deprecated(since="2026-10",forRemoval=false)
  @PostMapping("/courses")
  @Transactional
  public Map<String,Object> createCourse(@RequestBody Map<String,Object> body,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    return courseAuthoringService.createSourceCourse(
        principal.institutionId(),principal.tenantCode(),null,principal.adminId(),body);
  }

  @GetMapping("/course-releases")
  public List<Map<String,Object>> courseReleases(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    return jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,p.public_id,p.code as course_code,
          p.display_name,p.curriculum_version,r.release_version,r.release_state,r.content_fingerprint,
          r.legacy_migration,r.submitted_at,r.quality_reviewed_at,r.quality_review_notes,
          r.origin_approved_at,r.shareable_at,r.origin_rejection_reason
        from institution_course_release r join curriculum_program p on p.id=r.program_id
        where r.source_institution_id=?
        order by r.program_id,r.release_version desc,r.id desc
        """,principal.institutionId());
  }

  /** Submit a release only after the existing per-subject content review has produced published lessons. */
  @PostMapping("/courses/{programId}/release-requests")
  @Transactional
  public Map<String,Object> submitRelease(@PathVariable long programId,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    List<Map<String,Object>> owned=jdbc.queryForList("""
        select p.id,p.code,p.display_name,p.status,
          coalesce((select delivery_mode from curriculum_program_delivery_mode where program_id=p.id),'LEARNING_CONTENT') as delivery_model
        from curriculum_program p
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
          and exists(select 1 from institution_course_release r
            where r.program_id=p.id and r.source_institution_id=?)
        for update of p
        """,programId,principal.institutionId());
    if(owned.isEmpty()) throw new SecurityException("This active course is not owned by the current institution.");

    Map<String,Object> evidence=jdbc.queryForMap("""
        select count(distinct s.id) as subject_count,
          count(distinct case when l.id is not null then s.id end) as subjects_with_published_lessons,
          count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          and (s.program_id=ps.program_id or s.program_id is null)
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where ps.program_id=?
        """,programId);
    String deliveryMode=String.valueOf(owned.getFirst().get("delivery_model"));
    if("ASSESSMENT_ONLY".equals(deliveryMode)) {
      if(!assessmentOnlyCourseReady(programId)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Assessment-only release requires the three complete, source-verified TRE paper sets. Tests remain draft until question/answer review passes.");
      }
      evidence.put("deliveryMode",deliveryMode);
      evidence.put("assessmentOnlyReady",true);
    } else {
      long subjectCount=number(evidence.get("subject_count"));
      long coveredSubjects=number(evidence.get("subjects_with_published_lessons"));
      if(subjectCount==0||coveredSubjects!=subjectCount||number(evidence.get("published_chapters"))==0
          ||number(evidence.get("published_lessons"))==0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Every mapped active subject must have published, content-reviewed lesson content before release review.");
      }
    }

    List<Map<String,Object>> latestRows=jdbc.queryForList("""
        select id,release_version,release_state from institution_course_release
        where source_institution_id=? and program_id=?
        order by release_version desc limit 1 for update
        """,principal.institutionId(),programId);
    if(latestRows.isEmpty()) throw new SecurityException("This course has no institution-owned release.");
    Map<String,Object> latest=latestRows.getFirst();
    String state=String.valueOf(latest.get("release_state"));
    if(Set.of("IN_QUALITY_REVIEW","QUALITY_APPROVED").contains(state))
      throw conflict("This course already has a release in the quality review/approval workflow.");
    if(Set.of("SHAREABLE","WITHDRAWN","LEGACY_MIGRATED").contains(state))
      throw conflict("This release has already left the editable draft state. Create a separate draft content version before submitting an update.");
    String fingerprint=contentFingerprint(programId);
    evidence.put("capturedAt",java.time.Instant.now().toString());
    List<Map<String,Object>> trustedRows=jdbc.queryForList("""
        select coalesce(trust.enabled,false) as trusted_auto_approval,
          trust.changed_by_staff_id as trusted_auto_approval_changed_by_staff_id,
          trust.changed_at as trusted_auto_approval_changed_at,
          trust.note as trusted_auto_approval_note
        from curriculum_program p
        join institution_course_identity i on i.id=p.course_identity_id
          and i.source_institution_id=? and i.status='ACTIVE'
        left join institution_course_trust_policy trust
          on trust.course_identity_id=i.id and trust.source_institution_id=i.source_institution_id
        where p.id=?
        """,principal.institutionId(),programId);
    boolean trustedAutoApproval=!trustedRows.isEmpty()
        && Boolean.TRUE.equals(trustedRows.getFirst().get("trusted_auto_approval"));
    Long trustedPolicyActorId=trustedAutoApproval
        && trustedRows.getFirst().get("trusted_auto_approval_changed_by_staff_id") instanceof Number n
        ? n.longValue() : null;
    String trustedPolicyNote=trustedAutoApproval
        ? String.valueOf(trustedRows.getFirst().getOrDefault("trusted_auto_approval_note","")) : "";
    if(trustedAutoApproval && trustedPolicyActorId==null)
      throw conflict("Trusted-course policy attribution is incomplete. Disable and re-enable the policy in HRMS before continuing.");
    if(trustedAutoApproval) {
      evidence.put("approvalMode","TRUSTED_COURSE_AUTO_APPROVAL");
      evidence.put("trustedPolicyChangedAt",String.valueOf(trustedRows.getFirst().get("trusted_auto_approval_changed_at")));
      evidence.put("trustedPolicyNote",trustedPolicyNote);
      evidence.put("trustedPolicyActorStaffId",trustedPolicyActorId);
    }
    long releaseId;
    int version;
    if("DRAFT".equals(state)) {
      releaseId=((Number)latest.get("id")).longValue();
      version=((Number)latest.get("release_version")).intValue();
      if(trustedAutoApproval) {
        jdbc.update("""
            update institution_course_release set release_state='SHAREABLE',content_fingerprint=?,
              fingerprint_schema_version=2,
              submitted_by_institution_admin_id=?,submitted_at=now(),
              quality_reviewed_by_staff_id=?,quality_reviewed_at=now(),quality_review_notes=?,
              origin_approved_by_admin_id=null,origin_auto_approved_by_staff_id=?,
              origin_approval_mode='HRMS_TRUSTED_AUTO',origin_approved_at=now(),shareable_at=now(),
              origin_rejected_by_admin_id=null,origin_rejected_at=null,origin_rejection_reason=null,updated_at=now()
            where id=? and source_institution_id=? and release_state='DRAFT'
            """,fingerprint,principal.adminId(),trustedPolicyActorId,
            "Automatically approved under HRMS trusted-course policy. "+trustedPolicyNote,
            trustedPolicyActorId,releaseId,principal.institutionId());
      } else {
        jdbc.update("""
            update institution_course_release set release_state='IN_QUALITY_REVIEW',content_fingerprint=?,
              fingerprint_schema_version=2,
              submitted_by_institution_admin_id=?,submitted_at=now(),
              quality_reviewed_by_staff_id=null,quality_reviewed_at=null,quality_review_notes=null,
              origin_approved_by_admin_id=null,origin_approved_at=null,shareable_at=null,
              origin_rejected_by_admin_id=null,origin_rejected_at=null,origin_rejection_reason=null,updated_at=now()
            where id=? and source_institution_id=? and release_state='DRAFT'
            """,fingerprint,principal.adminId(),releaseId,principal.institutionId());
      }
    } else {
      version=jdbc.queryForObject("""
          select coalesce(max(release_version),0)+1 from institution_course_release
          where source_institution_id=? and program_id=?
          """,Integer.class,principal.institutionId(),programId);
      if(trustedAutoApproval) {
        releaseId=jdbc.queryForObject("""
            insert into institution_course_release(
              source_institution_id,program_id,release_version,release_state,content_fingerprint,
              fingerprint_schema_version,submitted_by_institution_admin_id,submitted_at,
              quality_reviewed_by_staff_id,quality_reviewed_at,quality_review_notes,
              origin_approved_by_admin_id,origin_auto_approved_by_staff_id,origin_approval_mode,
              origin_approved_at,shareable_at,legacy_migration,created_at,updated_at
            ) values(?,?,?,'SHAREABLE',?,2,?,now(),?,now(),?,NULL,?,'HRMS_TRUSTED_AUTO',now(),now(),false,now(),now()) returning id
            """,Long.class,principal.institutionId(),programId,version,fingerprint,
            principal.adminId(),trustedPolicyActorId,
            "Automatically approved under HRMS trusted-course policy. "+trustedPolicyNote,
            trustedPolicyActorId);
      } else {
        releaseId=jdbc.queryForObject("""
            insert into institution_course_release(
              source_institution_id,program_id,release_version,release_state,content_fingerprint,
              fingerprint_schema_version,submitted_by_institution_admin_id,submitted_at,legacy_migration,created_at,updated_at
            ) values(?,?,?,'IN_QUALITY_REVIEW',?,2,?,now(),false,now(),now()) returning id
            """,Long.class,principal.institutionId(),programId,version,fingerprint,principal.adminId());
      }
      jdbc.update("""
          insert into institution_course_assignment(
            institution_id,course_release_id,status,assignment_source,assigned_at,created_at,updated_at
          ) values(?,?,'ASSIGNED','ORIGIN',now(),now(),now())
          on conflict(institution_id,course_release_id) do update set status='ASSIGNED',
            assignment_source='ORIGIN',assigned_at=now(),revoked_by_staff_id=null,revoked_at=null,updated_at=now()
          """,principal.institutionId(),releaseId);
    }
    Long assignmentId=jdbc.queryForObject("""
        select id from institution_course_assignment
        where institution_id=? and course_release_id=? and status='ASSIGNED'
        """,Long.class,principal.institutionId(),releaseId);
    Map<String,Object> trustedDistribution=trustedAutoApproval
        ? propagateTrustedCourseVersion(programId,releaseId,principal.institutionId(),trustedPolicyActorId,principal.adminId())
        : Map.of("autoPublishedOffers",0,"heldOffers",0,"skippedInstitutions",0,"learnerVersionGrants",0);
    evidence.put("trustedDistribution",trustedDistribution);
    if(trustedAutoApproval) {
      jdbc.update("""
        insert into institution_course_version_event(
          course_identity_id,source_program_id,version_program_id,content_version_no,event_type,
          actor_staff_id,actor_institution_admin_id,details
        )
        select p.course_identity_id,coalesce(p.supersedes_program_id,p.id),p.id,p.content_version_no,
          'VERSION_SHAREABLE',?, ?,?::jsonb
        from curriculum_program p
        where p.id=? and p.content_version_no>1
        on conflict(course_identity_id,version_program_id,event_type) do nothing
        """,trustedPolicyActorId,principal.adminId(),json(evidence),programId);
    }
    recordEvent(principal.institutionId(),releaseId,assignmentId,null,
        trustedAutoApproval?"RELEASE_AUTO_APPROVED_TRUSTED_COURSE":"RELEASE_SUBMITTED",
        trustedPolicyActorId,principal.adminId(),evidence);
    return Map.of("submitted",true,"releaseId",releaseId,"programId",programId,
        "releaseVersion",version,"status",trustedAutoApproval?"SHAREABLE":"IN_QUALITY_REVIEW",
        "autoApprovedByTrustedCoursePolicy",trustedAutoApproval,"qualityEvidence",evidence,
        "trustedDistribution",trustedDistribution,
        "message",trustedAutoApproval
          ? "Content was automatically approved. Existing published destination offers were carried forward where their full access, rights and commercial readiness checks still pass; exceptions remain visible in HRMS for review."
          : "Release submitted for the standard HRMS content-quality workflow.");
  }

  @PostMapping("/course-releases/{releaseId}/approve")
  @Transactional
  public Map<String,Object> approveRelease(@PathVariable long releaseId,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    Map<String,Object> release=loadOriginRelease(releaseId,principal.institutionId());
    if(!"QUALITY_APPROVED".equals(String.valueOf(release.get("release_state"))))
      throw conflict("Platform content-quality review must pass before origin-institution approval.");
    long courseId=((Number)release.get("program_id")).longValue();
    if(!String.valueOf(release.get("content_fingerprint")).trim().equals(contentFingerprint(courseId)))
      throw conflict("Course content changed after review. Submit a fresh release.");
    int changed=jdbc.update("""
        update institution_course_release set release_state='SHAREABLE',
          origin_approved_by_admin_id=?,origin_approved_at=now(),shareable_at=now(),updated_at=now()
        where id=? and source_institution_id=? and release_state='QUALITY_APPROVED'
        """,principal.adminId(),releaseId,principal.institutionId());
    if(changed!=1) throw conflict("The release changed during approval.");
    recordEvent(principal.institutionId(),releaseId,null,null,"RELEASE_ORIGIN_APPROVED",null,
        principal.adminId(),Map.of("programId",courseId));
    return Map.of("approved",true,"releaseId",releaseId,"programId",courseId,"status","SHAREABLE");
  }

  @PostMapping("/course-releases/{releaseId}/reject")
  @Transactional
  public Map<String,Object> rejectRelease(
      @PathVariable long releaseId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    String reason=requiredText(body,"reason",3,1000,"Rejection reason");
    Map<String,Object> release=loadOriginRelease(releaseId,principal.institutionId());
    if(!"QUALITY_APPROVED".equals(String.valueOf(release.get("release_state"))))
      throw conflict("Only a quality-approved release awaiting origin approval can be rejected.");
    jdbc.update("""
        update institution_course_release set release_state='REJECTED',
          origin_rejected_by_admin_id=?,origin_rejected_at=now(),origin_rejection_reason=?,updated_at=now()
        where id=? and source_institution_id=? and release_state='QUALITY_APPROVED'
        """,principal.adminId(),reason,releaseId,principal.institutionId());
    recordEvent(principal.institutionId(),releaseId,null,null,"RELEASE_ORIGIN_REJECTED",null,
        principal.adminId(),Map.of("reason",reason));
    return Map.of("rejected",true,"releaseId",releaseId,"status","REJECTED");
  }

  @PostMapping("/course-releases/{releaseId}/revoke")
  @Transactional
  public Map<String,Object> revokeRelease(@PathVariable long releaseId,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    Map<String,Object> release=loadOriginRelease(releaseId,principal.institutionId());
    if(!"SHAREABLE".equals(String.valueOf(release.get("release_state"))))
      throw conflict("Only a shareable release can be withdrawn.");
    List<Map<String,Object>> assignments=jdbc.queryForList("""
        select id,institution_id from institution_course_assignment
        where course_release_id=? and status='ASSIGNED' for update
        """,releaseId);
    jdbc.update("""
        update institution_course_release set release_state='WITHDRAWN',
          withdrawn_by_admin_id=?,withdrawn_at=now(),updated_at=now()
        where id=? and source_institution_id=? and release_state='SHAREABLE'
        """,principal.adminId(),releaseId,principal.institutionId());
    jdbc.update("update institution_marketplace_offer set status='ARCHIVED',updated_at=now() where course_release_id=? and status<>'ARCHIVED'",releaseId);
    jdbc.update("""
        update institution_course_assignment set status='REVOKED',revoked_at=now(),updated_at=now()
        where course_release_id=? and status='ASSIGNED'
        """,releaseId);
    for(Map<String,Object> assignment:assignments) {
      long tenantId=((Number)assignment.get("institution_id")).longValue();
      recordEvent(tenantId,releaseId,((Number)assignment.get("id")).longValue(),null,
          "RELEASE_WITHDRAWN",null,tenantId==principal.institutionId()?principal.adminId():null,
          Map.of("sourceInstitutionId",principal.institutionId()));
    }
    return Map.of("withdrawn",true,"releaseId",releaseId,"assignmentsRevoked",assignments.size());
  }

  @GetMapping("/course-assignments")
  public List<Map<String,Object>> courseAssignments(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    return jdbc.queryForList("""
        select a.id as assignment_id,a.assignment_source,a.status as assignment_status,
          a.course_release_id as release_id,r.source_institution_id,r.release_version,r.release_state,
          p.id as course_id,p.public_id,p.code as course_code,p.display_name,p.curriculum_version,
          o.id as offer_id,o.status as offer_status,o.price_paise,o.currency,o.title_override,o.description,
          o.approved_at,o.published_at
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id and r.release_state='SHAREABLE'
        join curriculum_program p on p.id=r.program_id and p.status='ACTIVE'
        left join institution_marketplace_offer o on o.assignment_id=a.id and o.institution_id=a.institution_id
        where a.institution_id=? and a.status='ASSIGNED'
        order by p.display_name,p.id,r.release_version desc
        """,principal.institutionId());
  }

    /**
   * @deprecated Marketplace offer creation, approval and publication belong to the HRMS launch stepper.
   * Kept temporarily as an explicit compatibility response so old clients fail safely rather than
   * silently creating a parallel publication path.
   */
  @Deprecated(since="2026-10", forRemoval=false)
  @PostMapping("/course-assignments/{assignmentId}/offers")
  public Map<String,Object> configureOffer(
      @PathVariable long assignmentId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
    throw legacyOfferLifecycleGone();
  }

  /** @deprecated HRMS owns the offer review and publication state machine. */
  @Deprecated(since="2026-10", forRemoval=false)
  @PostMapping("/course-offers/{offerId}/submit")
  public Map<String,Object> submitOffer(@PathVariable long offerId,HttpServletRequest request) {
    throw legacyOfferLifecycleGone();
  }

  /** @deprecated HRMS alone may approve a Marketplace offer. */
  @Deprecated(since="2026-10", forRemoval=false)
  @PostMapping("/course-offers/{offerId}/approve")
  public Map<String,Object> approveOffer(@PathVariable long offerId,HttpServletRequest request) {
    throw legacyOfferLifecycleGone();
  }

  /** @deprecated HRMS alone may publish a Marketplace offer. */
  @Deprecated(since="2026-10", forRemoval=false)
  @PostMapping("/course-offers/{offerId}/publish")
  public Map<String,Object> publishOffer(@PathVariable long offerId,HttpServletRequest request) {
    throw legacyOfferLifecycleGone();
  }

  /** @deprecated Marketplace rejections are handled by the HRMS launch workflow. */
  @Deprecated(since="2026-10", forRemoval=false)
  @PostMapping("/course-offers/{offerId}/reject")
  public Map<String,Object> rejectOffer(
      @PathVariable long offerId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
    throw legacyOfferLifecycleGone();
  }

  private ResponseStatusException legacyOfferLifecycleGone() {
    return new ResponseStatusException(HttpStatus.GONE,
        "DEPRECATED_OFFER_LIFECYCLE: Marketplace offer setup, review and publication are managed exclusively by HRMS. Use the HRMS Marketplace launch stepper.");
  }

  /**
   * Trusted approval carries forward only previously published tenant offers and their exact commercial
   * terms. It re-runs the same launch-readiness service as the manual stepper. An exception leaves
   * that tenant's new offer in DRAFT while the old published version remains available.
   */
  private Map<String,Object> propagateTrustedCourseVersion(
      long programId,long releaseId,long sourceInstitutionId,long policyActorStaffId,long submittingAdminId) {
    Map<String,Object> version=jdbc.queryForMap("""
        select p.course_identity_id,p.content_version_no,p.supersedes_program_id
        from curriculum_program p
        where p.id=? and p.course_identity_id is not null
        """,programId);
    long identityId=number(version.get("course_identity_id"));
    int versionNo=(int)number(version.get("content_version_no"));
    List<Map<String,Object>> priorOffers=jdbc.queryForList("""
        select distinct on (o.institution_id)
          o.id as prior_offer_id,o.institution_id,o.assignment_id as prior_assignment_id,
          o.course_id as prior_course_id,o.title_override,o.description,o.price_paise,o.currency,
          old_program.content_version_no as prior_content_version_no
        from institution_marketplace_offer o
        join institution_course_assignment old_assignment
          on old_assignment.id=o.assignment_id and old_assignment.institution_id=o.institution_id
             and old_assignment.status='ASSIGNED'
        join institution_course_release old_release
          on old_release.id=o.course_release_id and old_release.program_id=o.course_id
             and old_release.release_state='SHAREABLE'
             and old_release.content_fingerprint=course_content_fingerprint(old_release.program_id)
        join curriculum_program old_program on old_program.id=o.course_id
          and old_program.course_identity_id=? and old_program.content_version_no<?
        join institution_tenant tenant on tenant.id=o.institution_id and tenant.status='ACTIVE'
        where o.status='PUBLISHED' and o.hrms_published_by_staff_id is not null
        order by o.institution_id,old_program.content_version_no desc,o.id desc
        """,identityId,versionNo);

    int autoPublished=0,held=0,skipped=0,learnerGrants=0;
    List<Map<String,Object>> destinationResults=new ArrayList<>();
    for(Map<String,Object> previous:priorOffers) {
      long tenantId=number(previous.get("institution_id"));
      long priorOfferId=number(previous.get("prior_offer_id"));
      List<Map<String,Object>> assignments=jdbc.queryForList("""
          select id,status from institution_course_assignment
          where institution_id=? and course_release_id=?
          for update
          """,tenantId,releaseId);
      long assignmentId;
      if(!assignments.isEmpty()) {
        if(!"ASSIGNED".equals(text(assignments.getFirst().get("status")))) {
          skipped++;
          recordEvent(tenantId,releaseId,number(assignments.getFirst().get("id")),null,
              "TRUSTED_UPDATE_ASSIGNMENT_SKIPPED",policyActorStaffId,null,
              Map.of("reason","An HRMS-revoked assignment is never reactivated by trusted-course automation.",
                "priorOfferId",priorOfferId));
          destinationResults.add(Map.of("institutionId",tenantId,"status","SKIPPED_REVOKED_ASSIGNMENT"));
          continue;
        }
        assignmentId=number(assignments.getFirst().get("id"));
      } else {
        String assignmentSource=tenantId==sourceInstitutionId?"ORIGIN":"HRMS";
        Long assignedBy=tenantId==sourceInstitutionId?null:policyActorStaffId;
        assignmentId=jdbc.queryForObject("""
            insert into institution_course_assignment(
              institution_id,course_release_id,status,assignment_source,assigned_by_staff_id,
              assigned_at,created_at,updated_at
            ) values(?,?,'ASSIGNED',?,?,now(),now(),now()) returning id
            """,Long.class,tenantId,releaseId,assignmentSource,assignedBy);
      }

      List<Map<String,Object>> existingOffer=jdbc.queryForList("""
          select id,status from institution_marketplace_offer
          where institution_id=? and assignment_id=?
          for update
          """,tenantId,assignmentId);
      if(!existingOffer.isEmpty()) {
        // Never overwrite an offer HRMS has already started configuring for the new version.
        skipped++;
        destinationResults.add(Map.of("institutionId",tenantId,
            "status","EXISTING_OFFER_PRESERVED","offerId",number(existingOffer.getFirst().get("id"))));
        continue;
      }

      long offerId=jdbc.queryForObject("""
          insert into institution_marketplace_offer(
            institution_id,assignment_id,course_release_id,course_id,title_override,description,
            price_paise,currency,status,created_at,updated_at
          ) values(?,?,?,?,?,?,?,?,'DRAFT',now(),now()) returning id
          """,Long.class,tenantId,assignmentId,releaseId,programId,
          previous.get("title_override"),previous.get("description"),
          number(previous.get("price_paise"),0L),text(previous.get("currency")).isBlank()?"INR":text(previous.get("currency")));

      String continuityBlock="";
      if(number(previous.get("price_paise"),0L)>0) {
        copyCurrentTaxProfile(priorOfferId,offerId,policyActorStaffId);
        copyCurrentRevenueSharePolicy(priorOfferId,offerId,policyActorStaffId);
        continuityBlock=copySafeDiscounts(priorOfferId,offerId,policyActorStaffId);
      }
      if(!continuityBlock.isBlank()) {
        jdbc.update("""
            update institution_marketplace_offer
            set trusted_update_block_reason=?,updated_at=now()
            where id=? and institution_id=? and status='DRAFT'
            """,continuityBlock,offerId,tenantId);
      }

      Map<String,Object> readinessResult=launchReadiness.inspect(offerId,tenantId);
      boolean ready=Boolean.TRUE.equals(readinessResult.get("ready"));
      boolean canAutoPublish=ready&&continuityBlock.isBlank();
      List<String> blockers=readinessBlockers(readinessResult);
      Map<String,Object> eventDetails=new LinkedHashMap<>();
      eventDetails.put("trustedPolicyActorStaffId",policyActorStaffId);
      eventDetails.put("priorOfferId",priorOfferId);
      eventDetails.put("sourceCourseIdentityId",identityId);
      eventDetails.put("sourceContentVersionNo",versionNo);
      eventDetails.put("readinessBlockers",blockers);
      eventDetails.put("commercialContinuityBlock",continuityBlock);

      if(canAutoPublish) {
        int changed=jdbc.update("""
            update institution_marketplace_offer
            set status='PUBLISHED',approved_at=now(),published_at=now(),
                hrms_approved_by_staff_id=?,hrms_approved_at=now(),
                hrms_published_by_staff_id=?,hrms_published_at=now(),
                trusted_update_block_reason=null,updated_at=now()
            where id=? and institution_id=? and assignment_id=? and status='DRAFT'
            """,policyActorStaffId,policyActorStaffId,offerId,tenantId,assignmentId);
        if(changed!=1) throw conflict("A trusted version offer changed during atomic publication; retry after refreshing the launch checklist.");
        eventDetails.put("autoPublished",true);
        recordEvent(tenantId,releaseId,assignmentId,offerId,"OFFER_APPROVED",policyActorStaffId,null,eventDetails);
        recordEvent(tenantId,releaseId,assignmentId,offerId,"OFFER_PUBLISHED",policyActorStaffId,null,eventDetails);
        int grants=versionLearnerMigration.grantExistingLearnersToVersion(tenantId,identityId,versionNo,programId);
        learnerGrants+=grants;
        autoPublished++;
        destinationResults.add(Map.of("institutionId",tenantId,"offerId",offerId,
            "status","PUBLISHED","learnerVersionGrants",grants));
      } else {
        eventDetails.put("autoPublished",false);
        recordEvent(tenantId,releaseId,assignmentId,offerId,"TRUSTED_UPDATE_OFFER_HELD",
            policyActorStaffId,null,eventDetails);
        held++;
        destinationResults.add(Map.of("institutionId",tenantId,"offerId",offerId,
            "status","DRAFT_REVIEW_REQUIRED","blockingIssues",blockers,
            "commercialContinuityBlock",continuityBlock));
      }
    }

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("priorPublishedInstitutions",priorOffers.size());
    result.put("autoPublishedOffers",autoPublished);
    result.put("heldOffers",held);
    result.put("skippedInstitutions",skipped);
    result.put("learnerVersionGrants",learnerGrants);
    result.put("destinations",destinationResults);
    return result;
  }

  private void copyCurrentTaxProfile(long priorOfferId,long newOfferId,long policyActorStaffId) {
    List<Map<String,Object>> profiles=jdbc.queryForList("""
        select id,legal_entity_id,service_classification_code,tax_treatment,gst_rate_basis_points,
          cess_rate_basis_points,price_includes_tax,place_of_supply_rule,rounding_rule,
          tax_components::text as tax_components_json,effective_until,approved_by_staff_id,approved_at
        from course_tax_profile
        where institution_offer_id=? and status='APPROVED' and effective_from<=now()
          and (effective_until is null or effective_until>now())
        order by version desc,id desc limit 2
        """,priorOfferId);
    if(profiles.size()!=1) return;
    Map<String,Object> profile=profiles.getFirst();
    jdbc.update("""
        insert into course_tax_profile(
          institution_offer_id,legal_entity_id,version,service_classification_code,tax_treatment,
          gst_rate_basis_points,cess_rate_basis_points,price_includes_tax,place_of_supply_rule,
          rounding_rule,tax_components,status,effective_from,effective_until,
          created_by_staff_id,approved_by_staff_id,approved_at,created_at
        ) values(?,?,1,?,?,?,?,?,?,?,?::jsonb,'APPROVED',now(),?,?,?,?,now())
        """,newOfferId,profile.get("legal_entity_id"),profile.get("service_classification_code"),
        profile.get("tax_treatment"),profile.get("gst_rate_basis_points"),profile.get("cess_rate_basis_points"),
        profile.get("price_includes_tax"),profile.get("place_of_supply_rule"),profile.get("rounding_rule"),
        text(profile.get("tax_components_json")),profile.get("effective_until"),policyActorStaffId,
        profile.get("approved_by_staff_id"),profile.get("approved_at"));
  }

  private void copyCurrentRevenueSharePolicy(long priorOfferId,long newOfferId,long policyActorStaffId) {
    List<Map<String,Object>> policies=jdbc.queryForList("""
        select id,currency,effective_until,contractual_reference,notes,approved_by_staff_id,approved_at
        from revenue_share_policy
        where institution_offer_id=? and status='APPROVED' and effective_from<=now()
          and (effective_until is null or effective_until>now())
        order by policy_version desc,id desc limit 2
        """,priorOfferId);
    if(policies.size()!=1) return;
    Map<String,Object> policy=policies.getFirst();
    long oldPolicyId=number(policy.get("id"));
    String oldNotes=text(policy.get("notes"));
    String inheritedNote="Inherited unchanged from published offer "+priorOfferId+" under the HRMS trusted-course version policy.";
    String notes=(oldNotes.isBlank()?inheritedNote:oldNotes+" | "+inheritedNote);
    if(notes.length()>2000) notes=notes.substring(0,2000);
    long newPolicyId=jdbc.queryForObject("""
        insert into revenue_share_policy(
          institution_offer_id,policy_version,currency,status,effective_from,effective_until,
          contractual_reference,notes,created_by_staff_id,approved_by_staff_id,approved_at,created_at
        ) values(?,1,?,'APPROVED',now(),?,?,?,?,?,?,now()) returning id
        """,Long.class,newOfferId,policy.get("currency"),policy.get("effective_until"),
        policy.get("contractual_reference"),notes,policyActorStaffId,
        policy.get("approved_by_staff_id"),policy.get("approved_at"));
    jdbc.update("""
        insert into revenue_share_policy_recipient(
          policy_id,institution_id,calculation_type,percentage_basis_points,flat_amount_paise
        )
        select ?,institution_id,calculation_type,percentage_basis_points,flat_amount_paise
        from revenue_share_policy_recipient where policy_id=?
        """,newPolicyId,oldPolicyId);
  }

  private String copySafeDiscounts(long priorOfferId,long newOfferId,long policyActorStaffId) {
    List<Map<String,Object>> discounts=jdbc.queryForList("""
        select d.id,d.code,d.version,d.currency,d.discount_type,d.percentage_basis_points,
          d.flat_amount_paise,d.max_discount_paise,d.min_listed_price_paise,d.max_redemptions,
          d.max_redemptions_per_purchaser,d.effective_until,d.approved_by_staff_id,d.approved_at,
          (select count(*) from course_discount_redemption red
            where red.discount_offer_id=d.id and red.status in ('RESERVED','REDEEMED')) as used_count
        from course_discount_offer d
        where d.institution_offer_id=? and d.status='APPROVED'
          and d.effective_from<=now() and (d.effective_until is null or d.effective_until>now())
        order by d.code,d.version desc,d.id desc
        """,priorOfferId);
    Map<String,Object> restricted=discounts.stream()
        .filter(d->d.get("max_redemptions_per_purchaser")!=null)
        .findFirst().orElse(null);
    if(restricted!=null) {
      return "Prior offer discount "+text(restricted.get("code"))
          +" has a per-purchaser redemption cap. HRMS must review and carry forward that redemption policy for the new version before it can auto-publish.";
    }
    for(Map<String,Object> discount:discounts) {
      Object maximum=discount.get("max_redemptions");
      long used=number(discount.get("used_count"),0L);
      if(maximum instanceof Number cap && cap.longValue()-used<=0L) continue;
      Object remaining=maximum instanceof Number cap?Math.max(0L,cap.longValue()-used):null;
      jdbc.update("""
          insert into course_discount_offer(
            institution_offer_id,code,version,currency,discount_type,percentage_basis_points,
            flat_amount_paise,max_discount_paise,min_listed_price_paise,max_redemptions,
            max_redemptions_per_purchaser,effective_from,effective_until,status,
            created_by_staff_id,approved_by_staff_id,approved_at,created_at,updated_at
          ) values(?,?,1,?,?,?,?,?,?,?,null,now(),?,'APPROVED',?,?,?,now(),now())
          """,newOfferId,discount.get("code"),discount.get("currency"),discount.get("discount_type"),
          discount.get("percentage_basis_points"),discount.get("flat_amount_paise"),
          discount.get("max_discount_paise"),discount.get("min_listed_price_paise"),
          remaining,discount.get("effective_until"),policyActorStaffId,
          discount.get("approved_by_staff_id"),discount.get("approved_at"));
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private List<String> readinessBlockers(Map<String,Object> readinessResult) {
    Object raw=readinessResult.get("blockingIssues");
    if(raw instanceof List<?> rows) return rows.stream().map(String::valueOf).toList();
    return List.of();
  }

  private long number(Object raw,long fallback) {
    if(raw==null) return fallback;
    return number(raw);
  }

  private Map<String,Object> loadOriginRelease(long releaseId,long institutionId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id,program_id,source_institution_id,release_version,release_state,content_fingerprint
        from institution_course_release
        where id=? and source_institution_id=? for update
        """,releaseId,institutionId);
    if(rows.isEmpty()) throw new SecurityException("This course release is not owned by the current institution.");
    return rows.getFirst();
  }

  private String contentFingerprint(long programId) {
    String value=jdbc.queryForObject("select course_content_fingerprint(?)",String.class,programId);
    return value==null?"d41d8cd98f00b204e9800998ecf8427e":value.trim();
  }

  private boolean assessmentOnlyCourseReady(long programId) {
    Boolean ready=jdbc.queryForObject("select assessment_only_course_ready(?)",Boolean.class,programId);
    return Boolean.TRUE.equals(ready);
  }

  private InstitutionAuthService.InstitutionPrincipal requirePrincipal(HttpServletRequest request) {
    String token=cookieValue(request,AuthService.INSTITUTION_COOKIE);
    InstitutionAuthService.InstitutionPrincipal principal=institutionAuth.current(token);
    if(principal==null) throw new SecurityException("Institution authentication required.");
    if(principal.mustChangePassword()) throw new SecurityException("Change the initial institution password before continuing.");
    return principal;
  }

  private static String cookieValue(HttpServletRequest request,String name) {
    if(request.getCookies()==null) return null;
    for(Cookie cookie:request.getCookies())
      if(name.equals(cookie.getName())&&cookie.getValue()!=null&&!cookie.getValue().isBlank()) return cookie.getValue();
    return null;
  }

  private void recordEvent(long institutionId,Long releaseId,Long assignmentId,Long offerId,String type,
      Long actorStaffId,Long actorAdminId,Map<String,Object> details) {
    jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,assignment_id,offer_id,event_type,
          actor_platform_staff_id,actor_institution_admin_id,details
        ) values(?,?,?,?,?,?,?,?::jsonb)
        """,institutionId,releaseId,assignmentId,offerId,type,actorStaffId,actorAdminId,json(details));
  }

  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Lifecycle event details could not be serialized.",ex); }
  }

  private static Map<String,Object> mapOf(Map<?,?> raw) {
    Map<String,Object> result=new LinkedHashMap<>();
    raw.forEach((key,value)->result.put(String.valueOf(key),value));
    return result;
  }
  private static String requiredText(Map<String,Object> body,String key,int min,int max,String label) {
    String value=body==null||body.get(key)==null?"":String.valueOf(body.get(key)).trim();
    if(value.length()<min||value.length()>max) throw new IllegalArgumentException(label+" must be "+min+"–"+max+" characters.");
    return value;
  }
  private static String optionalText(Object raw,String fallback,int min,int max,String label) {
    if(raw==null) return fallback;
    String value=String.valueOf(raw).trim();
    if(value.isEmpty()&&min==0) return null;
    if(value.length()<min||value.length()>max) throw new IllegalArgumentException(label+" must be "+min+"–"+max+" characters.");
    return value;
  }
  private static long number(Object raw) {
    if(raw instanceof Number number) return number.longValue();
    try { return Long.parseLong(String.valueOf(raw)); }
    catch(RuntimeException ex) { throw new IllegalArgumentException("A numeric value was expected."); }
  }
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
