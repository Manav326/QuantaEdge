package com.quantaedge.api;

import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class LearningMessagingRules {
  record NormalizedMessage(String clientMessageId, String body, String messageType) {}
  private LearningMessagingRules() {}

  static NormalizedMessage normalize(LearningMessageInput input, boolean staffSender) {
    if (input == null) throw badRequest("A message body is required.");
    String rawId = input.clientMessageId() == null ? "" : input.clientMessageId().trim();
    String canonicalId;
    try {
      canonicalId = UUID.fromString(rawId).toString();
      if (!canonicalId.equalsIgnoreCase(rawId)) throw new IllegalArgumentException("non-canonical UUID");
    } catch (IllegalArgumentException ex) {
      throw badRequest("A valid canonical client message ID is required for retry-safe sending.");
    }
    String body = input.body() == null ? "" : input.body().strip();
    if (body.isBlank() || body.length() > 4000) throw badRequest("Message text must contain 1–4000 characters.");
    String type = input.messageType() == null || input.messageType().isBlank()
        ? "TEXT" : input.messageType().trim().toUpperCase(Locale.ROOT);
    if (!java.util.Set.of("TEXT", "QUESTION", "ANNOUNCEMENT").contains(type)) {
      throw badRequest("Unsupported message type.");
    }
    if ("ANNOUNCEMENT".equals(type) && !staffSender) {
      throw badRequest("Only the assigned teacher or an authorized moderator can post announcements.");
    }
    return new NormalizedMessage(canonicalId, body, type);
  }
  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
