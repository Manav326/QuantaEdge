package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable HRMS operator history; records survive rollback of the reconciliation attempt itself. */
@Service
public class CoursePaymentOperationsAuditService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CoursePaymentOperationsAuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(long orderId, long staffId, String eventType, String resolutionCode,
      String message, String providerOrderId, String providerStatus, Map<String,Object> details) {
    String safeMessage = safe(message, 1200);
    String detailJson;
    try {
      detailJson = mapper.writeValueAsString(details == null ? Map.of() : details);
    } catch (JsonProcessingException exception) {
      detailJson = "{}";
    }
    jdbc.update("""
        insert into course_payment_operations_event(
          course_payment_order_id,actor_staff_id,event_type,resolution_code,message,
          provider_order_id,provider_status,idempotency_key,details
        ) values(?,?,?,?,?,?,?,?,?::jsonb)
        """, orderId, staffId, eventType, safe(resolutionCode,64),
        safeMessage.isBlank() ? "No additional message was provided." : safeMessage,
        nullable(providerOrderId,100), nullable(providerStatus,40),
        "course-payment-ops:"+UUID.randomUUID(), detailJson);
  }

  private static String nullable(String value, int limit) {
    String safe = safe(value, limit);
    return safe.isBlank() ? null : safe;
  }

  private static String safe(String value, int limit) {
    if (value == null) return "";
    String sanitized = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
        .replaceAll("\\s+", " ").trim();
    return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit);
  }
}
