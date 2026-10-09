package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AuthServiceRegistrationTest {
  private static final String MOBILE = "7070107483";
  private static final String NORMALIZED_MOBILE = "+917070107483";

  @Mock private JdbcTemplate jdbc;
  private AuthService auth;

  @BeforeEach
  void setUp() {
    auth = new AuthService(jdbc, true, 168, false, 60, "");
  }

  @Test
  void newVisitorCanRequestSignupOtp() {
    when(jdbc.queryForObject(contains("select exists(select 1 from user_account"),
        eq(Boolean.class), eq(NORMALIZED_MOBILE))).thenReturn(false);
    when(jdbc.queryForObject(contains("requested_at > now() -"), eq(Long.class),
        eq(NORMALIZED_MOBILE), eq(60))).thenReturn(0L);
    when(jdbc.queryForObject(contains("requested_at > now()-interval '1 hour'"),
        eq(Long.class), eq(NORMALIZED_MOBILE))).thenReturn(0L);

    assertEquals("123456", auth.requestOtp(MOBILE, "SIGNUP"));
    verify(jdbc).update(contains("insert into otp_challenge"), eq(NORMALIZED_MOBILE),
        eq("SIGNUP"), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void customerCanRequestLoginOtpWhenSameMobileAlsoHasStaffIdentity() {
    when(jdbc.queryForObject(contains("select exists(select 1 from user_account"),
        eq(Boolean.class), eq(NORMALIZED_MOBILE))).thenReturn(true);
    when(jdbc.queryForObject(contains("requested_at > now() -"), eq(Long.class),
        eq(NORMALIZED_MOBILE), eq(60))).thenReturn(0L);
    when(jdbc.queryForObject(contains("requested_at > now()-interval '1 hour'"),
        eq(Long.class), eq(NORMALIZED_MOBILE))).thenReturn(0L);

    assertEquals("123456", auth.requestOtp(MOBILE, "LOGIN"));
    verify(jdbc).update(contains("insert into otp_challenge"), eq(NORMALIZED_MOBILE),
        eq("LOGIN"), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void loginOtpIsNotIssuedForANumberWithoutAnAccount() {
    when(jdbc.queryForObject(contains("select exists(select 1 from user_account"),
        eq(Boolean.class), eq(NORMALIZED_MOBILE))).thenReturn(false);

    assertThrows(IllegalArgumentException.class, () -> auth.requestOtp(MOBILE, "LOGIN"));
    verify(jdbc, never()).update(contains("insert into otp_challenge"),
        org.mockito.ArgumentMatchers.<Object[]>any());
  }

  @Test
  void signupOtpIsNotIssuedForAnExistingAccount() {
    when(jdbc.queryForObject(contains("select exists(select 1 from user_account"),
        eq(Boolean.class), eq(NORMALIZED_MOBILE))).thenReturn(true);

    assertThrows(IllegalStateException.class, () -> auth.requestOtp(MOBILE, "SIGNUP"));
    verify(jdbc, never()).update(contains("insert into otp_challenge"),
        org.mockito.ArgumentMatchers.<Object[]>any());
  }
}
