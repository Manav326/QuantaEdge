package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P1 authorization regression tests for notification audience resolution.
 *
 * These tests mock persistence at the SQL boundary and deliberately exercise three distinct
 * institution IDs. A PostgreSQL/Flyway acceptance suite remains a separate rollout gate.
 */
class RichNotificationAudienceIsolationTest {
  private JdbcTemplate jdbc;
  private RichNotificationService service;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    service = new RichNotificationService(
        jdbc, mapper, new NotificationContentValidator(mapper), mock(AuthorizationService.class));
  }

  @Test
  void directStudentAudienceMustBelongToTheSelectedInstitutionAcrossThreeTenants() {
    stubStudentMembership(101L, 42L, false);
    stubStudentMembership(202L, 42L, true);
    stubStudentMembership(303L, 42L, false);

    assertThrows(SecurityException.class,
        () -> service.resolveAudience("USER", 101L, null, directStudent(42L)));
    List<RichNotificationService.Recipient> valid =
        service.resolveAudience("USER", 202L, null, directStudent(42L));
    assertEquals(1, valid.size());
    assertEquals(new RichNotificationService.Recipient("STUDENT", 42L, 202L), valid.getFirst());
    assertThrows(SecurityException.class,
        () -> service.resolveAudience("USER", 303L, null, directStudent(42L)));
  }

  @Test
  void hrmsCampaignRejectsAnInactiveInstitutionInsteadOfSilentlySkippingIt() {
    stubInstitutionActive(101L, true);
    stubInstitutionActive(202L, false);
    stubInstitutionActive(303L, true);

    Map<String, Object> audience = Map.of(
        "institutionIds", List.of(101L, 202L, 303L),
        "recipientTypes", List.of("STUDENT"));

    assertThrows(IllegalArgumentException.class,
        () -> service.resolveAudience("HRMS_PLATFORM", null, null, audience));
  }

  @Test
  void courseAudienceMustBeAssignedToTheSelectedTenantBeforeResolution() {
    when(jdbc.queryForObject(
        contains("from institution_course_assignment a"),
        eq(Boolean.class), eq(500L), eq(202L))).thenReturn(false);

    assertThrows(SecurityException.class,
        () -> service.resolveAudience("COURSE", 202L, 500L,
            Map.of("recipientTypes", List.of("STUDENT"))));
  }

  @Test
  void parentDirectTargetRequiresAnActiveConsentedGuardianRelationshipInThatTenant() {
    when(jdbc.queryForObject(
        contains("guardian_student gs on gs.guardian_user_id=u.id"),
        eq(Boolean.class), eq(202L), eq(88L))).thenReturn(false);

    assertThrows(SecurityException.class,
        () -> service.resolveAudience("USER", 202L, null,
            Map.of("recipients", List.of(Map.of("kind", "USER_ACCOUNT", "id", 88L)))));
  }

  @Test
  void staffTargetsFailClosedUntilCanonicalTenantMappingExists() {
    assertThrows(IllegalArgumentException.class,
        () -> service.resolveAudience("USER", 202L, null,
            Map.of("recipients", List.of(Map.of("kind", "STAFF_ACCOUNT", "id", 70L)))));
  }

  private void stubStudentMembership(long institutionId, long studentId, boolean eligible) {
    when(jdbc.queryForObject(
        contains("select exists(select 1 from student st join institution_membership"),
        eq(Boolean.class), eq(institutionId), eq(studentId))).thenReturn(eligible);
  }

  private void stubInstitutionActive(long institutionId, boolean active) {
    when(jdbc.queryForObject(
        contains("select exists(select 1 from institution_tenant where id=?"),
        eq(Boolean.class), eq(institutionId))).thenReturn(active);
  }

  private static Map<String, Object> directStudent(long studentId) {
    return Map.of("recipients", List.of(Map.of("kind", "STUDENT", "id", studentId)));
  }
}
