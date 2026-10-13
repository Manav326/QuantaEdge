package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * HRMS-scoped employee login and role management. This controller deliberately uses
 * HRMS permissions rather than Education Admin's global ADMIN shortcut.
 */
@RestController
@RequestMapping("/api/v1/hrms/access-users")
public class HrmsAccessController {
  private static final Set<String> ASSIGNABLE_ROLES =
      Set.of("EMPLOYEE", "HR", "ACCOUNTANT", "PEOPLE_MANAGER");
  private static final Set<String> SESSION_ELIGIBLE_STATUSES =
      Set.of("ACTIVE", "ON_LEAVE", "NOTICE_PERIOD");

  private final JdbcTemplate jdbc;
  private final AuthService auth;
  private final AuthorizationService authorization;
  private final StaffAuditService audit;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

  public HrmsAccessController(JdbcTemplate jdbc, AuthService auth,
      AuthorizationService authorization, StaffAuditService audit) {
    this.jdbc = jdbc;
    this.auth = auth;
    this.authorization = authorization;
    this.audit = audit;
  }

  @GetMapping
  public List<Map<String,Object>> listAccessUsers(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireAccessManager(context);
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select e.id as employee_id, e.employee_number, e.display_name, e.department_id,
               e.designation_id, e.employment_status, e.hrms_access_enabled,
               s.id as staff_id, s.username, s.active as identity_active, s.role as identity_role,
               s.last_login_at
        from hr_employee e
        left join staff_account s on s.id=e.linked_staff_account_id
        order by lower(e.display_name), e.id
        """);
    for (Map<String,Object> row : rows) {
      Object staffValue = row.get("staff_id");
      if (staffValue != null) {
        long staffId = ((Number) staffValue).longValue();
        row.put("roles", assignedRoles(staffId));
        row.put("permissions", auth.permissionsForStaff(staffId, "HRMS"));
      } else {
        row.put("roles", List.of("EMPLOYEE"));
        row.put("permissions", List.of());
      }
    }
    return rows;
  }

  /**
   * Creates or resets an employee's HRMS username and initial password. First login
   * always requires the employee to choose their own strong password.
   */
  @PostMapping("/{employeeId}")
  @Transactional
  public Map<String,Object> provisionAccess(
      @PathVariable long employeeId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireAccessManager(context);
    Map<String,Object> employee = employeeForUpdate(employeeId);
    String status = String.valueOf(employee.get("employment_status"));
    if (!SESSION_ELIGIBLE_STATUSES.contains(status)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "HRMS login access can only be enabled for an active employee.");
    }
    String username = normalizeUsername(body.get("username"));
    String password = body.get("initialPassword") == null ? "" : String.valueOf(body.get("initialPassword"));
    if (password.length() < 12 || password.length() > 128) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The initial password must contain between 12 and 128 characters.");
    }
    List<String> roles = readRoles(body.get("roles"));
    Long staffId = employee.get("linked_staff_account_id") == null
        ? null : ((Number) employee.get("linked_staff_account_id")).longValue();
    if (staffId != null && staffId.equals(context.staffId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own HRMS credentials or roles.");
    }

    try {
      if (staffId == null) {
        staffId = jdbc.queryForObject("""
            insert into staff_account(
              public_id, mobile_e164, display_name, role, active, username,
              password_hash, must_change_password, created_by_staff_id
            ) values (?,?,?,'EMPLOYEE',true,?,?,true,?) returning id
            """, Long.class, UUID.randomUUID(), null, String.valueOf(employee.get("display_name")),
            username, passwordEncoder.encode(password), context.staffId());
        if (staffId == null) throw new IllegalStateException("Could not create the employee login identity.");
        jdbc.update("""
            update hr_employee
            set linked_staff_account_id=?, hrms_access_enabled=true, updated_at=now()
            where id=?
            """, staffId, employeeId);
      } else {
        Map<String,Object> linked = staffIdentity(staffId);
        if ("hrms_admin".equalsIgnoreCase(String.valueOf(linked.get("username")))) {
          throw new ResponseStatusException(HttpStatus.FORBIDDEN,
              "The initial People Operations Super Admin identity cannot be re-provisioned here.");
        }
        if (!Boolean.TRUE.equals(linked.get("active"))) {
          throw new ResponseStatusException(HttpStatus.CONFLICT,
              "The linked staff identity is suspended. Ask an authorised administrator to review it first.");
        }
        jdbc.update("""
            update staff_account
            set username=?, password_hash=?, must_change_password=true,
                password_changed_at=null, updated_at=now()
            where id=? and active=true
            """, username, passwordEncoder.encode(password), staffId);
        jdbc.update("update hr_employee set hrms_access_enabled=true, updated_at=now() where id=?", employeeId);
      }
      replaceHrmsRoles(staffId, roles);
      jdbc.update("""
          update auth_session set revoked_at=now()
          where staff_id=? and workspace_key='HRMS' and revoked_at is null
          """, staffId);
    } catch (DataIntegrityViolationException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "That username is already assigned, or this employee is already linked to another login.");
    }

    audit.recordAction(context, "/api/v1/hrms/access-users/" + employeeId,
        "Provisioned HRMS credentials and roles " + String.join(", ", roles)
            + "; existing HRMS sessions were revoked.");
    return accessUser(employeeId);
  }

  @PutMapping("/{employeeId}/roles")
  @Transactional
  public Map<String,Object> updateRoles(
      @PathVariable long employeeId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireAccessManager(context);
    Map<String,Object> employee = employeeForUpdate(employeeId);
    Long staffId = employee.get("linked_staff_account_id") == null
        ? null : ((Number) employee.get("linked_staff_account_id")).longValue();
    if (staffId == null || !Boolean.TRUE.equals(employee.get("hrms_access_enabled"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Enable HRMS login access before assigning roles.");
    }
    if (staffId.equals(context.staffId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own HRMS roles.");
    }
    Map<String,Object> linked = staffIdentity(staffId);
    if ("hrms_admin".equalsIgnoreCase(String.valueOf(linked.get("username")))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "The initial People Operations Super Admin roles are managed through the protected system bootstrap.");
    }
    List<String> roles = readRoles(body.get("roles"));
    replaceHrmsRoles(staffId, roles);
    jdbc.update("""
        update auth_session set revoked_at=now()
        where staff_id=? and workspace_key='HRMS' and revoked_at is null
        """, staffId);
    audit.recordAction(context, "/api/v1/hrms/access-users/" + employeeId + "/roles",
        "Updated HRMS role assignments to " + String.join(", ", roles)
            + "; active HRMS sessions were revoked.");
    return accessUser(employeeId);
  }

  @PutMapping("/{employeeId}/status")
  @Transactional
  public Map<String,Object> updateAccessStatus(
      @PathVariable long employeeId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireAccessManager(context);
    Map<String,Object> employee = employeeForUpdate(employeeId);
    Long staffId = employee.get("linked_staff_account_id") == null
        ? null : ((Number) employee.get("linked_staff_account_id")).longValue();
    if (staffId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This employee has no linked login identity yet.");
    }
    if (staffId.equals(context.staffId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot disable or re-enable your own HRMS access.");
    }
    Map<String,Object> linked = staffIdentity(staffId);
    if ("hrms_admin".equalsIgnoreCase(String.valueOf(linked.get("username")))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "The initial People Operations Super Admin access cannot be disabled through this screen.");
    }
    Object rawEnabled = body.get("enabled");
    if (!(rawEnabled instanceof Boolean enabled)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose whether HRMS access should be enabled.");
    }
    if (enabled && !SESSION_ELIGIBLE_STATUSES.contains(String.valueOf(employee.get("employment_status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "HRMS access cannot be enabled for a draft, inactive or separated employee.");
    }
    jdbc.update("update hr_employee set hrms_access_enabled=?, updated_at=now() where id=?", enabled, employeeId);
    if (!enabled) {
      jdbc.update("""
          update auth_session set revoked_at=now()
          where staff_id=? and workspace_key='HRMS' and revoked_at is null
          """, staffId);
    }
    audit.recordAction(context, "/api/v1/hrms/access-users/" + employeeId + "/status",
        enabled ? "Enabled HRMS access." : "Disabled HRMS access and revoked active HRMS sessions.");
    return accessUser(employeeId);
  }

  private AuthContext requireAccessManager(AuthContext context) {
    return authorization.requirePermission(context, "HR_ACCESS_MANAGE");
  }

  private Map<String,Object> employeeForUpdate(long employeeId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select id, display_name, employment_status, linked_staff_account_id, hrms_access_enabled
        from hr_employee where id=? for update
        """, employeeId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee record not found.");
    return rows.getFirst();
  }

  private Map<String,Object> staffIdentity(long staffId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select id, username, active from staff_account where id=?
        """, staffId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Linked login identity no longer exists.");
    return rows.getFirst();
  }

  private String normalizeUsername(Object raw) {
    String username = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
    if (!username.matches("^[a-z0-9][a-z0-9._-]{2,63}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Username must be 3–64 characters and use letters, numbers, dots, underscores or hyphens.");
    }
    if ("hrms_admin".equals(username)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is reserved for the People Operations Super Admin.");
    }
    return username;
  }

  private List<String> readRoles(Object raw) {
    if (!(raw instanceof List<?> requested)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select one or more HRMS roles.");
    }
    Set<String> roles = new LinkedHashSet<>();
    for (Object value : requested) {
      if (!(value instanceof String role)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each role must be a role name.");
      }
      String normalized = role.trim().toUpperCase(Locale.ROOT);
      if (!ASSIGNABLE_ROLES.contains(normalized)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "HRMS supports Employee, HR, Accountant, and People Manager role assignments only.");
      }
      roles.add(normalized);
    }
    if (roles.isEmpty()) roles.add("EMPLOYEE");
    return new ArrayList<>(roles);
  }

  private void replaceHrmsRoles(long staffId, List<String> roles) {
    jdbc.update("""
        delete from staff_additional_role
        where staff_id=? and role_code in ('EMPLOYEE','HR','ACCOUNTANT','PEOPLE_MANAGER')
        """, staffId);
    for (String role : roles) {
      jdbc.update("""
          insert into staff_additional_role(staff_id,role_code)
          values (?,?)
          on conflict(staff_id,role_code) do nothing
          """, staffId, role);
    }
  }

  private List<String> assignedRoles(long staffId) {
    List<String> roles = jdbc.queryForList("""
        select role_code from staff_additional_role
        where staff_id=? and role_code in ('EMPLOYEE','HR','ACCOUNTANT','PEOPLE_MANAGER')
        order by role_code
        """, String.class, staffId);
    return roles.isEmpty() ? List.of("EMPLOYEE") : roles;
  }

  private Map<String,Object> accessUser(long employeeId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select e.id as employee_id, e.employee_number, e.display_name, e.employment_status,
               e.hrms_access_enabled, s.id as staff_id, s.username, s.active as identity_active,
               s.role as identity_role, s.last_login_at
        from hr_employee e
        left join staff_account s on s.id=e.linked_staff_account_id
        where e.id=?
        """, employeeId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee record not found.");
    Map<String,Object> row = rows.getFirst();
    if (row.get("staff_id") != null) {
      long staffId = ((Number) row.get("staff_id")).longValue();
      row.put("roles", assignedRoles(staffId));
      row.put("permissions", auth.permissionsForStaff(staffId, "HRMS"));
    } else {
      row.put("roles", List.of("EMPLOYEE"));
      row.put("permissions", List.of());
    }
    return row;
  }
}
