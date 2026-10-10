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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LearningClassControllerTest {
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
    when(mediaStorage.resolve("afe0cdee-218f-4668-a5b6-8e52a66bb718.mp4"))
        .thenReturn(java.nio.file.Path.of("/private/class.mp4"));
    // File is not present, so the controller denies the read before consuming any stream bytes.
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.streamRecordedClass(88L, "bytes=bad-range", student));

    assertEquals(404, error.getStatusCode().value());
  }
}
