package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/tutor")
public class TutorAnalyticsController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public TutorAnalyticsController(JdbcTemplate jdbc,AuthorizationService authorization) {
    this.jdbc=jdbc;
    this.authorization=authorization;
  }

  @GetMapping("/analytics")
  public Map<String,Object> analytics(
      @RequestParam Long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    authorization.requireAdmin(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("courseId",courseId);
    result.put("institutionId",institutionId);
    result.put("summary",jdbc.queryForMap("""
      select count(distinct ts.student_id) filter(where tm.created_at>=now()-interval '30 days') as active_students_30d,
             count(tm.id) filter(where tm.role='USER' and tm.created_at>=now()-interval '30 days') as user_messages_30d,
             count(tm.id) filter(where tm.role='ASSISTANT' and tm.created_at>=now()-interval '30 days') as assistant_messages_30d,
             coalesce(sum(tm.input_tokens) filter(where tm.created_at>=now()-interval '30 days'),0) as input_tokens_30d,
             coalesce(sum(tm.output_tokens) filter(where tm.created_at>=now()-interval '30 days'),0) as output_tokens_30d
      from tutor_session ts
      join student_program_enrollment spe on spe.student_id=ts.student_id
        and spe.institution_id=ts.institution_id and spe.program_id=? and spe.status='ACTIVE'
      join institution_membership im on im.student_id=ts.student_id
        and im.institution_id=ts.institution_id and im.status='ACTIVE'
      join student st on st.id=ts.student_id and st.active=true and st.environment='PRODUCTION'
      join lesson l on l.id=ts.lesson_id and l.active=true
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
      left join tutor_message tm on tm.session_id=ts.id
      where ts.institution_id=?
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      """,courseId,institutionId));
    result.put("modes",jdbc.queryForList("""
      select coalesce(tm.mode,'UNKNOWN') as mode,count(*) as messages
      from tutor_message tm
      join tutor_session ts on ts.id=tm.session_id
      join student_program_enrollment spe on spe.student_id=ts.student_id
        and spe.institution_id=ts.institution_id and spe.program_id=? and spe.status='ACTIVE'
      join institution_membership im on im.student_id=ts.student_id
        and im.institution_id=ts.institution_id and im.status='ACTIVE'
      join lesson l on l.id=ts.lesson_id and l.active=true
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
      where ts.institution_id=? and tm.role='USER' and tm.created_at>=now()-interval '30 days'
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      group by coalesce(tm.mode,'UNKNOWN') order by messages desc
      """,courseId,institutionId));
    result.put("providers",jdbc.queryForList("""
      select coalesce(tm.model,'unknown') as model,count(*) as replies,
             coalesce(sum(tm.input_tokens),0) as input_tokens,
             coalesce(sum(tm.output_tokens),0) as output_tokens
      from tutor_message tm
      join tutor_session ts on ts.id=tm.session_id
      join student_program_enrollment spe on spe.student_id=ts.student_id
        and spe.institution_id=ts.institution_id and spe.program_id=? and spe.status='ACTIVE'
      join institution_membership im on im.student_id=ts.student_id
        and im.institution_id=ts.institution_id and im.status='ACTIVE'
      join lesson l on l.id=ts.lesson_id and l.active=true
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
      where ts.institution_id=? and tm.role='ASSISTANT' and tm.created_at>=now()-interval '30 days'
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      group by coalesce(tm.model,'unknown') order by replies desc,model
      """,courseId,institutionId));
    return result;
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if(tenant==null || tenant.institutionId()<1L) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before accessing tutor analytics.");
    }
    return tenant.institutionId();
  }

  private void requireAssignedCourse(long courseId,long institutionId) {
    Long allowed=jdbc.queryForObject("""
      select count(*) from curriculum_program p
      join institution_course_assignment assignment on assignment.institution_id=?
        and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id
        and release.program_id=p.id
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """,Long.class,institutionId,courseId);
    if(allowed==null || allowed<1L) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "Selected course is not assigned and released to this institution.");
    }
  }
}
