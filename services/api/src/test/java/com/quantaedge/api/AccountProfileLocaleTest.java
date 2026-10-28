package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

class AccountProfileLocaleTest {
  @Test
  void childReadOnlyProfileDoesNotInferInterfaceLocaleFromLearningLanguage() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AuthorizationService authorization = mock(AuthorizationService.class);
    AuthContext context = new AuthContext(null, 77L, "STUDENT", "Learner");
    when(authorization.requireAuth(context)).thenReturn(context);
    Map<String,Object> row = Map.of(
        "id", 77L,
        "display_name", "Learner",
        "class_code", "CLASS_6",
        "board", "CBSE",
        "language", "english",
        "username", "learner77");
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(), List.of(row));

    Map<String,Object> profile = new AccountProfileController(jdbc, authorization).profile(context);

    assertEquals("english", profile.get("learningLanguage"));
    assertNull(profile.get("preferredLocale"),
        "Child learning-language preference must not seed the interface locale.");
  }

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
