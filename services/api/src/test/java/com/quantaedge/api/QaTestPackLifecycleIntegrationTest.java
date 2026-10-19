package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Executable QA lifecycle integration test. CI creates a separate quantaedge_qa database and
 * opts into this class via -Dqe.qa.lifecycle.integration=true; local unit runs skip it by default.
 */
@EnabledIfSystemProperty(named = "qe.qa.lifecycle.integration", matches = "true")
class QaTestPackLifecycleIntegrationTest {
  private static final String DATABASE_URL =
      System.getProperty("qe.qa.lifecycle.jdbc-url", "jdbc:postgresql://localhost:5432/quantaedge_qa");
  private static final String DATABASE_USER =
      System.getProperty("qe.qa.lifecycle.jdbc-user", System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "quantaedge"));
  private static final String DATABASE_PASSWORD =
      System.getProperty("qe.qa.lifecycle.jdbc-password", System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "quantaedge-test"));

  @Test
  void installsVerifiesAndUninstallsOneRunWithoutChangingAnotherRunOrProtectedBaseline() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(DATABASE_URL);
    dataSource.setUsername(DATABASE_USER);
    dataSource.setPassword(DATABASE_PASSWORD);

    // This database is isolated for lifecycle tests. Apply the actual migrations, not a hand-made
    // partial schema, so the pack and cleanup are checked against real FK/unique/check constraints.
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
        .cleanDisabled(true).load().migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "qa");
    QaTestPackService qa = new QaTestPackService(
        jdbc, environment, new DataSourceTransactionManager(dataSource), new ObjectMapper(),
        true, true, "ci-qa", "quantaedge_qa", "QAfixture!2026");

    Map<String,Object> gate = qa.environmentStatus();
    assertTrue(Boolean.TRUE.equals(gate.get("writeAllowed")), () -> "QA safety gate rejected isolated database: " + gate);

    String idempotencyA = "integration-a-" + UUID.randomUUID();
    String idempotencyB = "integration-b-" + UUID.randomUUID();
    UUID runA = null;
    UUID runB = null;
    try {
      Map<String,Object> installedA = qa.install("TENANT_LEARNER_BOUNDARY_V1", idempotencyA, null);
      assertEquals("INSTALLED", installedA.get("state"), () -> "Run A installation failed: " + installedA);
      runA = UUID.fromString(String.valueOf(installedA.get("run_id")));
      assertTrue(number(installedA.get("created_resource_count")) > 0);

      Map<String,Object> replayA = qa.install("TENANT_LEARNER_BOUNDARY_V1", idempotencyA, null);
      assertEquals("INSTALLED", replayA.get("state"));
      assertTrue(Boolean.TRUE.equals(replayA.get("replayed")));
      assertEquals(installedA.get("created_resource_count"), replayA.get("created_resource_count"),
          "Replaying an idempotency key must not duplicate owned resources.");

      Map<String,Object> verifiedA = qa.verifyRun(runA, null);
      assertEquals("PASS", ((Map<?,?>) verifiedA.get("verification")).get("overallStatus"),
          () -> "Run A invariant checks failed: " + verifiedA.get("checks"));
      assertTrue(((List<?>) verifiedA.get("checks")).stream()
          .map(item -> (Map<?,?>) item).allMatch(check -> "PASS".equals(check.get("status"))));

      Map<String,Object> installedB = qa.install("TENANT_LEARNER_BOUNDARY_V1", idempotencyB, null);
      assertEquals("INSTALLED", installedB.get("state"), () -> "Run B installation failed: " + installedB);
      runB = UUID.fromString(String.valueOf(installedB.get("run_id")));
      Map<String,Object> verifiedB = qa.verifyRun(runB, null);
      assertEquals("PASS", ((Map<?,?>) verifiedB.get("verification")).get("overallStatus"),
          () -> "Run B invariant checks failed: " + verifiedB.get("checks"));

      String tenantCodeA = codeFromCredential(installedA, "parentA");
      assertEquals(2L, count(jdbc, "select count(*) from institution_tenant where qa_test_run_id=?", runA));
      assertEquals(2L, count(jdbc, "select count(*) from institution_tenant where qa_test_run_id=?", runB));
      assertEquals(1L, count(jdbc, "select count(*) from lesson where qa_test_run_id=?", runA));
      assertEquals(1L, count(jdbc, "select count(*) from question where qa_test_run_id=?", runA));
      assertEquals(2L, count(jdbc, "select count(*) from question_option where qa_test_run_id=?", runA));
      assertEquals(1L, count(jdbc, "select count(*) from lesson where qa_test_run_id=?", runB));
      assertEquals(1L, count(jdbc, "select count(*) from question where qa_test_run_id=?", runB));
      assertEquals(2L, count(jdbc, "select count(*) from question_option where qa_test_run_id=?", runB));

      Map<String,Object> removedA = qa.uninstall(runA, runA.toString(), null);
      assertEquals("UNINSTALLED", removedA.get("state"), () -> "Run A cleanup failed: " + removedA.get("error_summary"));
      assertTrue(Boolean.TRUE.equals(((Map<?,?>) removedA.get("cleanup_report")).get("baselineCheckPassed")));
      assertEquals(0L, count(jdbc, "select count(*) from institution_tenant where qa_test_run_id=?", runA));
      assertEquals(2L, count(jdbc, "select count(*) from institution_tenant where qa_test_run_id=?", runB),
          "Removing run A must preserve run B.");
      assertEquals(0L, count(jdbc, "select count(*) from lesson where qa_test_run_id=?", runA));
      assertEquals(0L, count(jdbc, "select count(*) from question where qa_test_run_id=?", runA));
      assertEquals(0L, count(jdbc, "select count(*) from question_option where qa_test_run_id=?", runA));
      assertEquals(1L, count(jdbc, "select count(*) from lesson where qa_test_run_id=?", runB),
          "Removing run A must preserve run B's curriculum fixture.");
      assertEquals(1L, count(jdbc, "select count(*) from question where qa_test_run_id=?", runB));
      assertEquals(2L, count(jdbc, "select count(*) from question_option where qa_test_run_id=?", runB));

      Map<String,Object> replayUninstallA = qa.uninstall(runA, runA.toString(), null);
      assertTrue(Boolean.TRUE.equals(replayUninstallA.get("replayed")));
      assertEquals(2L, count(jdbc, "select count(*) from institution_tenant where tenant_code like ? and qa_test_run_id=?", "QAT%", runB));

      Map<String,Object> removedB = qa.uninstall(runB, runB.toString(), null);
      assertEquals("UNINSTALLED", removedB.get("state"), () -> "Run B cleanup failed: " + removedB.get("error_summary"));
      assertEquals(0L, count(jdbc, "select count(*) from institution_tenant where qa_test_run_id=?", runA));
      assertEquals(0L, count(jdbc, "select count(*) from institution_tenant where qa_test_run_id=?", runB));
      assertEquals(0L, count(jdbc, "select count(*) from lesson where qa_test_run_id=?", runB));
      assertEquals(0L, count(jdbc, "select count(*) from question where qa_test_run_id=?", runB));
      assertEquals(0L, count(jdbc, "select count(*) from question_option where qa_test_run_id=?", runB));
      assertEquals(0L, count(jdbc, "select count(*) from qa_test_run_resource where run_id=? and state<>'CLEANED'", runA));
      assertEquals(0L, count(jdbc, "select count(*) from qa_test_run_resource where run_id=? and state<>'CLEANED'", runB));
      assertTrue(number(qa.getRun(runA).get("cleaned_resource_count")) > 0);
      assertTrue(number(qa.getRun(runB).get("cleaned_resource_count")) > 0);
      assertFalse(Boolean.TRUE.equals(qa.environmentStatus().get("productionProfileDetected")));
      assertNotNull(qa.getRun(runA).get("cleanup_report"));
      // Kept to make it explicit that user-facing run identities are generated, never reused.
      assertTrue(tenantCodeA.startsWith("QAT"));
    } finally {
      // Do a best-effort exact-run cleanup after assertions, never broad database cleanup.
      for (UUID runId : new UUID[] {runA, runB}) {
        if (runId == null) continue;
        try {
          Map<String,Object> detail = qa.getRun(runId);
          if ("INSTALLED".equals(String.valueOf(detail.get("state")))
              || "UNINSTALL_BLOCKED".equals(String.valueOf(detail.get("state")))
              || "UNINSTALLING".equals(String.valueOf(detail.get("state")))
              || "INSTALL_FAILED".equals(String.valueOf(detail.get("state")))) {
            qa.uninstall(runId, runId.toString(), null);
          }
        } catch (Exception ignored) {
          // CI discards the disposable database after the job. The test error remains the primary failure.
        }
      }
    }
  }

  private static long count(JdbcTemplate jdbc, String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private static long number(Object value) {
    return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
  }

  private static String codeFromCredential(Map<String,Object> installed, String key) {
    Object credentials = installed.get("oneTimeSyntheticCredentials");
    if (!(credentials instanceof Map<?,?> values)) throw new AssertionError("One-time credentials were not returned by install.");
    Object parent = values.get(key);
    if (!(parent instanceof Map<?,?> parentMap)) throw new AssertionError("Missing synthetic parent credentials.");
    return String.valueOf(parentMap.get("institutionCode"));
  }
}
