package com.quantaedge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
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
  void scheduledClassesUseTheSameTwentyFourHourVisibilityRuleForItemsAndCount() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select cs.id as session_id"),
        eq(7L), eq(""), eq(""), eq(25), eq(0))).thenReturn(List.of());
    when(jdbc.queryForObject(contains("select count(*) from learning_class_session"),
        eq(Long.class), eq(7L), eq(""), eq(""))).thenReturn(0L);

    Map<String, Object> result = controller.studentLiveClasses(null, null, 25, 0, student);

    assertEquals(0L, result.get("total"));
    verify(jdbc).queryForList(
        contains("cs.status<>'SCHEDULED' or cs.starts_at<=now()+interval '24 hours'"),
        eq(7L), eq(""), eq(""), eq(25), eq(0));
    verify(jdbc).queryForObject(
        contains("cs.status<>'SCHEDULED' or cs.starts_at<=now()+interval '24 hours'"),
        eq(Long.class), eq(7L), eq(""), eq(""));
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
  void classManagerCannotSaveAnUntrustedYouTubeBroadcastUrl() {
    when(authorization.requirePermission(admin, "CLASS_MANAGE")).thenReturn(admin);
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> controller.createLiveClass(Map.of(
            "title", "Live science lesson", "classCode", "7", "subjectCode", "science",
            "provider", "YOUTUBE_LIVE", "broadcastUrl", "https://attacker.example/watch?v=abcdefghijk",
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
    when(jdbc.queryForList(contains("select cs.id,cs.status,cs.title,cs.provider"), eq(44L)))
        .thenReturn(List.of(Map.of("id", 44L, "status", "LIVE", "title", "Revision class",
            "provider", "CUSTOM", "provider_operation_status", "NOT_REQUIRED")));
    when(jdbc.queryForList(contains("select cs.id as session_id"), eq(44L)))
        .thenReturn(List.of(Map.of("session_id", 44L, "title", "Revision class", "status", "COMPLETED")));

    Map<String, Object> result = controller.setLiveClassStatus(44L, Map.of("status", "COMPLETED"), admin);

    assertEquals("COMPLETED", result.get("status"));
    verify(jdbc).update(contains("update learning_class_attendance att set"), eq(44L));
    verify(staffAudit).recordAction(eq(admin), contains("/admin/live-classes/44/status"), anyString());
  }

  @Test
  void unverifiedGoogleMeetLinkIsNeverReturnedToStaff() {
    when(authorization.requirePermission(admin, "CLASS_MANAGE")).thenReturn(admin);
    when(jdbc.queryForList(contains("select id,title,provider,private_join_url"), eq(44L)))
        .thenReturn(List.of(Map.of(
            "id", 44L, "title", "Revision class", "provider", "GOOGLE_MEET",
            "private_join_url", "https://meet.google.com/abc-defg-hij",
            "provider_operation_status", "FAILED",
            "provider_error_message", "Google permission denied")));

    Map<String, Object> result = controller.revealLiveClassMeetingLink(44L, admin);

    assertEquals("FAILED", result.get("integrationStatus"));
    assertEquals(null, result.get("joinUrl"));
  }

  @Test
  void studentCanJoinBeforeStartWhenAdminDisablesEarlyJoinGate() {
    when(authorization.requireStudent(student)).thenReturn(student);
    OffsetDateTime starts=OffsetDateTime.now().plusHours(3);
    when(jdbc.queryForList(contains("select cs.id,cs.private_join_url,cs.youtube_video_id"),eq(7L),eq(88L)))
        .thenReturn(List.of(Map.of(
            "id",88L,"private_join_url","https://meet.example.org/class/88",
            "provider","CUSTOM","title","Revision class","status","SCHEDULED",
            "starts_at",starts,"ends_at",starts.plusHours(1),"join_window_minutes",0)));
    Map<String,Object> result=controller.joinLiveClass(88L,student);
    assertEquals(true,result.get("joined"));
    verify(jdbc).update(contains("insert into learning_class_attendance"),eq(88L),eq(7L));
  }

  @Test
  void youtubeBroadcastJoinReturnsVideoIdAndRecordsAttendanceWithoutMeetingUrl() {
    when(authorization.requireStudent(student)).thenReturn(student);
    OffsetDateTime starts=OffsetDateTime.now().minusMinutes(1);
    Map<String,Object> youtubeSession=new java.util.HashMap<>();
    youtubeSession.put("id",88L);
    youtubeSession.put("private_join_url",null);
    youtubeSession.put("youtube_video_id","abcdefghijk");
    youtubeSession.put("provider","YOUTUBE_LIVE");
    youtubeSession.put("title","Live science");
    youtubeSession.put("status","LIVE");
    youtubeSession.put("starts_at",starts);
    youtubeSession.put("ends_at",starts.plusMinutes(59));
    youtubeSession.put("join_window_minutes",15);
    when(jdbc.queryForList(contains("select cs.id,cs.private_join_url,cs.youtube_video_id"),eq(7L),eq(88L)))
        .thenReturn(List.of(youtubeSession));
    Map<String,Object> result=controller.joinLiveClass(88L,student);
    assertEquals("YOUTUBE_LIVE",result.get("provider"));
    assertEquals("abcdefghijk",result.get("youtubeVideoId"));
    assertEquals(null,result.get("joinUrl"));
    assertEquals(true,result.get("joined"));
    assertEquals(true,result.get("attendanceRecorded"));
    verify(jdbc).update(contains("insert into learning_class_attendance"),eq(88L),eq(7L));
    verifyNoInteractions(staffAudit,mediaStorage);
  }

  @Test
  void studentJoinWindowUsesTheConfiguredMinutes() {
    when(authorization.requireStudent(student)).thenReturn(student);
    OffsetDateTime starts=OffsetDateTime.now().plusMinutes(45);
    when(jdbc.queryForList(contains("select cs.id,cs.private_join_url,cs.youtube_video_id"),eq(7L),eq(88L)))
        .thenReturn(List.of(Map.of(
            "id",88L,"private_join_url","https://meet.example.org/class/88",
            "provider","CUSTOM","title","Revision class","status","SCHEDULED",
            "starts_at",starts,"ends_at",starts.plusHours(1),"join_window_minutes",30)));
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.joinLiveClass(88L,student));
    assertEquals(409,error.getStatusCode().value());
    verify(jdbc,never()).update(contains("insert into learning_class_attendance"),eq(88L),eq(7L));
  }

  @Test
  void adminCannotSetJoinWindowOutsideAllowedRange() {
    when(authorization.requirePermission(admin,"CLASS_MANAGE")).thenReturn(admin);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,
        ()->controller.setLiveClassJoinWindow(88L,Map.of("joinWindowMinutes",1441),admin));
    assertEquals(400,error.getStatusCode().value());
    verifyNoInteractions(jdbc,staffAudit,mediaStorage);
  }

  @Test
  void identicalRecordingUploadRetryReturnsTheExistingDraftAndDeletesDuplicateBytes() {
    when(authorization.requirePermission(admin, "CLASS_MANAGE")).thenReturn(admin);
    org.mockito.Mockito.doReturn(3L).when(jdbc).query(
        contains("select s.id from curriculum_subject"),
        org.mockito.ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq("6"), eq("maths"));
    String storageKey="afe0cdee-218f-4668-a5b6-8e52a66bb718.mp4";
    String digest="a".repeat(64);
    PrivateMediaStorageService.StoredMedia stored=
        new PrivateMediaStorageService.StoredMedia(storageKey,"recording.mp4","video/mp4",64L,digest);
    when(mediaStorage.store(any(MultipartFile.class))).thenReturn(stored);
    Path duplicateBytes=tempDirectory.resolve(storageKey);
    when(mediaStorage.resolve(storageKey)).thenReturn(duplicateBytes);
    String key="admin-upload-key-123";
    String fingerprint=fingerprintForUpload("Test recording","",3L,null,null,null,
        "recording.mp4","video/mp4",64L,digest);
    when(jdbc.queryForList(contains("select id,request_fingerprint from recorded_class"),eq(99L),eq(key)))
        .thenReturn(List.of(Map.of("id",88L,"request_fingerprint",fingerprint)));
    when(jdbc.queryForList(contains("select rc.id as recorded_class_id"),eq(88L)))
        .thenReturn(List.of(Map.of("recorded_class_id",88L,"title","Test recording","status","DRAFT")));

    MockMultipartFile upload=new MockMultipartFile("file","recording.mp4","video/mp4",new byte[64]);
    Map<String,Object> result=controller.uploadRecordedClass(
        upload,"Test recording","6","maths",null,null,null,null,key,admin);

    assertEquals(88L,result.get("recorded_class_id"));
    assertEquals("DRAFT",result.get("status"));
    verify(mediaStorage).deleteQuietly(duplicateBytes);
    verifyNoInteractions(staffAudit);
  }

  @Test
  void reusingRecordingUploadKeyForDifferentContentConflictsAndCleansDuplicateFile() {
    when(authorization.requirePermission(admin, "CLASS_MANAGE")).thenReturn(admin);
    org.mockito.Mockito.doReturn(3L).when(jdbc).query(
        contains("select s.id from curriculum_subject"),
        org.mockito.ArgumentMatchers.<ResultSetExtractor<Long>>any(), eq("6"), eq("maths"));
    String storageKey="afe0cdee-218f-4668-a5b6-8e52a66bb718.mp4";
    PrivateMediaStorageService.StoredMedia stored=
        new PrivateMediaStorageService.StoredMedia(storageKey,"recording.mp4","video/mp4",64L,"a".repeat(64));
    when(mediaStorage.store(any(MultipartFile.class))).thenReturn(stored);
    Path duplicateBytes=tempDirectory.resolve(storageKey);
    when(mediaStorage.resolve(storageKey)).thenReturn(duplicateBytes);
    when(jdbc.queryForList(contains("select id,request_fingerprint from recorded_class"),eq(99L),eq("admin-upload-key-123")))
        .thenReturn(List.of(Map.of("id",88L,"request_fingerprint","0".repeat(64))));

    MockMultipartFile upload=new MockMultipartFile("file","recording.mp4","video/mp4",new byte[64]);
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.uploadRecordedClass(
        upload,"Test recording","6","maths",null,null,null,null,"admin-upload-key-123",admin));

    assertEquals(409,error.getStatusCode().value());
    verify(mediaStorage,times(2)).deleteQuietly(duplicateBytes);
    verifyNoInteractions(staffAudit);
  }

  private String fingerprintForUpload(String title,String description,Long subjectId,Long chapterId,
      Long sessionId,Integer durationSeconds,String filename,String mediaType,long size,String digest) {
    String canonical=String.join("\n",title,description,String.valueOf(subjectId),
        chapterId==null?"":String.valueOf(chapterId),sessionId==null?"":String.valueOf(sessionId),
        durationSeconds==null?"":String.valueOf(durationSeconds),filename,mediaType,String.valueOf(size),digest);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new AssertionError(ex);
    }
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
  void recordedListingCarriesPublishedDownloadReferenceOnlyWhenCatalogueAllowsIt() {
    when(authorization.requireStudent(student)).thenReturn(student);
    when(jdbc.queryForList(contains("select rc.id as recorded_class_id"), eq(7L), eq(""), eq(""), eq(25), eq(0)))
        .thenReturn(List.of(Map.of(
            "recorded_class_id", 88L, "title", "Fractions revision", "subject_name", "Mathematics",
            "media_type", "video/mp4", "file_size_bytes", 1024L, "sha256", "a".repeat(64),
            "download_resource_id", 501L, "download_assignment_id", 700L,
            "download_allowed", true, "download_sha256", "a".repeat(64))));
    when(jdbc.queryForObject(contains("select count(*) from recorded_class"), eq(Long.class),
        eq(7L), eq(""), eq(""))).thenReturn(1L);

    Map<String,Object> result = controller.studentRecordedClasses(null, null, 25, 0, student);

    assertEquals(1L, result.get("total"));
    Map<?,?> row = (Map<?,?>)((List<?>)result.get("items")).get(0);
    assertEquals(501L, row.get("download_resource_id"));
    assertEquals(700L, row.get("download_assignment_id"));
    assertEquals(true, row.get("download_allowed"));
    verify(jdbc).queryForList(contains("left join lateral"), eq(7L), eq(""), eq(""), eq(25), eq(0));
    verifyNoInteractions(staffAudit, mediaStorage);
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
