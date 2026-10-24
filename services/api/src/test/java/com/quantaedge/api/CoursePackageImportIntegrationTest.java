package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CoursePackageImportIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CourseAuthoringService authoring;

  @Test
  @Transactional
  void packageImportRetainsSourceJsonButDoesNotTrustItsRightsClaim() {
    Long institutionId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
    String packageId = "integration-package-" + suffix;
    Map<String,Object> body = validPackage(suffix);

    Map<String,Object> imported = authoring.importCoursePackage(institutionId, "QUANTA-EDGE", null, body);
    assertEquals(true, imported.get("created"));
    assertEquals(false, imported.get("replayed"));
    assertEquals("DRAFT", imported.get("releaseStatus"));
    assertEquals(false, imported.get("studentDeliveryAllowed"));
    long courseId = ((Number) imported.get("courseId")).longValue();
    long releaseId = ((Number) imported.get("releaseId")).longValue();

    Map<String,Object> ledger = jdbc.queryForMap("""
      select state,editorial_metadata->>'source_rights_status' as rights_status,
        editorial_metadata->>'notes' as rights_notes,rights_reviewed_by_staff_id,
        rights_reviewed_at,rights_review_notes,package_sha256
      from institution_course_package_import
      where source_institution_id=? and package_id=? and package_version='1.0.0'
      """, institutionId, packageId);
    assertEquals("IMPORTED", ledger.get("state"));
    assertEquals("UNCONFIRMED", ledger.get("rights_status"));
    assertEquals("Source declaration included by the package author.", ledger.get("rights_notes"));
    assertEquals(null, ledger.get("rights_reviewed_by_staff_id"));
    assertEquals(null, ledger.get("rights_reviewed_at"));
    assertEquals(null, ledger.get("rights_review_notes"));
    assertNotNull(ledger.get("package_sha256"));
    assertTrue(jdbc.queryForObject("""
      select package_snapshot @> ?::jsonb
      from institution_course_package_import
      where source_institution_id=? and package_id=? and package_version='1.0.0'
      """, Boolean.class,
      "{\"editorial\":{\"source_rights_status\":\"CLEARED_FOR_PUBLIC_REPOSITORY\"}}",
      institutionId, packageId),
      "The source declaration is retained as evidence but is not an HRMS decision.");

    assertEquals("DRAFT", jdbc.queryForObject(
        "select release_state from institution_course_release where id=?", String.class, releaseId));
    assertEquals(1L, jdbc.queryForObject("""
      select count(*) from curriculum_chapter ch
      join curriculum_program_subject ps on ps.subject_id=ch.subject_id
      where ps.program_id=? and ch.content_status='DRAFT'
      """, Long.class, courseId));
    assertEquals(1L, jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_program_subject ps on ps.subject_id=ch.subject_id
      where ps.program_id=? and l.status='DRAFT' and l.alignment_source_verified=false
      """, Long.class, courseId));
    assertEquals(1L, jdbc.queryForObject("""
      select count(*) from lesson_block b
      join lesson l on l.id=b.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_program_subject ps on ps.subject_id=ch.subject_id
      where ps.program_id=? and b.block_type='EXPLANATION' and b.active=true
      """, Long.class, courseId));

    Map<String,Object> replay = authoring.importCoursePackage(institutionId, "QUANTA-EDGE", null, body);
    assertEquals(true, replay.get("replayed"));
    assertEquals(courseId, ((Number) replay.get("courseId")).longValue());
    assertEquals(releaseId, ((Number) replay.get("releaseId")).longValue());
    assertEquals(1L, jdbc.queryForObject("""
      select count(*) from institution_course_package_import
      where source_institution_id=? and package_id=? and package_version='1.0.0'
      """, Long.class, institutionId, packageId));

    Map<String,Object> changed = new LinkedHashMap<>(body);
    Map<String,Object> edited = new LinkedHashMap<>((Map<String,Object>) body.get("editorial"));
    edited.put("notes", "Changed source notes require an incremented package version.");
    changed.put("editorial", edited);
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> authoring.importCoursePackage(institutionId, "QUANTA-EDGE", null, changed));
    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
  }

  private static Map<String,Object> validPackage(String suffix) {
    Map<String,Object> block = Map.of("sequence_no",1,"block_type","EXPLANATION",
        "content",Map.of("heading","Import fixture","body","Test-only draft block."));
    Map<String,Object> lesson = Map.of("code","lesson-"+suffix,"title","Import fixture lesson",
        "summary","Draft lesson created by integration test.","estimated_minutes",10,
        "sort_order",1,"blocks",List.of(block));
    Map<String,Object> chapter = Map.of("code","chapter-"+suffix,"display_name","Import fixture chapter",
        "description","Disposable test transaction chapter.","sort_order",1,
        "source",Map.of("title","Test package source","edition","Fixture 1","pages","1"),
        "lessons",List.of(lesson));
    Map<String,Object> subject = Map.of("code","maths"+suffix,"display_name","Mathematics",
        "sort_order",1,"chapters",List.of(chapter));
    Map<String,Object> course = Map.of("code","PKG"+suffix.toUpperCase(Locale.ROOT),
        "display_name","Portable import fixture "+suffix,"course_type_code","K12",
        "curriculum_version","TEST-2026","class_code","6");
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("schema","quantaedge.course-package.v1");
    body.put("package_id","integration-package-"+suffix);
    body.put("package_version","1.0.0");
    body.put("course",course);
    body.put("subjects",List.of(subject));
    body.put("editorial",Map.of("notes","Source declaration included by the package author.",
        "source_rights_status","CLEARED_FOR_PUBLIC_REPOSITORY"));
    return body;
  }
}
