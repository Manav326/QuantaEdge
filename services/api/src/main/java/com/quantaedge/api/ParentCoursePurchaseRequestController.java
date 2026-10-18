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
      """, institutionId, courseId);
    if (publishedOffers.isEmpty()) throw new IllegalArgumentException("This paid course is not currently available to the selected institution.");
    long institutionOfferId = ((Number) publishedOffers.getFirst().get("institution_offer_id")).longValue();

    Boolean access = jdbc.queryForObject("""
      select exists(select 1 from student_program_enrollment
          where student_id=? and institution_id=? and program_id=? and status in ('ACTIVE','PAUSED'))
        or exists(select 1 from course_purchase where student_id=? and institution_id=? and course_id=?)
        or exists(select 1 from student_course_access_grant where student_id=? and institution_id=?
          and course_id=? and grant_reason='LEGACY_CLASS_ENROLLMENT')
      """, Boolean.class, studentId, institutionId, courseId, studentId, institutionId, courseId,
        studentId, institutionId, courseId);
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
    for (Map<String,Object> guardian : guardians) {
      long guardianId = ((Number) guardian.get("guardian_user_id")).longValue();
      Long requestId = jdbc.queryForObject("""
        insert into parent_course_purchase_request(
          guardian_user_id,student_id,course_id,institution_id,institution_offer_id,
          status,requested_at,created_at,updated_at
        ) values(?,?,?,?,?,'PENDING',now(),now(),now())
        on conflict (guardian_user_id,student_id,institution_id,course_id)
          where status='PENDING' and institution_id is not null
        do update set institution_offer_id=excluded.institution_offer_id,updated_at=now()
        returning id
        """, Long.class, guardianId, studentId, courseId, institutionId, institutionOfferId);
      requestIds.add(requestId);
    }
    return Map.of("requested", true, "studentId", studentId,
        "courseId", courseId, "requestIds", requestIds, "notifiedParents", requestIds.size());
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
