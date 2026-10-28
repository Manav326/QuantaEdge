package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseMarketplaceLaunchControllerTest {

  @Test
  void creatingOfferDraftRecordsTheLockedAssignmentReleaseId() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    CourseMarketplaceLaunchReadinessService readiness = mock(CourseMarketplaceLaunchReadinessService.class);
    CourseMarketplaceLaunchController controller =
        new CourseMarketplaceLaunchController(jdbc, authorization, readiness, new ObjectMapper());
    AuthContext context = new AuthContext(1L, null, 44L, "PLATFORM_OWNER", "HRMS Owner", "HRMS");

    Map<String,Object> assignment = new HashMap<>();
    assignment.put("assignment_id", 12L);
    assignment.put("id", 12L);
    assignment.put("institution_id", 2L);
    assignment.put("course_release_id", 7L);
    assignment.put("release_id", 7L);
    assignment.put("assignment_status", "ASSIGNED");
    assignment.put("course_id", 5L);
    assignment.put("release_state", "SHAREABLE");
    assignment.put("course_status", "ACTIVE");
    assignment.put("course_code", "CLASS_6");
    assignment.put("course_name", "Class 6");
    assignment.put("institution_status", "ACTIVE");

    List<Map<String,Object>> passedChecks = List.of(
        "INSTITUTION_ACTIVE", "COURSE_ACTIVE", "COURSE_IDENTITY", "COURSE_EXPIRY_POLICY", "CURRENT_COURSE_RELEASE",
        "CONTENT_QUALITY_REVIEW", "ORIGIN_RELEASE_APPROVAL", "ASSIGNMENT_AND_RELEASE",
        "CONTENT_REVIEW_CURRENT", "PUBLISHED_LEARNING_CONTENT", "SOURCE_RIGHTS_CLEARANCE"
    ).stream().map(code -> Map.<String,Object>of(
        "code", code, "passed", true, "label", code, "message", ""
    )).toList();

    when(authorization.requirePermission(context, "QE_PLATFORM_OWNER")).thenReturn(context);
    when(jdbc.queryForObject(
        contains("select exists(select 1 from institution_tenant where id=?)"),
        eq(Boolean.class), any(Object[].class))).thenReturn(true);
    when(jdbc.queryForList(
        contains("from institution_course_assignment a"), any(Object[].class)))
        .thenReturn(List.of(assignment));
    when(jdbc.queryForList(
        contains("select id,status,obsolete_at from institution_marketplace_offer"),
        any(Object[].class))).thenReturn(List.of());
    when(readiness.inspectAssignment(12L, 2L)).thenReturn(Map.of("checks", passedChecks));
    when(jdbc.queryForObject(
        contains("insert into institution_marketplace_offer"), eq(Long.class), any(Object[].class)))
        .thenReturn(44L);

    Map<String,Object> result = controller.saveDraftOffer(2L, 12L, Map.of(
        "title", "Class 6", "description", "Class 6 learning course",
        "pricePaise", 100000L, "currency", "INR"
    ), context);

    assertTrue(Boolean.TRUE.equals(result.get("saved")));
    assertEquals(44L, ((Number) result.get("offerId")).longValue());

    // The row contract must expose a numeric release_id because the lifecycle audit is
    // attached to the exact source release, not merely to the assignment or course.
    verify(jdbc).queryForList(
        contains("a.course_release_id,r.id as release_id"), any(Object[].class));
    ArgumentCaptor<Object[]> auditArguments = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).update(contains("insert into institution_course_lifecycle_event"), auditArguments.capture());
    assertEquals(7L, ((Number) auditArguments.getValue()[1]).longValue());
    assertEquals(12L, ((Number) auditArguments.getValue()[2]).longValue());
    assertEquals(44L, ((Number) auditArguments.getValue()[3]).longValue());
  }
}
