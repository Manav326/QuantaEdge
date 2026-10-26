package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Child-to-parent course purchase pings and parent-side request management. */
@RestController
@RequestMapping("/api/v1")
public class ParentCoursePurchaseRequestController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  private InstitutionTenantContext requireTenant(InstitutionTenantContext tenant) {
    if (tenant == null) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "Select the institution before managing course purchase requests.");
    }
    return tenant;
  }

  public ParentCoursePurchaseRequestController(JdbcTemplate jdbc, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  @PostMapping("/marketplace/courses/{courseId}/purchase-request")
  @Transactional
  public Map<String,Object> requestPurchase(
      @PathVariable long courseId,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId = requireTenant(tenant).institutionId();
    long studentId = context.studentId();

    List<Map<String,Object>> child = jdbc.queryForList("""
      select st.id,st.display_name
      from student st
      join student_login_credential c on c.student_id=st.id and c.active=true
      where st.id=? and st.active=true and st.environment='PRODUCTION'
      """, studentId);
    if (child.isEmpty()) {
      throw new SecurityException("Parent purchase requests are available only for parent-managed learner accounts.");
    }

    List<Map<String,Object>> publishedOffers = jdbc.queryForList("""
      select o.id as institution_offer_id
      from institution_marketplace_offer o
      join institution_course_assignment assignment
        on assignment.id=o.assignment_id and assignment.institution_id=o.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=o.course_release_id and release.program_id=o.course_id
      join institution_tenant tenant_scope on tenant_scope.id=o.institution_id and tenant_scope.status='ACTIVE'
      join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
      where o.institution_id=? and o.course_id=? and o.status='PUBLISHED' and o.price_paise>0
         and qe_marketplace_offer_publicly_sellable(o.id)
        and o.hrms_published_by_staff_id is not null
        and release.release_state='SHAREABLE'
        and course_marketplace_content_type_ready(release.program_id)
        and course_release_fingerprint_current(release.program_id,release.content_fingerprint::text,release.updated_at)
        and not exists (
          select 1
          from institution_marketplace_offer newer_offer
          join institution_course_assignment newer_assignment
            on newer_assignment.id=newer_offer.assignment_id
           and newer_assignment.institution_id=newer_offer.institution_id
           and newer_assignment.status='ASSIGNED'
          join institution_course_release newer_release
            on newer_release.id=newer_offer.course_release_id
           and newer_release.program_id=newer_offer.course_id
          join curriculum_program newer_program
            on newer_program.id=newer_release.program_id
           and newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'
           and newer_program.course_identity_id=p.course_identity_id
           and (
             newer_program.content_version_no>p.content_version_no
             or (newer_program.content_version_no=p.content_version_no and (
               newer_release.release_version>release.release_version
               or (newer_release.release_version=release.release_version and newer_release.id>release.id)
             ))
           )
          where newer_offer.institution_id=o.institution_id
            and newer_offer.status='PUBLISHED'
            and newer_offer.hrms_published_by_staff_id is not null
            and newer_release.release_state='SHAREABLE'
            and course_marketplace_content_type_ready(newer_release.program_id)
            and course_release_fingerprint_current(newer_release.program_id,newer_release.content_fingerprint::text,newer_release.updated_at)
        )
      """, institutionId, courseId);
    if (publishedOffers.isEmpty()) throw new IllegalArgumentException("This paid course is not currently available to the selected institution.");
    long institutionOfferId = ((Number) publishedOffers.getFirst().get("institution_offer_id")).longValue();

    Boolean access = jdbc.queryForObject("""
      select exists(select 1 from student_program_enrollment
          where student_id=? and institution_id=? and qe_same_logical_course(program_id,?) and status in ('ACTIVE','PAUSED'))
        or exists(select 1 from course_purchase where student_id=? and institution_id=? and qe_same_logical_course(course_id,?))

      """, Boolean.class, studentId, institutionId, courseId, studentId, institutionId, courseId);
    if (Boolean.TRUE.equals(access)) throw new IllegalArgumentException("You already have access to this course.");

    List<Map<String,Object>> guardians = jdbc.queryForList("""
      select distinct gs.guardian_user_id
      from student_login_credential c
      join guardian_student gs on gs.guardian_user_id=c.guardian_user_id and gs.student_id=c.student_id
      join user_account u on u.id=gs.guardian_user_id and u.role='PARENT' and u.active=true
      where c.student_id=? and c.active=true and gs.active=true
        and gs.consent_status='CONSENTED' and gs.access_level='FULL'
      order by gs.guardian_user_id
      """, studentId);
    if (guardians.isEmpty()) throw new SecurityException("No active parent account is linked to this learner.");

    List<Long> requestIds = new java.util.ArrayList<>();
    List<Map<String,Object>> requestDetails = new java.util.ArrayList<>();
    String latestRequestedAt = null;
    for (Map<String,Object> guardian : guardians) {
      long guardianId = ((Number) guardian.get("guardian_user_id")).longValue();
      Map<String,Object> savedRequest = jdbc.queryForMap("""
        insert into parent_course_purchase_request(
          guardian_user_id,student_id,course_id,institution_id,institution_offer_id,
          status,requested_at,created_at,updated_at
        ) values(?,?,?,?,?,'PENDING',now(),now(),now())
        on conflict (guardian_user_id,student_id,institution_id,course_id)
          where status='PENDING' and institution_id is not null
        do update set institution_offer_id=excluded.institution_offer_id,updated_at=now()
        returning id,status,requested_at
        """, guardianId, studentId, courseId, institutionId, institutionOfferId);
      Long requestId = ((Number) savedRequest.get("id")).longValue();
      requestIds.add(requestId);
      Object requestedAt = savedRequest.get("requested_at");
      String requestedAtText = requestedAt == null ? null : String.valueOf(requestedAt);
      if (latestRequestedAt == null && requestedAtText != null) latestRequestedAt = requestedAtText;
      Map<String,Object> detail = new java.util.LinkedHashMap<>();
      detail.put("requestId", requestId);
      detail.put("status", String.valueOf(savedRequest.get("status")));
      detail.put("requestedAt", requestedAtText);
      requestDetails.add(detail);
    }
    Map<String,Object> response = new java.util.LinkedHashMap<>();
    response.put("requested", true);
    response.put("studentId", studentId);
    response.put("courseId", courseId);
    response.put("requestIds", requestIds);
    response.put("requestDetails", requestDetails);
    response.put("requestedAt", latestRequestedAt);
    response.put("status", "PENDING");
    response.put("notifiedParents", requestIds.size());
    return response;
  }

  /** Learners can verify that their parent request was recorded and track its lifecycle. */
  @GetMapping("/marketplace/student/purchase-requests")
  public List<Map<String,Object>> studentPurchaseRequests(
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId = requireTenant(tenant).institutionId();
    return jdbc.queryForList("""
      select r.id as request_id,r.course_id,r.status,r.requested_at,r.updated_at,r.resolved_at,
        p.public_id as course_public_id,p.code as course_code,
        coalesce(nullif(o.title_override,''),p.display_name) as course_name,
        o.price_paise,o.currency
      from parent_course_purchase_request r
      join student st on st.id=r.student_id and st.active=true
      join curriculum_program p on p.id=r.course_id
      left join institution_marketplace_offer o
        on o.id=r.institution_offer_id and o.institution_id=r.institution_id
      where r.student_id=? and r.institution_id=?
      order by r.requested_at desc,r.id desc
      limit 50
      """, context.studentId(), institutionId);
  }

  @GetMapping("/parent/course-requests")
  public List<Map<String,Object>> parentRequests(
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    return jdbc.queryForList("""
      select r.id as request_id,r.student_id,r.course_id,r.status,r.requested_at,
        st.display_name as student_name,st.class_code,
        p.public_id as course_public_id,p.code as course_code,
        coalesce(nullif(o.title_override,''),p.display_name) as course_name,
        o.price_paise,o.currency
      from parent_course_purchase_request r
      join guardian_student gs on gs.guardian_user_id=r.guardian_user_id
        and gs.student_id=r.student_id and gs.active=true
        and gs.consent_status='CONSENTED' and gs.access_level='FULL'
      join student st on st.id=r.student_id and st.active=true
      join curriculum_program p on p.id=r.course_id
      join institution_marketplace_offer o on o.id=r.institution_offer_id
        and o.institution_id=r.institution_id and o.status='PUBLISHED'
        and qe_marketplace_offer_publicly_sellable(o.id)
      join institution_course_assignment assignment
        on assignment.id=o.assignment_id and assignment.institution_id=o.institution_id and assignment.status='ASSIGNED'
      where r.guardian_user_id=? and r.institution_id=? and r.status='PENDING'
      order by r.requested_at desc,r.id desc
      """, context.userId(), institutionId);
  }

  @PostMapping("/parent/course-requests/{requestId}/dismiss")
  @Transactional
  public Map<String,Object> dismissRequest(
      @PathVariable long requestId,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    int changed = jdbc.update("""
      update parent_course_purchase_request
      set status='DECLINED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
      where id=? and guardian_user_id=? and institution_id=? and status='PENDING'
      """, context.userId(), requestId, context.userId(), institutionId);
    if (changed != 1) throw new IllegalArgumentException("This purchase request is no longer pending.");
    return Map.of("dismissed", true, "requestId", requestId);
  }
}
