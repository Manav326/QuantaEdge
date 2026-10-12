package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HrEmployeeControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService audit;

  private HrEmployeeController controller;
  private final AuthContext hrManager = new AuthContext(100L, null, 77L, "HR_MANAGER", "HR manager");

  @BeforeEach
  void setUp() {
    controller = new HrEmployeeController(jdbc, authorization, audit);
  }

  @Test
  void employeeDirectoryRequiresViewPermissionBeforeQueryingEmployeeData() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_VIEW"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class, () -> controller.employees(hrManager, "", false, 0, 50));

    verify(authorization).requirePermission(hrManager, "HR_EMPLOYEE_VIEW");
    verifyNoInteractions(jdbc, audit);
  }

  @Test
  void employeeHistoryRequiresViewPermissionBeforeQueryingHistory() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_VIEW"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class, () -> controller.employeeHistory(9L, hrManager));

    verify(authorization).requirePermission(hrManager, "HR_EMPLOYEE_VIEW");
    verifyNoInteractions(jdbc, audit);
  }

  @Test
  void employeeCreationRequiresManagePermissionBeforeAnyDatabaseAccess() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_MANAGE"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class,
        () -> controller.createEmployee(Map.of("displayName", "Teacher"), hrManager));

    verify(authorization).requirePermission(hrManager, "HR_EMPLOYEE_MANAGE");
    verifyNoInteractions(jdbc, audit);
  }

  @Test
  void separatedEmployeeCannotBeCreatedWithoutExitDate() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_MANAGE")).thenReturn(hrManager);
    when(jdbc.queryForObject("select id from hr_organization where active=true order by id limit 1", Long.class))
        .thenReturn(1L);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.createEmployee(Map.of(
            "displayName", "A Teacher",
            "employmentTypeCode", "FULL_TIME",
            "joiningDate", "2026-01-01",
            "employmentStatus", "SEPARATED"), hrManager));

    assertEquals(400, error.getStatusCode().value());
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(audit, never()).recordAction(any(), anyString(), anyString());
  }

  @Test
  void invalidExitDateBeforeJoiningDateIsRejectedBeforeWriting() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_MANAGE")).thenReturn(hrManager);
    when(jdbc.queryForObject("select id from hr_organization where active=true order by id limit 1", Long.class))
        .thenReturn(1L);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.createEmployee(Map.of(
            "displayName", "A Professor",
            "employmentTypeCode", "FULL_TIME",
            "joiningDate", "2026-04-10",
            "exitDate", "2026-04-09"), hrManager));

    assertEquals(400, error.getStatusCode().value());
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(audit, never()).recordAction(any(), anyString(), anyString());
  }

  @Test
  void employeeCannotReportToThemself() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_MANAGE")).thenReturn(hrManager);
    when(jdbc.queryForList(anyString(), eq(9L))).thenReturn(List.of(Map.of(
        "id", 9L,
        "organization_id", 1L,
        "joining_date", Date.valueOf("2025-01-01"),
        "employment_status", "ACTIVE",
        "display_name", "A Teacher",
        "employee_number", "QE-EMP-000009")));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.updateEmployee(9L, Map.of(
            "displayName", "A Teacher",
            "managerEmployeeId", 9L), hrManager));

    assertEquals(400, error.getStatusCode().value());
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(audit, never()).recordAction(any(), anyString(), anyString());
  }

  @Test
  void employeeDirectoryClampsPageSizeAndOffsetToSafeBounds() {
    when(authorization.requirePermission(hrManager, "HR_EMPLOYEE_VIEW")).thenReturn(hrManager);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);

    Map<String,Object> result = controller.employees(hrManager, "", false, -50, 5000);

    assertEquals(100, result.get("limit"));
    assertEquals(0, result.get("offset"));
    assertEquals(0L, result.get("total"));
  }
}
