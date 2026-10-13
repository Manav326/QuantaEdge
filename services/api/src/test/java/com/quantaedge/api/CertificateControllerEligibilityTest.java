package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class CertificateControllerEligibilityTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AuthorizationService authorization = mock(AuthorizationService.class);
  private final AuthContext student = new AuthContext(71L, 81L, "STUDENT", "Test Learner");
  private final BigDecimal threshold = new BigDecimal("80");
  private final CertificateController controller = new CertificateController(
      jdbc, authorization, new CertificateRenderer(),
      new CertificateArtifactStorage(System.getProperty("java.io.tmpdir")),
      new ObjectMapper(), threshold);

  @BeforeEach
  void authorizeStudentContext() {
    when(authorization.requireStudent(student)).thenReturn(student);
  }

  @Test
  void qualifyingAdminCreatedTestRequiresCompletedSubjectAndPassThreshold() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(attemptRow(
        "ADMIN_CREATED", false, "RELEASED", "80", "100", 6L, 6L, true, null)));

    Map<String, Object> response = controller.assessmentCertificateEligibility("9001", student);
    Map<String, Object> item = firstItem(response);

    assertEquals(true, item.get("eligible"));
    assertEquals(null, item.get("eligibility_reason"));
    assertEquals(threshold, response.get("passThresholdPercent"));
  }

  @Test
  void generatedTopicAndChapterTestsAreRejectedEvenWhenScoreAndProgressQualify() {
    for (String type : List.of("TOPIC_END", "CHAPTER_END")) {
      when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
          attemptRow(type, true, "RELEASED", "95", "100", 6L, 6L, true, null)));

      Map<String, Object> item = firstItem(controller.assessmentCertificateEligibility("9002", student));

      assertEquals(false, item.get("eligible"));
      assertEquals("ADMIN_CREATED_TEST_ONLY", item.get("eligibility_reason"));
    }
  }

  @Test
  void thresholdUsesExactRatioRatherThanRoundedDisplayPercent() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        attemptRow("ADMIN_CREATED", false, "RELEASED", "79.999", "100", 6L, 6L, true, null)));

    Map<String, Object> item = firstItem(controller.assessmentCertificateEligibility("9003", student));

    assertEquals(false, item.get("eligible"));
    assertEquals("PASS_THRESHOLD_NOT_MET", item.get("eligibility_reason"));
  }

  @Test
  void allPublishedLessonsMustBeCompletedBeforeAssessmentCertificateIsEligible() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        attemptRow("ADMIN_CREATED", false, "RELEASED", "90", "100", 6L, 5L, true, null)));

    Map<String, Object> item = firstItem(controller.assessmentCertificateEligibility("9004", student));

    assertEquals(false, item.get("eligible"));
    assertEquals("SUBJECT_COMPLETION_REQUIRED", item.get("eligibility_reason"));
  }

  @Test
  void issuedAndRevokedCertificatesAreSurfacedWithoutOfferingAnotherIssuance() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        attemptRow("ADMIN_CREATED", false, "RELEASED", "95", "100", 6L, 6L, true, "ISSUED")));

    Map<String, Object> issued = firstItem(controller.assessmentCertificateEligibility("9005", student));
    assertEquals(false, issued.get("eligible"));
    assertEquals("ALREADY_ISSUED", issued.get("eligibility_reason"));

    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        attemptRow("ADMIN_CREATED", false, "RELEASED", "95", "100", 6L, 6L, true, "REVOKED")));
    Map<String, Object> revoked = firstItem(controller.assessmentCertificateEligibility("9006", student));
    assertEquals(false, revoked.get("eligible"));
    assertEquals("CERTIFICATE_REVOKED", revoked.get("eligibility_reason"));
  }

  @Test
  void eligibleAssessmentPickerQueryEnforcesAdminSourceAndSubjectCompletion() {
    when(jdbc.queryForList(anyString(), eq(student.studentId()), eq(threshold))).thenReturn(List.of());

    controller.eligibleAssessments(student);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(student.studentId()), eq(threshold));
    assertTrue(sql.getValue().contains("a.assessment_type='ADMIN_CREATED'"));
    assertTrue(sql.getValue().contains("a.is_generated=false"));
    assertTrue(sql.getValue().contains("p.status IS DISTINCT FROM 'COMPLETED'"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> firstItem(Map<String, Object> response) {
    return ((List<Map<String, Object>>) response.get("items")).get(0);
  }

  private Map<String, Object> attemptRow(String type, boolean generated, String status,
      String score, String maxScore, long publishedLessons, long completedLessons,
      boolean activeSubjectTrack, String certificateStatus) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("attempt_id", 9001L);
    row.put("assessment_title", "Admin assessment");
    row.put("assessment_type", type);
    row.put("is_generated", generated);
    row.put("status", status);
    row.put("final_score", new BigDecimal(score));
    row.put("max_score", new BigDecimal(maxScore));
    row.put("released_at", null);
    row.put("subject_id", 5L);
    row.put("subject_code", "science");
    row.put("subject_name", "Science");
    row.put("class_code", "6");
    row.put("score_percent", new BigDecimal(score).multiply(BigDecimal.valueOf(100))
        .divide(new BigDecimal(maxScore), 2, java.math.RoundingMode.HALF_UP));
    row.put("active_subject_track", activeSubjectTrack);
    row.put("published_lessons", publishedLessons);
    row.put("completed_lessons", completedLessons);
    row.put("certificate_id", certificateStatus == null ? null : 3001L);
    row.put("certificate_number", certificateStatus == null ? null : "QE-2026-ABCDEF1234567890");
    row.put("certificate_status", certificateStatus);
    return row;
  }
}
