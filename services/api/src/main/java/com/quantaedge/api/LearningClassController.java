package com.quantaedge.api;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
      Set.of("CUSTOM", "GOOGLE_MEET", "ZOOM", "MICROSOFT_TEAMS", "OTHER", "YOUTUBE_LIVE");
  private static final Set<String> SESSION_STATUSES =
      Set.of("SCHEDULED", "LIVE", "COMPLETED", "CANCELLED");
  private static final Set<String> RECORDING_STATUSES =
      Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final PrivateMediaStorageService mediaStorage;
  private final GoogleMeetCalendarService googleMeet;
  private final TeachingEligibilityService teachingEligibility;

  @Autowired
  public LearningClassController(JdbcTemplate jdbc, AuthorizationService authorization,
      StaffAuditService staffAudit, PrivateMediaStorageService mediaStorage,
      GoogleMeetCalendarService googleMeet, TeachingEligibilityService teachingEligibility) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.mediaStorage = mediaStorage;
    this.googleMeet = googleMeet;
    this.teachingEligibility = teachingEligibility;
  }

  // Existing unit tests retain their constructors; the fallback fails closed without credentials.
  LearningClassController(JdbcTemplate jdbc, AuthorizationService authorization,
      StaffAuditService staffAudit, PrivateMediaStorageService mediaStorage,
      GoogleMeetCalendarService googleMeet) {
    this(jdbc, authorization, staffAudit, mediaStorage, googleMeet,new TeachingEligibilityService(jdbc));
  }

  LearningClassController(JdbcTemplate jdbc, AuthorizationService authorization,
      StaffAuditService staffAudit, PrivateMediaStorageService mediaStorage) {
    this(jdbc, authorization, staffAudit, mediaStorage, new GoogleMeetCalendarService(),new TeachingEligibilityService(jdbc));
  }

  // A safe staff picker for live-class host assignment; no login/contact secrets are returned.
  @GetMapping("/teaching/my-live-classes")
  public Map<String,Object> myTeachingLiveClasses(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireAuth(context);
    if(!context.isEmployee()||context.staffId()==null)throw new SecurityException("Linked employee access is required.");
    List<Map<String,Object>> items=jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.youtube_video_id,cs.starts_at,cs.ends_at,cs.status,cs.join_window_minutes,
        cs.provider_operation_status,cs.provider_error_message,cs.teaching_assignment_id,
        s.code as subject_code,s.display_name as subject_name,c.code as class_code,c.display_name as class_name,
        p.name as academic_period_name,
        (cs.status in ('SCHEDULED','LIVE')
          and (cs.join_window_minutes=0 or cs.starts_at<=now()+(cs.join_window_minutes * interval '1 minute'))
          and cs.ends_at>now() and (cs.provider='YOUTUBE_LIVE' or cs.private_join_url is not null)
          and (cs.provider<>'GOOGLE_MEET' or cs.provider_operation_status='CREATED')) as joinable
      from hr_employee e join hr_teaching_assignment a on a.employee_id=e.id
      join learning_class_session cs on cs.teaching_assignment_id=a.id
      join curriculum_subject s on s.id=cs.subject_id join curriculum_class c on c.id=s.class_id
      join hr_academic_period p on p.id=a.academic_period_id
      where e.linked_staff_account_id=? and e.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
        and e.joining_date <= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and (e.exit_date is null or e.exit_date >= (cs.starts_at at time zone 'Asia/Kolkata')::date)
        and not exists(select 1 from hr_leave_request lr where lr.employee_id=e.id
          and lr.request_status='APPROVED' and lr.start_date<=(cs.starts_at at time zone 'Asia/Kolkata')::date
          and lr.end_date>=(cs.starts_at at time zone 'Asia/Kolkata')::date)
        and a.status='APPROVED'
        and a.effective_start <= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and a.effective_end >= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and cs.status<>'CANCELLED' and cs.ends_at>now()-interval '60 days'
      order by cs.starts_at asc,cs.id desc limit 250
      """,context.staffId());
    return Map.of("items",items,"total",items.size());
  }

  @GetMapping("/teaching/live-classes/{sessionId}/meeting-link")
  public Map<String,Object> teacherLiveClassMeetingLink(
      @PathVariable long sessionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireAuth(context);
    if(!context.isEmployee()||context.staffId()==null)throw new SecurityException("Linked employee access is required.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select cs.id,cs.title,cs.provider,cs.private_join_url,cs.youtube_video_id,cs.starts_at,cs.ends_at,cs.status,cs.join_window_minutes,
        cs.provider_operation_status,cs.provider_error_message,a.employee_id,a.effective_start,a.effective_end
      from learning_class_session cs
      join hr_teaching_assignment a on a.id=cs.teaching_assignment_id and a.status='APPROVED'
      join hr_employee e on e.id=a.employee_id and e.linked_staff_account_id=?
      where cs.id=? and e.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
        and a.effective_start <= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and a.effective_end >= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and cs.status in ('SCHEDULED','LIVE') and cs.private_join_url is not null
        and (cs.provider<>'GOOGLE_MEET' or cs.provider_operation_status='CREATED')
      """,context.staffId(),sessionId);
    if(rows.isEmpty())throw notFound("Assigned live class",sessionId);
    Map<String,Object> row=rows.getFirst();
    OffsetDateTime classStart=toOffsetDateTime(row.get("starts_at"));
    if(classStart==null)throw conflict("The class schedule is incomplete. Ask an administrator to review it.");
    teachingEligibility.requireSessionEligible(((Number)row.get("employee_id")).longValue(),
        classStart.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate(),
        ((java.sql.Date)row.get("effective_start")).toLocalDate(),
        ((java.sql.Date)row.get("effective_end")).toLocalDate());
    OffsetDateTime now=OffsetDateTime.now();
    OffsetDateTime startsAt=toOffsetDateTime(row.get("starts_at")),endsAt=toOffsetDateTime(row.get("ends_at"));
    int joinWindowMinutes=row.get("join_window_minutes") instanceof Number n?n.intValue():15;
    if(startsAt!=null&&joinWindowMinutes>0&&startsAt.isAfter(now.plusMinutes(joinWindowMinutes)))
      throw conflict("The teacher join window opens "+joinWindowMinutes+" minutes before the scheduled start.");
    if(endsAt==null||!endsAt.isAfter(now))throw conflict("This class has ended.");
    staffAudit.recordAction(context,"/api/v1/teaching/live-classes/"+sessionId+"/meeting-link",
        "Retrieved the assigned teacher's private meeting link for class #"+sessionId+".");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("sessionId",sessionId);
    result.put("title",row.get("title"));
    result.put("provider",row.get("provider"));
    result.put("joinUrl",row.get("private_join_url"));
    return result;
  }

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

  @GetMapping("/admin/class-chapters")
  public List<Map<String, Object>> listClassChapters(
      @RequestParam String classCode,
      @RequestParam String subjectCode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireClassView(context);
    return jdbc.queryForList("""
      select ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             ch.content_status,ch.active
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true and ch.active=true
      order by coalesce(ch.teaching_order,ch.sort_order),ch.sort_order,ch.id
      """, requiredText(classCode,"Class",30),requiredText(subjectCode,"Subject",40));
  }

  @GetMapping("/admin/approved-teaching-assignments")
  public List<Map<String,Object>> approvedTeachingAssignments(
      @RequestParam String classCode, @RequestParam String subjectCode,
      @RequestParam(required=false) String onDate,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireClassManage(context);
    String cc=requiredText(classCode,"Class",30),sc=requiredText(subjectCode,"Subject",40);
    java.time.LocalDate date;
    try { date=onDate==null||onDate.isBlank()?java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")):java.time.LocalDate.parse(onDate); }
    catch(RuntimeException ex){throw badRequest("Assignment date must use YYYY-MM-DD format.");}
    return jdbc.queryForList("""
      select a.id as teaching_assignment_id,a.employee_id,e.display_name as employee_name,
        e.employee_number,e.linked_staff_account_id,a.subject_id,a.academic_period_id,p.name as period_name,
        a.effective_start,a.effective_end
      from hr_teaching_assignment a join hr_employee e on e.id=a.employee_id
      join hr_academic_period p on p.id=a.academic_period_id
      join curriculum_subject s on s.id=a.subject_id join curriculum_class c on c.id=s.class_id
      where a.status='APPROVED' and a.effective_start<=? and a.effective_end>=?
        and e.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
        and e.joining_date<=? and (e.exit_date is null or e.exit_date>=?)
        and not exists(select 1 from hr_leave_request lr where lr.employee_id=e.id
          and lr.request_status='APPROVED' and lr.start_date<=? and lr.end_date>=?)
        and c.code=? and s.code=? and c.active=true and s.active=true
      order by e.display_name,a.id
      """,java.sql.Date.valueOf(date),java.sql.Date.valueOf(date),
        java.sql.Date.valueOf(date),java.sql.Date.valueOf(date),java.sql.Date.valueOf(date),java.sql.Date.valueOf(date),cc,sc);
  }

  @GetMapping("/admin/live-classes")
  public Map<String,Object> adminLiveClasses(
      @RequestParam(required=false) String classCode,@RequestParam(required=false) String subjectCode,
      @RequestParam(required=false) String status,@RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireClassView(context);
    String classFilter=safeText(classCode,30),subjectFilter=safeText(subjectCode,40),statusFilter=safeText(status,20).toUpperCase(Locale.ROOT);
    if(statusFilter.isEmpty())statusFilter="ALL";
    if(!Set.of("ALL","SCHEDULED","LIVE","COMPLETED","CANCELLED").contains(statusFilter))throw badRequest("Choose a valid live class status.");
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.youtube_video_id,cs.starts_at,cs.ends_at,cs.status,cs.join_window_minutes,
             cs.host_staff_id,host.display_name as host_staff_name,cs.teaching_assignment_id,
             assigned.display_name as assigned_teacher_name,ap.name as academic_period_name,
             cs.provider_operation_status,cs.provider_error_code,cs.provider_error_message,cs.provider_event_id,cs.youtube_video_id,
             cs.created_at,cs.updated_at,
             c.code as class_code,c.display_name as class_name,s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             (select count(*) from learning_class_attendance ca where ca.session_id=cs.id) as attendee_count,
             (select count(*) from recorded_class rc where rc.class_session_id=cs.id and rc.status='PUBLISHED') as published_recording_count
      from learning_class_session cs join curriculum_subject s on s.id=cs.subject_id
      join curriculum_class c on c.id=s.class_id left join curriculum_chapter ch on ch.id=cs.chapter_id
      left join staff_account host on host.id=cs.host_staff_id
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee assigned on assigned.id=ta.employee_id
      left join hr_academic_period ap on ap.id=ta.academic_period_id
      where (?='' or c.code=?) and (?='' or s.code=?) and (?='ALL' or cs.status=?)
      order by cs.starts_at desc,cs.id desc limit ? offset ?
      """,classFilter,classFilter,subjectFilter,subjectFilter,statusFilter,statusFilter,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from learning_class_session cs join curriculum_subject s on s.id=cs.subject_id
      join curriculum_class c on c.id=s.class_id
      where (?='' or c.code=?) and (?='' or s.code=?) and (?='ALL' or cs.status=?)
      """,Long.class,classFilter,classFilter,subjectFilter,subjectFilter,statusFilter,statusFilter);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @PostMapping("/admin/live-classes")
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
    String youtubeVideoId = "YOUTUBE_LIVE".equals(provider) ? validYouTubeVideoId(body.get("broadcastUrl")) : null;
    String joinUrl = ("GOOGLE_MEET".equals(provider) || "YOUTUBE_LIVE".equals(provider)) ? null : validJoinUrl(body.get("joinUrl"));
    OffsetDateTime startsAt = parseDate(body.get("startsAt"), "Start time");
    OffsetDateTime endsAt = parseDate(body.get("endsAt"), "End time");
    int joinWindowMinutes = integer(body.getOrDefault("joinWindowMinutes", 15),
        "Join window minutes", 0, 1440);
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
    Long teachingAssignmentId = optionalLong(body.get("teachingAssignmentId"), "teachingAssignmentId");
    if (teachingAssignmentId == null) {
      throw badRequest("Live-class scheduling requires an approved teaching assignment for this class, subject, and date.");
    }
    Long assignedEmployeeId = null;
    if (teachingAssignmentId != null) {
      // CLASS_MANAGE authorizes consuming the approved teacher reference for timetable setup.
      List<Map<String,Object>> assignmentRows = jdbc.queryForList("""
        select a.id,a.employee_id,a.subject_id,a.effective_start,a.effective_end,a.status,
          e.linked_staff_account_id,e.employment_status
        from hr_teaching_assignment a join hr_employee e on e.id=a.employee_id where a.id=?
        """, teachingAssignmentId);
      if (assignmentRows.isEmpty()) throw badRequest("Choose a valid approved teaching assignment.");
      Map<String,Object> assignment = assignmentRows.getFirst();
      if (!"APPROVED".equals(assignment.get("status"))) throw badRequest("Live classes can only use approved teaching assignments.");
      assignedEmployeeId = ((Number)assignment.get("employee_id")).longValue();
      if (((Number)assignment.get("subject_id")).longValue() != subjectId) throw badRequest("The teaching assignment must match the selected class and subject.");
      java.time.LocalDate sessionDate = startsAt.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate();
      teachingEligibility.requireSessionEligible(assignedEmployeeId,sessionDate,
          ((java.sql.Date)assignment.get("effective_start")).toLocalDate(),
          ((java.sql.Date)assignment.get("effective_end")).toLocalDate());
      Long linkedStaffId = assignment.get("linked_staff_account_id") instanceof Number n ? n.longValue() : null;
      if (hostStaffId != null && (linkedStaffId == null || !linkedStaffId.equals(hostStaffId)))
        throw badRequest("The selected host must be the staff account explicitly linked to the assigned employee.");
      if (hostStaffId == null && linkedStaffId != null) hostStaffId = linkedStaffId;
    }
    if (hostStaffId != null) {
      Long hostCount = jdbc.queryForObject(
          "select count(*) from staff_account where id=? and active=true", Long.class, hostStaffId);
      if (hostCount == null || hostCount == 0) throw badRequest("Choose an active staff member as the class host.");
    }

    validateNoScheduleConflict(hostStaffId, assignedEmployeeId, startsAt, endsAt, null);
    String requestId = "GOOGLE_MEET".equals(provider) ? UUID.randomUUID().toString() : null;
    Long id = jdbc.queryForObject("""
      insert into learning_class_session(
        title,description,subject_id,chapter_id,teaching_assignment_id,provider,private_join_url,youtube_video_id,starts_at,ends_at,
        join_window_minutes,status,host_staff_id,created_by_staff_id,provider_operation_status,provider_conference_request_id
      ) values(?,?,?,?,?,?,?,?,?,?,?,'SCHEDULED',?,?,?,?) returning id
      """, Long.class, title, nullable(description), subjectId, chapterId, teachingAssignmentId, provider, joinUrl, youtubeVideoId,
        startsAt, endsAt, joinWindowMinutes, hostStaffId, context.staffId(),
        "GOOGLE_MEET".equals(provider) ? "PENDING" : "NOT_REQUIRED", requestId);

    // Save the local schedule before making a network call. If Google is unavailable, retry later
    // against the same persisted record instead of losing the user's schedule.
    if ("GOOGLE_MEET".equals(provider)) {
      try {
        GoogleMeetCalendarService.MeetingResult result = googleMeet.createOrGetMeeting(
            id, requestId, title, description, startsAt, endsAt);
        jdbc.update("""
          update learning_class_session set private_join_url=?,provider_event_id=?,
            provider_conference_request_id=?,provider_operation_status=?,provider_error_code=?,
            provider_error_message=?,provider_operation_attempts=provider_operation_attempts+1,
            provider_last_attempt_at=now(),updated_at=now() where id=?
          """, result.joinUrl(), result.eventId(), result.conferenceRequestId(), result.status(),
          result.errorCode(), shortMessage(result.errorMessage()), id);
      } catch (GoogleMeetCalendarService.GoogleMeetException ex) {
        jdbc.update("""
          update learning_class_session set provider_operation_status='FAILED',provider_error_code=?,
            provider_error_message=?,provider_operation_attempts=provider_operation_attempts+1,
            provider_last_attempt_at=now(),updated_at=now() where id=?
          """, ex.code(), shortMessage(ex.getMessage()), id);
      }
    }
    Map<String,Object> created = adminSessionById(id);
    staffAudit.recordAction(context, "/api/v1/admin/live-classes/" + id + "/created",
        "Scheduled live class #" + id + " for class " + classCode + " / subject " + subjectCode
            + ("GOOGLE_MEET".equals(provider) ? "; Google Meet=" + created.get("provider_operation_status") : "") + ".");
    return created;
  }

  @PutMapping("/admin/live-classes/{sessionId}/join-window")
  @Transactional
  public Map<String,Object> setLiveClassJoinWindow(
      @PathVariable long sessionId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireClassManage(context);
    int joinWindowMinutes=integer(body.get("joinWindowMinutes"),"Join window minutes",0,1440);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,status,join_window_minutes from learning_class_session where id=?
      """,sessionId);
    if(rows.isEmpty())throw notFound("Live class",sessionId);
    String status=String.valueOf(rows.getFirst().get("status"));
    if(!"SCHEDULED".equals(status))throw conflict("The join window can only be changed while a class is scheduled.");
    jdbc.update("""
      update learning_class_session set join_window_minutes=?,updated_at=now()
      where id=? and status='SCHEDULED'
      """,joinWindowMinutes,sessionId);
    staffAudit.recordAction(context,"/api/v1/admin/live-classes/"+sessionId+"/join-window",
      "Changed join window for live class #"+sessionId+" to "
        +(joinWindowMinutes==0?"unrestricted early access":joinWindowMinutes+" minutes before start")+".");
    return adminSessionById(sessionId);
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
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select cs.id,cs.status,cs.title,cs.provider,cs.provider_event_id,cs.provider_operation_status,
        cs.starts_at,cs.teaching_assignment_id,ta.employee_id,ta.status as assignment_status,ta.effective_start,ta.effective_end,
        e.employment_status
      from learning_class_session cs
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee e on e.id=ta.employee_id
      where cs.id=?
      """,sessionId);
    if (rows.isEmpty()) throw notFound("Live class", sessionId);
    Map<String,Object> current = rows.getFirst();
    String previous = String.valueOf(current.get("status"));
    String provider = String.valueOf(current.get("provider"));
    String integration = String.valueOf(current.get("provider_operation_status"));
    boolean allowed = switch (previous) {
      case "SCHEDULED" -> Set.of("SCHEDULED","LIVE","CANCELLED").contains(status);
      case "LIVE" -> Set.of("LIVE","COMPLETED","CANCELLED").contains(status);
      case "CANCELLED" -> Set.of("CANCELLED","SCHEDULED").contains(status);
      case "COMPLETED" -> "COMPLETED".equals(status);
      default -> false;
    };
    if (!allowed) throw conflict("The requested live-class status transition is not allowed.");
    if ("LIVE".equals(status) && "GOOGLE_MEET".equals(provider) && !"CREATED".equals(integration)) {
      throw conflict("This class cannot be marked live until Google Meet creation is verified. Resolve the integration status first.");
    }
    if ("LIVE".equals(status) && !(current.get("teaching_assignment_id") instanceof Number)) {
      throw conflict("A class without a canonical teaching assignment cannot go live. Link it to an approved effective assignment or create a new scheduled class.");
    }
    if ("LIVE".equals(status)) {
      if (!"APPROVED".equals(current.get("assignment_status"))) {
        throw conflict("The teaching assignment must be approved before the class can go live.");
      }
      OffsetDateTime scheduledStart=toOffsetDateTime(current.get("starts_at"));
      if(scheduledStart==null)throw conflict("The class schedule is incomplete. Review it before starting the class.");
      teachingEligibility.requireSessionEligible(((Number)current.get("employee_id")).longValue(),
          scheduledStart.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate(),
          ((java.sql.Date)current.get("effective_start")).toLocalDate(),
          ((java.sql.Date)current.get("effective_end")).toLocalDate());
    }
    if ("COMPLETED".equals(previous) && !"COMPLETED".equals(status)) {
      throw conflict("Completed live classes are terminal. Create a new class record instead.");
    }
    String providerErrorCode = null, providerErrorMessage = null;
    if ("CANCELLED".equals(status) && "GOOGLE_MEET".equals(provider)) {
      Object eventValue=current.get("provider_event_id");
      String eventId=eventValue==null||String.valueOf(eventValue).isBlank()
          ?googleMeet.eventIdForSession(sessionId):String.valueOf(eventValue);
      if (!eventId.isBlank()) {
        try { googleMeet.cancelMeeting(eventId); integration="CANCELLED"; }
        catch (GoogleMeetCalendarService.GoogleMeetException ex) {
          integration="FAILED";providerErrorCode=ex.code();providerErrorMessage=shortMessage(ex.getMessage());
        }
      } else integration="CANCELLED";
      jdbc.update("""
        update learning_class_session set status=?,provider_operation_status=?,provider_error_code=?,
          provider_error_message=?,updated_at=now() where id=?
        """,status,integration,providerErrorCode,providerErrorMessage,sessionId);
    } else {
      if ("GOOGLE_MEET".equals(provider) && "CANCELLED".equals(previous) && "SCHEDULED".equals(status)) {
        throw conflict("A cancelled Google Meet event cannot be restored. Create a new scheduled class.");
      }
      jdbc.update("update learning_class_session set status=?,updated_at=now() where id=?", status, sessionId);
    }
    if ("COMPLETED".equals(status)) {
      // Close open attendance clocks against the scheduled end time so an abandoned tab
      // cannot leave an active attendance session running indefinitely.
      jdbc.update("""
        update learning_class_attendance att set
          total_seconds=att.total_seconds
            + greatest(0,floor(extract(epoch from (least(now(),cs.ends_at)-att.active_since_at)))::bigint),
          active_since_at=null,last_left_at=now(),updated_at=now()
        from learning_class_session cs
        where att.session_id=cs.id and cs.id=? and att.active_since_at is not null
        """, sessionId);
    }
    staffAudit.recordAction(context, "/api/v1/admin/live-classes/" + sessionId + "/status",
        "Changed live class status from " + previous + " to " + status
            + ("GOOGLE_MEET".equals(provider) ? "; Meet integration=" + integration : "") + ".");
    return adminSessionById(sessionId);
  }

  @PutMapping("/admin/live-classes/{sessionId}/teacher-assignment")
  @Transactional
  public Map<String,Object> replaceScheduledClassTeacher(
      @PathVariable long sessionId,@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireClassManage(context);
    long assignmentId=positiveLong(body.get("teachingAssignmentId"),"Teaching assignment");
    List<Map<String,Object>> sessions=jdbc.queryForList("""
      select id,title,status,subject_id,starts_at,ends_at,teaching_assignment_id,host_staff_id
      from learning_class_session where id=?
      """,sessionId);
    if(sessions.isEmpty())throw notFound("Live class",sessionId);
    Map<String,Object> session=sessions.getFirst();
    if(!"SCHEDULED".equals(session.get("status")))throw conflict("Teacher replacement is only allowed before the class starts. Preserve the current live/past record and schedule a new class if replacement is needed.");
    OffsetDateTime startsAt=toOffsetDateTime(session.get("starts_at")),endsAt=toOffsetDateTime(session.get("ends_at"));
    java.time.LocalDate classDate=startsAt.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate();
    List<Map<String,Object>> assignments=jdbc.queryForList("""
      select a.id,a.employee_id,a.subject_id,a.academic_period_id,a.effective_start,a.effective_end,
        a.status,a.organization_id,e.display_name as employee_name,e.employee_number,
        e.employment_status,e.linked_staff_account_id,p.status as period_status,p.name as period_name
      from hr_teaching_assignment a join hr_employee e on e.id=a.employee_id
      join hr_academic_period p on p.id=a.academic_period_id
      where a.id=?
      """,assignmentId);
    if(assignments.isEmpty())throw notFound("Teaching assignment",assignmentId);
    Map<String,Object> assignment=assignments.getFirst();
    if(!"APPROVED".equals(assignment.get("status"))
        ||Set.of("CLOSED","CANCELLED").contains(String.valueOf(assignment.get("period_status")))) {
      throw conflict("Choose an approved assignment in an open academic period for this scheduled class.");
    }
    if(((Number)assignment.get("subject_id")).longValue()!=((Number)session.get("subject_id")).longValue()) {
      throw badRequest("The replacement assignment must match this class's subject.");
    }
    Long employeeId=((Number)assignment.get("employee_id")).longValue();
    teachingEligibility.requireSessionEligible(employeeId,classDate,
        ((java.sql.Date)assignment.get("effective_start")).toLocalDate(),
        ((java.sql.Date)assignment.get("effective_end")).toLocalDate());
    Long newHost=assignment.get("linked_staff_account_id") instanceof Number n?n.longValue():null;
    validateNoScheduleConflict(newHost,employeeId,startsAt,endsAt,sessionId);
    Object oldAssignment=session.get("teaching_assignment_id");
    Long changed=jdbc.queryForObject("""
      update learning_class_session set teaching_assignment_id=?,host_staff_id=?,updated_at=now()
      where id=? and status='SCHEDULED' returning id
      """,Long.class,assignmentId,newHost,sessionId);
    Map<String,Object> after=adminSessionById(changed);
    staffAudit.recordAction(context,"/api/v1/admin/live-classes/"+sessionId+"/teacher-assignment",
        "Changed scheduled class #"+sessionId+" teaching assignment from "
          +(oldAssignment==null?"unassigned":"#"+oldAssignment)+" to #"+assignmentId
          +" ("+assignment.get("employee_name")+", employee #"+assignment.get("employee_number")+").");
    return after;
  }

  @PutMapping("/admin/live-classes/{sessionId}/schedule")
  public Map<String,Object> rescheduleLiveClass(
      @PathVariable long sessionId, @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireClassManage(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select cs.id,cs.title,cs.description,cs.provider,cs.status,cs.teaching_assignment_id,
        cs.provider_event_id,cs.provider_conference_request_id,cs.provider_operation_status,
        cs.host_staff_id,ta.employee_id,ta.status as assignment_status,ta.effective_start,ta.effective_end,
        e.employment_status
      from learning_class_session cs left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee e on e.id=ta.employee_id
      where cs.id=?
      """,sessionId);
    if(rows.isEmpty())throw notFound("Live class",sessionId);
    Map<String,Object> row=rows.getFirst();
    if(!"SCHEDULED".equals(row.get("status")))throw conflict("Only scheduled classes can be rescheduled.");
    OffsetDateTime startsAt=parseDate(body.get("startsAt"),"Start time");
    OffsetDateTime endsAt=parseDate(body.get("endsAt"),"End time");
    if(!endsAt.isAfter(startsAt))throw badRequest("End time must be after the start time.");
    if(endsAt.isAfter(startsAt.plusHours(8)))throw badRequest("A live class cannot be longer than 8 hours.");
    if(startsAt.isBefore(OffsetDateTime.now().minusHours(2)))throw badRequest("Choose a future start time.");
    Long assignmentId=row.get("teaching_assignment_id") instanceof Number n?n.longValue():null;
    Long employeeId=row.get("employee_id") instanceof Number n?n.longValue():null;
    if(assignmentId==null)throw conflict("A scheduled class must be linked to an approved teaching assignment before it can be rescheduled.");
    {
      java.time.LocalDate localDate=startsAt.atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate();
      if(!"APPROVED".equals(row.get("assignment_status")))
        throw conflict("The class must keep an approved teaching assignment before rescheduling.");
      teachingEligibility.requireSessionEligible(employeeId,localDate,
          ((java.sql.Date)row.get("effective_start")).toLocalDate(),
          ((java.sql.Date)row.get("effective_end")).toLocalDate());
    }
    Long hostId=row.get("host_staff_id") instanceof Number n?n.longValue():null;
    validateNoScheduleConflict(hostId,employeeId,startsAt,endsAt,sessionId);
    String provider=String.valueOf(row.get("provider"));
    String operation=String.valueOf(row.get("provider_operation_status"));
    if("GOOGLE_MEET".equals(provider)) {
      if(!"CREATED".equals(operation)||row.get("provider_event_id")==null) {
        throw conflict("Resolve Google Meet creation first; rescheduling is disabled until the external meeting is verified.");
      }
      String eventId=String.valueOf(row.get("provider_event_id"));
      String requestId=row.get("provider_conference_request_id")==null?"":String.valueOf(row.get("provider_conference_request_id"));
      try {
        GoogleMeetCalendarService.MeetingResult result=googleMeet.rescheduleMeeting(eventId,requestId,
            String.valueOf(row.get("title")),row.get("description")==null?null:String.valueOf(row.get("description")),startsAt,endsAt);
        jdbc.update("""
          update learning_class_session set starts_at=?,ends_at=?,private_join_url=?,
            provider_conference_request_id=?,provider_operation_status=?,provider_error_code=?,
            provider_error_message=?,provider_operation_attempts=provider_operation_attempts+1,
            provider_last_attempt_at=now(),updated_at=now() where id=?
          """,startsAt,endsAt,result.joinUrl(),result.conferenceRequestId(),result.status(),
          result.errorCode(),shortMessage(result.errorMessage()),sessionId);
      } catch(GoogleMeetCalendarService.GoogleMeetException ex) {
        jdbc.update("""
          update learning_class_session set provider_error_code=?,provider_error_message=?,
            provider_last_attempt_at=now(),updated_at=now() where id=?
          """,ex.code(),shortMessage(ex.getMessage()),sessionId);
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Google Calendar did not confirm the reschedule. The QuantaEdge schedule was not changed. "+ex.getMessage());
      }
    } else {
      jdbc.update("update learning_class_session set starts_at=?,ends_at=?,updated_at=now() where id=?",startsAt,endsAt,sessionId);
    }
    staffAudit.recordAction(context,"/api/v1/admin/live-classes/"+sessionId+"/schedule",
        "Rescheduled live class #"+sessionId+" to "+startsAt+"–"+endsAt+".");
    return adminSessionById(sessionId);
  }

  @PostMapping("/admin/live-classes/{sessionId}/google-meet/retry")
  public Map<String,Object> retryGoogleMeet(
      @PathVariable long sessionId,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=requireClassManage(context);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,title,description,provider,status,starts_at,ends_at,provider_event_id,
        provider_conference_request_id,provider_operation_status
      from learning_class_session where id=?
      """,sessionId);
    if(rows.isEmpty())throw notFound("Live class",sessionId);
    Map<String,Object> row=rows.getFirst();
    if(!"GOOGLE_MEET".equals(row.get("provider")))throw badRequest("This class does not use managed Google Meet.");
    String status=String.valueOf(row.get("status"));
    if("COMPLETED".equals(status))throw conflict("A completed live class cannot be retried.");
    String eventId=row.get("provider_event_id")==null?"":String.valueOf(row.get("provider_event_id"));
    String requestId=row.get("provider_conference_request_id")==null?"":String.valueOf(row.get("provider_conference_request_id"));
    try {
      if("CANCELLED".equals(status)) {
        googleMeet.cancelMeeting(eventId.isBlank()?googleMeet.eventIdForSession(sessionId):eventId);
        jdbc.update("""
          update learning_class_session set provider_operation_status='CANCELLED',provider_error_code=null,
            provider_error_message=null,provider_operation_attempts=provider_operation_attempts+1,
            provider_last_attempt_at=now(),updated_at=now() where id=?
          """,sessionId);
      } else {
        OffsetDateTime startsAt=toOffsetDateTime(row.get("starts_at")),endsAt=toOffsetDateTime(row.get("ends_at"));
        GoogleMeetCalendarService.MeetingResult result=eventId.isBlank()
          ?googleMeet.createOrGetMeeting(sessionId,requestId,String.valueOf(row.get("title")),
              row.get("description")==null?null:String.valueOf(row.get("description")),startsAt,endsAt)
          :googleMeet.refreshOrRetryMeeting(eventId,requestId);
        jdbc.update("""
          update learning_class_session set private_join_url=?,provider_event_id=?,provider_conference_request_id=?,
            provider_operation_status=?,provider_error_code=?,provider_error_message=?,
            provider_operation_attempts=provider_operation_attempts+1,provider_last_attempt_at=now(),updated_at=now()
          where id=?
          """,result.joinUrl(),result.eventId(),result.conferenceRequestId(),result.status(),
          result.errorCode(),shortMessage(result.errorMessage()),sessionId);
      }
    } catch(GoogleMeetCalendarService.GoogleMeetException ex) {
      jdbc.update("""
        update learning_class_session set provider_operation_status='FAILED',provider_error_code=?,
          provider_error_message=?,provider_operation_attempts=provider_operation_attempts+1,
          provider_last_attempt_at=now(),updated_at=now() where id=?
        """,ex.code(),shortMessage(ex.getMessage()),sessionId);
    }
    staffAudit.recordAction(context,"/api/v1/admin/live-classes/"+sessionId+"/google-meet/retry",
        "Retried Google Meet lifecycle operation for live class #"+sessionId+".");
    return adminSessionById(sessionId);
  }

  @GetMapping("/admin/live-classes/{sessionId}/meeting-link")
  public Map<String, Object> revealLiveClassMeetingLink(
      @PathVariable long sessionId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireClassManage(context);
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select id,title,provider,private_join_url,provider_operation_status,provider_error_message from learning_class_session where id=?
      """, sessionId);
    if (rows.isEmpty()) throw notFound("Live class", sessionId);
    Map<String, Object> row = rows.getFirst();
    staffAudit.recordAction(context, "/api/v1/admin/live-classes/" + sessionId + "/meeting-link",
        "Retrieved the private meeting link for live class #" + sessionId + ".");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sessionId", sessionId);
    result.put("title", row.get("title"));
    result.put("provider", row.get("provider"));
    String integration = String.valueOf(row.get("provider_operation_status"));
    result.put("integrationStatus", integration);
    result.put("errorMessage", row.get("provider_error_message"));
    boolean verified = !"GOOGLE_MEET".equals(row.get("provider")) || "CREATED".equals(integration);
    result.put("joinUrl", verified ? row.get("private_join_url") : null);
    return result;
  }

  // ---------------- Staff: upload and publish private recordings ----------------

  @GetMapping("/admin/recorded-classes")
  public Map<String,Object> adminRecordedClasses(
      @RequestParam(required=false) String classCode,@RequestParam(required=false) String subjectCode,
      @RequestParam(required=false) String status,@RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requireClassView(context);
    String classFilter=safeText(classCode,30),subjectFilter=safeText(subjectCode,40),statusFilter=safeText(status,20).toUpperCase(Locale.ROOT);
    if(statusFilter.isEmpty())statusFilter="ALL";
    if(!Set.of("ALL","DRAFT","PUBLISHED","ARCHIVED").contains(statusFilter))throw badRequest("Choose a valid recording status.");
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select rc.id as recorded_class_id,rc.title,rc.description,rc.original_filename,rc.media_type,
             rc.file_size_bytes,rc.sha256,rc.duration_seconds,rc.status,rc.class_session_id,rc.created_at,rc.updated_at,rc.published_at,
             c.code as class_code,c.display_name as class_name,s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,cs.title as session_title
      from recorded_class rc join curriculum_subject s on s.id=rc.subject_id
      join curriculum_class c on c.id=s.class_id left join curriculum_chapter ch on ch.id=rc.chapter_id
      left join learning_class_session cs on cs.id=rc.class_session_id
      where (?='' or c.code=?) and (?='' or s.code=?) and (?='ALL' or rc.status=?)
      order by rc.created_at desc,rc.id desc limit ? offset ?
      """,classFilter,classFilter,subjectFilter,subjectFilter,statusFilter,statusFilter,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from recorded_class rc join curriculum_subject s on s.id=rc.subject_id
      join curriculum_class c on c.id=s.class_id
      where (?='' or c.code=?) and (?='' or s.code=?) and (?='ALL' or rc.status=?)
      """,Long.class,classFilter,classFilter,subjectFilter,subjectFilter,statusFilter,statusFilter);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
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
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
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

    String requestKey = normalizeIdempotencyKey(idempotencyKey);
    PrivateMediaStorageService.StoredMedia stored = mediaStorage.store(file);
    String fingerprint = requestFingerprint(title, desc, subjectId, requestedChapterId,
        classSessionId, durationSeconds, stored);
    try {
      if (requestKey != null) {
        List<Map<String, Object>> previous = jdbc.queryForList("""
          select id,request_fingerprint from recorded_class
          where created_by_staff_id=? and idempotency_key=?
          """, context.staffId(), requestKey);
        if (!previous.isEmpty()) {
          long existingId = ((Number) previous.getFirst().get("id")).longValue();
          String existingFingerprint = String.valueOf(previous.getFirst().get("request_fingerprint"));
          mediaStorage.deleteQuietly(mediaStorage.resolve(stored.storageKey()));
          if (!fingerprint.equals(existingFingerprint)) {
            throw conflict("This upload key was already used for different recording content. Start a new upload attempt with a new key.");
          }
          return adminRecordingById(existingId);
        }
      }

      List<Long> inserted = requestKey == null
          ? jdbc.query("""
              insert into recorded_class(
                title,description,subject_id,chapter_id,class_session_id,storage_key,original_filename,
                media_type,file_size_bytes,sha256,duration_seconds,status,created_by_staff_id
              ) values(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?) returning id
              """, (rs, rowNum) -> rs.getLong(1), title, nullable(desc), subjectId,
              requestedChapterId, classSessionId, stored.storageKey(), stored.originalFilename(),
              stored.mediaType(), stored.sizeBytes(), stored.sha256(), durationSeconds, context.staffId())
          : jdbc.query("""
              insert into recorded_class(
                title,description,subject_id,chapter_id,class_session_id,storage_key,original_filename,
                media_type,file_size_bytes,sha256,duration_seconds,status,created_by_staff_id,
                idempotency_key,request_fingerprint
              ) values(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?)
              on conflict do nothing returning id
              """, (rs, rowNum) -> rs.getLong(1), title, nullable(desc), subjectId,
              requestedChapterId, classSessionId, stored.storageKey(), stored.originalFilename(),
              stored.mediaType(), stored.sizeBytes(), stored.sha256(), durationSeconds, context.staffId(),
              requestKey, fingerprint);

      if (inserted.isEmpty()) {
        // A concurrent request may have won the same idempotency key after the preflight lookup.
        List<Map<String, Object>> previous = jdbc.queryForList("""
          select id,request_fingerprint from recorded_class
          where created_by_staff_id=? and idempotency_key=?
          """, context.staffId(), requestKey);
        mediaStorage.deleteQuietly(mediaStorage.resolve(stored.storageKey()));
        if (!previous.isEmpty()
            && fingerprint.equals(String.valueOf(previous.getFirst().get("request_fingerprint")))) {
          return adminRecordingById(((Number) previous.getFirst().get("id")).longValue());
        }
        throw conflict("This upload key was concurrently used for different recording content. Start a new upload attempt with a new key.");
      }

      Long id = inserted.getFirst();
      staffAudit.recordAction(context, "/api/v1/admin/recorded-classes/" + id + "/uploaded",
          "Uploaded private class recording #" + id + " (" + stored.originalFilename() + ").");
      return adminRecordingById(id);
    } catch (RuntimeException ex) {
      mediaStorage.deleteQuietly(mediaStorage.resolve(stored.storageKey()));
      throw ex;
    }
  }

  private String normalizeIdempotencyKey(String supplied) {
    if (supplied == null || supplied.isBlank()) return null;
    String key = supplied.trim();
    if (key.length() < 8 || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
      throw badRequest("Idempotency-Key must be 8–128 characters using letters, numbers, dot, underscore, colon, or hyphen.");
    }
    return key;
  }

  private String requestFingerprint(String title, String description, Long subjectId,
      Long chapterId, Long sessionId, Integer durationSeconds,
      PrivateMediaStorageService.StoredMedia media) {
    String canonical = String.join("\n",
        title, description == null ? "" : description, String.valueOf(subjectId),
        chapterId == null ? "" : String.valueOf(chapterId),
        sessionId == null ? "" : String.valueOf(sessionId),
        durationSeconds == null ? "" : String.valueOf(durationSeconds),
        media.originalFilename(), media.mediaType(), String.valueOf(media.sizeBytes()), media.sha256());
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available.", ex);
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
  public Map<String,Object> studentLiveClasses(
      @RequestParam(required=false) String subjectCode,@RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    String subjectFilter=safeText(subjectCode,40);
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.youtube_video_id,cs.starts_at,cs.ends_at,cs.status,cs.join_window_minutes,
             s.code as subject_code,s.display_name as subject_name,ch.id as chapter_id,ch.code as chapter_code,
             ch.display_name as chapter_name,host.display_name as host_staff_name,
             exists(select 1 from recorded_class rc where rc.class_session_id=cs.id and rc.status='PUBLISHED') as has_recording,
             coalesce(att.join_count,0) as join_count,coalesce(att.total_seconds,0) as attended_seconds,
             (att.active_since_at is not null) as currently_joined
      from learning_class_session cs join curriculum_subject s on s.id=cs.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee assigned_employee on assigned_employee.id=ta.employee_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id left join staff_account host on host.id=cs.host_staff_id
      left join learning_class_attendance att on att.session_id=cs.id and att.student_id=st.id
      where cs.status<>'CANCELLED' and cs.ends_at>now()-interval '60 days'
        and (cs.status<>'SCHEDULED' or cs.starts_at<=now()+interval '24 hours')
        and (cs.provider<>'GOOGLE_MEET' or (cs.provider_operation_status='CREATED' and cs.private_join_url is not null))
        and ta.id is not null and ta.status='APPROVED'
        and assigned_employee.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
        and ta.effective_start <= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and ta.effective_end >= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      order by case when cs.ends_at>now() then 0 else 1 end,cs.starts_at asc,cs.id desc limit ? offset ?
      """,context.studentId(),subjectFilter,subjectFilter,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from learning_class_session cs join curriculum_subject s on s.id=cs.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee assigned_employee on assigned_employee.id=ta.employee_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      where cs.status<>'CANCELLED' and cs.ends_at>now()-interval '60 days'
        and (cs.status<>'SCHEDULED' or cs.starts_at<=now()+interval '24 hours')
        and (cs.provider<>'GOOGLE_MEET' or (cs.provider_operation_status='CREATED' and cs.private_join_url is not null))
        and ta.id is not null and ta.status='APPROVED'
        and assigned_employee.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
        and ta.effective_start <= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and ta.effective_end >= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      """,Long.class,context.studentId(),subjectFilter,subjectFilter);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @PostMapping("/learning/live-classes/{sessionId}/join")
  @Transactional
  public Map<String, Object> joinLiveClass(
      @PathVariable long sessionId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select cs.id,cs.private_join_url,cs.youtube_video_id,cs.provider,cs.title,cs.status,cs.starts_at,cs.ends_at,cs.join_window_minutes
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee assigned_employee on assigned_employee.id=ta.employee_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      where cs.id=? and cs.status in ('SCHEDULED','LIVE')
        and (cs.provider='YOUTUBE_LIVE' or cs.private_join_url is not null)
        and (cs.provider<>'GOOGLE_MEET' or cs.provider_operation_status='CREATED')
        and ta.id is not null and ta.status='APPROVED'
        and assigned_employee.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
        and ta.effective_start <= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and ta.effective_end >= (cs.starts_at at time zone 'Asia/Kolkata')::date
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      """, context.studentId(), sessionId);
    if (rows.isEmpty()) throw notFound("Live class", sessionId);
    Map<String, Object> session = rows.getFirst();
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime startsAt = toOffsetDateTime(session.get("starts_at"));
    OffsetDateTime endsAt = toOffsetDateTime(session.get("ends_at"));
    int joinWindowMinutes = session.get("join_window_minutes") instanceof Number n ? n.intValue() : 15;
    if (joinWindowMinutes > 0 && startsAt != null && startsAt.isAfter(now.plusMinutes(joinWindowMinutes))) {
      throw conflict("This live class has not opened yet. Access opens "
          + joinWindowMinutes + " minutes before its scheduled start.");
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
    result.put("youtubeVideoId", session.get("youtube_video_id"));
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
  public Map<String,Object> studentRecordedClasses(
      @RequestParam(required=false) String subjectCode,@RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    context=authorization.requireStudent(context);
    String subjectFilter=safeText(subjectCode,40);
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100)),rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    List<Map<String,Object>> items=jdbc.queryForList("""
      select rc.id as recorded_class_id,rc.title,rc.description,rc.media_type,rc.file_size_bytes,
             rc.duration_seconds,rc.created_at,rc.published_at,s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             cs.id as session_id,cs.title as session_title,cs.starts_at as session_starts_at,
             coalesce(progress.last_position_seconds,0) as last_position_seconds,
             coalesce(progress.watched_seconds,0) as watched_seconds,coalesce(progress.completed,false) as completed
      from recorded_class rc join curriculum_subject s on s.id=rc.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=rc.chapter_id
      left join learning_class_session cs on cs.id=rc.class_session_id
      left join student_recorded_class_progress progress on progress.recorded_class_id=rc.id and progress.student_id=st.id
      where rc.status='PUBLISHED' and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      order by coalesce(ch.teaching_order,ch.sort_order),rc.created_at desc,rc.id desc limit ? offset ?
      """,context.studentId(),subjectFilter,subjectFilter,rowLimit,rowOffset);
    Long total=jdbc.queryForObject("""
      select count(*) from recorded_class rc join curriculum_subject s on s.id=rc.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=rc.chapter_id
      where rc.status='PUBLISHED' and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      """,Long.class,context.studentId(),subjectFilter,subjectFilter);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
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

  private long positiveLong(Object value,String label) {
    try { long parsed=Long.parseLong(String.valueOf(value)); if(parsed>0)return parsed; }
    catch(RuntimeException ignored) {}
    throw badRequest(label+" ID must be a positive integer.");
  }

  private void validateNoScheduleConflict(Long hostStaffId, Long employeeId,
      OffsetDateTime startsAt, OffsetDateTime endsAt, Long excludedSessionId) {
    if (hostStaffId == null && employeeId == null) return;
    Boolean overlaps = jdbc.queryForObject("""
      select exists(
        select 1 from learning_class_session other
        left join hr_teaching_assignment oa on oa.id=other.teaching_assignment_id
        where other.status in ('SCHEDULED','LIVE')
          and ((?::bigint is not null and other.host_staff_id=?)
            or (?::bigint is not null and oa.employee_id=?))
          and other.starts_at < ? and other.ends_at > ?
          and (?::bigint is null or other.id<>?)
      )
      """,Boolean.class,hostStaffId,hostStaffId,employeeId,employeeId,endsAt,startsAt,excludedSessionId,excludedSessionId);
    if (Boolean.TRUE.equals(overlaps)) {
      throw conflict("This teacher or host already has an overlapping scheduled/live class. Resolve the timetable conflict before saving.");
    }
  }

  private String shortMessage(String message) {
    if (message == null || message.isBlank()) return null;
    String value=message.trim();
    return value.length()>500?value.substring(0,500):value;
  }

  private Map<String, Object> adminSessionById(long sessionId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select cs.id as session_id,cs.title,cs.description,cs.provider,cs.youtube_video_id,cs.starts_at,cs.ends_at,cs.status,cs.join_window_minutes,
             cs.host_staff_id,host.display_name as host_staff_name,cs.teaching_assignment_id,
             assigned.display_name as assigned_teacher_name,ap.name as academic_period_name,
             cs.provider_operation_status,cs.provider_error_code,cs.provider_error_message,cs.provider_event_id,cs.youtube_video_id,
             cs.created_at,cs.updated_at,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id
      left join staff_account host on host.id=cs.host_staff_id
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee assigned on assigned.id=ta.employee_id
      left join hr_academic_period ap on ap.id=ta.academic_period_id
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

  private String validYouTubeVideoId(Object value) {
    String text = requiredText(value, "YouTube broadcast URL", 2000);
    try {
      URI uri = URI.create(text);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
        throw badRequest("Use a valid HTTPS YouTube live broadcast URL.");
      }
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (!Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be").contains(host)) {
        throw badRequest("Use a YouTube watch, live, or youtu.be URL.");
      }
      String videoId = null;
      if ("youtu.be".equals(host)) {
        videoId = uri.getPath() == null ? null : uri.getPath().replaceFirst("^/", "").split("/")[0];
      } else if ("/watch".equals(uri.getPath())) {
        String query = uri.getRawQuery();
        if (query != null) for (String part : query.split("&")) {
          String[] pair = part.split("=", 2);
          if (pair.length == 2 && pair[0].equals("v")) { videoId = pair[1]; break; }
        }
      } else if (uri.getPath() != null && (uri.getPath().startsWith("/live/") || uri.getPath().startsWith("/embed/"))) {
        String[] parts = uri.getPath().split("/");
        if (parts.length > 2) videoId = parts[2];
      }
      if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
        throw badRequest("Enter a YouTube broadcast URL with a valid 11-character video ID.");
      }
      return videoId;
    } catch (IllegalArgumentException ex) {
      throw badRequest("Enter a valid HTTPS YouTube broadcast URL.");
    }
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
