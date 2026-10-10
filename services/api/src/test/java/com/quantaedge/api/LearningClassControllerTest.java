package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LearningClassControllerTest {
  @TempDir Path tempDirectory;
  @Mock private JdbcTemplate jdbc;
  @Mock private AuthorizationService authorization;
  @Mock private StaffAuditService staffAudit;
  @Mock private PrivateMediaStorageService mediaStorage;

  private final AuthContext student = new AuthContext(70L, 7L, "STUDENT", "Preview Student");
  private final AuthContext admin = new AuthContext(101L, null, 99L, "ADMIN", "Administrator");
  private LearningClassController controller;

  @BeforeEach
  void setUp() {
    controller = new LearningClassController(jdbc, authorization, staffAudit, mediaStorage);
  }

  @Test
  void liveJoinDoesNotRevealMeetingLinkBeforeTheJoinWindow() {
    OffsetDateTime starts = OffsetDateTime.now().plusHours(1);
    OffsetDateTime ends = starts.plusMinutes(45);
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select cs.id,cs.private_join_url"), eq(7L), eq(44L)))
        .thenReturn(List.of(Map.of(
            "id", 44L, "private_join_url", "https://meet.example.com/secret",
            "provider", "CUSTOM", "title", "Class 7 Fractions",
            "status", "SCHEDULED", "starts_at", starts, "ends_at", ends)));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.joinLiveClass(44L, student));

    assertEquals(409, error.getStatusCode().value());
    verify(jdbc, never()).update(contains("insert into learning_class_attendance"), any(), any());
    verifyNoInteractions(staffAudit, mediaStorage);
  }

  @Test
  void classManagerCannotSaveAnInsecureMeetingUrl() {
    when(authorization.requirePermission(admin, "CLASS_MANAGE")).thenReturn(admin);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.createLiveClass(Map.of(
            "title", "Revision class",
            "classCode", "7",
            "subjectCode", "maths",
            "provider", "CUSTOM",
            "joinUrl", "http://meet.example.com/private",
            "startsAt", OffsetDateTime.now().plusHours(1).toString(),
            "endsAt", OffsetDateTime.now().plusHours(2).toString()), admin));

    assertEquals(400, error.getStatusCode().value());
    verifyNoInteractions(jdbc, staffAudit, mediaStorage);
  }

  @Test
  void staffWithoutClassManageCannotRetrievePrivateMeetingLink() {
    AuthContext author = new AuthContext(100L, null, 42L, "CONTENT_AUTHOR", "Author");
    when(authorization.requirePermission(author, "CLASS_MANAGE"))
        .thenThrow(new SecurityException("Class management permission required"));

    assertThrows(SecurityException.class, () -> controller.revealLiveClassMeetingLink(44L, author));

    verifyNoInteractions(jdbc, staffAudit, mediaStorage);
  }

  @Test
  void completingASessionClosesOpenAttendanceClocks() {
    when(authorization.requirePermission(admin, "CLASS_MANAGE")).thenReturn(admin);
    when(jdbc.queryForList(contains("select id,status,title from learning_class_session"), eq(44L)))
        .thenReturn(List.of(Map.of("id", 44L, "status", "LIVE", "title", "Revision class")));
    when(jdbc.queryForList(contains("select cs.id as session_id"), eq(44L)))
        .thenReturn(List.of(Map.of("session_id", 44L, "title", "Revision class", "status", "COMPLETED")));

    Map<String, Object> result = controller.setLiveClassStatus(44L, Map.of("status", "COMPLETED"), admin);

    assertEquals("COMPLETED", result.get("status"));
    verify(jdbc).update(contains("update learning_class_attendance att set"), eq(44L));
    verify(staffAudit).recordAction(eq(admin), contains("/admin/live-classes/44/status"), anyString());
  }

  @Test
  void outOfScopeStudentCannotFetchPrivateRecordingBytes() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("where rc.id=? and rc.status='PUBLISHED'"), eq(7L), eq(88L)))
        .thenReturn(List.of());

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.streamRecordedClass(88L, null, student));

    assertEquals(404, error.getStatusCode().value());
    verifyNoInteractions(mediaStorage);
  }

  @Test
  void malformedRangeHeaderIsRejectedWithoutOpeningTheMediaFile() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("where rc.id=? and rc.status='PUBLISHED'"), eq(7L), eq(88L)))
        .thenReturn(List.of(Map.of(
            "recorded_class_id", 88L, "storage_key", "afe0cdee-218f-4668-a5b6-8e52a66bb718.mp4",
            "original_filename", "class.mp4", "media_type", "video/mp4",
            "file_size_bytes", 128L, "sha256", "0".repeat(64),
            "duration_seconds", 60, "status", "PUBLISHED")));
    Path mediaFile = tempDirectory.resolve("class.mp4");
    try {
      Files.write(mediaFile, "private video bytes for byte-range test".getBytes());
    } catch (java.io.IOException ex) {
      throw new AssertionError(ex);
    }
    when(mediaStorage.resolve("afe0cdee-218f-4668-a5b6-8e52a66bb718.mp4"))
        .thenReturn(mediaFile);

    var response = controller.streamRecordedClass(88L, "bytes=bad-range", student);

    assertEquals(416, response.getStatusCode().value());
  }
}
