package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
