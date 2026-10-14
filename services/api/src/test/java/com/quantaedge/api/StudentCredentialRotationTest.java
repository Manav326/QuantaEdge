package com.quantaedge.api;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class StudentCredentialRotationTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void parentCredentialResetRevokesStudentSessionsButNotParentSessions() {
    AuthService auth = new AuthService(jdbc, false, 4, false, 60, "");
    when(jdbc.queryForList(contains("select role from user_account"), eq(String.class), eq(9L)))
        .thenReturn(List.of("PARENT"));
    when(jdbc.queryForObject(contains("select exists(select 1 from guardian_student"),
        eq(Boolean.class), eq(9L), eq(22L))).thenReturn(true);
    when(jdbc.queryForMap(contains("select gs.consent_version,st.class_code"), eq(9L), eq(22L)))
        .thenReturn(Map.of("consent_version", "guardian-consent-v1", "class_code", "7"));

    auth.setChildCredentials(9L, 22L, "child22", "a-strong-student-password");

    verify(jdbc).update(contains("insert into student_login_credential"), eq(9L), eq(22L),
        eq("child22"), org.mockito.ArgumentMatchers.anyString());
    verify(jdbc).update("update auth_session set revoked_at=now() where student_id=? and revoked_at is null", 22L);
    org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).update(
        contains("where user_id=? and revoked_at is null"), eq(9L));
  }
}
