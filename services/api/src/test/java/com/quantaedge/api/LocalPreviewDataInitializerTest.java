package com.quantaedge.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LocalPreviewDataInitializerTest {
  @Mock private JdbcTemplate jdbc;

  @Test
  void initializerNeverDeletesOtherLocalPreviewStudents() {
    when(jdbc.queryForObject(contains("insert into student(public_id"), eq(Long.class),
        eq(LocalPreviewIdentity.PUBLIC_ID), eq(LocalPreviewIdentity.ENVIRONMENT)))
        .thenReturn(101L);
    when(jdbc.queryForObject(contains("select id from institution_tenant"), eq(Long.class)))
        .thenReturn(7L);

    new LocalPreviewDataInitializer(jdbc).run();

    verify(jdbc, never()).update(contains("delete from student"), any(Object[].class));
    verify(jdbc).queryForObject(contains("insert into student(public_id"), eq(Long.class),
        eq(LocalPreviewIdentity.PUBLIC_ID), eq(LocalPreviewIdentity.ENVIRONMENT));
  }
}
