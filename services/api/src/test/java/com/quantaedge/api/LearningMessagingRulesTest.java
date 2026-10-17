package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class LearningMessagingRulesTest {
  @Test
  void validClientMessageIdAndPlainTextAreNormalized() {
    LearningMessagingRules.NormalizedMessage result = LearningMessagingRules.normalize(
        new LearningMessageInput("123e4567-e89b-42d3-a456-426614174000", "  Good question  ", "question"), false);
    assertEquals("123e4567-e89b-42d3-a456-426614174000", result.clientMessageId());
    assertEquals("Good question", result.body());
    assertEquals("QUESTION", result.messageType());
  }
  @Test
  void clientMessageIdMustBeCanonicalForRetryIdentity() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
        LearningMessagingRules.normalize(new LearningMessageInput("not-a-uuid", "Hello", "TEXT"), false));
    assertEquals(400, error.getStatusCode().value());
  }
  @Test
  void onlyStaffCanCreateAnnouncements() {
    ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
        LearningMessagingRules.normalize(new LearningMessageInput(
            "123e4567-e89b-42d3-a456-426614174000", "Pinned guidance", "ANNOUNCEMENT"), false));
    assertEquals(400, error.getStatusCode().value());
  }
  @Test
  void blankAndOversizedBodiesAreRejected() {
    assertThrows(ResponseStatusException.class, () -> LearningMessagingRules.normalize(
        new LearningMessageInput("123e4567-e89b-42d3-a456-426614174000", "  ", "TEXT"), false));
    assertThrows(ResponseStatusException.class, () -> LearningMessagingRules.normalize(
        new LearningMessageInput("123e4567-e89b-42d3-a456-426614174000", "x".repeat(4001), "TEXT"), false));
  }
  @Test
  void websocketWorkspaceQueryHintIsEndpointScopedAndCannotOverrideHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/learning-chat/ws");
    request.setParameter("workspace", "LEARNING");
    assertEquals("LEARNING", AuthFilter.requestedWorkspace(request));
    request.addHeader(AuthService.WORKSPACE_HEADER, "EDUCATION_ADMIN");
    assertEquals("EDUCATION_ADMIN", AuthFilter.requestedWorkspace(request));
  }
  @Test
  void ordinaryEndpointsIgnoreWorkspaceQueryHints() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/learning/resources");
    request.setParameter("workspace", "LEARNING");
    assertNull(AuthFilter.requestedWorkspace(request));
  }
}
