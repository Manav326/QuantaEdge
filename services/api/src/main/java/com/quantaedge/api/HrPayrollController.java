package com.quantaedge.api;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr/payroll")
public class HrPayrollController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrPayrollController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService audit) {
    this.jdbc=jdbc; this.authorization=authorization; this.audit=audit;
  }

  @GetMapping("/components")
  public List<Map<String,Object>> components(@RequestParam long organizationId,
      @RequestParam(defaultValue="false") boolean includeInactive,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context); ensureOrganization(organizationId);
    return jdbc.queryForList("""
      select id,organization_id,code,name,component_type,active,created_at,updated_at
      from hr_salary_component where organization_id=? and (?=true or active=true)
      order by active desc,component_type,name
      """,organizationId,includeInactive);
  }

  @PostMapping("/components")
  @Transactional
  public Map<String,Object> createComponent(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long orgId=positive(body.get("organizationId"),"Organisation"); ensureOrganization(orgId);
    String code=required(body.get("code"),32,"Component code").toUpperCase();
    if(!code.matches("^[A-Z0-9_-]+$")) throw bad("Component code may contain only letters, numbers, underscore and hyphen.");
    String name=required(body.get("name"),120,"Component name");
    String type=required(body.get("componentType"),16,"Component type").toUpperCase();
    if(!List.of("EARNING","DEDUCTION").contains(type)) throw bad("Component type must be EARNING or DEDUCTION.");
    try {
      Long id=jdbc.queryForObject("insert into hr_salary_component(organization_id,code,name,component_type,created_by_staff_id) values (?,?,?,?,?) returning id",Long.class,orgId,code,name,type,context.staffId());
      Map<String,Object> row=componentById(id);
      event(orgId,null,null,"COMPONENT_CREATED",context,optional(body.get("reason"),1000),"{\"componentId\":"+id+",\"code\":\""+jsonEscape(code)+"\"}");
      audit.recordAction(context,"/api/v1/hr/payroll/components/"+id,"Created payroll component "+code);
      return row;
    } catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Component code or name already exists in this organisation."); }
  }

  @GetMapping("/compensation")
  public Map<String,Object> compensation(@RequestParam long employeeId,@RequestParam(required=false) String asOf,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context); employee(employeeId);
    LocalDate date=parseOptionalDate(asOf,LocalDate.now());
    List<Map<String,Object>> items=jdbc.queryForList("""
      select x.id,x.organization_id,x.employee_id,x.component_id,c.code component_code,c.name component_name,
        c.component_type,x.monthly_amount,x.currency,x.effective_from,x.effective_to,x.reason,
        x.created_by_staff_id,x.created_at
      from hr_employee_salary_component x join hr_salary_component c on c.id=x.component_id
      where x.employee_id=? and x.effective_from<=? and (x.effective_to is null or x.effective_to>=?)
      order by x.effective_from desc,c.component_type,c.name,x.id desc
      """,employeeId,Date.valueOf(date),Date.valueOf(date));
    return Map.of("employeeId",employeeId,"asOf",date.toString(),"items",items);
  }

  @PostMapping("/compensation")
  @Transactional
  public Map<String,Object> addCompensation(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long employeeId=positive(body.get("employeeId"),"Employee");
    Map<String,Object> employee=employee(employeeId);
    // Serialize effective-date overlap checks for this employee so concurrent assignments cannot overlap.
    jdbc.queryForObject("select id from hr_employee where id=? for update",Long.class,employeeId);
    long orgId=((Number)employee.get("organization_id")).longValue();
    long componentId=positive(body.get("componentId"),"Salary component");
    Map<String,Object> component=componentById(componentId);
    if(((Number)component.get("organization_id")).longValue()!=orgId) throw bad("The salary component must belong to the employee's organisation.");
    if(!Boolean.TRUE.equals(component.get("active"))) throw bad("Choose an active salary component.");
    BigDecimal amount=decimal(body.get("monthlyAmount"),"Monthly amount");
    if(amount.signum()<=0||amount.scale()>2||amount.compareTo(new BigDecimal("9999999999.99"))>0)
      throw bad("Monthly amount must be positive, have at most two decimal places and be within the supported range.");
    String currency=required(body.get("currency"),3,"Currency").toUpperCase();
    if(!currency.matches("^[A-Z]{3}$")) throw bad("Currency must be a three-letter ISO currency code.");
    LocalDate from=parseRequiredDate(body.get("effectiveFrom"),"Effective from");
    LocalDate to=parseOptionalDate(body.get("effectiveTo"),"Effective to");
    if(to!=null&&to.isBefore(from)) throw bad("Effective-to date cannot be before effective-from date.");
    String reason=required(body.get("reason"),1000,"Reason");
    Boolean overlap=jdbc.queryForObject("""
      select exists(select 1 from hr_employee_salary_component
       where employee_id=? and component_id=? and effective_from<=coalesce(?::date,'infinity'::date)
         and coalesce(effective_to,'infinity'::date)>=?)
      """,Boolean.class,employeeId,componentId,to==null?null:Date.valueOf(to),Date.valueOf(from));
    if(Boolean.TRUE.equals(overlap)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This employee already has an overlapping effective period for this component.");
    Boolean mixedCurrency=jdbc.queryForObject("""
      select exists(select 1 from hr_employee_salary_component
       where employee_id=? and currency<>? and effective_from<=coalesce(?::date,'infinity'::date)
         and coalesce(effective_to,'infinity'::date)>=?)
      """,Boolean.class,employeeId,currency,to==null?null:Date.valueOf(to),Date.valueOf(from));
    if(Boolean.TRUE.equals(mixedCurrency)) throw bad("Overlapping compensation components for one employee must use the same currency.");
    Long id=jdbc.queryForObject("""
      insert into hr_employee_salary_component(organization_id,employee_id,component_id,monthly_amount,currency,effective_from,effective_to,reason,created_by_staff_id)
      values (?,?,?,?,?,?,?,?,?) returning id
      """,Long.class,orgId,employeeId,componentId,amount,currency,Date.valueOf(from),to==null?null:Date.valueOf(to),reason,context.staffId());
    Map<String,Object> row=jdbc.queryForMap("""
      select x.id,x.organization_id,x.employee_id,x.component_id,c.code component_code,c.name component_name,
        c.component_type,x.monthly_amount,x.currency,x.effective_from,x.effective_to,x.reason,x.created_by_staff_id,x.created_at
      from hr_employee_salary_component x join hr_salary_component c on c.id=x.component_id where x.id=?
      """,id);
    event(orgId,null,null,"COMPENSATION_ASSIGNED",context,reason,"{\"employeeId\":"+employeeId+",\"compensationId\":"+id+",\"componentId\":"+componentId+"}");
    audit.recordAction(context,"/api/v1/hr/payroll/compensation/"+id,"Configured effective-dated compensation for employee "+employee.get("employee_number"));
    return row;
  }

  @GetMapping("/periods")
  public List<Map<String,Object>> periods(@RequestParam long organizationId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context); ensureOrganization(organizationId);
    return jdbc.queryForList("""
      select p.id,p.organization_id,p.period_name,p.start_date,p.end_date,p.status,p.created_at,p.updated_at,
        r.id latest_run_id,r.version_no latest_run_version,r.status latest_run_status,
        r.total_gross,r.total_deductions,r.total_net,r.currency
      from hr_payroll_period p
      left join lateral (select * from hr_payroll_run x where x.period_id=p.id order by x.version_no desc limit 1) r on true
      where p.organization_id=? order by p.start_date desc,p.id desc
      """,organizationId);
  }

  @PostMapping("/periods")
  @Transactional
  public Map<String,Object> createPeriod(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long orgId=positive(body.get("organizationId"),"Organisation"); ensureOrganization(orgId);
    String name=required(body.get("periodName"),100,"Period name");
    LocalDate start=parseRequiredDate(body.get("startDate"),"Start date"),end=parseRequiredDate(body.get("endDate"),"End date");
    YearMonth month=YearMonth.from(start);
    if(!start.equals(month.atDay(1))||!end.equals(month.atEndOfMonth()))
      throw bad("Payroll periods must cover one complete calendar month. Proration and statutory calculations are not inferred.");
    try {
      Long id=jdbc.queryForObject("insert into hr_payroll_period(organization_id,period_name,start_date,end_date,created_by_staff_id) values (?,?,?,?,?) returning id",Long.class,orgId,name,Date.valueOf(start),Date.valueOf(end),context.staffId());
      Map<String,Object> row=periodById(id);
      event(orgId,id,null,"PERIOD_CREATED",context,optional(body.get("reason"),1000),"{\"startDate\":\""+start+"\",\"endDate\":\""+end+"\"}");
      audit.recordAction(context,"/api/v1/hr/payroll/periods/"+id,"Created payroll period "+name);
      return row;
    } catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"A payroll period with this name or date range already exists."); }
  }

  @PostMapping("/periods/{periodId}/calculate")
  @Transactional
  public Map<String,Object> calculate(@PathVariable long periodId,@RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    List<Map<String,Object>> periods=jdbc.queryForList("select * from hr_payroll_period where id=? for update",periodId);
    if(periods.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payroll period not found.");
    Map<String,Object> period=periods.getFirst();
    if(!List.of("OPEN","CALCULATED").contains(String.valueOf(period.get("status"))))
      throw conflict("Only open or calculated periods can be recalculated.");
    long orgId=((Number)period.get("organization_id")).longValue();
    LocalDate start=((Date)period.get("start_date")).toLocalDate(),end=((Date)period.get("end_date")).toLocalDate();
    Boolean partialEmployment=jdbc.queryForObject("""
      select exists(select 1 from hr_employee e where e.organization_id=? and e.employment_status='ACTIVE'
        and e.joining_date<=? and (e.exit_date is null or e.exit_date>=?)
        and (e.joining_date>? or (e.exit_date is not null and e.exit_date<?)))
      """,Boolean.class,orgId,Date.valueOf(end),Date.valueOf(start),Date.valueOf(start),Date.valueOf(end));
    if(Boolean.TRUE.equals(partialEmployment))
      throw conflict("An employee joins or exits inside this payroll month. Configure a reviewed proration policy before calculating payroll.");
    Boolean partialCompensation=jdbc.queryForObject("""
      select exists(select 1 from hr_employee_salary_component x join hr_employee e on e.id=x.employee_id
        where x.organization_id=? and e.employment_status='ACTIVE' and e.joining_date<=?
          and (e.exit_date is null or e.exit_date>=?) and x.effective_from<=?
          and (x.effective_to is null or x.effective_to>=?)
          and (x.effective_from>? or (x.effective_to is not null and x.effective_to<?)))
      """,Boolean.class,orgId,Date.valueOf(end),Date.valueOf(start),Date.valueOf(end),Date.valueOf(start),Date.valueOf(start),Date.valueOf(end));
    if(Boolean.TRUE.equals(partialCompensation))
      throw conflict("A compensation component changes inside this payroll month. Configure a reviewed proration policy before calculating payroll.");
    List<Map<String,Object>> missing=jdbc.queryForList("""
      select e.id,e.employee_number from hr_employee e
      where e.organization_id=? and e.employment_status='ACTIVE' and e.joining_date<=?
        and (e.exit_date is null or e.exit_date>=?)
        and not exists(select 1 from hr_employee_salary_component x where x.employee_id=e.id
          and x.organization_id=e.organization_id and x.effective_from<=?
          and (x.effective_to is null or x.effective_to>=?))
      order by e.display_name
      """,orgId,Date.valueOf(end),Date.valueOf(start),Date.valueOf(end),Date.valueOf(start));
    if(!missing.isEmpty()) {
      String names=missing.stream().limit(5).map(e->String.valueOf(e.get("employee_number"))).reduce((a,b)->a+", "+b).orElse("");
      throw conflict("Configure effective-dated compensation before calculating payroll. Missing: "+names+(missing.size()>5?" and "+(missing.size()-5)+" more":"")+".");
    }
    List<Map<String,Object>> lines=jdbc.queryForList("""
      select e.id employee_id,e.employee_number,e.display_name,x.currency,
        coalesce(sum(x.monthly_amount) filter(where c.component_type='EARNING'),0)::numeric(12,2) gross_amount,
        coalesce(sum(x.monthly_amount) filter(where c.component_type='DEDUCTION'),0)::numeric(12,2) deduction_amount,
        jsonb_agg(jsonb_build_object('componentCode',c.code,'componentName',c.name,
          'componentType',c.component_type,'monthlyAmount',x.monthly_amount,'currency',x.currency,
          'effectiveFrom',x.effective_from,'effectiveTo',x.effective_to) order by c.component_type,c.code) component_snapshot
      from hr_employee e
      join hr_employee_salary_component x on x.employee_id=e.id and x.organization_id=e.organization_id
        and x.effective_from<=? and (x.effective_to is null or x.effective_to>=?)
      join hr_salary_component c on c.id=x.component_id and c.organization_id=e.organization_id
      where e.organization_id=? and e.employment_status='ACTIVE' and e.joining_date<=?
        and (e.exit_date is null or e.exit_date>=?)
      group by e.id,e.employee_number,e.display_name,x.currency order by e.employee_number,e.id
      """,Date.valueOf(end),Date.valueOf(start),orgId,Date.valueOf(end),Date.valueOf(start));
    if(lines.isEmpty()) throw conflict("No active employees with effective compensation are eligible for this payroll period.");
    String currency=String.valueOf(lines.getFirst().get("currency"));
    if(lines.stream().anyMatch(line->!currency.equals(String.valueOf(line.get("currency"))))) throw bad("One payroll run cannot mix currencies. Separate the payroll by currency.");
    if(lines.stream().anyMatch(line->((BigDecimal)line.get("gross_amount")).compareTo((BigDecimal)line.get("deduction_amount"))<0))
      throw conflict("A deduction total exceeds gross earnings for at least one employee.");
    Integer version=jdbc.queryForObject("select coalesce(max(version_no),0)+1 from hr_payroll_run where period_id=?",Integer.class,periodId);
    jdbc.update("update hr_payroll_run set status='VOID' where period_id=? and status='CALCULATED'",periodId);
    Long runId=jdbc.queryForObject("insert into hr_payroll_run(period_id,version_no,status,currency,total_gross,total_deductions,total_net,calculation_policy_version,created_by_staff_id) values (?,?, 'CALCULATED', ?,0,0,0,'FIXED_MONTHLY_COMPONENTS_V1',?) returning id",Long.class,periodId,version,currency,context.staffId());
    BigDecimal grossTotal=BigDecimal.ZERO,deductionTotal=BigDecimal.ZERO,netTotal=BigDecimal.ZERO;
    for(Map<String,Object> line:lines) {
      BigDecimal gross=(BigDecimal)line.get("gross_amount"),deductions=(BigDecimal)line.get("deduction_amount"),net=gross.subtract(deductions);
      if(net.signum()<0) throw conflict("Payroll cannot be calculated with negative net pay.");
      grossTotal=grossTotal.add(gross);deductionTotal=deductionTotal.add(deductions);netTotal=netTotal.add(net);
      jdbc.update("insert into hr_payroll_line(run_id,employee_id,employee_number_snapshot,employee_name_snapshot,component_snapshot,gross_amount,deduction_amount,net_amount,currency) values (?,?,?,?,?::jsonb,?,?,?,?)",
        runId,line.get("employee_id"),line.get("employee_number"),line.get("display_name"),String.valueOf(line.get("component_snapshot")),gross,deductions,net,currency);
    }
    jdbc.update("update hr_payroll_run set total_gross=?,total_deductions=?,total_net=? where id=?",grossTotal,deductionTotal,netTotal,runId);
    jdbc.update("update hr_payroll_period set status='CALCULATED',updated_at=now() where id=?",periodId);
    event(orgId,periodId,runId,"RUN_CALCULATED",context,body==null?null:optional(body.get("reason"),1000),
      "{\"lineCount\":"+lines.size()+",\"gross\":\""+grossTotal+"\",\"deductions\":\""+deductionTotal+"\",\"net\":\""+netTotal+"\"}");
    audit.recordAction(context,"/api/v1/hr/payroll/runs/"+runId,"Calculated payroll run version "+version+" for period "+period.get("period_name"));
    return runById(runId);
  }

  @PostMapping("/runs/{runId}/submit")
  @Transactional
  public Map<String,Object> submit(@PathVariable long runId,@RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> run=runById(runId);
    if(!"CALCULATED".equals(run.get("status"))) throw conflict("Only a calculated payroll run can be submitted.");
    if(jdbc.update("update hr_payroll_run set status='SUBMITTED',submitted_by_staff_id=?,submitted_at=now() where id=? and status='CALCULATED'",context.staffId(),runId)!=1)
      throw conflict("Payroll run state changed; refresh and retry.");
    long periodId=((Number)run.get("period_id")).longValue(),orgId=((Number)run.get("organization_id")).longValue();
    jdbc.update("update hr_payroll_period set status='SUBMITTED',updated_at=now() where id=? and status='CALCULATED'",periodId);
    event(orgId,periodId,runId,"RUN_SUBMITTED",context,body==null?null:optional(body.get("reason"),1000),"{}");
    audit.recordAction(context,"/api/v1/hr/payroll/runs/"+runId,"Submitted payroll run for approval");
    return runById(runId);
  }

  @PostMapping("/runs/{runId}/approve")
  @Transactional
  public Map<String,Object> approve(@PathVariable long runId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_PAYROLL_APPROVE");
    Map<String,Object> run=runById(runId);
    if(!"SUBMITTED".equals(run.get("status"))) throw conflict("Only a submitted payroll run can be approved.");
    if(context.staffId()!=null&&context.staffId().equals(run.get("created_by_staff_id")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The payroll run creator cannot approve their own run.");
    String reason=required(body.get("reason"),1000,"Approval reason");
    if(jdbc.update("update hr_payroll_run set status='APPROVED',approved_by_staff_id=?,approved_at=now() where id=? and status='SUBMITTED'",context.staffId(),runId)!=1)
      throw conflict("Payroll run state changed; refresh and retry.");
    long periodId=((Number)run.get("period_id")).longValue(),orgId=((Number)run.get("organization_id")).longValue();
    jdbc.update("update hr_payroll_period set status='APPROVED',updated_at=now() where id=? and status='SUBMITTED'",periodId);
    event(orgId,periodId,runId,"RUN_APPROVED",context,reason,"{}");
    audit.recordAction(context,"/api/v1/hr/payroll/runs/"+runId,"Approved payroll run");
    return runById(runId);
  }

  @PostMapping("/runs/{runId}/payments")
  @Transactional
  public Map<String,Object> reconcilePayment(@PathVariable long runId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_PAYROLL_PAY");
    Map<String,Object> run=runById(runId);
    if(!"APPROVED".equals(String.valueOf(run.get("status")))) throw conflict("Payments can only be reconciled for an approved, not-yet-paid payroll run.");
    long employeeId=positive(body.get("employeeId"),"Employee");
    List<Map<String,Object>> matches=jdbc.queryForList("select * from hr_payroll_line where run_id=? and employee_id=?",runId,employeeId);
    if(matches.isEmpty()) throw bad("The employee is not included in this payroll run.");
    Map<String,Object> line=matches.getFirst();
    BigDecimal amount=decimal(body.get("amount"),"Payment amount");
    if(amount.compareTo((BigDecimal)line.get("net_amount"))!=0) throw bad("Payment amount must match the approved net amount; use an audited adjustment run for corrections.");
    if(!String.valueOf(run.get("currency")).equals(required(body.get("currency"),3,"Currency").toUpperCase())) throw bad("Payment currency must match the payroll run.");
    String status=required(body.get("status"),16,"Payment status").toUpperCase();
    if(!List.of("PENDING","SETTLED","FAILED").contains(status)) throw bad("Payment status must be PENDING, SETTLED or FAILED.");
    String reference=required(body.get("providerReference"),160,"Provider reference");
    if("SETTLED".equals(status)&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_payroll_payment where run_id=? and employee_id=? and payment_status='SETTLED')",Boolean.class,runId,employeeId)))
      throw conflict("This employee already has a settled payment for the run.");
    String note=optional(body.get("note"),1000);
    Long id;
    try {
      id=jdbc.queryForObject("insert into hr_payroll_payment(run_id,employee_id,amount,currency,payment_status,provider_reference,note,reconciled_by_staff_id,settled_at) values (?,?,?,?,?,?,?,?,case when ?='SETTLED' then now() else null end) returning id",
        Long.class,runId,employeeId,amount,String.valueOf(run.get("currency")),status,reference,note,context.staffId(),status);
    } catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"This provider reference has already been used."); }
    long orgId=((Number)run.get("organization_id")).longValue(),periodId=((Number)run.get("period_id")).longValue();
    event(orgId,periodId,runId,"PAYMENT_"+status,context,note,"{\"paymentId\":"+id+",\"employeeId\":"+employeeId+",\"providerReference\":\""+jsonEscape(reference)+"\",\"amount\":\""+amount+"\"}");
    audit.recordAction(context,"/api/v1/hr/payroll/runs/"+runId+"/payments/"+id,"Recorded payroll payment status "+status);
    if("SETTLED".equals(status)) {
      Long total=jdbc.queryForObject("select count(*) from hr_payroll_line where run_id=?",Long.class,runId);
      Long settled=jdbc.queryForObject("select count(distinct employee_id) from hr_payroll_payment where run_id=? and payment_status='SETTLED'",Long.class,runId);
      if(total!=null&&total.equals(settled)) {
        jdbc.update("update hr_payroll_run set status='PAID' where id=? and status='APPROVED'",runId);
        jdbc.update("update hr_payroll_period set status='PAID',updated_at=now() where id=? and status='APPROVED'",periodId);
      }
    }
    return jdbc.queryForMap("select id,run_id,employee_id,amount,currency,payment_status,provider_reference,note,reconciled_by_staff_id,settled_at,created_at,updated_at from hr_payroll_payment where id=?",id);
  }

  @GetMapping("/runs/{runId}")
  public Map<String,Object> run(@PathVariable long runId,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    Map<String,Object> run=runById(runId);
    List<Map<String,Object>> lines=jdbc.queryForList("select id,employee_id,employee_number_snapshot,employee_name_snapshot,component_snapshot,gross_amount,deduction_amount,net_amount,currency,created_at from hr_payroll_line where run_id=? order by employee_number_snapshot,employee_id",runId);
    List<Map<String,Object>> payments=jdbc.queryForList("select id,employee_id,amount,currency,payment_status,provider_reference,note,reconciled_by_staff_id,settled_at,created_at from hr_payroll_payment where run_id=? order by created_at desc,id desc",runId);
    return Map.of("run",run,"lines",lines,"payments",payments);
  }

  private Map<String,Object> employee(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("select id,organization_id,employee_number,display_name,employment_status from hr_employee where id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found.");
    return rows.getFirst();
  }
  private Map<String,Object> componentById(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("select id,organization_id,code,name,component_type,active from hr_salary_component where id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Salary component not found.");
    return rows.getFirst();
  }
  private Map<String,Object> periodById(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("select * from hr_payroll_period where id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payroll period not found.");
    return rows.getFirst();
  }
  private Map<String,Object> runById(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("select r.*,p.organization_id,p.period_name,p.start_date,p.end_date,p.status period_status from hr_payroll_run r join hr_payroll_period p on p.id=r.period_id where r.id=?",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payroll run not found.");
    return rows.getFirst();
  }
  private void ensureOrganization(long id) {
    if(id<=0||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,id)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active organisation not found.");
  }
  private void event(long orgId,Long periodId,Long runId,String type,AuthContext context,String reason,String details) {
    jdbc.update("insert into hr_payroll_event(organization_id,period_id,run_id,event_type,actor_staff_id,reason,details) values (?,?,?,?,?,?,?::jsonb)",orgId,periodId,runId,type,context.staffId(),reason,details==null?"{}":details);
  }
  private void requireView(AuthContext context){authorization.requirePermission(context,"HR_PAYROLL_VIEW");}
  private void requireManage(AuthContext context){authorization.requirePermission(context,"HR_PAYROLL_MANAGE");}
  private long positive(Object value,String label){try{long n=Long.parseLong(String.valueOf(value));if(n>0)return n;}catch(RuntimeException ignored){}throw bad(label+" ID must be a positive integer.");}
  private String required(Object value,int max,String label){if(!(value instanceof String s)||s.trim().isEmpty()||s.trim().length()>max)throw bad(label+" is required and must be no longer than "+max+" characters.");return s.trim();}
  private String optional(Object value,int max){if(value==null)return null;String s=String.valueOf(value).trim();if(s.isEmpty())return null;if(s.length()>max)throw bad("A text field exceeds its maximum length of "+max+" characters.");return s;}
  private BigDecimal decimal(Object value,String label){try{return new BigDecimal(String.valueOf(value));}catch(RuntimeException ex){throw bad(label+" must be a decimal number.");}}
  private LocalDate parseRequiredDate(Object value,String label){try{return LocalDate.parse(required(value,10,label));}catch(RuntimeException ex){throw bad(label+" must use YYYY-MM-DD format.");}}
  private LocalDate parseOptionalDate(Object value,LocalDate fallback){if(value==null||String.valueOf(value).isBlank())return fallback;try{return LocalDate.parse(String.valueOf(value));}catch(RuntimeException ex){throw bad("Date must use YYYY-MM-DD format.");}}
  private LocalDate parseOptionalDate(Object value,String label){if(value==null||String.valueOf(value).isBlank())return null;try{return LocalDate.parse(String.valueOf(value));}catch(RuntimeException ex){throw bad(label+" must use YYYY-MM-DD format.");}}
  private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
  private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
  private String jsonEscape(String value){
    StringBuilder out=new StringBuilder();
    for(int i=0;i<value.length();i++){
      char ch=value.charAt(i);
      switch(ch){
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> { if(ch<0x20) out.append(String.format("\\u%04x",(int)ch)); else out.append(ch); }
      }
    }
    return out.toString();
  }
}
