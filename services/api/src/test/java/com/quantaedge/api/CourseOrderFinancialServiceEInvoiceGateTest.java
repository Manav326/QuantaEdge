package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CourseOrderFinancialServiceEInvoiceGateTest {
  @Test
  void blocksSellerUntilApplicabilityHasBeenReviewed() {
    IllegalStateException error = checkoutFailure("UNREVIEWED", null, null);
    assertTrue(error.getMessage().contains("e-invoice applicability review"));
  }

  @Test
  void blocksSellerWhoseReviewSaysEInvoiceIsRequired() {
    IllegalStateException error = checkoutFailure(
        "REQUIRED", 42L, Timestamp.from(Instant.parse("2026-10-20T10:00:00Z")));
    assertTrue(error.getMessage().contains("requires e-invoicing"));
  }

  @Test
  void reviewedNotRequiredDecisionPassesThisGateButStillRequiresTaxConfiguration() {
    IllegalStateException error = checkoutFailure(
        "NOT_REQUIRED", 42L, Timestamp.from(Instant.parse("2026-10-20T10:00:00Z")));
    assertTrue(error.getMessage().contains("active HRMS-verified GST registration"));
  }

  private IllegalStateException checkoutFailure(String applicability, Long reviewer, Timestamp reviewedAt) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Map<String,Object> profile = new HashMap<>();
    profile.put("einvoice_applicability", applicability);
    profile.put("einvoice_applicability_reviewed_by_staff_id", reviewer);
    profile.put("einvoice_applicability_reviewed_at", reviewedAt);
    profile.put("tax_treatment", "TAXABLE");
    profile.put("gst_rate_basis_points", 1800);
    profile.put("cess_rate_basis_points", 0);
    profile.put("price_includes_tax", true);
    org.mockito.Mockito.when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql=invocation.getArgument(0,String.class);
      if(sql.contains("from institution_marketplace_offer o")) {
        return List.of(Map.<String,Object>of(
            "course_id",91L,"course_code","QE-COURSE-091","course_type_code","K12"));
      }
      return List.of(profile);
    });

    CourseOrderFinancialService service = new CourseOrderFinancialService(
        jdbc, new ObjectMapper(), mock(FiscalDocumentRenderer.class), mock(FiscalDocumentStorage.class));
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> service.prepareCheckout(1L, 2L, 10_000L, "INR", "Course", Map.of()));
    verify(jdbc, times(2)).queryForList(anyString(), any(Object[].class));
    return error;
  }
}
