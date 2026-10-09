package com.quantaedge.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LegacyFlywayMigrationRecoveryTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Flyway flyway = mock(Flyway.class);
  private final LegacyFlywayMigrationRecovery strategy = new LegacyFlywayMigrationRecovery(jdbc);

  @Test
  void freshDatabaseUsesNormalFlywayMigration() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

    strategy.migrate(flyway);

    verify(flyway).migrate();
    verify(flyway, never()).repair();
    verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.<Object>any());
  }

  @Test
  void replaysOnlyTheKnownLegacyV14WhenItIsTheLastMigration() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
    when(jdbc.queryForList(anyString())).thenReturn(List.of(
        Map.of("version", "13", "description", "author created provenance", "success", true),
        Map.of("version", "14", "description", "migration compatibility", "success", true)));
    when(jdbc.update(contains("delete from flyway_schema_history"),
        eq(true))).thenReturn(1);

    strategy.migrate(flyway);

    verify(jdbc).update(contains("delete from flyway_schema_history"), eq(true));
    verify(flyway).migrate();
    verify(flyway, never()).repair();
  }

  @Test
  void doesNotAutomaticallyRepairKnownLegacyHistoryIfLaterVersionsExist() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
    when(jdbc.queryForList(anyString())).thenReturn(List.of(
        Map.of("version", "13", "description", "author created provenance", "success", true),
        Map.of("version", "14", "description", "migration compatibility", "success", true),
        Map.of("version", "15", "description", "auth and guardians", "success", true)));

    strategy.migrate(flyway);

    verify(flyway).migrate();
    verify(jdbc, never()).update(contains("delete from flyway_schema_history"), eq(true));
    verify(flyway, never()).repair();
  }
}
