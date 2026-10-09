package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/staff")
public class AdminStaffController {
  private static final Set<String> ROLES = Set.of(
      "ADMIN", "MANAGER", "MODERATOR", "CONTENT_AUTHOR", "CONTENT_REVIEWER", "PUBLISHER");

  private final JdbcTemplate jdbc;
  private final AuthService auth;
  private final AuthorizationService authorization;

  public AdminStaffController(JdbcTemplate jdbc, AuthService auth, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.auth = auth;
    this.authorization = authorization;
  }

  @GetMapping
  public List<Map<String,Object>> listStaff(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireStaffManager(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id, public_id, mobile_e164, display_name, role, active,
               created_at, updated_at, last_login_at
        from staff_account
        order by case when role='ADMIN' then 0 else 1 end, lower(display_name), id
        """);
    for (Map<String,Object> row : rows) {
      long id=((Number)row.get("id")).longValue();
      row.put("permissions", auth.permissionsForStaff(id));
      row.put("isCurrentStaff", context.staffId()!=null && context.staffId()==id);
    }
    return rows;
  }

  @GetMapping("/permissions")
  public List<Map<String,Object>> listPermissions(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireStaffManager(context);
    return jdbc.queryForList("""
        select permission_key, display_name, description
        from staff_permission_catalog
        order by case when permission_key like 'CONTENT_%' then 0 else 1 end, display_name
        """);
  }

  @PostMapping
  @Transactional
  public Map<String,Object> createStaff(
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireStaffManager(context);
    String name=requiredText(body.get("displayName"), 120, "Display name");
    String mobile=auth.normalizeMobile(String.valueOf(body.getOrDefault("mobile","")));
    if (!(mobile.startsWith("+91") && mobile.length()==13
        && mobile.substring(3).chars().allMatch(Character::isDigit))) {
      throw badRequest("Enter a valid 10-digit Indian mobile number.");
    }
    Boolean linkedParent=jdbc.queryForObject(
        "select exists(select 1 from user_account where mobile_e164=?)",Boolean.class,mobile);
    if(Boolean.TRUE.equals(linkedParent)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This mobile is already associated with a parent account. Staff identities must use a separate mobile number.");
    }
    String role=String.valueOf(body.getOrDefault("role","CONTENT_AUTHOR")).trim().toUpperCase();
    if (!ROLES.contains(role)) throw badRequest("Choose a supported staff role.");
    List<String> permissions=body.containsKey("permissions")
        ? readPermissions(body.get("permissions"))
        : defaultPermissions(role);
    validateStaffPermissions(role, permissions);

    Long id;
    try {
      id=jdbc.queryForObject("""
          insert into staff_account(public_id,mobile_e164,display_name,role,active,created_by_staff_id)
          values (?,?,?,?,true,?) returning id
          """, Long.class, UUID.randomUUID(), mobile, name, role, context.staffId());
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A staff account already exists for this mobile number.");
    }
    replacePermissions(id, permissions, context.staffId());
    return staffById(id);
  }

  @PutMapping("/{staffId}/permissions")
  @Transactional
  public Map<String,Object> updateStaffRoleAndPermissions(
      @PathVariable long staffId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireStaffManager(context);
    Map<String,Object> existing=staffById(staffId);
    String role=String.valueOf(body.getOrDefault("role",existing.get("role"))).trim().toUpperCase();
    if (!ROLES.contains(role)) throw badRequest("Choose a supported staff role.");
    if (body.get("permissions")==null) throw badRequest("Select the permissions this staff member needs.");
    List<String> permissions=readPermissions(body.get("permissions"));
    validateStaffPermissions(role,permissions);
    jdbc.update("update staff_account set role=?,updated_at=now() where id=?",role,staffId);
    replacePermissions(staffId,permissions,context.staffId());
    return staffById(staffId);
  }

  @PutMapping("/{staffId}/status")
  @Transactional
  public Map<String,Object> updateStaffStatus(
      @PathVariable long staffId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireStaffManager(context);
    Map<String,Object> staff=staffById(staffId);
    boolean active=body.get("active") instanceof Boolean value ? value : false;
    if (context.staffId()!=null && context.staffId()==staffId && !active) {
      throw badRequest("You cannot suspend your own staff account.");
    }
    if (!active && "ADMIN".equals(staff.get("role"))) {
      Long otherAdmins=jdbc.queryForObject("""
          select count(*) from staff_account
          where role='ADMIN' and active=true and id<>?
          """, Long.class, staffId);
      if (otherAdmins==null || otherAdmins<1) {
        throw badRequest("At least one active administrator must remain.");
      }
    }
    jdbc.update("update staff_account set active=?,updated_at=now() where id=?",active,staffId);
    if (!active) {
      jdbc.update("update auth_session set revoked_at=now() where staff_id=? and revoked_at is null",staffId);
    }
    Map<String,Object> result=staffById(staffId);
    result.put("message",active?"Staff account activated.":"Staff account suspended; active sessions revoked.");
    return result;
  }

  @GetMapping("/audit")
  public List<Map<String,Object>> auditTrail(
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestParam(defaultValue="200") int limit) {
    authorization.requirePermission(context,"AUDIT_VIEW");
    int safeLimit=Math.max(1,Math.min(limit,500));
    return jdbc.queryForList("""
        select a.id,a.actor_staff_id,a.actor_role,a.http_method,a.request_path,
               a.response_status,a.remote_address,a.user_agent,a.created_at,
               coalesce(s.display_name,'Removed staff account') as actor_name
        from staff_audit_log a
        left join staff_account s on s.id=a.actor_staff_id
        order by a.created_at desc,a.id desc
        limit ?
        """,safeLimit);
  }

  private void requireStaffManager(AuthContext context) {
    authorization.requireAdmin(context);
  }

  private Map<String,Object> staffById(long id) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id,public_id,mobile_e164,display_name,role,active,created_at,updated_at,last_login_at
        from staff_account where id=?
        """,id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Staff account not found.");
    Map<String,Object> row=rows.getFirst();
    row.put("permissions",auth.permissionsForStaff(id));
    return row;
  }

  private void replacePermissions(long staffId,List<String> permissions,Long grantedBy) {
    jdbc.update("delete from staff_permission_grant where staff_id=?",staffId);
    for (String permission : permissions) {
      jdbc.update("""
          insert into staff_permission_grant(staff_id,permission_key,granted_by_staff_id)
          values (?,?,?)
          """,staffId,permission,grantedBy);
    }
  }

  private List<String> readPermissions(Object value) {
    if (!(value instanceof List<?> requested)) throw badRequest("Permissions must be selected from the available list.");
    Set<String> distinct=new LinkedHashSet<>();
    for (Object item : requested) {
      if (!(item instanceof String permission) || permission.isBlank()) {
        throw badRequest("Each permission must be a valid permission key.");
      }
      distinct.add(permission.trim().toUpperCase());
    }
    List<String> permissions=new ArrayList<>(distinct);
    for (String permission : permissions) {
      Boolean exists=jdbc.queryForObject(
          "select exists(select 1 from staff_permission_catalog where permission_key=?)",
          Boolean.class, permission);
      if (!Boolean.TRUE.equals(exists)) throw badRequest("One or more selected permissions are not supported.");
    }
    return permissions;
  }

  private void validateStaffPermissions(String role,List<String> permissions) {
    if (permissions.contains("STAFF_MANAGE") && !"ADMIN".equals(role)) {
      throw badRequest("Only administrator identities may receive staff-management permission.");
    }
    if ("ADMIN".equals(role) && permissions.size()!=permissionCatalogSize()) {
      throw badRequest("Administrator accounts must retain all permissions.");
    }
  }

  private List<String> defaultPermissions(String role) {
    return switch(role) {
      case "ADMIN" -> jdbc.queryForList(
          "select permission_key from staff_permission_catalog order by permission_key",String.class);
      case "MANAGER" -> List.of("CONTENT_VIEW","CONTENT_CREATE","CONTENT_EDIT","CONTENT_SUBMIT",
          "CONTENT_REVIEW","AUDIT_VIEW");
      case "MODERATOR" -> List.of("CONTENT_VIEW","CONTENT_REVIEW");
      case "CONTENT_AUTHOR" -> List.of("CONTENT_VIEW","CONTENT_CREATE","CONTENT_EDIT","CONTENT_SUBMIT");
      case "CONTENT_REVIEWER" -> List.of("CONTENT_VIEW","CONTENT_REVIEW");
      case "PUBLISHER" -> List.of("CONTENT_VIEW","CONTENT_PUBLISH");
      default -> List.of("CONTENT_VIEW");
    };
  }

  private int permissionCatalogSize() {
    Integer count=jdbc.queryForObject("select count(*) from staff_permission_catalog",Integer.class);
    return count==null?0:count;
  }

  private String requiredText(Object value,int maxLength,String label) {
    if (!(value instanceof String text) || text.trim().length()<2 || text.trim().length()>maxLength) {
      throw badRequest(label+" must contain between 2 and "+maxLength+" characters.");
    }
    return text.trim();
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }
}
