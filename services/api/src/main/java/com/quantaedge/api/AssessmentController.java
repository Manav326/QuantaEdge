package com.quantaedge.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class AssessmentController {
  private static final Set<String> AUTO_GRADED_TYPES = Set.of("MCQ", "TRUE_FALSE", "INPUT", "NUMERICAL");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final QuestionHistoryService questionHistory;
  private final QuestionAnswerService answerService;
  private final StaffAuditService staffAudit;
  private final ObjectMapper mapper;

  public AssessmentController(JdbcTemplate jdbc, AuthorizationService authorization,
      QuestionHistoryService questionHistory, QuestionAnswerService answerService,
      StaffAuditService staffAudit, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.questionHistory = questionHistory;
    this.answerService = answerService;
    this.staffAudit = staffAudit;
    this.mapper = mapper;
  }

  // ---------- Admin: assessment authoring and publication ----------

  @GetMapping("/admin/assessment-question-bank")
  public List<Map<String, Object>> questionBank(
      @RequestParam String classCode,
      @RequestParam String subjectCode,
      @RequestParam(required = false) Long chapterId,
      @RequestParam(required = false) String query,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireContentView(context);
    String term = safeText(query, 160);
    String chapterFilter = chapterId == null ? "" : " and ch.id=?";
    List<Object> args = new ArrayList<>(List.of(classCode.trim(), subjectCode.trim()));
    if (chapterId != null) args.add(chapterId);
    String sql = """
      select q.id as question_id,q.question_type,q.prompt,q.difficulty,q.marks,q.sort_order,
             l.id as lesson_id,l.title as lesson_title,ch.id as chapter_id,
             ch.code as chapter_code,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,c.code as class_code
      from question q join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true
        and ch.active=true and ch.content_status='PUBLISHED'
        and l.active=true and l.status='PUBLISHED'
        and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        __CHAPTER_FILTER__
        and (?='' or q.prompt ilike ? or l.title ilike ? or ch.display_name ilike ?)
      order by coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id,q.sort_order,q.id
      limit 500
      """.replace("__CHAPTER_FILTER__", chapterFilter);
    args.add(term);
    args.add("%" + term + "%");
    args.add("%" + term + "%");
    args.add("%" + term + "%");
    return jdbc.queryForList(sql, args.toArray());
  }

  @GetMapping("/admin/assessments")
  public List<Map<String, Object>> adminAssessments(
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireContentView(context);
    String classFilter = safeText(classCode, 30);
    String subjectFilter = safeText(subjectCode, 40);
    String statusFilter = safeText(status, 20).toUpperCase(Locale.ROOT);
    if (statusFilter.isEmpty()) statusFilter = "ALL";
    if (!Set.of("ALL", "DRAFT", "PUBLISHED", "ARCHIVED").contains(statusFilter)) {
      throw badRequest("Choose a valid assessment status.");
    }
    return jdbc.queryForList("""
      select a.id as assessment_id,a.title,a.description,a.duration_minutes,a.max_attempts,a.status,
             a.created_at,a.published_at,c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             (select count(*) from assessment_question aq where aq.assessment_id=a.id) as question_count,
             (select coalesce(sum(aq.max_marks),0) from assessment_question aq where aq.assessment_id=a.id) as max_score,
             count(distinct att.id) filter (where att.status<>'ABANDONED') as attempt_count,
             count(distinct att.id) filter (where att.status='AWAITING_REVIEW') as review_count
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join student_assessment_attempt att on att.assessment_id=a.id
      where (?='' or c.code=?) and (?='' or s.code=?)
        and (?='ALL' or a.status=?)
        and (?=true or exists(select 1 from assessment_staff_assignment asa
          where asa.assessment_id=a.id and asa.staff_id=? and asa.active=true))
      group by a.id,c.code,c.display_name,s.code,s.display_name,ch.id,ch.code,ch.display_name
      order by a.created_at desc,a.id desc
      limit 300
      """, classFilter, classFilter, subjectFilter, subjectFilter,
        statusFilter, statusFilter, context.isAdmin(), context.staffId());
  }

  @PostMapping("/admin/assessments")
  @Transactional
  public Map<String, Object> createAssessment(
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requirePermission(context, "CONTENT_CREATE");
    String title = requiredText(body.get("title"), "Assessment title", 240);
    String description = safeText(body.get("description"), 1200);
    String classCode = requiredText(body.get("classCode"), "Class", 30);
    String subjectCode = requiredText(body.get("subjectCode"), "Subject", 40);
    int duration = integer(body.getOrDefault("durationMinutes", 45), "Duration", 1, 240);
    int maxAttempts = integer(body.getOrDefault("maxAttempts", 1), "Attempt limit", 1, 10);
    Long chapterId = optionalLong(body.get("chapterId"), "chapterId");
    Object rawIds = body.get("questionIds");
    if (!(rawIds instanceof List<?> values) || values.isEmpty() || values.size() > 200) {
      throw badRequest("Select between 1 and 200 approved questions for the test.");
    }
    List<Long> questionIds = new ArrayList<>();
    for (Object value : values) {
      long id = longValue(value, "Question ID");
      if (questionIds.contains(id)) throw badRequest("A question cannot be added twice to the same assessment.");
      questionIds.add(id);
    }

    Long subjectId = jdbc.query("""
      select s.id from curriculum_subject s join curriculum_class c on c.id=s.class_id
      where c.code=? and s.code=? and c.active=true and s.active=true
      """, rs -> rs.next() ? rs.getLong(1) : null, classCode, subjectCode);
    if (subjectId == null) throw badRequest("Choose a valid active class and subject.");
    if (chapterId != null) {
      Long validChapter = jdbc.queryForObject("""
        select count(*) from curriculum_chapter
        where id=? and subject_id=? and active=true and content_status='PUBLISHED'
        """, Long.class, chapterId, subjectId);
      if (validChapter == null || validChapter == 0) {
        throw badRequest("The selected chapter is not part of this active, published subject.");
      }
    }

    Map<Long, Map<String, Object>> snapshots = new LinkedHashMap<>();
    for (Long questionId : questionIds) {
      List<Object> args = new ArrayList<>(List.of(questionId, subjectId));
      String chapterClause = "";
      if (chapterId != null) {
        chapterClause = " and ch.id=?";
        args.add(chapterId);
      }
      Long eligible = jdbc.queryForObject("""
        select count(*) from question q
        join lesson l on l.id=q.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        where q.id=? and s.id=? and ch.active=true and ch.content_status='PUBLISHED'
          and l.active=true and l.status='PUBLISHED' and q.active=true
          and q.review_status in ('APPROVED','PUBLISHED')
        __CHAPTER__
        """.replace("__CHAPTER__", chapterClause), Long.class, args.toArray());
      if (eligible == null || eligible == 0) {
        throw badRequest("Question " + questionId + " is not an approved, published question in the selected subject/chapter.");
      }
      Map<String, Object> snapshot = questionHistory.snapshot(questionId);
      if (snapshot.isEmpty()) throw badRequest("Question " + questionId + " could not be snapshotted.");
      snapshots.put(questionId, snapshot);
    }

    Long assessmentId = jdbc.queryForObject("""
      insert into assessment(title,description,subject_id,chapter_id,duration_minutes,max_attempts,status,created_by_staff_id)
      values(?,?,?,?,?,?,'DRAFT',?) returning id
      """, Long.class, title, description.isBlank() ? null : description, subjectId, chapterId,
        duration, maxAttempts, context.staffId());

    BigDecimal maxScore = BigDecimal.ZERO;
    int sequence = 1;
    for (Long questionId : questionIds) {
      Map<String, Object> snapshot = snapshots.get(questionId);
      Object rawMarks = snapshot.get("marks");
      BigDecimal marks = rawMarks instanceof Number n
          ? new BigDecimal(n.toString()) : BigDecimal.ONE;
      if (marks.compareTo(BigDecimal.ZERO) < 0 || marks.compareTo(new BigDecimal("1000")) > 0) {
        throw badRequest("Question marks must be between 0 and 1,000.");
      }
      jdbc.update("""
        insert into assessment_question(assessment_id,source_question_id,sequence_no,max_marks,question_snapshot)
        values(?,?,?,?,?::jsonb)
        """, assessmentId, questionId, sequence++, marks, questionHistory.json(snapshot));
      maxScore = maxScore.add(marks);
    }
    audit(assessmentId, null, context, null, null, "ASSESSMENT_CREATED",
        "DRAFT", "DRAFT", "Created assessment with " + questionIds.size() + " frozen question snapshots.");
    staffAudit.recordAction(context, "/api/v1/admin/assessments/" + assessmentId,
        "Created a draft assessment: " + title + " (" + questionIds.size() + " questions).");
    Map<String, Object> result = assessmentDetails(assessmentId, true);
    result.put("max_score_calculated", maxScore);
    return result;
  }

  @PatchMapping("/admin/assessments/{assessmentId}/status")
  @Transactional
  public Map<String, Object> changeAssessmentStatus(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    String next = requiredText(body.get("status"), "Status", 20).toUpperCase(Locale.ROOT);
    if (!Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(next)) throw badRequest("Choose DRAFT, PUBLISHED, or ARCHIVED.");
    if ("PUBLISHED".equals(next) || "ARCHIVED".equals(next)) authorization.requirePermission(context, "CONTENT_PUBLISH");
    else authorization.requirePermission(context, "CONTENT_EDIT");
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select a.id,a.title,a.status,a.subject_id,a.chapter_id,
             s.code as subject_code,c.code as class_code,
             count(aq.id) as question_count
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      left join assessment_question aq on aq.assessment_id=a.id
      where a.id=? group by a.id,s.code,c.code
      """, assessmentId);
    if (rows.isEmpty()) throw notFound("Assessment", assessmentId);
    Map<String, Object> row = rows.getFirst();
    String before = String.valueOf(row.get("status"));
    if ("PUBLISHED".equals(next) && ((Number) row.get("question_count")).intValue() < 1) {
      throw badRequest("Add at least one question before publishing this test.");
    }
    if (!"DRAFT".equals(before) && !"DRAFT".equals(next) && !"PUBLISHED".equals(next) && !"ARCHIVED".equals(next)) {
      throw badRequest("This assessment status transition is not supported.");
    }
    jdbc.update("""
      update assessment set status=?,updated_at=now(),
        published_by_staff_id=case when ?='PUBLISHED' then ? else published_by_staff_id end,
        published_at=case when ?='PUBLISHED' then now() when ?='DRAFT' then null else published_at end
      where id=?
      """, next, next, context.staffId(), next, next, assessmentId);
    audit(assessmentId, null, context, null, null, "ASSESSMENT_STATUS_CHANGED", before, next,
        "Assessment status changed.");
    staffAudit.recordAction(context, "/api/v1/admin/assessments/" + assessmentId + "/status",
        "Changed assessment status from " + before + " to " + next + ".");
    return assessmentDetails(assessmentId, true);
  }

  @PostMapping("/admin/assessments/{assessmentId}/graders")
  @Transactional
  public Map<String, Object> assignGrader(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    long staffId = longValue(body.get("staffId"), "Staff ID");
    Long valid = jdbc.queryForObject("""
      select count(*) from staff_account s
      join staff_permission_grant g on g.staff_id=s.id and g.permission_key='ASSESSMENT_GRADE'
      where s.id=? and s.active=true
      """, Long.class, staffId);
    if (valid == null || valid == 0) throw badRequest("Choose an active staff member with the assessment grading permission.");
    Long assessmentCount = jdbc.queryForObject("select count(*) from assessment where id=?", Long.class, assessmentId);
    if (assessmentCount == null || assessmentCount == 0) throw notFound("Assessment", assessmentId);
    jdbc.update("""
      insert into assessment_staff_assignment(assessment_id,staff_id,assigned_by_staff_id,active,assigned_at)
      values(?,?,?,true,now())
      on conflict(assessment_id,staff_id) do update set
        assigned_by_staff_id=excluded.assigned_by_staff_id,active=true,assigned_at=now()
      """, assessmentId, staffId, context.staffId());
    audit(assessmentId, null, context, null, null, "GRADER_ASSIGNED", null, null, "Assigned staff grader #" + staffId + ".");
    staffAudit.recordAction(context, "/api/v1/admin/assessments/" + assessmentId + "/graders",
        "Assigned staff grader #" + staffId + " to assessment #" + assessmentId + ".");
    return Map.of("assigned", true, "assessmentId", assessmentId, "staffId", staffId);
  }

  @GetMapping("/admin/assessment-staff")
  public List<Map<String, Object>> gradingStaff(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireContentView(context);
    return jdbc.queryForList("""
      select s.id as staff_id,s.display_name,s.role,
             exists(select 1 from staff_permission_grant g
               where g.staff_id=s.id and g.permission_key='ASSESSMENT_GRADE') as can_grade
      from staff_account s where s.active=true
      order by case when s.role='ADMIN' then 0 else 1 end,lower(s.display_name),s.id
      """);
  }

  // ---------- Admin: review queue, grading and test-level audit ----------

  @GetMapping("/admin/assessment-reviews")
  public List<Map<String, Object>> assessmentReviews(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String query,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireGrader(context);
    String statusFilter = safeText(status, 24).toUpperCase(Locale.ROOT);
    if (statusFilter.isEmpty()) statusFilter = "AWAITING_REVIEW";
    if (!Set.of("ALL", "AWAITING_REVIEW", "GRADED", "RELEASED").contains(statusFilter)) {
      throw badRequest("Choose a valid test review status.");
    }
    String term = safeText(query, 160);
    return jdbc.queryForList("""
      select att.id as attempt_id,att.assessment_id,att.attempt_number,att.status,att.started_at,
             att.submitted_at,att.graded_at,att.released_at,att.auto_score,att.final_score,att.max_score,
             a.title as assessment_title,a.duration_minutes,
             st.display_name as student_name,st.id as student_id,st.class_code,
             s.code as subject_code,s.display_name as subject_name,
             ch.display_name as chapter_name,
             count(distinct aq.id) as question_count,
             count(distinct ans.id) filter (where ans.answer_status='PENDING_REVIEW') as pending_answers,
             case when ? then true else false end as can_review
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=att.student_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join student_assessment_attempt_question aq on aq.attempt_id=att.id
      left join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where (?='ALL' or att.status=?)
        and (?='' or a.title ilike ? or st.display_name ilike ? or ch.display_name ilike ?)
        and (?=true or exists(select 1 from assessment_staff_assignment asa
          where asa.assessment_id=a.id and asa.staff_id=? and asa.active=true))
      group by att.id,a.title,a.duration_minutes,st.display_name,st.id,st.class_code,
               s.code,s.display_name,ch.display_name
      order by case when att.status='AWAITING_REVIEW' then 0 else 1 end,att.submitted_at asc,att.id
      limit 500
      """, context.isAdmin(), statusFilter, statusFilter, term, "%" + term + "%", "%" + term + "%",
        "%" + term + "%", context.isAdmin(), context.staffId());
  }

  @GetMapping("/admin/assessment-attempts/{attemptId}")
  public Map<String, Object> reviewAttempt(
      @PathVariable long attemptId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireGrader(context);
    Map<String, Object> attempt = attemptDetails(attemptId, context, true);
    attempt.put("audit", auditHistory(((Number) attempt.get("assessment_id")).longValue(), attemptId));
    return attempt;
  }

  @PostMapping("/admin/assessment-attempts/{attemptId}/grade")
  @Transactional
  public Map<String, Object> gradeAttempt(
      @PathVariable long attemptId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = requireGrader(context);
    List<Map<String, Object>> attempts = jdbc.queryForList("""
      select id,assessment_id,status,auto_score,max_score
      from student_assessment_attempt where id=? for update
      """, attemptId);
    if (attempts.isEmpty()) throw notFound("Assessment attempt", attemptId);
    Map<String, Object> attempt = attempts.getFirst();
    long assessmentId = ((Number) attempt.get("assessment_id")).longValue();
    requireAssignment(context, assessmentId);
    String previous = String.valueOf(attempt.get("status"));
    if (!Set.of("AWAITING_REVIEW", "GRADED").contains(previous)) {
      throw conflict("This test is not waiting for manual grading.");
    }

    Object rawGrades = body.getOrDefault("answers", List.of());
    if (!(rawGrades instanceof List<?> grades) || grades.size() > 200) {
      throw badRequest("Answers to grade must be a list of up to 200 items.");
    }
    int changedAnswers = 0;
    for (Object raw : grades) {
      if (!(raw instanceof Map<?, ?> entry)) throw badRequest("Each grading item must be an object.");
      long answerId = longValue(entry.get("answerId"), "Answer ID");
      BigDecimal marks = decimal(entry.get("marks"), "Marks");
      String feedback = safeText(entry.get("feedback"), 2000);
      List<Map<String, Object>> answers = jdbc.queryForList("""
        select ans.id,ans.answer_status,aq.max_marks
        from student_assessment_answer ans
        join student_assessment_attempt_question aq on aq.id=ans.attempt_question_id
        join student_assessment_attempt att on att.id=aq.attempt_id
        where ans.id=? and att.id=? for update of ans
        """, answerId, attemptId);
      if (answers.isEmpty()) throw badRequest("An answer does not belong to this test attempt.");
      Map<String, Object> answer = answers.getFirst();
      if (!"PENDING_REVIEW".equals(String.valueOf(answer.get("answer_status")))) {
        throw conflict("An answer is already graded or does not need manual review.");
      }
      BigDecimal maxMarks = new BigDecimal(String.valueOf(answer.get("max_marks")));
      if (marks.compareTo(BigDecimal.ZERO) < 0 || marks.compareTo(maxMarks) > 0) {
        throw badRequest("Marks must be between 0 and " + maxMarks + " for answer #" + answerId + ".");
      }
      jdbc.update("""
        update student_assessment_answer set answer_status='GRADED',awarded_marks=?,
          teacher_feedback=?,graded_at=now(),graded_by_staff_id=?
        where id=?
        """, marks.setScale(2, RoundingMode.HALF_UP),
          feedback.isBlank() ? null : feedback, context.staffId(), answerId);
      changedAnswers++;
    }

    Long pending = jdbc.queryForObject("""
      select count(*) from student_assessment_attempt_question aq
      join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where aq.attempt_id=? and ans.answer_status='PENDING_REVIEW'
      """, Long.class, attemptId);
    BigDecimal score = jdbc.queryForObject("""
      select coalesce(sum(coalesce(ans.awarded_marks,0)),0)
      from student_assessment_attempt_question aq
      left join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where aq.attempt_id=?
      """, BigDecimal.class, attemptId);
    String next = (pending != null && pending > 0) ? "AWAITING_REVIEW" : "GRADED";
    if (!"GRADED".equals(next)) {
      jdbc.update("update student_assessment_attempt set status='AWAITING_REVIEW',updated_at=now() where id=?", attemptId);
    } else {
      jdbc.update("""
        update student_assessment_attempt set status='GRADED',final_score=?,graded_at=coalesce(graded_at,now()),updated_at=now()
        where id=?
        """, score, attemptId);
    }

    boolean release = !(body.get("releaseResult") instanceof Boolean value) || value;
    if ("GRADED".equals(next) && release) {
      jdbc.update("""
        update student_assessment_attempt set status='RELEASED',released_at=now(),updated_at=now()
        where id=?
        """, attemptId);
      next = "RELEASED";
    }
    String overallNote = safeText(body.get("overallNote"), 1200);
    audit(assessmentId, attemptId, context,
        null, null, changedAnswers > 0 ? "MANUAL_GRADING_SAVED" : "REVIEW_OPENED",
        previous, next, overallNote.isBlank() ? (changedAnswers == 0 ? "Opened test-level review." : "Saved manual grading decisions.") : overallNote);
    if ("RELEASED".equals(next)) {
      audit(assessmentId, attemptId, context, null, null, "RESULT_RELEASED",
          "GRADED", "RELEASED", overallNote.isBlank() ? "Final result released to the student." : overallNote);
    }
    staffAudit.recordAction(context, "/api/v1/admin/assessment-attempts/" + attemptId + "/grade",
        "Updated test-level grading state for assessment #" + assessmentId + "; " + changedAnswers + " answer(s) graded; status " + next + ".");
    return attemptDetails(attemptId, context, true);
  }

  @GetMapping("/admin/assessments/{assessmentId}/audit")
  public List<Map<String, Object>> assessmentAudit(
      @PathVariable long assessmentId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAuth(context);
    if (context.isEmployee()) {
      if (!context.isAdmin()) requireAssignment(context, assessmentId);
    } else {
      throw new SecurityException("Staff access required.");
    }
    return auditHistory(assessmentId, null);
  }

  // ---------- Student: catalogue, durable attempts, persisted results ----------

  @GetMapping("/learning/assessments")
  public List<Map<String, Object>> studentAssessmentCatalog(
      @RequestParam(required = false) String subjectCode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String subjectFilter = safeText(subjectCode, 40);
    return jdbc.queryForList("""
      select a.id as assessment_id,a.title,a.description,a.duration_minutes,a.max_attempts,
             a.created_at,a.published_at,s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             count(distinct aq.id) as question_count,coalesce(sum(aq.max_marks),0) as max_score,
             (select count(*) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.status<>'ABANDONED') as attempts_used,
             (select max(att.status) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.status='IN_PROGRESS') as in_progress_status,
             (select max(att.id) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.status='IN_PROGRESS') as in_progress_attempt_id,
             (select max(att.id) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.status='RELEASED') as latest_result_attempt_id
      from assessment a join curriculum_subject s on s.id=a.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join assessment_question aq on aq.assessment_id=a.id
      where a.status='PUBLISHED' and (?='' or s.code=?)
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      group by a.id,s.code,s.display_name,c.code,ch.id,ch.code,ch.display_name,st.id
      order by coalesce(ch.teaching_order,ch.sort_order),a.published_at desc,a.id desc
      """, context.studentId(), subjectFilter, subjectFilter);
  }

  @PostMapping("/learning/assessments/{assessmentId}/attempts")
  @Transactional
  public Map<String, Object> startOrResumeAttempt(
      @PathVariable long assessmentId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> assessmentLock = jdbc.queryForList(
        "select id from assessment where id=? and status='PUBLISHED' for update", assessmentId);
    if (assessmentLock.isEmpty()) throw notFound("Published assessment", assessmentId);
    List<Map<String, Object>> assessmentRows = jdbc.queryForList("""
      select a.id,a.title,a.description,a.duration_minutes,a.max_attempts,a.status,
             s.id as subject_id,s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,ch.id as chapter_id,ch.display_name as chapter_name,
             count(aq.id) as question_count,coalesce(sum(aq.max_marks),0) as max_score
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=? and st.active=true and st.class_code=c.code
      join student_track_enrollment ste on ste.student_id=st.id and ste.subject_id=s.id and ste.status='ACTIVE'
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join assessment_question aq on aq.assessment_id=a.id
      where a.id=? and a.status='PUBLISHED'
        and (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      group by a.id,s.id,s.code,s.display_name,c.code,ch.id,ch.display_name
      """, context.studentId(), assessmentId);
    if (assessmentRows.isEmpty()) throw notFound("Published assessment", assessmentId);
    Map<String, Object> assessment = assessmentRows.getFirst();

    List<Map<String, Object>> existing = jdbc.queryForList("""
      select id from student_assessment_attempt
      where assessment_id=? and student_id=? and status='IN_PROGRESS'
      order by started_at desc limit 1 for update
      """, assessmentId, context.studentId());
    if (!existing.isEmpty()) {
      return attemptDetails(((Number) existing.getFirst().get("id")).longValue(), context, false);
    }
    Integer used = jdbc.queryForObject("""
      select count(*) from student_assessment_attempt
      where assessment_id=? and student_id=? and status<>'ABANDONED'
      """, Integer.class, assessmentId, context.studentId());
    int maxAttempts = ((Number) assessment.get("max_attempts")).intValue();
    if (used != null && used >= maxAttempts) throw conflict("You have used all attempts allowed for this test.");
    Long questionCount = ((Number) assessment.get("question_count")).longValue();
    if (questionCount < 1) throw badRequest("This test has no questions and cannot be started.");

    int attemptNumber = (used == null ? 0 : used) + 1;
    Map<String, Object> assessmentSnapshot = new LinkedHashMap<>();
    assessmentSnapshot.put("assessmentId", assessmentId);
    assessmentSnapshot.put("title", assessment.get("title"));
    assessmentSnapshot.put("description", assessment.get("description"));
    assessmentSnapshot.put("durationMinutes", assessment.get("duration_minutes"));
    assessmentSnapshot.put("attemptNumber", attemptNumber);
    assessmentSnapshot.put("questionCount", questionCount);
    assessmentSnapshot.put("maxScore", assessment.get("max_score"));
    assessmentSnapshot.put("subjectCode", assessment.get("subject_code"));
    assessmentSnapshot.put("subjectName", assessment.get("subject_name"));
    assessmentSnapshot.put("chapterName", assessment.get("chapter_name"));
    Long attemptId = jdbc.queryForObject("""
      insert into student_assessment_attempt(
        assessment_id,student_id,attempt_number,status,assessment_snapshot,
        started_at,deadline_at,max_score
      ) values(?,?,?,'IN_PROGRESS',?::jsonb,now(),now()+(? * interval '1 minute'),?) returning id
      """, Long.class, assessmentId, context.studentId(), attemptNumber, json(assessmentSnapshot),
        assessment.get("duration_minutes"), assessment.get("max_score"));

    jdbc.update("""
      insert into student_assessment_attempt_question(
        attempt_id,source_assessment_question_id,source_question_id,sequence_no,max_marks,question_snapshot
      )
      select ?,aq.id,aq.source_question_id,aq.sequence_no,aq.max_marks,aq.question_snapshot
      from assessment_question aq where aq.assessment_id=? order by aq.sequence_no
      """, attemptId, assessmentId);
    jdbc.update("""
      insert into student_assessment_answer(attempt_question_id,response_payload,answer_status)
      select id,'""'::jsonb,'NOT_ANSWERED'
      from student_assessment_attempt_question where attempt_id=?
      """, attemptId);
    audit(assessmentId, attemptId, null, context.studentId(), "STUDENT", "ATTEMPT_STARTED",
        null, "IN_PROGRESS", "Student started test attempt " + attemptNumber + ".");
    return attemptDetails(attemptId, context, false);
  }

  @GetMapping("/learning/assessment-attempts/{attemptId}")
  public Map<String, Object> studentAttempt(
      @PathVariable long attemptId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    return attemptDetails(attemptId, context, false);
  }

  @PutMapping("/learning/assessment-attempts/{attemptId}/answers/{attemptQuestionId}")
  @Transactional
  public Map<String, Object> saveAnswer(
      @PathVariable long attemptId,
      @PathVariable long attemptQuestionId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select att.id,att.status,att.deadline_at,att.student_id
      from student_assessment_attempt att join student_assessment_attempt_question aq on aq.attempt_id=att.id
      where att.id=? and aq.id=? for update of att
      """, attemptId, attemptQuestionId);
    if (rows.isEmpty() || ((Number) rows.getFirst().get("student_id")).longValue() != context.studentId()) {
      throw notFound("Assessment answer", attemptQuestionId);
    }
    Map<String, Object> attempt = rows.getFirst();
    if (!"IN_PROGRESS".equals(String.valueOf(attempt.get("status")))) {
      throw conflict("Submitted test answers are locked and can no longer be changed.");
    }
    OffsetDateTime deadline = offsetDateTime(attempt.get("deadline_at"));
    if (deadline != null && deadline.isBefore(OffsetDateTime.now())) {
      throw conflict("The test time has expired. Submit the attempt to finalize your saved answers.");
    }
    Object value = body.get("answer");
    if (value == null) value = "";
    String answerJson = json(value);
    jdbc.update("""
      update student_assessment_answer set response_payload=?::jsonb,saved_at=now()
      where attempt_question_id=? and exists(
        select 1 from student_assessment_attempt_question aq
        where aq.id=? and aq.attempt_id=?
      )
      """, answerJson, attemptQuestionId, attemptQuestionId, attemptId);
    return Map.of("saved", true, "attemptId", attemptId, "attemptQuestionId", attemptQuestionId);
  }

  @PostMapping("/learning/assessment-attempts/{attemptId}/submit")
  @Transactional
  public Map<String, Object> submitAttempt(
      @PathVariable long attemptId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> attemptRows = jdbc.queryForList("""
      select id,assessment_id,student_id,status,auto_score,max_score
      from student_assessment_attempt where id=? for update
      """, attemptId);
    if (attemptRows.isEmpty() || ((Number) attemptRows.getFirst().get("student_id")).longValue() != context.studentId()) {
      throw notFound("Assessment attempt", attemptId);
    }
    Map<String, Object> attempt = attemptRows.getFirst();
    String oldStatus = String.valueOf(attempt.get("status"));
    if ("RELEASED".equals(oldStatus)) return attemptDetails(attemptId, context, false);
    if (!"IN_PROGRESS".equals(oldStatus)) throw conflict("This test attempt has already been submitted.");
    long assessmentId = ((Number) attempt.get("assessment_id")).longValue();

    List<Map<String, Object>> answerRows = jdbc.queryForList("""
      select aq.id as attempt_question_id,aq.sequence_no,aq.max_marks,aq.question_snapshot::text as question_snapshot,
             ans.id as answer_id,ans.response_payload::text as response_payload
      from student_assessment_attempt_question aq
      join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where aq.attempt_id=? order by aq.sequence_no for update of ans
      """, attemptId);
    BigDecimal autoScore = BigDecimal.ZERO;
    boolean needsReview = false;
    for (Map<String, Object> row : answerRows) {
      Map<String, Object> snapshot = parseObject(String.valueOf(row.get("question_snapshot")));
      String questionType = String.valueOf(snapshot.getOrDefault("question_type", ""));
      String responseJson = String.valueOf(row.get("response_payload"));
      Object response = parseValue(responseJson);
      BigDecimal maxMarks = new BigDecimal(String.valueOf(row.get("max_marks")));
      String answerStatus;
      Boolean correct = null;
      BigDecimal awarded;
      if (AUTO_GRADED_TYPES.contains(questionType)) {
        String answerPayload = String.valueOf(snapshot.getOrDefault("answer_payload", "{}"));
        QuestionAnswerService.Evaluation evaluation = answerService.evaluate(answerPayload, response);
        correct = evaluation.correct();
        awarded = Boolean.TRUE.equals(correct) ? maxMarks : BigDecimal.ZERO;
        answerStatus = "AUTO_GRADED";
        autoScore = autoScore.add(awarded);
      } else if (isEmptyAnswer(response)) {
        correct = null;
        awarded = BigDecimal.ZERO;
        answerStatus = "GRADED";
      } else {
        correct = null;
        awarded = null;
        answerStatus = "PENDING_REVIEW";
        needsReview = true;
      }
      jdbc.update("""
        update student_assessment_answer set answer_status=?,is_correct=?,awarded_marks=?,
          teacher_feedback=null,graded_at=case when ? in ('AUTO_GRADED','GRADED') then now() else null end,
          graded_by_staff_id=null,saved_at=now()
        where id=?
        """, answerStatus, correct, awarded, answerStatus, row.get("answer_id"));
    }
    String next = needsReview ? "AWAITING_REVIEW" : "RELEASED";
    BigDecimal finalScore = needsReview ? null : autoScore;
    jdbc.update("""
      update student_assessment_attempt set status=?,submitted_at=now(),auto_score=?,final_score=?,
        graded_at=case when ?='RELEASED' then now() else null end,
        released_at=case when ?='RELEASED' then now() else null end,updated_at=now()
      where id=?
      """, next, autoScore, finalScore, next, next, attemptId);
    audit(assessmentId, attemptId, null, context.studentId(), "STUDENT", "ATTEMPT_SUBMITTED",
        "IN_PROGRESS", next, needsReview ? "Submitted; teacher review is required before the result is released."
            : "Submitted and automatically graded; result released.");
    if ("RELEASED".equals(next)) {
      audit(assessmentId, attemptId, null, context.studentId(), "STUDENT", "RESULT_AUTO_RELEASED",
          "IN_PROGRESS", "RELEASED", "Objective result released after automatic grading.");
    }
    return attemptDetails(attemptId, context, false);
  }

  @GetMapping("/learning/assessment-results")
  public List<Map<String, Object>> studentResults(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    return jdbc.queryForList("""
      select att.id as attempt_id,att.assessment_id,att.attempt_number,att.status,att.submitted_at,
             att.graded_at,att.released_at,att.final_score,att.max_score,
             a.title as assessment_title,s.code as subject_code,s.display_name as subject_name,
             ch.display_name as chapter_name,
             (select count(*) from student_assessment_attempt_question aq where aq.attempt_id=att.id) as question_count
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where att.student_id=? and att.status='RELEASED'
      order by att.released_at desc,att.id desc
      limit 200
      """, context.studentId());
  }

  // ---------- Shared private helpers ----------

  private Map<String, Object> attemptDetails(long attemptId, AuthContext context, boolean staffView) {
    List<Map<String, Object>> attempts = jdbc.queryForList("""
      select att.id as attempt_id,att.assessment_id,att.student_id,att.attempt_number,att.status,
             att.started_at,att.deadline_at,att.submitted_at,att.graded_at,att.released_at,
             att.auto_score,att.final_score,att.max_score,att.assessment_snapshot::text as assessment_snapshot,
             st.display_name as student_name,st.class_code,a.title as assessment_title,
             s.code as subject_code,s.display_name as subject_name,ch.display_name as chapter_name
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      join student st on st.id=att.student_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where att.id=?
      """, attemptId);
    if (attempts.isEmpty()) throw notFound("Assessment attempt", attemptId);
    Map<String, Object> attempt = new LinkedHashMap<>(attempts.getFirst());
    if (!staffView && (context == null || context.studentId() == null
        || ((Number) attempt.get("student_id")).longValue() != context.studentId())) {
      throw notFound("Assessment attempt", attemptId);
    }
    if (staffView) requireAssignment(context, ((Number) attempt.get("assessment_id")).longValue());
    boolean resultReleased = "RELEASED".equals(String.valueOf(attempt.get("status")));
    attempt.remove("student_id");
    if (!resultReleased) {
      attempt.remove("final_score");
      attempt.remove("auto_score");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select aq.id as attempt_question_id,aq.sequence_no,aq.max_marks,
             aq.question_snapshot::text as question_snapshot,
             ans.id as answer_id,ans.response_payload::text as response_payload,
             ans.answer_status,ans.is_correct,ans.awarded_marks,ans.teacher_feedback
      from student_assessment_attempt_question aq
      join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where aq.attempt_id=? order by aq.sequence_no
      """, attemptId);
    List<Map<String, Object>> questions = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> snapshot = parseObject(String.valueOf(row.get("question_snapshot")));
      Map<String, Object> item = publicSnapshot(snapshot);
      item.put("attemptQuestionId", row.get("attempt_question_id"));
      item.put("answerId", row.get("answer_id"));
      item.put("maxMarks", row.get("max_marks"));
      item.put("answer", parseValue(String.valueOf(row.get("response_payload")));
      item.put("answerStatus", row.get("answer_status"));
      if (resultReleased || staffView) {
        item.put("isCorrect", row.get("is_correct"));
        item.put("awardedMarks", row.get("awarded_marks"));
        item.put("teacherFeedback", row.get("teacher_feedback"));
      }
      if (staffView) {
        item.put("manualReviewRequired", "PENDING_REVIEW".equals(String.valueOf(row.get("answer_status"))));
      }
      questions.add(item);
    }
    attempt.put("questions", questions);
    attempt.put("resultReleased", resultReleased);
    if (staffView) attempt.put("audit", auditHistory(((Number) attempt.get("assessment_id")).longValue(), attemptId));
    return attempt;
  }

  private Map<String, Object> assessmentDetails(long id, boolean includeQuestionBankDetails) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select a.id as assessment_id,a.title,a.description,a.duration_minutes,a.max_attempts,a.status,
             a.created_at,a.published_at,s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where a.id=?
      """, id);
    if (rows.isEmpty()) throw notFound("Assessment", id);
    Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("question_count", jdbc.queryForObject("select count(*) from assessment_question where assessment_id=?", Integer.class, id));
    result.put("max_score", jdbc.queryForObject("select coalesce(sum(max_marks),0) from assessment_question where assessment_id=?", BigDecimal.class, id));
    if (includeQuestionBankDetails) {
      result.put("questions", jdbc.queryForList("""
        select id as assessment_question_id,source_question_id,sequence_no,max_marks
        from assessment_question where assessment_id=? order by sequence_no
        """, id));
      result.put("assigned_graders", jdbc.queryForList("""
        select asa.staff_id,s.display_name,s.role,asa.assigned_at
        from assessment_staff_assignment asa join staff_account s on s.id=asa.staff_id
        where asa.assessment_id=? and asa.active=true order by s.display_name
        """, id));
      result.put("audit", auditHistory(id, null));
    }
    return result;
  }

  private Map<String, Object> publicSnapshot(Map<String, Object> snapshot) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : List.of("id", "question_type", "prompt", "explanation", "difficulty",
        "sort_order", "marks", "exam_format", "topic", "subtopic", "skill")) {
      if (snapshot.containsKey(key)) result.put(key, snapshot.get(key));
    }
    Object rawOptions = snapshot.get("options");
    List<Map<String, Object>> options = new ArrayList<>();
    if (rawOptions instanceof List<?> entries) {
      for (Object raw : entries) {
        if (!(raw instanceof Map<?, ?> option)) continue;
        Map<String, Object> safe = new LinkedHashMap<>();
        Object key = option.get("option_key");
        if (key == null) key = option.get("key");
        Object label = option.get("label");
        if (label == null) label = option.get("label");
        safe.put("key", key);
        safe.put("label", label);
        options.add(safe);
      }
    }
    result.put("options", options);
    return result;
  }

  private void requireContentView(AuthContext context) {
    context = authorization.requireAuth(context);
    if (!authorization.hasPermission(context, "CONTENT_VIEW")
        && !authorization.hasPermission(context, "CONTENT_CREATE")
        && !authorization.hasPermission(context, "CONTENT_EDIT")
        && !authorization.hasPermission(context, "CONTENT_REVIEW")
        && !authorization.hasPermission(context, "ASSESSMENT_GRADE")) {
      throw new SecurityException("Content view permission required.");
    }
  }

  private AuthContext requireGrader(AuthContext context) {
    context = authorization.requireAuth(context);
    if (!context.isEmployee()) throw new SecurityException("Staff grading access required.");
    if (!context.isAdmin()) authorization.requirePermission(context, "ASSESSMENT_GRADE");
    return context;
  }

  private void requireAssignment(AuthContext context, long assessmentId) {
    if (context != null && context.isAdmin()) return;
    if (context == null || !context.isEmployee()) throw new SecurityException("Staff grading access required.");
    authorization.requirePermission(context, "ASSESSMENT_GRADE");
    Long assigned = jdbc.queryForObject("""
      select count(*) from assessment_staff_assignment asa
      join staff_account s on s.id=asa.staff_id and s.active=true
      where asa.assessment_id=? and asa.staff_id=? and asa.active=true
      """, Long.class, assessmentId, context.staffId());
    if (assigned == null || assigned == 0) throw new SecurityException("You are not assigned to review this assessment.");
  }

  private List<Map<String, Object>> auditHistory(long assessmentId, Long attemptId) {
    return jdbc.queryForList("""
      select al.id,al.assessment_id,al.attempt_id,al.actor_staff_id,al.actor_student_id,
             coalesce(staff.display_name,student.display_name,'System') as actor_name,
             al.actor_role,al.action,al.previous_status,al.new_status,al.note,al.occurred_at
      from assessment_audit_log al
      left join staff_account staff on staff.id=al.actor_staff_id
      left join student on student.id=al.actor_student_id
      where al.assessment_id=? and (?::bigint is null or al.attempt_id=?)
      order by al.occurred_at desc,al.id desc
      limit 500
      """, assessmentId, attemptId, attemptId);
  }

  private void audit(long assessmentId, Long attemptId, AuthContext staffContext,
      Long studentId, String fallbackRole, String action, String previousStatus, String newStatus, String note) {
    Long staffId = staffContext != null && staffContext.isEmployee() ? staffContext.staffId() : null;
    String role = staffContext != null && staffContext.role() != null
        ? staffContext.role() : fallbackRole == null ? "SYSTEM" : fallbackRole;
    jdbc.update("""
      insert into assessment_audit_log(
        assessment_id,attempt_id,actor_staff_id,actor_student_id,actor_role,action,
        previous_status,new_status,note,occurred_at
      ) values(?,?,?,?,?,?,?,?,?,now())
      """, assessmentId, attemptId, staffId, studentId, role, action, previousStatus, newStatus,
        note == null || note.isBlank() ? null : truncate(note, 1200));
  }

  private Map<String, Object> parseObject(String source) {
    try {
      JsonNode node = mapper.readTree(source);
      @SuppressWarnings("unchecked")
      Map<String, Object> map = mapper.convertValue(node, Map.class);
      return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    } catch (JacksonException ex) {
      throw new IllegalStateException("Stored question snapshot is invalid JSON.", ex);
    }
  }

  private Object parseValue(String source) {
    try {
      return mapper.readValue(source, Object.class);
    } catch (JacksonException ex) {
      return "";
    }
  }

  private OffsetDateTime offsetDateTime(Object value) {
    if (value instanceof OffsetDateTime dateTime) return dateTime;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
    if (value instanceof java.time.Instant instant) return instant.atOffset(java.time.ZoneOffset.UTC);
    if (value == null) return null;
    try { return OffsetDateTime.parse(String.valueOf(value)); }
    catch (RuntimeException ex) { return null; }
  }

  private boolean isEmptyAnswer(Object value) {
    if (value == null) return true;
    if (value instanceof String s) return s.isBlank();
    if (value instanceof List<?> list) return list.isEmpty() || list.stream().allMatch(this::isEmptyAnswer);
    if (value instanceof Map<?, ?> map) return map.isEmpty() || map.values().stream().allMatch(this::isEmptyAnswer);
    return false;
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException ex) { throw new IllegalStateException("Could not serialize assessment data.", ex); }
  }

  private String requiredText(Object value, String label, int max) {
    String text = safeText(value, max);
    if (text.isBlank()) throw badRequest(label + " is required.");
    return text;
  }

  private String safeText(Object value, int max) {
    String text = value == null ? "" : String.valueOf(value).trim();
    if (text.length() > max) text = text.substring(0, max);
    return text;
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
      long result = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
      if (result <= 0) throw badRequest(label + " must be a positive number.");
      return result;
    } catch (NumberFormatException ex) {
      throw badRequest(label + " must be a valid number.");
    }
  }

  private Long optionalLong(Object value, String label) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    return longValue(value, label);
  }

  private BigDecimal decimal(Object value, String label) {
    try {
      BigDecimal result = new BigDecimal(String.valueOf(value).trim());
      if (result.scale() > 2) throw badRequest(label + " can have at most 2 decimal places.");
      return result;
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

  private String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
