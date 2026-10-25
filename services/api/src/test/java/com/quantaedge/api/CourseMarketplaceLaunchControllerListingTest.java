package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseMarketplaceLaunchControllerListingTest {
  @Test
  void unshareableOriginAssignmentIsNotCountedAsMarketplaceDistribution() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    AuthorizationService authorization=mock(AuthorizationService.class);
    CourseMarketplaceLaunchReadinessService readiness=mock(CourseMarketplaceLaunchReadinessService.class);
    AuthContext reviewer=new AuthContext(null,null,42L,"ADMIN","HRMS reviewer","HRMS");
    CourseMarketplaceLaunchController controller=
        new CourseMarketplaceLaunchController(jdbc,authorization,readiness,new ObjectMapper());

    when(authorization.requirePermission(reviewer,"QE_PLATFORM_OWNER")).thenReturn(reviewer);
    when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(Object[].class))).thenReturn(true);
    when(jdbc.queryForObject(contains("select display_name from institution_tenant"),
        eq(String.class),any(Object[].class))).thenReturn("Quanta Edge");
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForList(contains("from institution_course_assignment a"),
        eq(Long.class),any(Object[].class))).thenReturn(List.of());

    Map<String,Object> result=controller.marketplaceLaunches(2L,reviewer);

    assertEquals(0,result.get("total"));
    assertEquals(0,result.get("qualityReviewCount"));
    assertTrue(result.containsKey("qualityReviewItems"));
    verify(jdbc).queryForList(contains("a.assignment_source <> 'ORIGIN'"),eq(Long.class),any(Object[].class));
    verify(jdbc).queryForList(contains("where r.source_institution_id=? and r.release_state='IN_QUALITY_REVIEW'"),
        any(Object[].class));
  }
}
