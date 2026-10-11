package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class StudentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private StudentController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireStudent(student)).thenReturn(student);
    controller = new StudentController(jdbc, authorization);
  }

  @Test
  void persistsEnglishLanguagePreferenceForAuthenticatedStudent() {
    when(jdbc.update("update student set language=? where id=? and active=true", "en", 7L)).thenReturn(1);

    Map<String,Object> result = controller.updateMyLanguage(Map.of("language", "en"), student);

    assertEquals("en", result.get("language"));
    verify(jdbc).update("update student set language=? where id=? and active=true", "en", 7L);
  }

  @Test
  void persistsHindiLanguagePreferenceForAuthenticatedStudent() {
    when(jdbc.update("update student set language=? where id=? and active=true", "hi", 7L)).thenReturn(1);

    Map<String,Object> result = controller.updateMyLanguage(Map.of("language", "hi"), student);

    assertEquals("hi", result.get("language"));
    verify(jdbc).update("update student set language=? where id=? and active=true", "hi", 7L);
  }

  @Test
  void rejectsUnsupportedLanguageWithoutChangingStudentRecord() {
    assertThrows(IllegalArgumentException.class,
        () -> controller.updateMyLanguage(Map.of("language", "fr"), student));
    verify(jdbc, never()).update(
        eq("update student set language=? where id=? and active=true"), eq("fr"), eq(7L));
  }
}
