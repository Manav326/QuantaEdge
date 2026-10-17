package com.quantaedge.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Tracks scoped subscriptions so eligibility is rechecked before each realtime delivery. */
@Component
public class LearningMessagingConnectionRegistry {
  private final ConcurrentMap<String,Connection> connections = new ConcurrentHashMap<>();

  public void connect(String sessionId, AuthContext context) {
    if (sessionId == null || sessionId.isBlank() || context == null) return;
    connections.put(sessionId, new Connection(context));
  }

  public void subscribe(String sessionId, String subscriptionId, long conversationId, AuthContext context) {
    if (sessionId == null || subscriptionId == null || subscriptionId.isBlank() || context == null) return;
    connections.compute(sessionId, (key, old) -> {
      Connection value = old == null ? new Connection(context) : old;
      if (!principalName(value.context()).equals(principalName(context))) {
        throw new IllegalStateException("WebSocket identity changed.");
      }
      value.subscriptions().put(subscriptionId, conversationId);
      return value;
    });
  }

  public void unsubscribe(String sessionId, String subscriptionId) {
    Connection connection = connections.get(sessionId);
    if (connection != null && subscriptionId != null) connection.subscriptions().remove(subscriptionId);
  }

  public void removeConversation(String sessionId, long conversationId) {
    Connection connection = connections.get(sessionId);
    if (connection != null) connection.subscriptions().entrySet().removeIf(entry -> entry.getValue() == conversationId);
  }

  public void disconnect(String sessionId) {
    if (sessionId != null) connections.remove(sessionId);
  }

  public List<Subscriber> subscribers(long conversationId) {
    List<Subscriber> result = new ArrayList<>();
    for (Map.Entry<String,Connection> entry : connections.entrySet()) {
      Connection connection = entry.getValue();
      if (connection.subscriptions().containsValue(conversationId)) {
        result.add(new Subscriber(entry.getKey(), principalName(connection.context()), connection.context()));
      }
    }
    return List.copyOf(result);
  }

  static String principalName(AuthContext context) {
    String workspace = context.workspace() == null ? "UNKNOWN" : context.workspace();
    return (context.isStudent() ? "STUDENT:" + context.studentId() : "STAFF:" + context.staffId()) + "@" + workspace;
  }

  private record Connection(AuthContext context, ConcurrentMap<String,Long> subscriptions) {
    Connection(AuthContext context) { this(context, new ConcurrentHashMap<>()); }
  }
  public record Subscriber(String sessionId, String principalName, AuthContext context) {}
}
