package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class StudentControllerTest {
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private LearningMetricsService learningMetrics;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private StudentController controller;

  @BeforeEach
  void setUp() {
    when(authorization.requireStudent(student)).thenReturn(student);
    controller = new StudentController(jdbc, authorization, learningMetrics);
  }

  @Test
  void savesPhotoForAuthenticatedStudentAndReturnsPhotoUrl() {
    String image = "data:image/jpeg;base64,aGVsbG8=";
    String sql = "update student set profile_image_data_url=?,profile_updated_at=now() where id=? and active=true";
    when(jdbc.update(sql, image, 7L)).thenReturn(1);

    Map<String,Object> result = controller.updateMyProfilePhoto(Map.of("profileImageDataUrl", image), student);

    assertEquals(true, result.get("saved"));
    assertTrue(String.valueOf(result.get("profile_image_url")).startsWith("/api/v1/students/me/profile-photo?v="));
    verify(jdbc).update(sql, image, 7L);
  }

  @Test
  void profileEndpointReturnsPhotoUrlInsteadOfStoredImagePayload() {
    String url = "/api/v1/students/me/profile-photo?v=123";
    when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("profile_image_url", url));

    Map<String,Object> result = controller.myProfile(student);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).queryForMap(sql.capture(), eq(7L));

    assertEquals(url, result.get("profile_image_url"));
    assertTrue(sql.getValue().contains("as profile_image_url"));
    assertFalse(sql.getValue().contains("profile_image_data_url,city,state"));
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
