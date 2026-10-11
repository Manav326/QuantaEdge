package com.quantaedge.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthFilter.class);
  private final AuthService auth;
  private final StaffAuditService staffAudit;

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
      String token = null;
      if (workspace != null) {
        String scopedCookie = AuthService.sessionCookieForWorkspace(workspace);
        token = cookieValue(request, scopedCookie);
        if (token != null) context = auth.currentForWorkspace(token, workspace);
        // Existing browsers can have the old shared cookie. Accept it only when the
        // session record itself belongs to this workspace.
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
      if (context != null) request.setAttribute("authContext", context);
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
