package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
    assertNull(chain.getRequest());
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
    assertNull(chain.getRequest());
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
    assertTrue(response.getContentAsString().contains("INVALID_INSTITUTION_CODE"));
    assertNull(chain.getRequest());
    verifyNoInteractions(jdbc);
  }

  @Test
  void corsPreflightDoesNotRequireWorkspaceCookie() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/hrms/access-users");
    request.addHeader("Origin", "http://localhost:3002");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    verifyNoInteractions(auth);
    assertEquals(200, response.getStatus());
    assertEquals(request, chain.getRequest());
  }

  @Test
  void educationAdminRequestGetsConfiguredTenantAndIgnoresBrowserTenantSelector() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        Map.<String,Object>of("id", 92L, "tenant_code", "SCHOOL-B", "display_name", "School B")));
    InstitutionTenantContextService tenantService = new InstitutionTenantContextService(jdbc);
    AuthFilter filter = new AuthFilter(auth, audit, null, tenantService);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "GET", "/api/v1/admin/content/programs");
    request.addHeader(AuthService.WORKSPACE_HEADER, "EDUCATION_ADMIN");
    request.addHeader("Origin", "http://localhost:3001");
    // A client cannot redirect Education Admin course retrieval to another institution.
    request.addHeader(InstitutionTenantContextService.HEADER_NAME, "SCHOOL-B");
    request.setCookies(new Cookie(
        AuthService.sessionCookieForWorkspace("EDUCATION_ADMIN"), "admin-session"));
    when(auth.currentForWorkspace("admin-session", "EDUCATION_ADMIN"))
        .thenReturn(new AuthContext(null, null, 999L, "ADMIN", "QE Admin", "EDUCATION_ADMIN"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals(request, chain.getRequest());
    assertEquals(new InstitutionTenantContext(92L, "SCHOOL-B", "School B"),
        request.getAttribute(InstitutionTenantContextService.REQUEST_ATTRIBUTE));
  }

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
    assertTrue(response.getContentAsString().contains("INSTITUTION_CONTEXT_REQUIRED"));
    assertNull(chain.getRequest());
  }

  @Test
  void activeTenantContextDoesNotAuthorizeANonMember() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        Map.<String,Object>of("id", 91L, "tenant_code", "SCHOOL-A", "display_name", "School A")));
    when(jdbc.queryForObject(contains("institution_membership"), eq(Boolean.class), any(Object[].class)))
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
    assertTrue(response.getContentAsString().contains("INSTITUTION_MEMBERSHIP_REQUIRED"));
    assertNull(chain.getRequest());
  }
  @Test
  void studentAccountSignupOtpIsAllowedInStudentWorkspace() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/v1/auth/account-signup/request-otp");
    request.addHeader(AuthService.WORKSPACE_HEADER, "STUDENT");
    request.addHeader("Origin", "http://localhost:3000");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals(request, chain.getRequest());
    verifyNoInteractions(auth);
  }

  @Test
  void parentPersonalLearningSessionIsAllowedInParentWorkspace() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/v1/auth/my-learning-session");
    request.addHeader(AuthService.WORKSPACE_HEADER, "PARENT");
    request.addHeader("Origin", "http://localhost:3000");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals(request, chain.getRequest());
    verifyNoInteractions(auth);
  }

  @Test
  void parentPersonalLearningAccessLookupIsAllowedOnlyInParentWorkspace() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest parentRequest = new MockHttpServletRequest(
        "GET", "/api/v1/auth/my-learning-access");
    parentRequest.addHeader(AuthService.WORKSPACE_HEADER, "PARENT");
    parentRequest.addHeader("Origin", "http://localhost:3000");
    MockHttpServletResponse parentResponse = new MockHttpServletResponse();
    MockFilterChain parentChain = new MockFilterChain();

    filter.doFilter(parentRequest, parentResponse, parentChain);

    assertEquals(200, parentResponse.getStatus());
    assertEquals(parentRequest, parentChain.getRequest());
    verifyNoInteractions(auth);

    MockHttpServletRequest studentRequest = new MockHttpServletRequest(
        "GET", "/api/v1/auth/my-learning-access");
    studentRequest.addHeader(AuthService.WORKSPACE_HEADER, "STUDENT");
    studentRequest.addHeader("Origin", "http://localhost:3000");
    MockHttpServletResponse studentResponse = new MockHttpServletResponse();
    MockFilterChain studentChain = new MockFilterChain();

    filter.doFilter(studentRequest, studentResponse, studentChain);

    assertEquals(403, studentResponse.getStatus());
    assertTrue(studentResponse.getContentAsString().contains("AUTH_OPERATION_SCOPE_MISMATCH"));
    assertNull(studentChain.getRequest());
    verifyNoInteractions(auth);
  }

  @Test
  void parentPersonalLearningSessionCannotBeInvokedFromStudentWorkspace() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    AuthFilter filter = new AuthFilter(auth, audit);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/v1/auth/my-learning-session");
    request.addHeader(AuthService.WORKSPACE_HEADER, "STUDENT");
    request.addHeader("Origin", "http://localhost:3000");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("AUTH_OPERATION_SCOPE_MISMATCH"));
    assertNull(chain.getRequest());
    verifyNoInteractions(auth);
  }


  @Test
  void archivedChildCanReachRestoreOnlyThroughItsPreviouslyLinkedInstitution() throws Exception {
    AuthService auth = mock(AuthService.class);
    StaffAuditService audit = mock(StaffAuditService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        Map.<String,Object>of("id", 17L, "tenant_code", "SCHOOL-A", "display_name", "School A")));
    when(jdbc.queryForObject(contains("institution_user_membership"), eq(Boolean.class), any(Object[].class)))
        .thenReturn(true);
    when(jdbc.queryForObject(contains("gs.active=false"), eq(Boolean.class), any(Object[].class)))
        .thenReturn(true);
    InstitutionTenantContextService tenantService = new InstitutionTenantContextService(jdbc);
    AuthFilter filter = new AuthFilter(auth, audit, null, tenantService);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/v1/guardians/children/70/restore");
    request.addHeader(AuthService.WORKSPACE_HEADER, "PARENT");
    request.addHeader(InstitutionTenantContextService.HEADER_NAME, "SCHOOL-A");
    request.setCookies(new Cookie(AuthService.sessionCookieForWorkspace("PARENT"), "parent-session"));
    when(auth.currentForWorkspace("parent-session", "PARENT"))
        .thenReturn(new AuthContext(90L, null, "PARENT", "Parent"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals(request, chain.getRequest());
    assertTrue(request.getAttribute(InstitutionTenantContextService.REQUEST_ATTRIBUTE) instanceof InstitutionTenantContext);
    org.mockito.Mockito.verify(jdbc).queryForObject(
        contains("gs.active=false"), eq(Boolean.class), any(Object[].class));
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).queryForObject(
        contains("gs.active=true"), eq(Boolean.class), any(Object[].class));
  }
}
