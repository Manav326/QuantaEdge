package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
class HrAttendanceCorrectionReviewControllerTest {
  @Mock JdbcTemplate jdbc;
  @Mock AuthorizationService authorization;
  @Mock StaffAuditService audit;
  HrAttendanceCorrectionReviewController controller;
  final AuthContext manager=new AuthContext(100L,null,77L,"HR_MANAGER","HR manager");

  @BeforeEach void setup(){controller=new HrAttendanceCorrectionReviewController(jdbc,authorization,audit);}

  @Test void reviewListRequiresDedicatedPermissionBeforeDatabase(){
    when(authorization.requirePermission(manager,"HR_ATTENDANCE_CORRECTION_REVIEW")).thenThrow(new SecurityException("denied"));
    assertThrows(SecurityException.class,()->controller.list("PENDING",20,0,manager));
    verifyNoInteractions(jdbc,audit);
  }

  @Test void unsupportedDecisionIsRejectedBeforeMutation(){
    when(authorization.requirePermission(manager,"HR_ATTENDANCE_CORRECTION_REVIEW")).thenReturn(manager);
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of(
      "id",9L,"attendance_record_id",12L,"employee_id",31L,"work_date",java.sql.Date.valueOf("2026-10-12"),
      "requested_status","PRESENT","request_status","PENDING","requested_by_staff_id",88L)));
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
      ()->controller.decide(9,Map.of("status","CANCELLED","reviewNote","Not valid"),manager));
    assertEquals(HttpStatus.BAD_REQUEST.value(),error.getStatusCode().value());
    verify(jdbc,never()).update(anyString(),any(Object[].class));
    verify(audit,never()).recordAction(any(),anyString(),anyString());
  }
}
