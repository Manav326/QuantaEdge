package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
  private final CertificateRenderer renderer = mock(CertificateRenderer.class);
  private final AuthContext student = new AuthContext(71L, 81L, "STUDENT", "Test Learner");
  private final BigDecimal threshold = new BigDecimal("80");
  private final CertificateController controller = new CertificateController(
      jdbc, authorization, renderer,
      new CertificateArtifactStorage(System.getProperty("java.io.tmpdir")),
      new ObjectMapper(), threshold);

  @BeforeEach
  void authorizeStudentContext() {
    when(authorization.requireStudent(student)).thenReturn(student);
  }

  @Test
  void qualifyingAdminCreatedTestCanEarnCertificateWithIncompleteLessonsAndPerfectScore() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(attemptRow(
        "ADMIN_CREATED", false, "RELEASED", "100", "100", 6L, 0L, true, null)));

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
  void incompleteSubjectLessonsDoNotBlockAnOtherwiseQualifyingAssessmentCertificate() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
        attemptRow("ADMIN_CREATED", false, "RELEASED", "90", "100", 6L, 1L, true, null)));

    Map<String, Object> item = firstItem(controller.assessmentCertificateEligibility("9004", student));

    assertEquals(true, item.get("eligible"));
    assertEquals(null, item.get("eligibility_reason"));
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
  void issuanceEndpointIndependentlyRejectsGeneratedAssessmentEvenIfClientCallsItDirectly() {
    Map<String, Object> studentRow = new LinkedHashMap<>();
    studentRow.put("id", 81L);
    studentRow.put("display_name", "Test Learner");
    studentRow.put("class_code", "6");
    studentRow.put("language", "en");

    Map<String, Object> attempt = new LinkedHashMap<>();
    attempt.put("recipient_name", "Test Learner");
    attempt.put("student_language", "en");
    attempt.put("class_code", "6");
    attempt.put("subject_id", 5L);
    attempt.put("subject_code", "science");
    attempt.put("subject_name", "Science");
    attempt.put("assessment_title", "Generated topic test");
    attempt.put("assessment_type", "TOPIC_END");
    attempt.put("is_generated", true);
    attempt.put("assessment_attempt_id", 9001L);
    attempt.put("final_score", new BigDecimal("100"));
    attempt.put("max_score", new BigDecimal("100"));
    attempt.put("released_at", null);

    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(studentRow), List.of(attempt), List.of());

    org.springframework.web.server.ResponseStatusException error = assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> controller.issueCertificate(
            new CertificateController.IssueRequest("ASSESSMENT_PASS", null, 9001L, "CLASSIC", "en"),
            student));

    assertEquals(409, error.getStatusCode().value());
    assertTrue(error.getReason().contains("Only admin-created assessments"));
  }

  @Test
  void issuingPerfectAdminCreatedTestDoesNotRequireAllPublishedLessonsCompleted() {
    Map<String, Object> studentRow = new LinkedHashMap<>();
    studentRow.put("id", 81L);
    studentRow.put("display_name", "Test Learner");
    studentRow.put("class_code", "6");
    studentRow.put("language", "en");

    Map<String, Object> attempt = new LinkedHashMap<>();
    attempt.put("recipient_name", "Test Learner");
    attempt.put("student_language", "en");
    attempt.put("class_code", "6");
    attempt.put("subject_id", 5L);
    attempt.put("subject_code", "science");
    attempt.put("subject_name", "Science");
    attempt.put("assessment_title", "Perfect admin-created test");
    attempt.put("assessment_type", "ADMIN_CREATED");
    attempt.put("is_generated", false);
    attempt.put("assessment_attempt_id", 9007L);
    attempt.put("final_score", new BigDecimal("100"));
    attempt.put("max_score", new BigDecimal("100"));

    Map<String, Object> progress = new LinkedHashMap<>();
    progress.put("active_subject_track", true);
    progress.put("total_lessons", 6L);
    progress.put("completed_lessons", 0L);

    Map<String, Object> certificate = new LinkedHashMap<>();
    certificate.put("id", 3007L);
    certificate.put("certificate_number", "QE-2026-ABCDEF1234567890");
    certificate.put("achievement_type", "ASSESSMENT_PASS");
    certificate.put("status", "ISSUED");

    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0, String.class);
      if (sql.contains("FROM student WHERE id=? AND active=true FOR UPDATE")) return List.of(studentRow);
      if (sql.contains("FROM student_assessment_attempt att") && sql.contains("assessment_type")) return List.of(attempt);
      if (sql.contains("SELECT id,status FROM student_certificate WHERE student_id=? AND achievement_key=?")) {
        return List.of();
      }
      if (sql.contains("FROM student_certificate c") && sql.contains("WHERE c.id=? AND c.student_id=?")) {
        return List.of(certificate);
      }
      return List.of();
    });
    when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(progress);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3007L);
    when(renderer.render(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
        any(java.time.OffsetDateTime.class), eq(false)))
        .thenReturn(new CertificateRenderer.RenderedCertificate(new byte[] {1, 2, 3}, new byte[] {4, 5, 6}));

    Map<String, Object> issued = controller.issueCertificate(
        new CertificateController.IssueRequest("ASSESSMENT_PASS", null, 9007L, "CLASSIC", "en"), student);

    assertEquals("ASSESSMENT_PASS", issued.get("achievement_type"));
    assertEquals("QE-2026-ABCDEF1234567890", issued.get("certificate_number"));
    verify(renderer).render(eq("CLASSIC"), eq("en"), eq("Test Learner"),
        eq("Class 6 · Science"),
        org.mockito.ArgumentMatchers.contains("Perfect admin-created test"),
        anyString(), any(java.time.OffsetDateTime.class), eq(false));
  }

  @Test
  void eligibleAssessmentPickerUsesReleasedPassingAdminCreatedTestsWithoutCourseCompletionGate() {
    when(jdbc.queryForList(anyString(), eq(student.studentId()), eq(threshold))).thenReturn(List.of());

    controller.eligibleAssessments(student);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForList(sql.capture(), eq(student.studentId()), eq(threshold));
    assertTrue(sql.getValue().contains("att.status='RELEASED'"));
    assertTrue(sql.getValue().contains("a.assessment_type='ADMIN_CREATED'"));
    assertTrue(sql.getValue().contains("a.is_generated=false"));
    org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().contains("student_lesson_progress"));
    org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().contains("p.status IS DISTINCT FROM 'COMPLETED'"));
    assertTrue(sql.getValue().contains("LEFT JOIN student_certificate existing"));
    org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().contains("NOT EXISTS ("));
  }

  @Test
  void eligibleAssessmentPickerKeepsAnAlreadyIssuedAssessmentVisible() {
    Map<String, Object> row = attemptRow(
        "ADMIN_CREATED", false, "RELEASED", "100", "100", 6, 0, true, "ISSUED");
    when(jdbc.queryForList(anyString(), eq(student.studentId()), eq(threshold))).thenReturn(List.of(row));

    Map<String, Object> response = controller.eligibleAssessments(student);
    Map<String, Object> item = firstItem(response);

    assertEquals(3001L, item.get("certificate_id"));
    assertEquals("QE-2026-ABCDEF1234567890", item.get("certificate_number"));
    assertEquals("ISSUED", item.get("certificate_status"));
  }

  @Test
  void publicCertificateVerificationResolvesHumanReadableCertificateNumber() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("certificate_number", "QE-2026-ABCDEF1234567890");
    row.put("recipient_name", "Test Learner");
    row.put("achievement_type", "ASSESSMENT_PASS");
    row.put("class_code", "6");
    row.put("subject_code", "science");
    row.put("achievement_title", "Assessment achievement: Perfect admin-created test");
    row.put("issued_at", java.time.OffsetDateTime.parse("2026-10-13T12:55:00+05:30"));
    row.put("status", "ISSUED");
    row.put("revoked_at", null);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

    Map<String, Object> result = controller.verifyCertificate(" qe-2026-abcdef1234567890 ");

    assertEquals("QE-2026-ABCDEF1234567890", result.get("certificateNumber"));
    assertEquals("VALID", result.get("status"));
    assertEquals(true, result.get("verified"));
    assertEquals("Class 6 · Science", result.get("subjectTrack"));
    assertEquals("en", result.get("language"));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(sql.capture(), args.capture());
    assertTrue(sql.getValue().contains("WHERE c.certificate_number=?"));
    assertEquals("QE-2026-ABCDEF1234567890", args.getValue()[0]);
  }

  @Test
  void publicCertificateVerificationReportsRevokedCertificate() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("certificate_number", "QE-2026-ABCDEF1234567890");
    row.put("recipient_name", "Test Learner");
    row.put("achievement_type", "ASSESSMENT_PASS");
    row.put("class_code", "6");
    row.put("subject_code", "science");
    row.put("achievement_title", "Assessment achievement");
    row.put("issued_at", java.time.OffsetDateTime.parse("2026-10-13T12:55:00+05:30"));
    row.put("status", "REVOKED");
    row.put("revoked_at", java.time.OffsetDateTime.parse("2026-10-13T13:00:00+05:30"));
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

    Map<String, Object> result = controller.verifyCertificate("QE-2026-ABCDEF1234567890");

    assertEquals("REVOKED", result.get("status"));
    assertEquals(false, result.get("verified"));
  }

  @Test
  void publicCertificateVerificationReturnsNotFoundForUnknownCertificateNumber() {
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

    org.springframework.web.server.ResponseStatusException error = assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> controller.verifyCertificate("QE-2026-ABCDEF1234567890"));

    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, error.getStatusCode());
  }

  @Test
  void publicCertificateVerificationRejectsInternalOrMalformedIdentifiers() {
    assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> controller.verifyCertificate("3007"));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> controller.verifyCertificate("../QE-2026-ABCDEF1234567890"));
  }



  @Test
  void adminRegisterWiresStatusAchievementTypeAndLanguageFiltersIntoBothRowsAndCount() {
    when(authorization.requireAdmin(student)).thenReturn(student);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

    controller.adminCertificates("ISSUED", "ASSESSMENT_PASS", "en", 50, 0, student);

    ArgumentCaptor<String> rowSql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> rowArgs = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForList(rowSql.capture(), rowArgs.capture());
    assertTrue(rowSql.getValue().contains("c.status=?"));
    assertTrue(rowSql.getValue().contains("c.achievement_type=?"));
    assertTrue(rowSql.getValue().contains("c.language=?"));
    assertEquals(List.of("ISSUED", "ASSESSMENT_PASS", "en", 50, 0), List.of(rowArgs.getValue()));

    ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
    verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
    assertTrue(countSql.getValue().contains("c.status=?"));
    assertTrue(countSql.getValue().contains("c.achievement_type=?"));
    assertTrue(countSql.getValue().contains("c.language=?"));
    assertEquals(List.of("ISSUED", "ASSESSMENT_PASS", "en"), List.of(countArgs.getValue()));
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
