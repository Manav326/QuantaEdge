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

  public ParentCoursePurchaseRequestController(JdbcTemplate jdbc, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  @PostMapping("/marketplace/courses/{courseId}/purchase-request")
  @Transactional
  public Map<String,Object> requestPurchase(
      @PathVariable long courseId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireStudent(context);
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

    Boolean published = jdbc.queryForObject("""
      select exists(
        select 1 from course_marketplace_offer o
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where o.course_id=? and o.status='PUBLISHED' and o.price_paise>0
      )
      """, Boolean.class, courseId);
    if (!Boolean.TRUE.equals(published)) throw new IllegalArgumentException("This paid course is not currently available.");

    Boolean access = jdbc.queryForObject("""
      select exists(select 1 from student_program_enrollment
          where student_id=? and program_id=? and status in ('ACTIVE','PAUSED'))
        or exists(select 1 from course_purchase where student_id=? and course_id=?)
      """, Boolean.class, studentId, courseId, studentId, courseId);
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
          guardian_user_id,student_id,course_id,status,requested_at,created_at,updated_at
        ) values(?,?,?,'PENDING',now(),now(),now())
        on conflict (guardian_user_id,student_id,course_id) where status='PENDING'
        do update set updated_at=now()
        returning id
        """, Long.class, guardianId, studentId, courseId);
      requestIds.add(requestId);
    }
    return Map.of("requested", true, "studentId", studentId,
        "courseId", courseId, "requestIds", requestIds, "notifiedParents", requestIds.size());
  }

  @GetMapping("/parent/course-requests")
  public List<Map<String,Object>> parentRequests(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
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
      join course_marketplace_offer o on o.course_id=p.id and o.status='PUBLISHED'
      where r.guardian_user_id=? and r.status='PENDING'
      order by r.requested_at desc,r.id desc
      """, context.userId());
  }

  @PostMapping("/parent/course-requests/{requestId}/dismiss")
  @Transactional
  public Map<String,Object> dismissRequest(
      @PathVariable long requestId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
    int changed = jdbc.update("""
      update parent_course_purchase_request
      set status='DECLINED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
      where id=? and guardian_user_id=? and status='PENDING'
      """, context.userId(), requestId, context.userId());
    if (changed != 1) throw new IllegalArgumentException("This purchase request is no longer pending.");
    return Map.of("dismissed", true, "requestId", requestId);
  }
}
