package com.quantaedge.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StaffAuditService {
  private static final Logger LOGGER = LoggerFactory.getLogger(StaffAuditService.class);
  private final JdbcTemplate jdbc;

  public StaffAuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void recordRequest(AuthContext actor, HttpServletRequest request, HttpServletResponse response) {
    if (actor == null || !actor.isEmployee() || !request.getRequestURI().startsWith("/api/v1/admin/")) return;
    try {
      String path = request.getRequestURI();
      if (path.length() > 500) path = path.substring(0, 500);
      String userAgent = request.getHeader("User-Agent");
      if (userAgent != null && userAgent.length() > 500) userAgent = userAgent.substring(0, 500);
      String address = request.getRemoteAddr();
      if (address != null && address.length() > 80) address = address.substring(0, 80);
      jdbc.update("""
          insert into staff_audit_log(
            actor_staff_id, actor_role, http_method, request_path,
            response_status, remote_address, user_agent
          ) values (?,?,?,?,?,?,?)
          """,
          actor.staffId(),
          actor.role() == null ? "UNKNOWN" : actor.role(),
          request.getMethod(),
          path,
          response.getStatus(),
          address,
          userAgent);
    } catch (RuntimeException ex) {
      LOGGER.error("Could not append staff audit trail entry for {}", request.getRequestURI(), ex);
    }
  }
}
