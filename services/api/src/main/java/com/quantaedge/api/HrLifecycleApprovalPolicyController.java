package com.quantaedge.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/hr/organization/{organizationId}/approval-rules")
public class HrLifecycleApprovalPolicyController {
  private static final List<String> STATUSES=List.of("DRAFT","ACTIVE","ON_LEAVE","NOTICE_PERIOD","SEPARATED","INACTIVE");
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;

  public HrLifecycleApprovalPolicyController(JdbcTemplate jdbc,AuthorizationService authorization,StaffAuditService audit) {
    this.jdbc=jdbc;this.authorization=authorization;this.audit=audit;
  }

  @GetMapping
  public Map<String,Object> list(@PathVariable long organizationId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_APPROVAL_POLICY_MANAGE");
    ensureOrganization(organizationId);
    return Map.of("items",jdbc.queryForList("""
      select r.id,r.organization_id,r.transition_key,r.from_status,r.to_status,
        r.required_permission_key,r.active,r.updated_by_staff_id,r.created_at,r.updated_at
      from hr_lifecycle_approval_rule r where r.organization_id=?
      order by r.transition_key
      """,organizationId));
  }

  @PutMapping("/{transitionKey}")
  @Transactional
  public Map<String,Object> upsert(@PathVariable long organizationId,@PathVariable String transitionKey,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    authorization.requirePermission(context,"HR_APPROVAL_POLICY_MANAGE");
    ensureOrganization(organizationId);
    String key=required(transitionKey,64,"Transition key").toUpperCase();
    String from=required(body.get("fromStatus"),24,"From status").toUpperCase();
    String to=required(body.get("toStatus"),24,"To status").toUpperCase();
    String permission=required(body.get("requiredPermissionKey"),96,"Required permission").toUpperCase();
    if(!STATUSES.contains(from)||!STATUSES.contains(to)||from.equals(to))
      throw bad("Choose two different supported employment statuses.");
    if(!key.matches("[A-Z0-9_]+"))throw bad("Transition key may contain only uppercase letters, digits and underscores.");
    if(!key.equals(from+"_TO_"+to))throw bad("Transition key must match FROMSTATUS_TOTOSTATUS for the selected statuses.");
    if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from staff_permission_catalog where permission_key=?)",Boolean.class,permission)))
      throw bad("Choose a permission key from the permission catalog.");
    boolean active=body.get("active")==null||Boolean.parseBoolean(String.valueOf(body.get("active")));
    jdbc.update("""
      insert into hr_lifecycle_approval_rule(organization_id,transition_key,from_status,to_status,required_permission_key,active,updated_by_staff_id)
      values (?,?,?,?,?,?,?)
      on conflict(organization_id,transition_key) do update set from_status=excluded.from_status,
        to_status=excluded.to_status,required_permission_key=excluded.required_permission_key,
        active=excluded.active,updated_by_staff_id=excluded.updated_by_staff_id,updated_at=now()
      """,organizationId,key,from,to,permission,active,context.staffId());
    audit.recordAction(context,"/api/v1/hr/organization/"+organizationId+"/approval-rules/"+key,
      "Configured lifecycle transition "+from+" -> "+to+" requiring "+permission+" (active="+active+")");
    return jdbc.queryForMap("""
      select id,organization_id,transition_key,from_status,to_status,required_permission_key,
        active,updated_by_staff_id,created_at,updated_at from hr_lifecycle_approval_rule
      where organization_id=? and transition_key=?
      """,organizationId,key);
  }

  private void ensureOrganization(long id) {
    if(id<=0||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,id)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active organisation not found.");
  }
  private static String required(Object value,int max,String label) {
    String text=value==null?"":String.valueOf(value).trim();
    if(text.isEmpty()||text.length()>max)throw bad(label+" is required and must be at most "+max+" characters.");
    return text;
  }
  private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
