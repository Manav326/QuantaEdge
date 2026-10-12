package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HrLeaveBalanceControllerTest {
  @Mock JdbcTemplate jdbc;
  @Mock AuthorizationService authorization;
  @Mock StaffAuditService audit;
  HrLeaveBalanceController controller;
  final AuthContext manager = new AuthContext(100L,null,77L,"HR_MANAGER","HR manager");

  @BeforeEach void setup() {
    controller = new HrLeaveBalanceController(jdbc,authorization,audit);
  }

  @Test void balanceViewChecksPermissionBeforeDatabase() {
    when(authorization.requirePermission(manager,"HR_LEAVE_BALANCE_VIEW")).thenThrow(new SecurityException("denied"));
    assertThrows(SecurityException.class, () -> controller.balances(1,null,manager));
    verifyNoInteractions(jdbc,audit);
  }

  @Test void ledgerViewChecksPermissionBeforeDatabase() {
    when(authorization.requirePermission(manager,"HR_LEAVE_BALANCE_VIEW")).thenThrow(new SecurityException("denied"));
    assertThrows(SecurityException.class, () -> controller.ledger(1,null,0,100,manager));
    verifyNoInteractions(jdbc,audit);
  }

  @Test void balanceGrantChecksPermissionBeforeDatabase() {
    when(authorization.requirePermission(manager,"HR_LEAVE_BALANCE_MANAGE")).thenThrow(new SecurityException("denied"));
    assertThrows(SecurityException.class, () -> controller.recordTransaction(Map.of(
      "employeeId",1,"leaveTypeCode","CASUAL","transactionType","GRANT",
      "quantityDays","2.0","effectiveDate","2026-10-13","reason","Approved annual grant"),manager));
    verifyNoInteractions(jdbc,audit);
  }

  @Test void manualConsumptionIsRejectedBeforeDatabase() {
    when(authorization.requirePermission(manager,"HR_LEAVE_BALANCE_MANAGE")).thenReturn(manager);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.recordTransaction(Map.of(
      "employeeId",1,"leaveTypeCode","CASUAL","transactionType","CONSUMPTION",
      "quantityDays","-1","effectiveDate","2026-10-13","reason","Leave taken"),manager));
    assertEquals(400,ex.getStatusCode().value());
    verifyNoInteractions(jdbc);
  }
}
