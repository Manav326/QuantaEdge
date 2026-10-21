package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AccountProfileControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;

  private final AuthContext learner = new AuthContext(null, 70L, null, "STUDENT", "Learner", "STUDENT");
  private AccountProfileController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireAuth(learner)).thenReturn(learner);
    controller = new AccountProfileController(jdbc, authorization);
  }

  @Test
  void passwordChangeRevokesAccountAndLinkedLearnerSessions() {
    when(jdbc.queryForList(contains("select u.id from student st"), eq(70L)))
        .thenReturn(List.of(Map.of("id", 90L)));
    when(jdbc.queryForList(contains("select password_hash from user_account"), eq(90L)))
        .thenReturn(List.of(Map.of("password_hash", "")));

    Map<String,Object> result = controller.updatePassword(Map.of("newPassword", "SafePass123!"), learner);

    assertTrue(Boolean.TRUE.equals(result.get("saved")));
    assertTrue(Boolean.TRUE.equals(result.get("reauthenticationRequired")));
    verify(jdbc).update(contains("user_id=? or student_id in"), eq(90L), eq(90L));
  }
}
