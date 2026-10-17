package com.quantaedge.api;

import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Configuration
@EnableWebSocketMessageBroker
public class LearningMessagingWebSocketConfig implements WebSocketMessageBrokerConfigurer {
  private final AuthService auth;
  private final LearningMessagingService service;
  private final LearningMessagingConnectionRegistry connections;

  @Value("$" + "{app.auth.workspace-origins.learning:http://localhost:3000}")
  private String learningOrigins = "http://localhost:3000";
  @Value("$" + "{app.auth.workspace-origins.education-admin:http://localhost:3001}")
  private String staffOrigins = "http://localhost:3001";

  public LearningMessagingWebSocketConfig(AuthService auth, LearningMessagingService service,
      LearningMessagingConnectionRegistry connections) {
    this.auth = auth;
    this.service = service;
    this.connections = connections;
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/queue");
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    StompWebSocketEndpointRegistration endpoint = registry
        .addEndpoint("/api/v1/learning-chat/ws")
        .addInterceptors(new LearningMessagingHandshakeInterceptor(auth, learningOrigins, staffOrigins))
        .setHandshakeHandler(new DefaultHandshakeHandler() {
          @Override
          protected Principal determineUser(org.springframework.http.server.ServerHttpRequest request,
              WebSocketHandler handler, Map<String,Object> attributes) {
            Object value = attributes.get("authContext");
            if (!(value instanceof AuthContext context)) return null;
            return () -> LearningMessagingConnectionRegistry.principalName(context);
          }
        });
    endpoint.setAllowedOrigins(combinedOrigins(learningOrigins, staffOrigins));
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
      @Override
      public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return authorizeInbound(message);
      }
    });
  }

  private Message<?> authorizeInbound(Message<?> message) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    StompCommand command = accessor.getCommand();
    String sessionId = accessor.getSessionId();
    Map<String,Object> attributes = accessor.getSessionAttributes();
    AuthContext context = attributes != null && attributes.get("authContext") instanceof AuthContext item ? item : null;
    if (command == StompCommand.CONNECT) {
      if (context == null || sessionId == null || sessionId.isBlank()) {
        throw new MessageDeliveryException("Authenticated learning messaging session required.");
      }
      connections.connect(sessionId, context);
    } else if (command == StompCommand.SUBSCRIBE) {
      if (context == null) throw new MessageDeliveryException("Authenticated learning messaging session required.");
      String destination = accessor.getDestination();
      java.util.regex.Matcher match = java.util.regex.Pattern
          .compile("^/user/queue/learning-conversations/([1-9][0-9]*)$")
          .matcher(destination == null ? "" : destination);
      if (!match.matches()) throw new MessageDeliveryException("Unsupported messaging subscription.");
      long conversationId;
      try { conversationId = Long.parseLong(match.group(1)); }
      catch (NumberFormatException ex) { throw new MessageDeliveryException("Invalid conversation destination."); }
      service.assertCanReadConversation(conversationId, context);
      connections.subscribe(sessionId, accessor.getSubscriptionId(), conversationId, context);
    } else if (command == StompCommand.UNSUBSCRIBE) {
      connections.unsubscribe(sessionId, accessor.getSubscriptionId());
    } else if (command == StompCommand.DISCONNECT) {
      connections.disconnect(sessionId);
    } else if (command == StompCommand.SEND) {
      String destination = accessor.getDestination();
      if (destination == null || !destination.matches("^/app/learning-conversations/[1-9][0-9]*/send$")) {
        throw new MessageDeliveryException("Unsupported client messaging destination.");
      }
      if (context == null) throw new MessageDeliveryException("Authenticated learning messaging session required.");
    }
    return message;
  }

  private static String[] combinedOrigins(String learning, String staff) {
    return java.util.stream.Stream.of(learning, staff).filter(value -> value != null && !value.isBlank())
        .flatMap(value -> Arrays.stream(value.split(","))).map(String::trim)
        .filter(value -> !value.isBlank()).distinct().toArray(String[]::new);
  }
}
