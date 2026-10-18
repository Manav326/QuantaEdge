package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
}
