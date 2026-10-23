package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
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
  @Mock private CourseOrderFinancialService courseOrderFinancialService;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Tenant Student");
  private final InstitutionTenantContext tenant = new InstitutionTenantContext(17L, "SCHOOL-A", "School A");
  private CourseMarketplaceController controller;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(authorization.requireStudent(student)).thenReturn(student);
    controller = new CourseMarketplaceController(jdbc, authorization, new ObjectMapper(),courseOrderFinancialService,
        "", "", "https://api.razorpay.com/v1");
  }

  @Test
  void onlyReusesPendingOrdersWithLockedTaxAndShareSnapshots() {
    Map<String,Object> complete = new LinkedHashMap<>();
    complete.put("tax_profile_id", 12L);
    complete.put("revenue_share_policy_id", 7L);
    complete.put("tax_calculation_snapshot_json", "{\"customerPayablePaise\":11800,\"taxProfileId\":12}");
    complete.put("revenue_share_policy_snapshot_json", "{\"policyId\":7,\"recipients\":[]}");

    assertTrue(CourseMarketplaceController.hasLockedFinancialSnapshots(complete));

    complete.put("revenue_share_policy_snapshot_json", "{}");
    org.junit.jupiter.api.Assertions.assertFalse(
        CourseMarketplaceController.hasLockedFinancialSnapshots(complete));

    complete.put("revenue_share_policy_snapshot_json", "{\"policyId\":7,\"recipients\":[]}");
    complete.put("tax_profile_id", null);
    org.junit.jupiter.api.Assertions.assertFalse(
        CourseMarketplaceController.hasLockedFinancialSnapshots(complete));
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
    verify(jdbc, times(2)).queryForList(sql.capture(), args.capture());
    String catalogueSql = sql.getAllValues().stream()
        .filter(value -> value.contains("from institution_marketplace_offer o"))
        .findFirst().orElseThrow();
    Object[] catalogueArgs = args.getAllValues().get(sql.getAllValues().indexOf(catalogueSql));
    assertTrue(catalogueSql.contains("where o.institution_id=? and o.status='PUBLISHED'"));
    assertTrue(catalogueSql.contains("assignment.status='ASSIGNED'"));
    assertTrue(catalogueSql.contains("tenant_scope.status='ACTIVE'"));
    assertTrue(catalogueSql.contains("o.hrms_published_by_staff_id is not null"));
    assertTrue(catalogueSql.contains("release.release_state='SHAREABLE'"));
    assertTrue(catalogueSql.contains("release.content_fingerprint=course_content_fingerprint(release.program_id)"));
    assertTrue(catalogueSql.contains("newer_release.release_state='SHAREABLE'"));
    assertTrue(!catalogueSql.contains("not exists(select 1 from institution_membership"));
    assertTrue(List.of(catalogueArgs).contains(17L));
  }

  @Test
  void missingTenantContextFailsClosedBeforeMarketplaceQuery() {
    assertThrows(InstitutionTenantContextService.InvalidInstitutionContextException.class,
        () -> controller.courses(student, null));
    verifyNoInteractions(jdbc);
  }
}
