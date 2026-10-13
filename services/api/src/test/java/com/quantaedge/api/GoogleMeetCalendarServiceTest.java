package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleMeetCalendarServiceTest {
  private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
  private static final String EVENTS_URL =
      "https://www.googleapis.com/calendar/v3/calendars/primary/events?conferenceDataVersion=1&sendUpdates=none";

  @Test
  void missingCredentialsFailClosedWithActionableConfigurationError() {
    GoogleMeetCalendarService service = new GoogleMeetCalendarService();
    GoogleMeetCalendarService.GoogleMeetException error = assertThrows(
        GoogleMeetCalendarService.GoogleMeetException.class,
        () -> service.createOrGetMeeting(42L, "request-id", "Revision", null,
            OffsetDateTime.parse("2026-10-20T10:00:00+05:30"),
            OffsetDateTime.parse("2026-10-20T11:00:00+05:30")));
    assertEquals("GOOGLE_NOT_CONFIGURED", error.code());
  }

  @Test
  void createReturnsOnlyVerifiedGoogleMeetUrl() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    GoogleMeetCalendarService service = new GoogleMeetCalendarService(
        builder, "client-id", "client-secret", "refresh-token", "primary", "Asia/Kolkata");
    server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"access_token\":\"access-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(EVENTS_URL)).andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.conferenceData.createRequest.conferenceSolutionKey.type").value("hangoutsMeet"))
        .andRespond(withSuccess("""
          {"id":"q0123456789abcdef","hangoutLink":"https://meet.google.com/abc-defg-hij",
           "conferenceData":{"createRequest":{"requestId":"attempt-1","status":{"statusCode":"success"}}}}
          """, MediaType.APPLICATION_JSON));

    GoogleMeetCalendarService.MeetingResult result = service.createOrGetMeeting(42L, "attempt-1",
        "Revision", "Exam preparation", OffsetDateTime.parse("2026-10-20T10:00:00+05:30"),
        OffsetDateTime.parse("2026-10-20T11:00:00+05:30"));

    assertEquals("CREATED", result.status());
    assertEquals("https://meet.google.com/abc-defg-hij", result.joinUrl());
    assertNull(result.errorCode());
    server.verify();
  }

  @Test
  void googlePermissionFailureIsMappedToActionableError() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    GoogleMeetCalendarService service = new GoogleMeetCalendarService(
        builder, "client-id", "client-secret", "refresh-token", "primary", "Asia/Kolkata");
    server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"access_token\":\"access-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(EVENTS_URL)).andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":{\"message\":\"insufficient calendar scope\"}}"));

    GoogleMeetCalendarService.GoogleMeetException error = assertThrows(
        GoogleMeetCalendarService.GoogleMeetException.class,
        () -> service.createOrGetMeeting(42L, "attempt-1", "Revision", null,
            OffsetDateTime.parse("2026-10-20T10:00:00+05:30"),
            OffsetDateTime.parse("2026-10-20T11:00:00+05:30")));

    assertEquals("GOOGLE_PERMISSION_DENIED", error.code());
    assertTrue(error.getMessage().contains("calendar.events"));
    server.verify();
  }

  @Test
  void retryRequestsANewConferenceAfterGoogleReportsFailure() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    GoogleMeetCalendarService service = new GoogleMeetCalendarService(
        builder, "client-id", "client-secret", "refresh-token", "primary", "Asia/Kolkata");
    server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"access_token\":\"access-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/primary/events/event-42"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("""
          {"id":"event-42","conferenceData":{"createRequest":{"requestId":"previous","status":{"statusCode":"failure"}}}}
          """, MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/primary/events/event-42?conferenceDataVersion=1&sendUpdates=none"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(jsonPath("$.conferenceData.createRequest.conferenceSolutionKey.type").value("hangoutsMeet"))
        .andRespond(withSuccess("""
          {"id":"event-42","conferenceData":{"createRequest":{"requestId":"retry","status":{"statusCode":"pending"}}}}
          """, MediaType.APPLICATION_JSON));

    GoogleMeetCalendarService.MeetingResult result = service.refreshOrRetryMeeting("event-42", "previous");

    assertEquals("PENDING", result.status());
    assertNull(result.joinUrl());
    assertNotEquals("previous", result.conferenceRequestId());
    server.verify();
  }
}
