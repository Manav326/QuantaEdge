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
    if (context == null || !context.isAuthenticated()) return false;
    if (context.isAdmin()) return true;
    if (!context.isEmployee() || permission == null || permission.isBlank()) return false;
    Boolean granted = jdbc.queryForObject("""
        select exists (
          select 1
          from staff_permission_grant g
          join staff_account s on s.id = g.staff_id
          join staff_permission_catalog p on p.permission_key = g.permission_key
          where g.staff_id = ?
            and g.permission_key = ?
            and s.active = true
        )
        """, Boolean.class, context.staffId(), permission);
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
