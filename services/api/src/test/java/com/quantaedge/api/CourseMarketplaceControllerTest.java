package com.quantaedge.api;

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
  void signatureIsBoundToExactOrderAndPaymentIds() {
    String expected = CourseMarketplaceController.hmacSha256("order_123|pay_456", "test_secret");
    assertEquals(64, expected.length());
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_999|pay_456", "test_secret"));
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_123|pay_999", "test_secret"));
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_123|pay_456", "different_secret"));
  }
}
