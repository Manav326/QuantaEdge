package com.quantaedge.api;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr")
public class HrLeaveBalanceController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;
  private final HrReportingScopeService reportingScope;

  @Autowired
  public HrLeaveBalanceController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService audit,HrReportingScopeService reportingScope) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.audit = audit;
    this.reportingScope = reportingScope;
  }
  public HrLeaveBalanceController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService audit) {
    this(jdbc,authorization,audit,new HrReportingScopeService(jdbc,authorization));
  }

  @GetMapping("/leave/balances")
  public Map<String,Object> balances(@RequestParam long employeeId,
      @RequestParam(required=false) String asOf,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context, "HR_LEAVE_BALANCE_VIEW");
    if (employeeId <= 0) throw bad("Employee ID must be a positive integer.");
    LocalDate date = parseDate(asOf);
    reportingScope.requireEmployeeAccess(context,employeeId);
    ensureEmployee(employeeId);
    List<Map<String,Object>> items = jdbc.queryForList("""
      select t.code leave_type_code,t.display_name,t.paid,
        coalesce(sum(x.quantity_days) filter(where x.effective_date<=?),0)::numeric(9,2) balance_days
      from hr_leave_type t
      left join hr_leave_balance_transaction x on x.leave_type_code=t.code and x.employee_id=? and x.organization_id=(select organization_id from hr_employee where id=?) and x.effective_date<=?
      where t.active=true
      group by t.code,t.display_name,t.paid
      order by t.display_name
      """, Date.valueOf(date), employeeId, employeeId, Date.valueOf(date));
    return Map.of("employeeId",employeeId,"asOf",date.toString(),"items",items);
  }

  @GetMapping("/leave/balances/ledger")
  public Map<String,Object> ledger(@RequestParam long employeeId,
      @RequestParam(required=false) String leaveTypeCode,
      @RequestParam(defaultValue="0") int offset,
      @RequestParam(defaultValue="100") int limit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context, "HR_LEAVE_BALANCE_VIEW");
    if (employeeId <= 0) throw bad("Employee ID must be a positive integer.");
    reportingScope.requireEmployeeAccess(context,employeeId);
    ensureEmployee(employeeId);
    int pageSize=Math.max(1,Math.min(limit,200)), pageOffset=Math.max(0,Math.min(offset,1000000));
    String type=leaveTypeCode==null||leaveTypeCode.isBlank()?null:leaveTypeCode.trim().toUpperCase();
    List<Map<String,Object>> items=jdbc.queryForList("""
      select x.id,x.organization_id,x.employee_id,x.leave_type_code,t.display_name leave_type,
        x.transaction_type,x.quantity_days,x.effective_date,x.source_type,x.source_reference,
        x.reason,x.reverses_transaction_id,x.created_by_staff_id,x.created_at
      from hr_leave_balance_transaction x join hr_leave_type t on t.code=x.leave_type_code
      where x.employee_id=? and (?::varchar is null or x.leave_type_code=?)
      order by x.effective_date desc,x.id desc limit ? offset ?
      """,employeeId,type,type,pageSize,pageOffset);
    Long total=jdbc.queryForObject("select count(*) from hr_leave_balance_transaction where employee_id=? and (?::varchar is null or leave_type_code=?)",Long.class,employeeId,type,type);
    return Map.of("items",items,"total",total==null?0L:total,"limit",pageSize,"offset",pageOffset);
  }

  @PostMapping("/leave/balances/transactions")
  @Transactional
  public Map<String,Object> recordTransaction(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_LEAVE_BALANCE_MANAGE");
    long employeeId=positive(body.get("employeeId"),"Employee");
    String type=required(body.get("leaveTypeCode"),32,"Leave type").toUpperCase();
    String transactionType=required(body.get("transactionType"),16,"Transaction type").toUpperCase();
    if(!List.of("GRANT","ACCRUAL","ADJUSTMENT").contains(transactionType))throw bad("Only GRANT, ACCRUAL and ADJUSTMENT may be entered manually.");
    // Reject unsupported/manual consumption before any database lookup. Consumption must be
    // created only by the approved-leave workflow, never by a caller-supplied transaction.
    reportingScope.requireEmployeeAccess(context,employeeId);
    Map<String,Object> employee=ensureEmployee(employeeId);
    Boolean activeType=jdbc.queryForObject("select exists(select 1 from hr_leave_type where code=? and active=true)",Boolean.class,type);
    if(!Boolean.TRUE.equals(activeType))throw bad("Choose an active leave type.");
    BigDecimal quantity;
    try{quantity=new BigDecimal(String.valueOf(body.get("quantityDays")));}catch(RuntimeException ex){throw bad("Quantity must be a decimal number of days.");}
    if(quantity.scale()>2||quantity.signum()==0||quantity.abs().compareTo(new BigDecimal("999999.99"))>0)throw bad("Quantity must be non-zero, no more than two decimal places, and within the supported range.");
    if("GRANT".equals(transactionType)||"ACCRUAL".equals(transactionType)){
      if(quantity.signum()<0)throw bad(transactionType+" quantity must be positive.");
    }else if(body.get("reason")==null||String.valueOf(body.get("reason")).trim().isEmpty()){
      throw bad("An adjustment requires a reason.");
    }
    LocalDate effectiveDate=parseRequiredDate(body.get("effectiveDate"));
    if(quantity.signum()<0&&effectiveDate.isBefore(LocalDate.now()))
      throw bad("Negative balance adjustments must be effective today or in the future; use a linked reversal for a posted transaction.");
    String reason=required(body.get("reason"),1000,"Reason");
    String sourceType=required(body.getOrDefault("sourceType","MANUAL"),32,"Source type").toUpperCase();
    String sourceReference=optional(body.get("sourceReference"),160);
    long organizationId=((Number)employee.get("organization_id")).longValue();
    if(quantity.signum()<0){
      jdbc.query("select pg_advisory_xact_lock(hashtext(?))",rs->{if(rs.next())rs.getObject(1);},organizationId+":"+employeeId+":"+type);
      BigDecimal balance=jdbc.queryForObject("select coalesce(sum(quantity_days),0) from hr_leave_balance_transaction where organization_id=? and employee_id=? and leave_type_code=? and effective_date<=?",BigDecimal.class,organizationId,employeeId,type,Date.valueOf(effectiveDate));
      if(balance==null||balance.add(quantity).signum()<0)throw new ResponseStatusException(HttpStatus.CONFLICT,"This adjustment would make the leave balance negative.");
    }
    Long id;
    try{
      id=jdbc.queryForObject("""
        insert into hr_leave_balance_transaction(organization_id,employee_id,leave_type_code,transaction_type,quantity_days,effective_date,source_type,source_reference,reason,created_by_staff_id)
        values (?,?,?,?,?,?,?,?,?,?) returning id
        """,Long.class,organizationId,employeeId,type,transactionType,quantity,Date.valueOf(effectiveDate),sourceType,sourceReference,reason,context.staffId());
    }catch(org.springframework.dao.DuplicateKeyException ex){
      throw new ResponseStatusException(HttpStatus.CONFLICT,"A transaction already exists for this source reference.");
    }
    Map<String,Object> row=jdbc.queryForMap("select id,organization_id,employee_id,leave_type_code,transaction_type,quantity_days,effective_date,source_type,source_reference,reason,reverses_transaction_id,created_by_staff_id,created_at from hr_leave_balance_transaction where id=?",id);
    audit.recordAction(context,"/api/v1/hr/leave/balances/transactions/"+id,"Recorded "+transactionType+" leave balance transaction for employee "+employeeId+" ("+type+")");
    return row;
  }

  @PostMapping("/leave/balances/transactions/{transactionId}/reverse")
  @Transactional
  public Map<String,Object> reverseTransaction(@PathVariable long transactionId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_LEAVE_BALANCE_MANAGE");
    if(transactionId<=0)throw bad("Transaction ID must be positive.");
    Map<String,Object> original;
    try {
      original=jdbc.queryForMap("select id,organization_id,employee_id,leave_type_code,transaction_type,quantity_days,effective_date from hr_leave_balance_transaction where id=?",transactionId);
    } catch(org.springframework.dao.EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Leave ledger transaction not found.");
    }
    long employeeId=((Number)original.get("employee_id")).longValue();
    reportingScope.requireEmployeeAccess(context,employeeId);
    if("REVERSAL".equals(String.valueOf(original.get("transaction_type"))))throw new ResponseStatusException(HttpStatus.CONFLICT,"A reversal cannot itself be reversed; record an authorized adjustment with a reason.");
    long organizationId=((Number)original.get("organization_id")).longValue();
    String type=String.valueOf(original.get("leave_type_code"));
    jdbc.query("select pg_advisory_xact_lock(hashtext(?))",rs->{if(rs.next())rs.getObject(1);},organizationId+":"+employeeId+":"+type);
    Boolean alreadyReversed=jdbc.queryForObject("select exists(select 1 from hr_leave_balance_transaction where reverses_transaction_id=?)",Boolean.class,transactionId);
    if(Boolean.TRUE.equals(alreadyReversed))throw new ResponseStatusException(HttpStatus.CONFLICT,"This ledger transaction has already been reversed.");
    LocalDate effectiveDate=body.get("effectiveDate")==null?LocalDate.now():parseRequiredDate(body.get("effectiveDate"));
    if(effectiveDate.isBefore(LocalDate.now()))throw bad("A ledger reversal must be effective today or in the future; original ledger history is never rewritten.");
    String reason=required(body.get("reason"),1000,"Reversal reason");
    BigDecimal reversalQuantity=((BigDecimal)original.get("quantity_days")).negate();
    if(reversalQuantity.signum()<0) {
      BigDecimal balance=jdbc.queryForObject("select coalesce(sum(quantity_days),0) from hr_leave_balance_transaction where organization_id=? and employee_id=? and leave_type_code=? and effective_date<=?",BigDecimal.class,organizationId,employeeId,type,Date.valueOf(LocalDate.now()));
      if(balance==null||balance.add(reversalQuantity).signum()<0)throw new ResponseStatusException(HttpStatus.CONFLICT,"This reversal would make the current leave balance negative. Reverse dependent consumption first.");
    }
    Long id;
    try {
      id=jdbc.queryForObject("insert into hr_leave_balance_transaction(organization_id,employee_id,leave_type_code,transaction_type,quantity_days,effective_date,source_type,source_reference,reason,reverses_transaction_id,created_by_staff_id) values (?,?,?,?,?,?,?,?,?,?,?) returning id",Long.class,organizationId,employeeId,type,"REVERSAL",reversalQuantity,Date.valueOf(effectiveDate),"REVERSAL","TXN:"+transactionId,reason,transactionId,context.staffId());
    } catch(org.springframework.dao.DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"This ledger transaction has already been reversed.");
    }
    Map<String,Object> row=jdbc.queryForMap("select id,organization_id,employee_id,leave_type_code,transaction_type,quantity_days,effective_date,source_type,source_reference,reason,reverses_transaction_id,created_by_staff_id,created_at from hr_leave_balance_transaction where id=?",id);
    audit.recordAction(context,"/api/v1/hr/leave/balances/transactions/"+id+"/reverse","Reversed leave ledger transaction "+transactionId+" for employee "+employeeId+" ("+type+")");
    return row;
  }

  private Map<String,Object> ensureEmployee(long id){
    List<Map<String,Object>> rows=jdbc.queryForList("select id,organization_id,employment_status from hr_employee where id=? and employment_status not in ('SEPARATED','INACTIVE','DRAFT')",id);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active employee not found.");
    return rows.getFirst();
  }
  private LocalDate parseDate(String value){
    if(value==null||value.isBlank())return LocalDate.now();
    try{return LocalDate.parse(value);}catch(DateTimeParseException ex){throw bad("Date must use YYYY-MM-DD format.");}
  }
  private LocalDate parseRequiredDate(Object value){
    if(value==null||String.valueOf(value).isBlank())throw bad("Effective date is required.");
    return parseDate(String.valueOf(value));
  }
  private long positive(Object value,String label){
    try{long n=Long.parseLong(String.valueOf(value));if(n>0)return n;}catch(RuntimeException ignored){}
    throw bad(label+" ID must be a positive integer.");
  }
  private String required(Object value,int max,String label){
    if(!(value instanceof String s)||s.trim().isEmpty()||s.trim().length()>max)throw bad(label+" is required and must be no longer than "+max+" characters.");
    return s.trim();
  }
  private String optional(Object value,int max){
    if(value==null)return null;String s=String.valueOf(value).trim();if(s.isEmpty())return null;
    if(s.length()>max)throw bad("A text field exceeds its maximum length of "+max+" characters.");return s;
  }
  private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
