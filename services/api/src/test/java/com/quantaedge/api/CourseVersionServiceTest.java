package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CourseVersionServiceTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void staleVersionTriggerReturnsConflictInsteadOfServerError() {
    CourseVersionService service=new CourseVersionService(jdbc);
    SQLException postgresError=new SQLException(
        "ERROR: COURSE_VERSION_SOURCE_STALE: fork from the latest course version, not a superseded version");
    when(jdbc.queryForMap(anyString(),any(Object[].class)))
        .thenThrow(new DataIntegrityViolationException("Course version rejected.",postgresError));

    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        () -> service.forkReleasedVersion(7L,11L,null,null));

    assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
    assertTrue(error.getReason().contains("latest source version"));
  }
}
