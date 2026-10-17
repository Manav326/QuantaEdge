package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
  void signatureIsBoundToExactOrderAndPaymentIds() {
    String expected = CourseMarketplaceController.hmacSha256("order_123|pay_456", "test_secret");
    assertEquals(64, expected.length());
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_999|pay_456", "test_secret"));
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_123|pay_999", "test_secret"));
    assertNotEquals(expected, CourseMarketplaceController.hmacSha256("order_123|pay_456", "different_secret"));
  }
}
