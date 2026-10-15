package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Additive program catalogue and self-enrolment; class membership is never changed. */
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
      select p.id,p.code,p.display_name,p.curriculum_version,p.status,c.code as class_code,
        exists(select 1 from student_program_enrollment spe
          where spe.student_id=st.id and spe.program_id=p.id and spe.status='ACTIVE') as enrolled,
        exists(select 1 from student_program_enrollment spe
          where spe.student_id=st.id and spe.program_id=p.id and spe.status='PAUSED') as paused
      from student st
      join curriculum_class c on c.code=st.class_code and c.active=true
      join curriculum_program p on p.class_id=c.id and p.status='ACTIVE'
      where st.id=? and st.active=true and p.code<>'GENERAL'
      order by p.display_name,p.id
      """, context.studentId());

    for (Map<String,Object> program : programs) {
      long programId = ((Number) program.get("id")).longValue();
      List<Map<String,Object>> subjects = jdbc.queryForList("""
        select s.id,s.code as subject_code,s.display_name as subject_name,s.sort_order,
          count(distinct ch.id) as chapters,count(distinct l.id) as lessons
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
          and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where ps.program_id=? and ps.class_id=(select class_id from curriculum_program where id=?)
        group by s.id,s.code,s.display_name,s.sort_order
        order by ps.sort_order,s.sort_order,s.id
        """, programId, programId);
      program.put("subjects", subjects);
      program.put("is_enrolled", Boolean.TRUE.equals(program.get("enrolled")));
    }
    return programs;
  }

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
      select p.id,p.code,p.display_name,c.code as class_code
      from curriculum_program p
      join curriculum_class c on c.id=p.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      where upper(p.code)=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """, context.studentId(), code);
    if (matches.isEmpty()) {
      throw new SecurityException("This course is not available for your class or is not active.");
    }
    Map<String,Object> program = matches.getFirst();
    long programId = ((Number) program.get("id")).longValue();
    List<Map<String,Object>> subjects = jdbc.queryForList("""
      select s.id,s.code
      from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
        and s.class_id=ps.class_id and s.active=true
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """, programId);
    if (subjects.isEmpty()) {
      throw new IllegalStateException("The course has no active subject tracks yet.");
    }

    jdbc.update("""
      insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
      values(?,?,'ACTIVE',now(),null,now(),now())
      on conflict(student_id,program_id) do update
        set status='ACTIVE',enrolled_at=case
              when student_program_enrollment.status='ACTIVE' then student_program_enrollment.enrolled_at
              else now() end,
            ended_at=null,updated_at=now()
      """, context.studentId(), programId);
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
    result.put("programId", programId);
    result.put("programCode", program.get("code"));
    result.put("programName", program.get("display_name"));
    result.put("classCode", program.get("class_code"));
    result.put("subjectCount", subjects.size());
    return result;
  }
}
