package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void configuredLegacyAdminCanRequestStaffOtpEvenWhileLegacyUserRowExists() {
    String mobile = "+919876543210";
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "9876543210");
    when(jdbc.queryForList("select active from staff_account where mobile_e164=?", mobile))
        .thenReturn(List.of());
    when(jdbc.queryForObject(contains("interval '1 second'"), eq(Long.class), eq(mobile), eq(60)))
        .thenReturn(0L);
    when(jdbc.queryForObject(contains("interval '1 hour'"), eq(Long.class), eq(mobile)))
        .thenReturn(0L);

    assertEquals("123456", auth.requestOtp("9876543210", "STAFF_LOGIN"));
  }

  @Test
  void configuredLegacyAdminIsMovedToSeparateStaffIdentityAfterValidOtp() throws Exception {
    String mobile = "+919876543210";
    long otpId = 31L;
    long staffId = 41L;
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "9876543210");
    Map<String,Object> otpRow = Map.of(
        "id", otpId,
        "code_hash", hash("123456"),
        "attempts", 0,
        "expires_at", Timestamp.from(Instant.now().plusSeconds(120)),
        "purpose", "STAFF_LOGIN");
    when(jdbc.queryForList(contains("from otp_challenge where mobile_e164=?"), eq(mobile)))
        .thenReturn(List.of(otpRow));
    when(jdbc.queryForList("select id, active from staff_account where mobile_e164=?", mobile))
        .thenReturn(List.of());
    when(jdbc.queryForObject(
        contains("insert into staff_account(public_id,mobile_e164,display_name,role,active)"),
        eq(Long.class), any(UUID.class), eq(mobile), eq("Administrator")))
        .thenReturn(staffId);
    when(jdbc.queryForMap("select role, display_name from staff_account where id=? and active=true", staffId))
        .thenReturn(Map.of("role", "ADMIN", "display_name", "Administrator"));

    AuthContext context = auth.verifyOtp("9876543210", "123456", "STAFF_LOGIN", null, true);

    assertNotNull(context);
    assertEquals(staffId, context.staffId());
    assertEquals("ADMIN", context.role());
    verify(jdbc).update(contains("update auth_session set revoked_at=now()"), eq(mobile));
    verify(jdbc).update(contains("update user_account set role='PARENT', active=false"), eq(mobile));
    verify(jdbc).update(contains("insert into staff_permission_grant"), eq(staffId));
  }

  private static String hash(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }
}
