package com.quantaedge.api;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Enforces optional manager-only visibility over the authenticated employee's reporting tree. */
@Component
public class HrReportingScopeService {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  public HrReportingScopeService(JdbcTemplate jdbc,AuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

  public boolean isManagerScoped(AuthContext context){
    return context!=null&&!context.isAdmin()&&authorization.hasPermission(context,"HR_MANAGER_TEAM_VIEW");
  }

  public void requireEmployeeAccess(AuthContext context,long employeeId){
    if(!isManagerScoped(context))return;
    if(context==null||context.staffId()==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found.");
    Boolean allowed=jdbc.queryForObject("""
      with recursive reporting(id,organization_id) as (
        select child.id,child.organization_id from hr_employee child
        where child.manager_employee_id in
          (select manager.id from hr_employee manager where manager.linked_staff_account_id=?)
          and child.employment_status not in ('SEPARATED','INACTIVE','DRAFT')
        union
        select child.id,child.organization_id from hr_employee child
        join reporting parent on child.manager_employee_id=parent.id and child.organization_id=parent.organization_id
        where child.employment_status not in ('SEPARATED','INACTIVE','DRAFT')
      )
      select exists(select 1 from reporting where id=?)
      """,Boolean.class,context.staffId(),employeeId);
    if(!Boolean.TRUE.equals(allowed))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found.");
  }

  public String sqlScopePredicate(String employeeAlias){
    return "(not exists(select 1 from staff_permission_grant scope_grant where scope_grant.staff_id=? and scope_grant.permission_key='HR_MANAGER_TEAM_VIEW') or "
      +employeeAlias+".id in (with recursive reporting(id) as ("
      +"select child.id from hr_employee child where child.manager_employee_id in (select manager.id from hr_employee manager where manager.linked_staff_account_id=?) and child.employment_status not in ('SEPARATED','INACTIVE','DRAFT') "
      +"union select child.id from hr_employee child join reporting parent on child.manager_employee_id=parent.id where child.employment_status not in ('SEPARATED','INACTIVE','DRAFT')) select id from reporting))";
  }
}
