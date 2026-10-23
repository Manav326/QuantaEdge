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
 * Tenant-admin course origination, release review and institution-specific offer lifecycle.
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

  public InstitutionCourseLifecycleController(
      JdbcTemplate jdbc, InstitutionAuthService institutionAuth, ObjectMapper objectMapper,
      CourseMarketplaceLaunchReadinessService launchReadiness, CourseAuthoringService courseAuthoringService) {
    this.jdbc = jdbc;
    this.institutionAuth = institutionAuth;
    this.objectMapper = objectMapper;
    this.launchReadiness = launchReadiness;
    this.courseAuthoringService = courseAuthoringService;
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
    String fingerprint=contentFingerprint(programId);
    evidence.put("capturedAt",java.time.Instant.now().toString());
    long releaseId;
    int version;
    if("DRAFT".equals(state)) {
      releaseId=((Number)latest.get("id")).longValue();
      version=((Number)latest.get("release_version")).intValue();
      jdbc.update("""
          update institution_course_release set release_state='IN_QUALITY_REVIEW',content_fingerprint=?,
            submitted_by_institution_admin_id=?,submitted_at=now(),
            quality_reviewed_by_staff_id=null,quality_reviewed_at=null,quality_review_notes=null,
            origin_approved_by_admin_id=null,origin_approved_at=null,shareable_at=null,
            origin_rejected_by_admin_id=null,origin_rejected_at=null,origin_rejection_reason=null,updated_at=now()
          where id=? and source_institution_id=? and release_state='DRAFT'
          """,fingerprint,principal.adminId(),releaseId,principal.institutionId());
    } else {
      version=jdbc.queryForObject("""
          select coalesce(max(release_version),0)+1 from institution_course_release
          where source_institution_id=? and program_id=?
          """,Integer.class,principal.institutionId(),programId);
      releaseId=jdbc.queryForObject("""
          insert into institution_course_release(
            source_institution_id,program_id,release_version,release_state,content_fingerprint,
            submitted_by_institution_admin_id,submitted_at,legacy_migration,created_at,updated_at
          ) values(?,?,?,'IN_QUALITY_REVIEW',?,?,now(),false,now(),now()) returning id
          """,Long.class,principal.institutionId(),programId,version,fingerprint,principal.adminId());
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
    recordEvent(principal.institutionId(),releaseId,assignmentId,null,"RELEASE_SUBMITTED",null,
        principal.adminId(),evidence);
    return Map.of("submitted",true,"releaseId",releaseId,"programId",programId,
        "releaseVersion",version,"status","IN_QUALITY_REVIEW","qualityEvidence",evidence);
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
