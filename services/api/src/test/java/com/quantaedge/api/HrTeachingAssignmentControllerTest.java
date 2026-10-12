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
class HrTeachingAssignmentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService audit;
  private HrTeachingAssignmentController controller;
  private final AuthContext hrManager=new AuthContext(100L,null,77L,"HR_MANAGER","HR manager");

  @BeforeEach void setUp(){controller=new HrTeachingAssignmentController(jdbc,authorization,audit);}

  @Test
  void assignmentDirectoryRequiresViewPermissionBeforeDatabaseAccess(){
    when(authorization.requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_VIEW"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class,()->controller.assignments(1L,null,"ALL",0,100,hrManager));

    verify(authorization).requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_VIEW");
    verifyNoInteractions(jdbc,audit);
  }

  @Test
  void academicPeriodCreationRequiresManagePermissionBeforeDatabaseAccess(){
    when(authorization.requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_MANAGE"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class,()->controller.createPeriod(Map.of(
        "organizationId",1,"periodCode","2027","name","Academic year 2027",
        "startDate","2027-04-01","endDate","2028-03-31"),hrManager));

    verify(authorization).requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_MANAGE");
    verifyNoInteractions(jdbc,audit);
  }

  @Test
  void academicPeriodRejectsEndBeforeStartBeforeInsert(){
    when(authorization.requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_MANAGE")).thenReturn(hrManager);
    when(jdbc.queryForObject("select exists(select 1 from hr_organization where id=? and active=true)",Boolean.class,1L))
        .thenReturn(true);

    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.createPeriod(Map.of(
        "organizationId",1,"periodCode","2027","name","Academic year 2027",
        "startDate","2027-04-01","endDate","2027-03-31"),hrManager));

    assertEquals(400,error.getStatusCode().value());
    verify(jdbc,never()).update(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Object[].class));
    verify(audit,never()).recordAction(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void assignmentProposalRequiresManagePermissionBeforeDatabaseAccess(){
    when(authorization.requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_MANAGE"))
        .thenThrow(new SecurityException("Permission required"));

    assertThrows(SecurityException.class,()->controller.createAssignment(Map.of(
        "employeeId",1,"subjectId",1,"academicPeriodId",1,
        "effectiveStart","2027-04-01","effectiveEnd","2027-06-30"),hrManager));

    verify(authorization).requirePermission(hrManager,"HR_ACADEMIC_ASSIGNMENT_MANAGE");
    verifyNoInteractions(jdbc,audit);
  }
}
