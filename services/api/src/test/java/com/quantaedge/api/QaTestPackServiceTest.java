package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class QaTestPackServiceTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private PlatformTransactionManager transactionManager;

  private QaTestPackService service(boolean enabled, boolean approved, String label,
      String targetDatabase, String fixturePassword, MockEnvironment environment) {
    return new QaTestPackService(jdbc, environment, transactionManager, new ObjectMapper(),
        enabled, approved, label, targetDatabase, fixturePassword);
  }

  @Test
  void qaMutationIsDisabledByDefaultEvenOnAQaNamedDatabase() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "local");
    when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("quantaedge_qa");

    MapAssert.assertGate(service(false, true, "local-qa", "quantaedge_qa", "QAfixture!2026", environment).environmentStatus(),
        false, "APP_QA_ENABLED");
    verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
  }

  @Test
  void productionProfileRejectsQaEvenWhenFlagsAndDatabaseNameLookSafe() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "local").withProperty("spring.profiles.active", "production");
    environment.setActiveProfiles("production");
    when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("quantaedge_qa");

    var status = service(true, true, "ci-qa", "quantaedge_qa", "QAfixture!2026", environment).environmentStatus();
    assertFalse(Boolean.TRUE.equals(status.get("writeAllowed")));
    assertTrue(Boolean.TRUE.equals(status.get("productionProfileDetected")));
  }

  @Test
  void sharedDatabaseIsNotApprovedJustBecauseTheEnvironmentLabelSaysQa() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "local");
    when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("quantaedge");

    var status = service(true, true, "ci-qa", "quantaedge_qa", "QAfixture!2026", environment).environmentStatus();
    assertFalse(Boolean.TRUE.equals(status.get("writeAllowed")));
    assertFalse(Boolean.TRUE.equals(status.get("databaseApproved")));
  }

  @Test
  void similarlyNamedQaDatabaseIsRejectedWhenItIsNotTheExplicitTarget() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "local");
    when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("quantaedge_other_test");

    var status = service(true, true, "ci-qa", "quantaedge_qa", "QAfixture!2026", environment).environmentStatus();

    assertFalse(Boolean.TRUE.equals(status.get("writeAllowed")));
    assertFalse(Boolean.TRUE.equals(status.get("databaseApproved")));
    assertTrue(String.valueOf(status.get("reason")).contains("does not exactly match"));
  }

  @Test
  void fixturePasswordMustBeExplicitlyConfiguredBeforeWritesAreAllowed() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "local");
    when(jdbc.queryForObject("select current_database()", String.class)).thenReturn("quantaedge_qa");

    var status = service(true, true, "ci-qa", "quantaedge_qa", "", environment).environmentStatus();
    assertFalse(Boolean.TRUE.equals(status.get("writeAllowed")));
    assertFalse(Boolean.TRUE.equals(status.get("fixtureCredentialsConfigured")));
  }

  @Test
  void onlyTheCodeOwnedStarterPackIsReturned() {
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "local");
    var packs = service(false, false, "", "", "", environment).packs();

    assertEquals(1, packs.size());
    assertEquals("TENANT_LEARNER_BOUNDARY_V1", packs.getFirst().get("packKey"));
    assertEquals(3, packs.getFirst().get("version"));
    assertFalse(Boolean.TRUE.equals(packs.getFirst().get("createsExternalResources")));
    assertTrue(Boolean.TRUE.equals(packs.getFirst().get("createsRunOwnedSyntheticCurriculum")));
    assertFalse(Boolean.TRUE.equals(packs.getFirst().get("requiresPublishedClass7Question")));
    assertEquals(3, packs.getFirst().get("minimumInstitutions"));
    assertEquals(3, ((java.util.List<?>) packs.getFirst().get("institutionCourseCoverage")).size());
  }

  static final class MapAssert {
    static void assertGate(java.util.Map<String,Object> status, boolean expected, String reasonFragment) {
      assertEquals(expected, Boolean.TRUE.equals(status.get("writeAllowed")));
      assertTrue(String.valueOf(status.get("reason")).contains(reasonFragment));
    }
  }
}
