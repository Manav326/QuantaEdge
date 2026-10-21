package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

class AuthControllerGoogleConfigTest {
  @Test
  void exposesConfiguredPublicClientIdWithoutDisclosingASecret() {
    GoogleIdentityService identity = mock(GoogleIdentityService.class);
    when(identity.publicClientId()).thenReturn("web-client.apps.googleusercontent.com");
    AuthController controller = new AuthController(null, null, identity, true);

    ResponseEntity<Map<String,Object>> response = controller.googleSignInConfig();
    assertEquals(200, response.getStatusCode().value());
    assertEquals("web-client.apps.googleusercontent.com", response.getBody().get("clientId"));
    assertTrue((Boolean) response.getBody().get("configured"));
    assertEquals(CacheControl.noStore().getHeaderValue(), response.getHeaders().getCacheControl());
  }

  @Test
  void advertisesMissingConfigurationWithoutBreakingOtherAuthentication() {
    GoogleIdentityService identity = mock(GoogleIdentityService.class);
    when(identity.publicClientId()).thenReturn("");
    AuthController controller = new AuthController(null, null, identity, true);

    Map<String,Object> result = controller.googleSignInConfig().getBody();
    assertFalse((Boolean) result.get("configured"));
    assertEquals("", result.get("clientId"));
  }

  @Test
  void safelyReportsMissingConfigurationWhenIdentityServiceIsAbsent() {
    AuthController controller = new AuthController(null, null, true);
    Map<String,Object> result = controller.googleSignInConfig().getBody();
    assertFalse((Boolean) result.get("configured"));
    assertEquals("", result.get("clientId"));
  }
  @Test
  void parentLearningAccessUsesParentSessionAndSelectedInstitution() {
    AuthService auth = mock(AuthService.class);
    InstitutionTenantContextService tenantContexts = mock(InstitutionTenantContextService.class);
    InstitutionMembershipService memberships = mock(InstitutionMembershipService.class);
    InstitutionTenantContext tenant = new InstitutionTenantContext(31L, "SCHOOL-A", "School A");
    AuthContext parent = new AuthContext(501L, null, "PARENT", "Parent");
    when(tenantContexts.requireSelector("SCHOOL-A")).thenReturn(tenant);
    when(auth.hasParentPersonalLearningAccess(501L, 31L)).thenReturn(true);
    AuthController controller = new AuthController(auth, null, null, tenantContexts, memberships, true);

    Map<String,Object> result = controller.parentPersonalLearningAccess(parent, "SCHOOL-A");

    assertEquals(Boolean.TRUE, result.get("available"));
    verify(memberships).requireExistingMembership(parent, tenant);
    verify(auth).hasParentPersonalLearningAccess(501L, 31L);
  }

  @Test
  void studentSessionCannotInspectParentPersonalLearningEligibility() {
    AuthService auth = mock(AuthService.class);
    InstitutionTenantContextService tenantContexts = mock(InstitutionTenantContextService.class);
    InstitutionMembershipService memberships = mock(InstitutionMembershipService.class);
    AuthContext student = new AuthContext(null, 801L, "STUDENT", "Learner");
    AuthController controller = new AuthController(auth, null, null, tenantContexts, memberships, true);

    assertThrows(SecurityException.class,
        () -> controller.parentPersonalLearningAccess(student, "SCHOOL-A"));
    verifyNoInteractions(auth, tenantContexts, memberships);
  }

}
