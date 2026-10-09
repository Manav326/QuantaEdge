package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationStartupSmokeTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void cleanDatabaseMigratesAndRetainsAllSixClassSubjectTracks() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.curriculum_class') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.question') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.student_track_enrollment') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version='28' and success=true
      """, Integer.class));
    assertEquals(6, jdbc.queryForObject("""
      select count(*) from curriculum_class c
      join curriculum_subject s on s.class_id=c.id
      where c.code in ('6','7','8') and s.code in ('maths','science')
      """, Integer.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from information_schema.columns
        where table_schema='public' and table_name='question' and column_name='review_status')
      """, Boolean.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from information_schema.columns
        where table_schema='public' and table_name='lesson' and column_name='alignment_source_url')
      """, Boolean.class));
    assertEquals(2, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version in ('10','13') and success=true
      """, Integer.class));
  }
}
