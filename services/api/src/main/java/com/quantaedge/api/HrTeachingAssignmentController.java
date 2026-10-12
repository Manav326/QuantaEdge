package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr")
public class HrTeachingAssignmentController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrTeachingAssignmentController(JdbcTemplate jdbc,AuthorizationService authorization,StaffAuditService audit) {
    this.jdbc=jdbc;this.authorization=authorization;this.audit=audit;
  }

  @GetMapping("/academic-periods")
  public List<Map<String,Object>> periods(@RequestParam long organizationId,
      @RequestParam(defaultValue="false") boolean includeInactive,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);ensureOrganization(organizationId);
    return jdbc.queryForList("""
      select id,organization_id,period_code,name,start_date,end_date,status,created_by_staff_id,created_at,updated_at
      from hr_academic_period where organization_id=? and (?=true or status not in ('CLOSED','CANCELLED'))
      order by start_date desc,id desc
      """,organizationId,includeInactive);
  }

  @PostMapping("/academic-periods")
  @Transactional
  public Map<String,Object> createPeriod(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long orgId=positive(body.get("organizationId"),"Organisation");ensureOrganization(orgId);
    String code=required(body.get("periodCode"),40,"Period code").toUpperCase();
    if(!code.matches("^[A-Z0-9_-]+$"))throw bad("Period code may contain only letters, numbers, underscore and hyphen.");
    String name=required(body.get("name"),120,"Period name");
    LocalDate start=date(body.get("startDate"),"Start date"),end=date(body.get("endDate"),"End date");
    if(end.isBefore(start))throw bad("Academic period end date cannot be before its start date.");
    Long id;
    try{id=jdbc.queryForObject("""
      insert into hr_academic_period(organization_id,period_code,name,start_date,end_date,created_by_staff_id)
      values (?,?,?,?,?,?) returning id
      """,Long.class,orgId,code,name,Date.valueOf(start),Date.valueOf(end),context.staffId());}
    catch(DuplicateKeyException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Academic period code or name already exists in this organisation.");}
    Map<String,Object> row=periodById(id);
    audit.recordAction(context,"/api/v1/hr/academic-periods/"+id,"Created academic period "+code);
    return row;
  }

  @GetMapping("/teaching-assignments")
  public Map<String,Object> assignments(@RequestParam long organizationId,
      @RequestParam(required=false) Long academicPeriodId,@RequestParam(defaultValue="ALL") String status,
      @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="100") int limit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);ensureOrganization(organizationId);
    String st=status==null?"ALL":status.trim().toUpperCase();
    if(!List.of("ALL","PENDING_APPROVAL","APPROVED","REJECTED","CANCELLED","ENDED").contains(st))throw bad("Choose a supported assignment status.");
    int lim=Math.max(1,Math.min(limit,200)),off=Math.max(0,Math.min(offset,1000000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select a.id,a.public_id,a.organization_id,a.employee_id,e.employee_number,e.display_name employee_name,
        a.subject_id,s.code subject_code,s.display_name subject_name,c.id class_id,c.code class_code,c.display_name class_name,
        a.academic_period_id,p.period_code,p.name period_name,a.effective_start,a.effective_end,a.status,
        a.replaces_assignment_id,a.assignment_note,a.decision_reason,a.created_by_staff_id,
        a.approved_by_staff_id,a.approved_at,a.created_at,a.updated_at
      from hr_teaching_assignment a
      join hr_employee e on e.id=a.employee_id
      join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      join hr_academic_period p on p.id=a.academic_period_id
      where a.organization_id=? and (?::bigint is null or a.academic_period_id=?)
        and (?='ALL' or a.status=?)
      order by case a.status when 'PENDING_APPROVAL' then 0 when 'APPROVED' then 1 else 2 end,
        a.effective_start desc,e.display_name
      limit ? offset ?
      """,organizationId,academicPeriodId,academicPeriodId,st,st,lim,off);
    Long total=jdbc.queryForObject("select count(*) from hr_teaching_assignment where organization_id=? and (?::bigint is null or academic_period_id=?) and (?='ALL' or status=?)",Long.class,organizationId,academicPeriodId,academicPeriodId,st,st);
    return Map.of("items",items,"total",total==null?0L:total,"limit",lim,"offset",off);
  }

  @PostMapping("/teaching-assignments")
  @Transactional
  public Map<String,Object> createAssignment(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long employeeId=positive(body.get("employeeId"),"Employee");
    Map<String,Object> employee=employee(employeeId);
    long orgId=((Number)employee.get("organization_id")).longValue();
    String employmentStatus=String.valueOf(employee.get("employment_status"));
    if(!List.of("ACTIVE","ON_LEAVE","NOTICE_PERIOD").contains(employmentStatus))
      throw bad("Teaching assignments require an active employment record.");
    long subjectId=positive(body.get("subjectId"),"Subject");
    Map<String,Object> subject=subject(subjectId);
    if(!Boolean.TRUE.equals(subject.get("active")))throw bad("Choose an active class subject.");
    long periodId=positive(body.get("academicPeriodId"),"Academic period");
    Map<String,Object> period=periodById(periodId);
    if(((Number)period.get("organization_id")).longValue()!=orgId)throw bad("The academic period must belong to the employee's organisation.");
    if(List.of("CLOSED","CANCELLED").contains(String.valueOf(period.get("status"))))throw bad("Choose an open academic period.");
    LocalDate start=date(body.get("effectiveStart"),"Effective start"),end=date(body.get("effectiveEnd"),"Effective end");
    LocalDate periodStart=((Date)period.get("start_date")).toLocalDate(),periodEnd=((Date)period.get("end_date")).toLocalDate();
    if(end.isBefore(start))throw bad("Assignment end date cannot be before its start date.");
    if(start.isBefore(periodStart)||end.isAfter(periodEnd))throw bad("Assignment dates must stay within the academic period.");
    Long replaces=optionalLong(body.get("replacesAssignmentId"));
    if(replaces!=null){
      Map<String,Object> prior=assignmentById(replaces);
      if(((Number)prior.get("organization_id")).longValue()!=orgId||((Number)prior.get("subject_id")).longValue()!=subjectId||((Number)prior.get("academic_period_id")).longValue()!=periodId||!"APPROVED".equals(prior.get("status")))
        throw bad("A replacement must reference an approved assignment for the same organisation, subject and academic period.");
      LocalDate priorStart=((Date)prior.get("effective_start")).toLocalDate(),priorEnd=((Date)prior.get("effective_end")).toLocalDate();
      if(!start.isAfter(priorStart)||start.isAfter(priorEnd))throw bad("A replacement must start after the current assignment starts and before it ends.");
    }
    String note=optional(body.get("assignmentNote"),1000);
    Long id=jdbc.queryForObject("""
      insert into hr_teaching_assignment(organization_id,employee_id,subject_id,academic_period_id,
        effective_start,effective_end,status,replaces_assignment_id,assignment_note,created_by_staff_id)
      values (?,?,?,?,?,?,'PENDING_APPROVAL',?,?,?) returning id
      """,Long.class,orgId,employeeId,subjectId,periodId,Date.valueOf(start),Date.valueOf(end),replaces,note,context.staffId());
    String after=snapshot(id);
    history(id,"ASSIGNMENT_PROPOSED",context,start,optional(body.get("reason"),1000),null,after);
    Map<String,Object> row=assignmentViewById(id);
    audit.recordAction(context,"/api/v1/hr/teaching-assignments/"+id,"Proposed teaching assignment for "+employee.get("employee_number"));
    return row;
  }

  @PostMapping("/teaching-assignments/{id}/decision")
  @Transactional
  public Map<String,Object> decide(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_ACADEMIC_ASSIGNMENT_APPROVE");
    Map<String,Object> assignment=assignmentById(id);
    if(!"PENDING_APPROVAL".equals(assignment.get("status")))throw conflict("Only pending teaching assignments can be reviewed.");
    if(context.staffId()!=null&&context.staffId().equals(assignment.get("created_by_staff_id")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The assignment creator cannot approve their own teaching assignment.");
    String decision=required(body.get("status"),16,"Decision").toUpperCase();
    if(!List.of("APPROVED","REJECTED").contains(decision))throw bad("Decision must be APPROVED or REJECTED.");
    String reason=required(body.get("reason"),1000,"Decision reason");
    long orgId=((Number)assignment.get("organization_id")).longValue(),subjectId=((Number)assignment.get("subject_id")).longValue(),periodId=((Number)assignment.get("academic_period_id")).longValue();
    if("APPROVED".equals(decision)){
      Map<String,Object> employee=employee(((Number)assignment.get("employee_id")).longValue());
      if(!List.of("ACTIVE","ON_LEAVE","NOTICE_PERIOD").contains(String.valueOf(employee.get("employment_status"))))
        throw conflict("The assigned employee is no longer eligible for teaching assignments.");
      Map<String,Object> period=periodById(periodId);
      if(List.of("CLOSED","CANCELLED").contains(String.valueOf(period.get("status"))))
        throw conflict("This academic period is closed or cancelled.");
    }
    // Serialize approvals for a subject so two reviewers cannot approve overlapping assignments concurrently.
    jdbc.queryForObject("select id from curriculum_subject where id=? for update",Long.class,subjectId);
    String before=snapshot(id);
    if("APPROVED".equals(decision)){
      Long replaces=assignment.get("replaces_assignment_id") instanceof Number n?n.longValue():null;
      LocalDate start=((Date)assignment.get("effective_start")).toLocalDate(),end=((Date)assignment.get("effective_end")).toLocalDate();
      if(replaces!=null){
        Map<String,Object> prior=assignmentById(replaces);
        if(!"APPROVED".equals(prior.get("status")))throw conflict("The assignment being replaced is no longer approved.");
        LocalDate priorStart=((Date)prior.get("effective_start")).toLocalDate();
        if(!start.isAfter(priorStart))throw bad("Replacement start must follow the prior assignment start.");
        String priorBefore=snapshot(replaces);
        LocalDate priorEnd=start.minusDays(1);
        if(priorEnd.isBefore(priorStart))throw bad("Replacement would create an invalid prior assignment period.");
        jdbc.update("update hr_teaching_assignment set effective_end=?,updated_at=now(),decision_reason=? where id=? and status='APPROVED'",
          Date.valueOf(priorEnd),"Replaced by assignment "+id+": "+reason,replaces);
        history(replaces,"ASSIGNMENT_REPLACED",context,priorEnd,reason,priorBefore,snapshot(replaces));
      }
      Boolean overlap=jdbc.queryForObject("""
        select exists(select 1 from hr_teaching_assignment a where a.organization_id=? and a.subject_id=?
          and a.academic_period_id=? and a.status='APPROVED' and a.id<>?
          and a.effective_start<=? and a.effective_end>=?)
        """,Boolean.class,orgId,subjectId,periodId,id,Date.valueOf(end),Date.valueOf(start));
      if(Boolean.TRUE.equals(overlap))throw conflict("Another approved teacher assignment overlaps this class, subject and period. Choose a replacement or non-overlapping dates.");
      jdbc.update("update hr_teaching_assignment set status='APPROVED',decision_reason=?,approved_by_staff_id=?,approved_at=now(),updated_at=now() where id=? and status='PENDING_APPROVAL'",reason,context.staffId(),id);
    }else{
      jdbc.update("update hr_teaching_assignment set status='REJECTED',decision_reason=?,approved_by_staff_id=?,approved_at=now(),updated_at=now() where id=? and status='PENDING_APPROVAL'",reason,context.staffId(),id);
    }
    history(id,"ASSIGNMENT_"+decision,context,((Date)assignment.get("effective_start")).toLocalDate(),reason,before,snapshot(id));
    Map<String,Object> result=assignmentViewById(id);
    audit.recordAction(context,"/api/v1/hr/teaching-assignments/"+id+"/decision",decision+" teaching assignment "+id);
    return result;
  }

  @GetMapping("/approved-teaching-assignments")
  public List<Map<String,Object>> approvedAssignments(@RequestParam long organizationId,
      @RequestParam(required=false) Long subjectId,@RequestParam(required=false) Long academicPeriodId,
      @RequestParam(required=false) String onDate,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"TEACHING_ASSIGNMENT_VIEW");ensureOrganization(organizationId);
    LocalDate date=onDate==null||onDate.isBlank()?LocalDate.now():date(onDate,"Date");
    return jdbc.queryForList("""
      select a.id,a.public_id,a.organization_id,a.employee_id,e.employee_number,e.display_name employee_name,
        e.linked_staff_account_id,a.subject_id,s.code subject_code,s.display_name subject_name,
        c.id class_id,c.code class_code,c.display_name class_name,a.academic_period_id,p.period_code,p.name period_name,
        a.effective_start,a.effective_end,a.status
      from hr_teaching_assignment a join hr_employee e on e.id=a.employee_id
      join curriculum_subject s on s.id=a.subject_id join curriculum_class c on c.id=s.class_id
      join hr_academic_period p on p.id=a.academic_period_id
      where a.organization_id=? and a.status='APPROVED' and a.effective_start<=? and a.effective_end>=?
        and (?::bigint is null or a.subject_id=?) and (?::bigint is null or a.academic_period_id=?)
      order by c.sort_order,s.sort_order,e.display_name
      """,organizationId,Date.valueOf(date),Date.valueOf(date),subjectId,subjectId,academicPeriodId,academicPeriodId);
  }

  @GetMapping("/teaching-assignments/{id}/history")
  public List<Map<String,Object>> history(@PathVariable long id,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);assignmentById(id);
    return jdbc.queryForList("select id,assignment_id,event_type,actor_staff_id,effective_date,reason,before_state,after_state,created_at from hr_teaching_assignment_history where assignment_id=? order by created_at desc,id desc",id);
  }

  private Map<String,Object> employee(long id){
    List<Map<String,Object>> r=jdbc.queryForList("select id,organization_id,employee_number,display_name,employment_status from hr_employee where id=?",id);
    if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found.");return r.getFirst();
  }
  private Map<String,Object> subject(long id){
    List<Map<String,Object>> r=jdbc.queryForList("select s.id,s.class_id,s.code,s.display_name,s.active,c.code class_code,c.display_name class_name from curriculum_subject s join curriculum_class c on c.id=s.class_id where s.id=?",id);
    if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class subject not found.");return r.getFirst();
  }
  private Map<String,Object> periodById(long id){
    List<Map<String,Object>> r=jdbc.queryForList("select * from hr_academic_period where id=?",id);
    if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Academic period not found.");return r.getFirst();
  }
  private Map<String,Object> assignmentById(long id){
    List<Map<String,Object>> r=jdbc.queryForList("select * from hr_teaching_assignment where id=?",id);
    if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Teaching assignment not found.");return r.getFirst();
  }
  private Map<String,Object> assignmentViewById(long id){
    List<Map<String,Object>> r=jdbc.queryForList("""
      select a.id,a.public_id,a.organization_id,a.employee_id,e.employee_number,e.display_name employee_name,
        a.subject_id,s.code subject_code,s.display_name subject_name,c.id class_id,c.code class_code,c.display_name class_name,
        a.academic_period_id,p.period_code,p.name period_name,a.effective_start,a.effective_end,a.status,
        a.replaces_assignment_id,a.assignment_note,a.decision_reason,a.created_by_staff_id,
        a.approved_by_staff_id,a.approved_at,a.created_at,a.updated_at
      from hr_teaching_assignment a join hr_employee e on e.id=a.employee_id
      join curriculum_subject s on s.id=a.subject_id join curriculum_class c on c.id=s.class_id
      join hr_academic_period p on p.id=a.academic_period_id where a.id=?
      """,id);
    if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Teaching assignment not found.");return r.getFirst();
  }
  private String snapshot(long id){
    return jdbc.queryForObject("select to_jsonb(a)::text from hr_teaching_assignment a where id=?",String.class,id);
  }
  private void history(long id,String type,AuthContext context,LocalDate effective,String reason,String before,String after){
    jdbc.update("insert into hr_teaching_assignment_history(assignment_id,event_type,actor_staff_id,effective_date,reason,before_state,after_state) values (?,?,?,?,?,?::jsonb,?::jsonb)",
      id,type,context.staffId(),Date.valueOf(effective),reason,before,after);
  }
  private void ensureOrganization(long id){
    if(id<=0||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,id)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active organisation not found.");
  }
  private void requireView(AuthContext c){authorization.requirePermission(c,"HR_ACADEMIC_ASSIGNMENT_VIEW");}
  private void requireManage(AuthContext c){authorization.requirePermission(c,"HR_ACADEMIC_ASSIGNMENT_MANAGE");}
  private long positive(Object v,String label){try{long n=Long.parseLong(String.valueOf(v));if(n>0)return n;}catch(RuntimeException ignored){}throw bad(label+" ID must be a positive integer.");}
  private Long optionalLong(Object v){if(v==null||String.valueOf(v).isBlank())return null;try{long n=Long.parseLong(String.valueOf(v));if(n>0)return n;}catch(RuntimeException ignored){}throw bad("A selected record ID is invalid.");}
  private String required(Object v,int max,String label){if(!(v instanceof String s)||s.trim().isEmpty()||s.trim().length()>max)throw bad(label+" is required and must be no longer than "+max+" characters.");return s.trim();}
  private String optional(Object v,int max){if(v==null)return null;String s=String.valueOf(v).trim();if(s.isEmpty())return null;if(s.length()>max)throw bad("A text field exceeds its maximum length of "+max+" characters.");return s;}
  private LocalDate date(Object v,String label){try{return LocalDate.parse(required(v,10,label));}catch(RuntimeException ex){throw bad(label+" must use YYYY-MM-DD format.");}}
  private void audit(AuthContext c,String path,String details){audit.recordAction(c,path,details);}
  private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
  private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
}
