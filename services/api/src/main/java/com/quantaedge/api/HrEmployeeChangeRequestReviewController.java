package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr")
public class HrEmployeeChangeRequestReviewController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrEmployeeChangeRequestReviewController(JdbcTemplate jdbc,AuthorizationService authorization,StaffAuditService audit) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.audit=audit;
  }

  @GetMapping("/employee-change-requests")
  public Map<String,Object> list(@RequestParam(defaultValue="PENDING") String status,
      @RequestParam(defaultValue="100") int limit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_EMPLOYEE_CHANGE_REVIEW");
    String state=status==null?"PENDING":status.trim().toUpperCase();
    if(!List.of("ALL","PENDING","APPROVED","REJECTED","CANCELLED").contains(state))
      throw bad("Choose a supported profile-change request status.");
    int pageSize=Math.max(1,Math.min(limit,200));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select r.id,r.public_id,r.employee_id,e.employee_number,e.display_name,
        e.email current_email,e.phone_e164 current_phone,r.requested_by_staff_id,
        r.requested_values::text requested_values,r.reason,r.request_status,r.review_note,
        r.reviewed_by_staff_id,r.reviewed_at,r.created_at,r.updated_at
      from hr_employee_change_request r join hr_employee e on e.id=r.employee_id
      where (?='ALL' or r.request_status=?)
      order by case r.request_status when 'PENDING' then 0 else 1 end,r.created_at desc,r.id desc
      limit ?
      """,state,state,pageSize);
    return Map.of("items",items,"limit",pageSize,"status",state);
  }

  @PatchMapping("/employee-change-requests/{requestId}/decision")
  @Transactional
  public Map<String,Object> decide(@PathVariable long requestId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_EMPLOYEE_CHANGE_REVIEW");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select r.id,r.employee_id,e.employee_number,e.email before_email,e.phone_e164 before_phone,
        r.requested_values->>'email' requested_email,(r.requested_values ? 'email') has_email,
        r.requested_values->>'phoneE164' requested_phone,(r.requested_values ? 'phoneE164') has_phone,
        r.request_status,r.reason
      from hr_employee_change_request r join hr_employee e on e.id=r.employee_id
      where r.id=? for update of r,e
      """,requestId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Profile change request not found.");
    Map<String,Object> request=rows.getFirst();
    if(!"PENDING".equals(request.get("request_status")))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending profile change requests can be reviewed.");
    String decision=required(body.get("status"),16,"Decision").toUpperCase();
    if(!List.of("APPROVED","REJECTED").contains(decision))
      throw bad("A profile change decision must be APPROVED or REJECTED.");
    String reviewNote=required(body.get("reviewNote"),1000,"Review note");
    long employeeId=((Number)request.get("employee_id")).longValue();

    if("APPROVED".equals(decision)) {
      boolean hasEmail=Boolean.TRUE.equals(request.get("has_email"));
      boolean hasPhone=Boolean.TRUE.equals(request.get("has_phone"));
      String email=(String)request.get("requested_email");
      String phone=(String)request.get("requested_phone");
      if(!hasEmail&&!hasPhone) throw new ResponseStatusException(HttpStatus.CONFLICT,"This request contains no supported contact fields.");
      if(hasEmail&&(email==null||!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"The requested email value is invalid; reject this request and ask the employee to resubmit.");
      if(hasPhone&&(phone==null||!phone.matches("^\\+[1-9]\\d{7,14}$")))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"The requested phone value is invalid; reject this request and ask the employee to resubmit.");
      if(hasEmail&&hasPhone) {
        jdbc.update("update hr_employee set email=?,phone_e164=?,updated_by_staff_id=?,updated_at=now() where id=?",email,phone,context.staffId(),employeeId);
      } else if(hasEmail) {
        jdbc.update("update hr_employee set email=?,updated_by_staff_id=?,updated_at=now() where id=?",email,context.staffId(),employeeId);
      } else {
        jdbc.update("update hr_employee set phone_e164=?,updated_by_staff_id=?,updated_at=now() where id=?",phone,context.staffId(),employeeId);
      }
      Map<String,Object> after=jdbc.queryForMap("select email,phone_e164 from hr_employee where id=?",employeeId);
      jdbc.update("""
        insert into hr_employee_history(employee_id,changed_by_staff_id,change_type,effective_date,before_state,after_state,reason)
        values(?,?,'SELF_SERVICE_CONTACT_UPDATE',current_date,
          jsonb_build_object('email',?::varchar,'phoneE164',?::varchar),
          jsonb_build_object('email',?::varchar,'phoneE164',?::varchar),?)
        """,employeeId,context.staffId(),request.get("before_email"),request.get("before_phone"),
          after.get("email"),after.get("phone_e164"),"Approved employee request #"+requestId+": "+request.get("reason"));
    }
    if(jdbc.update("""
      update hr_employee_change_request set request_status=?,review_note=?,reviewed_by_staff_id=?,
        reviewed_at=now(),updated_at=now() where id=? and request_status='PENDING'
      """,decision,reviewNote,context.staffId(),requestId)!=1)
      throw new ResponseStatusException(HttpStatus.CONFLICT,"The profile change request was already reviewed.");
    audit.recordAction(context,"/api/v1/hr/employee-change-requests/"+requestId+"/decision",
      decision+" employee contact change request for "+request.get("employee_number"));
    return jdbc.queryForMap("""
      select id,public_id,employee_id,requested_values::text requested_values,reason,request_status,
        review_note,reviewed_by_staff_id,reviewed_at,created_at,updated_at
      from hr_employee_change_request where id=?
      """,requestId);
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
