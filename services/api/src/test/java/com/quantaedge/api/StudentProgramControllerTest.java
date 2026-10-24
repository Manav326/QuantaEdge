package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class StudentProgramControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private final InstitutionTenantContext tenant = new InstitutionTenantContext(17L, "SCHOOL-A", "School A");
  private StudentProgramController controller;

  @BeforeEach void setUp() {
    when(authorization.requireStudent(student)).thenReturn(student);
    controller = new StudentProgramController(jdbc, authorization);
  }

  @Test
  void courseCatalogueIsBoundToTheExactInstitutionAndOnlyOwnedCourses() {
    Map<String,Object> program = new LinkedHashMap<>();
    program.put("id", 91L);
    program.put("code", "NAVODAYA_BATCH");
    program.put("display_name", "Navodaya Batch");
    program.put("enrolled", true);
    program.put("paused", false);
    program.put("purchased", false);

    when(jdbc.queryForList(contains("institution_course_assignment"), any(Object[].class)))
        .thenReturn(List.of(program));
    when(jdbc.queryForList(contains("count(distinct ch.id)"), any(Object[].class)))
        .thenReturn(List.of(Map.of("subject_code","maths","chapters",2L,"lessons",8L)));

    List<Map<String,Object>> rows = controller.programs(student, tenant);

    assertEquals(1, rows.size());
    assertEquals("NAVODAYA_BATCH", rows.getFirst().get("code"));
    assertEquals(true, rows.getFirst().get("is_enrolled"));
    assertEquals(2L, ((List<Map<String,Object>>) rows.getFirst().get("subjects")).getFirst().get("chapters"));

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).queryForList(sqlCaptor.capture(), any(Object[].class));
    assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("institution_course_assignment")));
    assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("offer.institution_id=?")));
    assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("purchase.institution_id=?")));
    assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("published_release.program_id=offer.course_id")));
    assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("spe.institution_id=?")));
    assertTrue(sqlCaptor.getAllValues().stream().anyMatch(sql -> sql.contains("ce.institution_id=?")));
    assertTrue(sqlCaptor.getAllValues().stream().noneMatch(sql -> sql.contains("not exists(select 1 from institution_membership")));
  }

  @Test
  void missingInstitutionContextFailsClosedBeforeQueryingGlobalCourseRows() {
    assertThrows(IllegalArgumentException.class, () -> controller.programs(student, null));
    verifyNoInteractions(jdbc);
  }

  @Test
  void unlistedOrPaidCourseCannotBeSelfEnrolledWithoutTenantEntitlement() {
    when(jdbc.queryForList(contains("select p.id,p.public_id,p.code,p.display_name,p.status"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id",91L,"public_id","NAV-A7K2Q","code","NAVODAYA_BATCH","display_name","Navodaya Batch","status","ACTIVE")));
    when(jdbc.queryForObject(contains("exists(select 1 from student_program_enrollment e"),
        eq(Boolean.class), any(Object[].class))).thenReturn(false);

    assertThrows(SecurityException.class, () -> controller.enrollById(91L, student, tenant));
  }
}
