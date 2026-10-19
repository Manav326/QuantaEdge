package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class InstitutionMembershipServiceTest {
  @Test
  void signupAuditExplicitlyTypesNullableActorUserId() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(11L))).thenReturn(true);
    when(jdbc.queryForObject(contains("insert into institution_membership("), eq(Long.class), any(Object[].class)))
        .thenReturn(300L);
    when(jdbc.queryForObject(contains("insert into institution_user_membership("), eq(Long.class), any(Object[].class)))
        .thenReturn(400L);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("from student where id=? for update")) {
        return List.of(Map.<String,Object>of(
            "id", 23L, "user_account_id", 77L, "active", true, "environment", "PRODUCTION"));
      }
      if (sql.contains("from user_account where id=? for update")) {
        return List.of(Map.<String,Object>of("id", 77L, "active", true));
      }
      if (sql.contains("from institution_membership")) return List.of();
      if (sql.contains("from institution_user_membership")) return List.of();
      return List.of();
    });

    new InstitutionMembershipService(jdbc)
        .ensureStudentMembership(23L, 11L, "PHONE_SIGNUP", null);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), eq(11L), eq(300L), eq(23L), eq("PHONE_SIGNUP"), isNull());
    assertTrue(sql.getValue().contains("jsonb_build_object('source',?,'actorUserId',?::bigint)"),
        "Nullable signup actor must have an explicit SQL type inside jsonb_build_object.");
  }
}
