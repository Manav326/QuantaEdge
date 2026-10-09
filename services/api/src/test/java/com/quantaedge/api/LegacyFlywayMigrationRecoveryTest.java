package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class LegacyFlywayMigrationRecoveryTest {
  @Test
  void freshDatabaseUsesNormalFlywayMigration() {
    assertEquals(
        LegacyFlywayMigrationRecovery.RecoveryAction.NORMAL,
        LegacyFlywayMigrationRecovery.planRecovery(List.of()));
  }

  @Test
  void normalizesOnlyTheKnownLegacyV13EntryWhenNoLaterMigrationExists() {
    var history = List.of(
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "13", "provenance repair", true));

    assertEquals(
        LegacyFlywayMigrationRecovery.RecoveryAction.NORMALIZE_V13,
        LegacyFlywayMigrationRecovery.planRecovery(history));
  }

  @Test
  void replaysKnownLegacyV14OnlyWhenItIsTheLastMigration() {
    var history = List.of(
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "13", "author created provenance", true),
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "14", "migration compatibility", true));

    assertEquals(
        LegacyFlywayMigrationRecovery.RecoveryAction.REPLAY_V14,
        LegacyFlywayMigrationRecovery.planRecovery(history));
  }

  @Test
  void refusesAutomaticRecoveryWhenAnyLaterMigrationExists() {
    var history = List.of(
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "13", "author created provenance", true),
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "14", "migration compatibility", true),
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "15", "auth and guardians", true));

    assertEquals(
        LegacyFlywayMigrationRecovery.RecoveryAction.UNSAFE_LEGACY_STATE,
        LegacyFlywayMigrationRecovery.planRecovery(history));
  }

  @Test
  void refusesLegacyV13RecoveryAfterLaterMigrations() {
    var history = List.of(
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "13", "provenance repair", true),
        new LegacyFlywayMigrationRecovery.HistoryEntry(
            "27", "assessment bank depth targets", true));

    assertEquals(
        LegacyFlywayMigrationRecovery.RecoveryAction.UNSAFE_LEGACY_STATE,
        LegacyFlywayMigrationRecovery.planRecovery(history));
  }
}
