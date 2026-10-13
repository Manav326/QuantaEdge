package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TeachingEligibilityServiceTest {
  @Mock private JdbcTemplate jdbc;

  @Test void assignmentCannotStartBeforeJoiningDate() {
    TeachingEligibilityService service=new TeachingEligibilityService(jdbc);
    when(jdbc.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of(
        "id",8L,"employment_status","ACTIVE","joining_date",Date.valueOf("2026-03-01"),
        "exit_date",Date.valueOf("2027-12-31"))));
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.requireAssignmentEligible(
        8L,LocalDate.parse("2026-02-28"),LocalDate.parse("2026-06-30")));
    assertEquals(409,error.getStatusCode().value());
  }

  @Test void assignmentCannotExtendBeyondLastWorkingDate() {
    TeachingEligibilityService service=new TeachingEligibilityService(jdbc);
    when(jdbc.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of(
        "id",8L,"employment_status","ACTIVE","joining_date",Date.valueOf("2023-03-01"),
        "exit_date",Date.valueOf("2026-06-30"))));
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.requireAssignmentEligible(
        8L,LocalDate.parse("2026-04-01"),LocalDate.parse("2026-07-01")));
    assertEquals(409,error.getStatusCode().value());
  }

  @Test void sessionOnApprovedLeaveIsBlocked() {
    TeachingEligibilityService service=new TeachingEligibilityService(jdbc);
    when(jdbc.queryForList(anyString(),eq(8L))).thenReturn(List.of(Map.of(
        "id",8L,"employment_status","ON_LEAVE","joining_date",Date.valueOf("2023-03-01"),
        "exit_date",Date.valueOf("2027-06-30"))));
    when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(8L),eq(Date.valueOf("2026-10-20")),eq(Date.valueOf("2026-10-20"))))
        .thenReturn(true);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.requireSessionEligible(
        8L,LocalDate.parse("2026-10-20"),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-12-31")));
    assertEquals(409,error.getStatusCode().value());
  }
}
