package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
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
class HrExtendedWorkforceControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService audit;
  private HrExtendedWorkforceController controller;
  private final AuthContext manager=new AuthContext(100L,null,77L,"HR_MANAGER","HR manager");

  @BeforeEach void setUp(){controller=new HrExtendedWorkforceController(jdbc,authorization,audit);}

  @Test void recruitmentReadPermissionIsRequiredBeforeDatabaseAccess(){
    when(authorization.requirePermission(manager,"HR_RECRUITMENT_VIEW")).thenThrow(new SecurityException("Permission required"));
    assertThrows(SecurityException.class,()->controller.vacancies(1L,manager));
    verify(authorization).requirePermission(manager,"HR_RECRUITMENT_VIEW");
    verifyNoInteractions(jdbc,audit);
  }

  @Test void unsupportedVacancyStatusIsRejectedWithoutMutation(){
    when(authorization.requirePermission(manager,"HR_RECRUITMENT_MANAGE")).thenReturn(manager);
    when(jdbc.queryForList("select * from hr_vacancy where id=?",4L))
        .thenReturn(List.of(Map.of("id",4L,"organization_id",1L,"vacancy_status","OPEN")));
    ResponseStatusException ex=assertThrows(ResponseStatusException.class,
        ()->controller.vacancyStatus(4L,Map.of("status","MAGIC"),manager));
    assertEquals(HttpStatus.BAD_REQUEST.value(),ex.getStatusCode().value());
    verify(jdbc,never()).update(contains("update hr_vacancy"),any(Object[].class));
  }

  @Test void candidateConversionRequiresAnApprovedHiringDecision(){
    when(authorization.requirePermission(manager,"HR_RECRUITMENT_MANAGE")).thenReturn(manager);
    Map<String,Object> candidate=new HashMap<>();
    candidate.put("id",9L); candidate.put("organization_id",1L);
    candidate.put("hiring_decision","PENDING"); candidate.put("converted_employee_id",null);
    when(jdbc.queryForList("select * from hr_candidate where id=? for update",9L)).thenReturn(List.of(candidate));
    ResponseStatusException ex=assertThrows(ResponseStatusException.class,
        ()->controller.convertCandidate(9L,Map.of("joiningDate","2026-10-13","employmentTypeCode","FULL_TIME"),manager));
    assertEquals(HttpStatus.CONFLICT.value(),ex.getStatusCode().value());
    verify(jdbc,never()).queryForObject(contains("insert into hr_employee"),eq(Long.class),any(Object[].class));
  }

  @Test void futureLastWorkingDateCannotCompleteExitEarly(){
    when(authorization.requirePermission(manager,"HR_EXIT_MANAGE")).thenReturn(manager);
    Map<String,Object> exit=Map.of(
        "id",44L,"employee_id",31L,"organization_id",1L,"exit_status","READY_FOR_SETTLEMENT",
        "final_settlement_status","SETTLED","approved_last_working_date",java.sql.Date.valueOf("2099-12-31"));
    when(jdbc.queryForList(contains("from hr_employee_exit x join hr_employee e"),(Object)any()))
        .thenReturn(List.of(exit));

    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.exitStatus(44L,Map.of("status","COMPLETED"),manager));

    assertEquals(409,error.getStatusCode().value());
    verify(jdbc,never()).update(contains("update hr_employee set employment_status='SEPARATED'"),any(Object[].class));
  }

  @Test void invalidAssetReturnConditionCannotChangeAssignmentOrInventory(){
    when(authorization.requirePermission(manager,"HR_ASSET_MANAGE")).thenReturn(manager);
    Map<String,Object> assignment=new HashMap<>();
    assignment.put("id",3L); assignment.put("asset_id",2L); assignment.put("organization_id",1L); assignment.put("returned_at",null);
    when(jdbc.queryForList(contains("from hr_asset_assignment x join hr_asset t"),(Object)any())).thenReturn(List.of(assignment));
    ResponseStatusException ex=assertThrows(ResponseStatusException.class,
        ()->controller.returnAsset(3L,Map.of("condition","EXPLODED"),manager));
    assertEquals(HttpStatus.BAD_REQUEST.value(),ex.getStatusCode().value());
    verify(jdbc,never()).update(contains("update hr_asset_assignment"),any(Object[].class));
    verify(audit,never()).recordAction(any(),any(),any());
  }
}
