package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  public RecommendationController(JdbcTemplate jdbc,AuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "Select the institution for recommendations.");
    }
    return tenant.institutionId();
  }

  @GetMapping("/next")
  public Map<String,Object> next(
      @RequestParam Long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant){
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    if(courseId==null || courseId<=0L) throw new IllegalArgumentException("Select an enrolled course for recommendations.");
    Long enrolled=jdbc.queryForObject("""
      select count(*)
      from student_program_enrollment spe
      join institution_membership membership
        on membership.student_id=spe.student_id and membership.institution_id=spe.institution_id and membership.status='ACTIVE'
      join institution_tenant tenant on tenant.id=spe.institution_id and tenant.status='ACTIVE'
      join curriculum_program course on course.id=spe.program_id and course.status='ACTIVE' and course.code<>'GENERAL'
      join institution_course_assignment assignment
        on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=assignment.course_release_id and release.program_id=course.id
      where spe.student_id=? and spe.institution_id=? and spe.program_id=? and spe.status='ACTIVE'
        and spe.course_access_blocked=false
        and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
      """,Long.class,context.studentId(),institutionId,courseId);
    if(enrolled==null || enrolled==0L) throw new SecurityException("The selected course is not enrolled in this institution.");

    Long diagnostics=jdbc.queryForObject("""
      select count(*)
      from student_diagnostic_attempt diagnostic
      where diagnostic.student_id=? and diagnostic.institution_id=? and diagnostic.course_id=?
        and diagnostic.status='SUBMITTED'
      """,Long.class,context.studentId(),institutionId,courseId);
    if(diagnostics==null||diagnostics==0)
      return Map.of("available",true,"kind","DIAGNOSTIC","reason","INITIAL_DIAGNOSTIC","courseId",courseId);

    List<Map<String,Object>> rows=jdbc.queryForList("""
      select l.id,l.code,l.title,l.summary,l.estimated_minutes,
             ch.code as chapter_code,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,
             coalesce(round(avg(coalesce(m.mastery_percent,diagnostic_result.percent,0))),0) as mastery,
             diagnostic_result.percent as initial_diagnostic_percent,
             test_result.percent as latest_assessment_percent,
             selected_course.id as course_id,selected_course.display_name as course_name
      from curriculum_program selected_course
      join student_program_enrollment spe
        on spe.program_id=selected_course.id and spe.student_id=? and spe.institution_id=? and spe.status='ACTIVE'
      join institution_course_assignment assignment
        on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=assignment.course_release_id and release.program_id=selected_course.id
      join curriculum_program_subject mapped on mapped.program_id=selected_course.id
      join curriculum_subject s on s.id=mapped.subject_id and s.class_id=mapped.class_id and s.active=true
        and (s.program_id=selected_course.id or (s.program_id is null and selected_course.class_id=s.class_id))
      join curriculum_class c on c.id=s.class_id and c.active=true
      join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
      join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
      left join question q on q.lesson_id=l.id and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
      left join student_concept_mastery m
        on m.student_id=? and m.institution_id=spe.institution_id and m.concept_id=q.concept_id
      left join lateral (
        select round(100.0*count(*) filter (where picked.is_correct=true)/
          nullif(count(*) filter (where picked.is_correct is not null),0))::int as percent
        from student_diagnostic_attempt diagnostic
        join student_diagnostic_attempt_question picked
          on picked.diagnostic_attempt_id=diagnostic.id
        where diagnostic.student_id=? and diagnostic.institution_id=spe.institution_id
          and diagnostic.course_id=selected_course.id and diagnostic.status='SUBMITTED'
          and picked.lesson_id=l.id
      ) diagnostic_result on true
      left join student_lesson_progress p
        on p.student_id=? and p.institution_id=spe.institution_id and p.lesson_id=l.id
      left join lateral (
        select round(100.0*att.final_score/nullif(att.max_score,0))::int as percent
        from student_assessment_attempt att
        join assessment a on a.id=att.assessment_id
        where att.student_id=? and att.institution_id=spe.institution_id and att.status='RELEASED'
          and att.final_score is not null and att.max_score>0
          and (a.lesson_id=l.id or (a.lesson_id is null and a.chapter_id=ch.id))
        order by att.released_at desc,att.id desc limit 1
      ) test_result on true
      where selected_course.id=? and selected_course.status='ACTIVE'
      group by l.id,ch.id,s.id,p.status,test_result.percent,diagnostic_result.percent,selected_course.id,selected_course.display_name
      having coalesce(p.status,'NOT_STARTED') <> 'COMPLETED'
          or (test_result.percent is not null and test_result.percent < 80)
          or (diagnostic_result.percent is not null and diagnostic_result.percent < 80)
          or (count(m.id)>0 and avg(m.mastery_percent)<80)
      order by case when coalesce(p.status,'NOT_STARTED')='COMPLETED' then 1 else 0 end,
               coalesce(round(avg(coalesce(m.mastery_percent,diagnostic_result.percent,0))*0.7 +
                 coalesce(test_result.percent,avg(coalesce(m.mastery_percent,diagnostic_result.percent,0)))*0.3),0),
               s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id
      limit 1
      """,context.studentId(),institutionId,context.studentId(),context.studentId(),context.studentId(),context.studentId(),courseId);
    if(rows.isEmpty()) return Map.of("available",false,"courseId",courseId);
    return Map.of("available",true,"reason","PROGRESS_DIAGNOSTIC_MASTERY_AND_RELEASED_ASSESSMENTS","courseId",courseId,"lesson",rows.getFirst());
  }
}
