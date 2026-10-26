package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    assertTrue(catalogueSql.contains("course_release_fingerprint_current(release.program_id,release.content_fingerprint::text,release.updated_at)"));
    assertTrue(catalogueSql.contains("course_marketplace_content_type_ready(release.program_id)"));
    assertTrue(catalogueSql.contains("qe_same_logical_course(e.program_id,p.id)"),
        "A learner who owns an earlier content version must not be offered a second purchase.");
    assertTrue(catalogueSql.contains("qe_same_logical_course(cp.course_id,p.id)"),
        "Purchase history must be compared by stable logical course identity.");
    assertTrue(catalogueSql.contains("or exists(select 1 from course_entitlement ce")
        && catalogueSql.contains("ce.institution_id=?")
        && catalogueSql.contains("ce.holder_student_id=?")
        && catalogueSql.contains("ce.holder_user_id="),
        "Marketplace must mark an existing tenant-scoped entitlement as owned, even without a course_purchase row.");
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

  @Test
  void pendingStudentOrdersAreScopedToTheCurrentLearnerAndInstitution() {
    when(jdbc.queryForList(contains("from course_payment_order o"), any(Object[].class)))
        .thenReturn(List.of());

    List<Map<String,Object>> result = controller.listPendingStudentOrders(student, tenant);

    assertTrue(result.isEmpty());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    assertTrue(sql.getValue().contains("where o.institution_id=?"));
    assertTrue(sql.getValue().contains("o.student_id=? and o.purchaser_user_id is null"));
    assertTrue(sql.getValue().contains("cast(? as bigint) is not null"));
    Object[] values = args.getValue();
    assertEquals(17L, values[0]);
    assertEquals(7L, values[1]);
    assertEquals(7L, values[2]);
    assertNull(values[3]);
    assertNull(values[4]);
  }

  @Test
  void pendingParentOrdersAreScopedToTheAuthenticatedPurchaserAndInstitution() {
    AuthContext parent = new AuthContext(1L, 4L, "PARENT", "Test Parent");
    when(authorization.requireParent(parent)).thenReturn(parent);
    when(jdbc.queryForList(contains("from course_payment_order o"), any(Object[].class)))
        .thenReturn(List.of());

    List<Map<String,Object>> result = controller.listPendingFamilyOrders(parent, tenant);

    assertTrue(result.isEmpty());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    assertTrue(sql.getValue().contains("where o.institution_id=?"));
    assertTrue(sql.getValue().contains("o.purchaser_user_id=?"));
    Object[] values = args.getValue();
    assertEquals(17L, values[0]);
    assertNull(values[1]);
    assertNull(values[2]);
    assertEquals(1L, values[3]);
    assertEquals(1L, values[4]);
  }

  @Test
  void studentCannotVerifyOrReconcileAnOrderPaidForByTheirParent() {
    CourseMarketplaceController secured = new CourseMarketplaceController(
        jdbc, authorization, new ObjectMapper(), courseOrderFinancialService,
        "rzp_test_unit123", "test-secret", "https://api.razorpay.com/v1");
    when(jdbc.queryForList(contains("from course_payment_order"), any(Object[].class)))
        .thenReturn(List.of());
    CourseMarketplaceController.VerifyCoursePaymentRequest callback =
        new CourseMarketplaceController.VerifyCoursePaymentRequest(
            "pay_Test123", "order_Test123", "0".repeat(64));

    assertThrows(SecurityException.class,
        () -> secured.verifyOrder(41L, callback, student, tenant));
    assertThrows(SecurityException.class,
        () -> secured.reconcileOrder(41L, student, tenant));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2)).queryForList(sql.capture(), args.capture());
    for (int i = 0; i < sql.getAllValues().size(); i++) {
      assertTrue(sql.getAllValues().get(i).contains("student_id=? and purchaser_user_id is null"));
      assertTrue(sql.getAllValues().get(i).contains("purchaser_user_id=?"));
      assertTrue(sql.getAllValues().get(i).contains("cast(? as bigint) is not null"),
          "Nullable caller identity parameters must be explicitly typed for PostgreSQL.");
      Object[] values = args.getAllValues().get(i);
      assertEquals(41L, values[0]);
      assertEquals(17L, values[1]);
      assertEquals(7L, values[2]);
      assertEquals(7L, values[3]);
      assertEquals(null, values[4]);
      assertEquals(null, values[5]);
    }
  }

  @Test
  void parentVerificationAndReconciliationAreBoundOnlyToTheAuthenticatedPurchaser() {
    AuthContext parent = new AuthContext(1L, 4L, "PARENT", "Test Parent");
    when(authorization.requireParent(parent)).thenReturn(parent);
    CourseMarketplaceController secured = new CourseMarketplaceController(
        jdbc, authorization, new ObjectMapper(), courseOrderFinancialService,
        "rzp_test_unit123", "test-secret", "https://api.razorpay.com/v1");
    when(jdbc.queryForList(contains("from course_payment_order"), any(Object[].class)))
        .thenReturn(List.of());
    CourseMarketplaceController.VerifyCoursePaymentRequest callback =
        new CourseMarketplaceController.VerifyCoursePaymentRequest(
            "pay_Test123", "order_Test123", "0".repeat(64));

    assertThrows(SecurityException.class,
        () -> secured.verifyFamilyOrder(42L, callback, parent, tenant));
    assertThrows(SecurityException.class,
        () -> secured.reconcileFamilyOrder(42L, parent, tenant));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(2)).queryForList(sql.capture(), args.capture());
    for (int i = 0; i < sql.getAllValues().size(); i++) {
      assertTrue(sql.getAllValues().get(i).contains("student_id=? and purchaser_user_id is null"));
      assertTrue(sql.getAllValues().get(i).contains("purchaser_user_id=?"));
      assertTrue(sql.getAllValues().get(i).contains("cast(? as bigint) is not null"),
          "Nullable caller identity parameters must be explicitly typed for PostgreSQL.");
      Object[] values = args.getAllValues().get(i);
      assertEquals(42L, values[0]);
      assertEquals(17L, values[1]);
      assertEquals(null, values[2]);
      assertEquals(null, values[3]);
      assertEquals(1L, values[4]);
      assertEquals(1L, values[5]);
    }
  }
}
