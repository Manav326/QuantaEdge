package com.quantaedge.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Earned certificates, private artifact delivery, public verification, and admin revocation. */
@RestController
@RequestMapping("/api/v1")
public class CertificateController {
  private static final String CERTIFICATE_COLUMNS = """
      c.id,c.certificate_number,c.achievement_type,c.class_code,c.subject_id,s.code AS subject_code,
      s.display_name AS subject_name,c.assessment_attempt_id,c.template_id,c.language,
      c.recipient_name,c.achievement_title,c.achievement_summary,c.status,c.issued_at,
      c.revoked_at,c.revocation_reason
      """;
  private static final String CERTIFICATE_JOIN = """
      FROM student_certificate c
      LEFT JOIN curriculum_subject s ON s.id=c.subject_id
      """;
  private static final String CERTIFICATE_NUMBER_PATTERN = "QE-[0-9]{4}-[A-F0-9]{16}";

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final CertificateRenderer renderer;
  private final CertificateArtifactStorage storage;
  private final ObjectMapper objectMapper;
  private final BigDecimal minimumPassPercent;
  private final SecureRandom secureRandom = new SecureRandom();

  public record IssueRequest(String achievementType, String subjectCode, Long assessmentAttemptId,
      String templateId, String language) {}

  public CertificateController(JdbcTemplate jdbc, AuthorizationService authorization,
      CertificateRenderer renderer, CertificateArtifactStorage storage, ObjectMapper objectMapper,
      @Value("$" + "{app.certificates.assessment-pass-percent:80}") BigDecimal minimumPassPercent) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.renderer = renderer;
    this.storage = storage;
    this.objectMapper = objectMapper;
    if (minimumPassPercent.compareTo(BigDecimal.ZERO) < 0
        || minimumPassPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
      throw new IllegalArgumentException("Certificate pass threshold must be between 0 and 100.");
    }
    this.minimumPassPercent = minimumPassPercent;
  }

  @GetMapping("/students/me/certificates")
  public Map<String, Object> myCertificates(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> items = jdbc.queryForList("""
        SELECT """ + CERTIFICATE_COLUMNS + " " + CERTIFICATE_JOIN + """
        WHERE c.student_id=?
        ORDER BY c.issued_at DESC,c.id DESC
        LIMIT 200
        """, context.studentId());
    return Map.of("items", items);
  }

  @GetMapping("/guardians/children/{studentId}/certificates")
  public Map<String, Object> childCertificates(@PathVariable long studentId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireParent(context);
    requireGuardianChild(context, studentId);
    List<Map<String, Object>> items = jdbc.queryForList("""
        SELECT """ + CERTIFICATE_COLUMNS + " " + CERTIFICATE_JOIN + """
        WHERE c.student_id=?
        ORDER BY c.issued_at DESC,c.id DESC
        LIMIT 200
        """, studentId);
    return Map.of("items", items, "studentId", studentId);
  }

  @GetMapping("/guardians/children/{studentId}/certificates/{certificateId}/view")
  public ResponseEntity<byte[]> viewChildCertificate(@PathVariable long studentId,
      @PathVariable long certificateId, @RequestParam(defaultValue = "pdf") String format,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireParent(context);
    requireGuardianChild(context, studentId);
    return certificateArtifact(certificateId, studentId, format, true);
  }

  @GetMapping("/guardians/children/{studentId}/certificates/{certificateId}/download")
  public ResponseEntity<byte[]> downloadChildCertificate(@PathVariable long studentId,
      @PathVariable long certificateId, @RequestParam(defaultValue = "pdf") String format,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireParent(context);
    requireGuardianChild(context, studentId);
    return certificateArtifact(certificateId, studentId, format, false);
  }

  private void requireGuardianChild(AuthContext context, long studentId) {
    Boolean allowed = jdbc.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM guardian_student gs
          JOIN student st ON st.id=gs.student_id
          WHERE gs.guardian_user_id=? AND gs.student_id=? AND gs.active=true
            AND gs.consent_status='CONSENTED' AND st.active=true
        )
        """, Boolean.class, context.userId(), studentId);
    if (!Boolean.TRUE.equals(allowed)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Child not found.");
    }
  }

  @GetMapping("/students/me/certificates/eligible-assessments")
  public Map<String, Object> eligibleAssessments(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> items = jdbc.queryForList("""
        SELECT att.id AS attempt_id,a.title AS assessment_title,a.assessment_type,
               att.final_score,att.max_score,att.released_at,s.code AS subject_code,
               s.display_name AS subject_name,cc.code AS class_code,
               round(100.0*att.final_score/NULLIF(att.max_score,0),2) AS score_percent
        FROM student_assessment_attempt att
        JOIN assessment a ON a.id=att.assessment_id
        JOIN curriculum_subject s ON s.id=a.subject_id AND s.active=true
        JOIN curriculum_class cc ON cc.id=s.class_id AND cc.active=true
        JOIN student st ON st.id=att.student_id AND st.active=true AND st.class_code=cc.code
        WHERE att.student_id=? AND att.status='RELEASED'
          AND att.final_score IS NOT NULL AND att.max_score>0
          AND 100.0*att.final_score/att.max_score >= ?
          AND a.assessment_type='ADMIN_CREATED' AND a.is_generated=false
          AND EXISTS (
            SELECT 1 FROM student_track_enrollment ste
            WHERE ste.student_id=st.id AND ste.subject_id=s.id AND ste.status='ACTIVE'
          )
          AND NOT EXISTS (
            SELECT 1 FROM student_certificate existing
            WHERE existing.student_id=att.student_id
              AND existing.assessment_attempt_id=att.id
              AND existing.achievement_type='ASSESSMENT_PASS'
          )
        ORDER BY att.released_at DESC,att.id DESC
        LIMIT 100
        """, context.studentId(), minimumPassPercent);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("passThresholdPercent", minimumPassPercent);
    result.put("rule", "ADMIN_CREATED_RELEASED_PASS");
    return result;
  }

  /**
   * Evaluates the exact result attempts currently displayed to a student. Attempt IDs are
   * always re-scoped to the authenticated student; client-supplied eligibility is never trusted.
   */
  @GetMapping("/students/me/certificates/assessment-eligibility")
  public Map<String, Object> assessmentCertificateEligibility(
      @RequestParam String attemptIds,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Set<Long> uniqueIds = new LinkedHashSet<>();
    for (String raw : attemptIds.split(",")) {
      String value = raw.trim();
      if (value.isEmpty()) continue;
      try {
        long id = Long.parseLong(value);
        if (id < 1) throw new NumberFormatException();
        uniqueIds.add(id);
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Assessment attempt IDs must be positive integers.");
      }
      if (uniqueIds.size() > 100) {
        throw new IllegalArgumentException("Check certificate eligibility for at most 100 attempts at a time.");
      }
    }
    if (uniqueIds.isEmpty()) return Map.of("items", List.of(), "passThresholdPercent", minimumPassPercent);

    String placeholders = String.join(",", java.util.Collections.nCopies(uniqueIds.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(context.studentId());
    args.addAll(uniqueIds);
    String sql = """
        SELECT att.id AS attempt_id,a.title AS assessment_title,a.assessment_type,a.is_generated,
               att.status,att.final_score,att.max_score,att.released_at,s.id AS subject_id,
               s.code AS subject_code,s.display_name AS subject_name,cc.code AS class_code,
               round(100.0*att.final_score/NULLIF(att.max_score,0),2) AS score_percent,
               EXISTS (
                 SELECT 1 FROM student_track_enrollment ste
                 JOIN student active_st ON active_st.id=ste.student_id AND active_st.active=true
                 JOIN curriculum_subject active_s ON active_s.id=ste.subject_id AND active_s.active=true
                 JOIN curriculum_class active_cc ON active_cc.id=active_s.class_id
                   AND active_cc.active=true AND active_cc.code=active_st.class_code
                 WHERE ste.student_id=att.student_id AND ste.subject_id=s.id AND ste.status='ACTIVE'
                   AND active_st.id=att.student_id AND active_s.id=s.id
               ) AS active_subject_track,
               COALESCE(progress.total_lessons,0) AS published_lessons,
               COALESCE(progress.completed_lessons,0) AS completed_lessons,
               cert.id AS certificate_id,cert.certificate_number,cert.status AS certificate_status
        FROM student_assessment_attempt att
        JOIN assessment a ON a.id=att.assessment_id
        JOIN curriculum_subject s ON s.id=a.subject_id
        JOIN curriculum_class cc ON cc.id=s.class_id
        JOIN student st ON st.id=att.student_id AND st.active=true
        LEFT JOIN LATERAL (
          SELECT COUNT(DISTINCT l.id) AS total_lessons,
                 COUNT(DISTINCT l.id) FILTER (WHERE p.status='COMPLETED') AS completed_lessons
          FROM curriculum_chapter ch
          JOIN lesson l ON l.chapter_id=ch.id AND l.active=true AND l.status='PUBLISHED'
          LEFT JOIN student_lesson_progress p ON p.student_id=att.student_id AND p.lesson_id=l.id
          WHERE ch.subject_id=s.id AND ch.active=true AND ch.content_status='PUBLISHED'
        ) progress ON true
        LEFT JOIN student_certificate cert
          ON cert.student_id=att.student_id AND cert.assessment_attempt_id=att.id
             AND cert.achievement_type='ASSESSMENT_PASS'
        WHERE att.student_id=?
        ORDER BY att.released_at DESC NULLS LAST,att.id DESC
        """;
    sql = sql.replace("WHERE att.student_id=?", "WHERE att.student_id=? AND att.id IN (" + placeholders + ")");
    List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> item = new LinkedHashMap<>(row);
      String reason = assessmentEligibilityReason(row);
      item.put("eligible", reason == null);
      item.put("eligibility_reason", reason);
      item.put("pass_threshold_percent", minimumPassPercent);
      items.add(item);
    }
    return Map.of("items", items, "passThresholdPercent", minimumPassPercent);
  }

  private String assessmentEligibilityReason(Map<String, Object> attempt) {
    String certificateStatus = clean(attempt.get("certificate_status"));
    if ("ISSUED".equals(certificateStatus)) return "ALREADY_ISSUED";
    if ("REVOKED".equals(certificateStatus)) return "CERTIFICATE_REVOKED";
    if (!"ADMIN_CREATED".equals(clean(attempt.get("assessment_type")))
        || Boolean.TRUE.equals(attempt.get("is_generated"))) return "ADMIN_CREATED_TEST_ONLY";
    if (!"RELEASED".equals(clean(attempt.get("status")))) return "RESULT_NOT_RELEASED";
    if (!Boolean.TRUE.equals(attempt.get("active_subject_track"))) return "SUBJECT_TRACK_NOT_ACTIVE";
    if (attempt.get("final_score") == null || attempt.get("max_score") == null) return "INVALID_SCORE";
    BigDecimal score = decimal(attempt.get("final_score"));
    BigDecimal max = decimal(attempt.get("max_score"));
    if (max.signum() <= 0) return "INVALID_SCORE";
    if (score.multiply(BigDecimal.valueOf(100))
        .compareTo(max.multiply(minimumPassPercent)) < 0) return "PASS_THRESHOLD_NOT_MET";
    return null;
  }

  @PostMapping("/students/me/certificates/issue")
  @Transactional
  public Map<String, Object> issueCertificate(@RequestBody IssueRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    if (request == null) throw new IllegalArgumentException("Choose the achievement for your certificate.");
    long studentId = context.studentId();

    // Serialize issuance attempts for the same student. This, plus the unique achievement key,
    // makes double-clicks and concurrent retries idempotent.
    List<Map<String, Object>> studentRows = jdbc.queryForList("""
        SELECT id,display_name,class_code,language
        FROM student WHERE id=? AND active=true FOR UPDATE
        """, studentId);
    if (studentRows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active student profile not found.");
    Map<String, Object> student = studentRows.get(0);

    String type = clean(request.achievementType()).toUpperCase(Locale.ROOT);
    String template = CertificateRenderer.normalizeTemplate(request.templateId());
    String language = normalizeLanguage(request.language(), String.valueOf(student.get("language")));
    if ("SUBJECT_COMPLETION".equals(type)) {
      String subjectCode = clean(request.subjectCode()).toLowerCase(Locale.ROOT);
      if (!subjectCode.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Choose a valid subject track.");
      return issueSubjectCompletion(studentId, student, subjectCode, template, language);
    }
    if ("ASSESSMENT_PASS".equals(type)) {
      if (request.assessmentAttemptId() == null || request.assessmentAttemptId() < 1) {
        throw new IllegalArgumentException("Choose a released assessment result.");
      }
      return issueAssessmentPass(studentId, student, request.assessmentAttemptId(), template, language);
    }
    throw new IllegalArgumentException("Choose SUBJECT_COMPLETION or ASSESSMENT_PASS.");
  }

  private Map<String, Object> issueSubjectCompletion(long studentId, Map<String, Object> student,
      String subjectCode, String template, String language) {
    String classCode = String.valueOf(student.get("class_code"));
    String key = "SUBJECT_COMPLETION:" + classCode + ":" + subjectCode;
    Map<String, Object> existing = existingCertificate(studentId, key);
    if (existing != null) return existingOrRejectRevoked(existing);

    Map<String, Object> achievement = queryOptional("""
        SELECT st.display_name AS recipient_name,st.class_code,s.id AS subject_id,s.code AS subject_code,
               s.display_name AS subject_name,COUNT(DISTINCT l.id) AS total_lessons,
               COUNT(DISTINCT l.id) FILTER (WHERE p.status='COMPLETED') AS completed_lessons
        FROM student st
        JOIN student_track_enrollment ste ON ste.student_id=st.id AND ste.status='ACTIVE'
        JOIN curriculum_subject s ON s.id=ste.subject_id AND s.active=true
        JOIN curriculum_class cc ON cc.id=s.class_id AND cc.active=true
        LEFT JOIN curriculum_chapter ch
          ON ch.subject_id=s.id AND ch.active=true AND ch.content_status='PUBLISHED'
        LEFT JOIN lesson l ON l.chapter_id=ch.id AND l.active=true AND l.status='PUBLISHED'
        LEFT JOIN student_lesson_progress p ON p.student_id=st.id AND p.lesson_id=l.id
        WHERE st.id=? AND st.active=true AND cc.code=st.class_code AND lower(s.code)=lower(?)
        GROUP BY st.display_name,st.class_code,s.id,s.code,s.display_name
        """, studentId, subjectCode);
    if (achievement == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This subject is not an active learning track for your current class.");
    }
    long total = number(achievement.get("total_lessons"));
    long completed = number(achievement.get("completed_lessons"));
    if (total < 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This subject does not have published lessons ready for certificate completion yet.");
    }
    if (completed != total) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Certificate not available yet: complete all " + total + " currently published lessons in this subject first.");
    }

    boolean hindi = "hi".equals(language);
    String subjectName = "maths".equals(subjectCode)
        ? (hindi ? "गणित" : "Mathematics")
        : "science".equals(subjectCode) ? (hindi ? "विज्ञान" : "Science")
        : String.valueOf(achievement.get("subject_name"));
    String title = (hindi ? "कक्षा " : "Class ") + classCode + " · " + subjectName;
    String summary = hindi
        ? "इस विषय में प्रकाशित सभी " + total + " पाठ सफलतापूर्वक पूरे किए।"
        : "Completed all " + total + " currently published lessons in this subject track.";
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("rule", "ALL_CURRENTLY_PUBLISHED_SUBJECT_LESSONS_COMPLETED");
    snapshot.put("classCode", classCode);
    snapshot.put("subjectCode", subjectCode);
    snapshot.put("publishedLessonsAtIssue", total);
    snapshot.put("completedLessonsAtIssue", completed);
    return createIssuedCertificate(studentId, key, "SUBJECT_COMPLETION", classCode,
        ((Number) achievement.get("subject_id")).longValue(), null, template, language,
        String.valueOf(achievement.get("recipient_name")), title, summary, snapshot);
  }

  private Map<String, Object> issueAssessmentPass(long studentId, Map<String, Object> student,
      long attemptId, String template, String language) {
    Map<String, Object> achievement = queryOptional("""
        SELECT st.display_name AS recipient_name,st.language AS student_language,
               cc.code AS class_code,s.id AS subject_id,s.code AS subject_code,s.display_name AS subject_name,
               a.title AS assessment_title,a.assessment_type,a.is_generated,
               att.id AS assessment_attempt_id,att.final_score,att.max_score,att.released_at
        FROM student_assessment_attempt att
        JOIN assessment a ON a.id=att.assessment_id
        JOIN curriculum_subject s ON s.id=a.subject_id
        JOIN curriculum_class cc ON cc.id=s.class_id
        JOIN student st ON st.id=att.student_id AND st.active=true AND st.class_code=cc.code
        WHERE st.id=? AND att.student_id=? AND att.id=? AND att.status='RELEASED'
          AND att.final_score IS NOT NULL AND att.max_score>0
        """, studentId, studentId, attemptId);
    if (achievement == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Only your own released, graded assessment result can earn a certificate.");
    }
    String key = "ASSESSMENT_PASS:" + attemptId;
    Map<String, Object> existing = existingCertificate(studentId, key);
    // Previously issued records remain immutable and downloadable unless revoked, even if
    // curriculum content later changes. New issuance below must pass today's full policy.
    if (existing != null) return existingOrRejectRevoked(existing);

    if (!"ADMIN_CREATED".equals(clean(achievement.get("assessment_type")))
        || Boolean.TRUE.equals(achievement.get("is_generated"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Only admin-created assessments (not generated topic/chapter tests or practice) can earn a test certificate.");
    }

    Map<String, Object> progress = subjectCompletionStats(studentId,
        ((Number) achievement.get("subject_id")).longValue());
    if (!Boolean.TRUE.equals(progress.get("active_subject_track"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Certificate requires an active enrollment in this subject for your current class.");
    }
    BigDecimal finalScore = decimal(achievement.get("final_score"));
    BigDecimal maxScore = decimal(achievement.get("max_score"));
    if (maxScore.signum() <= 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assessment maximum score is invalid.");
    BigDecimal percent = finalScore.multiply(BigDecimal.valueOf(100)).divide(maxScore, 2, RoundingMode.HALF_UP);
    if (finalScore.multiply(BigDecimal.valueOf(100)).compareTo(maxScore.multiply(minimumPassPercent)) < 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Certificate requires at least " + minimumPassPercent.stripTrailingZeros().toPlainString()
              + "%; your released result is " + percent.toPlainString() + "%.");
    }

    boolean hindi = "hi".equals(language);
    String assessmentTitle = String.valueOf(achievement.get("assessment_title"));
    String title = safeText((hindi ? "मूल्यांकन उपलब्धि: " : "Assessment achievement: ") + assessmentTitle, 240);
    String summary = hindi
        ? "प्राप्तांक " + finalScore.stripTrailingZeros().toPlainString() + " / "
            + maxScore.stripTrailingZeros().toPlainString() + " (" + percent.toPlainString() + "%)।"
        : "Scored " + finalScore.stripTrailingZeros().toPlainString() + " / "
            + maxScore.stripTrailingZeros().toPlainString() + " (" + percent.toPlainString() + "%).";
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("rule", "RELEASED_ADMIN_CREATED_ASSESSMENT_AT_OR_ABOVE_PASS_THRESHOLD");
    snapshot.put("assessmentAttemptId", attemptId);
    snapshot.put("assessmentTitle", assessmentTitle);
    snapshot.put("finalScore", finalScore);
    snapshot.put("maxScore", maxScore);
    snapshot.put("scorePercent", percent);
    snapshot.put("passThresholdPercent", minimumPassPercent);
    String classCode = String.valueOf(achievement.get("class_code"));
    return createIssuedCertificate(studentId, key, "ASSESSMENT_PASS", classCode,
        ((Number) achievement.get("subject_id")).longValue(), attemptId, template, language,
        String.valueOf(achievement.get("recipient_name")), title, summary, snapshot);
  }

  private Map<String, Object> createIssuedCertificate(long studentId, String achievementKey,
      String achievementType, String classCode, long subjectId, Long attemptId, String template,
      String language, String recipient, String title, String summary, Map<String, Object> snapshot) {
    OffsetDateTime issuedAt = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));
    String certificateNumber = "QE-" + issuedAt.getYear() + "-"
        + HexFormat.of().withUpperCase().formatHex(randomBytes(8));
    CertificateRenderer.RenderedCertificate rendered = renderer.render(template, language, recipient,
        title, summary, certificateNumber, issuedAt, false);
    CertificateArtifactStorage.StoredArtifact pdf = null;
    CertificateArtifactStorage.StoredArtifact png = null;
    try {
      pdf = storage.store(rendered.pdf(), "pdf");
      png = storage.store(rendered.png(), "png");
      jdbc.update("""
          INSERT INTO student_certificate(
            certificate_number,student_id,achievement_key,achievement_type,class_code,subject_id,
            assessment_attempt_id,template_id,language,recipient_name,achievement_title,
            achievement_summary,achievement_snapshot,status,issued_at,pdf_storage_key,png_storage_key,
            pdf_sha256,png_sha256,pdf_size_bytes,png_size_bytes)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'ISSUED',?,?,?,?,?,?,?)
          """,
          certificateNumber, studentId, achievementKey, achievementType, classCode, subjectId,
          attemptId, template, language, safeText(recipient, 120), safeText(title, 240),
          safeText(summary, 600), json(snapshot), issuedAt, pdf.storageKey(), png.storageKey(),
          pdf.sha256(), png.sha256(), pdf.sizeBytes(), png.sizeBytes());
      long certificateId = jdbc.queryForObject(
          "SELECT id FROM student_certificate WHERE certificate_number=?", Long.class, certificateNumber);
      jdbc.update("""
          INSERT INTO certificate_audit_log(certificate_id,actor_student_id,actor_role,action,detail)
          VALUES (?,?,'STUDENT','ISSUED',?::jsonb)
          """, certificateId, studentId, json(snapshot));
      return publicCertificateById(certificateId, studentId);
    } catch (RuntimeException ex) {
      if (png != null) storage.deleteQuietly(png.storageKey());
      if (pdf != null) storage.deleteQuietly(pdf.storageKey());
      throw ex;
    }
  }

  @GetMapping("/students/me/certificates/{certificateId}/download")
  public ResponseEntity<byte[]> downloadMyCertificate(@PathVariable long certificateId,
      @RequestParam(defaultValue = "pdf") String format,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    return studentCertificateArtifact(certificateId, format, context, false);
  }

  @GetMapping("/students/me/certificates/{certificateId}/view")
  public ResponseEntity<byte[]> viewMyCertificate(@PathVariable long certificateId,
      @RequestParam(defaultValue = "pdf") String format,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    return studentCertificateArtifact(certificateId, format, context, true);
  }

  private ResponseEntity<byte[]> studentCertificateArtifact(long certificateId, String format,
      AuthContext context, boolean inline) {
    context = authorization.requireStudent(context);
    return certificateArtifact(certificateId, context.studentId(), format, inline);
  }

  private ResponseEntity<byte[]> certificateArtifact(long certificateId, long studentId,
      String format, boolean inline) {
    String normalizedFormat = format == null ? "pdf" : format.trim().toLowerCase(Locale.ROOT);
    if (!List.of("pdf", "png").contains(normalizedFormat)) {
      throw new IllegalArgumentException("Choose PDF or PNG format.");
    }
    Map<String, Object> certificate = queryOptional("""
        SELECT id,certificate_number,status,pdf_storage_key,png_storage_key,pdf_sha256,png_sha256
        FROM student_certificate WHERE id=? AND student_id=?
        """, certificateId, studentId);
    if (certificate == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    if ("REVOKED".equals(certificate.get("status"))) {
      throw new ResponseStatusException(HttpStatus.GONE, "This certificate has been revoked and is no longer downloadable as valid proof.");
    }
    boolean pdfFormat = "pdf".equals(normalizedFormat);
    String key = String.valueOf(certificate.get(pdfFormat ? "pdf_storage_key" : "png_storage_key"));
    String expectedHash = String.valueOf(certificate.get(pdfFormat ? "pdf_sha256" : "png_sha256")).trim();
    byte[] bytes = storage.read(key);
    if (!CertificateArtifactStorage.sha256(bytes).equals(expectedHash)) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored certificate failed its SHA-256 integrity check.");
    }
    MediaType contentType = pdfFormat ? MediaType.APPLICATION_PDF : MediaType.IMAGE_PNG;
    String filename = "QuantaEdge-" + certificate.get("certificate_number") + "." + normalizedFormat;
    ContentDisposition disposition = inline
        ? ContentDisposition.inline().filename(filename).build()
        : ContentDisposition.attachment().filename(filename).build();
    return ResponseEntity.ok().contentType(contentType).cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Certificate-Number", String.valueOf(certificate.get("certificate_number")))
        .body(bytes);
  }

  @GetMapping("/certificates/verify/{certificateNumber}")
  public Map<String, Object> verifyCertificate(@PathVariable String certificateNumber) {
    String number = certificateNumber == null ? "" : certificateNumber.trim().toUpperCase(Locale.ROOT);
    if (!number.matches(CERTIFICATE_NUMBER_PATTERN)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    }
    Map<String, Object> row = queryOptional("""
        SELECT c.certificate_number,c.recipient_name,c.achievement_type,c.class_code,s.code AS subject_code,
               c.achievement_title,c.issued_at,c.status,c.revoked_at
        FROM student_certificate c LEFT JOIN curriculum_subject s ON s.id=c.subject_id
        WHERE c.certificate_number=?
        """, number);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    boolean valid = "ISSUED".equals(row.get("status"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("certificateNumber", row.get("certificate_number"));
    result.put("recipientName", row.get("recipient_name"));
    result.put("achievementType", row.get("achievement_type"));
    result.put("classCode", row.get("class_code"));
    result.put("subjectCode", row.get("subject_code"));
    result.put("achievementTitle", row.get("achievement_title"));
    result.put("issuedAt", row.get("issued_at"));
    result.put("status", valid ? "VALID" : "REVOKED");
    result.put("verified", valid);
    return result;
  }

  @GetMapping("/certificates/samples/{templateId}")
  public ResponseEntity<byte[]> certificateSample(@PathVariable String templateId,
      @RequestParam(defaultValue = "png") String format,
      @RequestParam(defaultValue = "hi") String language) {
    String template = CertificateRenderer.normalizeTemplate(templateId);
    String normalizedFormat = format.trim().toLowerCase(Locale.ROOT);
    if (!List.of("pdf", "png").contains(normalizedFormat)) throw new IllegalArgumentException("Choose PDF or PNG format.");
    String normalizedLanguage = normalizeLanguage(language, "hi");
    OffsetDateTime sampleDate = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));
    String recipient = "hi".equals(normalizedLanguage) ? "नमूना विद्यार्थी" : "Sample Student";
    String title = "hi".equals(normalizedLanguage) ? "कक्षा 6 · गणित" : "Class 6 · Mathematics";
    String summary = "hi".equals(normalizedLanguage)
        ? "यह केवल नमूना है। कोई उपलब्धि सत्यापित नहीं की गई है।"
        : "Design preview only. No student achievement has been verified.";
    boolean pdf = "pdf".equals(normalizedFormat);
    byte[] bytes;
    if (pdf) {
      bytes = renderer.render(template, normalizedLanguage, recipient, title, summary,
          "QE-2026-0000000000000000", sampleDate, true).pdf();
    } else {
      bytes = renderer.renderSamplePng(template, normalizedLanguage, recipient, title, summary,
          "QE-2026-0000000000000000", sampleDate);
    }
    return ResponseEntity.ok().contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.maxAge(Duration.ofHours(12)).cachePublic())
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline().filename("QuantaEdge-SAMPLE-" + template + "." + normalizedFormat).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .header("X-Robots-Tag", "noindex, nofollow")
        .body(bytes);
  }

  @GetMapping("/admin/certificates/for-attempt/{attemptId}")
  public Map<String, Object> adminCertificateForAttempt(@PathVariable long attemptId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAdmin(context);
    Map<String, Object> row = queryOptional("""
        SELECT id FROM student_certificate
        WHERE assessment_attempt_id=? AND achievement_type='ASSESSMENT_PASS'
        ORDER BY id DESC LIMIT 1
        """, attemptId);
    if (row == null) return Map.of("issued", false);
    return Map.of("issued", true,
        "certificate", adminCertificateById(((Number) row.get("id")).longValue()));
  }

  @GetMapping("/admin/certificates")
  public Map<String, Object> adminCertificates(@RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAdmin(context);
    String filter = status.trim().toUpperCase(Locale.ROOT);
    if (!List.of("ALL", "ISSUED", "REVOKED").contains(filter)) throw new IllegalArgumentException("Choose ALL, ISSUED, or REVOKED.");
    if (limit < 1 || limit > 500 || offset < 0 || offset > 2_000_000) {
      throw new IllegalArgumentException("Certificate page size or offset is outside the allowed range.");
    }
    String where = "ALL".equals(filter) ? "" : " WHERE c.status=?";
    Object[] args = "ALL".equals(filter) ? new Object[]{} : new Object[]{filter};
    List<Map<String, Object>> items = jdbc.queryForList("""
        SELECT c.id,c.certificate_number,c.student_id,st.display_name AS recipient_name,
               c.achievement_type,c.class_code,s.code AS subject_code,c.achievement_title,
               c.template_id,c.language,c.status,c.issued_at,c.revoked_at,c.revocation_reason
        FROM student_certificate c
        JOIN student st ON st.id=c.student_id
        LEFT JOIN curriculum_subject s ON s.id=c.subject_id
        """ + where + " ORDER BY c.issued_at DESC,c.id DESC LIMIT ? OFFSET ?",
        append(args, limit, offset));
    Long total = jdbc.queryForObject("SELECT count(*) FROM student_certificate c" + where, Long.class, args);
    return Map.of("items", items, "total", total == null ? 0L : total, "limit", limit, "offset", offset, "status", filter);
  }

  @GetMapping("/admin/certificates/{certificateId}/download")
  public ResponseEntity<byte[]> adminDownloadCertificate(@PathVariable long certificateId,
      @RequestParam(defaultValue = "pdf") String format,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAdmin(context);
    String normalizedFormat = format.trim().toLowerCase(Locale.ROOT);
    if (!List.of("pdf", "png").contains(normalizedFormat)) {
      throw new IllegalArgumentException("Choose PDF or PNG format.");
    }
    Map<String, Object> certificate = queryOptional("""
        SELECT certificate_number,status,pdf_storage_key,png_storage_key,pdf_sha256,png_sha256
        FROM student_certificate WHERE id=?
        """, certificateId);
    if (certificate == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    boolean isPdf = "pdf".equals(normalizedFormat);
    String key = String.valueOf(certificate.get(isPdf ? "pdf_storage_key" : "png_storage_key"));
    String expectedHash = String.valueOf(certificate.get(isPdf ? "pdf_sha256" : "png_sha256")).trim();
    byte[] bytes = storage.read(key);
    if (!CertificateArtifactStorage.sha256(bytes).equals(expectedHash)) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored certificate failed its SHA-256 integrity check.");
    }
    return ResponseEntity.ok().contentType(isPdf ? MediaType.APPLICATION_PDF : MediaType.IMAGE_PNG)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("QuantaEdge-" + certificate.get("certificate_number") + "." + normalizedFormat).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(bytes);
  }

  @GetMapping("/admin/certificates/{certificateId}/audit")
  public Map<String, Object> certificateAudit(@PathVariable long certificateId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAdmin(context);
    Long exists = jdbc.queryForObject("SELECT count(*) FROM student_certificate WHERE id=?", Long.class, certificateId);
    if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    List<Map<String, Object>> events = jdbc.queryForList("""
        SELECT id,actor_role,action,detail,occurred_at,
               CASE WHEN actor_student_id IS NOT NULL THEN 'STUDENT'
                    WHEN actor_staff_id IS NOT NULL THEN 'STAFF' ELSE 'SYSTEM' END AS actor_category
        FROM certificate_audit_log WHERE certificate_id=?
        ORDER BY occurred_at DESC,id DESC LIMIT 100
        """, certificateId);
    return Map.of("certificateId", certificateId, "items", events);
  }

  @PostMapping("/admin/certificates/{certificateId}/revoke")
  @Transactional
  public Map<String, Object> revokeCertificate(@PathVariable long certificateId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAdmin(context);
    String reason = safeText(clean(body == null ? null : body.get("reason")), 500);
    if (reason.length() < 5) throw new IllegalArgumentException("Provide a revocation reason of at least 5 characters.");
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT id,status FROM student_certificate WHERE id=? FOR UPDATE", certificateId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    if ("REVOKED".equals(rows.get(0).get("status"))) return adminCertificateById(certificateId);

    int changed = jdbc.update("""
        UPDATE student_certificate
        SET status='REVOKED',revoked_at=now(),revoked_by_staff_id=?,revocation_reason=?
        WHERE id=? AND status='ISSUED'
        """, context.staffId(), reason, certificateId);
    if (changed != 1) throw new IllegalStateException("Certificate status changed concurrently. Reload and retry.");
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("reason", reason);
    jdbc.update("""
        INSERT INTO certificate_audit_log(certificate_id,actor_staff_id,actor_role,action,detail)
        VALUES (?,?,'ADMIN','REVOKED',?::jsonb)
        """, certificateId, context.staffId(), json(detail));
    return adminCertificateById(certificateId);
  }

  private Map<String, Object> subjectCompletionStats(long studentId, long subjectId) {
    return jdbc.queryForMap("""
        SELECT EXISTS (
                 SELECT 1
                 FROM student st
                 JOIN student_track_enrollment ste
                   ON ste.student_id=st.id AND ste.subject_id=s.id AND ste.status='ACTIVE'
                 JOIN curriculum_class cc
                   ON cc.id=s.class_id AND cc.code=st.class_code AND cc.active=true
                 WHERE st.id=? AND st.active=true AND s.active=true
               ) AS active_subject_track,
               COUNT(DISTINCT l.id) AS total_lessons,
               COUNT(DISTINCT l.id) FILTER (WHERE p.status='COMPLETED') AS completed_lessons
        FROM curriculum_subject s
        LEFT JOIN curriculum_chapter ch
          ON ch.subject_id=s.id AND ch.active=true AND ch.content_status='PUBLISHED'
        LEFT JOIN lesson l ON l.chapter_id=ch.id AND l.active=true AND l.status='PUBLISHED'
        LEFT JOIN student_lesson_progress p ON p.student_id=? AND p.lesson_id=l.id
        WHERE s.id=?
        GROUP BY s.id
        """, studentId, studentId, subjectId);
  }

  private Map<String, Object> existingCertificate(long studentId, String key) {
    Map<String, Object> row = queryOptional("SELECT id,status FROM student_certificate WHERE student_id=? AND achievement_key=?",
        studentId, key);
    if (row == null) return null;
    return publicCertificateById(((Number) row.get("id")).longValue(), studentId);
  }

  private Map<String, Object> existingOrRejectRevoked(Map<String, Object> certificate) {
    if ("REVOKED".equals(certificate.get("status"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This achievement already has a revoked certificate; contact an administrator instead of reissuing it.");
    }
    return certificate;
  }

  private Map<String, Object> publicCertificateById(long id, long studentId) {
    Map<String, Object> row = queryOptional("SELECT " + CERTIFICATE_COLUMNS + " " + CERTIFICATE_JOIN
        + " WHERE c.id=? AND c.student_id=?", id, studentId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    return row;
  }

  private Map<String, Object> adminCertificateById(long id) {
    Map<String, Object> row = queryOptional("""
        SELECT c.id,c.certificate_number,c.student_id,st.display_name AS recipient_name,
               c.achievement_type,c.class_code,s.code AS subject_code,c.achievement_title,
               c.template_id,c.language,c.status,c.issued_at,c.revoked_at,c.revocation_reason
        FROM student_certificate c JOIN student st ON st.id=c.student_id
        LEFT JOIN curriculum_subject s ON s.id=c.subject_id WHERE c.id=?
        """, id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found.");
    return row;
  }

  private Map<String, Object> queryOptional(String sql, Object... args) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private String normalizeLanguage(String requested, String fallback) {
    String value = requested == null || requested.isBlank() ? fallback : requested;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("hi") || normalized.equals("hindi")) return "hi";
    if (normalized.equals("en") || normalized.equals("english")) return "en";
    throw new IllegalArgumentException("Choose Hindi or English certificate language.");
  }

  private long number(Object value) {
    if (value instanceof Number number) return number.longValue();
    return Long.parseLong(String.valueOf(value));
  }

  private BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
    return new BigDecimal(String.valueOf(value));
  }

  private String json(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Certificate audit snapshot could not be serialized.", ex);
    }
  }

  private byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    secureRandom.nextBytes(bytes);
    return bytes;
  }

  private Object[] append(Object[] original, Object... extra) {
    Object[] result = new Object[original.length + extra.length];
    System.arraycopy(original, 0, result, 0, original.length);
    System.arraycopy(extra, 0, result, original.length, extra.length);
    return result;
  }

  private String clean(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String safeText(String value, int maximum) {
    String safe = value == null ? "" : value.trim();
    safe = safe.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    return safe.length() <= maximum ? safe : safe.substring(0, maximum);
  }
}
