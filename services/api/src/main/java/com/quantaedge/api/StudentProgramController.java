package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Course ownership and self-enrolment for the selected institution.
 *
 * A global curriculum row is never enough to make a learner-facing course discoverable or
 * accessible. All learner access checks in this controller bind to the request's exact
 * InstitutionTenantContext and the active course assignment in that institution.
 */
@RestController
@RequestMapping("/api/v1/students/me/programs")
public class StudentProgramController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public StudentProgramController(JdbcTemplate jdbc, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.authorization = authorization;
  }

  @GetMapping
  public List<Map<String,Object>> programs(
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId = requireInstitution(tenant);
    List<Map<String,Object>> programs = jdbc.queryForList("""
      select distinct p.id,p.id as course_id,p.public_id,p.public_id_prefix,p.code,p.display_name,
        p.curriculum_version,p.status,
        exists(select 1 from student_program_enrollment spe
          where spe.institution_id=? and spe.student_id=st.id and spe.program_id=p.id
            and spe.status='ACTIVE') as enrolled,
        (exists(select 1 from student_program_enrollment spe
          where spe.institution_id=? and spe.student_id=st.id and spe.program_id=p.id
            and spe.status='PAUSED')) as paused,
        exists(select 1 from course_purchase purchase
          where purchase.institution_id=? and purchase.student_id=st.id and purchase.course_id=p.id) as purchased,
        (exists(select 1 from student_program_enrollment access_enrollment
            where access_enrollment.institution_id=? and access_enrollment.student_id=st.id
              and access_enrollment.program_id=p.id and access_enrollment.status in ('ACTIVE','PAUSED'))
          or exists(select 1 from course_purchase access_purchase
            where access_purchase.institution_id=? and access_purchase.student_id=st.id and access_purchase.course_id=p.id)

          or exists(select 1 from course_entitlement access_entitlement
            where access_entitlement.institution_id=? and access_entitlement.course_id=p.id
              and (access_entitlement.holder_student_id=st.id or access_entitlement.holder_user_id=st.user_account_id))
        ) as has_access
      from student st
      join curriculum_program p on p.status='ACTIVE'
      where st.id=? and st.active=true and p.code<>'GENERAL'
        and exists(
          select 1
          from institution_course_assignment assignment
          join institution_course_release release on release.id=assignment.course_release_id
            and release.program_id=p.id
          join institution_tenant tenant_scope on tenant_scope.id=assignment.institution_id
            and tenant_scope.status='ACTIVE'
          where assignment.institution_id=? and assignment.status='ASSIGNED'
        )
        and (
          exists(select 1 from institution_marketplace_offer offer
            join institution_course_assignment published_assignment
              on published_assignment.id=offer.assignment_id
              and published_assignment.institution_id=offer.institution_id
              and published_assignment.status='ASSIGNED'
            join institution_course_release published_release
              on published_release.id=offer.course_release_id
              and published_release.program_id=offer.course_id
            where offer.institution_id=? and offer.course_id=p.id
              and offer.status='PUBLISHED')
          or exists(select 1 from student_program_enrollment spe
            where spe.institution_id=? and spe.student_id=st.id and spe.program_id=p.id
              and spe.status in ('ACTIVE','PAUSED'))
          or exists(select 1 from course_purchase purchase
            where purchase.institution_id=? and purchase.student_id=st.id and purchase.course_id=p.id)

          or exists(select 1 from course_entitlement ce
            where ce.institution_id=? and ce.course_id=p.id
              and (ce.holder_student_id=st.id or ce.holder_user_id=st.user_account_id))
        )
        and not exists(
          select 1
          from curriculum_program newer_program
          join institution_course_release newer_release
            on newer_release.program_id=newer_program.id and newer_release.release_state='SHAREABLE'
            and newer_release.content_fingerprint=course_content_fingerprint(newer_release.program_id)
          join institution_course_assignment newer_assignment
            on newer_assignment.course_release_id=newer_release.id
            and newer_assignment.institution_id=? and newer_assignment.status='ASSIGNED'
          join institution_marketplace_offer newer_offer
            on newer_offer.assignment_id=newer_assignment.id
            and newer_offer.institution_id=newer_assignment.institution_id
            and newer_offer.course_release_id=newer_release.id
            and newer_offer.course_id=newer_program.id
            and newer_offer.status='PUBLISHED'
            and newer_offer.hrms_published_by_staff_id is not null
          where p.course_identity_id is not null
            and newer_program.course_identity_id=p.course_identity_id
            and newer_program.content_version_no>p.content_version_no
            and newer_program.status='ACTIVE'
        )
      order by p.display_name,p.id
      """, institutionId, institutionId, institutionId, institutionId, institutionId, institutionId,
          context.studentId(), institutionId, institutionId, institutionId, institutionId, institutionId,
          institutionId);

    for (Map<String,Object> program : programs) {
      long courseId = ((Number) program.get("id")).longValue();
      program.put("subjects", subjectsForProgram(courseId));
      program.put("is_enrolled", Boolean.TRUE.equals(program.get("enrolled")));
    }
    return programs;
  }

  /**
   * Loads the exact subject mapping for one active course. Kept as a focused method so the
   * migrated-database smoke test exercises the same SQL that the learner catalogue uses.
   */
  List<Map<String,Object>> subjectsForProgram(long courseId) {
    return jdbc.queryForList("""
      select s.id,s.code as subject_code,s.display_name as subject_name,
        c.code as curriculum_class_code,s.sort_order,
        count(distinct ch.id) as chapters,count(distinct l.id) as lessons
      from curriculum_program_subject ps
      join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
        and ch.content_status='PUBLISHED'
      left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      where ps.program_id=?
      group by ps.sort_order,s.id,s.code,s.display_name,c.code,s.sort_order
      order by ps.sort_order,s.sort_order,s.id
      """, courseId);
  }

  /** Stable ID route. An institution assignment, not a global program row, determines eligibility. */
  @PostMapping("/id/{courseId}/enroll")
  @Transactional
  public Map<String,Object> enrollById(@PathVariable long courseId,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    return enrollProgramId(courseId, context, requireInstitution(tenant));
  }

  /** Compatibility route for older clients. New clients should enrol using the numeric ID. */
  @PostMapping("/{programCode}/enroll")
  @Transactional
  public Map<String,Object> enroll(@PathVariable String programCode,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId = requireInstitution(tenant);
    String code = programCode == null ? "" : programCode.trim().toUpperCase(java.util.Locale.ROOT);
    if (!code.matches("[A-Z0-9_-]{2,60}") || "GENERAL".equals(code)) {
      throw new IllegalArgumentException("Choose a valid additional course.");
    }
    List<Map<String,Object>> matches = jdbc.queryForList("""
      select distinct p.id
      from curriculum_program p
      join institution_course_assignment assignment
        on assignment.institution_id=? and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=p.id
      where upper(p.code)=? and p.status='ACTIVE' and p.code<>'GENERAL'
      order by p.id
      """, institutionId, code);
    if (matches.size() != 1) {
      throw new IllegalArgumentException(matches.isEmpty()
          ? "This course is not available to the selected institution."
          : "This course code is ambiguous; select the course using its stable numeric ID.");
    }
    return enrollProgramId(((Number)matches.getFirst().get("id")).longValue(), context, institutionId);
  }

  private Map<String,Object> enrollProgramId(long courseId, AuthContext context, long institutionId) {
    List<Map<String,Object>> matches = jdbc.queryForList("""
      select p.id,p.public_id,p.code,p.display_name,p.status
      from curriculum_program p
      join student st on st.id=? and st.active=true
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
        and exists(
          select 1
          from institution_course_assignment assignment
          join institution_course_release release on release.id=assignment.course_release_id
            and release.program_id=p.id
          join institution_tenant tenant_scope on tenant_scope.id=assignment.institution_id
            and tenant_scope.status='ACTIVE'
          where assignment.institution_id=? and assignment.status='ASSIGNED'
        )
      """, context.studentId(), courseId, institutionId);
    if (matches.isEmpty()) {
      throw new SecurityException("This course is not available to the selected institution.");
    }
    Map<String,Object> course = matches.getFirst();

    Boolean hasAuthorisedEnrolment = jdbc.queryForObject("""
      select
        exists(select 1 from student_program_enrollment e
          where e.student_id=? and e.program_id=? and e.institution_id=?
            and e.status in ('ACTIVE','PAUSED'))
        or exists(select 1 from course_purchase cp
          where cp.student_id=? and cp.course_id=? and cp.institution_id=?)

        or exists(select 1 from course_entitlement ce
          where ce.institution_id=? and ce.course_id=?
            and (ce.holder_student_id=? or ce.holder_user_id=(select user_account_id from student where id=?)))
        or exists(
          select 1
          from institution_marketplace_offer offer
          join institution_course_assignment assignment
            on assignment.institution_id=offer.institution_id
            and assignment.id=offer.assignment_id and assignment.status='ASSIGNED'
          join institution_course_release release
            on release.id=offer.course_release_id and release.program_id=offer.course_id
          where offer.institution_id=? and offer.course_id=? and offer.status='PUBLISHED'
            and offer.price_paise=0 and offer.currency='INR'
        )
      """, Boolean.class, context.studentId(), courseId, institutionId,
          context.studentId(), courseId, institutionId,
          institutionId, courseId, context.studentId(), context.studentId(),
          institutionId, courseId);
    if (!Boolean.TRUE.equals(hasAuthorisedEnrolment)) {
      throw new SecurityException("This course is not available for self-enrolment. Choose a published free offer or purchase the selected institution's offer.");
    }

    List<Map<String,Object>> subjects = jdbc.queryForList("""
      select s.id,s.code
      from curriculum_program_subject ps
      join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """, courseId);
    if (subjects.isEmpty()) throw new IllegalStateException("The course has no active subject tracks yet.");

    Boolean conflictingTrack = jdbc.queryForObject("""
      select exists(
        select 1 from student_track_enrollment track
        join curriculum_program_subject ps on ps.subject_id=track.subject_id and ps.program_id=?
        where track.student_id=? and track.institution_id is distinct from ?
      )
      """, Boolean.class, courseId, context.studentId(), institutionId);
    if (Boolean.TRUE.equals(conflictingTrack)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A matching subject track already exists under another institution. Cross-institution learning history will not be reused.");
    }

    // Enrollment is independent of the learner's primary/focused course. Keep other
    // institution-scoped enrollments active so reports can be switched without altering
    // eligibility or historical progress for any other course.
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',now(),null,now(),now())
      on conflict(institution_id,student_id,program_id) do update
        set status='ACTIVE',
            enrolled_at=case when student_program_enrollment.status='ACTIVE'
              then student_program_enrollment.enrolled_at else now() end,
            ended_at=null,updated_at=now()
      """, context.studentId(), courseId, institutionId);
    for (Map<String,Object> subject : subjects) {
      long subjectId = ((Number) subject.get("id")).longValue();
      jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,institution_id,status)
        values(?,?,?,'ACTIVE')
        on conflict(institution_id,student_id,subject_id) do update set status='ACTIVE',updated_at=now()
        """, context.studentId(), subjectId, institutionId);
    }

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("enrolled", true);
    result.put("institutionId", institutionId);
    result.put("courseId", courseId);
    result.put("publicId", course.get("public_id"));
    result.put("programId", courseId);
    result.put("programCode", course.get("code"));
    result.put("programName", course.get("display_name"));
    result.put("subjectCount", subjects.size());
    return result;
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() <= 0 || tenant.tenantCode() == null
        || tenant.tenantCode().isBlank()) {
      throw new IllegalArgumentException("An institution context is required for course access.");
    }
    return tenant.institutionId();
  }
}
