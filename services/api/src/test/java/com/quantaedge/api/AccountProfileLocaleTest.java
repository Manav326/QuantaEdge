package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AccountProfileLocaleTest {
  @Test
  void preferredLocaleAcceptsOnlyRegisteredUiLocales() {
    assertEquals("english", AccountProfileController.normalizePreferredLocale(" ENGLISH "));
    assertEquals("hinglish", AccountProfileController.normalizePreferredLocale("Hinglish"));
    assertThrows(IllegalArgumentException.class,
        () -> AccountProfileController.normalizePreferredLocale("bhojpuri"));
    assertThrows(IllegalArgumentException.class,
        () -> AccountProfileController.normalizePreferredLocale("fr"));
  }
}
