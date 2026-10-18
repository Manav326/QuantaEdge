package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class InstitutionBrandingAssetControlTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private InstitutionAuthService institutionAuth;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AuthContext platformOwner =
      new AuthContext(null, null, 17L, "STAFF", "Platform owner", "HRMS");

  @BeforeEach
  void allowExplicitPlatformOwnerForControllerUnitTests() {
    when(authorization.requirePermission(platformOwner, "QE_PLATFORM_OWNER")).thenReturn(platformOwner);
  }

  @Test
  void ownerCanRemoveAnAssetAndRemovalInvalidatesTheBrandingVersion() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    when(jdbc.queryForMap("select logo_asset_key as asset_id from institution_branding where institution_id=?", 42L))
        .thenReturn(Map.of("asset_id", "existing-logo-asset"));
    when(jdbc.queryForObject("select version from institution_branding where institution_id=?", Long.class, 42L))
        .thenReturn(12L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    PlatformInstitutionController controller =
        new PlatformInstitutionController(jdbc, authorization, institutionAuth, objectMapper);
    Map<String,Object> result = controller.removeBrandingAsset(42L, "logo", platformOwner);

    assertEquals(true, result.get("removed"));
    assertEquals("LOGO", result.get("assetType"));
    assertEquals(12L, result.get("brandingVersion"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.atLeast(3)).update(sql.capture(), any(Object[].class));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("update institution_branding set logo_asset_key=null,version=version+1")
            && q.contains("logo_asset_key=?")));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("update institution_branding_asset set active=false")
            && q.contains("asset_type=? and asset_id=?")));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("BRANDING_CHANGED") && q.contains("'removed',true")));
    verify(authorization).requirePermission(platformOwner, "QE_PLATFORM_OWNER");
  }

  @Test
  void assetRemovalRejectsNonOwnerBeforeReadingInstitutionData() {
    when(authorization.requirePermission(null, "QE_PLATFORM_OWNER"))
        .thenThrow(new SecurityException("Permission required: QE_PLATFORM_OWNER"));
    PlatformInstitutionController controller =
        new PlatformInstitutionController(jdbc, authorization, institutionAuth, objectMapper);

    assertThrows(SecurityException.class, () -> controller.removeBrandingAsset(42L, "logo", null));
    verifyNoInteractions(jdbc);
  }

  @Test
  void publicAssetRouteServesOnlyAnActiveAssetAtTheExactCurrentBrandVersion() {
    byte[] bytes = new byte[] {1, 2, 3, 4};
    String digest = "a".repeat(64);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      Object[] args = invocation.getArgument(1);
      assertTrue(sql.contains("a.asset_id=case when ?='LOGO'"));
      assertTrue(sql.contains("a.asset_type=? and a.active=true"));
      assertTrue(sql.contains("t.tenant_code=? and t.status='ACTIVE' and b.version=?"));
      assertEquals("LOGO", args[0]);
      assertEquals("LOGO", args[1]);
      assertEquals("SCHOOL-A", args[2]);
      assertEquals(9L, args[3]);
      Map<String,Object> row = new LinkedHashMap<>();
      row.put("content_type", "image/png");
      row.put("image_bytes", bytes);
      row.put("content_sha256", digest);
      return List.of(row);
    });

    InstitutionTenantController controller = new InstitutionTenantController(
        institutionAuth, authorization, jdbc, objectMapper, true);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/institution/public/branding/SCHOOL-A/assets/logo");
    ResponseEntity<byte[]> response = controller.publicBrandingAsset("school-a", "logo", 9L, request);

    assertEquals(200, response.getStatusCode().value());
    assertArrayEquals(bytes, response.getBody());
    assertEquals("image/png", response.getHeaders().getContentType().toString());
    assertEquals("\"" + digest + "\"", response.getHeaders().getETag());
    assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    assertEquals(4L, response.getHeaders().getContentLength());
  }

  @Test
  void staleVersionDoesNotResolveAfterReplaceOrRemove() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    InstitutionTenantController controller = new InstitutionTenantController(
        institutionAuth, authorization, jdbc, objectMapper, true);

    ResponseEntity<byte[]> response = controller.publicBrandingAsset(
        "SCHOOL-A", "logo", 8L, new MockHttpServletRequest("GET", "/assets/logo"));

    assertEquals(404, response.getStatusCode().value());
    assertNull(response.getBody());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    verify(jdbc).queryForList(anyString(), any(Object[].class));
  }

  @Test
  void etagMatchReturnsNotModifiedWithoutReSendingImageBytes() {
    byte[] bytes = new byte[] {9, 8, 7};
    String digest = "b".repeat(64);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        Map.of("content_type", "image/jpeg", "image_bytes", bytes, "content_sha256", digest)));
    InstitutionTenantController controller = new InstitutionTenantController(
        institutionAuth, authorization, jdbc, objectMapper, true);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/logo");
    request.addHeader("If-None-Match", "\"" + digest + "\"");

    ResponseEntity<byte[]> response = controller.publicBrandingAsset(
        "SCHOOL-A", "logo", 2L, request);

    assertEquals(304, response.getStatusCode().value());
    assertNull(response.getBody());
    assertEquals("\"" + digest + "\"", response.getHeaders().getETag());
    assertFalse(response.getHeaders().containsKey("Content-Length"));
  }
}
