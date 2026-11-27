package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PlatformInstitutionControllerQualityReviewTest {

  private static final AuthContext OWNER =
      new AuthContext(1L, null, 44L, "PLATFORM_OWNER", "HRMS Owner", "HRMS");

  @Test
  void staleReleaseCanBeRejectedWithReviewNote() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    PlatformInstitutionController controller = controller(jdbc, authorization);
    when(authorization.requirePermission(OWNER, "QE_PLATFORM_OWNER")).thenReturn(OWNER);
    when(jdbc.queryForList(contains("select r.id,r.source_institution_id"), any(Object[].class)))
        .thenReturn(List.of(release()));
    when(jdbc.update(contains("update institution_course_release set release_state=?"), any(Object[].class)))
        .thenReturn(1);

    Map<String,Object> result = controller.reviewCourseRelease(7L,
        Map.of("approved", false, "notes", "Content is stale; resubmission required."), OWNER);

    assertEquals("REJECTED", result.get("status"));
    verify(jdbc, never()).queryForObject(
        contains("select course_release_fingerprint_current"), eq(Boolean.class), any(Object[].class));
    verify(jdbc).update(contains("insert into institution_course_lifecycle_event"), any(Object[].class));
  }

  @Test
  void staleReleaseCannotBeApprovedEvenWithMatchingSubmittedFingerprint() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    PlatformInstitutionController controller = controller(jdbc, authorization);
    when(authorization.requirePermission(OWNER, "QE_PLATFORM_OWNER")).thenReturn(OWNER);
    when(jdbc.queryForList(contains("select r.id,r.source_institution_id"), any(Object[].class)))
        .thenReturn(List.of(release()));
    when(jdbc.queryForObject(
        contains("select course_release_fingerprint_current"), eq(Boolean.class), any(Object[].class)))
        .thenReturn(false);

    assertThrows(ResponseStatusException.class, () -> controller.reviewCourseRelease(7L,
        Map.of("approved", true, "notes", "Reviewed exact release.",
            "reviewedContentFingerprint", "fingerprint-1"), OWNER));

    verify(jdbc, never()).update(
        contains("update institution_course_release set release_state=?"), any(Object[].class));
  }

  private static PlatformInstitutionController controller(
      JdbcTemplate jdbc, AuthorizationService authorization) {
    return new PlatformInstitutionController(
        jdbc, authorization, mock(InstitutionAuthService.class), new ObjectMapper());
  }

  private static Map<String,Object> release() {
    Map<String,Object> release = new HashMap<>();
    release.put("id", 7L);
    release.put("source_institution_id", 2L);
    release.put("program_id", 5L);
    release.put("release_state", "IN_QUALITY_REVIEW");
    release.put("content_fingerprint", "fingerprint-1");
    release.put("updated_at", Timestamp.from(Instant.parse("2026-11-27T10:00:00Z")));
    release.put("release_version", 1);
    release.put("code", "CLASS_6");
    release.put("display_name", "Class 6");
    return release;
  }
}
