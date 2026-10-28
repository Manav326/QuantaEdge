package com.quantaedge.api;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * FCM transport for already-authorized durable notification recipients.
 * The database inbox remains authoritative: provider failures never roll back in-app delivery.
 * Credentials are read only from server-side runtime configuration and are never returned by an API.
 */
@Service
public class AndroidPushService {
  public static final String CHANNEL_ID = "quantaedge_notifications";
  public static final String OPEN_ACTION = "com.quantaedge.android.OPEN_NOTIFICATION";
  private static final Logger LOGGER = LoggerFactory.getLogger(AndroidPushService.class);
  private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private static final int MAX_ATTEMPTS = 4;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final FirebaseMessaging messaging;

  public AndroidPushService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${app.notifications.fcm.project-id:}") String projectId,
      @Value("${app.notifications.fcm.service-account-json-base64:}") String credentialsBase64) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.messaging = initializeMessaging(projectId, credentialsBase64);
  }

  public boolean isConfigured() {
    return messaging != null;
  }

  private FirebaseMessaging initializeMessaging(String rawProjectId, String rawCredentialsBase64) {
    String projectId = rawProjectId == null ? "" : rawProjectId.trim();
    String encoded = rawCredentialsBase64 == null ? "" : rawCredentialsBase64.trim();
    if (projectId.isBlank() || encoded.isBlank()) return null;
    try {
      byte[] credentialJson = Base64.getDecoder().decode(encoded);
      GoogleCredentials credentials = GoogleCredentials
          .fromStream(new ByteArrayInputStream(credentialJson))
          .createScoped(List.of(FCM_SCOPE));
      FirebaseOptions options = FirebaseOptions.builder()
          .setProjectId(projectId)
          .setCredentials(credentials)
          .build();
      FirebaseApp app = FirebaseApp.getApps().stream()
          .filter(candidate -> "quantaedge-android-push".equals(candidate.getName()))
          .findFirst()
          .orElseGet(() -> FirebaseApp.initializeApp(options, "quantaedge-android-push"));
      return FirebaseMessaging.getInstance(app);
    } catch (Exception failure) {
      LOGGER.warn("Android push is disabled because server-side FCM configuration could not initialize ({})",
          failure.getClass().getSimpleName());
      return null;
    }
  }

  /** Called on a bounded schedule. Each row is a durable delivery attempt for one registered device. */
  public int dispatchPendingDeliveries() {
    // Keep queued push attempts pending while configuration is absent. This lets a newly
    // configured deployment deliver existing durable notifications without requiring a resend.
    if (!isConfigured()) return 0;

    // Close attempts whose campaign expired, or whose installation/account binding was revoked.
    jdbc.update("""
      update notification_delivery_attempt a
      set status='FAILED',failure_code='DEVICE_OR_CAMPAIGN_INACTIVE',attempted_at=now()
      from notification_recipient r join notification_campaign c on c.id=r.campaign_id
      where a.recipient_id=r.id and a.channel='ANDROID_PUSH'
        and a.status in ('PENDING','RETRYING')
        and (
          c.status<>'SENT' or (c.expires_at is not null and c.expires_at<=now())
          or not exists (
            select 1 from notification_device_registration d
            where d.id=a.device_registration_id and d.status='ACTIVE'
              and d.identity_kind=r.recipient_kind
              and ((r.recipient_kind='STUDENT' and d.student_id=r.student_id)
                or (r.recipient_kind='USER_ACCOUNT' and d.user_account_id=r.user_account_id))
          )
        )
      """);

    List<Map<String,Object>> attempts = jdbc.queryForList("""
      select a.id as attempt_id,a.recipient_id,a.attempt_number,a.device_registration_id,
        d.device_token,r.institution_id as recipient_institution_id,
        r.recipient_kind,r.student_id,r.user_account_id,
        c.public_id as campaign_public_id,c.scope,c.course_id,c.title,
        c.audience_snapshot::text as audience_snapshot_json,
        coalesce(nullif(c.summary,''),nullif(c.content_document #>> '{blocks,0,subtitle}',''),
          nullif(c.content_document #>> '{blocks,0,body}',''),'Open QuantaEdge to view details') as push_body
      from notification_delivery_attempt a
      join notification_recipient r on r.id=a.recipient_id
      join notification_campaign c on c.id=r.campaign_id
      join notification_device_registration d on d.id=a.device_registration_id
      where a.channel='ANDROID_PUSH' and a.status in ('PENDING','RETRYING')
        and a.next_attempt_at<=now()
        and d.status='ACTIVE' and d.identity_kind=r.recipient_kind
        and ((r.recipient_kind='STUDENT' and d.student_id=r.student_id)
          or (r.recipient_kind='USER_ACCOUNT' and d.user_account_id=r.user_account_id))
        and c.status='SENT' and (c.expires_at is null or c.expires_at>now())
      order by a.next_attempt_at,a.id limit 100
      """);

    int processed = 0;
    for (Map<String,Object> row : attempts) {
      long attemptId = number(row.get("attempt_id"));
      // Conditional lease claim prevents separate API replicas from sending the same row.
      // A worker crash leaves the attempt pending but releases it automatically after ten minutes.
      int claimed=jdbc.update("""
        update notification_delivery_attempt
        set next_attempt_at=now()+interval '10 minutes'
        where id=? and channel='ANDROID_PUSH' and status in ('PENDING','RETRYING')
          and next_attempt_at<=now()
        """,attemptId);
      if(claimed!=1) continue;
      long recipientId = number(row.get("recipient_id"));
      long deviceId = number(row.get("device_registration_id"));
      Long recipientInstitutionId = row.get("recipient_institution_id") instanceof Number value
          ? value.longValue() : null;
      String kind = String.valueOf(row.get("recipient_kind"));
      long identityId = "STUDENT".equals(kind)
          ? number(row.get("student_id")) : number(row.get("user_account_id"));
      String scope = String.valueOf(row.get("scope"));
      Long courseId = row.get("course_id") instanceof Number value ? value.longValue() : null;
      int attemptNumber = Math.toIntExact(number(row.get("attempt_number")));

      boolean eligible = eligibilityInstitutionIds(row, recipientInstitutionId).stream()
          .anyMatch(institutionId -> isRecipientCurrentlyEligible(
              kind, identityId, institutionId, scope, courseId));
      if (!eligible) {
        failAttempt(attemptId, "RECIPIENT_INELIGIBLE");
        processed++;
        continue;
      }

      String title = compact(row.get("title"), 100);
      String body = compact(row.get("push_body"), 220);
      try {
        Message message = Message.builder()
            .setToken(String.valueOf(row.get("device_token")))
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .putData("qe_notification_recipient_id", Long.toString(recipientId))
            .putData("qe_campaign_public_id", String.valueOf(row.get("campaign_public_id")))
            .setAndroidConfig(AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                    .setChannelId(CHANNEL_ID)
                    .setClickAction(OPEN_ACTION)
                    .setSound("default")
                    .build())
                .build())
            .build();
        String providerMessageId = messaging.send(message);
        jdbc.update("""
          update notification_delivery_attempt
          set status='SENT',provider_reference=?,failure_code=null,
              attempted_at=now(),acknowledged_at=null
          where id=? and channel='ANDROID_PUSH' and status in ('PENDING','RETRYING')
          """, providerMessageId, attemptId);
      } catch (FirebaseMessagingException failure) {
        MessagingErrorCode errorCode = failure.getMessagingErrorCode();
        String failureCode = errorCode == null ? "FCM_SEND_ERROR" : errorCode.name();
        if (errorCode == MessagingErrorCode.UNREGISTERED) {
          jdbc.update("""
            update notification_device_registration
            set status='REVOKED',revoked_at=now(),updated_at=now()
            where id=? and status='ACTIVE'
            """, deviceId);
          failAttempt(attemptId, "TOKEN_UNREGISTERED");
        } else {
          retryOrFail(attemptId, recipientId, deviceId, attemptNumber, failureCode);
        }
        LOGGER.warn("Android push attempt {} failed with {}", attemptId, failureCode);
      } catch (RuntimeException failure) {
        retryOrFail(attemptId, recipientId, deviceId, attemptNumber, "FCM_DELIVERY_ERROR");
        LOGGER.warn("Android push attempt {} failed with {}", attemptId, failure.getClass().getSimpleName());
      }
      processed++;
    }
    return processed;
  }


  /**
   * A device's currently selected institution is not a recipient boundary: parents may have
   * eligible children in several institutions. For HRMS fan-out rows, check the frozen campaign
   * target list and send once per account/device only if the account remains eligible somewhere
   * in that list.
   */
  List<Long> eligibilityInstitutionIds(Map<String,Object> row, Long recipientInstitutionId) {
    if (recipientInstitutionId != null && recipientInstitutionId > 0L) {
      return List.of(recipientInstitutionId);
    }
    Object raw = row.get("audience_snapshot_json");
    if (raw == null || String.valueOf(raw).isBlank()) return List.of();
    try {
      JsonNode ids = mapper.readTree(String.valueOf(raw)).path("institutionIds");
      if (!ids.isArray()) return List.of();
      LinkedHashSet<Long> result = new LinkedHashSet<>();
      for (JsonNode id : ids) {
        if (id.canConvertToLong() && id.asLong() > 0L) result.add(id.asLong());
      }
      return List.copyOf(result);
    } catch (Exception failure) {
      LOGGER.warn("Android push attempt has an invalid campaign institution snapshot");
      return List.of();
    }
  }

  private boolean isRecipientCurrentlyEligible(
      String kind,long identityId,long institutionId,String scope,Long courseId) {
    boolean courseScoped = "COURSE".equals(scope);
    if ("STUDENT".equals(kind)) {
      return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(
          select 1 from student st
          join institution_membership m on m.student_id=st.id and m.status='ACTIVE'
          join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
          where st.id=? and st.active=true and m.institution_id=?
            and (?=false or exists(
              select 1 from student_program_enrollment spe
              join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
              join institution_course_release rel on rel.id=a.course_release_id and rel.program_id=spe.program_id
              where spe.student_id=st.id and spe.institution_id=m.institution_id
                and spe.status='ACTIVE' and spe.program_id=?
                 and spe.course_access_blocked=false
                 and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
            ))
        )
        """, Boolean.class, identityId,institutionId,courseScoped,courseId));
    }
    if ("USER_ACCOUNT".equals(kind)) {
      return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(
          select 1 from user_account u
          join institution_user_membership um on um.user_id=u.id and um.status='ACTIVE'
          where u.id=? and u.active=true and um.institution_id=?
            and exists(
              select 1 from guardian_student gs
              join student st on st.id=gs.student_id and st.active=true
              join institution_membership m on m.student_id=st.id and m.institution_id=um.institution_id and m.status='ACTIVE'
              join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
              where gs.guardian_user_id=u.id and gs.active=true
                and gs.consent_status='CONSENTED' and gs.access_level='FULL'
                and (?=false or exists(
                  select 1 from student_program_enrollment spe
                  join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
                  join institution_course_release rel on rel.id=a.course_release_id and rel.program_id=spe.program_id
                  where spe.student_id=st.id and spe.institution_id=m.institution_id
                    and spe.status='ACTIVE' and spe.program_id=?
                 and spe.course_access_blocked=false
                 and (spe.course_expires_at is null or spe.course_expires_at>statement_timestamp())
                ))
            )
        )
        """, Boolean.class,identityId,institutionId,courseScoped,courseId));
    }
    return false;
  }

  private void retryOrFail(long attemptId,long recipientId,long deviceId,int attemptNumber,String failureCode) {
    failAttempt(attemptId,failureCode);
    if (attemptNumber >= MAX_ATTEMPTS) return;
    long delaySeconds = switch (attemptNumber) {
      case 1 -> 30L;
      case 2 -> 120L;
      default -> 600L;
    };
    jdbc.update("""
      insert into notification_delivery_attempt(
        recipient_id,channel,attempt_number,device_registration_id,status,
        attempted_at,next_attempt_at,failure_code)
      values (?,'ANDROID_PUSH',?,?, 'RETRYING',now(),?,?)
      on conflict do nothing
      """, recipientId,attemptNumber+1,deviceId,
      Timestamp.from(Instant.now().plusSeconds(delaySeconds)),failureCode);
  }

  private void failAttempt(long attemptId,String failureCode) {
    jdbc.update("""
      update notification_delivery_attempt
      set status='FAILED',failure_code=?,attempted_at=now()
      where id=? and channel='ANDROID_PUSH' and status in ('PENDING','RETRYING')
      """,failureCode,attemptId);
  }

  private static long number(Object value) {
    if (!(value instanceof Number number)) throw new IllegalStateException("A push delivery identity is invalid.");
    return number.longValue();
  }

  private static String compact(Object value,int maxLength) {
    String result = value == null ? "" : String.valueOf(value);
    result = result.replaceAll("<[^>]*>", " ").replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
    if (result.isBlank()) result = "Open QuantaEdge to view details";
    return result.length() <= maxLength ? result : result.substring(0,maxLength-1).stripTrailing() + "…";
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException("SHA-256 is unavailable.",failure);
    }
  }
}
