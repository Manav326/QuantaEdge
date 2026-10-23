package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class CourseAuthoringPackageTest {
  private CourseAuthoringService service() {
    return new CourseAuthoringService(org.mockito.Mockito.mock(JdbcTemplate.class),new ObjectMapper());
  }

  private Map<String,Object> validPackage() {
    Map<String,Object> block=Map.of("sequence_no",1,"block_type","EXPLANATION",
        "content",Map.of("heading","Fractions","body","A fraction represents equal parts."));
    Map<String,Object> lesson=Map.of("code","fraction-basics","title","Fraction basics",
        "estimated_minutes",10,"blocks",List.of(block));
    Map<String,Object> chapter=Map.of("code","fraction-basics","display_name","Fractions",
        "lessons",List.of(lesson));
    Map<String,Object> subject=Map.of("code","maths","display_name","Mathematics",
        "chapters",List.of(chapter));
    Map<String,Object> course=Map.of("code","CLASS6_MATHS","display_name","Class 6 Mathematics",
        "course_type_code","K12","curriculum_version","NCERT-2026","class_code","6");
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("schema","quantaedge.course-package.v1");
    body.put("package_id","class6-maths-fractions");
    body.put("package_version","1.0.0");
    body.put("course",course);
    body.put("subjects",List.of(subject));
    return body;
  }

  @Test
  void validatesCompleteNestedK12HierarchyAndCountsEveryLevel() {
    Map<String,Object> result=service().validateCoursePackage(validPackage());
    assertEquals(true,result.get("valid"));
    assertEquals(1,result.get("subjectCount"));
    assertEquals(1,result.get("chapterCount"));
    assertEquals(1,result.get("lessonCount"));
    assertEquals(1,result.get("blockCount"));
    assertEquals("DRAFT",result.get("releaseStatus"));
    assertEquals(false,result.get("studentDeliveryAllowed"));
  }

  @Test
  void rejectsUnsupportedCourseTypeRatherThanPretendingK12HierarchyFits() {
    Map<String,Object> body=validPackage();
    body.put("course",new LinkedHashMap<>((Map<String,Object>)body.get("course")));
    ((Map<String,Object>)body.get("course")).put("course_type_code","COMPETITIVE");
    Map<String,Object> result=service().validateCoursePackage(body);
    assertEquals(false,result.get("valid"));
    assertTrue(((List<String>)result.get("errors")).stream()
        .anyMatch(e->e.contains("type-specific package workflow")));
  }

  @Test
  void rejectsDuplicateAndUnsupportedNestedFields() {
    Map<String,Object> body=validPackage();
    Map<String,Object> subject=new LinkedHashMap<>((Map<String,Object>)((List<?>)body.get("subjects")).getFirst());
    subject.put("unexpected","must not be silently ignored");
    body.put("subjects",List.of(subject,subject));
    Map<String,Object> result=service().validateCoursePackage(body);
    assertFalse(Boolean.TRUE.equals(result.get("valid")));
    List<String> errors=(List<String>)result.get("errors");
    assertTrue(errors.stream().anyMatch(e->e.contains("unsupported property 'unexpected'")));
    assertTrue(errors.stream().anyMatch(e->e.contains("subject.code must be unique")));
  }

  @Test
  void rejectsInvalidBlockTypesAndDuplicateSequencesBeforeDatabaseWrites() {
    Map<String,Object> body=validPackage();
    Map<String,Object> altered=new LinkedHashMap<>((Map<String,Object>)((List<?>)((Map<?,?>)((List<?>)((Map<?,?>)((List<?>)body.get("subjects")).getFirst()).get("chapters")).getFirst()).get("lessons")).getFirst());
    altered.put("blocks",List.of(
        Map.of("sequence_no",1,"block_type","DROP_TABLE","content",Map.of()),
        Map.of("sequence_no",1,"block_type","EXPLANATION","content",Map.of())));
    @SuppressWarnings("unchecked")
    Map<String,Object> subject=new LinkedHashMap<>((Map<String,Object>)((List<?>)body.get("subjects")).getFirst());
    @SuppressWarnings("unchecked")
    Map<String,Object> chapter=new LinkedHashMap<>((Map<String,Object>)((List<?>)subject.get("chapters")).getFirst());
    chapter.put("lessons",List.of(altered));
    subject.put("chapters",List.of(chapter));
    body.put("subjects",List.of(subject));
    List<String> errors=(List<String>)service().validateCoursePackage(body).get("errors");
    assertTrue(errors.stream().anyMatch(e->e.contains("block_type is not supported")));
    assertTrue(errors.stream().anyMatch(e->e.contains("sequence_no must be unique")));
  }
}
