package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class HrmsAccessControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthService auth;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService audit;

  private HrmsAccessController controller;
  private final AuthContext accessManager =
      new AuthContext(100L, null, 999L, "HR_MANAGER", "HR manager");

  @BeforeEach
  void setUp() {
    controller = new HrmsAccessController(jdbc, auth, authorization, audit);
  }

  @Test
  void editingEmployeeRolesOnlyReplacesPermissionsOwnedByTheHrmsAccessPicker() {
    when(authorization.requirePermission(accessManager, "HR_ACCESS_MANAGE"))
        .thenReturn(accessManager);
    when(jdbc.queryForList(contains("from hr_employee where id=? for update"), (Object) any()))
        .thenReturn(List.of(Map.of(
            "id", 55L,
            "display_name", "Employee Example",
            "employment_status", "ACTIVE",
            "linked_staff_account_id", 77L,
            "hrms_access_enabled", true)));
    when(jdbc.queryForList(contains("select id, username, active from staff_account where id=?"), (Object) any()))
        .thenReturn(List.of(Map.of(
            "id", 77L,
            "username", "employee.example",
            "active", true)));
    when(jdbc.queryForObject(contains("select exists"), eq(Boolean.class), eq("HR_EMPLOYEE_VIEW")))
        .thenReturn(true);

    Map<String, Object> responseRow = new HashMap<>(Map.of(
        "employee_id", 55L,
        "employee_number", "EMP-55",
        "display_name", "Employee Example",
        "employment_status", "ACTIVE",
        "hrms_access_enabled", true,
        "staff_id", 77L,
        "username", "employee.example",
        "identity_active", true,
        "identity_role", "EMPLOYEE"));
    when(jdbc.queryForList(contains("where e.id=?"), (Object) any()))
        .thenReturn(List.of(responseRow));
    when(jdbc.queryForList(contains("from staff_additional_role"), eq(String.class), eq(77L)))
        .thenReturn(List.of("EMPLOYEE"));
    when(jdbc.queryForList(contains("select permission_key from staff_permission_grant"), eq(String.class), eq(77L)))
        .thenReturn(List.of("HR_EMPLOYEE_VIEW"));
    when(auth.permissionsForStaff(77L, "HRMS"))
        .thenReturn(List.of("HR_EMPLOYEE_VIEW", "QE_FINANCE_PAYOUT_MANAGE", "QE_PLATFORM_OWNER"));

    Map<String, Object> result = controller.updateRoles(55L, Map.of(
        "roles", List.of("EMPLOYEE"),
        "permissions", List.of("HR_EMPLOYEE_VIEW")), accessManager);

    assertEquals(List.of("HR_EMPLOYEE_VIEW"), result.get("assigned_permissions"));
    assertTrue(((List<?>) result.get("permissions")).contains("QE_FINANCE_PAYOUT_MANAGE"));
    assertTrue(((List<?>) result.get("permissions")).contains("QE_PLATFORM_OWNER"));
    verify(jdbc).update(argThat(sql ->
        sql.contains("delete from staff_permission_grant")
            && sql.contains("workspace_key='HRMS'")
            && sql.contains("permission_key like")
            && sql.contains("HR_SELF_SERVICE_VIEW")), eq(77L));
    verify(jdbc).queryForList(argThat(sql ->
        sql.contains("select permission_key from staff_permission_grant")
            && sql.contains("workspace_key='HRMS'")
            && sql.contains("permission_key like")
            && sql.contains("HR_SELF_SERVICE_VIEW")), eq(String.class), eq(77L));
  }
}
