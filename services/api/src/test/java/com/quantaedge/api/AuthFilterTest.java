package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthFilterTest {
  @Test
  void educationAdminOriginCannotCallHrmsEndpoints() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hrms/access-users");
    request.addHeader(AuthService.WORKSPACE_HEADER, "EDUCATION_ADMIN");
    request.addHeader("Origin", "http://localhost:3001");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
    verifyNoInteractions(auth);
  }

  @Test
  void hrmsOriginCannotSelectEducationAdminWorkspace() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/staff");
    request.addHeader(AuthService.WORKSPACE_HEADER, "EDUCATION_ADMIN");
    request.addHeader("Origin", "http://localhost:3002");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
    verifyNoInteractions(auth);
  }

  @Test
  void hrmsWorkspaceCannotInvokeParentLogin() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/parent-login");
    request.addHeader(AuthService.WORKSPACE_HEADER, "HRMS");
    request.addHeader("Origin", "http://localhost:3002");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
    verifyNoInteractions(auth);
  }

  @Test
  void parentWorkspaceCannotInvokeHrmsLogin() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/hrms-login");
    request.addHeader(AuthService.WORKSPACE_HEADER, "PARENT");
    request.addHeader("Origin", "http://localhost:3000");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(403, response.getStatus());
    verifyNoInteractions(auth);
  }

  @Test
  void oversizedBrandingUploadIsRejectedBeforeMultipartParsing() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    AuthFilter filter = new AuthFilter(auth, audit, authorization);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/v1/hrms/platform/institutions/42/branding/assets/logo");
    request.addHeader(AuthService.WORKSPACE_HEADER, "HRMS");
    request.addHeader("Origin", "http://localhost:3002");
    request.setContent(new byte[2_300_000]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(413, response.getStatus());
    assertEquals(null, chain.getRequest());
    verifyNoInteractions(auth);
  }

  @Test
  void anonymousBrandingUploadIsRejectedBeforeMultipartParsing() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    AuthFilter filter = new AuthFilter(auth, audit, authorization);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/v1/hrms/platform/institutions/42/branding/assets/app-icon");
    request.addHeader(AuthService.WORKSPACE_HEADER, "HRMS");
    request.addHeader("Origin", "http://localhost:3002");
    request.setContent(new byte[128]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(403, response.getStatus());
    assertEquals(null, chain.getRequest());
    verifyNoInteractions(auth);
  }


  @Test
  void malformedInstitutionSelectorIsRejectedBeforeTheControllerRuns() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    InstitutionTenantContextService tenantService = new InstitutionTenantContextService(jdbc);
    AuthFilter filter = new AuthFilter(auth, audit, null, tenantService);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/students/me/programs");
    request.addHeader(AuthService.WORKSPACE_HEADER, "STUDENT");
    request.addHeader(InstitutionTenantContextService.HEADER_NAME, "bad code");
    request.setCookies(new Cookie(AuthService.sessionCookieForWorkspace("STUDENT"), "valid-session"));
    when(auth.currentForWorkspace("valid-session", "STUDENT"))
        .thenReturn(new AuthContext(null, 42L, null, "STUDENT", "Learner", "STUDENT"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(400, response.getStatus());
    assertEquals("INVALID_INSTITUTION_CODE", response.getContentAsString().contains("INVALID_INSTITUTION_CODE")
        ? "INVALID_INSTITUTION_CODE" : "missing error code");
    assertEquals(null, chain.getRequest());
  }

  @Test
  void corsPreflightDoesNotRequireWorkspaceCookie() throws Exception {

  @Test
  void tenantScopedCourseCatalogueRejectsMissingInstitutionContext() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    InstitutionTenantContextService tenantService = new InstitutionTenantContextService(mock(JdbcTemplate.class));
    AuthFilter filter = new AuthFilter(auth, audit, null, tenantService);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/students/me/programs");
    request.addHeader(AuthService.WORKSPACE_HEADER, "STUDENT");
    request.setCookies(new Cookie(AuthService.sessionCookieForWorkspace("STUDENT"), "valid-session"));
    when(auth.currentForWorkspace("valid-session", "STUDENT"))
        .thenReturn(new AuthContext(null, 42L, null, "STUDENT", "Learner", "STUDENT"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(400, response.getStatus());
    assertEquals(true, response.getContentAsString().contains("INSTITUTION_CONTEXT_REQUIRED"));
    assertEquals(null, chain.getRequest());
  }

  @Test
  void activeTenantContextDoesNotAuthorizeANonMember() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(java.util.List.of(java.util.Map.<String,Object>of(
            "id", 91L, "tenant_code", "SCHOOL-A", "display_name", "School A")));
    when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("institution_membership"),
        org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenReturn(false);
    InstitutionTenantContextService tenantService = new InstitutionTenantContextService(jdbc);
    AuthFilter filter = new AuthFilter(auth, audit, null, tenantService);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/students/me/programs");
    request.addHeader(AuthService.WORKSPACE_HEADER, "STUDENT");
    request.addHeader(InstitutionTenantContextService.HEADER_NAME, "school-a");
    request.setCookies(new Cookie(AuthService.sessionCookieForWorkspace("STUDENT"), "valid-session"));
    when(auth.currentForWorkspace("valid-session", "STUDENT"))
        .thenReturn(new AuthContext(null, 42L, null, "STUDENT", "Learner", "STUDENT"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(403, response.getStatus());
    assertEquals(true, response.getContentAsString().contains("INSTITUTION_MEMBERSHIP_REQUIRED"));
    assertEquals(null, chain.getRequest());
  }

    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/hrms/access-users");
    request.addHeader("Origin", "http://localhost:3002");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    verifyNoInteractions(auth);
    assertEquals(200, response.getStatus());
  }
}
