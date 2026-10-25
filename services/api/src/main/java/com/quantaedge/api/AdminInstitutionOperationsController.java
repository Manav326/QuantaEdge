package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Institution operations in the Education Admin workspace.
 *
 * All reads and transitions are scoped to the active institution binding assigned by HRMS.
 * The request cannot select an institution by URL, body or browser storage. Source-release
 * transitions delegate to the shared workflow service; Marketplace publication remains HRMS-only.
 */
@RestController
@RequestMapping("/api/v1/admin/institution-operations")
public class AdminInstitutionOperationsController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final InstitutionCourseReleaseWorkflowService releaseWorkflow;

  public AdminInstitutionOperationsController(
      JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService staffAudit,
      InstitutionCourseReleaseWorkflowService releaseWorkflow) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.releaseWorkflow = releaseWorkflow;
  }

  @GetMapping("/overview")
  public Map<String,Object> overview(
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext actor=requireEducationStaff(supplied,tenant,"INSTITUTION_REPORTS_VIEW");
    Map<String,Object> row=jdbc.queryForMap("""
        select
          (select count(*) from institution_membership m
            where m.institution_id=? and m.status='ACTIVE') as active_learners,
          (select count(*) from institution_membership m
            where m.institution_id=?) as all_memberships,
          (select count(distinct coalesce(p.course_identity_id,-p.id))
            from institution_course_assignment a
            join institution_course_release r on r.id=a.course_release_id
            join curriculum_program p on p.id=r.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
            where a.institution_id=? and a.status='ASSIGNED'
              and (a.assignment_source<>'ORIGIN' or r.release_state='SHAREABLE')) as authorized_courses,
          (select count(distinct (p.student_id,p.lesson_id)) from institution_membership m
            join student_lesson_progress p on p.student_id=m.student_id
            join lesson l on l.id=p.lesson_id and l.active=true and l.status='PUBLISHED'
            join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
            join curriculum_subject sub on sub.id=ch.subject_id and sub.active=true
            join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
            join institution_course_release r on r.id=a.course_release_id
              and r.program_id=sub.program_id and r.release_state='SHAREABLE'
            join curriculum_program assigned_program on assigned_program.id=r.program_id
              and assigned_program.status='ACTIVE' and assigned_program.code<>'GENERAL'
            where m.institution_id=? and m.status='ACTIVE') as lessons_with_activity,
          (select count(distinct (p.student_id,p.lesson_id)) from institution_membership m
            join student_lesson_progress p on p.student_id=m.student_id and p.status='COMPLETED'
            join lesson l on l.id=p.lesson_id and l.active=true and l.status='PUBLISHED'
            join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
            join curriculum_subject sub on sub.id=ch.subject_id and sub.active=true
            join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
            join institution_course_release r on r.id=a.course_release_id
              and r.program_id=sub.program_id and r.release_state='SHAREABLE'
            join curriculum_program assigned_program on assigned_program.id=r.program_id
              and assigned_program.status='ACTIVE' and assigned_program.code<>'GENERAL'
            where m.institution_id=? and m.status='ACTIVE') as completed_lessons,
          (select count(*) from institution_billable_event be
            join institution_billing_contract bc on bc.id=be.contract_id
            where be.institution_id=? and be.event_type='CHARGE'
              and be.billing_period_start=case when bc.billing_cadence='ANNUAL'
                then date_trunc('year',current_date)::date else date_trunc('month',current_date)::date end) as current_period_billable_events,
          (select coalesce(sum(be.amount_paise),0) from institution_billable_event be
            join institution_billing_contract bc on bc.id=be.contract_id
            where be.institution_id=? and be.event_type='CHARGE'
              and be.billing_period_start=case when bc.billing_cadence='ANNUAL'
                then date_trunc('year',current_date)::date else date_trunc('month',current_date)::date end) as current_period_amount_paise
        """,tenant.institutionId(),tenant.institutionId(),tenant.institutionId(),
        tenant.institutionId(),tenant.institutionId(),tenant.institutionId(),tenant.institutionId());
    row.put("institution_id",tenant.institutionId());
    row.put("institution_code",tenant.tenantCode());
    row.put("institution_name",tenant.displayName());
    staffAudit.recordAction(actor,"/api/v1/admin/institution-operations/overview",
        "Viewed institution operations summary for institutionId="+tenant.institutionId());
    return row;
  }

  @GetMapping("/roster")
  public Map<String,Object> roster(
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant,
      @RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="20") int pageSize,
      @RequestParam(defaultValue="") String query) {
    AuthContext actor=requireEducationStaff(supplied,tenant,"INSTITUTION_REPORTS_VIEW");
    int safePage=Math.max(1,Math.min(page,100000));
    int safeSize=Math.max(1,Math.min(pageSize,200));
    int offset=(safePage-1)*safeSize;
    String search=query==null?"":query.trim();
    List<Map<String,Object>> learners=jdbc.queryForList("""
        select st.public_user_id as learner_reference,st.display_name,st.class_code,st.board,
          st.language,st.city,st.state,st.school_name,m.status as membership_status,m.joined_at,
          count(distinct case when p.status='COMPLETED' then p.lesson_id end) as completed_lessons,
          count(distinct p.lesson_id) as lessons_with_activity,
          coalesce(round(avg(p.progress_percent)),0) as average_lesson_progress_percent
        from institution_membership m
        join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
        left join student_lesson_progress p on p.student_id=st.id and p.lesson_id in (
          select l.id from lesson l
          join curriculum_chapter ch on ch.id=l.chapter_id and ch.active=true
          join curriculum_subject sub on sub.id=ch.subject_id and sub.active=true
          join institution_course_assignment a on a.institution_id=m.institution_id and a.status='ASSIGNED'
          join institution_course_release r on r.id=a.course_release_id
            and r.program_id=sub.program_id and r.release_state='SHAREABLE'
          join curriculum_program pr on pr.id=r.program_id and pr.status='ACTIVE' and pr.code<>'GENERAL'
          where l.active=true and l.status='PUBLISHED'
        )
        where m.institution_id=?
          and (?='' or lower(st.display_name) like lower(?) or lower(st.class_code) like lower(?)
            or lower(coalesce(st.city,'')) like lower(?) or lower(coalesce(st.state,'')) like lower(?))
        group by st.id,m.id
        order by st.display_name,st.id
        limit ? offset ?
        """,tenant.institutionId(),search,"%"+search+"%","%"+search+"%","%"+search+"%","%"+search+"%",safeSize,offset);
    Integer total=jdbc.queryForObject("""
        select count(*) from institution_membership m
        join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
        where m.institution_id=? and (?='' or lower(st.display_name) like lower(?) or lower(st.class_code) like lower(?)
          or lower(coalesce(st.city,'')) like lower(?) or lower(coalesce(st.state,'')) like lower(?))
        """,Integer.class,tenant.institutionId(),search,"%"+search+"%","%"+search+"%","%"+search+"%","%"+search+"%");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("page",safePage);
    result.put("pageSize",safeSize);
    result.put("total",total==null?0:total);
    result.put("learners",learners);
    result.put("addressFieldsNote","Only city and state from the learner profile are available; exact home addresses are not collected in this profile.");
    staffAudit.recordAction(actor,"/api/v1/admin/institution-operations/roster",
        "Viewed institution learner roster for institutionId="+tenant.institutionId());
    return result;
  }

  @GetMapping("/courses")
  public List<Map<String,Object>> authorisedCourses(
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext actor=requireEducationStaff(supplied,tenant,"INSTITUTION_REPORTS_VIEW");
    List<Map<String,Object>> courses=jdbc.queryForList("""
        with ranked_assignments as (
          select a.institution_id,a.id as assignment_id,a.assigned_at,
            r.id as release_id,r.release_version,r.release_state,
            p.id as course_id,p.display_name,p.content_version_no,p.course_identity_id,
            coalesce(identity.stable_course_key,p.code) as code,
            identity.public_id as course_identity_public_id,
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
        select course_id,code,display_name,assigned_at as entitlement_start,
          null::timestamptz as entitlement_end,content_version_no,release_id,release_version,
          release_state,course_identity_public_id,
          (select count(distinct l.id) from curriculum_program_subject ps
            join curriculum_subject sub on sub.id=ps.subject_id and sub.class_id=ps.class_id and sub.active=true
            join curriculum_chapter ch on ch.subject_id=sub.id and ch.active=true
            join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
            where ps.program_id=ranked_assignments.course_id) as published_lessons,
          (select count(distinct m.student_id) from institution_membership m
            where m.institution_id=ranked_assignments.institution_id and m.status='ACTIVE') as institution_learners
        from ranked_assignments
        where identity_rank=1
        order by display_name,code
        """,tenant.institutionId());
    staffAudit.recordAction(actor,"/api/v1/admin/institution-operations/courses",
        "Viewed authorised institution courses for institutionId="+tenant.institutionId());
    return courses;
  }

  @GetMapping("/course-releases")
  public List<Map<String,Object>> sourceReleases(
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext actor=requireEducationStaff(supplied,tenant,"INSTITUTION_REPORTS_VIEW");
    staffAudit.recordAction(actor,"/api/v1/admin/institution-operations/course-releases",
        "Viewed source course releases for institutionId="+tenant.institutionId());
    return releaseWorkflow.listSourceReleases(tenant.institutionId());
  }

  @PostMapping("/courses/{programId}/release-requests")
  public Map<String,Object> submitRelease(
      @PathVariable long programId,
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext actor=requireEducationStaff(supplied,tenant,"INSTITUTION_RELEASE_MANAGE");
    Map<String,Object> result=releaseWorkflow.submitRelease(programId,tenant.institutionId(),actor.staffId(),null);
    staffAudit.recordAction(actor,"/api/v1/admin/institution-operations/courses/"+programId+"/release-requests",
        "Submitted institution source releaseId="+result.get("releaseId")+" for HRMS quality review.");
    return result;
  }

  @PostMapping("/course-releases/{releaseId}/approve")
  public Map<String,Object> approveRelease(
      @PathVariable long releaseId,
      @RequestAttribute(value="authContext",required=false) AuthContext supplied,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    AuthContext actor=requireEducationStaff(supplied,tenant,"INSTITUTION_RELEASE_MANAGE");
    Map<String,Object> result=releaseWorkflow.approveRelease(releaseId,tenant.institutionId(),actor.staffId(),null);
    staffAudit.recordAction(actor,"/api/v1/admin/institution-operations/course-releases/"+releaseId+"/approve",
        "Approved source release for sharing after HRMS quality review.");
    return result;
  }

  private AuthContext requireEducationStaff(AuthContext supplied,InstitutionTenantContext tenant,String permission) {
    AuthContext context=authorization.requirePermission(supplied,permission);
    if(!"EDUCATION_ADMIN".equals(AuthService.normalizeWorkspace(context.workspace()))||context.staffId()==null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This operation requires an Education Admin staff session.");
    }
    if(tenant==null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This staff account has no active HRMS institution binding.");
    }
    Boolean bound=jdbc.queryForObject("""
        select exists(
          select 1 from institution_staff_binding b
          join institution_tenant t on t.id=b.institution_id and t.status='ACTIVE'
          join staff_account s on s.id=b.staff_id and s.active=true
          where b.staff_id=? and b.institution_id=? and b.status='ACTIVE'
        )
        """,Boolean.class,context.staffId(),tenant.institutionId());
    if(!Boolean.TRUE.equals(bound)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This staff account is not actively bound to the current institution.");
    }
    return context;
  }
}
