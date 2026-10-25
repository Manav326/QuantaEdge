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
  private final InstitutionCourseReleaseWorkflowService releaseWorkflow;

  public InstitutionCourseLifecycleController(
      JdbcTemplate jdbc, InstitutionAuthService institutionAuth, ObjectMapper objectMapper,
      CourseMarketplaceLaunchReadinessService launchReadiness, CourseAuthoringService courseAuthoringService,
      InstitutionCourseReleaseWorkflowService releaseWorkflow) {
    this.jdbc = jdbc;
    this.institutionAuth = institutionAuth;
    this.objectMapper = objectMapper;
    this.launchReadiness = launchReadiness;
    this.courseAuthoringService = courseAuthoringService;
    this.releaseWorkflow = releaseWorkflow;
  }

  @GetMapping("/courses")
  public List<Map<String,Object>> courses(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    List<Map<String,Object>> rows=jdbc.queryForList("""
        with ranked_assignments as (
          select a.id as assignment_id,a.assignment_source,a.status as assignment_status,
            r.id as release_id,r.release_version,r.release_state,r.legacy_migration,
            r.quality_reviewed_at,r.quality_review_notes,r.origin_approved_at,
            p.id as course_id,p.public_id,p.display_name,p.curriculum_version,p.class_id,
            p.content_version_no,p.course_identity_id,identity.public_id as course_identity_public_id,
            coalesce(identity.stable_course_key,p.code) as course_code,
            row_number() over (
              partition by coalesce(p.course_identity_id,-p.id)
              order by p.content_version_no desc,r.release_version desc,r.id desc,
                a.assigned_at desc,a.id desc
            ) as identity_rank
          from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id
          join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
          left join institution_course_identity identity on identity.id=p.course_identity_id
          where a.institution_id=? and a.status='ASSIGNED'
            and (a.assignment_source<>'ORIGIN' or r.release_state='SHAREABLE')
        )
        select assignment_id,assignment_source,assignment_status,release_id,release_version,release_state,
          legacy_migration,quality_reviewed_at,quality_review_notes,origin_approved_at,course_id,public_id,
          course_identity_public_id,course_identity_id,course_code,display_name,curriculum_version,class_id,
          content_version_no
        from ranked_assignments
        where identity_rank=1
        order by display_name,course_code
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
    return releaseWorkflow.listSourceReleases(principal.institutionId());
  }

  /** Submit a release only after the existing per-subject content review has produced published lessons. */
  @PostMapping("/courses/{programId}/release-requests")
  @Transactional
  public Map<String,Object> submitRelease(@PathVariable long programId,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    return releaseWorkflow.submitRelease(programId,principal.institutionId(),null,principal.adminId());
  }

  @PostMapping("/course-releases/{releaseId}/approve")
  @Transactional
  public Map<String,Object> approveRelease(@PathVariable long releaseId,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    return releaseWorkflow.approveRelease(releaseId,principal.institutionId(),null,principal.adminId());
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
        with ranked_assignments as (
          select a.id as assignment_id,a.assignment_source,a.status as assignment_status,
            a.course_release_id as release_id,r.source_institution_id,r.release_version,r.release_state,
            p.id as course_id,p.public_id,p.display_name,p.curriculum_version,p.content_version_no,
            p.course_identity_id,identity.public_id as course_identity_public_id,
            coalesce(identity.stable_course_key,p.code) as course_code,
            o.id as offer_id,o.status as offer_status,o.price_paise,o.currency,o.title_override,o.description,
            o.approved_at,o.published_at,
            row_number() over (
              partition by coalesce(p.course_identity_id,-p.id)
              order by p.content_version_no desc,r.release_version desc,r.id desc,
                a.assigned_at desc,a.id desc
            ) as identity_rank
          from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id and r.release_state='SHAREABLE'
          join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
          left join institution_course_identity identity on identity.id=p.course_identity_id
          left join institution_marketplace_offer o on o.assignment_id=a.id and o.institution_id=a.institution_id
          where a.institution_id=? and a.status='ASSIGNED'
        )
        select assignment_id,assignment_source,assignment_status,release_id,source_institution_id,
          release_version,release_state,course_id,public_id,course_identity_public_id,course_identity_id,
          course_code,display_name,curriculum_version,content_version_no,offer_id,offer_status,
          price_paise,currency,title_override,description,approved_at,published_at
        from ranked_assignments
        where identity_rank=1
        order by display_name,course_code
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
