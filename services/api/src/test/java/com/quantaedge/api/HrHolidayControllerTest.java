package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class HrHolidayControllerTest {
  @Mock JdbcTemplate jdbc;
  @Mock AuthorizationService authorization;
  @Mock StaffAuditService audit;

  @Test void calendarImportRejectsNonGovernmentSourceBeforeInsertingDates(){
    AuthContext hr=new AuthContext(100L,null,77L,"HR","HR");
    when(authorization.requirePermission(hr,"HR_HOLIDAY_MANAGE")).thenReturn(hr);
    when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(1L))).thenReturn(true);
    HrHolidayController controller=new HrHolidayController(jdbc,authorization,audit);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.importOfficialCalendar(1,
      Map.of("calendarSource","STATE_GOVERNMENT","sourceUrl","https://example.com/holidays.pdf",
        "jurisdiction","Bihar","calendarYear",2026,"holidays",List.of(Map.of("holidayDate","2026-01-26","name","Republic Day"))),hr));
    assertEquals(HttpStatus.BAD_REQUEST.value(),error.getStatusCode().value());
    verify(jdbc,never()).update(anyString(),any(Object[].class));
    verify(audit,never()).recordAction(any(),anyString(),anyString());
  }
}
