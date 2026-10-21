package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HrmsLoginThrottleTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void blocksFurtherAttemptsAfterFiveRecentFailures() {
    AuthService auth = new AuthService(jdbc, false, 4, true, 60, "");
    when(jdbc.queryForObject(contains("select count(*) from hrms_login_failure"),
        eq(Long.class), anyString())).thenReturn(5L);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> auth.loginHrms("HRMS_Admin", "wrong-password"));

    assertEquals(429, error.getStatusCode().value());
    verify(jdbc, never()).queryForList(contains("select s.id, s.password_hash"), anyString());
    verify(jdbc, never()).update(contains("insert into hrms_login_failure"), anyString());
  }

  @Test
  void recordsBadUsernameAttemptsWithoutStoringTheRawUsername() {
    AuthService auth = new AuthService(jdbc, false, 4, true, 60, "");
    when(jdbc.queryForObject(contains("select count(*) from hrms_login_failure"),
        eq(Long.class), anyString())).thenReturn(0L);
    when(jdbc.queryForList(contains("select s.id, s.password_hash"), anyString())).thenReturn(java.util.List.of());

    assertThrows(IllegalArgumentException.class, () -> auth.loginHrms("does-not-exist", "wrong-password"));

    verify(jdbc).update(contains("insert into hrms_login_failure"), anyString());
  }
}
