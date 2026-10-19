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
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;

  public AdminAnalyticsController(JdbcTemplate jdbc, AuthorizationService authorization) {
    this.jdbc=jdbc;
    this.authorization=authorization;
  }

  @GetMapping
  public Map<String,Object> overview(
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
      select count(distinct ts.student_id) filter(where ts.created_at>=now()-interval '30 days') as active_students_30d,
             coalesce(round(avg(least(240,greatest(0,
               ceil(extract(epoch from (coalesce(ts.ended_at,ts.last_message_at)-ts.created_at))/60)::int)))
               filter(where ts.created_at>=now()-interval '30 days' and coalesce(ts.ended_at,ts.last_message_at) is not null),1),0) as avg_session_minutes,
             count(distinct ts.id) filter(where ts.created_at>=now()-interval '30 days') as sessions_30d
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
      where ts.institution_id=?
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      """,courseId,institutionId));
    result.put("learning",jdbc.queryForMap("""
      select count(*) as attempts_30d,
             count(*) filter(where qa.correct=true) as correct_30d,
             coalesce(round(100.0*count(*) filter(where qa.correct=true)/
               nullif(count(*) filter(where qa.correct is not null),0),1),0) as accuracy_30d
      from student_question_attempt qa
      join student_program_enrollment spe on spe.student_id=qa.student_id
        and spe.institution_id=qa.institution_id and spe.program_id=? and spe.status='ACTIVE'
      join institution_membership im on im.student_id=qa.student_id
        and im.institution_id=qa.institution_id and im.status='ACTIVE'
      join question q on q.id=qa.question_id
      join lesson l on l.id=q.lesson_id and l.active=true
      join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
      where qa.institution_id=? and qa.answered_at>=now()-interval '30 days'
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      """,courseId,institutionId));
    result.put("daily",jdbc.queryForList("""
      select d::date as day,
             coalesce(s.sessions,0) as sessions,
             coalesce(a.attempts,0) as attempts,
             coalesce(a.accuracy,0) as accuracy
      from generate_series(current_date-interval '13 days',current_date,interval '1 day') d
      left join (
        select ts.created_at::date as day,count(distinct ts.id) as sessions
        from tutor_session ts
        join student_program_enrollment spe on spe.student_id=ts.student_id
          and spe.institution_id=ts.institution_id and spe.program_id=? and spe.status='ACTIVE'
        join institution_membership im on im.student_id=ts.student_id
          and im.institution_id=ts.institution_id and im.status='ACTIVE'
        join lesson l on l.id=ts.lesson_id and l.active=true
        join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
        join curriculum_subject s on s.id=ch.subject_id and s.active=true
        join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
        join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
        where ts.institution_id=? and ts.created_at>=current_date-interval '13 days'
          and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
        group by ts.created_at::date
      ) s on s.day=d::date
      left join (
        select qa.answered_at::date as day,count(*) as attempts,
               coalesce(round(100.0*count(*) filter(where qa.correct=true)/
                 nullif(count(*) filter(where qa.correct is not null),0),1),0) as accuracy
        from student_question_attempt qa
        join student_program_enrollment spe on spe.student_id=qa.student_id
          and spe.institution_id=qa.institution_id and spe.program_id=? and spe.status='ACTIVE'
        join institution_membership im on im.student_id=qa.student_id
          and im.institution_id=qa.institution_id and im.status='ACTIVE'
        join question q on q.id=qa.question_id
        join lesson l on l.id=q.lesson_id and l.active=true
        join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
        join curriculum_subject s on s.id=ch.subject_id and s.active=true
        join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
        join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
        where qa.institution_id=? and qa.answered_at>=current_date-interval '13 days'
          and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
        group by qa.answered_at::date
      ) a on a.day=d::date
      order by day
      """,courseId,institutionId,courseId,institutionId));
    result.put("mastery",jdbc.queryForList("""
      select course.id as course_id,course.display_name as course_name,
             s.code as subject_code,s.display_name as subject_name,
             count(distinct cc.id) as concepts,
             coalesce(round(avg(m.mastery_percent),1),0) as average_mastery
      from student_concept_mastery m
      join student_program_enrollment spe on spe.student_id=m.student_id
        and spe.institution_id=m.institution_id and spe.program_id=? and spe.status='ACTIVE'
      join institution_membership im on im.student_id=m.student_id
        and im.institution_id=m.institution_id and im.status='ACTIVE'
      join chapter_concept cc on cc.id=m.concept_id
      join curriculum_chapter ch on ch.id=cc.chapter_id and ch.active=true
      join curriculum_subject s on s.id=ch.subject_id and s.active=true
      join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
      join curriculum_program_subject cps on cps.program_id=course.id and cps.subject_id=s.id and cps.class_id=s.class_id
      where m.institution_id=?
        and (s.program_id=course.id or (s.program_id is null and course.class_id=s.class_id))
      group by course.id,course.display_name,s.code,s.display_name
      order by course.display_name,s.display_name
      """,courseId,institutionId));
    return result;
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant==null || tenant.institutionId()<1L) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before accessing administration analytics.");
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
