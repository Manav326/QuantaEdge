package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationStartupSmokeTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ContentTranslationController contentTranslationController;


  @Test
  void bilingualSchemaExistsAndTranslationInventoryQueryExecutes() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.curriculum_program') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.lesson_translation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.lesson_block_translation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.question_translation') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history
      where version='51' and success=true
      """, Integer.class));

    AuthContext admin = new AuthContext(null, null, 999L, "ADMIN", "CI admin");
    Map<String,Object> result = contentTranslationController.list(null, null, "ALL", "ALL", "ALL", 20, 0, admin);
    assertTrue(result.get("items") instanceof List<?>);
    assertTrue(result.get("total") instanceof Number);
    Map<String,Object> sourceInventory = contentTranslationController.inventory("", "", "ALL", "ALL", 20, 0, admin);
    assertTrue(sourceInventory.get("items") instanceof List<?>);
    assertTrue(sourceInventory.get("total") instanceof Number);
    assertTrue(((Number) sourceInventory.get("total")).longValue() > 0, "Untranslated source inventory should include current class/subject content.");
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> filteredItems = (List<Map<String,Object>>) contentTranslationController
        .inventory("6", "maths", "en", "ALL", 20, 0, admin).get("items");
    assertTrue(!filteredItems.isEmpty(), "Class 6 Mathematics source should be filterable for English.");
    assertTrue(filteredItems.stream().allMatch(row -> "6".equals(String.valueOf(row.get("class_code")))
        && "maths".equals(String.valueOf(row.get("subject_code")))
        && "en".equals(String.valueOf(row.get("language")))),
        "Source inventory results must match class, subject and target-language filters.");
  }

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
