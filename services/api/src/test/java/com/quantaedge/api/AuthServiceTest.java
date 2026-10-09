package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void localDemoOtpCanOptIntoRepeatedRequestsWithoutRateLimitQueries() throws Exception {
    String mobile = "+917070107483";
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    var field = AuthService.class.getDeclaredField("allowRepeatedDemoOtp");
    field.setAccessible(true);
    field.setBoolean(auth, true);
    when(jdbc.queryForList("select active from staff_account where mobile_e164=?", mobile))
        .thenReturn(List.of());

    assertEquals("123456", auth.requestOtp("7070107483", "STAFF_LOGIN"));
    verify(jdbc, never()).queryForObject(contains("interval '1 second'"), eq(Long.class), eq(mobile), eq(60));
    verify(jdbc, never()).queryForObject(contains("interval '1 hour'"), eq(Long.class), eq(mobile));
  }

  @Test
  void configuredBootstrapAdminCanRequestOtpWithoutExistingStaffRow() {
    String mobile = "+917070107483";
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    when(jdbc.queryForList("select active from staff_account where mobile_e164=?", mobile))
        .thenReturn(List.of());
    when(jdbc.queryForObject(contains("interval '1 second'"), eq(Long.class), eq(mobile), eq(60)))
        .thenReturn(0L);
    when(jdbc.queryForObject(contains("interval '1 hour'"), eq(Long.class), eq(mobile)))
        .thenReturn(0L);

    assertEquals("123456", auth.requestOtp("7070107483", "STAFF_LOGIN"));
  }

  @Test
  void configuredLegacyAdminIsMovedToSeparateStaffIdentityAfterValidOtp() throws Exception {
    String mobile = "+917070107483";
    long otpId = 31L;
    long staffId = 41L;
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    Map<String,Object> otpRow = Map.of(
        "id", otpId,
        "code_hash", hash("123456"),
        "attempts", 0,
        "expires_at", Timestamp.from(Instant.now().plusSeconds(120)),
        "purpose", "STAFF_LOGIN");
    when(jdbc.queryForList(contains("from otp_challenge"), eq(mobile), eq("STAFF_LOGIN")))
        .thenReturn(List.of(otpRow));
    when(jdbc.queryForList("select id, active, last_login_at from staff_account where mobile_e164=?", mobile))
        .thenReturn(List.of());
    when(jdbc.queryForObject(
        contains("insert into staff_account(public_id,mobile_e164,display_name,role,active)"),
        eq(Long.class), any(UUID.class), eq(mobile), eq("Administrator")))
        .thenReturn(staffId);
    when(jdbc.queryForMap("select role, display_name from staff_account where id=? and active=true", staffId))
        .thenReturn(Map.of("role", "ADMIN", "display_name", "Administrator"));

    AuthContext context = auth.verifyOtp("7070107483", "123456", "STAFF_LOGIN", null, true);

    assertNotNull(context);
    assertEquals(staffId, context.staffId());
    assertEquals("ADMIN", context.role());
    verify(jdbc, never()).update(contains("update auth_session set revoked_at=now()"), eq(mobile));
    verify(jdbc, never()).update(contains("update user_account set role='PARENT', active=false"), eq(mobile));
    verify(jdbc).update(contains("insert into staff_permission_grant"), eq(staffId));
  }

  @Test
  void customerCanVerifyLoginWhenSameMobileAlsoHasStaffIdentity() throws Exception {
    String mobile = "+917070107483";
    long userId = 52L;
    Map<String,Object> otpRow = Map.of(
        "id", 71L,
        "code_hash", hash("123456"),
        "attempts", 0,
        "expires_at", Timestamp.from(Instant.now().plusSeconds(120)),
        "purpose", "LOGIN");
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");

    when(jdbc.queryForList(contains("from otp_challenge"), eq(mobile), eq("LOGIN")))
        .thenReturn(List.of(otpRow));
    when(jdbc.queryForObject(contains("select exists(select 1 from user_account"),
        eq(Boolean.class), eq(mobile))).thenReturn(true);
    when(jdbc.queryForObject("select id from user_account where mobile_e164=?", Long.class, mobile))
        .thenReturn(userId);
    when(jdbc.queryForMap("select role,display_name from user_account where id=? and active=true", userId))
        .thenReturn(Map.of("role", "PARENT", "display_name", "Existing customer"));

    AuthContext context = auth.verifyOtp("7070107483", "123456", "LOGIN", null);

    assertEquals(userId, context.userId());
    assertEquals(null, context.staffId());
    assertEquals("PARENT", context.role());
  }

  @Test
  void allowlistedBootstrapMobileIsRecognizedAsFirstAccessBeforeStaffRowExists() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    when(jdbc.queryForList("select last_login_at from staff_account where mobile_e164=?", "+917070107483"))
        .thenReturn(List.of());

    assertTrue(auth.isStaffFirstAccess("7070107483"));
  }

  @Test
  void activatedStaffAccountIsRecognizedAsReturningLogin() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    when(jdbc.queryForList("select last_login_at from staff_account where mobile_e164=?", "+917070107483"))
        .thenReturn(List.of(Map.of("last_login_at", Timestamp.from(Instant.now()))));

    assertFalse(auth.isStaffFirstAccess("7070107483"));
  }

  @Test
  void administratorGetsEveryCurrentPermissionFromCatalogNotStaleGrantRows() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    List<String> catalog = List.of("AUDIT_VIEW", "CONTENT_REVIEW", "CONTENT_PUBLISH", "STAFF_MANAGE");
    when(jdbc.queryForObject("select role from staff_account where id=?", String.class, 9L))
        .thenReturn("ADMIN");
    when(jdbc.queryForList(
        "select permission_key from staff_permission_catalog order by permission_key", String.class))
        .thenReturn(catalog);

    assertIterableEquals(catalog, auth.permissionsForStaff(9L));
  }

  @Test
  void suspendedEmployeeStillShowsConfiguredGrantsButAuthorizationRejectsUse() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "7070107483");
    List<String> grants = List.of("CONTENT_VIEW", "CONTENT_EDIT");
    when(jdbc.queryForObject("select role from staff_account where id=?", String.class, 12L))
        .thenReturn("CONTENT_AUTHOR");
    when(jdbc.queryForList(contains("from staff_permission_grant"), eq(String.class), eq(12L)))
        .thenReturn(grants);

    assertIterableEquals(grants, auth.permissionsForStaff(12L));
  }

  @Test
  void parentCannotOpenChildAsTheirLearningAccount() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");

    SecurityException error = assertThrows(SecurityException.class,
        () -> auth.selectStudent(52L, 81L));

    assertTrue(error.getMessage().contains("cannot enter a child's learning account"));
  }

  @Test
  void legacyPinLoginCannotBypassParentProvisionedCredentials() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");

    SecurityException error = assertThrows(SecurityException.class,
        () -> auth.loginStudent("00000000-0000-0000-0000-000000000081", "1234"));

    assertTrue(error.getMessage().contains("parent-created username and password"));
  }

  @Test
  void legacyParentChildSessionIsRevokedInsteadOfBecomingAStudentSession() throws Exception {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");
    String token = "legacy-parent-child-session";
    Map<String,Object> legacySession = new java.util.HashMap<>();
    legacySession.put("user_id", 52L);
    legacySession.put("student_id", 81L);
    legacySession.put("staff_id", null);
    legacySession.put("role", "PARENT");
    legacySession.put("display_name", "Parent");
    legacySession.put("student_name", "Child");
    legacySession.put("staff_role", null);
    legacySession.put("staff_name", null);
    when(jdbc.queryForList(contains("where s.token_hash=?"), eq(hash(token))))
        .thenReturn(List.of(legacySession));

    assertEquals(null, auth.current(token));

    verify(jdbc).update(contains("update auth_session set revoked_at=now()"), eq(hash(token)));
  }

  private static String hash(String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }
  @Test
  void existingParentWithoutPasswordCanStillChooseOtpLogin() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");
    when(jdbc.queryForObject(contains("select exists(select 1 from user_account"),
        eq(Boolean.class), eq("+917070107483"))).thenReturn(true);
    when(jdbc.queryForObject(contains("requested_at > now() -"), eq(Long.class),
        eq("+917070107483"), eq(60))).thenReturn(0L);
    when(jdbc.queryForObject(contains("requested_at > now()-interval '1 hour'"),
        eq(Long.class), eq("+917070107483"))).thenReturn(0L);

    assertEquals("123456", auth.requestOtp("7070107483", "LOGIN"));
  }

  @Test
  void parentCanSignInWithPasswordWithoutOtp() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");
    String passwordHash = new BCryptPasswordEncoder(12).encode("ParentPass!2026");
    when(jdbc.queryForList(contains("select id,password_hash from user_account"),
        eq("+917070107483"))).thenReturn(List.of(Map.of("id", 52L, "password_hash", passwordHash)));
    when(jdbc.queryForMap("select role,display_name from user_account where id=? and active=true", 52L))
        .thenReturn(Map.of("role", "PARENT", "display_name", "Existing Parent"));

    AuthContext context = auth.loginParent("7070107483", "ParentPass!2026");

    assertEquals(52L, context.userId());
    assertEquals("PARENT", context.role());
    assertEquals("Existing Parent", context.displayName());
  }

  @Test
  void parentWithoutPasswordGetsRecoveryMessageRatherThanBeingLockedOut() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");
    when(jdbc.queryForList(contains("select id,password_hash from user_account"),
        eq("+917070107483"))).thenReturn(List.of(Map.of("id", 52L)));

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> auth.loginParent("7070107483", "some-password"));

    assertTrue(error.getMessage().contains("Forgot password"));
  }

  @Test
  void studentPasswordLoginOpensOnlyTheMatchedChildAccount() {
    AuthService auth = new AuthService(jdbc, true, 168, false, 60, "");
    String passwordHash = new BCryptPasswordEncoder(12).encode("StudentPass!2026");
    when(jdbc.queryForList(contains("from user_account u"), eq("+917070107483"), eq("aarav07")))
        .thenReturn(List.of(Map.of("student_id", 81L, "password_hash", passwordHash)));
    when(jdbc.queryForObject("select display_name from student where id=?", String.class, 81L))
        .thenReturn("Aarav");

    AuthContext context = auth.loginStudentByParent("7070107483", "Aarav07", "StudentPass!2026");

    assertEquals(null, context.userId());
    assertEquals(81L, context.studentId());
    assertEquals("STUDENT", context.role());
    assertEquals("Aarav", context.displayName());
  }

}
