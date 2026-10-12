package com.quantaedge.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String,Object>> SNAPSHOT_TYPE = new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final StaffAuditService audit;

  public HrEmployeeSelfServiceController(JdbcTemplate jdbc, StaffAuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
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
      @RequestParam(defaultValue="100") int limit) {
    long employeeId = linkedEmployeeId(context);
    LocalDate end = parseDate(to, LocalDate.now());
    LocalDate start = parseDate(from, end.withDayOfMonth(1));
    if (end.isBefore(start)) throw bad("The end date cannot be before the start date.");
    if (java.time.temporal.ChronoUnit.DAYS.between(start,end)>92) throw bad("Choose an attendance range of 93 days or fewer.");
    int pageSize=Math.max(1,Math.min(limit,200));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select work_date,attendance_status,check_in_at,check_out_at,
        case when check_in_at is not null and check_out_at is not null
          then floor(extract(epoch from (check_out_at-check_in_at))/60)::int else null end minutes_worked,
        notes
      from hr_attendance_record where employee_id=? and work_date between ? and ?
      order by work_date desc limit ?
      """,employeeId,Date.valueOf(start),Date.valueOf(end),pageSize);
    return Map.of("items",items,"from",start.toString(),"to",end.toString(),"limit",pageSize);
  }

  @GetMapping("/leave/requests")
  public Map<String,Object> leaveRequests(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(defaultValue="100") int limit) {
    long employeeId=linkedEmployeeId(context);
    int pageSize=Math.max(1,Math.min(limit,200));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select r.id,r.public_id,r.leave_type_code,t.display_name leave_type,t.paid,
        r.start_date,r.end_date,r.requested_days,r.reason,r.request_status,r.review_note,
        r.reviewed_at,r.created_at,r.updated_at
      from hr_leave_request r join hr_leave_type t on t.code=r.leave_type_code
      where r.employee_id=? order by r.created_at desc,r.id desc limit ?
      """,employeeId,pageSize);
    return Map.of("items",items,"limit",pageSize);
  }

  @GetMapping("/leave/types")
  public List<Map<String,Object>> leaveTypes(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    linkedEmployeeId(context);
    return jdbc.queryForList("select code,display_name,paid from hr_leave_type where active=true order by display_name");
  }

  @GetMapping("/payslips")
  public Map<String,Object> payslips(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    long employeeId=linkedEmployeeId(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,public_id,run_id,version_no,snapshot::text snapshot_json,checksum,issued_at
      from hr_payslip where employee_id=? order by issued_at desc,id desc
      """,employeeId);
    List<Map<String,Object>> items=new ArrayList<>();
    for(Map<String,Object> row:rows) {
      Map<String,Object> item=new LinkedHashMap<>(row);
      String snapshot=String.valueOf(item.remove("snapshot_json"));
      try { item.put("snapshot",JSON.readValue(snapshot,SNAPSHOT_TYPE)); }
      catch(Exception ex) { throw new IllegalStateException("An issued payslip snapshot could not be read.",ex); }
      items.add(item);
    }
    return Map.of("items",items);
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

  private long linkedEmployeeId(AuthContext context) {
    if(context==null||context.staffId()==null) throw new SecurityException("Authenticated employee access required.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id from hr_employee where linked_staff_account_id=?
        and employment_status not in ('SEPARATED','INACTIVE','DRAFT')
      """,context.staffId());
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "No active employee record is linked to this account. Contact People Operations.");
    return ((Number)rows.getFirst().get("id")).longValue();
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
