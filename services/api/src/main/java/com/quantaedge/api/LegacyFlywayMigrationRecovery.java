package com.quantaedge.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
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
 *
 * This class uses DataSource directly because JdbcTemplate initialization is ordered
 * after Flyway by Spring Boot and would create a startup dependency cycle here.
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

  private final DataSource dataSource;

  public LegacyFlywayMigrationRecovery(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void migrate(Flyway flyway) {
    List<HistoryEntry> history;
    try {
      history = loadHistory();
    } catch (SQLException ex) {
      // Let Flyway emit its normal connection/schema error rather than masking it
      // with a diagnostic pre-check.
      LOGGER.debug("Unable to inspect Flyway history before normal migration", ex);
      flyway.migrate();
      return;
    }

    RecoveryAction action = planRecovery(history);
    switch (action) {
      case NORMAL -> flyway.migrate();
      case NORMALIZE_V13 -> {
        HistoryEntry legacyV13 = findLegacy(history, V13, OLD_V13_DESCRIPTION).orElseThrow();
        alignOrRemoveLegacyV13(flyway, legacyV13);
        LOGGER.warn(
            "Aligned the known temporary Flyway V13 'provenance repair' history entry "
                + "with the current V13 migration. No application data was deleted.");
        flyway.migrate();
      }
      case REPLAY_V14 -> {
        // If V13 also has its temporary filename, normalize only that known row.
        findLegacy(history, V13, OLD_V13_DESCRIPTION)
            .ifPresent(entry -> alignOrRemoveLegacyV13(flyway, entry));

        HistoryEntry legacyV14 = findLegacy(history, V14, OLD_V14_DESCRIPTION).orElseThrow();
        int removed = executeUpdate("""
            delete from public.flyway_schema_history
            where version = '14'
              and description = 'migration compatibility'
              and success = ?
            """, statement -> statement.setBoolean(1, legacyV14.success()));
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
      }
      case UNSAFE_LEGACY_STATE -> {
        LOGGER.error(
            "Found a known legacy Flyway migration entry alongside later migration history. "
                + "Automatic recovery was deliberately not attempted; inspect "
                + "flyway_schema_history and the startup error before repairing this database.");
        flyway.migrate();
      }
    }
  }

  private List<HistoryEntry> loadHistory() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement = connection.prepareStatement(
          "select to_regclass('public.flyway_schema_history') is not null");
          ResultSet result = statement.executeQuery()) {
        if (!result.next() || !result.getBoolean(1)) {
          return List.of();
        }
      }

      List<HistoryEntry> history = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement("""
          select version, description, success
          from public.flyway_schema_history
          order by installed_rank
          """);
          ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          history.add(new HistoryEntry(
              result.getString("version"),
              result.getString("description"),
              result.getBoolean("success")));
        }
      }
      return List.copyOf(history);
    }
  }

  private void alignOrRemoveLegacyV13(Flyway flyway, HistoryEntry entry) {
    if (!entry.success()) {
      int removed = executeUpdate("""
          delete from public.flyway_schema_history
          where version = '13'
            and description = 'provenance repair'
            and success = false
          """, statement -> {});
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

    int updated = executeUpdate("""
        update public.flyway_schema_history
        set description = ?, script = ?, checksum = ?
        where version = '13'
          and description = 'provenance repair'
          and success = true
        """, statement -> {
          statement.setString(1, currentV13.getResolvedDescription());
          statement.setString(2, CURRENT_V13_SCRIPT);
          statement.setInt(3, checksum);
        });
    if (updated != 1) {
      throw new IllegalStateException(
          "Could not align the known legacy Flyway V13 record; migration was stopped safely.");
    }
  }

  private int executeUpdate(String sql, StatementBinder binder) {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      return statement.executeUpdate();
    } catch (SQLException ex) {
      throw new IllegalStateException(
          "Could not safely reconcile the known legacy Flyway history record.", ex);
    }
  }

  static RecoveryAction planRecovery(List<HistoryEntry> history) {
    Optional<HistoryEntry> legacyV13 = findLegacy(history, V13, OLD_V13_DESCRIPTION);
    Optional<HistoryEntry> legacyV14 = findLegacy(history, V14, OLD_V14_DESCRIPTION);

    if (legacyV14.isPresent()) {
      return V14.equals(lastRecordedVersion(history)) && !hasVersionAfter(history, V14)
          ? RecoveryAction.REPLAY_V14
          : RecoveryAction.UNSAFE_LEGACY_STATE;
    }
    if (legacyV13.isPresent()) {
      return hasVersionAfter(history, V14)
          ? RecoveryAction.UNSAFE_LEGACY_STATE
          : RecoveryAction.NORMALIZE_V13;
    }
    return RecoveryAction.NORMAL;
  }

  private static Optional<HistoryEntry> findLegacy(
      List<HistoryEntry> history, String version, String description) {
    return history.stream()
        .filter(entry -> version.equals(entry.version())
            && description.equalsIgnoreCase(entry.description()))
        .findFirst();
  }

  private static String lastRecordedVersion(List<HistoryEntry> history) {
    return history.isEmpty() ? null : history.getLast().version();
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

  @FunctionalInterface
  private interface StatementBinder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  enum RecoveryAction {
    NORMAL,
    NORMALIZE_V13,
    REPLAY_V14,
    UNSAFE_LEGACY_STATE
  }

  record HistoryEntry(String version, String description, boolean success) {}
}
