package com.quantaedge.api;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1")
public class LearningClassController {
  private static final Set<String> PROVIDERS =
      Set.of("CUSTOM", "GOOGLE_MEET", "ZOOM", "MICROSOFT_TEAMS", "OTHER");
  private static final Set<String> SESSION_STATUSES =
      Set.of("SCHEDULED", "LIVE", "COMPLETED", "CANCELLED");
  private static final Set<String> RECORDING_STATUSES =
      Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final PrivateMediaStorageService mediaStorage;

  public LearningClassController(JdbcTemplate jdbc, AuthorizationService authorization,
      StaffAuditService staffAudit, PrivateMediaStorageService mediaStorage) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.mediaStorage = mediaStorage;
  }

  // A safe staff picker for live-class host assignment; no login/contact secrets are returned.
  @GetMapping("/admin/class-staff")
  public List<Map<String, Object>> listClassStaff(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireClassView(context);
    return jdbc.queryForList("""
      select id as staff_id,display_name,role
      from staff_account where active=true
      order by case when role='ADMIN' then 0 else 1 end,lower(display_name),id
      """);
  }

  // ---------------- Staff: schedule and manage live classes ----------------

  @GetMapping("/admin/live-classes")
  public List<Map<String, Object>> adminLiveClasses(
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireClassView(context);
    String classFilter = safeText(classCode, 30);
    String subjectFilter = safeText(subjectCode, 40);
    String statusFilter = safeText(status, 20).toUpperCase(Locale.ROOT);
    if (statusFilter.isEmpty()) statusFilter = "ALL";
    if (!Set.of("ALL", "SCHEDULED", "LIVE", "COMPLETED", "CANCELLED").contains(statusFilter)) {
      throw badRequest("Choose a valid live class status.");
    }
    return jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.starts_at,cs.ends_at,cs.status,
             cs.host_staff_id,host.display_name as host_staff_name,cs.created_at,cs.updated_at,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             (select count(*) from learning_class_attendance ca where ca.session_id=cs.id) as attendee_count,
             (select count(*) from recorded_class rc where rc.class_session_id=cs.id and rc.status='PUBLISHED') as published_recording_count
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      left join staff_account host on host.id=cs.host_staff_id
      where (?='' or c.code=?) and (?='' or s.code=?)
        and (?='ALL' or cs.status=?)
      order by cs.starts_at desc,cs.id desc
      limit 500
      """, classFilter, classFilter, subjectFilter, subjectFilter, statusFilter, statusFilter);
  }

  @PostMapping("/admin/live-classes")
  @Transactional
  public Map<String, Object> createLiveClass(
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireClassManage(context);
    String title = requiredText(body.get("title"), "Class title", 240);
    String description = safeText(body.get("description"), 1600);
    String classCode = requiredText(body.get("classCode"), "Class", 30);
    String subjectCode = requiredText(body.get("subjectCode"), "Subject", 40);
    String provider = safeText(body.getOrDefault("provider", "CUSTOM"), 32).toUpperCase(Locale.ROOT);
    if (!PROVIDERS.contains(provider)) throw badRequest("Choose a supported live class provider.");
    String joinUrl = validJoinUrl(body.get("joinUrl"));
    OffsetDateTime startsAt = parseDate(body.get("startsAt"), "Start time");
    OffsetDateTime endsAt = parseDate(body.get("endsAt"), "End time");
    if (!endsAt.isAfter(startsAt)) throw badRequest("End time must be after the start time.");
    if (endsAt.isAfter(startsAt.plusHours(8))) throw badRequest("A live class cannot be longer than 8 hours.");
    if (startsAt.isBefore(OffsetDateTime.now().minusHours(2))) {
      throw badRequest("For an earlier class, create the record and mark it completed after scheduling rules are reviewed.");
    }

    Long subjectId = findActiveSubject(classCode, subjectCode);
    if (subjectId == null) throw badRequest("Choose a valid active class and subject.");
    Long chapterId = optionalLong(body.get("chapterId"), "chapterId");
    if (chapterId != null) validateChapter(chapterId, subjectId, false);
    Long hostStaffId = optionalLong(body.get("hostStaffId"), "hostStaffId");
    if (hostStaffId != null) {
      Long hostCount = jdbc.queryForObject(
          "select count(*) from staff_account where id=? and active=true", Long.class, hostStaffId);
      if (hostCount == null || hostCount == 0) throw badRequest("Choose an active staff member as the class host.");
    }

    Long id = jdbc.queryForObject("""
      insert into learning_class_session(
        title,description,subject_id,chapter_id,provider,private_join_url,starts_at,ends_at,
        status,host_staff_id,created_by_staff_id
      ) values(?,?,?,?,?,?,?,?, 'SCHEDULED',?,?) returning id
      """, Long.class, title, nullable(description), subjectId, chapterId, provider, joinUrl,
        startsAt, endsAt, hostStaffId, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/live-classes/" + id + "/created",
        "Scheduled live class #" + id + " for class " + classCode + " / subject " + subjectCode + ".");
    return adminSessionById(id);
  }

  @PatchMapping("/admin/live-classes/{sessionId}/status")
  @Transactional
  public Map<String, Object> setLiveClassStatus(
      @PathVariable long sessionId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireClassManage(context);
    String status = requiredText(body.get("status"), "Status", 20).toUpperCase(Locale.ROOT);
    if (!SESSION_STATUSES.contains(status)) throw badRequest("Choose SCHEDULED, LIVE, COMPLETED, or CANCELLED.");
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id,status,title from learning_class_session where id=?", sessionId);
    if (rows.isEmpty()) throw notFound("Live class", sessionId);
    String previous = String.valueOf(rows.getFirst().get("status"));
    jdbc.update("update learning_class_session set status=?,updated_at=now() where id=?", status, sessionId);
    staffAudit.recordAction(context, "/api/v1/admin/live-classes/" + sessionId + "/status",
        "Changed live class status from " + previous + " to " + status + ".");
    return adminSessionById(sessionId);
  }

  // ---------------- Staff: upload and publish private recordings ----------------

  @GetMapping("/admin/recorded-classes")
  public List<Map<String, Object>> adminRecordedClasses(
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireClassView(context);
    String classFilter = safeText(classCode, 30);
    String subjectFilter = safeText(subjectCode, 40);
    String statusFilter = safeText(status, 20).toUpperCase(Locale.ROOT);
    if (statusFilter.isEmpty()) statusFilter = "ALL";
    if (!Set.of("ALL", "DRAFT", "PUBLISHED", "ARCHIVED").contains(statusFilter)) {
      throw badRequest("Choose a valid recording status.");
    }
    return jdbc.queryForList("""
      select rc.id as recorded_class_id,rc.title,rc.description,rc.original_filename,rc.media_type,
             rc.file_size_bytes,rc.sha256,rc.duration_seconds,rc.status,rc.class_session_id,
             rc.created_at,rc.updated_at,rc.published_at,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             cs.title as session_title
      from recorded_class rc
      join curriculum_subject s on s.id=rc.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=rc.chapter_id
      left join learning_class_session cs on cs.id=rc.class_session_id
      where (?='' or c.code=?) and (?='' or s.code=?)
        and (?='ALL' or rc.status=?)
      order by rc.created_at desc,rc.id desc
      limit 500
      """, classFilter, classFilter, subjectFilter, subjectFilter, statusFilter, statusFilter);
  }

  @PostMapping(value = "/admin/recorded-classes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String, Object> uploadRecordedClass(
      @RequestPart("file") MultipartFile file,
      @RequestParam("title") String suppliedTitle,
      @RequestParam("classCode") String classCode,
      @RequestParam("subjectCode") String subjectCode,
      @RequestParam(value = "description", required = false) String description,
      @RequestParam(value = "chapterId", required = false) Long requestedChapterId,
      @RequestParam(value = "classSessionId", required = false) Long classSessionId,
      @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireClassManage(context);
    String title = requiredText(suppliedTitle, "Recording title", 240);
    String desc = safeText(description, 1600);
    String normalizedClass = requiredText(classCode, "Class", 30);
    String normalizedSubject = requiredText(subjectCode, "Subject", 40);
    Long subjectId = findActiveSubject(normalizedClass, normalizedSubject);
    if (subjectId == null) throw badRequest("Choose a valid active class and subject.");
    if (requestedChapterId != null) validateChapter(requestedChapterId, subjectId, false);
    if (durationSeconds != null && (durationSeconds < 1 || durationSeconds > 86400)) {
      throw badRequest("Recording duration must be between 1 second and 24 hours.");
    }
    if (classSessionId != null) {
      List<Map<String, Object>> sessionRows = jdbc.queryForList("""
        select subject_id,chapter_id,status from learning_class_session where id=?
        """, classSessionId);
      if (sessionRows.isEmpty()) throw badRequest("The linked live class does not exist.");
      Map<String, Object> session = sessionRows.getFirst();
      if (((Number) session.get("subject_id")).longValue() != subjectId) {
        throw badRequest("The live class and recording must belong to the same subject.");
      }
      Object sessionChapter = session.get("chapter_id");
      if (requestedChapterId != null && sessionChapter != null
          && ((Number) sessionChapter).longValue() != requestedChapterId) {
        throw badRequest("The recording chapter must match the linked live class chapter.");
      }
    }

    PrivateMediaStorageService.StoredMedia stored = mediaStorage.store(file);
    try {
      Long id = jdbc.queryForObject("""
        insert into recorded_class(
          title,description,subject_id,chapter_id,class_session_id,storage_key,original_filename,
          media_type,file_size_bytes,sha256,duration_seconds,status,created_by_staff_id
        ) values(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?) returning id
        """, Long.class, title, nullable(desc), subjectId, requestedChapterId, classSessionId,
          stored.storageKey(), stored.originalFilename(), stored.mediaType(), stored.sizeBytes(),
          stored.sha256(), durationSeconds, context.staffId());
      staffAudit.recordAction(context, "/api/v1/admin/recorded-classes/" + id + "/uploaded",
          "Uploaded private class recording #" + id + " (" + stored.originalFilename() + ").");
      return adminRecordingById(id);
    } catch (RuntimeException ex) {
      mediaStorage.deleteQuietly(mediaStorage.resolve(stored.storageKey()));
      throw ex;
    }
  }

  @PatchMapping("/admin/recorded-classes/{recordingId}/status")
  @Transactional
  public Map<String, Object> setRecordedClassStatus(
      @PathVariable long recordingId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireClassManage(context);
    String status = requiredText(body.get("status"), "Status", 20).toUpperCase(Locale.ROOT);
    if (!RECORDING_STATUSES.contains(status)) throw badRequest("Choose DRAFT, PUBLISHED, or ARCHIVED.");
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select rc.id,rc.status,ch.active as chapter_active,ch.content_status
      from recorded_class rc left join curriculum_chapter ch on ch.id=rc.chapter_id
      where rc.id=?
      """, recordingId);
    if (rows.isEmpty()) throw notFound("Recorded class", recordingId);
    Map<String, Object> current = rows.getFirst();
    String previous = String.valueOf(current.get("status"));
    if ("PUBLISHED".equals(status) && current.get("chapter_active") != null
        && (!Boolean.TRUE.equals(current.get("chapter_active"))
            || !"PUBLISHED".equals(String.valueOf(current.get("content_status"))))) {
      throw badRequest("Publish the linked chapter before publishing its recording.");
    }
    jdbc.update("""
      update recorded_class set status=?,updated_at=now(),
        published_by_staff_id=case when ?='PUBLISHED' then ? else published_by_staff_id end,
        published_at=case when ?='PUBLISHED' then now() when ?='DRAFT' then null else published_at end
      where id=?
      """, status, status, context.staffId(), status, status, recordingId);
    staffAudit.recordAction(context, "/api/v1/admin/recorded-classes/" + recordingId + "/status",
        "Changed recorded class status from " + previous + " to " + status + ".");
    return adminRecordingById(recordingId);
  }

  // ---------------- Student: protected live class access and attendance ----------------

  @GetMapping("/learning/live-classes")
  public List<Map<String, Object>> studentLiveClasses(
      @RequestParam(required = false) String subjectCode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String subjectFilter = safeText(subjectCode, 40);
    return jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.starts_at,cs.ends_at,cs.status,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             host.display_name as host_staff_name,
             exists(select 1 from recorded_class rc where rc.class_session_id=cs.id and rc.status='PUBLISHED') as has_recording,
             coalesce(att.join_count,0) as join_count,coalesce(att.total_seconds,0) as attended_seconds,
             (att.active_since_at is not null) as currently_joined
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      left join staff_account host on host.id=cs.host_staff_id
      left join learning_class_attendance att on att.session_id=cs.id and att.student_id=st.id
      where cs.status<>'CANCELLED' and cs.ends_at>now()-interval '60 days'
        and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      order by case when cs.ends_at>now() then 0 else 1 end,cs.starts_at asc,cs.id desc
      limit 300
      """, context.studentId(), subjectFilter, subjectFilter);
  }

  @PostMapping("/learning/live-classes/{sessionId}/join")
  @Transactional
  public Map<String, Object> joinLiveClass(
      @PathVariable long sessionId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select cs.id,cs.private_join_url,cs.provider,cs.title,cs.status,cs.starts_at,cs.ends_at
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      where cs.id=? and cs.status in ('SCHEDULED','LIVE')
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      """, context.studentId(), sessionId);
    if (rows.isEmpty()) throw notFound("Live class", sessionId);
    Map<String, Object> session = rows.getFirst();
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime startsAt = toOffsetDateTime(session.get("starts_at"));
    OffsetDateTime endsAt = toOffsetDateTime(session.get("ends_at"));
    if (startsAt != null && startsAt.isAfter(now.plusMinutes(15))) {
      throw conflict("This live class has not opened yet.");
    }
    if (endsAt == null || !endsAt.isAfter(now)) throw conflict("This live class has ended.");
    jdbc.update("""
      insert into learning_class_attendance(
        session_id,student_id,first_joined_at,last_joined_at,active_since_at,join_count,total_seconds,updated_at
      ) values(?,?,now(),now(),now(),1,0,now())
      on conflict(session_id,student_id) do update set
        last_joined_at=case when learning_class_attendance.active_since_at is null then now()
                            else learning_class_attendance.last_joined_at end,
        active_since_at=coalesce(learning_class_attendance.active_since_at,now()),
        join_count=learning_class_attendance.join_count
          + case when learning_class_attendance.active_since_at is null then 1 else 0 end,
        updated_at=now()
      """, sessionId, context.studentId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionId", sessionId);
    result.put("title", session.get("title"));
    result.put("provider", session.get("provider"));
    result.put("joinUrl", session.get("private_join_url"));
    result.put("joined", true);
    result.put("attendanceRecorded", true);
    return result;
  }

  @PostMapping("/learning/live-classes/{sessionId}/leave")
  @Transactional
  public Map<String, Object> leaveLiveClass(
      @PathVariable long sessionId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    int updated = jdbc.update("""
      update learning_class_attendance set
        total_seconds=total_seconds+greatest(0,floor(extract(epoch from (now()-active_since_at)))::bigint),
        active_since_at=null,last_left_at=now(),updated_at=now()
      where session_id=? and student_id=? and active_since_at is not null
      """, sessionId, context.studentId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionId", sessionId);
    result.put("left", updated == 1);
    result.put("attendanceRecorded", true);
    return result;
  }

  // ---------------- Student: private recorded class library and byte-range streaming ----------------

  @GetMapping("/learning/recorded-classes")
  public List<Map<String, Object>> studentRecordedClasses(
      @RequestParam(required = false) String subjectCode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String subjectFilter = safeText(subjectCode, 40);
    return jdbc.queryForList("""
      select rc.id as recorded_class_id,rc.title,rc.description,rc.media_type,rc.file_size_bytes,
             rc.duration_seconds,rc.created_at,rc.published_at,s.code as subject_code,
             s.display_name as subject_name,c.code as class_code,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             cs.id as session_id,cs.title as session_title,cs.starts_at as session_starts_at,
             coalesce(progress.last_position_seconds,0) as last_position_seconds,
             coalesce(progress.watched_seconds,0) as watched_seconds,coalesce(progress.completed,false) as completed
      from recorded_class rc
      join curriculum_subject s on s.id=rc.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=rc.chapter_id
      left join learning_class_session cs on cs.id=rc.class_session_id
      left join student_recorded_class_progress progress
        on progress.recorded_class_id=rc.id and progress.student_id=st.id
      where rc.status='PUBLISHED' and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      order by coalesce(ch.teaching_order,ch.sort_order),rc.created_at desc,rc.id desc
      limit 500
      """, context.studentId(), subjectFilter, subjectFilter);
  }

  @GetMapping("/learning/recorded-classes/{recordingId}")
  public Map<String, Object> studentRecordedClass(
      @PathVariable long recordingId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Map<String, Object> recording = readableRecording(recordingId, context.studentId());
    recording.remove("storage_key");
    recording.remove("sha256");
    return recording;
  }

  @PutMapping("/learning/recorded-classes/{recordingId}/progress")
  @Transactional
  public Map<String, Object> saveRecordedProgress(
      @PathVariable long recordingId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Map<String, Object> recording = readableRecording(recordingId, context.studentId());
    int position = integer(body.get("positionSeconds"), "Playback position", 0, 86400);
    long maxSeconds = recording.get("duration_seconds") instanceof Number n ? n.longValue() : 86400;
    if (position > maxSeconds) throw badRequest("Playback position exceeds the recording duration.");
    long watched = longValue(body.getOrDefault("watchedSeconds", position), "Watched seconds");
    if (watched > maxSeconds) throw badRequest("Watched time exceeds the recording duration.");
    boolean completed = body.get("completed") instanceof Boolean value && value;
    if (maxSeconds > 0 && position >= maxSeconds - 10) completed = true;
    jdbc.update("""
      insert into student_recorded_class_progress(
        student_id,recorded_class_id,last_position_seconds,watched_seconds,completed,last_played_at,updated_at
      ) values(?,?,?,?,?,now(),now())
      on conflict(student_id,recorded_class_id) do update set
        last_position_seconds=excluded.last_position_seconds,
        watched_seconds=greatest(student_recorded_class_progress.watched_seconds,excluded.watched_seconds),
        completed=student_recorded_class_progress.completed or excluded.completed,
        last_played_at=now(),updated_at=now()
      """, context.studentId(), recordingId, position, watched, completed);
    return Map.of("recordingId", recordingId, "positionSeconds", position, "completed", completed);
  }

  @GetMapping("/learning/recorded-classes/{recordingId}/stream")
  public ResponseEntity<StreamingResponseBody> streamRecordedClass(
      @PathVariable long recordingId,
      @RequestHeader(value = "Range", required = false) String rangeHeader,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    Map<String, Object> recording = readableRecording(recordingId, context.studentId());
    String storageKey = String.valueOf(recording.get("storage_key"));
    Path file = mediaStorage.resolve(storageKey);
    if (!Files.isRegularFile(file)) throw notFound("Recorded media", recordingId);
    final long size;
    try {
      size = Files.size(file);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The recording is unavailable.", ex);
    }
    long start = 0;
    long end = Math.max(0, size - 1);
    boolean partial = rangeHeader != null && !rangeHeader.isBlank();
    if (partial) {
      long[] range = parseRange(rangeHeader, size);
      if (range == null) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .cacheControl(CacheControl.noStore())
            .build();
      }
      start = range[0];
      end = range[1];
    }
    long length = end - start + 1;
    final long byteStart = start;
    final long bytesToSend = length;
    StreamingResponseBody stream = output -> {
      try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
        input.seek(byteStart);
        byte[] buffer = new byte[64 * 1024];
        long remaining = bytesToSend;
        while (remaining > 0) {
          int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
          if (read < 0) break;
          output.write(buffer, 0, read);
          remaining -= read;
        }
        output.flush();
      }
    };
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(String.valueOf(recording.get("media_type"))));
    headers.setContentLength(length);
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.setCacheControl(CacheControl.noStore());
    headers.setPragma("no-cache");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("X-Robots-Tag", "noindex, noarchive");
    headers.setContentDisposition(ContentDisposition.inline()
        .filename(String.valueOf(recording.get("original_filename")), java.nio.charset.StandardCharsets.UTF_8).build());
    if (partial) headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
    return new ResponseEntity<>(stream, headers, partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
  }

  // ---------------- Access checks and shared helpers ----------------

  private Map<String, Object> readableRecording(long recordingId, long studentId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select rc.id as recorded_class_id,rc.title,rc.description,rc.storage_key,rc.original_filename,
             rc.media_type,rc.file_size_bytes,rc.sha256,rc.duration_seconds,rc.created_at,rc.published_at,
             s.code as subject_code,s.display_name as subject_name,c.code as class_code,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             cs.id as session_id,cs.title as session_title,cs.starts_at as session_starts_at,
             coalesce(progress.last_position_seconds,0) as last_position_seconds,
             coalesce(progress.watched_seconds,0) as watched_seconds,coalesce(progress.completed,false) as completed
      from recorded_class rc
      join curriculum_subject s on s.id=rc.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=rc.chapter_id
      left join learning_class_session cs on cs.id=rc.class_session_id
      left join student_recorded_class_progress progress
        on progress.recorded_class_id=rc.id and progress.student_id=st.id
      where rc.id=? and rc.status='PUBLISHED'
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      """, studentId, recordingId);
    if (rows.isEmpty()) throw notFound("Recorded class", recordingId);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Map<String, Object> adminSessionById(long sessionId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.starts_at,cs.ends_at,cs.status,
             cs.host_staff_id,host.display_name as host_staff_name,cs.created_at,cs.updated_at,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      left join staff_account host on host.id=cs.host_staff_id
      where cs.id=?
      """, sessionId);
    if (rows.isEmpty()) throw notFound("Live class", sessionId);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Map<String, Object> adminRecordingById(long recordingId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select rc.id as recorded_class_id,rc.title,rc.description,rc.original_filename,rc.media_type,
             rc.file_size_bytes,rc.sha256,rc.duration_seconds,rc.status,rc.class_session_id,
             rc.created_at,rc.updated_at,rc.published_at,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             cs.title as session_title
      from recorded_class rc
      join curriculum_subject s on s.id=rc.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=rc.chapter_id
      left join learning_class_session cs on cs.id=rc.class_session_id
      where rc.id=?
      """, recordingId);
    if (rows.isEmpty()) throw notFound("Recorded class", recordingId);
    return new LinkedHashMap<>(rows.getFirst());
  }

  private void requireClassView(AuthContext context) {
    context = authorization.requireAuth(context);
    if (!context.isEmployee()) throw new SecurityException("Staff access required.");
    if (!authorization.hasPermission(context, "CONTENT_VIEW")
        && !authorization.hasPermission(context, "CLASS_MANAGE")) {
      throw new SecurityException("Class view permission required.");
    }
  }

  private AuthContext requireClassManage(AuthContext context) {
    return authorization.requirePermission(context, "CLASS_MANAGE");
  }

  private Long findActiveSubject(String classCode, String subjectCode) {
    return jdbc.query("""
      select s.id from curriculum_subject s join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true
      """, rs -> rs.next() ? rs.getLong(1) : null, classCode, subjectCode);
  }

  private void validateChapter(long chapterId, long subjectId, boolean requirePublished) {
    String publishedClause = requirePublished ? " and content_status='PUBLISHED'" : "";
    Long count = jdbc.queryForObject("""
      select count(*) from curriculum_chapter
      where id=? and subject_id=? and active=true
      """ + publishedClause, Long.class, chapterId, subjectId);
    if (count == null || count == 0) throw badRequest("The selected active chapter does not belong to this subject.");
  }

  private String validJoinUrl(Object value) {
    String text = requiredText(value, "Meeting URL", 2000);
    try {
      URI uri = URI.create(text);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw badRequest("Use a valid HTTPS meeting URL without embedded credentials.");
      }
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (host.equals("localhost") || host.endsWith(".local") || host.equals("127.0.0.1")
          || host.equals("0.0.0.0") || host.equals("::1")) {
        throw badRequest("A local/private host cannot be used as a student meeting URL.");
      }
      return uri.toASCIIString();
    } catch (IllegalArgumentException ex) {
      throw badRequest("Enter a valid HTTPS meeting URL.");
    }
  }

  private String safeText(Object value, int max) {
    String text = value == null ? "" : String.valueOf(value).trim();
    return text.length() > max ? text.substring(0, max) : text;
  }

  private String requiredText(Object value, String label, int max) {
    String text = safeText(value, max);
    if (text.isBlank()) throw badRequest(label + " is required.");
    return text;
  }

  private String nullable(String text) {
    return text == null || text.isBlank() ? null : text;
  }

  private OffsetDateTime parseDate(Object value, String label) {
    String text = requiredText(value, label, 80);
    try {
      return OffsetDateTime.parse(text);
    } catch (RuntimeException ex) {
      throw badRequest(label + " must include a timezone, for example 2026-10-10T10:30:00+05:30.");
    }
  }

  private OffsetDateTime toOffsetDateTime(Object value) {
    if (value instanceof OffsetDateTime dateTime) return dateTime;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
    if (value instanceof java.time.Instant instant) return instant.atOffset(ZoneOffset.UTC);
    if (value == null) return null;
    try { return OffsetDateTime.parse(String.valueOf(value)); }
    catch (RuntimeException ex) { return null; }
  }

  private long[] parseRange(String header, long totalSize) {
    if (totalSize <= 0 || header == null || !header.startsWith("bytes=") || header.contains(",")) return null;
    String value = header.substring("bytes=".length()).trim();
    String[] parts = value.split("-", -1);
    if (parts.length != 2 || (parts[0].isBlank() && parts[1].isBlank())) return null;
    try {
      long start;
      long end;
      if (parts[0].isBlank()) {
        long suffixLength = Long.parseLong(parts[1]);
        if (suffixLength <= 0) return null;
        start = Math.max(0, totalSize - suffixLength);
        end = totalSize - 1;
      } else {
        start = Long.parseLong(parts[0]);
        end = parts[1].isBlank() ? totalSize - 1 : Long.parseLong(parts[1]);
      }
      if (start < 0 || start >= totalSize || end < start) return null;
      return new long[]{start, Math.min(end, totalSize - 1)};
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private int integer(Object value, String label, int min, int max) {
    try {
      int number = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
      if (number < min || number > max) throw badRequest(label + " must be between " + min + " and " + max + ".");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(label + " must be a valid number.");
    }
  }

  private long longValue(Object value, String label) {
    try {
      long number = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
      if (number < 0) throw badRequest(label + " cannot be negative.");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(label + " must be a valid number.");
    }
  }

  private Long optionalLong(Object value, String label) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    try {
      long number = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
      if (number <= 0) throw badRequest(label + " must be positive.");
      return number;
    } catch (NumberFormatException ex) {
      throw badRequest(label + " must be a valid number.");
    }
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException notFound(String type, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id);
  }
}
