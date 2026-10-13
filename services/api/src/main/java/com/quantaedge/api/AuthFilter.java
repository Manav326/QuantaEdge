package com.quantaedge.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthFilter.class);
  private final AuthService auth;
  private final StaffAuditService staffAudit;

  @Value("${app.auth.workspace-origins.education-admin:http://localhost:3001}")
  private String educationAdminOrigins = "http://localhost:3001";
  @Value("${app.auth.workspace-origins.hrms:http://localhost:3002}")
  private String hrmsOrigins = "http://localhost:3002";
  @Value("${app.auth.workspace-origins.learning:http://localhost:3000}")
  private String learningOrigins = "http://localhost:3000";

  private static Set<String> parseOrigins(String raw) {
    if (raw == null || raw.isBlank()) return Set.of();
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

  public AuthFilter(AuthService auth, StaffAuditService staffAudit) {
    this.auth = auth;
    this.staffAudit = staffAudit;
  }

  private static String cookieValue(HttpServletRequest request, String name) {
    if (request.getCookies() == null) return null;
    for (Cookie cookie : request.getCookies()) {
      if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
        return cookie.getValue();
      }
    }
    return null;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String uri = request.getRequestURI();
    boolean trace = uri.startsWith("/api/v1/admin/lessons/")
        && uri.contains("/blocks/") && uri.endsWith("/asset");
    AuthContext context = null;
    if (trace) LOGGER.info("HTTP_DIAG auth-filter-enter uri={} hasCookies={}", uri, request.getCookies() != null);
    try {
      String workspace = AuthService.normalizeWorkspace(request.getHeader(AuthService.WORKSPACE_HEADER));
      String origin = request.getHeader("Origin");
      if (origin != null && !origin.isBlank()) {
        Set<String> educationOrigins = parseOrigins(educationAdminOrigins);
        Set<String> hrmsOriginSet = parseOrigins(hrmsOrigins);
        Set<String> learningOriginSet = parseOrigins(learningOrigins);
        boolean expectedWorkspace =
            (educationOrigins.contains(origin) && ("STAFF".equals(workspace) || "EDUCATION_ADMIN".equals(workspace)))
            || (hrmsOriginSet.contains(origin) && "HRMS".equals(workspace))
            || (learningOriginSet.contains(origin)
                && ("LEARNING".equals(workspace) || "PARENT".equals(workspace) || "STUDENT".equals(workspace)));
        boolean registeredOrigin = educationOrigins.contains(origin)
            || hrmsOriginSet.contains(origin) || learningOriginSet.contains(origin);
        if (!registeredOrigin || !expectedWorkspace) {
          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
          response.setCharacterEncoding("UTF-8");
          response.setContentType("application/json");
          response.getWriter().write("{\"error\":\"WORKSPACE_ORIGIN_MISMATCH\",\"message\":\"This browser origin is not authorised for the requested application workspace.\"}");
          return;
        }
      }
      String path = request.getRequestURI();
      boolean hrmsEndpoint = path.equals("/api/v1/hr")
          || path.startsWith("/api/v1/hr/")
          || path.startsWith("/api/v1/hrms/");
      boolean authEndpoint = path.startsWith("/api/v1/auth/");
      if (hrmsEndpoint && !"HRMS".equals(workspace)) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"WORKSPACE_MISMATCH\",\"message\":\"HRMS endpoints require an HRMS-scoped session.\"}");
        return;
      }
      if ("HRMS".equals(workspace) && path.startsWith("/api/v1/") && !hrmsEndpoint && !authEndpoint) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"WORKSPACE_MISMATCH\",\"message\":\"HRMS sessions cannot access Educational Admin or learning endpoints.\"}");
        return;
      }
      String token = null;
      if (workspace != null) {
        String scopedCookie = AuthService.sessionCookieForWorkspace(workspace);
        token = cookieValue(request, scopedCookie);
        if (token != null) context = auth.currentForWorkspace(token, workspace);
        // Existing browsers can have the old shared cookie. Accept it only when the
        // session record itself belongs to this workspace.
        if (context == null && ("PARENT".equals(workspace) || "STUDENT".equals(workspace))) {
          // Migrate older browsers/apps that still hold the former shared learner cookie.
          // The AuthService workspace predicate verifies the stored parent/student identity,
          // so this compatibility path never turns a parent cookie into a student session.
          String legacyLearnerToken = cookieValue(request, AuthService.LEARNING_COOKIE);
          if (legacyLearnerToken != null) {
            context = auth.currentForWorkspace(legacyLearnerToken, workspace);
            if (context != null) token = legacyLearnerToken;
          }
        }
        if (context == null) {
          String legacyToken = cookieValue(request, AuthService.COOKIE);
          if (legacyToken != null) {
            context = auth.currentForWorkspace(legacyToken, workspace);
            if (context != null) token = legacyToken;
          }
        }
      } else {
        token = cookieValue(request, AuthService.COOKIE);
        if (token != null) context = auth.current(token);
        // Compatibility for non-browser API clients that do not yet send the workspace header.
        if (context == null) {
          String staffToken = cookieValue(request, AuthService.STAFF_COOKIE);
          if (staffToken != null) context = auth.currentForWorkspace(staffToken, "STAFF");
        }
        if (context == null) {
          String learningToken = cookieValue(request, AuthService.LEARNING_COOKIE);
          if (learningToken != null) context = auth.currentForWorkspace(learningToken, "LEARNING");
        }
      }
      if (trace) LOGGER.info("HTTP_DIAG auth-filter-cookie-found={} workspace={} context-present={}",
          token != null, workspace, context != null);
      if (context != null) {
        request.setAttribute("authContext", context);
        request.setAttribute("authWorkspace", workspace);
        if ("HRMS".equals(workspace) && context.staffId() != null
            && auth.staffMustChangePassword(context.staffId())) {
          boolean allowedDuringPasswordRotation = java.util.Set.of(
              "/api/v1/auth/me",
              "/api/v1/auth/logout",
              "/api/v1/auth/refresh",
              "/api/v1/auth/hrms/change-password"
          ).contains(uri);
          if (uri.startsWith("/api/v1/") && !allowedDuringPasswordRotation) {
            response.setStatus(423);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Change the initial HRMS password before continuing.\"}");
            return;
          }
        }
      }
      chain.doFilter(request, response);
      if (trace) LOGGER.info("HTTP_DIAG auth-filter-chain-returned status={} committed={}", response.getStatus(), response.isCommitted());
    } catch (IOException | ServletException | RuntimeException ex) {
      if (trace) LOGGER.error("HTTP_DIAG auth-filter-failed uri={} status={}", uri, response.getStatus(), ex);
      throw ex;
    } finally {
      staffAudit.recordRequest(context, request, response);
      if (trace) LOGGER.info("HTTP_DIAG auth-filter-exit status={} committed={}", response.getStatus(), response.isCommitted());
    }
  }
}
