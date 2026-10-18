package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@ExtendWith(MockitoExtension.class)
class CourseMarketplaceTenantScopeTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Tenant Student");
  private final InstitutionTenantContext tenant = new InstitutionTenantContext(17L, "SCHOOL-A", "School A");
  private CourseMarketplaceController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireStudent(student)).thenReturn(student);
    controller = new CourseMarketplaceController(jdbc, authorization, new ObjectMapper(),
        "", "", "https://api.razorpay.com/v1");
  }

  @Test
  void marketplaceDiscoveryUsesOnlyPublishedOffersAssignedToExactTenant() {
    Map<String,Object> offer = new LinkedHashMap<>();
    offer.put("course_id", 91L);
    offer.put("title", "School A course");
    offer.put("has_access", false);
    when(jdbc.queryForList(contains("from institution_marketplace_offer o"), any(Object[].class)))
        .thenReturn(List.of(offer));
    when(jdbc.queryForList(contains("where ps.program_id=?"), any(Object[].class)))
        .thenReturn(List.of(Map.of("subject_code", "maths", "chapters", 2L, "lessons", 8L)));

    List<Map<String,Object>> result = controller.courses(student, tenant);

    assertEquals(1, result.size());
    assertEquals("School A course", result.getFirst().get("title"));
    assertEquals(false, result.getFirst().get("is_enrolled"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    assertTrue(sql.getValue().contains("where o.institution_id=? and o.status='PUBLISHED'"));
    assertTrue(sql.getValue().contains("assignment.status='ASSIGNED'"));
    assertTrue(sql.getValue().contains("tenant_scope.status='ACTIVE'"));
    assertTrue(sql.getValue().contains("grant_row.institution_id=?"));
    assertTrue(sql.getValue().contains("cp.institution_id=?"));
    assertTrue(!sql.getValue().contains("not exists(select 1 from institution_membership"));
    assertTrue(List.of(args.getValue()).contains(17L));
  }

  @Test
  void missingTenantContextFailsClosedBeforeMarketplaceQuery() {
    assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
        () -> controller.courses(student, null));
    verifyNoInteractions(jdbc);
  }
}
