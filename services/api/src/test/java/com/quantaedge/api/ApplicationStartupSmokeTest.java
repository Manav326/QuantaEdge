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

  @Autowired
  private AdminContentController adminContentController;


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
  void practiceBankDeduplicationUsesFullQuestionContentNotPromptAlone() {
    Map<String,Object> incoming = new java.util.LinkedHashMap<>();
    incoming.put("question_type", "MCQ");
    incoming.put("prompt", "कौन-सा कथन सही है?");
    incoming.put("difficulty", "CORE");
    incoming.put("marks", 1);
    incoming.put("exam_format", "Topic practice");
    incoming.put("explanation", "पहला कथन सही है।");
    incoming.put("answer_payload", Map.of("kind", "OPTION", "value", "A"));
    incoming.put("options", List.of(
        Map.of("key", "A", "label", "रेखाखंड के दो अंत-बिंदु होते हैं", "correct", true, "sort_order", 1),
        Map.of("key", "B", "label", "रेखा के दो अंत-बिंदु होते हैं", "correct", false, "sort_order", 2)));

    Map<String,Object> stored = new java.util.LinkedHashMap<>();
    stored.put("question_type", "MCQ");
    stored.put("prompt", "  कौन-सा   कथन सही है? ");
    stored.put("difficulty", "CORE");
    stored.put("marks", 1);
    stored.put("exam_format", "Topic practice");
    stored.put("explanation", "पहला कथन सही है।");
    stored.put("answer_payload", "{\"value\":\"A\",\"kind\":\"OPTION\"}");
    stored.put("options", "[{\"key\":\"A\",\"label\":\"रेखाखंड के दो अंत-बिंदु होते हैं\",\"correct\":true,\"sort_order\":1},"
        + "{\"key\":\"B\",\"label\":\"रेखा के दो अंत-बिंदु होते हैं\",\"correct\":false,\"sort_order\":2}]");

    String incomingFingerprint = adminContentController.questionContentFingerprint(incoming);
    assertEquals(incomingFingerprint, adminContentController.questionContentFingerprint(stored));

    Map<String,Object> differentChoices = new java.util.LinkedHashMap<>(incoming);
    differentChoices.put("options", List.of(
        Map.of("key", "A", "label", "रेखा के दो अंत-बिंदु होते हैं", "correct", true, "sort_order", 1),
        Map.of("key", "B", "label", "रेखाखंड के दो अंत-बिंदु होते हैं", "correct", false, "sort_order", 2)));
    assertTrue(!incomingFingerprint.equals(adminContentController.questionContentFingerprint(differentChoices)),
        "Same prompt with different answer options must not be treated as the same question.");
  }

  @Test
  void cleanDatabaseMigratesPeopleOperationsFoundationWithoutCreatingFakeEmployees() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_organization') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_department') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_designation') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_employee') is not null", Boolean.class));
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_employee_history') is not null", Boolean.class));
    assertEquals(1, jdbc.queryForObject("""
      select count(*) from flyway_schema_history where version='60' and success=true
      """, Integer.class));
    assertEquals(1, jdbc.queryForObject("select count(*) from hr_organization where name='QuantaEdge'", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from hr_employee", Integer.class),
        "A clean installation must not invent employee records.");
    assertEquals(7, jdbc.queryForObject("select count(*) from hr_employment_type where active=true", Integer.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from staff_permission_catalog where permission_key='HR_EMPLOYEE_VIEW')
      """, Boolean.class));
    assertTrue(jdbc.queryForObject("""
      select exists(select 1 from staff_permission_catalog where permission_key='HR_EMPLOYEE_MANAGE')
      """, Boolean.class));
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
