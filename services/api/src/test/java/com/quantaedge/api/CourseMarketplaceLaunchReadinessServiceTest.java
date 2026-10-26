package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseMarketplaceLaunchReadinessServiceTest {
  @Test
  void publishedSellerTemplateCanSatisfyFallbackForItsInstitution() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    CourseMarketplaceLaunchReadinessService service=
        new CourseMarketplaceLaunchReadinessService(jdbc,new ObjectMapper(),"","","");
    when(jdbc.queryForObject(contains("from invoice_template"),eq(Boolean.class),any(Object[].class)))
        .thenReturn(true);

    assertTrue(service.hasPublishedTemplateForInstitution(41L));
    verify(jdbc).queryForObject(contains("t.scope='SELLER'"),eq(Boolean.class),any(Object[].class));
    verify(jdbc).queryForObject(contains("le.institution_id=?"),eq(Boolean.class),any(Object[].class));
  }

  @Test
  void missingPublishedTemplateKeepsFallbackBlocked() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    CourseMarketplaceLaunchReadinessService service=
        new CourseMarketplaceLaunchReadinessService(jdbc,new ObjectMapper(),"","","");
    when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(false);

    assertFalse(service.hasPublishedTemplateForInstitution(41L));
  }

  @Test
  void archivedLegacyOfferCannotPassCurrentPipelineOrRecomputeFingerprint() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    CourseMarketplaceLaunchReadinessService service=
        new CourseMarketplaceLaunchReadinessService(jdbc,new ObjectMapper(),"","","");
    java.util.Map<String,Object> offer=new java.util.HashMap<>();
    offer.put("offer_id",1L);
    offer.put("institution_id",2L);
    offer.put("assignment_id",2L);
    offer.put("course_release_id",2L);
    offer.put("course_id",6L);
    offer.put("course_identity_id",6L);
    offer.put("course_identity_public_id","CLS6-TEST-PUBLIC");
    offer.put("course_identity_status","ACTIVE");
    offer.put("course_identity_source_institution_id",2L);
    offer.put("release_source_institution_id",2L);
    offer.put("identity_course_type_code","K12");
    offer.put("course_type_code","K12");
    offer.put("current_offer_version",false);
    offer.put("title_override","Class 6");
    offer.put("description","Class 6 course");
    offer.put("price_paise",0L);
    offer.put("currency","INR");
    offer.put("offer_status","ARCHIVED");
    offer.put("institution_name","Quanta Edge");
    offer.put("institution_status","ACTIVE");
    offer.put("assignment_status","ASSIGNED");
    offer.put("release_state","LEGACY_MIGRATED");
    offer.put("content_fingerprint","legacy-fingerprint");
    offer.put("course_status","ACTIVE");
    offer.put("course_code","CLASS_6");
    offer.put("course_name","Class 6");
    offer.put("delivery_mode","LEARNING_CONTENT");
    offer.put("subject_count",1L);
    offer.put("subjects_with_published_lessons",1L);
    offer.put("published_chapter_count",1L);
    offer.put("published_lesson_count",1L);
    when(jdbc.queryForList(contains("from institution_marketplace_offer o"),any(Object[].class)))
        .thenReturn(java.util.List.of(offer));

    java.util.Map<String,Object> result=service.inspect(1L,2L);

    assertEquals("ARCHIVED",result.get("offerStatus"));
    assertEquals("LEGACY_MIGRATED",result.get("releaseState"));
    @SuppressWarnings("unchecked")
    java.util.List<java.util.Map<String,Object>> checks=
        (java.util.List<java.util.Map<String,Object>>)result.get("checks");
    assertTrue(checks.stream().anyMatch(check ->
        "CURRENT_COURSE_RELEASE".equals(check.get("code"))
            && Boolean.FALSE.equals(check.get("passed"))));
    assertTrue(checks.stream().anyMatch(check ->
        "CONTENT_REVIEW_CURRENT".equals(check.get("code"))
            && Boolean.FALSE.equals(check.get("passed"))));
    assertTrue(checks.stream().anyMatch(check ->
        "SOURCE_RIGHTS_CLEARANCE".equals(check.get("code"))
            && Boolean.TRUE.equals(check.get("passed"))
            && String.valueOf(check.get("label")).contains("not applicable")));
    verify(jdbc,never()).queryForObject(
        eq("select course_content_fingerprint(?)"),eq(String.class),any(Object[].class));
  }


  @Test
  void inReviewReleaseDefersFingerprintAndNonImportedRightsAreExplicitlyNotApplicable() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    CourseMarketplaceLaunchReadinessService service=
        new CourseMarketplaceLaunchReadinessService(jdbc,new ObjectMapper(),"","","");
    java.util.Map<String,Object> assignment=new java.util.HashMap<>();
    assignment.put("assignment_id",10L);
    assignment.put("institution_id",2L);
    assignment.put("assignment_status","ASSIGNED");
    assignment.put("release_id",10L);
    assignment.put("release_state","IN_QUALITY_REVIEW");
    assignment.put("content_fingerprint","submitted-fingerprint");
    assignment.put("institution_name","Quanta Edge");
    assignment.put("institution_status","ACTIVE");
    assignment.put("course_id",14L);
    assignment.put("course_identity_id",14L);
    assignment.put("course_identity_public_id","TRE4-TEST-PUBLIC");
    assignment.put("course_identity_status","ACTIVE");
    assignment.put("course_identity_source_institution_id",2L);
    assignment.put("release_source_institution_id",2L);
    assignment.put("identity_course_type_code","ASSESSMENT");
    assignment.put("content_version_no",1);
    assignment.put("release_version",1);
    assignment.put("current_release_assignment",false);
    assignment.put("course_code","I2_TRE4");
    assignment.put("course_name","TRE-4 · Computer Science");
    assignment.put("course_status","ACTIVE");
    assignment.put("course_type_code","ASSESSMENT");
    assignment.put("delivery_mode","ASSESSMENT_ONLY");
    assignment.put("subject_count",0L);
    assignment.put("subjects_with_published_lessons",0L);
    assignment.put("published_chapter_count",0L);
    assignment.put("published_lesson_count",0L);
    assignment.put("offer_id",null);
    when(jdbc.queryForList(contains("from institution_course_assignment a"),any(Object[].class)))
        .thenReturn(java.util.List.of(assignment));

    java.util.Map<String,Object> result=service.inspectAssignment(10L,2L);

    assertEquals("NOT_CONFIGURED",result.get("offerStatus"));
    assertEquals("IN_QUALITY_REVIEW",result.get("releaseState"));
    @SuppressWarnings("unchecked")
    java.util.List<java.util.Map<String,Object>> checks=
        (java.util.List<java.util.Map<String,Object>>)result.get("checks");
    assertTrue(checks.stream().anyMatch(check ->
        "CONTENT_REVIEW_CURRENT".equals(check.get("code"))
            && Boolean.FALSE.equals(check.get("passed"))));
    assertTrue(checks.stream().anyMatch(check ->
        "CURRENT_COURSE_RELEASE".equals(check.get("code"))
            && Boolean.FALSE.equals(check.get("passed"))));
    assertTrue(checks.stream().anyMatch(check ->
        "SOURCE_RIGHTS_CLEARANCE".equals(check.get("code"))
            && Boolean.TRUE.equals(check.get("passed"))
            && String.valueOf(check.get("label")).contains("not applicable")));
    verify(jdbc,never()).queryForObject(
        eq("select course_content_fingerprint(?)"),eq(String.class),any(Object[].class));
  }

}
