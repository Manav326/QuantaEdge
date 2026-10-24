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
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class CourseMarketplaceLaunchRightsReviewTest {
  private JdbcTemplate jdbc;
  private AuthorizationService authorization;
  private ObjectMapper mapper;
  private CourseVersionLearnerMigrationService versionLearnerMigration;
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
    versionLearnerMigration = org.mockito.Mockito.mock(CourseVersionLearnerMigrationService.class);
    controller = new CourseMarketplaceLaunchController(jdbc, authorization, readiness, mapper, versionLearnerMigration);

    when(authorization.requirePermission(reviewer, "QE_PLATFORM_OWNER")).thenReturn(reviewer);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    when(mapper.writeValueAsString(any())).thenReturn("{}");
  }


  @Test
  void normalHrmsPublicationMigratesEligibleLearnersForAnAlreadyReviewedNewVersion() {
    Map<String,Object> offer = new java.util.LinkedHashMap<>();
    offer.put("id", 91L);
    offer.put("institution_id", 7L);
    offer.put("assignment_id", 92L);
    offer.put("course_release_id", 93L);
    offer.put("course_id", 1701L);
    offer.put("status", "APPROVED");
    offer.put("price_paise", 10000L);
    offer.put("currency", "INR");
    offer.put("assignment_status", "ASSIGNED");
    offer.put("release_state", "SHAREABLE");
    offer.put("course_identity_id", 701L);
    offer.put("content_version_no", 2);
    when(jdbc.queryForList(contains("select o.id,o.institution_id,o.assignment_id"), any(Object[].class)))
        .thenReturn(List.of(offer));
    when(versionLearnerMigration.grantExistingLearnersToVersion(7L, 701L, 2, 1701L)).thenReturn(3);

    Map<String,Object> result = controller.publishOffer(7L, 91L, reviewer);

    assertEquals(true, result.get("updated"));
    assertEquals("PUBLISHED", result.get("status"));
    assertEquals(3, result.get("learnerVersionGrants"));
    verify(versionLearnerMigration).grantExistingLearnersToVersion(7L, 701L, 2, 1701L);
    verify(jdbc).update(contains("set status='PUBLISHED'"), any(Object[].class));
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
  @Test
  void packageRightsClearanceRequiresEvidenceBackedHrmsReviewAttribution() {
    Instant reviewedAt=Instant.parse("2026-10-24T00:00:00Z");
    String notes="Verified publisher licence for redistribution.";
    String validEvidence="https://publisher.example.org/licence";

    assertFalse(CourseMarketplaceLaunchReadinessService.isVerifiedPackageRightsClearance(
        "CLEARED_FOR_PUBLIC_REPOSITORY",validEvidence,null,reviewedAt,notes));
    assertFalse(CourseMarketplaceLaunchReadinessService.isVerifiedPackageRightsClearance(
        "CLEARED_FOR_PUBLIC_REPOSITORY",validEvidence,42L,null,notes));
    assertFalse(CourseMarketplaceLaunchReadinessService.isVerifiedPackageRightsClearance(
        "CLEARED_FOR_PUBLIC_REPOSITORY","http://publisher.example.org/licence",42L,reviewedAt,notes));
    assertFalse(CourseMarketplaceLaunchReadinessService.isVerifiedPackageRightsClearance(
        "CLEARED_FOR_PUBLIC_REPOSITORY","https://reviewer:secret@publisher.example.org/licence",42L,reviewedAt,notes));
    assertFalse(CourseMarketplaceLaunchReadinessService.isVerifiedPackageRightsClearance(
        "DO_NOT_REDISTRIBUTE",validEvidence,42L,reviewedAt,notes));
    assertTrue(CourseMarketplaceLaunchReadinessService.isVerifiedPackageRightsClearance(
        "CLEARED_FOR_PUBLIC_REPOSITORY",validEvidence,42L,reviewedAt,notes));
  }

  @Test
  void trustedCourseAutoApprovalRequiresAnAlreadyPublishedCourseAndAuditsThePolicyChange() {
    Map<String,Object> identity = Map.of(
        "id", 701L,
        "source_institution_id", 7L,
        "trusted_auto_approval", false,
        "latest_course_id", 1701L,
        "authenticated", true);
    when(jdbc.queryForList(contains("select i.id,i.source_institution_id"), any(Object[].class)))
        .thenReturn(List.of(identity));
    when(jdbc.queryForObject(contains("from institution_course_package_import i"), eq(Boolean.class), any(Object[].class)))
        .thenReturn(false);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    Map<String,Object> result = controller.setTrustedCourseAutoApproval(7L,701L,Map.of(
        "enabled",true,
        "note","Trusted source and content lineage independently verified by HRMS."),reviewer);

    assertEquals(true,result.get("saved"));
    assertEquals(true,result.get("enabled"));
    assertEquals(701L,result.get("courseIdentityId"));
    verify(jdbc).update(contains("insert into institution_course_trust_policy("), any(Object[].class));
    verify(jdbc).update(contains("insert into institution_course_trust_policy_event"), any(Object[].class));
  }

  @Test
  void trustedCourseModeCannotBeEnabledBeforeInitialHrmsPublication() {
    Map<String,Object> identity = Map.of(
        "id", 702L,
        "source_institution_id", 7L,
        "trusted_auto_approval", false,
        "latest_course_id", 1702L,
        "authenticated", false);
    when(jdbc.queryForList(contains("select i.id,i.source_institution_id"), any(Object[].class)))
        .thenReturn(List.of(identity));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.setTrustedCourseAutoApproval(7L,702L,Map.of(
            "enabled",true,
            "note","Attempted trusted mode before the initial publication."),reviewer));

    assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
    assertTrue(error.getReason().contains("previously HRMS-published"));
    verify(jdbc, org.mockito.Mockito.never())
        .update(contains("update institution_course_identity"), any(Object[].class));
  }

  @Test
  void everyTrustedCoursePolicyChangeRequiresAnAuditableReason() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.setTrustedCourseAutoApproval(7L,703L,Map.of(
            "enabled",true,"note","no"),reviewer));

    assertEquals(HttpStatus.BAD_REQUEST,error.getStatusCode());
    assertTrue(error.getReason().contains("audit note"));
    verify(jdbc, org.mockito.Mockito.never())
        .queryForList(contains("select i.id,i.source_institution_id"), any(Object[].class));
  }

}
