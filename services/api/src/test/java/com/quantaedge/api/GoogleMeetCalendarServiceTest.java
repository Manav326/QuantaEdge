package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class GoogleMeetCalendarServiceTest {
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
}
