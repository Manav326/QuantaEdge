package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
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

  @GetMapping("/academic-subjects")
  public List<Map<String,Object>> subjects(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    return jdbc.queryForList("""
      select s.id subject_id,s.code subject_code,s.display_name subject_name,
        c.id class_id,c.code class_code,c.display_name class_name,s.sort_order
      from curriculum_subject s join curriculum_class c on c.id=s.class_id
      where s.active=true and c.active=true
      order by c.sort_order,s.sort_order,s.display_name
      """);
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
    Boolean periodOverlap=jdbc.queryForObject("""
      select exists(select 1 from hr_academic_period where organization_id=? and status<>'CANCELLED'
        and start_date<=? and end_date>=?)
      """,Boolean.class,orgId,Date.valueOf(end),Date.valueOf(start));
    if(Boolean.TRUE.equals(periodOverlap))throw conflict("Academic periods for one organisation cannot overlap. Adjust the dates or close/cancel the prior period first.");
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
    String decision=required(body.get("status"),16,"Decision").toUpperCase();
    if(!List.of("APPROVED","REJECTED").contains(decision))throw bad("Decision must be APPROVED or REJECTED.");
    if("APPROVED".equals(decision)&&context.staffId()!=null&&context.staffId().equals(assignment.get("created_by_staff_id")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The assignment creator cannot approve their own teaching assignment.");
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
    // Re-read after the lock to prevent two reviewers from deciding the same pending record.
    assignment=assignmentById(id);
    if(!"PENDING_APPROVAL".equals(assignment.get("status")))throw conflict("This teaching assignment was already reviewed.");
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

  @PutMapping("/teaching-assignments/{id}")
  @Transactional
  public Map<String,Object> amendAssignment(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> current=assignmentById(id);
    if(!"PENDING_APPROVAL".equals(current.get("status"))) {
      throw conflict("Only pending proposals can be amended. For an approved assignment, submit a replacement so the existing record and history remain intact.");
    }
    long orgId=((Number)current.get("organization_id")).longValue();
    long employeeId=positive(body.getOrDefault("employeeId",current.get("employee_id")),"Employee");
    Map<String,Object> employee=employee(employeeId);
    if(((Number)employee.get("organization_id")).longValue()!=orgId)throw bad("The employee must belong to the same organisation.");
    if(!List.of("ACTIVE","ON_LEAVE","NOTICE_PERIOD").contains(String.valueOf(employee.get("employment_status"))))
      throw bad("Teaching assignments require an active employment record.");
    long subjectId=positive(body.getOrDefault("subjectId",current.get("subject_id")),"Subject");
    Map<String,Object> subject=subject(subjectId);
    if(!Boolean.TRUE.equals(subject.get("active")))throw bad("Choose an active class subject.");
    long periodId=positive(body.getOrDefault("academicPeriodId",current.get("academic_period_id")),"Academic period");
    Map<String,Object> period=periodById(periodId);
    if(((Number)period.get("organization_id")).longValue()!=orgId||List.of("CLOSED","CANCELLED").contains(String.valueOf(period.get("status"))))
      throw bad("Choose an open academic period in the employee's organisation.");
    LocalDate start=date(body.getOrDefault("effectiveStart",String.valueOf(current.get("effective_start"))),"Effective start");
    LocalDate end=date(body.getOrDefault("effectiveEnd",String.valueOf(current.get("effective_end"))),"Effective end");
    LocalDate periodStart=((Date)period.get("start_date")).toLocalDate(),periodEnd=((Date)period.get("end_date")).toLocalDate();
    if(end.isBefore(start)||start.isBefore(periodStart)||end.isAfter(periodEnd))throw bad("Assignment dates must be valid and stay within the academic period.");
    Long replaces=body.containsKey("replacesAssignmentId")?optionalLong(body.get("replacesAssignmentId")):
      (current.get("replaces_assignment_id") instanceof Number n?n.longValue():null);
    if(replaces!=null) {
      if(replaces==id)throw bad("An assignment cannot replace itself.");
      Map<String,Object> prior=assignmentById(replaces);
      if(!"APPROVED".equals(prior.get("status"))||((Number)prior.get("organization_id")).longValue()!=orgId
          ||((Number)prior.get("subject_id")).longValue()!=subjectId||((Number)prior.get("academic_period_id")).longValue()!=periodId)
        throw bad("A replacement must reference an approved assignment for the same organisation, subject and academic period.");
      LocalDate priorStart=((Date)prior.get("effective_start")).toLocalDate(),priorEnd=((Date)prior.get("effective_end")).toLocalDate();
      if(!start.isAfter(priorStart)||start.isAfter(priorEnd))throw bad("Replacement start must follow the prior assignment start and fall within its effective period.");
    }
    String note=body.containsKey("assignmentNote")?optional(body.get("assignmentNote"),1000):
      (current.get("assignment_note")==null?null:String.valueOf(current.get("assignment_note")));
    String reason=optional(body.get("reason"),1000);
    String before=snapshot(id);
    int changed=jdbc.update("""
      update hr_teaching_assignment set employee_id=?,subject_id=?,academic_period_id=?,effective_start=?,
        effective_end=?,replaces_assignment_id=?,assignment_note=?,updated_at=now()
      where id=? and status='PENDING_APPROVAL'
      """,employeeId,subjectId,periodId,Date.valueOf(start),Date.valueOf(end),replaces,note,id);
    if(changed!=1)throw conflict("The assignment changed while you were editing it. Refresh and try again.");
    history(id,"ASSIGNMENT_AMENDED",context,start,reason,before,snapshot(id));
    audit.recordAction(context,"/api/v1/hr/teaching-assignments/"+id,"Amended pending teaching assignment "+id);
    return assignmentViewById(id);
  }

  @PatchMapping("/teaching-assignments/{id}/status")
  @Transactional
  public Map<String,Object> setAssignmentStatus(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> assignment=assignmentById(id);
    String previous=String.valueOf(assignment.get("status"));
    String next=required(body.get("status"),20,"Status").toUpperCase();
    if(!"CANCELLED".equals(next))throw bad("This endpoint only supports cancellation. Approved replacements must use the approval workflow.");
    if(!List.of("PENDING_APPROVAL","APPROVED").contains(previous))throw conflict("Only pending or approved assignments can be cancelled.");
    String reason=required(body.get("reason"),1000,"Cancellation reason");
    String before=snapshot(id);
    int changed=jdbc.update("update hr_teaching_assignment set status='CANCELLED',decision_reason=?,updated_at=now() where id=? and status=?",reason,id,previous);
    if(changed!=1)throw conflict("The assignment changed while you were cancelling it. Refresh and try again.");
    history(id,"ASSIGNMENT_CANCELLED",context,LocalDate.now(ZoneId.of("Asia/Kolkata")),reason,before,snapshot(id));
    audit.recordAction(context,"/api/v1/hr/teaching-assignments/"+id+"/status","Cancelled teaching assignment "+id);
    return assignmentViewById(id);
  }

  @GetMapping("/teaching-workload-policy")
  public Map<String,Object> workloadPolicy(@RequestParam long organizationId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);ensureOrganization(organizationId);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select organization_id,weekly_minutes_limit,updated_by_staff_id,created_at,updated_at
      from hr_teaching_workload_policy where organization_id=?
      """,organizationId);
    Map<String,Object> result=new java.util.LinkedHashMap<>();
    result.put("organizationId",organizationId);
    result.put("configured",!rows.isEmpty());
    if(!rows.isEmpty())result.putAll(rows.getFirst());
    return result;
  }

  @PutMapping("/teaching-workload-policy")
  @Transactional
  public Map<String,Object> setWorkloadPolicy(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"CLASS_SCHEDULE_POLICY_MANAGE");
    long orgId=positive(body.get("organizationId"),"Organisation");ensureOrganization(orgId);
    int minutes;
    try{minutes=Integer.parseInt(String.valueOf(body.get("weeklyMinutesLimit")));}catch(RuntimeException ex){throw bad("Weekly workload limit must be a number of minutes.");}
    if(minutes<60||minutes>6000)throw bad("Weekly workload limit must be between 60 and 6000 minutes.");
    jdbc.update("""
      insert into hr_teaching_workload_policy(organization_id,weekly_minutes_limit,updated_by_staff_id,updated_at)
      values(?,?,?,now())
      on conflict(organization_id) do update set weekly_minutes_limit=excluded.weekly_minutes_limit,
        updated_by_staff_id=excluded.updated_by_staff_id,updated_at=now()
      """,orgId,minutes,context.staffId());
    audit.recordAction(context,"/api/v1/hr/teaching-workload-policy","Set weekly teaching workload limit for organisation "+orgId+" to "+minutes+" minutes.");
    return workloadPolicy(orgId,context);
  }

  @GetMapping("/academic-reporting")
  public Map<String,Object> academicReporting(@RequestParam long organizationId,
      @RequestParam(required=false) Long academicPeriodId,
      @RequestParam(required=false) String from,@RequestParam(required=false) String to,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);ensureOrganization(organizationId);
    LocalDate today=LocalDate.now(ZoneId.of("Asia/Kolkata"));
    LocalDate fromDate=from==null||from.isBlank()?today.with(DayOfWeek.MONDAY):date(from,"From date");
    LocalDate toDate=to==null||to.isBlank()?fromDate.plusDays(6):date(to,"To date");
    if(toDate.isBefore(fromDate)||toDate.isAfter(fromDate.plusDays(92)))throw bad("Report date range must be ordered and no longer than 93 days.");
    java.sql.Timestamp rangeStart=java.sql.Timestamp.from(fromDate.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());
    java.sql.Timestamp rangeEnd=java.sql.Timestamp.from(toDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());
    List<Map<String,Object>> assignments=jdbc.queryForList("""
      select a.id,a.employee_id,e.employee_number,e.display_name employee_name,a.subject_id,s.code subject_code,
        s.display_name subject_name,c.code class_code,c.display_name class_name,a.academic_period_id,p.name period_name,
        a.effective_start,a.effective_end,a.status,a.replaces_assignment_id
      from hr_teaching_assignment a join hr_employee e on e.id=a.employee_id
      join curriculum_subject s on s.id=a.subject_id join curriculum_class c on c.id=s.class_id
      join hr_academic_period p on p.id=a.academic_period_id
      where a.organization_id=? and (?::bigint is null or a.academic_period_id=?)
      order by c.sort_order,s.sort_order,e.display_name,a.effective_start
      """,organizationId,academicPeriodId,academicPeriodId);
    List<Map<String,Object>> coverage=jdbc.queryForList("""
      select c.code class_code,c.display_name class_name,s.id subject_id,s.code subject_code,s.display_name subject_name,
        count(distinct a.id) filter(where a.status='APPROVED' and a.effective_start<=? and a.effective_end>=?) approved_assignment_count,
        coalesce(string_agg(distinct e.display_name,', ' order by e.display_name)
          filter(where a.status='APPROVED' and a.effective_start<=? and a.effective_end>=?), '') assigned_teachers
      from curriculum_subject s join curriculum_class c on c.id=s.class_id
      left join hr_teaching_assignment a on a.subject_id=s.id and a.organization_id=?
        and (?::bigint is null or a.academic_period_id=?)
        and a.effective_start<=? and a.effective_end>=?
      left join hr_employee e on e.id=a.employee_id
      where c.active=true and s.active=true
      group by c.sort_order,c.code,c.display_name,s.id,s.code,s.display_name
      order by c.sort_order,s.sort_order,s.display_name
      """,Date.valueOf(toDate),Date.valueOf(fromDate),Date.valueOf(toDate),Date.valueOf(fromDate),
      organizationId,academicPeriodId,academicPeriodId,Date.valueOf(toDate),Date.valueOf(fromDate));
    List<Map<String,Object>> workload=jdbc.queryForList("""
      select e.id employee_id,e.employee_number,e.display_name employee_name,
        date_trunc('week',cs.starts_at at time zone 'Asia/Kolkata')::date week_start,
        count(cs.id) scheduled_class_count,
        coalesce(sum(greatest(0,extract(epoch from (cs.ends_at-cs.starts_at))/60)),0)::bigint scheduled_minutes,
        wp.weekly_minutes_limit,
        (wp.weekly_minutes_limit is not null and
          coalesce(sum(greatest(0,extract(epoch from (cs.ends_at-cs.starts_at))/60)),0)>wp.weekly_minutes_limit) over_limit
      from hr_employee e
      left join hr_teaching_assignment a on a.employee_id=e.id and a.organization_id=?
        and (?::bigint is null or a.academic_period_id=?)
      left join learning_class_session cs on cs.teaching_assignment_id=a.id
        and cs.status in ('SCHEDULED','LIVE','COMPLETED')
        and cs.starts_at<?::timestamptz and cs.ends_at>?::timestamptz
      left join hr_teaching_workload_policy wp on wp.organization_id=e.organization_id
      where e.organization_id=?
      group by e.id,e.employee_number,e.display_name,week_start,wp.weekly_minutes_limit
      having count(cs.id)>0
      order by week_start,e.display_name
      """,organizationId,academicPeriodId,academicPeriodId,rangeEnd,rangeStart,organizationId);
    List<Map<String,Object>> sessions=jdbc.queryForList("""
      select cs.status,count(*) class_count
      from learning_class_session cs join hr_teaching_assignment a on a.id=cs.teaching_assignment_id
      where a.organization_id=? and (?::bigint is null or a.academic_period_id=?)
        and cs.starts_at<?::timestamptz and cs.ends_at>?::timestamptz
      group by cs.status order by cs.status
      """,organizationId,academicPeriodId,academicPeriodId,rangeEnd,rangeStart);
    Long conflicts=jdbc.queryForObject("""
      select count(*) from learning_class_session a join learning_class_session b on a.id<b.id
      left join hr_teaching_assignment aa on aa.id=a.teaching_assignment_id
      left join hr_teaching_assignment ba on ba.id=b.teaching_assignment_id
      where a.status in ('SCHEDULED','LIVE') and b.status in ('SCHEDULED','LIVE')
        and a.starts_at<b.ends_at and b.starts_at<a.ends_at
        and ((a.host_staff_id is not null and a.host_staff_id=b.host_staff_id)
          or (aa.employee_id is not null and aa.employee_id=ba.employee_id))
        and a.starts_at<?::timestamptz and a.ends_at>?::timestamptz
        and (aa.organization_id=? or ba.organization_id=?)
      """,Long.class,rangeEnd,rangeStart,organizationId,organizationId);
    return Map.of("organizationId",organizationId,"academicPeriodId",academicPeriodId==null?0L:academicPeriodId,
      "from",fromDate.toString(),"to",toDate.toString(),"assignments",assignments,"teacherCoverage",coverage,
      "weeklyWorkload",workload,"classStatusCounts",sessions,"schedulingConflictCount",conflicts==null?0L:conflicts);
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
