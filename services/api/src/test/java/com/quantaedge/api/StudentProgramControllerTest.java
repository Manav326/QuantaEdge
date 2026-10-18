package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class StudentProgramControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private StudentProgramController controller;

  @BeforeEach void setUp() {
    when(authorization.requireStudent(student)).thenReturn(student);
    controller = new StudentProgramController(jdbc, authorization);
  }

  @Test
  void courseCatalogueReturnsOnlyActiveProgramsForTheStudentsOwnClass() {
    Map<String,Object> program = new LinkedHashMap<>();
    program.put("id", 91L);
    program.put("code", "NAVODAYA_BATCH");
    program.put("display_name", "Navodaya Batch");
    program.put("enrolled", true);
    when(jdbc.queryForList(contains("p.code<>'GENERAL'"), eq(7L))).thenReturn(List.of(program));
    when(jdbc.queryForList(contains("count(distinct ch.id)"), any(Object[].class)))
        .thenReturn(List.of(Map.of("subject_code","maths","chapters",2L,"lessons",8L)));

    List<Map<String,Object>> rows = controller.programs(student);

    assertEquals(1, rows.size());
    assertEquals("NAVODAYA_BATCH", rows.getFirst().get("code"));
    assertEquals(true, rows.getFirst().get("is_enrolled"));
    assertEquals(2L, ((List<Map<String,Object>>) rows.getFirst().get("subjects")).getFirst().get("chapters"));
  }

  @Test
  void unlistedOrPaidCourseCannotBeSelfEnrolledWithoutExistingEntitlement() {
    when(jdbc.queryForList(contains("where p.id=? and p.status='ACTIVE'"), eq(7L), eq(91L)))
        .thenReturn(List.of(Map.of("id",91L,"code","NAVODAYA_BATCH","display_name","Navodaya Batch","status","ACTIVE")));
    when(jdbc.queryForObject(contains("course_marketplace_offer"), eq(Boolean.class),
        eq(7L), eq(91L), eq(7L), eq(91L), eq(7L), eq(91L), eq(91L), eq(91L), eq(7L))).thenReturn(false);

    assertThrows(SecurityException.class, () -> controller.enrollById(91L, student));
  }

  @Test
  void institutionMembershipCanAuthoriseCourseWhenPlatformGrantedEntitlementIsActive() {
    when(jdbc.queryForList(contains("where p.id=? and p.status='ACTIVE'"), eq(7L), eq(91L)))
        .thenReturn(List.of(Map.of("id",91L,"code","NAVODAYA_BATCH","display_name","Navodaya Batch","status","ACTIVE")));
    when(jdbc.queryForObject(contains("institution_course_entitlement"), eq(Boolean.class),
        eq(7L), eq(91L), eq(7L), eq(91L), eq(7L), eq(91L), eq(91L), eq(91L), eq(7L))).thenReturn(true);
    when(jdbc.queryForList(contains("where ps.program_id=? order by"), eq(91L)))
        .thenReturn(List.of(Map.of("id",901L,"code","maths")));
    when(jdbc.update(contains("update student_program_enrollment set status='PAUSED'"), eq(7L), eq(91L))).thenReturn(0);
    when(jdbc.update(contains("insert into student_program_enrollment"), eq(7L), eq(91L))).thenReturn(1);
    when(jdbc.update(contains("insert into student_track_enrollment"), eq(7L), eq(901L))).thenReturn(1);

    Map<String,Object> result = controller.enrollById(91L, student);

    assertTrue(Boolean.TRUE.equals(result.get("enrolled")));
    assertEquals("NAVODAYA_BATCH", result.get("programCode"));
  }

  @Test
  void enrolmentUsesExistingClassIdentityAndCourseSubjects() {
    when(jdbc.queryForList(contains("upper(p.code)=?"), eq(7L), eq("NAVODAYA_BATCH")))
        .thenReturn(List.of(Map.of("id",91L,"code","NAVODAYA_BATCH","display_name","Navodaya Batch","class_code","6")));
    when(jdbc.queryForList(contains("where p.id=? and p.status='ACTIVE'"), eq(7L), eq(91L)))
        .thenReturn(List.of(Map.of("id",91L,"code","NAVODAYA_BATCH","display_name","Navodaya Batch","status","ACTIVE")));
    when(jdbc.queryForObject(contains("course_marketplace_offer"), eq(Boolean.class),
        eq(7L), eq(91L), eq(7L), eq(91L), eq(7L), eq(91L), eq(91L), eq(91L), eq(7L))).thenReturn(true);
    when(jdbc.queryForList(contains("where ps.program_id=? order by"), eq(91L)))
        .thenReturn(List.of(Map.of("id",901L,"code","maths"),Map.of("id",902L,"code","science")));
    when(jdbc.update(contains("update student_program_enrollment set status='PAUSED'"), eq(7L), eq(91L))).thenReturn(0);
    when(jdbc.update(contains("insert into student_program_enrollment"), eq(7L), eq(91L))).thenReturn(1);
    when(jdbc.update(contains("insert into student_track_enrollment"), eq(7L), eq(901L))).thenReturn(1);
    when(jdbc.update(contains("insert into student_track_enrollment"), eq(7L), eq(902L))).thenReturn(1);

    Map<String,Object> result = controller.enroll("navodaya_batch", student);

    assertTrue(Boolean.TRUE.equals(result.get("enrolled")));
    assertEquals("NAVODAYA_BATCH", result.get("programCode"));
    assertEquals(2, result.get("subjectCount"));
  }
}
