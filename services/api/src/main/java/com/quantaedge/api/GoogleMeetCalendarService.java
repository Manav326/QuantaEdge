package com.quantaedge.api;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Creates and manages scheduled Google Meet conferences through a user-authorized
 * Google Calendar connection. A managed event ID lets a retry recover from lost
 * HTTP responses without creating duplicate events.
 */
@Component
public class GoogleMeetCalendarService {
  private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
  private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3/calendars/";
  private final RestClient http;
  private final String clientId;
  private final String clientSecret;
  private final String refreshToken;
  private final String calendarId;
  private final String timeZone;

  @Autowired
  public GoogleMeetCalendarService(RestClient.Builder builder,
      @Value("${app.google-meet.client-id:}") String clientId,
      @Value("${app.google-meet.client-secret:}") String clientSecret,
      @Value("${app.google-meet.refresh-token:}") String refreshToken,
      @Value("${app.google-meet.calendar-id:primary}") String calendarId,
      @Value("${app.google-meet.time-zone:Asia/Kolkata}") String timeZone) {
    this(builder.build(), clientId, clientSecret, refreshToken, calendarId, timeZone);
  }

  /** Used only by tests that verify fail-closed behavior without external credentials. */
  GoogleMeetCalendarService() {
    this(RestClient.builder().build(), "", "", "", "primary", "Asia/Kolkata");
  }

  private GoogleMeetCalendarService(RestClient http, String clientId, String clientSecret,
      String refreshToken, String calendarId, String timeZone) {
    this.http = http;
    this.clientId = clientId == null ? "" : clientId.trim();
    this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    this.refreshToken = refreshToken == null ? "" : refreshToken.trim();
    this.calendarId = calendarId == null || calendarId.isBlank() ? "primary" : calendarId.trim();
    this.timeZone = timeZone == null || timeZone.isBlank() ? "Asia/Kolkata" : timeZone.trim();
  }

  public MeetingResult createOrGetMeeting(long sessionId, String requestId, String title,
      String description, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    requireConfigured();
    String token = accessToken();
    String stableEventId = managedEventId(sessionId);
    String conferenceRequestId = requestId == null || requestId.isBlank()
        ? UUID.randomUUID().toString() : requestId;
    Map<String, Object> event;
    try {
      event = http.post().uri(eventsUri())
          .headers(headers -> headers.setBearerAuth(token))
          .contentType(MediaType.APPLICATION_JSON)
          .body(eventBody(stableEventId, conferenceRequestId, title, description, startsAt, endsAt))
          .retrieve().body(Map.class);
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() != 409) throw apiFailure(ex, false);
      // Google may have created the event before the client lost the first response.
      event = getEvent(stableEventId, token);
    } catch (RestClientException ex) {
      throw unavailable();
    }
    if (event == null || value(event, "id") == null) event = getEvent(stableEventId, token);
    return meetingResult(stableEventId, conferenceRequestId, event);
  }

  /** Refreshes Google conference status, or requests a new conference after provider failure. */
  public MeetingResult refreshOrRetryMeeting(String eventId, String currentRequestId) {
    requireConfigured();
    String token = accessToken();
    Map<String, Object> event = getEvent(eventId, token);
    if (meetingUrl(event) != null) {
      return new MeetingResult(eventId, currentRequestId, meetingUrl(event), "CREATED", null, null);
    }
    String state = conferenceStatus(event);
    if ("pending".equalsIgnoreCase(state)) {
      return new MeetingResult(eventId, currentRequestId, null, "PENDING", null,
          "Google Calendar accepted the event and is still generating its Meet link. Check again shortly.");
    }
    String newRequestId = UUID.randomUUID().toString();
    Map<String, Object> body = Map.of("conferenceData", conferenceData(newRequestId));
    Map<String, Object> updated;
    try {
      updated = http.patch().uri(eventUri(eventId) + "?conferenceDataVersion=1&sendUpdates=none")
          .headers(headers -> headers.setBearerAuth(token))
          .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    } catch (RestClientException ex) {
      throw mapFailure(ex, false);
    }
    if (updated == null || value(updated, "id") == null) updated = getEvent(eventId, token);
    return meetingResult(eventId, newRequestId, updated);
  }

  /** Reschedules the managed Google Calendar event while preserving its conference. */
  public MeetingResult rescheduleMeeting(String eventId, String requestId, String title,
      String description, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    requireConfigured();
    String token = accessToken();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("summary", title);
    body.put("description", description == null ? "" : description);
    body.put("start", timeBlock(startsAt));
    body.put("end", timeBlock(endsAt));
    Map<String, Object> event;
    try {
      event = http.patch().uri(eventUri(eventId) + "?conferenceDataVersion=1&sendUpdates=none")
          .headers(headers -> headers.setBearerAuth(token))
          .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    } catch (RestClientException ex) {
      throw mapFailure(ex, false);
    }
    if (event == null || value(event, "id") == null) event = getEvent(eventId, token);
    return meetingResult(eventId, requestId, event);
  }

  /** Stable identifier also permits cleanup after a successful Google request whose response was lost. */
  public String eventIdForSession(long sessionId) {
    return managedEventId(sessionId);
  }

  public void cancelMeeting(String eventId) {
    requireConfigured();
    String token = accessToken();
    try {
      http.delete().uri(eventUri(eventId) + "?sendUpdates=none")
          .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity();
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 404 || ex.getStatusCode().value() == 410) return;
      throw apiFailure(ex, false);
    } catch (RestClientException ex) {
      throw unavailable();
    }
  }

  private Map<String, Object> eventBody(String eventId, String requestId, String title,
      String description, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", eventId);
    body.put("summary", title);
    if (description != null && !description.isBlank()) body.put("description", description);
    body.put("start", timeBlock(startsAt));
    body.put("end", timeBlock(endsAt));
    body.put("conferenceData", conferenceData(requestId));
    return body;
  }

  private Map<String, Object> timeBlock(OffsetDateTime value) {
    return Map.of("dateTime", value.toString(), "timeZone", timeZone);
  }

  private Map<String, Object> conferenceData(String requestId) {
    return Map.of("createRequest", Map.of("requestId", requestId,
        "conferenceSolutionKey", Map.of("type", "hangoutsMeet")));
  }

  private Map<String, Object> getEvent(String eventId, String token) {
    try {
      Map<String, Object> event = http.get().uri(eventUri(eventId))
          .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Map.class);
      if (event == null) {
        throw new GoogleMeetException("GOOGLE_EMPTY_RESPONSE",
            "Google Calendar returned an empty event response.");
      }
      return event;
    } catch (RestClientException ex) {
      throw mapFailure(ex, false);
    }
  }

  private String accessToken() {
    String form = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
        + "&refresh_token=" + encode(refreshToken) + "&grant_type=refresh_token";
    try {
      Map<?, ?> token = http.post().uri(TOKEN_ENDPOINT)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).body(form)
          .retrieve().body(Map.class);
      Object access = token == null ? null : token.get("access_token");
      if (access == null || String.valueOf(access).isBlank()) {
        throw new GoogleMeetException("GOOGLE_AUTH_FAILED",
            "Google OAuth did not return an access token. Reconnect the authorized Google account.");
      }
      return String.valueOf(access);
    } catch (GoogleMeetException ex) {
      throw ex;
    } catch (RestClientException ex) {
      throw mapFailure(ex, true);
    }
  }

  private void requireConfigured() {
    if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
      throw new GoogleMeetException("GOOGLE_NOT_CONFIGURED",
          "Google Meet is not configured on this server. Set GOOGLE_MEET_CLIENT_ID, GOOGLE_MEET_CLIENT_SECRET, and GOOGLE_MEET_REFRESH_TOKEN, then retry.");
    }
  }

  private GoogleMeetException mapFailure(RuntimeException ex, boolean tokenRequest) {
    if (ex instanceof GoogleMeetException google) return google;
    if (ex instanceof RestClientResponseException response) return apiFailure(response, tokenRequest);
    return unavailable();
  }

  private GoogleMeetException apiFailure(RestClientResponseException ex, boolean tokenRequest) {
    int status = ex.getStatusCode().value();
    if (tokenRequest && (status == 400 || status == 401)) {
      return new GoogleMeetException("GOOGLE_AUTH_FAILED",
          "Google OAuth rejected the client credentials or refresh token. Re-authorize the integration account and update GOOGLE_MEET_CLIENT_ID, GOOGLE_MEET_CLIENT_SECRET, and GOOGLE_MEET_REFRESH_TOKEN.");
    }
    if (status == 401) return new GoogleMeetException("GOOGLE_AUTH_FAILED",
        tokenRequest
            ? "Google OAuth rejected the refresh token. Re-authorize the account and update GOOGLE_MEET_REFRESH_TOKEN."
            : "Google authorization was rejected. Re-authorize the account and retry.");
    if (status == 403) return new GoogleMeetException("GOOGLE_PERMISSION_DENIED",
        "The authorized Google account lacks Calendar permission. Grant the calendar.events scope and verify Calendar API access.");
    if (status == 429) return new GoogleMeetException("GOOGLE_QUOTA_EXCEEDED",
        "Google Calendar quota was reached. Wait before retrying this operation.");
    if (status >= 500) return new GoogleMeetException("GOOGLE_TEMPORARY_FAILURE",
        "Google Calendar is temporarily unavailable. The QuantaEdge schedule is retained; retry the Meet operation.");
    return new GoogleMeetException("GOOGLE_API_REJECTED",
        "Google Calendar rejected the Meet operation (HTTP " + status + "). Verify calendar access and conference availability.");
  }

  private GoogleMeetException unavailable() {
    return new GoogleMeetException("GOOGLE_NETWORK_FAILURE",
        "QuantaEdge could not reach Google Calendar. The local schedule is retained; check outbound HTTPS access and retry.");
  }

  private String eventsUri() {
    return CALENDAR_API + encodePath(calendarId) + "/events?conferenceDataVersion=1&sendUpdates=none";
  }

  private String eventUri(String eventId) {
    return CALENDAR_API + encodePath(calendarId) + "/events/" + encodePath(eventId);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String encodePath(String value) {
    return encode(value).replace("+", "%20");
  }

  private static String managedEventId(long sessionId) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(("quantaedge-live-class-" + sessionId).getBytes(StandardCharsets.UTF_8));
      // Lower-case hexadecimal satisfies the lowercase event-ID character subset accepted by Calendar.
      return "q" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by the JDK.", ex);
    }
  }

  private static String conferenceStatus(Map<String, Object> event) {
    Map<?, ?> conference = nested(event, "conferenceData");
    Map<?, ?> request = nested(conference, "createRequest");
    Map<?, ?> status = nested(request, "status");
    Object value = status.get("statusCode");
    return value == null ? "" : String.valueOf(value);
  }

  private static String meetingUrl(Map<String, Object> event) {
    if (event == null) return null;
    Object hangout = event.get("hangoutLink");
    if (safeMeetUrl(hangout)) return String.valueOf(hangout);
    Map<?, ?> conference = nested(event, "conferenceData");
    Object entries = conference.get("entryPoints");
    if (entries instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> entry)) continue;
        if ("video".equals(String.valueOf(entry.get("entryPointType"))) && safeMeetUrl(entry.get("uri"))) {
          return String.valueOf(entry.get("uri"));
        }
      }
    }
    return null;
  }

  private static boolean safeMeetUrl(Object value) {
    if (value == null) return false;
    try {
      URI uri = URI.create(String.valueOf(value));
      return "https".equalsIgnoreCase(uri.getScheme())
          && "meet.google.com".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private MeetingResult meetingResult(String eventId, String requestId, Map<String, Object> event) {
    String link = meetingUrl(event);
    String state = conferenceStatus(event);
    if ("failure".equalsIgnoreCase(state) && link == null) {
      return new MeetingResult(eventId, requestId, null, "FAILED", "CONFERENCE_CREATION_FAILED",
          "Google Calendar created the event but Meet conference generation failed. Retry Meet setup to request a new conference.");
    }
    if (link != null) return new MeetingResult(eventId, requestId, link, "CREATED", null, null);
    return new MeetingResult(eventId, requestId, null, "PENDING", null,
        "The Calendar event is saved, but the Meet link is still being generated. Check or retry the integration.");
  }

  private static Map<?, ?> nested(Map<?, ?> source, String name) {
    if (source == null) return Map.of();
    Object value = source.get(name);
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private static String value(Map<?, ?> source, String name) {
    Object value = source == null ? null : source.get(name);
    return value == null ? null : String.valueOf(value);
  }

  public record MeetingResult(String eventId, String conferenceRequestId, String joinUrl,
      String status, String errorCode, String errorMessage) {}

  public static final class GoogleMeetException extends RuntimeException {
    private final String code;
    public GoogleMeetException(String code, String message) {
      super(message);
      this.code = code;
    }
    public String code() { return code; }
  }
}
