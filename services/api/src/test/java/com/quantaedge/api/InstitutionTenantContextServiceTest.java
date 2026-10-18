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
