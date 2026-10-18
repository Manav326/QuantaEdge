package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InstitutionTenantContextServiceTest {
  @Test
  void certificateAndChildCertificateRoutesRequireAnInstitutionContext() {
    assertEquals(true, InstitutionTenantContextService.requiresInstitutionContext(
        "/api/v1/students/me/certificates"));
    assertEquals(true, InstitutionTenantContextService.requiresInstitutionContext(
        "/api/v1/students/me/certificates/123/download"));
    assertEquals(true, InstitutionTenantContextService.requiresInstitutionContext(
        "/api/v1/guardians/children/456/certificates"));
  }

  @Test
  void publicBrandingAndIdentityRoutesDoNotRequireAnInstitutionContext() {
    assertEquals(false, InstitutionTenantContextService.requiresInstitutionContext(
        "/api/v1/institution/public/branding/SCHOOL-A"));
    assertEquals(false, InstitutionTenantContextService.requiresInstitutionContext(
        "/api/v1/auth/me"));
    assertEquals(false, InstitutionTenantContextService.requiresInstitutionContext(
        "/api/v1/hrms/platform/institutions"));
  }

  @Test
  void missingSelectorDoesNotInventADefaultTenant() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc);

    assertNull(service.resolve(null));
    assertNull(service.resolve("  "));
    verifyNoInteractions(jdbc);
  }

  @Test
  void normalizesCodeAndResolvesOnlyAnActiveInstitution() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        Map.<String,Object>of("id", 17L, "tenant_code", "QUANTA-EDGE", "display_name", "QuantaEdge")));
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc);

    InstitutionTenantContext resolved = service.resolve("  quanta-edge ");

    assertEquals(new InstitutionTenantContext(17L, "QUANTA-EDGE", "QuantaEdge"), resolved);
  }

  @Test
  void routedModeUsesConfiguredDefaultOnlyWhenNoExplicitTenantIsSelected() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc, " school-a ", " routed ");

    assertEquals("SCHOOL-A", service.selectTenantCode(null));
    assertEquals("SCHOOL-A", service.selectTenantCode("school-a"));
    assertEquals("SCHOOL-B", service.selectTenantCode("school-b"));
    assertEquals("ROUTED", service.contextMode());
    verifyNoInteractions(jdbc);
  }

  @Test
  void routedModeWithoutDefaultRequiresExplicitTenantForProtectedRoutes() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc, "", "ROUTED");

    assertNull(service.selectTenantCode(null));
    InstitutionTenantContextService.InvalidInstitutionContextException error =
        assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
            () -> service.requireSelector(null));
    assertEquals("INSTITUTION_CONTEXT_REQUIRED", error.errorCode());
    verifyNoInteractions(jdbc);
  }

  @Test
  void fixedModePinsDeploymentToItsConfiguredInstitution() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc, " school-a ", "FIXED");

    assertEquals("SCHOOL-A", service.selectTenantCode(null));
    assertEquals("SCHOOL-A", service.selectTenantCode("school-a"));
    InstitutionTenantContextService.InvalidInstitutionContextException error =
        assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
            () -> service.selectTenantCode("SCHOOL-B"));
    assertEquals(403, error.statusCode());
    assertEquals("INSTITUTION_CONTEXT_MISMATCH", error.errorCode());
    verifyNoInteractions(jdbc);
  }

  @Test
  void fixedModeCannotStartWithoutDefaultInstitution() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new InstitutionTenantContextService(jdbc, "", "FIXED"));

    org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("APP_DEFAULT_INSTITUTION_CODE"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void rejectsUnknownContextModesAndMalformedConfiguredDefaults() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);

    assertThrows(IllegalArgumentException.class,
        () -> new InstitutionTenantContextService(jdbc, "SCHOOL-A", "MAGIC"));
    assertThrows(IllegalArgumentException.class,
        () -> new InstitutionTenantContextService(jdbc, "not a code", "ROUTED"));
    verifyNoInteractions(jdbc);
  }

  @Test
  void rejectsMalformedCodeBeforeConsultingTheDatabase() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc);

    InstitutionTenantContextService.InvalidInstitutionContextException error =
        assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
            () -> service.resolve("bad code"));

    assertEquals(400, error.statusCode());
    assertEquals("INVALID_INSTITUTION_CODE", error.errorCode());
    verifyNoInteractions(jdbc);
  }

  @Test
  void malformedRequestSelectorIsAClientErrorNotAnInternalServerError() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc, "", "ROUTED");

    InstitutionTenantContextService.InvalidInstitutionContextException error =
        assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
            () -> service.selectTenantCode("not a code"));

    assertEquals(400, error.statusCode());
    assertEquals("INVALID_INSTITUTION_CODE", error.errorCode());
    verifyNoInteractions(jdbc);
  }

  @Test
  void unknownOrInactiveInstitutionFailsClosed() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    InstitutionTenantContextService service = new InstitutionTenantContextService(jdbc);

    InstitutionTenantContextService.InvalidInstitutionContextException error =
        assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
            () -> service.resolve("SCHOOL-A"));

    assertEquals(403, error.statusCode());
    assertEquals("INSTITUTION_UNAVAILABLE", error.errorCode());
  }
}
