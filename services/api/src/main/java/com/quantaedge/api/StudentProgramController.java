package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Additive course catalogue and self-enrolment. The numeric course ID is the stable identity. */
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
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String,Object>> programs = jdbc.queryForList("""
      select p.id,p.id as course_id,p.public_id,p.public_id_prefix,p.code,p.display_name,p.curriculum_version,p.status,
        exists(select 1 from student_program_enrollment spe
          where spe.student_id=st.id and spe.program_id=p.id and spe.status='ACTIVE') as enrolled,
        exists(select 1 from student_program_enrollment spe
          where spe.student_id=st.id and spe.program_id=p.id and spe.status='PAUSED') as paused
      from student st
      join curriculum_program p on p.status='ACTIVE'
      where st.id=? and st.active=true and p.code<>'GENERAL'
      order by p.display_name,p.id
      """, context.studentId());

    for (Map<String,Object> program : programs) {
      long courseId = ((Number) program.get("id")).longValue();
      List<Map<String,Object>> subjects = jdbc.queryForList("""
        select s.id,s.code as subject_code,s.display_name as subject_name,
          c.code as curriculum_class_code,s.sort_order,
          count(distinct ch.id) as chapters,count(distinct l.id) as lessons
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
        join curriculum_class c on c.id=s.class_id
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
          and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where ps.program_id=?
        group by s.id,s.code,s.display_name,c.code,s.sort_order
        order by ps.sort_order,s.sort_order,s.id
        """, courseId);
      program.put("subjects", subjects);
      program.put("is_enrolled", Boolean.TRUE.equals(program.get("enrolled")));
    }
    return programs;
  }

  /** Stable ID route. Code and display name may change without changing enrollments. */
  @PostMapping("/id/{courseId}/enroll")
  @Transactional
  public Map<String,Object> enrollById(@PathVariable long courseId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireStudent(context);
    return enrollProgramId(courseId, context);
  }

  /** Compatibility route for older clients. New clients should enrol using the numeric ID. */
  @PostMapping("/{programCode}/enroll")
  @Transactional
  public Map<String,Object> enroll(@PathVariable String programCode,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireStudent(context);
    String code = programCode == null ? "" : programCode.trim().toUpperCase(java.util.Locale.ROOT);
    if (!code.matches("[A-Z0-9_-]{2,60}") || "GENERAL".equals(code)) {
      throw new IllegalArgumentException("Choose a valid additional course.");
    }
    List<Map<String,Object>> matches = jdbc.queryForList("""
      select p.id
      from curriculum_program p
      where exists(select 1 from student_program_enrollment spe
          where spe.student_id=? and spe.program_id=p.id and spe.status='ACTIVE')
        and upper(p.code)=? and p.status='ACTIVE' and p.code<>'GENERAL'
      order by p.id
      """, context.studentId(), code);
    // Resolve already-enrolled legacy-code access if unique; if not enrolled, use the unique catalogue record.
    if (matches.isEmpty()) {
      matches = jdbc.queryForList("""
        select p.id from curriculum_program p
        where upper(p.code)=? and p.status='ACTIVE' and p.code<>'GENERAL'
        order by p.id
        """, code);
    }
    if (matches.size() != 1) {
      throw new IllegalArgumentException(matches.isEmpty()
          ? "This course is not available or is not active."
          : "This course code is ambiguous; select the course using its stable numeric ID.");
    }
    return enrollProgramId(((Number)matches.getFirst().get("id")).longValue(), context);
  }

  private Map<String,Object> enrollProgramId(long courseId, AuthContext context) {
    List<Map<String,Object>> matches = jdbc.queryForList("""
      select p.id,p.public_id,p.code,p.display_name,p.status
      from curriculum_program p
      join student st on st.id=? and st.active=true
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """, context.studentId(), courseId);
    if (matches.isEmpty()) {
      throw new SecurityException("This course is not available or is not active.");
    }
    Map<String,Object> course = matches.getFirst();
    // Existing enrollment/purchase permits resuming the course regardless of later offer edits.
    // A new self-enrollment is allowed only for an explicitly published free offer. Missing,
    // draft, archived, and paid offers must never turn into a free-enrollment path.
    Boolean hasAuthorisedEnrolment = jdbc.queryForObject("""
      select
        exists(
          select 1 from student_program_enrollment e
          where e.student_id=? and e.program_id=? and e.status in ('ACTIVE','PAUSED')
        )
        or exists(
          select 1 from course_purchase cp
          where cp.student_id=? and cp.course_id=?
        )
        or exists(
          select 1 from course_marketplace_offer o
          where o.course_id=? and o.status='PUBLISHED'
            and o.price_paise=0 and o.currency='INR'
        )
        or exists(
          select 1
          from institution_membership im
          join institution_tenant it on it.id=im.institution_id and it.status='ACTIVE'
          join institution_course_entitlement e on e.institution_id=im.institution_id
            and e.program_id=? and e.status='ACTIVE' and e.valid_from<=now()
            and (e.valid_until is null or e.valid_until>now())
          where im.student_id=? and im.status='ACTIVE'
        )
      """, Boolean.class, context.studentId(), courseId, context.studentId(), courseId, courseId, courseId, context.studentId());
    if (!Boolean.TRUE.equals(hasAuthorisedEnrolment)) {
      throw new SecurityException("This course is not available for self-enrolment. Choose a published free course or purchase its paid offer.");
    }
    List<Map<String,Object>> subjects = jdbc.queryForList("""
      select s.id,s.code
      from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
        and s.class_id=ps.class_id and s.active=true
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """, courseId);
    if (subjects.isEmpty()) throw new IllegalStateException("The course has no active subject tracks yet.");

    // Only one course is active at a time, but older enrollments stay paused and resumable.
    jdbc.update("""
      update student_program_enrollment set status='PAUSED',updated_at=now()
      where student_id=? and status='ACTIVE' and program_id<>?
      """, context.studentId(), courseId);
    jdbc.update("""
      insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
      values(?,?,'ACTIVE',now(),null,now(),now())
      on conflict(student_id,program_id) do update
        set status='ACTIVE',enrolled_at=case
              when student_program_enrollment.status='ACTIVE' then student_program_enrollment.enrolled_at
              else now() end,
            ended_at=null,updated_at=now()
      """, context.studentId(), courseId);
    for (Map<String,Object> subject : subjects) {
      long subjectId = ((Number) subject.get("id")).longValue();
      jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,status)
        values(?,?,'ACTIVE')
        on conflict(student_id,subject_id) do update set status='ACTIVE',updated_at=now()
        """, context.studentId(), subjectId);
    }
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("enrolled", true);
    result.put("courseId", courseId);
    result.put("publicId", course.get("public_id"));
    result.put("programId", courseId);
    result.put("programCode", course.get("code"));
    result.put("programName", course.get("display_name"));
    result.put("subjectCount", subjects.size());
    return result;
  }
}
