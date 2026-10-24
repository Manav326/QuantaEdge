package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * P1 notification audience integration gate using the real PostgreSQL schema and the existing,
 * run-owned three-institution HRMS QA fixture. This checks actual Flyway constraints and real
 * tenant/course/guardian relationships, rather than only matching SQL strings in unit tests.
 */
@EnabledIfSystemProperty(named = "qe.qa.lifecycle.integration", matches = "true")
class RichNotificationAudienceIsolationIntegrationTest {
  private static final String DATABASE_URL =
      System.getProperty("qe.qa.lifecycle.jdbc-url", "jdbc:postgresql://localhost:5432/quantaedge_qa");
  private static final String DATABASE_USER =
      System.getProperty("qe.qa.lifecycle.jdbc-user", System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "quantaedge"));
  private static final String DATABASE_PASSWORD =
      System.getProperty("qe.qa.lifecycle.jdbc-password", System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "quantaedge-test"));

  @Test
  void enforcesRealThreeInstitutionCourseAndGuardianBoundariesForNotifications() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(DATABASE_URL);
    dataSource.setUsername(DATABASE_USER);
    dataSource.setPassword(DATABASE_PASSWORD);

    // Use the actual migration chain, including notification migrations, in the isolated CI QA DB.
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
        .cleanDisabled(true).load().migrate();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    ObjectMapper mapper = new ObjectMapper();
    MockEnvironment environment = new MockEnvironment().withProperty("app.environment", "qa");
    QaTestPackService qa = new QaTestPackService(
        jdbc, environment, new DataSourceTransactionManager(dataSource), mapper,
        true, true, "ci-qa", "quantaedge_qa", "QAfixture!2026");
    assertTrue(Boolean.TRUE.equals(qa.environmentStatus().get("writeAllowed")),
        () -> "Notification QA fixture is not permitted to write: " + qa.environmentStatus());

    String key = "notification-audience-" + UUID.randomUUID();
    UUID runId = null;
    List<Long> notificationCampaignIds = new ArrayList<>();
    try {
      Map<String, Object> installed = qa.install("TENANT_LEARNER_BOUNDARY_V1", key, null);
      assertEquals("INSTALLED", installed.get("state"), () -> "QA fixture install failed: " + installed);
      runId = UUID.fromString(String.valueOf(installed.get("run_id")));

      List<Map<String, Object>> tenantRows = jdbc.queryForList("""
          select id,tenant_code from institution_tenant
          where qa_test_run_id=? and status='ACTIVE'
          order by tenant_code
          """, runId);
      assertEquals(3, tenantRows.size(), "The P1 gate must exercise three separate institutions.");

      Map<String, Long> tenantBySuffix = new java.util.LinkedHashMap<>();
      for (Map<String, Object> row : tenantRows) {
        String code = String.valueOf(row.get("tenant_code"));
        tenantBySuffix.put(code.substring(code.length() - 1),
            ((Number) row.get("id")).longValue());
      }
      long institutionA = required(tenantBySuffix, "A");
      long institutionB = required(tenantBySuffix, "B");
      long institutionC = required(tenantBySuffix, "C");

      Map<Long, Long> studentByInstitution = new java.util.LinkedHashMap<>();
      for (Map<String, Object> row : jdbc.queryForList("""
          select institution_id,student_id from institution_membership
          where qa_test_run_id=? and status='ACTIVE'
          order by institution_id
          """, runId)) {
        studentByInstitution.put(
            ((Number) row.get("institution_id")).longValue(),
            ((Number) row.get("student_id")).longValue());
      }
      assertEquals(3, studentByInstitution.size(),
          "The fixture must supply one active test learner per selected institution.");
      long studentA = required(studentByInstitution, institutionA);
      long studentB = required(studentByInstitution, institutionB);
      long studentC = required(studentByInstitution, institutionC);

      RichNotificationService service = new RichNotificationService(
          jdbc, mapper, new NotificationContentValidator(mapper), mock(AuthorizationService.class));

      // Individual targeting is allowed only in the selected institution.
      List<RichNotificationService.Recipient> targetA = service.resolveAudience(
          "USER", institutionA, null, directStudent(studentA));
      assertEquals(List.of(new RichNotificationService.Recipient("STUDENT", studentA, institutionA)), targetA);
      assertThrows(SecurityException.class,
          () -> service.resolveAudience("USER", institutionA, null, directStudent(studentB)));
      assertThrows(SecurityException.class,
          () -> service.resolveAudience("USER", institutionB, null, directStudent(studentC)));

      // HRMS campaign must fan out only to the explicitly selected active institution set.
      List<RichNotificationService.Recipient> hrmsAudience = service.resolveAudience(
          "HRMS_PLATFORM", null, null, Map.of(
              "institutionIds", List.of(institutionA, institutionB, institutionC),
              "recipientTypes", List.of("STUDENT")));
      assertEquals(3, hrmsAudience.size());
      assertTrue(hrmsAudience.stream().allMatch(r ->
          SetOfLongs.contains(List.of(institutionA, institutionB, institutionC), r.institutionId())));

      // The fixture gives A/B Class 7 and C Class 6, so C cannot be targeted with Class 7 notices.
      Long class7Program = jdbc.queryForObject("""
          select r.program_id
          from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id
          join curriculum_program p on p.id=r.program_id
          where a.qa_test_run_id=? and r.qa_test_run_id=?
            and a.institution_id=? and a.status='ASSIGNED'
            and p.qa_test_run_id=? and left(p.code,6)='QA_C7_'
          order by a.id limit 1
          """, Long.class, runId, runId, institutionA, runId);
      assertTrue(class7Program != null && class7Program > 0L,
          "Institution A must have the explicit HRMS Class 7 assignment.");
      List<RichNotificationService.Recipient> courseAudience = service.resolveAudience(
          "COURSE", institutionA, class7Program,
          Map.of("recipientTypes", List.of("STUDENT")));
      assertEquals(List.of(new RichNotificationService.Recipient("STUDENT", studentA, institutionA)), courseAudience);
      assertThrows(SecurityException.class,
          () -> service.resolveAudience("COURSE", institutionC, class7Program,
              Map.of("recipientTypes", List.of("STUDENT"))));

      // Parent notification eligibility is backed by a consented guardian/child edge in the same tenant.
      Map<String, Object> parentEdge = jdbc.queryForMap("""
          select gs.guardian_user_id,gs.student_id,m.institution_id
          from guardian_student gs
          join institution_membership m on m.student_id=gs.student_id and m.status='ACTIVE'
          join institution_user_membership um on um.user_id=gs.guardian_user_id
            and um.institution_id=m.institution_id and um.status='ACTIVE'
          where gs.qa_test_run_id=? and gs.active=true
            and gs.consent_status='CONSENTED' and gs.access_level='FULL'
          order by gs.id limit 1
          """, runId);
      long parentA = ((Number) parentEdge.get("guardian_user_id")).longValue();
      long parentInstitution = ((Number) parentEdge.get("institution_id")).longValue();
      long childA = ((Number) parentEdge.get("student_id")).longValue();
      List<RichNotificationService.Recipient> parentAudience = service.resolveAudience(
          "USER", parentInstitution, null,
          Map.of("recipients", List.of(Map.of("kind", "USER_ACCOUNT", "id", parentA))));
      assertEquals(List.of(new RichNotificationService.Recipient("USER_ACCOUNT", parentA, parentInstitution)), parentAudience);
      long otherInstitution = parentInstitution == institutionA ? institutionB : institutionA;
      assertThrows(SecurityException.class, () -> service.resolveAudience(
          "USER", otherInstitution, null,
          Map.of("recipients", List.of(Map.of("kind", "USER_ACCOUNT", "id", parentA)))));

      assertTrue(childA > 0L, "The consented parent edge must refer to a real QA child.");

      // Exercise the real in-app dispatch contract, idempotent replay, tenant-scoped inbox,
      // read-state transition and channel attempt record against PostgreSQL.
      Long senderStaffId = jdbc.queryForObject("""
          select id from staff_account where qa_test_run_id=? and role='CONTENT_REVIEWER'
          order by id limit 1
          """, Long.class, runId);
      RichNotificationService.Actor sender = new RichNotificationService.Actor(
          "EDUCATION_ADMIN", senderStaffId, null, institutionA);
      Map<String, Object> document = Map.of("version", 1, "blocks", List.of(
          Map.of("type", "hero", "title", "QA notice", "subtitle", "Institution A only")));
      String idempotencyKey = "notification-send-" + UUID.randomUUID();
      Map<String, Object> sendPreview = service.preview(sender, "USER", institutionA, null,
          directStudent(studentA), "Tenant-scoped QA notice", "A durable inbox delivery test", document);
      String sendPreviewToken = String.valueOf(sendPreview.get("previewToken"));
      Map<String, Object> sent = service.send(sender, "USER", institutionA, null,
          directStudent(studentA), "Tenant-scoped QA notice", "A durable inbox delivery test",
          document, null, null, idempotencyKey, sendPreviewToken, null, null);
      long campaignId = ((Number) sent.get("id")).longValue();
      notificationCampaignIds.add(campaignId);
      assertEquals("SENT", sent.get("status"));
      assertEquals(1, ((Number) sent.get("resolved_recipient_count")).intValue());

      Map<String, Object> replay = service.send(sender, "USER", institutionA, null,
          directStudent(studentA), "Tenant-scoped QA notice", "A durable inbox delivery test",
          document, null, null, idempotencyKey, sendPreviewToken, null, null);
      assertEquals(campaignId, ((Number) replay.get("id")).longValue(),
          "Replaying the same sender-scoped idempotency key must not create another campaign.");
      assertEquals(Boolean.TRUE, replay.get("idempotentReplay"));

      AuthContext studentContext = new AuthContext(null, studentA, null,
          "STUDENT", "QA Child A", "STUDENT");
      List<Map<String, Object>> deliveredInbox = service.inbox(
          studentContext, institutionA, 50, null, false);
      Map<String, Object> delivered = deliveredInbox.stream()
          .filter(item -> "Tenant-scoped QA notice".equals(item.get("title")))
          .findFirst().orElseThrow(() -> new AssertionError("Sent notification is missing from the targeted learner inbox."));
      long recipientId = ((Number) delivered.get("recipient_id")).longValue();
      assertEquals("CREATED", delivered.get("state"));
      assertEquals(1L, count(jdbc, """
          select count(*) from notification_delivery_attempt d
          join notification_recipient r on r.id=d.recipient_id
          where r.campaign_id=? and d.channel='IN_APP' and d.status='SENT'
          """, campaignId));
      assertTrue(service.inbox(studentContext, institutionB, 50, null, false).stream()
          .noneMatch(item -> "Tenant-scoped QA notice".equals(item.get("title"))),
          "Institution B must not receive Institution A's notification, even with a shared content title.");

      assertTrue(service.markRead(studentContext, institutionA, recipientId, false));
      assertTrue(service.inbox(studentContext, institutionA, 50, null, true).stream()
          .noneMatch(item -> "Tenant-scoped QA notice".equals(item.get("title"))),
          "A read notification must disappear from the unread-only inbox view.");
      assertEquals(1L, count(jdbc, """
          select count(*) from notification_recipient
          where id=? and campaign_id=? and state='READ' and read_at is not null
          """, recipientId, campaignId));

      Map<String, Object> scheduledPreview = service.preview(sender, "USER", institutionA, null,
          directStudent(studentA), "Scheduled QA notice", "A cancellation lifecycle test", document);
      String scheduledPreviewToken = String.valueOf(scheduledPreview.get("previewToken"));
      Map<String, Object> scheduled = service.send(sender, "USER", institutionA, null,
          directStudent(studentA), "Scheduled QA notice", "A cancellation lifecycle test",
          document, null, null, "notification-schedule-" + UUID.randomUUID(),
          scheduledPreviewToken, Instant.now().plusSeconds(3600), null);
      long scheduledCampaignId = ((Number) scheduled.get("id")).longValue();
      notificationCampaignIds.add(scheduledCampaignId);
      assertEquals("SCHEDULED", scheduled.get("status"));
      Map<String, Object> cancelled = service.cancelCampaign(sender, scheduledCampaignId);
      assertEquals("CANCELLED", cancelled.get("status"));
      assertEquals(0L, count(jdbc,
          "select count(*) from notification_recipient where campaign_id=?", scheduledCampaignId),
          "A cancelled scheduled campaign must not be delivered to the inbox.");

      assertThrows(IllegalArgumentException.class, () -> service.send(
          sender, "USER", institutionA, null, directStudent(studentA),
          "Invalid scheduled notice", "This should be rejected",
          document, null, null, "notification-schedule-past-" + UUID.randomUUID(),
          null, Instant.now().minusSeconds(60), null));
    } finally {
      // Notification rows reference QA users and intentionally restrict their deletion. Remove
      // only the campaign IDs created by this test before uninstalling its exact QA fixture.
      for (Long campaignId : notificationCampaignIds) {
        try {
          jdbc.update("delete from notification_audit where campaign_id=?", campaignId);
          jdbc.update("delete from notification_delivery_attempt where recipient_id in (select id from notification_recipient where campaign_id=?)", campaignId);
          jdbc.update("delete from notification_recipient where campaign_id=?", campaignId);
          jdbc.update("delete from notification_campaign where id=?", campaignId);
        } catch (Exception ignored) {
          // Preserve the original failure; CI discards this isolated QA database after the job.
        }
      }
      // Exact-run, best-effort cleanup only; never delete other test runs or production institutions.
      if (runId != null) {
        try {
          Map<String, Object> detail = qa.getRun(runId);
          String state = String.valueOf(detail.get("state"));
          if (List.of("INSTALLED", "UNINSTALL_BLOCKED", "UNINSTALLING", "INSTALL_FAILED").contains(state)) {
            qa.uninstall(runId, runId.toString(), null);
          }
        } catch (Exception ignored) {
          // The surrounding CI job uses a disposable database; retain the primary assertion on failure.
        }
      }
    }
  }

  private static long count(JdbcTemplate jdbc, String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private static long required(Map<String, Long> values, String key) {
    Long value = values.get(key);
    if (value == null || value <= 0L) throw new AssertionError("Missing QA fixture value for " + key);
    return value;
  }

  private static long required(Map<Long, Long> values, long key) {
    Long value = values.get(key);
    if (value == null || value <= 0L) throw new AssertionError("Missing QA student membership for institution " + key);
    return value;
  }

  private static Map<String, Object> directStudent(long studentId) {
    return Map.of("recipients", List.of(Map.of("kind", "STUDENT", "id", studentId)));
  }

  private static final class SetOfLongs {
    private SetOfLongs() {}
    static boolean contains(List<Long> values, Long value) {
      return value != null && values.contains(value);
    }
  }
}
