package com.quantaedge.api;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Employee-self endpoints are identity-scoped and never accept a caller-supplied employee ID. */
@RestController
@RequestMapping("/api/v1/hr/self-service")
public class HrEmployeeSelfServiceController {
  private final JdbcTemplate jdbc;
  private final StaffAuditService audit;
  private final AuthorizationService authorization;

  @Autowired
  public HrEmployeeSelfServiceController(JdbcTemplate jdbc, StaffAuditService audit,
      AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.audit = audit;
    this.authorization = authorization;
  }

  @GetMapping("/profile")
  public Map<String,Object> profile(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId = linkedEmployeeId(context);
    return jdbc.queryForMap("""
      select e.id,e.public_id,e.employee_number,e.display_name,e.email,e.phone_e164,
        e.joining_date,e.probation_end_date,e.employment_status,e.employment_type_code,
        et.display_name employment_type,e.department_id,d.name department_name,
        e.designation_id,g.name designation_name,e.manager_employee_id,m.display_name manager_name,
        o.name organization_name,o.timezone
      from hr_employee e
      join hr_organization o on o.id=e.organization_id
      join hr_employment_type et on et.code=e.employment_type_code
      left join hr_department d on d.id=e.department_id
      left join hr_designation g on g.id=e.designation_id
      left join hr_employee m on m.id=e.manager_employee_id
      where e.id=?
      """, employeeId);
  }

  @GetMapping("/attendance")
  public Map<String,Object> attendance(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(required=false) String from, @RequestParam(required=false) String to,
      @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
    long employeeId = linkedEmployeeId(context);
    LocalDate end = parseDate(to, LocalDate.now());
    LocalDate start = parseDate(from, end.withDayOfMonth(1));
    if (end.isBefore(start)) throw bad("The end date cannot be before the start date.");
    if (java.time.temporal.ChronoUnit.DAYS.between(start,end)>92) throw bad("Choose an attendance range of 93 days or fewer.");
    int pageSize=List.of(10,20,50).contains(limit)?limit:20;
    int pageOffset=Math.max(0,Math.min(offset,1_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select work_date,attendance_status,check_in_at,check_out_at,
        case when check_in_at is not null and check_out_at is not null
          then floor(extract(epoch from (check_out_at-check_in_at))/60)::int else null end minutes_worked,
        notes
      from hr_attendance_record where employee_id=? and work_date between ? and ?
      order by work_date desc limit ? offset ?
      """,employeeId,Date.valueOf(start),Date.valueOf(end),pageSize,pageOffset);
    Long total=jdbc.queryForObject("select count(*) from hr_attendance_record where employee_id=? and work_date between ? and ?",
      Long.class,employeeId,Date.valueOf(start),Date.valueOf(end));
    return Map.of("items",items,"total",total==null?0L:total,"from",start.toString(),"to",end.toString(),"limit",pageSize,"offset",pageOffset);
  }

  @GetMapping("/leave/requests")
  public Map<String,Object> leaveRequests(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
    long employeeId=linkedEmployeeId(context);
    int pageSize=List.of(10,20,50).contains(limit)?limit:20;
    int pageOffset=Math.max(0,Math.min(offset,1_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select r.id,r.public_id,r.leave_type_code,t.display_name leave_type,t.paid,
        r.start_date,r.end_date,r.requested_days,r.reason,r.request_status,r.review_note,
        r.reviewed_at,r.created_at,r.updated_at
      from hr_leave_request r join hr_leave_type t on t.code=r.leave_type_code
      where r.employee_id=? order by r.created_at desc,r.id desc limit ? offset ?
      """,employeeId,pageSize,pageOffset);
    Long total=jdbc.queryForObject("select count(*) from hr_leave_request where employee_id=?",Long.class,employeeId);
    return Map.of("items",items,"total",total==null?0L:total,"limit",pageSize,"offset",pageOffset);
  }

  @GetMapping("/leave/types")
  public List<Map<String,Object>> leaveTypes(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    linkedEmployeeId(context);
    return jdbc.queryForList("select code,display_name,paid from hr_leave_type where active=true order by display_name");
  }

  @GetMapping("/payslips")
  public Map<String,Object> payslips(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
    long employeeId=linkedEmployeeId(context);
    int pageSize=List.of(10,20,50).contains(limit)?limit:20;
    int pageOffset=Math.max(0,Math.min(offset,1_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select id,public_id,run_id,version_no,snapshot::text snapshot,checksum,issued_at
      from hr_payslip where employee_id=? order by issued_at desc,id desc limit ? offset ?
      """,employeeId,pageSize,pageOffset);
    Long total=jdbc.queryForObject("select count(*) from hr_payslip where employee_id=?",Long.class,employeeId);
    return Map.of("items",items,"total",total==null?0L:total,"limit",pageSize,"offset",pageOffset);
  }

  @GetMapping("/leave/balances")
  public Map<String,Object> leaveBalances(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(required=false) String asOf) {
    long employeeId=linkedEmployeeId(context);
    LocalDate date=parseDate(asOf,LocalDate.now());
    List<Map<String,Object>> items=jdbc.queryForList("""
      select t.code leave_type_code,t.display_name,t.paid,
        coalesce(sum(x.quantity_days) filter(where x.effective_date<=?),0)::numeric(9,2) balance_days
      from hr_leave_type t
      left join hr_leave_balance_transaction x on x.leave_type_code=t.code
        and x.employee_id=? and x.effective_date<=?
      where t.active=true group by t.code,t.display_name,t.paid order by t.display_name
      """,Date.valueOf(date),employeeId,Date.valueOf(date));
    return Map.of("employeeId",employeeId,"asOf",date.toString(),"items",items);
  }

  @PostMapping("/leave/requests")
  @Transactional
  public Map<String,Object> requestLeave(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId=linkedEmployeeId(context);
    Map<String,Object> employee=jdbc.queryForMap(
      "select id,organization_id,employee_number from hr_employee where id=?",employeeId);
    String type=required(body.get("leaveTypeCode"),32,"Leave type").toUpperCase();
    Boolean valid=jdbc.queryForObject("select exists(select 1 from hr_leave_type where code=? and active=true)",Boolean.class,type);
    if(!Boolean.TRUE.equals(valid)) throw bad("Choose an active leave type.");
    LocalDate start=parseDate(required(body.get("startDate"),10,"Start date"),null);
    LocalDate end=parseDate(required(body.get("endDate"),10,"End date"),null);
    if(end.isBefore(start)) throw bad("Leave end date cannot be before its start date.");
    if(java.time.temporal.ChronoUnit.DAYS.between(start,end)+1>366) throw bad("A leave request cannot exceed 366 calendar days.");
    Boolean overlap=jdbc.queryForObject("""
      select exists(select 1 from hr_leave_request
        where employee_id=? and request_status in ('PENDING','APPROVED')
          and start_date<=? and end_date>=?)
      """,Boolean.class,employeeId,Date.valueOf(end),Date.valueOf(start));
    if(Boolean.TRUE.equals(overlap)) throw new ResponseStatusException(HttpStatus.CONFLICT,
      "You already have a pending or approved leave request overlapping those dates.");
    long organizationId=((Number)employee.get("organization_id")).longValue();
    Integer workingDays=jdbc.queryForObject("select working_days_per_week from hr_organization where id=?",Integer.class,organizationId);
    if(workingDays==null) workingDays=5;
    BigDecimal days=jdbc.queryForObject("""
      select count(*)::numeric from generate_series(?::date,?::date,interval '1 day') as days(day)
      where extract(isodow from day)<=?
        and not exists(select 1 from hr_holiday_calendar h
          where h.organization_id=? and h.holiday_date=day and h.active=true)
      """,BigDecimal.class,Date.valueOf(start),Date.valueOf(end),workingDays,organizationId);
    if(days==null||days.signum()<=0) throw bad("The requested range contains no working days after applying the organisation schedule and holidays.");
    String reason=required(body.get("reason"),1000,"Reason");
    Long id;
    try {
      id=jdbc.queryForObject("""
        insert into hr_leave_request(employee_id,leave_type_code,start_date,end_date,requested_days,reason,created_by_staff_id)
        values (?,?,?,?,?,?,?) returning id
        """,Long.class,employeeId,type,Date.valueOf(start),Date.valueOf(end),days,reason,context.staffId());
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"The leave request conflicts with an existing record.");
    }
    audit.recordAction(context,"/api/v1/hr/self-service/leave/requests",
      "Employee "+employee.get("employee_number")+" submitted a leave request.");
    return jdbc.queryForMap("""
      select r.id,r.public_id,r.employee_id,r.leave_type_code,t.display_name leave_type,t.paid,
        r.start_date,r.end_date,r.requested_days,r.reason,r.request_status,r.created_at
      from hr_leave_request r join hr_leave_type t on t.code=r.leave_type_code where r.id=?
      """,id);
  }

  @GetMapping("/attendance/correction-requests")
  public Map<String,Object> attendanceCorrectionRequests(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
    long employeeId=linkedEmployeeId(context);
    int pageSize=List.of(10,20,50).contains(limit)?limit:20;
    int pageOffset=Math.max(0,Math.min(offset,1_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select r.id,r.public_id,r.attendance_record_id,r.work_date,r.requested_status,
        r.requested_check_in_at,r.requested_check_out_at,r.requested_notes,r.reason,
        r.request_status,r.review_note,r.reviewed_at,r.created_at,r.updated_at
      from hr_attendance_correction_request r where r.employee_id=?
      order by r.created_at desc,r.id desc limit ? offset ?
      """,employeeId,pageSize,pageOffset);
    Long total=jdbc.queryForObject("select count(*) from hr_attendance_correction_request where employee_id=?",Long.class,employeeId);
    return Map.of("items",items,"total",total==null?0L:total,"limit",pageSize,"offset",pageOffset);
  }

  @PostMapping("/attendance/correction-requests")
  @Transactional
  public Map<String,Object> requestAttendanceCorrection(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId=linkedEmployeeId(context);
    LocalDate workDate=parseDate(required(body.get("workDate"),10,"Work date"),null);
    List<Map<String,Object>> records=jdbc.queryForList("""
      select id,attendance_status,check_in_at,check_out_at,notes
      from hr_attendance_record where employee_id=? and work_date=? for update
      """,employeeId,Date.valueOf(workDate));
    if(records.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "No attendance record exists for that date. Contact People Operations if a record needs to be created.");
    String status=required(body.get("status"),16,"Requested attendance status").toUpperCase();
    if(!List.of("PRESENT","ABSENT","HALF_DAY","REMOTE","ON_LEAVE","HOLIDAY").contains(status))
      throw bad("Choose a supported attendance status.");
    java.time.OffsetDateTime in=parseTimestamp(body.get("checkInAt"),"Check-in time");
    java.time.OffsetDateTime out=parseTimestamp(body.get("checkOutAt"),"Check-out time");
    if(out!=null&&in==null) throw bad("A check-out time requires a check-in time.");
    if(in!=null&&out!=null&&!out.isAfter(in)) throw bad("Check-out must be later than check-in.");
    if(List.of("ABSENT","ON_LEAVE","HOLIDAY").contains(status)&&(in!=null||out!=null))
      throw bad("Absent, on-leave and holiday requests cannot include check-in/out times.");
    String reason=required(body.get("reason"),1000,"Reason");
    long recordId=((Number)records.getFirst().get("id")).longValue();
    Long id;
    try {
      id=jdbc.queryForObject("""
        insert into hr_attendance_correction_request(attendance_record_id,employee_id,work_date,
          requested_status,requested_check_in_at,requested_check_out_at,requested_notes,reason,requested_by_staff_id)
        values (?,?,?,?,?,?,?,?,?) returning id
        """,Long.class,recordId,employeeId,Date.valueOf(workDate),status,in,out,
          optionalText(body.get("notes"),500),reason,context.staffId());
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
        "A pending attendance correction already exists for this date.");
    }
    audit.recordAction(context,"/api/v1/hr/self-service/attendance/correction-requests",
      "Submitted attendance correction request "+id+" for "+workDate);
    return jdbc.queryForMap("""
      select id,public_id,attendance_record_id,employee_id,work_date,requested_status,
        requested_check_in_at,requested_check_out_at,requested_notes,reason,request_status,created_at
      from hr_attendance_correction_request where id=? and employee_id=?
      """,id,employeeId);
  }

  @PatchMapping("/attendance/correction-requests/{requestId}/cancel")
  @Transactional
  public Map<String,Object> cancelAttendanceCorrection(@PathVariable long requestId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId=linkedEmployeeId(context);
    int changed=jdbc.update("""
      update hr_attendance_correction_request set request_status='CANCELLED',updated_at=now()
      where id=? and employee_id=? and request_status='PENDING'
      """,requestId,employeeId);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,
      "The correction request was not found or is no longer pending.");
    audit.recordAction(context,"/api/v1/hr/self-service/attendance/correction-requests/"+requestId+"/cancel",
      "Cancelled attendance correction request "+requestId);
    return jdbc.queryForMap("select id,public_id,work_date,request_status,updated_at from hr_attendance_correction_request where id=?",requestId);
  }

  @GetMapping("/profile-change-requests")
  public Map<String,Object> profileChangeRequests(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
    long employeeId=linkedEmployeeId(context);
    int pageSize=List.of(10,20,50).contains(limit)?limit:20;
    int pageOffset=Math.max(0,Math.min(offset,1_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select id,public_id,requested_values::text requested_values,reason,request_status,
        review_note,reviewed_at,created_at,updated_at
      from hr_employee_change_request where employee_id=?
      order by created_at desc,id desc limit ? offset ?
      """,employeeId,pageSize,pageOffset);
    Long total=jdbc.queryForObject("select count(*) from hr_employee_change_request where employee_id=?",Long.class,employeeId);
    return Map.of("items",items,"total",total==null?0L:total,"limit",pageSize,"offset",pageOffset);
  }

  @PostMapping("/profile-change-requests")
  @Transactional
  public Map<String,Object> requestProfileChange(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId=linkedEmployeeId(context);
    Map<String,Object> current=jdbc.queryForMap("select email,phone_e164 from hr_employee where id=?",employeeId);
    boolean includeEmail=body.containsKey("email"),includePhone=body.containsKey("phoneE164");
    if(!includeEmail&&!includePhone) throw bad("Provide an email address, phone number, or both to change.");
    String email=null,phone=null;
    if(includeEmail) {
      email=required(body.get("email"),254,"Email");
      if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw bad("Enter a valid email address.");
      if(Objects.equals(current.get("email"),email)) includeEmail=false;
    }
    if(includePhone) {
      phone=required(body.get("phoneE164"),24,"Phone number");
      if(!phone.matches("^\\+[1-9]\\d{7,14}$")) throw bad("Phone number must use E.164 format, for example +919876543210.");
      if(Objects.equals(current.get("phone_e164"),phone)) includePhone=false;
    }
    if(!includeEmail&&!includePhone) throw bad("The requested contact details are already on your profile.");
    String reason=required(body.get("reason"),1000,"Reason");
    Long id;
    try {
      id=jdbc.queryForObject("""
        insert into hr_employee_change_request(employee_id,requested_by_staff_id,requested_values,reason)
        values (?,?,jsonb_strip_nulls(jsonb_build_object('email',?::varchar,'phoneE164',?::varchar)),?)
        returning id
        """,Long.class,employeeId,context.staffId(),includeEmail?email:null,includePhone?phone:null,reason);
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"You already have a pending profile change request. Wait for it to be reviewed before submitting another.");
    }
    audit.recordAction(context,"/api/v1/hr/self-service/profile-change-requests","Submitted employee contact-detail change request "+id);
    return jdbc.queryForMap("""
      select id,public_id,requested_values::text requested_values,reason,request_status,
        review_note,reviewed_at,created_at,updated_at
      from hr_employee_change_request where id=? and employee_id=?
      """,id,employeeId);
  }

  @PatchMapping("/profile-change-requests/{requestId}/cancel")
  @Transactional
  public Map<String,Object> cancelProfileChange(@PathVariable long requestId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId=linkedEmployeeId(context);
    List<Map<String,Object>> rows=jdbc.queryForList(
      "select id,request_status from hr_employee_change_request where id=? and employee_id=?",requestId,employeeId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Profile change request not found.");
    if(!"PENDING".equals(rows.getFirst().get("request_status")))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending profile change requests can be cancelled.");
    if(jdbc.update("update hr_employee_change_request set request_status='CANCELLED',updated_at=now() where id=? and employee_id=? and request_status='PENDING'",requestId,employeeId)!=1)
      throw new ResponseStatusException(HttpStatus.CONFLICT,"The profile change request was already reviewed.");
    audit.recordAction(context,"/api/v1/hr/self-service/profile-change-requests/"+requestId+"/cancel","Cancelled profile change request "+requestId);
    return jdbc.queryForMap("select id,public_id,requested_values::text requested_values,reason,request_status,review_note,reviewed_at,created_at,updated_at from hr_employee_change_request where id=?",requestId);
  }

  private long linkedEmployeeId(AuthContext context) {
    authorization.requirePermission(context, "HR_SELF_SERVICE_VIEW");
    if(context==null||context.staffId()==null) throw new SecurityException("Authenticated employee access required.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id from hr_employee where linked_staff_account_id=?
        and employment_status not in ('SEPARATED','INACTIVE','DRAFT')
      """,context.staffId());
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "No active employee record is linked to this account. Contact People Operations.");
    return ((Number)rows.getFirst().get("id")).longValue();
  }

  private static java.time.OffsetDateTime parseTimestamp(Object value,String label) {
    if(value==null||String.valueOf(value).isBlank()) return null;
    try { return java.time.OffsetDateTime.parse(String.valueOf(value)); }
    catch(java.time.format.DateTimeParseException ex) { throw bad(label+" must be an ISO-8601 timestamp with timezone."); }
  }
  private static String optionalText(Object value,int max) {
    if(value==null) return null;
    String text=String.valueOf(value).trim();
    if(text.isEmpty()) return null;
    if(text.length()>max) throw bad("Notes must be at most "+max+" characters.");
    return text;
  }
  private static LocalDate parseDate(String value,LocalDate fallback) {
    if(value==null||value.isBlank()) {
      if(fallback!=null) return fallback;
      throw bad("A date is required.");
    }
    try { return LocalDate.parse(value.trim()); }
    catch(DateTimeParseException ex) { throw bad("Dates must use YYYY-MM-DD format."); }
  }
  private static String required(Object value,int max,String label) {
    String text=value==null?"":String.valueOf(value).trim();
    if(text.isEmpty()||text.length()>max) throw bad(label+" is required and must be at most "+max+" characters.");
    return text;
  }
  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }
}
