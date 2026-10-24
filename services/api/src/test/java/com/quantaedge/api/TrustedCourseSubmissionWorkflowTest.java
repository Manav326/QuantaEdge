package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrustedCourseSubmissionWorkflowTest {
  @Test
  void trustedVersionSubmissionIsAutoApprovedWithHrmsAttributionAndDistribution() throws Exception {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    ObjectMapper mapper = org.mockito.Mockito.mock(ObjectMapper.class);
    TrustedCourseVersionDistributionService distribution =
        org.mockito.Mockito.mock(TrustedCourseVersionDistributionService.class);
    InstitutionCourseReleaseWorkflowService workflow =
        new InstitutionCourseReleaseWorkflowService(jdbc, mapper, distribution);

    when(jdbc.queryForList(contains("where p.id=? and p.status='ACTIVE'"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id",1701L,"code","TEST_COURSE","display_name","Test course",
            "status","ACTIVE","delivery_model","LEARNING_CONTENT")));
    Map<String,Object> evidence = new HashMap<>();
    evidence.put("subject_count",1L);
    evidence.put("subjects_with_published_lessons",1L);
    evidence.put("published_chapters",1L);
    evidence.put("published_lessons",1L);
    when(jdbc.queryForMap(contains("select count(distinct s.id)"), any(Object[].class))).thenReturn(evidence);
    when(jdbc.queryForList(contains("select id,release_version,release_state"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id",91L,"release_version",2,"release_state","DRAFT")));
    when(jdbc.queryForList(contains("select coalesce(trust.enabled,false)"), any(Object[].class)))
        .thenReturn(List.of(Map.of("trusted_auto_approval",true,
            "trusted_auto_approval_changed_by_staff_id",42L,
            "trusted_auto_approval_changed_at","2026-10-24T09:00:00Z",
            "trusted_auto_approval_note","HRMS verified source lineage and content integrity.")));
    when(jdbc.queryForObject(contains("select course_content_fingerprint"), eq(String.class), any(Object[].class)))
        .thenReturn("f1ngerprint");
    when(jdbc.queryForObject(contains("select id from institution_course_assignment"), eq(Long.class), any(Object[].class)))
        .thenReturn(92L);
    when(mapper.writeValueAsString(any())).thenReturn("{}");
    Map<String,Object> distributionResult=Map.of("autoPublishedOffers",1,"heldOffers",0,
        "skippedInstitutions",0,"learnerVersionGrants",4);
    when(distribution.propagateTrustedCourseVersion(1701L,91L,7L,42L,null)).thenReturn(distributionResult);

    Map<String,Object> result=workflow.submitRelease(1701L,7L,null,null);

    assertEquals("SHAREABLE",result.get("status"));
    assertEquals(true,result.get("autoApprovedByTrustedCoursePolicy"));
    assertEquals(distributionResult,result.get("trustedDistribution"));
    verify(jdbc).update(contains("set release_state='SHAREABLE'"),any(Object[].class));
    verify(jdbc).update(contains("insert into institution_course_version_event"),any(Object[].class));
    verify(distribution).propagateTrustedCourseVersion(1701L,91L,7L,42L,null);
  }
}
