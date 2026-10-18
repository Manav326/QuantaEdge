package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
  void corsPreflightDoesNotRequireWorkspaceCookie() throws Exception {
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
