package com.quantaedge.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class LearningMessagingStompController {
  private final LearningMessagingService messaging;
  public LearningMessagingStompController(LearningMessagingService messaging) { this.messaging = messaging; }

  @MessageMapping("/learning-conversations/{conversationId}/send")
  public void send(@DestinationVariable long conversationId, @Payload LearningMessageInput input,
      SimpMessageHeaderAccessor headers) {
    Map<String,Object> session = headers.getSessionAttributes();
    Object value = session == null ? null : session.get("authContext");
    if (!(value instanceof AuthContext context)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated messaging session required.");
    }
    messaging.sendMessage(conversationId, input, context);
  }
}
