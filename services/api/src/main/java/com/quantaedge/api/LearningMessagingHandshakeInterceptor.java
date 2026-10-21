package com.quantaedge.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public class LearningMessagingHandshakeInterceptor implements HandshakeInterceptor {
  private final AuthService auth;
  private final Set<String> learningOrigins;
  private final Set<String> staffOrigins;

  public LearningMessagingHandshakeInterceptor(AuthService auth, String learningOrigins, String staffOrigins) {
    this.auth = auth;
    this.learningOrigins = parse(learningOrigins);
    this.staffOrigins = parse(staffOrigins);
  }

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Map<String,Object> attributes) {
    String origin = request.getHeaders().getOrigin();
    boolean learningOrigin = origin != null && learningOrigins.contains(origin);
    boolean staffOrigin = origin != null && staffOrigins.contains(origin);
    if (origin != null && !learningOrigin && !staffOrigin) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    String hint = workspaceHint(request);
    List<String> cookies = cookiePriority(hint, learningOrigin, staffOrigin);
    List<String> cookieHeaders = request.getHeaders().getOrEmpty(HttpHeaders.COOKIE);
    for (String cookieName : cookies) {
      for (String token : cookieValues(cookieHeaders, cookieName)) {
        AuthContext context;
        try { context = auth.current(token); } catch (RuntimeException ignored) { context = null; }
        if (!isMessagingIdentity(context) || !matchesHint(context, hint)
            || !matchesOrigin(context, learningOrigin, staffOrigin)) continue;
        attributes.put("authContext", context);
        attributes.put("authWorkspace", context.workspace());
        return true;
      }
    }
    response.setStatusCode(HttpStatus.UNAUTHORIZED);
    return false;
  }

  static List<String> cookieValues(List<String> headers, String cookieName) {
    if (headers == null || cookieName == null || cookieName.isBlank()) return List.of();
    List<String> result = new ArrayList<>();
    for (String header : headers) {
      if (header == null || header.isBlank()) continue;
      for (String pair : header.split(";")) {
        int equals = pair.indexOf('=');
        if (equals <= 0 || !cookieName.equals(pair.substring(0, equals).trim())) continue;
        String value = pair.substring(equals + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        if (!value.isBlank()) result.add(value);
      }
    }
    return List.copyOf(result);
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Exception exception) {}

  private static List<String> cookiePriority(String hint, boolean learningOrigin, boolean staffOrigin) {
    List<String> students = List.of("QE_STUDENT_SESSION", AuthService.LEARNING_COOKIE, AuthService.COOKIE);
    List<String> staff = List.of(AuthService.STAFF_COOKIE, AuthService.COOKIE);
    if ("LEARNING".equals(hint) || "STUDENT".equals(hint) || (learningOrigin && !staffOrigin)) return students;
    if ("STAFF".equals(hint) || "EDUCATION_ADMIN".equals(hint) || (staffOrigin && !learningOrigin)) return staff;
    List<String> all = new ArrayList<>(students);
    for (String name : staff) if (!all.contains(name)) all.add(name);
    return all;
  }

  private static String workspaceHint(ServerHttpRequest request) {
    String query = request.getURI().getRawQuery();
    if (query == null) return "";
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && "workspace".equals(pair.substring(0, equals))) {
        try {
          return java.net.URLDecoder.decode(pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8)
              .trim().toUpperCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException invalid) { return ""; }
      }
    }
    return "";
  }

  private static boolean isMessagingIdentity(AuthContext context) {
    if (context == null || !context.isAuthenticated() || context.workspace() == null) return false;
    if (context.isStudent()) return Set.of("LEARNING","STUDENT").contains(context.workspace());
    return context.isEmployee() && Set.of("STAFF","EDUCATION_ADMIN").contains(context.workspace());
  }

  private static boolean matchesHint(AuthContext context, String hint) {
    if (hint == null || hint.isBlank()) return true;
    return switch (hint) {
      case "LEARNING", "STUDENT" -> context.isStudent() && Set.of("LEARNING","STUDENT").contains(context.workspace());
      case "STAFF", "EDUCATION_ADMIN" -> context.isEmployee() && Set.of("STAFF","EDUCATION_ADMIN").contains(context.workspace());
      default -> false;
    };
  }

  private static boolean matchesOrigin(AuthContext context, boolean learningOrigin, boolean staffOrigin) {
    if (!learningOrigin && !staffOrigin) return true;
    if (context.isStudent()) return learningOrigin;
    return context.isEmployee() && staffOrigin;
  }

  private static Set<String> parse(String raw) {
    if (raw == null || raw.isBlank()) return Set.of();
    return java.util.Arrays.stream(raw.split(",")).map(String::trim)
        .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
  }
}
