package com.quantaedge.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationService {
  private final JdbcTemplate jdbc;

  public AuthorizationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public AuthContext requireAuth(AuthContext context) {
    if (context == null || !context.isAuthenticated()) {
      throw new SecurityException("Authentication required");
    }
    return context;
  }

  public AuthContext requireStudent(AuthContext context) {
    context = requireAuth(context);
    if (!context.isStudent()) throw new SecurityException("Student access required");
    return context;
  }

  public AuthContext requireParent(AuthContext context) {
    context = requireAuth(context);
    if (!context.isParent() && !context.isAdmin()) {
      throw new SecurityException("Parent access required");
    }
    return context;
  }

  public AuthContext requireAdmin(AuthContext context) {
    context = requireAuth(context);
    if (!context.isAdmin()) throw new SecurityException("Administrator access required");
    return context;
  }

  public boolean hasPermission(AuthContext context, String permission) {
    if (context == null || !context.isAuthenticated() || permission == null || permission.isBlank()) return false;
    boolean hrmsPermission = permission.startsWith("HR_")
        || "HR_SELF_SERVICE_VIEW".equals(permission)
        || "TEACHING_ASSIGNMENT_VIEW".equals(permission)
        || "CLASS_SCHEDULE_POLICY_MANAGE".equals(permission)
        || "QE_PLATFORM_OWNER".equals(permission);
    // Permissions are authoritative for every staff identity, including role ADMIN.
    // A role may unlock a route intended only for administrators, but it never silently
    // grants unrelated content, publication, resource, assessment or HRMS capabilities.
    if (hrmsPermission && !"HRMS".equals(AuthService.normalizeWorkspace(context.workspace()))) return false;
    if (!context.isEmployee()) return false;
    // Role-based permissions are evaluated live on every request; HRMS-only keys remain
    // scoped even when one staff identity legitimately has roles in both applications.
    String workspace = "HRMS".equals(AuthService.normalizeWorkspace(context.workspace())) ? "HRMS" : "EDUCATION_ADMIN";
    Boolean granted = jdbc.queryForObject("""
        select exists (
          select 1
          from staff_account s
          join staff_permission_catalog p on p.permission_key = ?
          where s.id = ? and s.active = true
            and (
              exists (select 1 from staff_permission_grant g
                      where g.staff_id=s.id and g.permission_key=p.permission_key
                        and g.workspace_key=?)
              or exists (select 1 from staff_additional_role ar
                         join staff_role_permission rp on rp.role_code=ar.role_code
                         where ar.staff_id=s.id and rp.permission_key=p.permission_key
                           and rp.workspace_key=?)
            )
        )
        """, Boolean.class, permission, context.staffId(), workspace, workspace);
    return Boolean.TRUE.equals(granted);
  }

  public AuthContext requirePermission(AuthContext context, String permission) {
    context = requireAuth(context);
    if (!hasPermission(context, permission)) {
      throw new SecurityException("Permission required: " + permission);
    }
    return context;
  }
}
