package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
class HrPayrollControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService audit;
  private HrPayrollController controller;
  private final AuthContext payrollManager = new AuthContext(100L,null,77L,"HR_MANAGER","HR manager");

  @BeforeEach
  void setUp() { controller=new HrPayrollController(jdbc,authorization,audit); }

  @Test
  void payrollComponentListRequiresPayrollViewBeforeDatabaseAccess() {
    when(authorization.requirePermission(payrollManager,"HR_PAYROLL_VIEW"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class,()->controller.components(1L,false,payrollManager));

    verify(authorization).requirePermission(payrollManager,"HR_PAYROLL_VIEW");
    verifyNoInteractions(jdbc,audit);
  }

  @Test
  void payrollComponentCreationRequiresManagePermissionBeforeDatabaseAccess() {
    when(authorization.requirePermission(payrollManager,"HR_PAYROLL_MANAGE"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class,()->controller.createComponent(Map.of(
        "organizationId",1,"code","BASIC","name","Basic salary","componentType","EARNING"),payrollManager));

    verify(authorization).requirePermission(payrollManager,"HR_PAYROLL_MANAGE");
    verifyNoInteractions(jdbc,audit);
  }

  @Test
  void unsupportedComponentTypeIsRejectedWithoutInsert() {
    when(authorization.requirePermission(payrollManager,"HR_PAYROLL_MANAGE")).thenReturn(payrollManager);
    when(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,1L))
        .thenReturn(true);

    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.createComponent(Map.of(
        "organizationId",1,"code","BASIC","name","Basic salary","componentType","NET"),payrollManager));

    assertEquals(400,error.getStatusCode().value());
    verify(jdbc,never()).update(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
    verify(audit,never()).recordAction(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void payrollPeriodMustCoverWholeCalendarMonth() {
    when(authorization.requirePermission(payrollManager,"HR_PAYROLL_MANAGE")).thenReturn(payrollManager);
    when(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,1L))
        .thenReturn(true);

    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.createPeriod(Map.of(
        "organizationId",1,"periodName","January 2027","startDate","2027-01-02","endDate","2027-01-31"),payrollManager));

    assertEquals(400,error.getStatusCode().value());
    verify(jdbc,never()).update(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
    verify(audit,never()).recordAction(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
  }
  @Test
  void payrollCalculationReadsCurrencyFromEffectiveCompensationRows() {
    when(authorization.requirePermission(payrollManager,"HR_PAYROLL_MANAGE")).thenReturn(payrollManager);
    when(jdbc.queryForList("select * from hr_payroll_period where id=? for update",1L))
        .thenReturn(List.of(Map.of(
            "id",1L,"organization_id",1L,"start_date",Date.valueOf("2027-01-01"),
            "end_date",Date.valueOf("2027-01-31"),"status","OPEN")));

    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.calculate(1L,Map.of(),payrollManager));

    assertEquals(409,error.getStatusCode().value());
    verify(jdbc).queryForList(contains("select e.id employee_id,e.employee_number,e.display_name,x.currency"),any(Object[].class));
  }

  @Test
  void payrollSubmitterCannotApproveTheirOwnSubmittedRun() {
    when(authorization.requirePermission(payrollManager,"HR_PAYROLL_APPROVE")).thenReturn(payrollManager);
    when(jdbc.queryForList("select r.*,p.organization_id,p.period_name,p.start_date,p.end_date,p.status period_status from hr_payroll_run r join hr_payroll_period p on p.id=r.period_id where r.id=?",12L))
        .thenReturn(List.of(Map.of("id",12L,"period_id",4L,"organization_id",1L,"status","SUBMITTED","created_by_staff_id",99L,"submitted_by_staff_id",100L)));
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.approve(12L,Map.of("reason","Reviewed"),payrollManager));
    assertEquals(403,error.getStatusCode().value());
    verify(jdbc,never()).update(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
    verify(audit,never()).recordAction(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
  }

}
