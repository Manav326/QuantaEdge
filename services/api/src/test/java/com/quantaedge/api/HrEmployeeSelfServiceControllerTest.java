package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HrEmployeeSelfServiceControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private StaffAuditService audit;
  @Mock private AuthorizationService authorization;
  private HrEmployeeSelfServiceController controller;
  private final AuthContext employee = new AuthContext(100L,null,77L,"STAFF","Employee");

  @BeforeEach
  void setUp() {
    controller = new HrEmployeeSelfServiceController(jdbc,audit,authorization);
    when(authorization.requirePermission(employee, "HR_SELF_SERVICE_VIEW")).thenReturn(employee);
  }

  @Test
  void employeeWithoutSelfServicePermissionCannotReadOwnRecords() {
    when(authorization.requirePermission(employee, "HR_SELF_SERVICE_VIEW"))
        .thenThrow(new SecurityException("Permission required: HR_SELF_SERVICE_VIEW"));

    assertThrows(SecurityException.class, () -> controller.profile(employee));

    verifyNoInteractions(jdbc);
    verifyNoInteractions(audit);
  }

  @Test
  void unlinkedAccountCannotReadEmployeeProfile() {
    when(jdbc.queryForList(contains("linked_staff_account_id=?"),(Object)any())).thenReturn(List.of());

    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.profile(employee));

    assertEquals(HttpStatus.NOT_FOUND.value(),error.getStatusCode().value());
    verify(jdbc,never()).queryForMap(contains("select e.id,e.public_id"),(Object)any());
    verifyNoInteractions(audit);
  }

  @Test
  void profileUsesOnlyTheAuthenticatedStaffLink() {
    when(jdbc.queryForList(contains("linked_staff_account_id=?"),(Object)any()))
        .thenReturn(List.of(Map.of("id",31L)));
    when(jdbc.queryForMap(contains("select e.id,e.public_id"),(Object)any()))
        .thenReturn(Map.of("id",31L,"employee_number","QE-00031","display_name","Employee"));

    Map<String,Object> profile=controller.profile(employee);

    assertEquals(31L,profile.get("id"));
    verify(jdbc).queryForList(contains("linked_staff_account_id=?"),(Object)any());
    verify(jdbc).queryForMap(contains("where e.id=?"),(Object)any());
  }

  @Test
  void payslipsAreLoadedOnlyForTheAuthenticatedEmployee() {
    when(jdbc.queryForList(contains("linked_staff_account_id=?"),(Object)any()))
        .thenReturn(List.of(Map.of("id",31L)));
    when(jdbc.queryForList(contains("from hr_payslip where employee_id=?"),
        eq((Object)31L),eq((Object)20),eq((Object)0)))
        .thenReturn(List.of(Map.of("id",6L,"public_id","payslip-public-id","run_id",12L,"version_no",1,
            "snapshot","{\"employeeName\":\"Employee\",\"net\":100.00}","checksum","a".repeat(64))));

    Map<String,Object> result=controller.payslips(employee,20,0);
    @SuppressWarnings("unchecked")
    List<Map<String,Object>> items=(List<Map<String,Object>>)result.get("items");

    assertEquals("{\"employeeName\":\"Employee\",\"net\":100.00}",items.getFirst().get("snapshot"));
    verify(jdbc).queryForList(contains("from hr_payslip where employee_id=?"),
        eq((Object)31L),eq((Object)20),eq((Object)0));
  }

  @Test
  void leaveRequestRejectsInvalidDateRangeBeforeInserting() {
    when(jdbc.queryForList(contains("linked_staff_account_id=?"),(Object)any()))
        .thenReturn(List.of(Map.of("id",31L)));

    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.requestLeave(Map.of(
        "employeeId",999L,"leaveTypeCode","CASUAL","startDate","2027-01-10","endDate","2027-01-09","reason","Personal"),
        employee));

    assertEquals(HttpStatus.BAD_REQUEST.value(),error.getStatusCode().value());
    verify(jdbc,never()).queryForObject(contains("insert into hr_leave_request"),org.mockito.ArgumentMatchers.eq(Long.class),(Object)any());
    verifyNoInteractions(audit);
  }

  @Test
  void leaveRequestAlwaysTargetsTheAuthenticatedEmployee() {
    when(jdbc.queryForList(contains("linked_staff_account_id=?"),(Object)any()))
        .thenReturn(List.of(Map.of("id",31L)));
    when(jdbc.queryForMap(contains("select id,organization_id,employee_number from hr_employee where id=?"),(Object)any()))
        .thenReturn(Map.of("id",31L,"organization_id",1L,"employee_number","QE-00031"));
    when(jdbc.queryForObject(contains("select exists(select 1 from hr_leave_type"),org.mockito.ArgumentMatchers.eq(Boolean.class),org.mockito.ArgumentMatchers.eq("CASUAL")))
        .thenReturn(true);
    when(jdbc.queryForObject(contains("select exists(select 1 from hr_leave_request"),org.mockito.ArgumentMatchers.eq(Boolean.class),
        org.mockito.ArgumentMatchers.eq(31L),any(Date.class),any(Date.class))).thenReturn(false);
    when(jdbc.queryForObject("select working_days_per_week from hr_organization where id=?",Integer.class,1L)).thenReturn(5);
    when(jdbc.queryForObject(contains("select count(*)::numeric from generate_series"),org.mockito.ArgumentMatchers.eq(BigDecimal.class),
        any(Date.class),any(Date.class),org.mockito.ArgumentMatchers.eq(5),org.mockito.ArgumentMatchers.eq(1L))).thenReturn(new BigDecimal("1"));
    when(jdbc.queryForObject(contains("insert into hr_leave_request"),org.mockito.ArgumentMatchers.eq(Long.class),
        org.mockito.ArgumentMatchers.eq(31L),org.mockito.ArgumentMatchers.eq("CASUAL"),any(Date.class),any(Date.class),
        org.mockito.ArgumentMatchers.eq(new BigDecimal("1")),org.mockito.ArgumentMatchers.eq("Personal"),org.mockito.ArgumentMatchers.eq(77L)))
        .thenReturn(55L);
    when(jdbc.queryForMap(contains("from hr_leave_request r join hr_leave_type t"),org.mockito.ArgumentMatchers.eq(55L)))
        .thenReturn(Map.of("id",55L,"employee_id",31L,"request_status","PENDING"));

    Map<String,Object> result=controller.requestLeave(Map.of(
        "employeeId",999L,"leaveTypeCode","CASUAL","startDate","2027-01-11","endDate","2027-01-11","reason","Personal"),employee);

    assertEquals(31L,result.get("employee_id"));
    verify(jdbc).queryForObject(contains("insert into hr_leave_request"),org.mockito.ArgumentMatchers.eq(Long.class),
        org.mockito.ArgumentMatchers.eq(31L),org.mockito.ArgumentMatchers.eq("CASUAL"),any(Date.class),any(Date.class),
        org.mockito.ArgumentMatchers.eq(new BigDecimal("1")),org.mockito.ArgumentMatchers.eq("Personal"),org.mockito.ArgumentMatchers.eq(77L));
    verify(audit).recordAction(employee,"/api/v1/hr/self-service/leave/requests","Employee QE-00031 submitted a leave request.");
  }
}
