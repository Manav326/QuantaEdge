package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HrReportingScopeServiceTest {
  @Mock JdbcTemplate jdbc;
  @Mock AuthorizationService authorization;

  @Test void managerScopeHidesEmployeesOutsideReportingTree(){
    AuthContext manager=new AuthContext(100L,null,77L,"HR_MANAGER","Manager");
    when(authorization.hasPermission(manager,"HR_MANAGER_TEAM_VIEW")).thenReturn(true);
    when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(77L),eq(999L))).thenReturn(false);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
      ()->new HrReportingScopeService(jdbc,authorization).requireEmployeeAccess(manager,999L));
    assertEquals(HttpStatus.NOT_FOUND.value(),error.getStatusCode().value());
    verify(authorization).hasPermission(manager,"HR_MANAGER_TEAM_VIEW");
  }

  @Test void adminsAreNotRestrictedByManagerScopePermission(){
    AuthContext admin=new AuthContext(100L,null,77L,"ADMIN","Admin");
    HrReportingScopeService scope=new HrReportingScopeService(jdbc,authorization);
    scope.requireEmployeeAccess(admin,999L);
    verifyNoInteractions(jdbc,authorization);
  }
}
