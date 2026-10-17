package com.quantaedge.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LearningMessagingService {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final ApplicationEventPublisher events;

  public LearningMessagingService(JdbcTemplate jdbc, AuthorizationService authorization,
      StaffAuditService staffAudit, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.events = events;
  }

  @Transactional
  public List<Map<String,Object>> conversations(long sessionId, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    assertSessionAccess(sessionId, context, false);
    getOrCreateConversation(sessionId, "CLASSROOM", null, context);
    String actorKind = actorKind(context);
    long actorId = actorId(context);
    String extraScope = context.isStudent()
        ? " and (c.conversation_type='CLASSROOM' or c.student_id=?)" : "";
    String sql = """
      select c.id as conversation_id,c.conversation_type,c.session_id,c.student_id,c.status,
        c.slow_mode_seconds,c.created_at,c.updated_at,c.last_message_at,
        st.display_name as student_name,teacher.display_name as teacher_name,
        latest.id as latest_message_id,left(latest.body,160) as last_message_preview,
        latest.created_at as latest_message_created_at,coalesce(unread.unread_count,0) as unread_count
      from learning_conversation c
      join learning_class_session cs on cs.id=c.session_id
      left join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
      left join hr_employee e on e.id=ta.employee_id
      left join staff_account teacher on teacher.id=e.linked_staff_account_id
      left join student st on st.id=c.student_id
      left join learning_conversation_read_state rs
        on rs.conversation_id=c.id and rs.participant_kind=? and rs.participant_id=?
      left join lateral (
        select m.id,m.body,m.created_at from learning_message m
        where m.conversation_id=c.id and m.status='VISIBLE' order by m.id desc limit 1
      ) latest on true
      left join lateral (
        select count(*) as unread_count from learning_message m
        where m.conversation_id=c.id and m.status='VISIBLE'
          and m.id>coalesce(rs.last_read_message_id,0)
          and not (m.sender_student_id is not distinct from ?::bigint
                   or m.sender_staff_id is not distinct from ?::bigint)
      ) unread on true
      where c.session_id=? and c.status<>'ARCHIVED'
      """ + extraScope + """
      order by case when c.conversation_type='CLASSROOM' then 0 else 1 end,
        coalesce(c.last_message_at,c.created_at) desc,c.id
      """;
    Object[] params = context.isStudent()
        ? new Object[]{actorKind,actorId,context.studentId(),context.staffId(),sessionId,context.studentId()}
        : new Object[]{actorKind,actorId,context.studentId(),context.staffId(),sessionId};
    List<Map<String,Object>> result = new ArrayList<>();
    for (Map<String,Object> row : jdbc.queryForList(sql, params)) {
      Map<String,Object> item = new LinkedHashMap<>();
      item.put("conversationId", row.get("conversation_id"));
      item.put("conversationType", row.get("conversation_type"));
      item.put("sessionId", row.get("session_id"));
      item.put("studentId", context.isEmployee() ? row.get("student_id") : null);
      item.put("studentName", context.isEmployee() ? row.get("student_name") : null);
      item.put("teacherName", row.get("teacher_name"));
      item.put("status", row.get("status"));
      item.put("slowModeSeconds", row.get("slow_mode_seconds"));
      item.put("lastMessagePreview", row.get("last_message_preview"));
      item.put("lastMessageCreatedAt", row.get("latest_message_created_at"));
      item.put("unreadCount", row.get("unread_count"));
      result.add(item);
    }
    return List.copyOf(result);
  }

  @Transactional
  public Map<String,Object> openConversation(long sessionId, String suppliedType, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    assertSessionAccess(sessionId, context, false);
    String type = suppliedType == null ? "" : suppliedType.trim().toUpperCase(Locale.ROOT);
    if (!"CLASSROOM".equals(type) && !"STUDENT_TEACHER".equals(type)) {
      throw badRequest("Choose CLASSROOM or STUDENT_TEACHER.");
    }
    if ("STUDENT_TEACHER".equals(type) && !context.isStudent()) {
      throw forbidden("Only a student can open their private student-to-teacher conversation.");
    }
    long id = getOrCreateConversation(sessionId, type,
        "STUDENT_TEACHER".equals(type) ? context.studentId() : null, context);
    return conversations(sessionId, context).stream()
        .filter(item -> ((Number)item.get("conversationId")).longValue() == id)
        .findFirst().orElseThrow(() -> notFound("Conversation", id));
  }

  @Transactional
  public Map<String,Object> messages(long conversationId, Long beforeId, Integer requestedLimit,
      AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, false);
    long anchor = beforeId == null ? 0L : beforeId;
    if (anchor < 0L) throw badRequest("beforeId cannot be negative.");
    if (anchor > 0L && !exists("select exists(select 1 from learning_message where id=? and conversation_id=?)",
        anchor, conversationId)) throw badRequest("The message cursor does not belong to this conversation.");
    int pageSize = Math.max(1, Math.min(requestedLimit == null ? 40 : requestedLimit, MAX_PAGE_SIZE));
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select m.id as message_id,m.conversation_id,m.sender_student_id,m.sender_staff_id,
        m.client_message_id,m.message_type,m.body,m.status,m.created_at,m.pinned_at,
        coalesce(st.display_name,sa.display_name,
          case when m.sender_student_id is not null then 'Student' else 'Teacher' end) as sender_name
      from learning_message m
      left join student st on st.id=m.sender_student_id
      left join staff_account sa on sa.id=m.sender_staff_id
      where m.conversation_id=? and m.status='VISIBLE' and (?<=0 or m.id<?)
      order by m.id desc limit ?
      """, conversationId, anchor, anchor, pageSize + 1);
    boolean hasMore = rows.size() > pageSize;
    if (hasMore) rows.remove(rows.size() - 1);
    java.util.Collections.reverse(rows);
    List<Map<String,Object>> result = rows.stream().map(this::messageView).toList();
    if (!rows.isEmpty()) markReadState(conversationId, context,
        ((Number)rows.get(rows.size()-1).get("message_id")).longValue());
    Map<String,Object> response = new LinkedHashMap<>();
    response.put("conversationId", conversationId);
    response.put("messages", result);
    response.put("hasMore", hasMore);
    response.put("nextBeforeId", rows.isEmpty() ? null : rows.get(0).get("message_id"));
    response.put("status", conversation.get("status"));
    return response;
  }

  @Transactional
  public Map<String,Object> sendMessage(long conversationId, LearningMessageInput input, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    LearningMessagingRules.NormalizedMessage normalized =
        LearningMessagingRules.normalize(input, context.isEmployee());
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, true);
    if (!"OPEN".equals(String.valueOf(conversation.get("status")))) throw conflict("This conversation is closed to new messages.");
    assertSessionAccess(number(conversation.get("session_id")), context, true);

    List<Map<String,Object>> duplicate = jdbc.queryForList("""
      select id as message_id,body,message_type,status,sender_student_id,sender_staff_id
      from learning_message where conversation_id=? and client_message_id=?::uuid
      """, conversationId, normalized.clientMessageId());
    if (!duplicate.isEmpty()) return returnIdenticalRetry(duplicate.getFirst(), normalized, context);

    if (context.isStudent() && exists("""
        select exists(select 1 from learning_conversation_mute
          where conversation_id=? and student_id=? and muted_until>now())
        """, conversationId, context.studentId())) {
      throw forbidden("You are temporarily muted in this conversation.");
    }
    long sentLastMinute = scalarLong("""
      select count(*) from learning_message where conversation_id=?
        and created_at>now()-interval '60 seconds'
        and sender_student_id is not distinct from ?::bigint
        and sender_staff_id is not distinct from ?::bigint
      """, conversationId, context.studentId(), context.staffId());
    if (sentLastMinute >= 20L) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Message rate limit reached. Please slow down.");
    }
    int slowMode = ((Number)conversation.getOrDefault("slow_mode_seconds", 0)).intValue();
    if (slowMode > 0 && exists("""
        select exists(select 1 from learning_message
          where conversation_id=? and status='VISIBLE'
            and sender_student_id is not distinct from ?::bigint
            and sender_staff_id is not distinct from ?::bigint
            and created_at>now()-(? * interval '1 second'))
        """, conversationId, context.studentId(), context.staffId(), slowMode)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Slow mode is enabled. Wait before sending another message.");
    }

    Long insertedId = jdbc.query("""
      insert into learning_message(conversation_id,sender_student_id,sender_staff_id,client_message_id,message_type,body)
      values(?,?,?,?,?,?)
      on conflict(conversation_id,client_message_id) do nothing returning id
      """, rs -> rs.next() ? Long.valueOf(rs.getLong(1)) : null,
      conversationId, context.studentId(), context.staffId(), normalized.clientMessageId(),
      normalized.messageType(), normalized.body());
    if (insertedId == null) {
      List<Map<String,Object>> afterRace = jdbc.queryForList("""
        select id as message_id,body,message_type,status,sender_student_id,sender_staff_id
        from learning_message where conversation_id=? and client_message_id=?::uuid
        """, conversationId, normalized.clientMessageId());
      if (afterRace.isEmpty()) throw conflict("Message retry could not be reconciled; reload history and retry.");
      return returnIdenticalRetry(afterRace.getFirst(), normalized, context);
    }
    jdbc.update("update learning_conversation set last_message_at=now(),updated_at=now() where id=?", conversationId);
    Map<String,Object> saved = messageById(insertedId);
    events.publishEvent(new LearningMessagePublishedEvent(conversationId, saved));
    return saved;
  }

  private Map<String,Object> returnIdenticalRetry(Map<String,Object> row,
      LearningMessagingRules.NormalizedMessage input, AuthContext context) {
    boolean sameOwner = Objects.equals(longOrNull(row.get("sender_student_id")), context.studentId())
        && Objects.equals(longOrNull(row.get("sender_staff_id")), context.staffId());
    boolean samePayload = Objects.equals(String.valueOf(row.get("body")), input.body())
        && Objects.equals(String.valueOf(row.get("message_type")), input.messageType());
    if (!sameOwner || !samePayload) throw conflict("This client message ID was already used for a different sender or payload.");
    if (!"VISIBLE".equals(String.valueOf(row.get("status")))) {
      throw conflict("This message was already moderated and cannot be replayed as visible.");
    }
    return messageById(number(row.get("message_id")));
  }

  @Transactional
  public void markRead(long conversationId, Long lastReadMessageId, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    assertConversationAccess(conversationId, context, false);
    if (lastReadMessageId == null || lastReadMessageId <= 0L
        || !exists("select exists(select 1 from learning_message where id=? and conversation_id=? and status='VISIBLE')",
            lastReadMessageId, conversationId)) {
      throw badRequest("A visible message in this conversation is required to update read state.");
    }
    markReadState(conversationId, context, lastReadMessageId);
  }

  @Transactional
  public Map<String,Object> reportMessage(long messageId, String suppliedReason, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    Map<String,Object> message = rawMessage(messageId);
    long conversationId = number(message.get("conversation_id"));
    assertConversationAccess(conversationId, context, false);
    String reason = suppliedReason == null ? "" : suppliedReason.strip();
    if (reason.length() < 5 || reason.length() > 1000) throw badRequest("A report reason must contain 5–1000 characters.");
    boolean isOwn = context.isStudent()
        ? Objects.equals(longOrNull(message.get("sender_student_id")), context.studentId())
        : Objects.equals(longOrNull(message.get("sender_staff_id")), context.staffId());
    if (isOwn) throw badRequest("You cannot report your own message.");
    jdbc.update("""
      insert into learning_message_report(conversation_id,message_id,reporter_student_id,reporter_staff_id,reason)
      values(?,?,?,?,?) on conflict do nothing
      """, conversationId, messageId, context.studentId(), context.staffId(), reason);
    recordAudit(conversationId, messageId, context, "REPORT", "A conversation message was reported.");
    return jdbc.queryForMap("""
      select id as report_id,message_id,status,created_at from learning_message_report
      where message_id=? and reporter_student_id is not distinct from ?::bigint
        and reporter_staff_id is not distinct from ?::bigint
      """, messageId, context.studentId(), context.staffId());
  }

  @Transactional
  public Map<String,Object> moderateMessage(long messageId, String suppliedAction, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    if (!context.isEmployee()) throw forbidden("Staff moderation access is required.");
    Map<String,Object> message = rawMessage(messageId);
    long conversationId = number(message.get("conversation_id"));
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, false);
    assertSessionAccess(number(conversation.get("session_id")), context, false);
    String action = suppliedAction == null ? "" : suppliedAction.trim().toUpperCase(Locale.ROOT);
    switch (action) {
      case "HIDE" -> jdbc.update("update learning_message set status='HIDDEN',hidden_at=now(),hidden_by_staff_id=? where id=?",
          context.staffId(), messageId);
      case "RESTORE" -> jdbc.update("update learning_message set status='VISIBLE',hidden_at=null,hidden_by_staff_id=null where id=?",
          messageId);
      case "PIN" -> {
        if (!java.util.Set.of("QUESTION","ANNOUNCEMENT").contains(String.valueOf(message.get("message_type")))) {
          throw badRequest("Only a question or announcement can be pinned.");
        }
        jdbc.update("update learning_message set pinned_at=now(),pinned_by_staff_id=? where id=?", context.staffId(), messageId);
      }
      case "UNPIN" -> jdbc.update("update learning_message set pinned_at=null,pinned_by_staff_id=null where id=?", messageId);
      default -> throw badRequest("Choose HIDE, RESTORE, PIN, or UNPIN.");
    }
    recordAudit(conversationId, messageId, context, action, "Moderator action " + action + " applied.");
    staffAudit.recordAction(context, "/api/v1/learning/messages/" + messageId + "/moderation",
        action + " message #" + messageId + " in conversation #" + conversationId + ".");
    return messageById(messageId);
  }

  @Transactional
  public Map<String,Object> updateConversationSettings(long conversationId, Integer suppliedSlowMode,
      AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    if (!context.isEmployee()) throw forbidden("Only the assigned teacher or a moderator can change chat settings.");
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, false);
    int seconds = suppliedSlowMode == null ? -1 : suppliedSlowMode;
    if (seconds < 0 || seconds > 60) throw badRequest("Slow mode must be from 0 to 60 seconds.");
    jdbc.update("update learning_conversation set slow_mode_seconds=?,updated_at=now() where id=?", seconds, conversationId);
    recordAudit(conversationId, null, context, "SLOW_MODE_CHANGED", "Slow mode seconds set to " + seconds + ".");
    staffAudit.recordAction(context, "/api/v1/learning/conversations/" + conversationId + "/settings",
        "Changed classroom chat slow mode to " + seconds + " seconds.");
    return Map.of("conversationId", conversationId, "slowModeSeconds", seconds);
  }

  @Transactional
  public Map<String,Object> muteStudent(long conversationId, Long suppliedStudentId, Integer suppliedMinutes,
      AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    if (!context.isEmployee()) throw forbidden("Staff moderation access is required.");
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, false);
    long studentId = suppliedStudentId == null ? 0L : suppliedStudentId;
    int minutes = suppliedMinutes == null ? -1 : suppliedMinutes;
    if (studentId <= 0L || minutes < 0 || minutes > 1440) {
      throw badRequest("Choose a valid student and a mute duration from 0 to 1440 minutes.");
    }
    if ("STUDENT_TEACHER".equals(String.valueOf(conversation.get("conversation_type")))
        && number(conversation.get("student_id")) != studentId) {
      throw forbidden("A private thread moderator can only mute its participating student.");
    }
    Map<String,Object> session = assertSessionAccess(number(conversation.get("session_id")), context, false);
    if (!isStudentSessionMember(studentId, session)) throw forbidden("The selected student is not enrolled in this class subject.");
    if (minutes == 0) {
      jdbc.update("delete from learning_conversation_mute where conversation_id=? and student_id=?", conversationId, studentId);
    } else {
      jdbc.update("""
        insert into learning_conversation_mute(conversation_id,student_id,muted_until,created_by_staff_id)
        values(?,?,now() + (? * interval '1 minute'),?)
        on conflict(conversation_id,student_id) do update
          set muted_until=excluded.muted_until,created_by_staff_id=excluded.created_by_staff_id,created_at=now()
        """, conversationId, studentId, minutes, context.staffId());
    }
    String action = minutes == 0 ? "UNMUTE_STUDENT" : "MUTE_STUDENT";
    recordAudit(conversationId, null, context, action,
        action + " for student #" + studentId + (minutes == 0 ? "." : " for " + minutes + " minute(s)."));
    staffAudit.recordAction(context, "/api/v1/learning/conversations/" + conversationId + "/mute",
        action + " in conversation #" + conversationId + " for student #" + studentId + ".");
    return Map.of("conversationId", conversationId, "studentId", studentId, "muted", minutes > 0, "minutes", minutes);
  }

  @Transactional(readOnly = true)
  public List<Map<String,Object>> reports(long conversationId, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    if (!context.isEmployee()) throw forbidden("Only the assigned teacher or a moderator can review reports.");
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, false);
    assertSessionAccess(number(conversation.get("session_id")), context, false);
    return jdbc.queryForList("""
      select r.id as report_id,r.message_id,r.reason,r.status,r.created_at,r.reviewed_at,
        coalesce(rs.display_name,ra.display_name,'Member') as reporter_name,
        m.body as message_body,m.message_type,m.created_at as message_created_at
      from learning_message_report r join learning_message m on m.id=r.message_id
      left join student rs on rs.id=r.reporter_student_id
      left join staff_account ra on ra.id=r.reporter_staff_id
      where r.conversation_id=? order by case when r.status='OPEN' then 0 else 1 end,r.created_at desc
      """, conversationId);
  }

  @Transactional
  public Map<String,Object> resolveReport(long reportId, String suppliedStatus, AuthContext rawContext) {
    AuthContext context = authorization.requireAuth(rawContext);
    if (!context.isEmployee()) throw forbidden("Only the assigned teacher or a moderator can resolve reports.");
    List<Map<String,Object>> rows = jdbc.queryForList(
        "select id,conversation_id,message_id,status from learning_message_report where id=?", reportId);
    if (rows.isEmpty()) throw notFound("Message report", reportId);
    Map<String,Object> report = rows.getFirst();
    long conversationId = number(report.get("conversation_id"));
    Map<String,Object> conversation = assertConversationAccess(conversationId, context, false);
    assertSessionAccess(number(conversation.get("session_id")), context, false);
    String status = suppliedStatus == null ? "" : suppliedStatus.trim().toUpperCase(Locale.ROOT);
    if (!java.util.Set.of("RESOLVED","DISMISSED").contains(status)) throw badRequest("Choose RESOLVED or DISMISSED.");
    jdbc.update("update learning_message_report set status=?,reviewed_by_staff_id=?,reviewed_at=now() where id=?",
        status, context.staffId(), reportId);
    String action = "RESOLVED".equals(status) ? "REPORT_RESOLVED" : "REPORT_DISMISSED";
    recordAudit(conversationId, number(report.get("message_id")), context, action, action + " report #" + reportId + ".");
    staffAudit.recordAction(context, "/api/v1/learning/reports/" + reportId,
        "Changed message report #" + reportId + " to " + status + ".");
    return jdbc.queryForMap("select id as report_id,message_id,status,reviewed_at from learning_message_report where id=?", reportId);
  }

  @Transactional(readOnly = true)
  public void assertCanReadConversation(long conversationId, AuthContext context) {
    assertConversationAccess(conversationId, context, false);
  }

  private Map<String,Object> assertConversationAccess(long conversationId, AuthContext context, boolean forPost) {
    context = authorization.requireAuth(context);
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select id as conversation_id,conversation_type,session_id,student_id,status,slow_mode_seconds
      from learning_conversation where id=?
      """, conversationId);
    if (rows.isEmpty() || "ARCHIVED".equals(String.valueOf(rows.isEmpty() ? "" : rows.getFirst().get("status")))) {
      throw notFound("Conversation", conversationId);
    }
    Map<String,Object> conversation = rows.getFirst();
    assertSessionAccess(number(conversation.get("session_id")), context, forPost);
    if ("STUDENT_TEACHER".equals(String.valueOf(conversation.get("conversation_type")))
        && context.isStudent() && !Objects.equals(longOrNull(conversation.get("student_id")), context.studentId())) {
      throw forbidden("This private conversation is not available to this student.");
    }
    return conversation;
  }

  private Map<String,Object> assertSessionAccess(long sessionId, AuthContext context, boolean forPost) {
    context = authorization.requireAuth(context);
    List<Map<String,Object>> sessions = jdbc.queryForList("""
      select cs.id,cs.status,cs.starts_at,cs.ends_at,cs.join_window_minutes,cs.subject_id,
        cs.teaching_assignment_id,c.code as class_code,
        (s.active=true and c.active=true) as subject_class_active,
        (cs.status<>'SCHEDULED' or cs.starts_at<=now()+interval '24 hours') as student_visible,
        (cs.status in ('SCHEDULED','LIVE') and cs.ends_at>now()
          and (cs.join_window_minutes=0
            or cs.starts_at<=now()+(cs.join_window_minutes * interval '1 minute'))) as chat_open,
        (cs.chapter_id is null or (ch.active=true and ch.content_status='PUBLISHED')) as chapter_visible
      from learning_class_session cs
      join curriculum_subject s on s.id=cs.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=cs.chapter_id and ch.subject_id=cs.subject_id
      where cs.id=? and cs.status<>'CANCELLED' and cs.ends_at>now()-interval '60 days'
      """, sessionId);
    if (sessions.isEmpty()) throw notFound("Live class session", sessionId);
    Map<String,Object> session = sessions.getFirst();
    if (!Boolean.TRUE.equals(session.get("subject_class_active"))) throw notFound("Live class session", sessionId);
    if (context.isStudent()) {
      if (!isStudentSessionMember(context.studentId(), session)) throw forbidden("Active class and subject enrollment is required for this conversation.");
      if (!Boolean.TRUE.equals(session.get("student_visible")) || !Boolean.TRUE.equals(session.get("chapter_visible"))) {
        throw notFound("Live class session", sessionId);
      }
    } else if (context.isEmployee()) {
      boolean classManager = authorization.hasPermission(context, "CLASS_MANAGE");
      if (!classManager && !isAssignedTeacher(sessionId, context.staffId())) {
        throw forbidden("Only the assigned teacher or an authorized class moderator can access this conversation.");
      }
    } else {
      throw forbidden("Messaging is available only to an enrolled student or authorized teaching staff.");
    }
    if (forPost && !Boolean.TRUE.equals(session.get("chat_open"))) {
      throw conflict("Chat is not open for this session yet or the session has ended.");
    }
    return session;
  }

  private boolean isStudentSessionMember(long studentId, Map<String,Object> session) {
    if (studentId <= 0L) return false;
    return exists("""
      select exists(select 1 from student st
        join student_track_enrollment ste on ste.student_id=st.id
        where st.id=? and st.active=true and st.class_code=?
          and ste.subject_id=? and ste.status='ACTIVE')
      """, studentId, String.valueOf(session.get("class_code")), number(session.get("subject_id")));
  }

  private boolean isAssignedTeacher(long sessionId, Long staffId) {
    if (staffId == null) return false;
    return exists("""
      select exists(
        select 1 from learning_class_session cs
        join hr_teaching_assignment ta on ta.id=cs.teaching_assignment_id
        join hr_employee e on e.id=ta.employee_id
        join staff_account sa on sa.id=e.linked_staff_account_id and sa.active=true
        where cs.id=? and e.linked_staff_account_id=? and ta.status='APPROVED'
          and e.employment_status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')
          and ta.effective_start <= (cs.starts_at at time zone 'Asia/Kolkata')::date
          and ta.effective_end >= (cs.starts_at at time zone 'Asia/Kolkata')::date
          and e.joining_date <= (cs.starts_at at time zone 'Asia/Kolkata')::date
          and (e.exit_date is null or e.exit_date >= (cs.starts_at at time zone 'Asia/Kolkata')::date)
          and not exists(select 1 from hr_leave_request lr where lr.employee_id=e.id
            and lr.request_status='APPROVED'
            and lr.start_date<=(cs.starts_at at time zone 'Asia/Kolkata')::date
            and lr.end_date>=(cs.starts_at at time zone 'Asia/Kolkata')::date)
      )
      """, sessionId, staffId);
  }

  private long getOrCreateConversation(long sessionId, String type, Long studentId, AuthContext context) {
    String query = "select id from learning_conversation where session_id=? and conversation_type=? and status<>'ARCHIVED'"
        + ("STUDENT_TEACHER".equals(type) ? " and student_id=?" : "");
    List<Map<String,Object>> existing = "STUDENT_TEACHER".equals(type)
        ? jdbc.queryForList(query, sessionId, type, studentId) : jdbc.queryForList(query, sessionId, type);
    if (!existing.isEmpty()) return number(existing.getFirst().get("id"));
    Long created = jdbc.query("""
      insert into learning_conversation(conversation_type,session_id,student_id,created_by_student_id,created_by_staff_id)
      values(?,?,?,?,?) on conflict do nothing returning id
      """, rs -> rs.next() ? Long.valueOf(rs.getLong(1)) : null,
      type, sessionId, studentId, context.studentId(), context.staffId());
    if (created != null) return created;
    existing = "STUDENT_TEACHER".equals(type)
        ? jdbc.queryForList(query, sessionId, type, studentId) : jdbc.queryForList(query, sessionId, type);
    if (existing.isEmpty()) throw conflict("Conversation creation could not be reconciled; reload and retry.");
    return number(existing.getFirst().get("id"));
  }

  private void markReadState(long conversationId, AuthContext context, long messageId) {
    jdbc.update("""
      insert into learning_conversation_read_state(conversation_id,participant_kind,participant_id,last_read_message_id)
      values(?,?,?,?) on conflict(conversation_id,participant_kind,participant_id) do update
        set last_read_message_id=greatest(learning_conversation_read_state.last_read_message_id,excluded.last_read_message_id),
            updated_at=now()
      """, conversationId, actorKind(context), actorId(context), messageId);
  }

  private Map<String,Object> messageById(long id) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select m.id as message_id,m.conversation_id,m.sender_student_id,m.sender_staff_id,
        m.client_message_id,m.message_type,m.body,m.status,m.created_at,m.pinned_at,
        coalesce(st.display_name,sa.display_name,
          case when m.sender_student_id is not null then 'Student' else 'Teacher' end) as sender_name
      from learning_message m left join student st on st.id=m.sender_student_id
        left join staff_account sa on sa.id=m.sender_staff_id where m.id=?
      """, id);
    if (rows.isEmpty()) throw notFound("Message", id);
    return messageView(rows.getFirst());
  }

  private Map<String,Object> messageView(Map<String,Object> row) {
    Map<String,Object> item = new LinkedHashMap<>();
    item.put("messageId", row.get("message_id"));
    item.put("conversationId", row.get("conversation_id"));
    boolean fromStudent = row.get("sender_student_id") != null;
    item.put("senderType", fromStudent ? "STUDENT" : "STAFF");
    item.put("senderId", fromStudent ? row.get("sender_student_id") : row.get("sender_staff_id"));
    item.put("senderName", row.get("sender_name"));
    item.put("clientMessageId", String.valueOf(row.get("client_message_id")));
    item.put("messageType", row.get("message_type"));
    item.put("body", row.get("body"));
    item.put("status", row.get("status"));
    item.put("createdAt", row.get("created_at"));
    item.put("pinned", row.get("pinned_at") != null);
    item.put("pinnedAt", row.get("pinned_at"));
    return item;
  }

  private Map<String,Object> rawMessage(long messageId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select id as message_id,conversation_id,sender_student_id,sender_staff_id,message_type,status
      from learning_message where id=?
      """, messageId);
    if (rows.isEmpty()) throw notFound("Message", messageId);
    return rows.getFirst();
  }

  private void recordAudit(long conversationId, Long messageId, AuthContext context, String action, String details) {
    jdbc.update("""
      insert into learning_message_audit(conversation_id,message_id,actor_kind,actor_id,action_code,details)
      values(?,?,?,?,?,?)
      """, conversationId, messageId, actorKind(context), actorId(context), action, details);
  }

  private boolean exists(String sql, Object... args) {
    Boolean value = jdbc.queryForObject(sql, Boolean.class, args);
    return Boolean.TRUE.equals(value);
  }
  private long scalarLong(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }
  private static String actorKind(AuthContext context) {
    if (context.isStudent()) return "STUDENT";
    if (context.isEmployee()) return "STAFF";
    throw forbidden("A student or teaching-staff identity is required.");
  }
  private static long actorId(AuthContext context) {
    Long id = context.isStudent() ? context.studentId() : context.staffId();
    if (id == null || id <= 0L) throw forbidden("A valid sender identity is required.");
    return id;
  }
  private static long number(Object value) {
    if (value instanceof Number n) return n.longValue();
    if (value != null) return Long.parseLong(String.valueOf(value));
    throw new IllegalStateException("Required database identifier was null.");
  }
  private static Long longOrNull(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }
  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
  private static ResponseStatusException forbidden(String message) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
  private static ResponseStatusException notFound(String type, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id);
  }
}
