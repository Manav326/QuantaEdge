package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseVersionLearnerMigrationServiceTest {

  @Test
  void migrationIsIdempotentAndCountsOnlyNewVersionEnrollments() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    CourseVersionLearnerMigrationService migration = new CourseVersionLearnerMigrationService(jdbc);
    when(jdbc.queryForList(contains("with old_courses"), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(501L, 502L));
    when(jdbc.queryForList(contains("select subject_id from curriculum_program_subject"),
        eq(Long.class), any(Object[].class))).thenReturn(List.of(601L));
    when(jdbc.update(contains("insert into student_program_enrollment"), any(Object[].class)))
        .thenReturn(1, 0);

    int granted = migration.grantExistingLearnersToVersion(7L, 701L, 2, 1701L);

    assertEquals(1, granted, "A repeated migration must not count an already-existing enrollment as a new grant.");
    verify(jdbc, times(2)).update(contains("insert into student_program_enrollment"), any(Object[].class));
    verify(jdbc, times(2)).update(contains("insert into student_track_enrollment"), any(Object[].class));
    verify(jdbc).queryForList(contains("r.content_fingerprint=course_content_fingerprint(r.program_id)"),
        eq(Long.class), any(Object[].class));
  }

  @Test
  void doesNotCreateLearnerGrantsForInitialOrInvalidVersions() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    CourseVersionLearnerMigrationService migration = new CourseVersionLearnerMigrationService(jdbc);

    assertEquals(0, migration.grantExistingLearnersToVersion(7L, 701L, 1, 1701L));
    assertEquals(0, migration.grantExistingLearnersToVersion(0L, 701L, 2, 1701L));
    org.mockito.Mockito.verifyNoInteractions(jdbc);
  }
}
