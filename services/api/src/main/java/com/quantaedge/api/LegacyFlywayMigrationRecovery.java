package com.quantaedge.api;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recovers only two known, short-lived migration-name variants that were briefly
 * committed to main. All other Flyway validation failures remain strict failures.
 *
 * V14's temporary "migration compatibility" script did not contain the current
 * V14 schema migration. It is replayed only when V14 is the last history entry;
 * this lets the current V14 execute before V15+ without resetting application data.
 */
@Component
public final class LegacyFlywayMigrationRecovery implements FlywayMigrationStrategy {
  private static final Logger LOGGER = LoggerFactory.getLogger(LegacyFlywayMigrationRecovery.class);

  private static final String V13 = "13";
  private static final String V14 = "14";
  private static final String OLD_V13_DESCRIPTION = "provenance repair";
  private static final String CURRENT_V13_DESCRIPTION = "author created provenance";
  private static final String CURRENT_V13_SCRIPT = "V13__author_created_provenance.sql";
  private static final String OLD_V14_DESCRIPTION = "migration compatibility";

  private final JdbcTemplate jdbc;

  public LegacyFlywayMigrationRecovery(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void migrate(Flyway flyway) {
    List<HistoryEntry> history;
    try {
      Boolean historyTableExists = jdbc.queryForObject(
          "select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
      if (!Boolean.TRUE.equals(historyTableExists)) {
        flyway.migrate();
        return;
      }
      history = jdbc.queryForList("""
          select version, description, success
          from flyway_schema_history
          order by installed_rank
          """).stream().map(LegacyFlywayMigrationRecovery::toHistoryEntry).toList();
    } catch (DataAccessException ex) {
      // Let Flyway emit its normal connection/schema error rather than masking it
      // with a diagnostic pre-check.
      LOGGER.debug("Unable to inspect Flyway history before normal migration", ex);
      flyway.migrate();
      return;
    }

    Optional<HistoryEntry> legacyV13 = findLegacy(history, V13, OLD_V13_DESCRIPTION);
    Optional<HistoryEntry> legacyV14 = findLegacy(history, V14, OLD_V14_DESCRIPTION);

    if (legacyV14.isPresent()) {
      if (!"14".equals(lastRecordedVersion(history)) || hasVersionAfter(history, V14)) {
        LOGGER.error(
            "Found legacy Flyway V14 history, but later migration history exists. "
                + "Automatic migration recovery was deliberately not attempted; inspect "
                + "flyway_schema_history and the startup error before repairing this database.");
        flyway.migrate();
        return;
      }

      // Repair V13's known legacy row individually, never repair unrelated versions.
      legacyV13.ifPresent(entry -> alignOrRemoveLegacyV13(flyway, entry));

      HistoryEntry entry = legacyV14.get();
      int removed = jdbc.update("""
          delete from flyway_schema_history
          where version = '14'
            and description = 'migration compatibility'
            and success = ?
          """, entry.success());
      if (removed != 1) {
        throw new IllegalStateException(
            "Could not safely prepare the known legacy Flyway V14 record for replay; "
                + "the database was not reset. Inspect flyway_schema_history.");
      }

      LOGGER.warn(
          "Detected the known temporary Flyway V14 'migration compatibility' entry. "
              + "Its history record was removed because no later migration had run; "
              + "the current V14 schema migration will now run normally. Application rows "
              + "and PostgreSQL volumes are preserved.");
      flyway.migrate();
      return;
    }

    if (legacyV13.isPresent()) {
      if (hasVersionAfter(history, V14)) {
        LOGGER.error(
            "Found legacy Flyway V13 history alongside later migrations. "
                + "Automatic migration recovery was deliberately not attempted.");
        flyway.migrate();
        return;
      }

      alignOrRemoveLegacyV13(flyway, legacyV13.get());
      LOGGER.warn(
          "Aligned the known temporary Flyway V13 'provenance repair' history entry "
              + "with the current V13 migration. No application data was deleted.");
    }

    // All other checksum, missing-migration and SQL failures remain strict.
    flyway.migrate();
  }

  private void alignOrRemoveLegacyV13(Flyway flyway, HistoryEntry entry) {
    if (!entry.success()) {
      int removed = jdbc.update("""
          delete from flyway_schema_history
          where version = '13'
            and description = 'provenance repair'
            and success = false
          """);
      if (removed != 1) {
        throw new IllegalStateException(
            "Could not remove the failed legacy Flyway V13 marker; migration was stopped safely.");
      }
      return;
    }

    MigrationInfo currentV13 = Arrays.stream(flyway.info().all())
        .filter(info -> info.getVersion() != null
            && V13.equals(info.getVersion().getVersion())
            && CURRENT_V13_DESCRIPTION.equals(info.getResolvedDescription()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Current Flyway V13 could not be resolved; refusing to alter migration history."));

    Integer checksum = currentV13.getResolvedChecksum();
    if (checksum == null) {
      throw new IllegalStateException(
          "Current Flyway V13 has no resolved checksum; refusing to alter migration history.");
    }

    int updated = jdbc.update("""
        update flyway_schema_history
        set description = ?, script = ?, checksum = ?
        where version = '13'
          and description = 'provenance repair'
          and success = true
        """, currentV13.getResolvedDescription(), CURRENT_V13_SCRIPT, checksum);
    if (updated != 1) {
      throw new IllegalStateException(
          "Could not align the known legacy Flyway V13 record; migration was stopped safely.");
    }
  }

  private static Optional<HistoryEntry> findLegacy(
      List<HistoryEntry> history, String version, String description) {
    return history.stream()
        .filter(entry -> version.equals(entry.version())
            && description.equalsIgnoreCase(entry.description()))
        .findFirst();
  }

  private static String lastRecordedVersion(List<HistoryEntry> history) {
    if (history.isEmpty()) {
      return null;
    }
    return history.getLast().version();
  }

  private static boolean hasVersionAfter(List<HistoryEntry> history, String ceiling) {
    MigrationVersion max = MigrationVersion.fromVersion(ceiling);
    for (HistoryEntry entry : history) {
      if (entry.version() == null || entry.version().isBlank()) {
        continue;
      }
      try {
        if (MigrationVersion.fromVersion(entry.version()).compareTo(max) > 0) {
          return true;
        }
      } catch (RuntimeException ex) {
        // Unknown version syntax is not safe for automatic history recovery.
        return true;
      }
    }
    return false;
  }

  private static HistoryEntry toHistoryEntry(Map<String, Object> row) {
    Object version = row.get("version");
    Object description = row.get("description");
    Object success = row.get("success");
    return new HistoryEntry(
        version == null ? null : String.valueOf(version),
        description == null ? "" : String.valueOf(description),
        Boolean.TRUE.equals(success));
  }

  private record HistoryEntry(String version, String description, boolean success) {}
}
