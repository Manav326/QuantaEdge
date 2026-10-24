package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
