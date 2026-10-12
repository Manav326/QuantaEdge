package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HrLeaveBalanceMigrationTest {
  @Autowired JdbcTemplate jdbc;

  @Test void migrationCreatesAnEmptyLedgerAndDedicatedPermissions() {
    assertTrue(jdbc.queryForObject("select to_regclass('public.hr_leave_balance_transaction') is not null",Boolean.class));
    assertEquals(1,jdbc.queryForObject("select count(*) from flyway_schema_history where version='64' and success=true",Integer.class));
    assertEquals(0,jdbc.queryForObject("select count(*) from hr_leave_balance_transaction",Integer.class));
    assertEquals(2,jdbc.queryForObject("select count(*) from staff_permission_catalog where permission_key in ('HR_LEAVE_BALANCE_VIEW','HR_LEAVE_BALANCE_MANAGE')",Integer.class));
  }
}
