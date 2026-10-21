package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Approval path for employee-submitted attendance corrections. */
@RestController
@RequestMapping("/api/v1/hr/attendance/correction-requests")
public class HrAttendanceCorrectionReviewController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;
  private final HrReportingScopeService reportingScope;

  @Autowired
  public HrAttendanceCorrectionReviewController(JdbcTemplate jdbc,AuthorizationService authorization,StaffAuditService audit,HrReportingScopeService reportingScope) {
    this.jdbc=jdbc; this.authorization=authorization; this.audit=audit; this.reportingScope=reportingScope;
  }
  public HrAttendanceCorrectionReviewController(JdbcTemplate jdbc,AuthorizationService authorization,StaffAuditService audit) {
    this(jdbc,authorization,audit,new HrReportingScopeService(jdbc,authorization));
  }

  @GetMapping
  public Map<String,Object> list(@RequestParam(defaultValue="PENDING") String status,
      @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_ATTENDANCE_CORRECTION_REVIEW");
    String state=status==null?"PENDING":status.trim().toUpperCase();
    if(!List.of("ALL","PENDING","APPROVED","REJECTED","CANCELLED").contains(state))
      throw bad("Choose a supported correction-request status.");
    int pageSize=List.of(10,20,50).contains(limit)?limit:20;
    int safeOffset=Math.max(0,Math.min(offset,1_000_000));
    String scope=reportingScope.sqlScopePredicate(context,"e");
    Object[] filters={state,state};
    List<Map<String,Object>> items=jdbc.queryForList("""
      select r.id,r.public_id,r.attendance_record_id,r.employee_id,e.employee_number,e.display_name,
        r.work_date,r.requested_status,r.requested_check_in_at,r.requested_check_out_at,
        r.requested_notes,r.reason,r.request_status,r.review_note,r.requested_by_staff_id,
        r.reviewed_by_staff_id,r.reviewed_at,r.created_at,r.updated_at
      from hr_attendance_correction_request r join hr_employee e on e.id=r.employee_id
      where (?='ALL' or r.request_status=?) and """+scope+"""
      order by case r.request_status when 'PENDING' then 0 else 1 end,r.created_at,r.id
      limit ? offset ?
      """,reportingScope.withScopeArgs(context,filters,pageSize,safeOffset));
    Long total=jdbc.queryForObject("""
      select count(*) from hr_attendance_correction_request r join hr_employee e on e.id=r.employee_id
      where (?='ALL' or r.request_status=?) and """+scope,
      Long.class,reportingScope.withScopeArgs(context,filters));
    return Map.of("items",items,"status",state,"limit",pageSize,"offset",safeOffset,"total",total==null?0L:total);
  }

  @PatchMapping("/{requestId}/decision")
  @Transactional
  public Map<String,Object> decide(@PathVariable long requestId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_ATTENDANCE_CORRECTION_REVIEW");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select r.id,r.attendance_record_id,r.employee_id,r.work_date,r.requested_status,
        r.requested_check_in_at,r.requested_check_out_at,r.requested_notes,r.reason,
        r.request_status,r.requested_by_staff_id,a.attendance_status previous_status,
        a.check_in_at previous_check_in_at,a.check_out_at previous_check_out_at,a.notes previous_notes
      from hr_attendance_correction_request r
      join hr_attendance_record a on a.id=r.attendance_record_id
      where r.id=? for update of r,a
      """,requestId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Attendance correction request not found.");
    Map<String,Object> request=rows.getFirst();
    if(!"PENDING".equals(request.get("request_status")))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending correction requests can be reviewed.");
    String decision=required(body.get("status"),16,"Decision").toUpperCase();
    if(!List.of("APPROVED","REJECTED").contains(decision))
      throw bad("A correction decision must be APPROVED or REJECTED.");
    String reviewNote=required(body.get("reviewNote"),1000,"Review note");
    long employeeId=((Number)request.get("employee_id")).longValue();
    reportingScope.requireEmployeeAccess(context,employeeId);
    if(context.staffId()!=null&&Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from hr_employee where id=? and linked_staff_account_id=?)",
        Boolean.class,employeeId,context.staffId())))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Employees cannot review their own attendance correction.");

    if("APPROVED".equals(decision)) {
      LocalDate workDate=((Date)request.get("work_date")).toLocalDate();
      String status=String.valueOf(request.get("requested_status"));
      if("ON_LEAVE".equals(status)&&!Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from hr_leave_request where employee_id=? and request_status='APPROVED'
          and start_date<=? and end_date>=?)
        """,Boolean.class,employeeId,Date.valueOf(workDate),Date.valueOf(workDate))))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot approve ON_LEAVE without an approved leave request covering the date.");
      jdbc.update("""
        insert into hr_attendance_correction_history(attendance_record_id,employee_id,work_date,
          previous_status,previous_check_in_at,previous_check_out_at,previous_notes,
          corrected_status,corrected_check_in_at,corrected_check_out_at,corrected_notes,
          correction_reason,changed_by_staff_id)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,request.get("attendance_record_id"),employeeId,Date.valueOf(workDate),
          request.get("previous_status"),request.get("previous_check_in_at"),request.get("previous_check_out_at"),
          request.get("previous_notes"),request.get("requested_status"),request.get("requested_check_in_at"),
          request.get("requested_check_out_at"),request.get("requested_notes"),
          String.valueOf(request.get("reason")),context.staffId());
      int updated=jdbc.update("""
        update hr_attendance_record set attendance_status=?,check_in_at=?,check_out_at=?,notes=?,
          updated_by_staff_id=?,updated_at=now() where id=? and employee_id=? and work_date=?
        """,request.get("requested_status"),request.get("requested_check_in_at"),
          request.get("requested_check_out_at"),request.get("requested_notes"),context.staffId(),
          request.get("attendance_record_id"),employeeId,Date.valueOf(workDate));
      if(updated!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Attendance record changed; reload and review again.");
    }
    if(jdbc.update("""
      update hr_attendance_correction_request set request_status=?,review_note=?,reviewed_by_staff_id=?,
        reviewed_at=now(),updated_at=now() where id=? and request_status='PENDING'
      """,decision,reviewNote,context.staffId(),requestId)!=1)
      throw new ResponseStatusException(HttpStatus.CONFLICT,"The correction request was already reviewed.");
    audit.recordAction(context,"/api/v1/hr/attendance/correction-requests/"+requestId+"/decision",
      decision+" attendance correction request "+requestId+" for employee "+employeeId);
    return jdbc.queryForMap("""
      select id,public_id,attendance_record_id,employee_id,work_date,requested_status,request_status,
        review_note,reviewed_by_staff_id,reviewed_at,updated_at
      from hr_attendance_correction_request where id=?
      """,requestId);
  }

  private static String required(Object value,int max,String label) {
    String text=value==null?"":String.valueOf(value).trim();
    if(text.isEmpty()||text.length()>max) throw bad(label+" is required and must be at most "+max+" characters.");
    return text;
  }
  private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
