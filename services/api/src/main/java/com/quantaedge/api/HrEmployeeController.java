package com.quantaedge.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
public class HrEmployeeController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrEmployeeController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService audit) {
    this.jdbc=jdbc;
    this.authorization=authorization;
    this.audit=audit;
  }

  @GetMapping("/organization")
  public Map<String,Object> organization(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    return jdbc.queryForMap("select id,public_id,name,legal_name,timezone,active,working_days_per_week from hr_organization order by id limit 1");
  }

  @GetMapping("/organization/{id}/work-schedule")
  public Map<String,Object> workSchedule(@PathVariable long id,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    List<Map<String,Object>> rows=jdbc.queryForList("select id,name,working_days_per_week from hr_organization where id=? and active=true",id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organisation not found.");
    return rows.getFirst();
  }

  @PutMapping("/organization/{id}/work-schedule")
  @Transactional
  public Map<String,Object> updateWorkSchedule(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    int days;
    try { days=Integer.parseInt(String.valueOf(body.get("workingDaysPerWeek"))); }
    catch(RuntimeException ex) { throw badRequest("Working days per week must be 5 or 6."); }
    if(days!=5 && days!=6) throw badRequest("Working days per week must be 5 or 6.");
    int changed=jdbc.update("update hr_organization set working_days_per_week=?,updated_at=now() where id=? and active=true",days,id);
    if(changed!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organisation not found.");
    Map<String,Object> row=jdbc.queryForMap("select id,name,working_days_per_week from hr_organization where id=?",id);
    record(context,"/api/v1/hr/organization/"+id+"/work-schedule","Set working week to "+days+" days for "+row.get("name"));
    return row;
  }

  @GetMapping("/employment-types")
  public List<Map<String,Object>> employmentTypes(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    return jdbc.queryForList("select code,display_name from hr_employment_type where active=true order by display_name");
  }

  @GetMapping("/departments")
  public List<Map<String,Object>> departments(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    return jdbc.queryForList("""
      select d.id,d.code,d.name,d.parent_department_id,d.active,d.organization_id,
             p.name as parent_name
      from hr_department d left join hr_department p on p.id=d.parent_department_id
      order by d.active desc,d.name
      """);
  }

  @PostMapping("/departments")
  @Transactional
  public Map<String,Object> createDepartment(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long organizationId=organizationId(body.get("organizationId"));
    String code=required(body.get("code"),32,"Department code").toUpperCase();
    String name=required(body.get("name"),120,"Department name");
    Long parentId=optionalLong(body.get("parentDepartmentId"));
    if(parentId!=null) {
      Boolean valid=jdbc.queryForObject("select exists(select 1 from hr_department where id=? and organization_id=? and active=true)",Boolean.class,parentId,organizationId);
      if(!Boolean.TRUE.equals(valid)) throw badRequest("Choose an active parent department in the same organisation.");
    }
    try {
      Long id=jdbc.queryForObject("""
        insert into hr_department(organization_id,parent_department_id,code,name)
        values (?,?,?,?) returning id
        """,Long.class,organizationId,parentId,code,name);
      Map<String,Object> row=jdbc.queryForMap("select id,code,name,parent_department_id,active,organization_id from hr_department where id=?",id);
      record(context,"/api/v1/hr/departments/"+id,"Created department "+code+" — "+name);
      return row;
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Department code or name already exists in this organisation.");
    }
  }

  @PutMapping("/departments/{id}")
  @Transactional
  public Map<String,Object> updateDepartment(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> before;
    try { before=jdbc.queryForMap("select id,organization_id,code,name,parent_department_id,active from hr_department where id=?",id); }
    catch(org.springframework.dao.EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Department not found."); }
    long orgId=((Number)before.get("organization_id")).longValue();
    String code=required(body.get("code"),32,"Department code").toUpperCase();
    if(!code.matches("^[A-Z0-9_-]+$")) throw badRequest("Department code may contain only letters, numbers, underscore and hyphen.");
    String name=required(body.get("name"),120,"Department name");
    Long parent=body.containsKey("parentDepartmentId")?optionalLong(body.get("parentDepartmentId")):
      (before.get("parent_department_id") instanceof Number n?n.longValue():null);
    if(parent!=null) {
      Boolean valid=jdbc.queryForObject("select exists(select 1 from hr_department where id=? and organization_id=? and active=true)",Boolean.class,parent,orgId);
      if(!Boolean.TRUE.equals(valid)) throw badRequest("Choose an active parent department in the same organisation.");
      if(parent==id) throw badRequest("A department cannot be its own parent.");
      Boolean cycle=jdbc.queryForObject("""
        with recursive ancestors(id,parent_department_id) as (
          select id,parent_department_id from hr_department where id=? and organization_id=?
          union all
          select p.id,p.parent_department_id from hr_department p
          join ancestors a on p.id=a.parent_department_id where p.organization_id=?
        )
        select exists(select 1 from ancestors where id=?)
        """,Boolean.class,parent,orgId,orgId,id);
      if(Boolean.TRUE.equals(cycle)) throw badRequest("A department cannot be moved beneath one of its own descendants.");
    }
    boolean active=body.containsKey("active")?Boolean.parseBoolean(String.valueOf(body.get("active"))):Boolean.TRUE.equals(before.get("active"));
    if(!active) {
      Boolean hasEmployees=jdbc.queryForObject("select exists(select 1 from hr_employee where department_id=? and employment_status not in ('SEPARATED','INACTIVE'))",Boolean.class,id);
      Boolean hasChildren=jdbc.queryForObject("select exists(select 1 from hr_department where parent_department_id=? and active=true)",Boolean.class,id);
      if(Boolean.TRUE.equals(hasEmployees)||Boolean.TRUE.equals(hasChildren)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reassign active employees and deactivate child departments before deactivating this department.");
    }
    try {
      jdbc.update("update hr_department set code=?,name=?,parent_department_id=?,active=?,updated_at=now() where id=?",code,name,parent,active,id);
    } catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Department code or name already exists in this organisation."); }
    Map<String,Object> after=jdbc.queryForMap("select id,code,name,parent_department_id,active,organization_id from hr_department where id=?",id);
    record(context,"/api/v1/hr/departments/"+id,"Updated department "+code+" — "+name);
    return after;
  }

  @GetMapping("/designations")
  public List<Map<String,Object>> designations(@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    return jdbc.queryForList("select id,code,name,category,active,organization_id from hr_designation order by active desc,name");
  }

  @PostMapping("/designations")
  @Transactional
  public Map<String,Object> createDesignation(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    long organizationId=organizationId(body.get("organizationId"));
    String code=required(body.get("code"),32,"Designation code").toUpperCase();
    String name=required(body.get("name"),120,"Designation name");
    String category=String.valueOf(body.getOrDefault("category","OTHER")).trim().toUpperCase();
    if(!List.of("ACADEMIC","ADMINISTRATIVE","OPERATIONS","LEADERSHIP","OTHER").contains(category))
      throw badRequest("Choose a supported designation category.");
    try {
      Long id=jdbc.queryForObject("insert into hr_designation(organization_id,code,name,category) values (?,?,?,?) returning id",Long.class,organizationId,code,name,category);
      Map<String,Object> row=jdbc.queryForMap("select id,code,name,category,active,organization_id from hr_designation where id=?",id);
      record(context,"/api/v1/hr/designations/"+id,"Created designation "+code+" — "+name);
      return row;
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Designation code or name already exists in this organisation.");
    }
  }

  @PutMapping("/designations/{id}")
  @Transactional
  public Map<String,Object> updateDesignation(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> before;
    try { before=jdbc.queryForMap("select id,organization_id,code,name,category,active from hr_designation where id=?",id); }
    catch(org.springframework.dao.EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Designation not found."); }
    String code=required(body.get("code"),32,"Designation code").toUpperCase();
    if(!code.matches("^[A-Z0-9_-]+$")) throw badRequest("Designation code may contain only letters, numbers, underscore and hyphen.");
    String name=required(body.get("name"),120,"Designation name");
    String category=body.containsKey("category")?required(body.get("category"),24,"Designation category").toUpperCase():String.valueOf(before.get("category"));
    if(!List.of("ACADEMIC","ADMINISTRATIVE","OPERATIONS","LEADERSHIP","OTHER").contains(category)) throw badRequest("Choose a supported designation category.");
    boolean active=body.containsKey("active")?Boolean.parseBoolean(String.valueOf(body.get("active"))):Boolean.TRUE.equals(before.get("active"));
    if(!active) {
      Boolean assigned=jdbc.queryForObject("select exists(select 1 from hr_employee where designation_id=? and employment_status not in ('SEPARATED','INACTIVE'))",Boolean.class,id);
      if(Boolean.TRUE.equals(assigned)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reassign active employees before deactivating this designation.");
    }
    try { jdbc.update("update hr_designation set code=?,name=?,category=?,active=?,updated_at=now() where id=?",code,name,category,active,id); }
    catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Designation code or name already exists in this organisation."); }
    Map<String,Object> after=jdbc.queryForMap("select id,code,name,category,active,organization_id from hr_designation where id=?",id);
    record(context,"/api/v1/hr/designations/"+id,"Updated designation "+code+" — "+name);
    return after;
  }

  @GetMapping("/employees")
  public Map<String,Object> employees(@RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam(defaultValue="") String q,
      @RequestParam(defaultValue="false") boolean includeInactive,
      @RequestParam(defaultValue="0") int offset,
      @RequestParam(defaultValue="50") int limit) {
    requireView(context);
    int safeLimit=Math.max(1,Math.min(limit,100));
    int safeOffset=Math.max(0,Math.min(offset,1000000));
    String term="%"+(q==null?"":q.trim().replace("%","\\%").replace("_","\\_"))+"%";
    List<Map<String,Object>> items=jdbc.queryForList("""
      select e.id,e.public_id,e.employee_number,e.display_name,e.email,e.phone_e164,
             e.joining_date,e.probation_end_date,e.employment_status,e.exit_date,e.employment_type_code,
             e.department_id,d.name as department_name,e.designation_id,g.name as designation_name,
             e.manager_employee_id,m.display_name as manager_name,e.linked_staff_account_id,
             e.created_at,e.updated_at
      from hr_employee e
      left join hr_department d on d.id=e.department_id
      left join hr_designation g on g.id=e.designation_id
      left join hr_employee m on m.id=e.manager_employee_id
      where (? or e.employment_status not in ('INACTIVE','SEPARATED'))
        and (?='' or e.display_name ilike ? or e.employee_number ilike ? or coalesce(d.name,'') ilike ? or coalesce(g.name,'') ilike ?)
      order by e.display_name,e.id
      limit ? offset ?
      """,includeInactive,q==null?"":q.trim(),term,term,term,term,safeLimit,safeOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from hr_employee e
      left join hr_department d on d.id=e.department_id
      left join hr_designation g on g.id=e.designation_id
      where (? or e.employment_status not in ('INACTIVE','SEPARATED'))
        and (?='' or e.display_name ilike ? or e.employee_number ilike ? or coalesce(d.name,'') ilike ? or coalesce(g.name,'') ilike ?)
      """,Long.class,includeInactive,q==null?"":q.trim(),term,term,term,term);
    return Map.of("items",items,"total",total==null?0L:total,"limit",safeLimit,"offset",safeOffset);
  }

  @GetMapping("/employees/{id}")
  public Map<String,Object> employee(@PathVariable long id,@RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    return employeeById(id);
  }

  @GetMapping("/employees/{id}/history")
  public List<Map<String,Object>> employeeHistory(@PathVariable long id,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireView(context);
    // Resolve the employee first so callers get a consistent 404 for unknown records.
    employeeById(id);
    return jdbc.queryForList("""
      select h.id,h.employee_id,h.changed_by_staff_id,h.change_type,h.effective_date,
             h.before_state,h.after_state,h.reason,h.created_at
      from hr_employee_history h
      where h.employee_id=?
      order by h.effective_date desc,h.created_at desc,h.id desc
      """,id);
  }

  @PostMapping("/employees")
  @Transactional
  public Map<String,Object> createEmployee(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    String name=required(body.get("displayName"),120,"Employee name");
    String email=optionalText(body.get("email"),254);
    String phone=optionalText(body.get("phoneE164"),24);
    validateContactFields(email,phone);
    long orgId=organizationId(body.get("organizationId"));
    String type=required(body.get("employmentTypeCode"),32,"Employment type").toUpperCase();
    LocalDate joining=parseDate(body.get("joiningDate"),"Joining date");
    LocalDate probation=parseOptionalDate(body.get("probationEndDate"),"Probation end date");
    LocalDate exit=parseOptionalDate(body.get("exitDate"),"Exit date");
    validateEmploymentDates(joining,probation,exit);
    Long department=optionalLong(body.get("departmentId"));
    Long designation=optionalLong(body.get("designationId"));
    Long manager=optionalLong(body.get("managerEmployeeId"));
    validateReference("hr_department",department,orgId,"Department");
    validateReference("hr_designation",designation,orgId,"Designation");
    validateManager(manager,null,orgId);
    Boolean typeExists=jdbc.queryForObject("select exists(select 1 from hr_employment_type where code=? and active=true)",Boolean.class,type);
    if(!Boolean.TRUE.equals(typeExists)) throw badRequest("Choose a supported employment type.");
    String status=String.valueOf(body.getOrDefault("employmentStatus","ACTIVE")).toUpperCase();
    if(!List.of("DRAFT","ACTIVE","ON_LEAVE","NOTICE_PERIOD","SEPARATED","INACTIVE").contains(status)) throw badRequest("Choose a supported employment status.");
    if("SEPARATED".equals(status)&&exit==null) throw badRequest("A separated employee must have an exit date.");
    String number="QE-EMP-"+String.format("%06d",jdbc.queryForObject("select nextval('hr_employee_number_seq')",Long.class));
    try {
      Long id=jdbc.queryForObject("""
        insert into hr_employee(public_id,employee_number,organization_id,department_id,designation_id,
          employment_type_code,manager_employee_id,display_name,email,phone_e164,joining_date,
          probation_end_date,employment_status,exit_date,notes,created_by_staff_id,updated_by_staff_id)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
        """,Long.class,UUID.randomUUID(),number,orgId,department,designation,type,manager,name,email,phone,
          Date.valueOf(joining),probation==null?null:Date.valueOf(probation),status,exit==null?null:Date.valueOf(exit),
          optionalText(body.get("notes"),1000),context.staffId(),context.staffId());
      Map<String,Object> created=employeeById(id);
      writeHistory(id,context,"EMPLOYEE_CREATED",joining,null,created,optionalText(body.get("reason"),500));
      record(context,"/api/v1/hr/employees/"+id,"Created employee record "+number);
      return created;
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"A conflicting employee record already exists.");
    }
  }

  @PutMapping("/employees/{id}")
  @Transactional
  public Map<String,Object> updateEmployee(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> before=employeeById(id);
    String name=required(body.get("displayName"),120,"Employee name");
    String email=optionalText(body.get("email"),254);
    String phone=optionalText(body.get("phoneE164"),24);
    validateContactFields(email,phone);
    Long department=optionalLong(body.get("departmentId"));
    Long designation=optionalLong(body.get("designationId"));
    Long manager=optionalLong(body.get("managerEmployeeId"));
    long orgId=((Number)before.get("organization_id")).longValue();
    validateReference("hr_department",department,orgId,"Department");
    validateReference("hr_designation",designation,orgId,"Designation");
    validateManager(manager,id,orgId);
    LocalDate probation=parseOptionalDate(body.get("probationEndDate"),"Probation end date");
    LocalDate joining=((Date)before.get("joining_date")).toLocalDate();
    LocalDate exit=body.containsKey("exitDate")
        ? parseOptionalDate(body.get("exitDate"),"Exit date")
        : (before.get("exit_date") instanceof Date existingExit ? existingExit.toLocalDate() : null);
    validateEmploymentDates(joining,probation,exit);
    String previousStatus=String.valueOf(before.get("employment_status"));
    String status=body.get("employmentStatus")==null?previousStatus:required(body.get("employmentStatus"),24,"Employment status").toUpperCase();
    validateEmploymentStatusTransition(previousStatus,status,exit);
    String notes=optionalText(body.get("notes"),1000);
    jdbc.update("""
      update hr_employee set display_name=?,email=?,phone_e164=?,department_id=?,designation_id=?,
        manager_employee_id=?,probation_end_date=?,employment_status=?,exit_date=?,notes=?,
        updated_by_staff_id=?,updated_at=now()
      where id=?
      """,name,email,phone,department,designation,manager,probation==null?null:Date.valueOf(probation),
        status,exit==null?null:Date.valueOf(exit),notes,context.staffId(),id);
    Map<String,Object> after=employeeById(id);
    writeHistory(id,context,"EMPLOYEE_UPDATED",LocalDate.now(),before,after,optionalText(body.get("reason"),500));
    record(context,"/api/v1/hr/employees/"+id,"Updated employee record "+before.get("employee_number"));
    return after;
  }

  @PatchMapping("/employees/{id}/status")
  @Transactional
  public Map<String,Object> updateStatus(@PathVariable long id,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireManage(context);
    Map<String,Object> before=employeeById(id);
    String status=required(body.get("employmentStatus"),24,"Employment status").toUpperCase();
    if(!List.of("DRAFT","ACTIVE","ON_LEAVE","NOTICE_PERIOD","SEPARATED","INACTIVE").contains(status))
      throw badRequest("Choose a supported employment status.");
    LocalDate exit=body.containsKey("exitDate")
        ? parseOptionalDate(body.get("exitDate"),"Exit date")
        : (before.get("exit_date") instanceof java.sql.Date existingExit ? existingExit.toLocalDate() : null);
    LocalDate joining=((java.sql.Date)before.get("joining_date")).toLocalDate();
    LocalDate probation=before.get("probation_end_date") instanceof java.sql.Date existingProbation ? existingProbation.toLocalDate() : null;
    validateEmploymentDates(joining,probation,exit);
    String previousStatus=String.valueOf(before.get("employment_status"));
    validateEmploymentStatusTransition(previousStatus,status,exit);
    if(!previousStatus.equals(status)) {
      long organizationId=((Number)before.get("organization_id")).longValue();
      String transitionKey=previousStatus+"_TO_"+status;
      List<Map<String,Object>> rules=jdbc.queryForList("""
        select required_permission_key from hr_lifecycle_approval_rule
        where organization_id=? and transition_key=? and from_status=? and to_status=? and active=true
        """,organizationId,transitionKey,previousStatus,status);
      if(rules.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
        "No active approval rule is configured for "+previousStatus+" -> "+status+". Ask an approval-policy administrator to configure it.");
      authorization.requirePermission(context,String.valueOf(rules.getFirst().get("required_permission_key")));
    }
    jdbc.update("update hr_employee set employment_status=?,exit_date=?,updated_by_staff_id=?,updated_at=now() where id=?",
      status,exit==null?null:Date.valueOf(exit),context.staffId(),id);
    Map<String,Object> after=employeeById(id);
    writeHistory(id,context,"EMPLOYMENT_STATUS_CHANGED",LocalDate.now(),before,after,optionalText(body.get("reason"),500));
    record(context,"/api/v1/hr/employees/"+id+"/status","Changed employee status to "+status);
    return after;
  }

  private void validateEmploymentDates(LocalDate joining,LocalDate probation,LocalDate exit) {
    if(probation!=null && probation.isBefore(joining))
      throw badRequest("Probation end date cannot be before the joining date.");
    if(exit!=null && exit.isBefore(joining))
      throw badRequest("Exit date cannot be before the joining date.");
  }

  private void validateEmploymentStatusTransition(String previousStatus,String nextStatus,LocalDate exit) {
    if(!List.of("DRAFT","ACTIVE","ON_LEAVE","NOTICE_PERIOD","SEPARATED","INACTIVE").contains(nextStatus))
      throw badRequest("Choose a supported employment status.");
    if("SEPARATED".equals(nextStatus) && exit==null)
      throw badRequest("A separated employee must have an exit date.");
    if("SEPARATED".equals(previousStatus) && !"SEPARATED".equals(nextStatus))
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A separated employment record cannot be reactivated. Create a new employment record for a rehire.");
  }

  private void validateContactFields(String email,String phone) {
    if(email!=null && !email.matches("(?i)^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")) {
      throw badRequest("Email address is not valid.");
    }
    if(phone!=null && !phone.matches("^\\+[1-9][0-9]{6,14}$")) {
      throw badRequest("Phone number must use E.164 format, such as +14155552671.");
    }
  }

  private void validateManager(Long managerId,Long employeeId,long orgId) {
    if(managerId==null) return;
    Boolean valid=jdbc.queryForObject("""
      with recursive manager_chain(id,manager_employee_id) as (
        select id,manager_employee_id from hr_employee
        where id=? and organization_id=? and employment_status not in ('SEPARATED','INACTIVE','DRAFT')
        union
        select parent.id,parent.manager_employee_id
        from hr_employee parent
        join manager_chain child on parent.id=child.manager_employee_id
        where parent.organization_id=?
      )
      select exists(select 1 from manager_chain)
         and not exists(select 1 from manager_chain where id=coalesce(?, -1))
      """,Boolean.class,managerId,orgId,orgId,employeeId);
    if(!Boolean.TRUE.equals(valid)) {
      throw badRequest("Choose an active manager in the same organisation without creating a reporting cycle.");
    }
  }

  private void validateReference(String table,Long id,long orgId,String label) {
    if(id==null) return;
    // table is a fixed internal constant selected only by controller code, never user input.
    Boolean valid=jdbc.queryForObject("select exists(select 1 from "+table+" where id=? and organization_id=? and active=true)",Boolean.class,id,orgId);
    if(!Boolean.TRUE.equals(valid)) throw badRequest("Choose an active "+label.toLowerCase()+" in this organisation.");
  }

  private long organizationId(Object requested) {
    if(requested instanceof Number n) {
      long id=n.longValue();
      Boolean exists=jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,id);
      if(Boolean.TRUE.equals(exists)) return id;
      throw badRequest("Choose an active organisation.");
    }
    return jdbc.queryForObject("select id from hr_organization where active=true order by id limit 1",Long.class);
  }

  private Map<String,Object> employeeById(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select e.id,e.public_id,e.employee_number,e.organization_id,e.display_name,e.email,e.phone_e164,
        e.joining_date,e.probation_end_date,e.employment_status,e.exit_date,e.employment_type_code,
        e.department_id,d.name as department_name,e.designation_id,g.name as designation_name,
        e.manager_employee_id,m.display_name as manager_name,e.linked_staff_account_id,e.notes,
        e.created_at,e.updated_at
      from hr_employee e left join hr_department d on d.id=e.department_id
      left join hr_designation g on g.id=e.designation_id
      left join hr_employee m on m.id=e.manager_employee_id where e.id=?
      """,id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee record not found.");
    return rows.getFirst();
  }

  private void writeHistory(long id,AuthContext context,String type,LocalDate effective,Map<String,Object> before,Map<String,Object> after,String reason) {
    jdbc.update("""
      insert into hr_employee_history(employee_id,changed_by_staff_id,change_type,effective_date,before_state,after_state,reason)
      values (?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)
      """,id,context.staffId(),type,Date.valueOf(effective),json(before),json(after),reason);
  }

  private String json(Map<String,Object> value) {
    if(value==null) return null;
    StringBuilder out=new StringBuilder("{");
    boolean first=true;
    for(Map.Entry<String,Object> entry:value.entrySet()) {
      if(!first) out.append(',');
      first=false;
      out.append(jsonString(entry.getKey())).append(':');
      if(entry.getValue()==null) out.append("null");
      else out.append(jsonString(String.valueOf(entry.getValue())));
    }
    return out.append('}').toString();
  }
  private String jsonString(String value) {
    StringBuilder out=new StringBuilder("\"");
    for(int i=0;i<value.length();i++) {
      char c=value.charAt(i);
      switch(c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> { if(c<0x20) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
      }
    }
    return out.append('"').toString();
  }

  private void requireView(AuthContext context) { authorization.requirePermission(context,"HR_EMPLOYEE_VIEW"); }
  private void requireManage(AuthContext context) { authorization.requirePermission(context,"HR_EMPLOYEE_MANAGE"); }
  private void record(AuthContext context,String path,String details) { audit.recordAction(context,path,details); }
  private String required(Object value,int max,String label) {
    if(!(value instanceof String text)||text.trim().isEmpty()||text.trim().length()>max)
      throw badRequest(label+" is required and must be no longer than "+max+" characters.");
    return text.trim();
  }
  private String optionalText(Object value,int max) {
    if(value==null) return null;
    String text=String.valueOf(value).trim();
    if(text.isEmpty()) return null;
    if(text.length()>max) throw badRequest("A text field exceeds its maximum length of "+max+" characters.");
    return text;
  }
  private Long optionalLong(Object value) {
    if(value==null||String.valueOf(value).isBlank()) return null;
    try { long id=Long.parseLong(String.valueOf(value)); if(id<1) throw new NumberFormatException(); return id; }
    catch(NumberFormatException ex) { throw badRequest("A selected record ID is invalid."); }
  }
  private LocalDate parseDate(Object value,String label) {
    try { return LocalDate.parse(required(value,10,label)); }
    catch(RuntimeException ex) { throw badRequest(label+" must use YYYY-MM-DD format."); }
  }
  private LocalDate parseOptionalDate(Object value,String label) {
    if(value==null||String.valueOf(value).isBlank()) return null;
    try { return LocalDate.parse(String.valueOf(value)); }
    catch(RuntimeException ex) { throw badRequest(label+" must use YYYY-MM-DD format."); }
  }
  private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
