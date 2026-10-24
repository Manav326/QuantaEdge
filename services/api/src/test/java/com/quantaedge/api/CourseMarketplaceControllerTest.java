package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CourseMarketplaceControllerTest {
  @Test
  void razorpayCheckoutSignatureUsesHmacSha256Hex() {
    assertEquals(
        "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        CourseMarketplaceController.hmacSha256(
            "The quick brown fox jumps over the lazy dog", "key"));
  }

  @Test
  void idempotencyKeysRejectMissingShortOrUnsafeValues() {
    assertTrue(CourseMarketplaceController.isValidIdempotencyKey("af1f6f29-6c6c-4c73-94d1"));
    assertFalse(CourseMarketplaceController.isValidIdempotencyKey(null));
    assertFalse(CourseMarketplaceController.isValidIdempotencyKey("short"));
    assertFalse(CourseMarketplaceController.isValidIdempotencyKey("invalid key with spaces"));
    assertFalse(CourseMarketplaceController.isValidIdempotencyKey("x".repeat(65)));
  }

  @Test
  void onlyCompletePendingRazorpayOrdersAreReusable() {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("status", "PENDING");
    order.put("provider_order_id", "order_Abc123");
    order.put("offer_price_paise", 15000L);
    order.put("currency", "INR");
    assertTrue(CourseMarketplaceController.isReusablePendingOrder(order));

    order.put("status", "CREATING");
    assertFalse(CourseMarketplaceController.isReusablePendingOrder(order));
    order.put("status", "PENDING");
    order.put("provider_order_id", "");
    assertFalse(CourseMarketplaceController.isReusablePendingOrder(order));
    order.put("provider_order_id", "order_Abc123");
    order.put("currency", "USD");
    assertFalse(CourseMarketplaceController.isReusablePendingOrder(order));
  }

  @Test
  void automaticFinancialSnapshotRecoveryIsRestrictedToRazorpayTestMode() {
    assertTrue(CourseMarketplaceController.isRazorpayTestModeKey("rzp_test_4FKABC123xyz"));
    assertFalse(CourseMarketplaceController.isRazorpayTestModeKey("rzp_live_4FKABC123xyz"));
    assertFalse(CourseMarketplaceController.isRazorpayTestModeKey(null));
    assertFalse(CourseMarketplaceController.isRazorpayTestModeKey("rzp_test_"));
    assertFalse(CourseMarketplaceController.isRazorpayTestModeKey("test_key_without_provider_prefix"));
  }

  @Test
  void legacyIncompleteOrderMayOnlyBeRepairedWhenProviderConfirmsRetryIsSafe() {
    assertTrue(CourseMarketplaceController.providerConfirmsRetryIsSafe("RETRYABLE"));
    assertFalse(CourseMarketplaceController.providerConfirmsRetryIsSafe("PAID"));
    assertFalse(CourseMarketplaceController.providerConfirmsRetryIsSafe("IN_FLIGHT"));
    assertFalse(CourseMarketplaceController.providerConfirmsRetryIsSafe("UNKNOWN"));
    assertFalse(CourseMarketplaceController.providerConfirmsRetryIsSafe(null));
  }

  @Test
  void onlyLegacyOrdersWithNoDiscountFactsCanBeRepairedAutomatically() {
    Map<String,Object> legacy = new LinkedHashMap<>();
    legacy.put("discount_reference", null);
    legacy.put("discount_offer_id", null);
    legacy.put("discount_amount_paise", 0L);
    legacy.put("discount_snapshot_json", "{}");
    assertTrue(CourseMarketplaceController.hasNoDiscountFacts(legacy));

    legacy.put("discount_snapshot_json", "{\"snapshotVersion\": 1, \"discountType\": \"NONE\", \"discountPaise\": 0}");
    assertTrue(CourseMarketplaceController.hasNoDiscountFacts(legacy));

    legacy.put("discount_snapshot_json", "{\"discountType\": \"NONE\", \"discountPaise\": 100}");
    assertFalse(CourseMarketplaceController.hasNoDiscountFacts(legacy));
    legacy.put("discount_snapshot_json", "{}");

    legacy.put("discount_reference", "SAVE10");
    assertFalse(CourseMarketplaceController.hasNoDiscountFacts(legacy));
    legacy.put("discount_reference", null);
    legacy.put("discount_offer_id", 17L);
    assertFalse(CourseMarketplaceController.hasNoDiscountFacts(legacy));
    legacy.put("discount_offer_id", null);
    legacy.put("discount_amount_paise", 100L);
    assertFalse(CourseMarketplaceController.hasNoDiscountFacts(legacy));
  }

  @Test
  void verificationRejectsMissingFinancialSnapshotsBeforeProviderCapture() {
    Map<String,Object> incomplete = new LinkedHashMap<>();
    incomplete.put("status", "PENDING");
    incomplete.put("tax_profile_id", null);
    incomplete.put("revenue_share_policy_id", 7L);
    incomplete.put("tax_calculation_snapshot_json", "{}");
    incomplete.put("revenue_share_policy_snapshot_json", "{}");

    var exception = org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> CourseMarketplaceController.requireLockedFinancialSnapshotsForVerification(incomplete));

    assertEquals(409, exception.getStatusCode().value());
    assertTrue(exception.getReason().contains("Do not pay again"));
  }

  @Test
  void capturedPaymentMustMatchPaymentIdOrderAmountAndCurrency() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode payment = mapper.readTree("""
        {
          "id": "pay_123",
          "order_id": "order_123",
          "amount": 100000,
          "currency": "INR",
          "status": "captured"
        }
        """);

    assertTrue(CourseMarketplaceController.isCapturedPaymentForExactOrder(
        payment, "pay_123", "order_123", 100000, "INR"));
    assertFalse(CourseMarketplaceController.isCapturedPaymentForExactOrder(
        payment, "pay_other", "order_123", 100000, "INR"));
    assertFalse(CourseMarketplaceController.isCapturedPaymentForExactOrder(
        payment, "pay_123", "order_other", 100000, "INR"));
    assertFalse(CourseMarketplaceController.isCapturedPaymentForExactOrder(
        payment, "pay_123", "order_123", 99999, "INR"));
    assertFalse(CourseMarketplaceController.isCapturedPaymentForExactOrder(
        payment, "pay_123", "order_123", 100000, "USD"));
  }

  @Test
  void authorisedPaymentMustBeBoundToExpectedOrderBeforeCapture() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode payment = mapper.readTree("""
        {
          "id": "pay_456",
          "order_id": "order_456",
          "amount": 100000,
          "currency": "INR",
          "status": "authorized"
        }
        """);

    assertTrue(CourseMarketplaceController.isPaymentForExactOrder(
        payment, "pay_456", "order_456", 100000, "INR"));
    assertFalse(CourseMarketplaceController.isCapturedPaymentForExactOrder(
        payment, "pay_456", "order_456", 100000, "INR"));
    assertFalse(CourseMarketplaceController.isPaymentForExactOrder(
        payment, "pay_456", "order_tampered", 100000, "INR"));
  }

  @Test
  void signatureIsBoundToExactOrderAndPaymentIds() {
    String expected = CourseMarketplaceController.hmacSha256("order_123|pay_456", "test_secret");
    assertEquals(64, expected.length());
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_999|pay_456", "test_secret"));
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_123|pay_999", "test_secret"));
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_123|pay_456", "different_secret"));
  }

  @Test
  void retryableCheckoutCanOnlyReuseTheSameLockedOfferCode() {
    assertTrue(CourseMarketplaceController.discountCodeMatchesForRetry("save10", " SAVE10 "));
    assertTrue(CourseMarketplaceController.discountCodeMatchesForRetry(null, "  "));
    assertFalse(CourseMarketplaceController.discountCodeMatchesForRetry("SAVE10", "SAVE20"));
    assertFalse(CourseMarketplaceController.discountCodeMatchesForRetry("SAVE10", null));
  }

  @Test
  void retryOfferCodeValidationRejectsMalformedValues() {
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> CourseMarketplaceController.discountCodeMatchesForRetry("SAVE10", "bad code"));
  }
}
