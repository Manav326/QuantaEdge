package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class GuardianCourseEnrollmentTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private AuthService auth;
  @Mock private LearningMetricsService learningMetrics;

  private final AuthContext parent = new AuthContext(90L, null, "PARENT", "Parent");
  private final InstitutionTenantContext tenant = new InstitutionTenantContext(17L, "SCHOOL-A", "School A");
  private GuardianController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireParent(parent)).thenReturn(parent);
    controller = new GuardianController(jdbc, authorization, auth, learningMetrics);
  }

  @Test
  void parentCanAssignAnIndependentCourseOnlyToAChildWithFullConsentedGuardianAccess() {
    when(jdbc.queryForObject(contains("select exists(select 1 from guardian_student"), eq(Boolean.class), eq(90L), eq(70L)))
        .thenReturn(true);
    when(jdbc.queryForObject(contains("select exists(select 1 from institution_membership m"),
        eq(Boolean.class), eq(17L), eq(70L))).thenReturn(true);
    when(jdbc.queryForList(contains("from curriculum_program p"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 91L, "public_id", "NAV-A7K2Q", "code", "NAVODAYA_BATCH", "display_name", "Navodaya Batch")));
    when(jdbc.queryForList(contains("from curriculum_program_subject ps"), eq(91L)))
        .thenReturn(List.of(Map.of("id", 901L, "code", "maths")));
    when(jdbc.update(contains("insert into student_program_enrollment"), any(Object[].class))).thenReturn(1);
    when(jdbc.update(contains("insert into student_track_enrollment"), any(Object[].class))).thenReturn(1);

    Map<String,Object> result = controller.enrollChildInProgram(70L, 91L, parent, tenant);

    assertTrue(Boolean.TRUE.equals(result.get("enrolled")));
    assertEquals(70L, result.get("studentId"));
    assertEquals(91L, result.get("courseId"));
    assertEquals(17L, result.get("institutionId"));
    assertEquals("NAV-A7K2Q", result.get("publicId"));
    assertEquals(1, result.get("subjectCount"));
    verify(auth).auditChildEvent(90L, 70L, "COURSE_ENROLLED", "{\"courseId\":91,\"institutionId\":17,\"subjectCount\":1}");
  }
}
