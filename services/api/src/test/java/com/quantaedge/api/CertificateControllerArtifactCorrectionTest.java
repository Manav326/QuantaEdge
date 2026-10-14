package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

class CertificateControllerArtifactCorrectionTest {
  @TempDir
  Path tempDir;

  @Test
  void legacyAssessmentCertificateIsCorrectedOnDownloadAndOriginalArtifactIsArchived() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    CertificateRenderer renderer = mock(CertificateRenderer.class);
    AuthContext student = new AuthContext(71L, 81L, "STUDENT", "Test Learner");
    when(authorization.requireStudent(student)).thenReturn(student);

    CertificateArtifactStorage storage = new CertificateArtifactStorage(tempDir.toString());
    CertificateArtifactStorage.StoredArtifact oldPdf = storage.store(new byte[] {1, 2, 3}, "pdf");
    CertificateArtifactStorage.StoredArtifact oldPng = storage.store(new byte[] {4, 5, 6}, "png");
    OffsetDateTime issuedAt = OffsetDateTime.parse("2026-10-13T12:55:00+05:30");
    Map<String, Object> legacy = new LinkedHashMap<>();
    legacy.put("id", 3001L);
    legacy.put("certificate_number", "QE-2026-ABCDEF1234567890");
    legacy.put("status", "ISSUED");
    legacy.put("achievement_type", "ASSESSMENT_PASS");
    legacy.put("class_code", "6");
    legacy.put("subject_id", 5L);
    legacy.put("subject_code", "science");
    legacy.put("subject_name", "Science");
    legacy.put("template_id", "CLASSIC");
    legacy.put("language", "hi");
    legacy.put("recipient_name", "Test Learner");
    legacy.put("achievement_title", "मूल्यांकन उपलब्धि: Perfect test");
    legacy.put("achievement_summary", "प्राप्तांक 90 / 100 (90.00%)।");
    legacy.put("achievement_snapshot_json", """
        {"rule":"RELEASED_ADMIN_CREATED_ASSESSMENT_AT_OR_ABOVE_PASS_THRESHOLD",
         "assessmentAttemptId":9001,"assessmentTitle":"Perfect test","finalScore":90,
         "maxScore":100,"scorePercent":90,"passThresholdPercent":80}
        """);
    legacy.put("issued_at", issuedAt);
    legacy.put("artifact_version", 1);
    legacy.put("pdf_storage_key", oldPdf.storageKey());
    legacy.put("png_storage_key", oldPng.storageKey());
    legacy.put("pdf_sha256", oldPdf.sha256());
    legacy.put("png_sha256", oldPng.sha256());
    legacy.put("pdf_size_bytes", oldPdf.sizeBytes());
    legacy.put("png_size_bytes", oldPng.sizeBytes());

    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0, String.class);
      if (sql.contains("FROM student_certificate c") && sql.contains("FOR UPDATE OF c")) {
        return List.of(legacy);
      }
      return List.of();
    });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(renderer.render(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
        any(OffsetDateTime.class), any(CertificateRenderer.CertificateDetails.class), eq(false)))
        .thenReturn(new CertificateRenderer.RenderedCertificate(new byte[] {9, 8, 7}, new byte[] {6, 5, 4}));

    CertificateController controller = new CertificateController(
        jdbc, authorization, renderer, storage, new ObjectMapper(), java.math.BigDecimal.valueOf(80));

    ResponseEntity<byte[]> response = controller.downloadMyCertificate(3001L, "pdf", student);

    assertEquals(200, response.getStatusCode().value());
    assertArrayEquals(new byte[] {9, 8, 7}, response.getBody());
    assertEquals("QE-2026-ABCDEF1234567890", response.getHeaders().getFirst("X-Certificate-Number"));
    assertTrue(java.nio.file.Files.exists(tempDir.resolve(oldPdf.storageKey())));
    assertTrue(java.nio.file.Files.exists(tempDir.resolve(oldPng.storageKey())));

    ArgumentCaptor<CertificateRenderer.CertificateDetails> detailsCaptor =
        ArgumentCaptor.forClass(CertificateRenderer.CertificateDetails.class);
    verify(renderer).render(eq("CLASSIC"), eq("hi"), eq("Test Learner"),
        eq("कक्षा 6 · विज्ञान"),
        eq("प्राप्तांक 90 / 100 (90.00%)।"),
        eq("QE-2026-ABCDEF1234567890"), eq(issuedAt), detailsCaptor.capture(), eq(false));
    assertEquals("Perfect test", detailsCaptor.getValue().assessmentName(),
        "The actual assessment title must be supplied as its own certificate field.");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
    assertTrue(sql.getAllValues().stream().anyMatch(value ->
        value.contains("INSERT INTO student_certificate_artifact_revision")),
        "The old artifact metadata and hashes must be retained for audit.");
    assertTrue(sql.getAllValues().stream().anyMatch(value ->
        value.contains("ARTIFACT_CORRECTED")),
        "A correction event must be recorded in the certificate audit.");
    assertTrue(sql.getAllValues().stream().anyMatch(value ->
        value.contains("SET achievement_snapshot") && value.contains("artifact_version=3")),
        "The corrected metadata and artifact version must become current.");
  }
}
