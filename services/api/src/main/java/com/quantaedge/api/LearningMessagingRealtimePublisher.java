package com.quantaedge.api;

import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

record LearningMessagePublishedEvent(long conversationId, Map<String,Object> message) {}

/** Realtime delivery happens after the PostgreSQL transaction commits. */
@Component
public class LearningMessagingRealtimePublisher {
  private final SimpMessagingTemplate messaging;
  private final LearningMessagingService service;
  private final LearningMessagingConnectionRegistry connections;

  public LearningMessagingRealtimePublisher(SimpMessagingTemplate messaging,
      LearningMessagingService service, LearningMessagingConnectionRegistry connections) {
    this.messaging = messaging;
    this.service = service;
    this.connections = connections;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(LearningMessagePublishedEvent event) {
    for (LearningMessagingConnectionRegistry.Subscriber subscriber : connections.subscribers(event.conversationId())) {
      try {
        service.assertCanReadConversation(event.conversationId(), subscriber.context());
        messaging.convertAndSendToUser(subscriber.principalName(),
            "/queue/learning-conversations/" + event.conversationId(), event.message());
      } catch (RuntimeException noLongerAuthorized) {
        connections.removeConversation(subscriber.sessionId(), event.conversationId());
      }
    }
  }
}
