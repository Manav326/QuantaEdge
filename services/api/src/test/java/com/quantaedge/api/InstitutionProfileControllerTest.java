package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class InstitutionProfileControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private InstitutionAuthService institutionAuth;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AuthContext platformOwner =
      new AuthContext(null, null, 17L, "STAFF", "Platform owner", "HRMS");

  @Test
  void ownerCanUpdateProfileAndKeepDefaultBrandNameInSync() {
    when(authorization.requirePermission(platformOwner, "QE_PLATFORM_OWNER")).thenReturn(platformOwner);
    Map<String,Object> current = new LinkedHashMap<>();
    current.put("display_name", "Old School");
    current.put("legal_name", "Old School Private Limited");
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(current));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    PlatformInstitutionController controller =
        new PlatformInstitutionController(jdbc, authorization, institutionAuth, objectMapper);
    Map<String,Object> result = controller.updateInstitutionProfile(42L,
        Map.of("displayName", "New School", "legalName", "New School Private Limited"), platformOwner);

    assertEquals("New School", result.get("displayName"));
    assertEquals("New School Private Limited", result.get("legalName"));
    assertEquals(true, result.get("defaultBrandNameSynchronized"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeast(4)).update(sql.capture(), any(Object[].class));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("update institution_tenant set display_name=?,legal_name=?")));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("update institution_branding set display_name=?,version=version+1")
            && q.contains("where institution_id=? and display_name=?")));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("INSTITUTION_PROFILE_CHANGED") && q.contains("displayNameChanged")));
    verify(authorization).requirePermission(platformOwner, "QE_PLATFORM_OWNER");
  }

  @Test
  void profileUpdateDoesNotOverwriteACustomizedBrandDisplayName() {
    when(authorization.requirePermission(platformOwner, "QE_PLATFORM_OWNER")).thenReturn(platformOwner);
    Map<String,Object> current = new LinkedHashMap<>();
    current.put("display_name", "Old School");
    current.put("legal_name", null);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(current));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0, 1, 1);

    PlatformInstitutionController controller =
        new PlatformInstitutionController(jdbc, authorization, institutionAuth, objectMapper);
    Map<String,Object> result = controller.updateInstitutionProfile(42L,
        Map.of("displayName", "New School", "legalName", ""), platformOwner);

    assertEquals("New School", result.get("displayName"));
    assertEquals(false, result.get("defaultBrandNameSynchronized"));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, atLeast(4)).update(sql.capture(), any(Object[].class));
    assertTrue(sql.getAllValues().stream().anyMatch(q ->
        q.contains("update institution_branding set display_name=?,version=version+1")
            && q.contains("where institution_id=? and display_name=?")));
  }

  @Test
  void profileUpdateRejectsNonOwnerBeforeReadingInstitutionData() {
    when(authorization.requirePermission(null, "QE_PLATFORM_OWNER"))
        .thenThrow(new SecurityException("Permission required: QE_PLATFORM_OWNER"));
    PlatformInstitutionController controller =
        new PlatformInstitutionController(jdbc, authorization, institutionAuth, objectMapper);

    assertThrows(SecurityException.class, () -> controller.updateInstitutionProfile(
        42L, Map.of("displayName", "New School", "legalName", ""), null));
    verifyNoInteractions(jdbc);
  }

  @Test
  void profileUpdateRejectsInvalidDisplayNameBeforeDatabaseWrites() {
    when(authorization.requirePermission(platformOwner, "QE_PLATFORM_OWNER")).thenReturn(platformOwner);
    PlatformInstitutionController controller =
        new PlatformInstitutionController(jdbc, authorization, institutionAuth, objectMapper);

    assertThrows(IllegalArgumentException.class, () -> controller.updateInstitutionProfile(
        42L, Map.of("displayName", " ", "legalName", ""), platformOwner));
    verifyNoInteractions(jdbc);
  }
}
