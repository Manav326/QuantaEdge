package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class CourseMarketplaceLaunchRightsReviewTest {
  private JdbcTemplate jdbc;
  private AuthorizationService authorization;
  private ObjectMapper mapper;
  private CourseMarketplaceLaunchController controller;
  private final AuthContext reviewer =
      new AuthContext(null, null, 42L, "ADMIN", "HRMS rights reviewer", "HRMS");

  @BeforeEach
  void setUp() throws Exception {
    jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    authorization = org.mockito.Mockito.mock(AuthorizationService.class);
    mapper = org.mockito.Mockito.mock(ObjectMapper.class);
    CourseMarketplaceLaunchReadinessService readiness =
        org.mockito.Mockito.mock(CourseMarketplaceLaunchReadinessService.class);
    controller = new CourseMarketplaceLaunchController(jdbc, authorization, readiness, mapper);

    when(authorization.requirePermission(reviewer, "QE_PLATFORM_OWNER")).thenReturn(reviewer);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    when(mapper.writeValueAsString(any())).thenReturn("{}");
  }

  @Test
  void clearanceCannotBeGrantedWithoutHttpsEvidence() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.reviewCoursePackageRights(7L, 55L, Map.of(
            "status", "CLEARED_FOR_INTERNAL_USE",
            "notes", "Reviewed license terms and source permission."), reviewer));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    assertTrue(error.getReason().contains("HTTPS rights-evidence URL"));
    verify(jdbc, org.mockito.Mockito.never())
        .queryForList(contains("select i.id,i.package_id"), any(Object[].class));
  }

  @Test
  void clearanceDecisionStoresEvidenceReviewerNotesAndAuditEvent() {
    Map<String,Object> imported = Map.of(
        "id", 55L,
        "package_id", "class6-maths-fractions",
        "package_version", "1.0.0",
        "source_institution_id", 11L,
        "course_id", 71L,
        "release_id", 81L,
        "previous_rights_status", "UNCONFIRMED");
    when(jdbc.queryForList(contains("select i.id,i.package_id"), any(Object[].class)))
        .thenReturn(List.of(imported));
    when(jdbc.queryForList(contains("select id from institution_course_assignment"),
        eq(Long.class), any(Object[].class))).thenReturn(List.of(99L));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String,Object> result = controller.reviewCoursePackageRights(7L,55L,Map.of(
        "status","CLEARED_FOR_INTERNAL_USE",
        "evidenceUrl","https://publisher.example.org/license/terms",
        "notes","Verified the publisher licence and internal teaching scope."),reviewer);

    assertEquals(true,result.get("saved"));
    assertEquals("CLEARED_FOR_INTERNAL_USE",result.get("status"));
    assertEquals(55L,result.get("importId"));
    assertFalse(String.valueOf(result.get("message")).isBlank());
    verify(jdbc).update(contains("update institution_course_package_import"), any(Object[].class));
    verify(jdbc).update(contains("insert into institution_course_lifecycle_event"), any(Object[].class));
  }

  @Test
  void doNotRedistributeCanBeRecordedWithoutAWebEvidenceLink() {
    Map<String,Object> imported = Map.of(
        "id", 56L,
        "package_id", "restricted-package",
        "package_version", "2",
        "source_institution_id", 11L,
        "course_id", 72L,
        "release_id", 82L,
        "previous_rights_status", "UNCONFIRMED");
    when(jdbc.queryForList(contains("select i.id,i.package_id"), any(Object[].class)))
        .thenReturn(List.of(imported));
    when(jdbc.queryForList(contains("select id from institution_course_assignment"),
        eq(Long.class), any(Object[].class))).thenReturn(List.of(100L));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String,Object> result = controller.reviewCoursePackageRights(7L,56L,Map.of(
        "status","DO_NOT_REDISTRIBUTE",
        "notes","Rights are not granted for redistribution."),reviewer);

    assertEquals(true,result.get("saved"));
    assertEquals("DO_NOT_REDISTRIBUTE",result.get("status"));
    verify(jdbc).update(contains("insert into institution_course_lifecycle_event"), any(Object[].class));
  }
}
