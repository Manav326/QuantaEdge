package com.quantaedge.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
  // Fill-in and written responses in formal tests always require a human check.
  // Keep objective choices and numeric questions eligible for automatic scoring.
  private static final Set<String> AUTO_GRADED_TYPES = Set.of("MCQ", "TRUE_FALSE", "NUMERICAL");
  private static final SecureRandom OFFLINE_TOKEN_RANDOM = new SecureRandom();

  /**
   * Shared gate for a new student attempt and catalogue visibility. An in-progress attempt is
   * deliberately handled separately so a student can resume a frozen paper after its source
   * lesson/chapter is revised or the old generated version is archived.
   */
  private static final String STUDENT_ASSESSMENT_ELIGIBILITY_SQL = """
      (ch.id is null or (ch.active=true and ch.content_status='PUBLISHED'))
      and (a.assessment_type<>'TOPIC_END' or (
        a.lesson_id is not null
        and exists(select 1 from lesson l where l.id=a.lesson_id and l.active=true and l.status='PUBLISHED')
        and exists(select 1 from student_lesson_progress tp
                   where tp.student_id=st.id and tp.institution_id=(select institution_id from tenant_scope)
                     and tp.lesson_id=a.lesson_id and tp.status='COMPLETED')
      ))
      and (a.assessment_type<>'CHAPTER_END' or (
        ch.id is not null
        and exists(select 1 from lesson required_lesson
                   where required_lesson.chapter_id=ch.id
                     and required_lesson.active=true and required_lesson.status='PUBLISHED')
        and not exists(select 1 from lesson required_lesson
                       where required_lesson.chapter_id=ch.id
                         and required_lesson.active=true and required_lesson.status='PUBLISHED'
                         and not exists(select 1 from student_lesson_progress cp
                                        where cp.student_id=st.id and cp.institution_id=(select institution_id from tenant_scope)
                                          and cp.lesson_id=required_lesson.id and cp.status='COMPLETED'))
      ))
      and (not a.is_generated or a.is_current=true)
      """;

  static boolean isAutoGradedType(String questionType) {
    return questionType != null && AUTO_GRADED_TYPES.contains(questionType.trim().toUpperCase(Locale.ROOT));
  }

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final QuestionHistoryService questionHistory;
  private final QuestionAnswerService answerService;
  private final StaffAuditService staffAudit;
  private final ObjectMapper mapper;
  private final AssessmentCatalogService assessmentCatalog;

  public AssessmentController(JdbcTemplate jdbc, AuthorizationService authorization,
      QuestionHistoryService questionHistory, QuestionAnswerService answerService,
      StaffAuditService staffAudit, ObjectMapper mapper,AssessmentCatalogService assessmentCatalog) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.questionHistory = questionHistory;
    this.answerService = answerService;
    this.staffAudit = staffAudit;
    this.mapper = mapper;
    this.assessmentCatalog=assessmentCatalog;
  }

  // ---------- Admin: assessment authoring and publication ----------

  @GetMapping("/admin/assessment-question-bank")
  public Map<String,Object> questionBank(
      @RequestParam String classCode,
      @RequestParam String subjectCode,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant,
      @RequestParam(required = false) Long chapterId,
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue="40") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    requireContentView(context);
    requireAssignedCourse(courseId, tenant);
    int rowLimit=Math.max(1,Math.min(limit==null?40:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String term=safeText(query,160);
    String chapterFilter=chapterId==null?"":" and ch.id=?";
    String courseFilter=courseId==null?"s.program_id is null":"exists (select 1 from curriculum_program_subject cps join curriculum_program scope_program on scope_program.id=cps.program_id and scope_program.status='ACTIVE' where cps.program_id=? and cps.subject_id=s.id and (s.program_id=cps.program_id or (s.program_id is null and scope_program.class_id=s.class_id and cps.class_id=s.class_id)))";
    String fromWhere="""
      from question q join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_program p on p.id=s.program_id
      where c.code=? and s.code=? and c.active=true and s.active=true
        and __COURSE_FILTER__
        and ch.active=true and ch.content_status='PUBLISHED'
        and l.active=true and l.status='PUBLISHED'
        and q.active=true and q.review_status in ('APPROVED','PUBLISHED')
        __CHAPTER_FILTER__
        and (?='' or q.prompt ilike ? or l.title ilike ? or ch.display_name ilike ?)
      """.replace("__CHAPTER_FILTER__",chapterFilter).replace("__COURSE_FILTER__",courseFilter);
    List<Object> filterArgs=new ArrayList<>(List.of(classCode.trim(),subjectCode.trim()));
    if(courseId!=null)filterArgs.add(courseId);
    if(chapterId!=null)filterArgs.add(chapterId);
    filterArgs.add(term);filterArgs.add("%"+term+"%");filterArgs.add("%"+term+"%");filterArgs.add("%"+term+"%");
    List<Object> pageArgs=new ArrayList<>(filterArgs);pageArgs.add(rowLimit);pageArgs.add(rowOffset);
    String selectSql="""
      select q.id as question_id,q.question_type,q.prompt,q.difficulty,q.marks,q.sort_order,
             l.id as lesson_id,l.title as lesson_title,ch.id as chapter_id,
             ch.code as chapter_code,ch.display_name as chapter_name,
             s.code as subject_code,s.display_name as subject_name,c.code as class_code,
             p.id as course_id,p.code as course_code,p.display_name as course_name
      """;
    List<Map<String,Object>> items=jdbc.queryForList(selectSql+fromWhere+
      " order by coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id,q.sort_order,q.id limit ? offset ?",pageArgs.toArray());
    Long total=jdbc.queryForObject("select count(*) "+fromWhere,Long.class,filterArgs.toArray());
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @GetMapping("/admin/assessments")
  public Map<String,Object> adminAssessments(
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestParam Long courseId,
      @RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    requireContentView(context);
    requireAssignedCourse(courseId, tenant);
    String classFilter=safeText(classCode,30);
    String subjectFilter=safeText(subjectCode,40);
    if(!classFilter.isBlank() && courseId==null)assessmentCatalog.ensureGeneratedAssessmentsForClass(classFilter);
    if(courseId!=null) {
      Long enrolledProgram=jdbc.queryForObject(
          "select count(*) from curriculum_program where id=? and status='ACTIVE' and code<>'GENERAL'",
          Long.class,courseId);
      if(enrolledProgram==null || enrolledProgram==0) throw badRequest("Choose an active course.");
    }
    String statusFilter=safeText(status,20).toUpperCase(Locale.ROOT);
    if(statusFilter.isEmpty())statusFilter="ALL";
    if(!Set.of("ALL","DRAFT","PUBLISHED","ARCHIVED").contains(statusFilter))throw badRequest("Choose a valid assessment status.");
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String courseFilter="a.course_id=?";
    String fromJoins="""
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_program p on p.id=a.course_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      """;
    String fromWhere="""
      where (?='' or c.code=?) and (?='' or s.code=?)
        and __COURSE_FILTER__
        and (?='ALL' or a.status=?)
        and (?=true or exists(select 1 from assessment_staff_assignment asa
          where asa.assessment_id=a.id and asa.staff_id=? and asa.active=true))
      """.replace("__COURSE_FILTER__",courseFilter);
    List<Object> filterArgs=new ArrayList<>(List.of(classFilter,classFilter,subjectFilter,subjectFilter));
    if(courseId!=null)filterArgs.add(courseId);
    filterArgs.addAll(List.of(statusFilter,statusFilter,context.isAdmin(),context.staffId()));
    List<Object> pageArgs=new ArrayList<>(filterArgs);
    pageArgs.add(rowLimit);pageArgs.add(rowOffset);
    List<Map<String,Object>> items=jdbc.queryForList("""
      select a.id as assessment_id,a.public_id as test_id,a.title,a.description,a.duration_minutes,a.max_attempts,a.status,
             a.offline_enabled,a.assessment_type,a.lesson_id,a.is_generated,a.is_current,a.generation_version,
             a.negative_marking_fraction,a.created_at,a.published_at,c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             p.id as course_id,p.code as course_code,p.display_name as course_name,
             ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name,
             (select count(*) from assessment_question aq where aq.assessment_id=a.id) as question_count,
             (select coalesce(sum(case when aq.question_snapshot->>'section_result_policy' in ('QUALIFYING_ONLY','PRACTICE_ONLY')
                         or aq.question_snapshot->>'section_code'='LANGUAGE_QUALIFYING' then 0 else aq.max_marks end),0)
              from assessment_question aq where aq.assessment_id=a.id) as max_score,
             (select string_agg(gs.display_name, ', ' order by gs.display_name)
              from assessment_staff_assignment asa join staff_account gs on gs.id=asa.staff_id
              where asa.assessment_id=a.id and asa.active=true) as assigned_grader_names,
             count(distinct att.id) filter (where att.status<>'ABANDONED') as attempt_count,
             count(distinct att.id) filter (where att.status='AWAITING_REVIEW') as review_count
      """+fromJoins+"""
      left join student_assessment_attempt att on att.assessment_id=a.id
      """+fromWhere+"""
      group by a.id,c.code,c.display_name,s.code,s.display_name,p.id,p.code,p.display_name,ch.id,ch.code,ch.display_name
      order by a.created_at desc,a.id desc
      limit ? offset ?
      """,pageArgs.toArray());
    Long total=jdbc.queryForObject("select count(distinct a.id) "+fromJoins+fromWhere,Long.class,filterArgs.toArray());
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @PostMapping("/admin/assessments")
  @Transactional
  public Map<String, Object> createAssessment(
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context = authorization.requirePermission(context, "CONTENT_CREATE");
    String title = requiredText(body.get("title"), "Assessment title", 240);
    String description = safeText(body.get("description"), 1200);
    String classCode = requiredText(body.get("classCode"), "Class", 30);
    String subjectCode = requiredText(body.get("subjectCode"), "Subject", 40);
    Long courseId = optionalLong(body.get("courseId"), "courseId");
    if (courseId == null || courseId < 1L) {
      throw badRequest("Select an institution-assigned course before creating an assessment.");
    }
    requireAssignedCourse(courseId, tenant);
    int duration = integer(body.getOrDefault("durationMinutes", 45), "Duration", 1, 240);
    int maxAttempts = integer(body.getOrDefault("maxAttempts", 1), "Attempt limit", 1, 10);
    BigDecimal negativeMarkingFraction = decimal(body.getOrDefault("negativeMarkingFraction", "0"), "Negative-marking fraction");
    if (negativeMarkingFraction.compareTo(BigDecimal.ZERO) < 0
        || negativeMarkingFraction.compareTo(BigDecimal.ONE) > 0) {
      throw badRequest("Negative-marking fraction must be between 0 and 1.");
    }
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
      select s.id from curriculum_subject s
      join curriculum_class c on c.id=s.class_id
      join curriculum_program p on p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      join curriculum_program_subject ps on ps.program_id=p.id and ps.class_id=c.id and ps.subject_id=s.id
      where c.code=? and s.code=? and c.active=true and s.active=true
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
      """, rs -> rs.next() ? rs.getLong(1) : null, courseId, classCode, subjectCode);
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
      insert into assessment(course_id,title,description,subject_id,chapter_id,duration_minutes,max_attempts,
        negative_marking_fraction,status,created_by_staff_id)
      values(?,?,?,?,?,?,?,?, 'DRAFT',?) returning id
      """, Long.class, courseId, title, description.isBlank() ? null : description, subjectId, chapterId,
        duration, maxAttempts, negativeMarkingFraction, context.staffId());

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
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context = authorization.requireAuth(context);
    requireAssessmentInCourse(assessmentId,courseId,tenant);
    String next = requiredText(body.get("status"), "Status", 20).toUpperCase(Locale.ROOT);
    if (!Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(next)) throw badRequest("Choose DRAFT, PUBLISHED, or ARCHIVED.");
    if ("PUBLISHED".equals(next) || "ARCHIVED".equals(next)) authorization.requirePermission(context, "CONTENT_PUBLISH");
    else authorization.requirePermission(context, "CONTENT_EDIT");
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select a.id,a.title,a.status,a.is_generated,a.subject_id,a.chapter_id,
             s.code as subject_code,c.code as class_code,
             count(aq.id) as question_count
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      left join assessment_question aq on aq.assessment_id=a.id
      where a.id=? and a.course_id=? group by a.id,s.code,c.code
      """, assessmentId,courseId);
    if (rows.isEmpty()) throw notFound("Assessment", assessmentId);
    Map<String, Object> row = rows.getFirst();
    String before = String.valueOf(row.get("status"));
    if ("PUBLISHED".equals(next) && ((Number) row.get("question_count")).intValue() < 1) {
      throw badRequest("Add at least one question before publishing this test.");
    }
    if (Boolean.TRUE.equals(row.get("is_generated"))) {
      throw badRequest("System-generated tests are governed by published lesson content and cannot be changed manually.");
    }
    if (before.equals(next)) return assessmentDetails(assessmentId, true);
    boolean allowedTransition = switch (before) {
      case "DRAFT" -> "PUBLISHED".equals(next);
      case "PUBLISHED" -> "ARCHIVED".equals(next);
      case "ARCHIVED" -> "DRAFT".equals(next);
      default -> false;
    };
    if (!allowedTransition) {
      throw badRequest("Use the supported test lifecycle: Draft → Published → Archived → Draft. Restore an archived test to Draft before republishing it.");
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

  /** Offline delivery is an explicit editorial choice for low-stakes tests; default remains online-only. */
  @PatchMapping("/admin/assessments/{assessmentId}/offline-policy")
  @Transactional
  public Map<String,Object> updateOfflinePolicy(
      @PathVariable long assessmentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context=authorization.requireAuth(context);
    requireAssessmentInCourse(assessmentId,courseId,tenant);
    authorization.requirePermission(context,"CONTENT_EDIT");
    Object raw=body.get("offlineEnabled");
    if(!(raw instanceof Boolean enabled)) throw badRequest("offlineEnabled must be true or false.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,title,status,is_generated,offline_enabled
      from assessment where id=? and course_id=?
      for update
      """,assessmentId,courseId);
    if(rows.isEmpty()) throw notFound("Assessment",assessmentId);
    Map<String,Object> row=rows.getFirst();
    boolean previous=Boolean.TRUE.equals(row.get("offline_enabled"));
    if(Boolean.TRUE.equals(row.get("is_generated")) && enabled) {
      throw badRequest("System-generated topic/chapter tests stay online-only. Only manually authored assessments can be approved for offline use.");
    }
    if(previous==enabled) return assessmentDetails(assessmentId,true);
    jdbc.update("update assessment set offline_enabled=?,updated_at=now() where id=? and course_id=?",enabled,assessmentId,courseId);
    String next=enabled?"ENABLED":"DISABLED";
    audit(assessmentId,null,context,null,null,"OFFLINE_POLICY_CHANGED",
        previous?"ENABLED":"DISABLED",next,
        enabled?"Staff explicitly approved protected offline delivery for this manually authored assessment."
            :"Staff disabled protected offline delivery for this assessment.");
    staffAudit.recordAction(context,"/api/v1/admin/assessments/"+assessmentId+"/offline-policy",
        "Changed protected offline assessment delivery from "+(previous?"enabled":"disabled")+" to "+next+".");
    return assessmentDetails(assessmentId,true);
  }

  @PostMapping("/admin/assessments/{assessmentId}/graders")
  @Transactional
  public Map<String, Object> assignGrader(
      @PathVariable long assessmentId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    requireAssessmentInCourse(assessmentId,courseId,tenant);
    long staffId = longValue(body.get("staffId"), "Staff ID");
    Long valid = jdbc.queryForObject("""
      select count(*) from staff_account s
      join staff_permission_grant g on g.staff_id=s.id and g.permission_key='ASSESSMENT_GRADE'
      where s.id=? and s.active=true
      """, Long.class, staffId);
    if (valid == null || valid == 0) throw badRequest("Choose an active staff member with the assessment grading permission.");
    Long assessmentCount = jdbc.queryForObject("select count(*) from assessment where id=? and course_id=?", Long.class, assessmentId,courseId);
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
  public Map<String,Object> assessmentReviews(
      @RequestParam Long courseId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context=requireGrader(context);
    requireAssignedCourse(courseId, tenant);
    String statusFilter=safeText(status,24).toUpperCase(Locale.ROOT);
    if(statusFilter.isEmpty())statusFilter="AWAITING_REVIEW";
    if(!Set.of("ALL","AWAITING_REVIEW","GRADED","RELEASED").contains(statusFilter))throw badRequest("Choose a valid test review status.");
    String term=safeText(query,160);
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String filterSql="""
      where a.course_id=? and att.institution_id=?
        and exists(select 1 from curriculum_program_subject cps
          join curriculum_program scope_program on scope_program.id=cps.program_id
          where cps.program_id=? and cps.subject_id=s.id and cps.class_id=s.class_id
            and scope_program.status='ACTIVE' and scope_program.code<>'GENERAL'
            and (s.program_id=scope_program.id or (s.program_id is null and scope_program.class_id=s.class_id)))
        and (?='ALL' or att.status=?)
        and (?='' or a.title ilike ? or st.display_name ilike ? or ch.display_name ilike ?)
        and (?=true or exists(select 1 from assessment_staff_assignment asa
          where asa.assessment_id=a.id and asa.staff_id=? and asa.active=true))
      """;
    Object[] filterArgs={courseId,tenant.institutionId(),courseId,statusFilter,statusFilter,term,"%"+term+"%","%"+term+"%","%"+term+"%",context.isAdmin(),context.staffId()};
    List<Object> pageArgs=new ArrayList<>();pageArgs.add(context.isAdmin());pageArgs.addAll(Arrays.asList(filterArgs));pageArgs.add(rowLimit);pageArgs.add(rowOffset);
    String fromJoins="""
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=att.student_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      """;
    String selectSql="""
      select att.id as attempt_id,att.assessment_id,att.attempt_number,att.status,att.started_at,
             att.submitted_at,att.graded_at,att.released_at,att.auto_score,att.final_score,att.max_score,
             a.title as assessment_title,a.duration_minutes,
             st.display_name as student_name,st.id as student_id,st.class_code,
             s.code as subject_code,s.display_name as subject_name,
             ch.display_name as chapter_name,
             count(distinct aq.id) as question_count,
             count(distinct ans.id) filter (where ans.answer_status='PENDING_REVIEW') as pending_answers,
             case when ? then true else false end as can_review
      """;
    String reviewJoins=" left join student_assessment_attempt_question aq on aq.attempt_id=att.id "+
      "left join student_assessment_answer ans on ans.attempt_question_id=aq.id ";
    String reviewTail=" group by att.id,a.title,a.duration_minutes,st.display_name,st.id,st.class_code,"+
      "s.code,s.display_name,ch.display_name order by case when att.status='AWAITING_REVIEW' then 0 else 1 end,"+
      "att.submitted_at asc,att.id limit ? offset ?";
    List<Map<String,Object>> items=jdbc.queryForList(selectSql+fromJoins+reviewJoins+filterSql+reviewTail,pageArgs.toArray());
    Long total=jdbc.queryForObject("select count(*) "+fromJoins+filterSql,Long.class,filterArgs);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  @GetMapping("/admin/assessment-attempts/{attemptId}")
  public Map<String, Object> reviewAttempt(
      @PathVariable long attemptId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context = requireGrader(context);
    requireAttemptInCourse(attemptId,courseId,tenant);
    Map<String, Object> attempt = attemptDetails(attemptId, context, true);
    attempt.put("audit", auditHistory(((Number) attempt.get("assessment_id")).longValue(), attemptId));
    return attempt;
  }

  @PostMapping("/admin/assessment-attempts/{attemptId}/grade")
  @Transactional
  public Map<String, Object> gradeAttempt(
      @PathVariable long attemptId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context = requireGrader(context);
    requireAttemptInCourse(attemptId,courseId,tenant);
    List<Map<String, Object>> attempts = jdbc.queryForList("""
      select id,assessment_id,status,auto_score,max_score
      from student_assessment_attempt where id=? and institution_id=? for update
      """, attemptId,tenant.institutionId());
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
      select greatest(0::numeric,coalesce(sum(case
        when coalesce(aq.question_snapshot->>'section_result_policy','SCORED') in ('QUALIFYING_ONLY','PRACTICE_ONLY')
          or coalesce(aq.question_snapshot->>'section_code','')='LANGUAGE_QUALIFYING' then 0
        else coalesce(ans.awarded_marks,0)-coalesce(ans.penalty_marks,0) end),0))
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
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestParam Long courseId,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false) InstitutionTenantContext tenant) {
    context = authorization.requireAuth(context);
    requireAssessmentInCourse(assessmentId,courseId,tenant);
    if (context.isEmployee()) {
      if (!context.isAdmin()) requireAssignment(context, assessmentId);
    } else {
      throw new SecurityException("Staff access required.");
    }
    return auditHistory(assessmentId, null);
  }

  // ---------- Student: catalogue, durable attempts, persisted results ----------

  @GetMapping("/learning/assessments")
  public Map<String,Object> studentAssessmentCatalog(
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) Long courseId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam(defaultValue="24") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    language = normalizeLanguage(language);
    String subjectFilter = safeText(subjectCode, 40);
    if(courseId==null || courseId<=0L) throw badRequest("Select an active course before accessing tests and results.");
    Long active=jdbc.queryForObject("""
      select count(*) from student_program_enrollment spe
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
      join institution_course_release r on r.id=a.course_release_id and r.program_id=p.id
      where spe.student_id=? and spe.institution_id=? and spe.program_id=? and spe.status='ACTIVE'
      """,Long.class,context.studentId(),institutionId,courseId);
    if(active==null || active==0) throw new SecurityException("Active enrolment in this course is required.");
    int rowLimit=Math.max(1,Math.min(limit==null?24:limit,60));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String courseScope="""
        exists(select 1 from curriculum_program_subject cps
          join curriculum_program selected_course on selected_course.id=cps.program_id and selected_course.status='ACTIVE'
          join institution_course_assignment course_assignment
            on course_assignment.institution_id=(select institution_id from tenant_scope)
            and course_assignment.status='ASSIGNED'
          join institution_course_release course_release on course_release.id=course_assignment.course_release_id
            and course_release.program_id=selected_course.id
          where cps.program_id=? and cps.subject_id=s.id and cps.class_id=s.class_id
            and (s.program_id=cps.program_id
              or (s.program_id is null and selected_course.class_id=s.class_id)))
        and exists(select 1 from student_program_enrollment selected_enrollment
          where selected_enrollment.student_id=st.id
            and selected_enrollment.institution_id=(select institution_id from tenant_scope)
            and selected_enrollment.program_id=? and selected_enrollment.status='ACTIVE')
        """;
    String sql= ("""
      with tenant_scope as (select ?::bigint as institution_id)
      select a.id as assessment_id,a.public_id as test_id,coalesce(max(at.title),a.title) as title,
             coalesce(max(at.description),a.description) as description,a.duration_minutes,a.max_attempts,
             a.assessment_type,a.lesson_id,a.is_generated,a.generation_version,a.offline_enabled,
             a.created_at,a.published_at,s.code as subject_code,coalesce(max(subject_tx.display_name),s.display_name) as subject_name,
             c.code as class_code,p.id as course_id,p.code as course_code,p.display_name as course_name,
             ch.id as chapter_id,ch.code as chapter_code,coalesce(max(ct.display_name),ch.display_name) as chapter_name,
             count(distinct aq.id) as question_count,
             coalesce(sum(case when aq.question_snapshot->>'section_result_policy' in ('QUALIFYING_ONLY','PRACTICE_ONLY')
                        or aq.question_snapshot->>'section_code'='LANGUAGE_QUALIFYING' then 0 else aq.max_marks end),0) as max_score,
             (select count(*) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.institution_id=(select institution_id from tenant_scope)
                and att.status<>'ABANDONED') as attempts_used,
             (select max(att.status) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.institution_id=(select institution_id from tenant_scope)
                and att.status='IN_PROGRESS') as in_progress_status,
             (select max(att.id) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.institution_id=(select institution_id from tenant_scope)
                and att.status='IN_PROGRESS') as in_progress_attempt_id,
             (select max(att.id) from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.institution_id=(select institution_id from tenant_scope)
                and att.status='RELEASED') as latest_result_attempt_id,
             (select att.offline_mode from student_assessment_attempt att
              where att.assessment_id=a.id and att.student_id=st.id and att.institution_id=(select institution_id from tenant_scope)
                and att.status='IN_PROGRESS' order by att.started_at desc,att.id desc limit 1) as in_progress_offline_mode
      from assessment a join curriculum_subject s on s.id=a.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      left join curriculum_program p on p.id=a.course_id
      join student st on st.id=? and st.active=true
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join assessment_question aq on aq.assessment_id=a.id
      left join lateral (
        select t.id,t.title,t.description from assessment_translation t
        where t.assessment_id=a.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',a.title,'description',a.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) at on true
      left join lateral (
        select t.id,t.display_name from curriculum_subject_translation t
        where t.subject_id=s.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        order by t.version_no desc,t.id desc limit 1
      ) subject_tx on true
      left join lateral (
        select t.id,t.display_name from curriculum_chapter_translation t
        where t.chapter_id=ch.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ct on true
      where (a.status='PUBLISHED' or exists(
               select 1 from student_assessment_attempt resumable
               where resumable.assessment_id=a.id and resumable.student_id=st.id
                 and resumable.institution_id=(select institution_id from tenant_scope)
                 and resumable.status='IN_PROGRESS'
             ))
        and (?='' or s.code=?)
        and __COURSE_SCOPE__
        and a.course_id=?
        and (
          exists(select 1 from student_assessment_attempt resumable
                 where resumable.assessment_id=a.id and resumable.student_id=st.id
                   and resumable.institution_id=(select institution_id from tenant_scope)
                   and resumable.status='IN_PROGRESS')
          or (
            """ + STUDENT_ASSESSMENT_ELIGIBILITY_SQL + """
          )
        )
      group by a.id,s.code,s.display_name,c.code,p.id,p.code,p.display_name,ch.id,ch.code,ch.display_name,st.id
      order by case a.assessment_type when 'TOPIC_END' then 1 when 'CHAPTER_END' then 2 else 3 end,
               coalesce(ch.teaching_order,ch.sort_order),a.published_at desc,a.id desc
      limit ? offset ?
      """).replace("__COURSE_SCOPE__",courseScope);
    List<Object> args=new ArrayList<>(List.of(institutionId,context.studentId(),language,language,language,subjectFilter,subjectFilter));
    args.add(courseId);args.add(courseId);args.add(courseId);
     args.add(rowLimit+1);args.add(rowOffset);
    List<Map<String,Object>> items=jdbc.queryForList(sql,args.toArray());
    boolean hasMore=items.size()>rowLimit;
    if(hasMore)items.remove(items.size()-1);
    return Map.of("items",items,"hasMore",hasMore,"limit",rowLimit,"offset",rowOffset);
  }

  public Map<String,Object> studentAssessmentCatalog(String subjectCode,String language,Integer limit,Integer offset,AuthContext context) {
    throw missingInstitutionContext();
  }

  /** Legacy Java signature retained for source compatibility; public routes must provide tenant. */
  public Map<String,Object> studentAssessmentCatalog(String subjectCode,Long courseId,String language,
      Integer limit,Integer offset,AuthContext context) {
    throw missingInstitutionContext();
  }

  @GetMapping("/learning/assessments/{assessmentId}/leaderboard")
  public Map<String, Object> assessmentLeaderboard(
      @PathVariable long assessmentId,
      @RequestParam Long courseId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    language = normalizeLanguage(language);
    if(courseId==null || courseId<1L) throw badRequest("Select an enrolled course before opening this leaderboard.");
    Long activeCourse=jdbc.queryForObject("""
      select count(*) from student_program_enrollment spe
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_membership m on m.student_id=spe.student_id and m.institution_id=spe.institution_id and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join institution_course_assignment assignment on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
      where spe.student_id=? and spe.institution_id=? and spe.program_id=? and spe.status='ACTIVE'
    """,Long.class,context.studentId(),institutionId,courseId);
    if(activeCourse==null || activeCourse==0L) throw new SecurityException("Active enrolment in the selected course is required.");

    // Leaderboards are scoped to the selected course and its published assessments.
    Long eligible = jdbc.queryForObject("""
      with tenant_scope as (select ?::bigint as institution_id)
      select count(*)
      from assessment a
      join curriculum_subject s on s.id=a.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true
      join curriculum_program_subject course_subject on course_subject.program_id=?
        and course_subject.subject_id=s.id and course_subject.class_id=s.class_id
      join curriculum_program selected_course on selected_course.id=course_subject.program_id
        and selected_course.status='ACTIVE' and selected_course.code<>'GENERAL'
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where a.id=? and a.course_id=?
         and (s.program_id=selected_course.id or (s.program_id is null and selected_course.class_id=s.class_id))
        and (a.status='PUBLISHED' or exists(
          select 1 from student_assessment_attempt resumable
          where resumable.assessment_id=a.id and resumable.student_id=st.id
            and resumable.institution_id=(select institution_id from tenant_scope)
            and resumable.status in ('IN_PROGRESS','AWAITING_REVIEW','GRADED','RELEASED')
        ))
        and (
          exists(select 1 from student_assessment_attempt resumable
                 where resumable.assessment_id=a.id and resumable.student_id=st.id
                   and resumable.institution_id=(select institution_id from tenant_scope)
                   and resumable.status in ('IN_PROGRESS','AWAITING_REVIEW','GRADED','RELEASED'))
          or (
      """ + STUDENT_ASSESSMENT_ELIGIBILITY_SQL + """
          )
        )
      """, Long.class, institutionId,context.studentId(),courseId,assessmentId,courseId);
    if (eligible == null || eligible == 0) {
      // Do not reveal whether another class's assessment exists.
      throw notFound("Assessment", assessmentId);
    }

    List<Map<String, Object>> metadata = jdbc.queryForList("""
      select a.id as assessment_id,
             coalesce(max(translated.title), a.title) as assessment_title,
             coalesce(sum(case when aq.question_snapshot->>'section_result_policy' in ('QUALIFYING_ONLY','PRACTICE_ONLY')
                       or aq.question_snapshot->>'section_code'='LANGUAGE_QUALIFYING' then 0 else aq.max_marks end), 0) as max_score
      from assessment a
      left join assessment_question aq on aq.assessment_id=a.id
      left join lateral (
        select t.title
        from assessment_translation t
        where t.assessment_id=a.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',a.title,'description',a.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) translated on true
      where a.id=?
      group by a.id
      """, language, assessmentId);
    if (metadata.isEmpty()) throw notFound("Assessment", assessmentId);

    List<Map<String, Object>> rankedRows = jdbc.queryForList("""
      with tenant_scope as (select ?::bigint as institution_id),
      released_attempts as (
        select att.id as attempt_id, att.student_id,
               coalesce(nullif(btrim(st.display_name), ''), 'Student') as student_name,
               att.final_score as score, att.max_score,
               greatest(0, floor(extract(epoch from (att.submitted_at - att.started_at))))::bigint
                 as time_taken_seconds,
               att.submitted_at
        from student_assessment_attempt att
         join student st on st.id=att.student_id and st.active=true
         join student_program_enrollment spe on spe.student_id=att.student_id
           and spe.institution_id=(select institution_id from tenant_scope)
           and spe.program_id=? and spe.status='ACTIVE'
         join institution_course_assignment course_assignment
           on course_assignment.institution_id=spe.institution_id and course_assignment.status='ASSIGNED'
         join institution_course_release course_release
           on course_release.id=course_assignment.course_release_id and course_release.program_id=spe.program_id
         where att.assessment_id=? and att.institution_id=(select institution_id from tenant_scope)
          and att.status='RELEASED'
          and att.final_score is not null
          and att.submitted_at is not null
          and att.started_at is not null
      ),
      personal_attempts as (
        select released_attempts.*,
               row_number() over (
                 partition by student_id
                 order by score desc,time_taken_seconds asc,submitted_at asc,attempt_id asc
               ) as personal_rank
        from released_attempts
      ),
      best_attempts as (
        select attempt_id,student_id,student_name,score,max_score,time_taken_seconds,submitted_at
        from personal_attempts
        where personal_rank=1
      ),
      ranked as (
        select row_number() over (
                 order by score desc,time_taken_seconds asc,submitted_at asc,
                          lower(student_name) asc,student_id asc
               ) as leaderboard_rank,
               count(*) over() as participant_count,
               student_id,student_name,score,max_score,time_taken_seconds,submitted_at
        from best_attempts
      )
      select leaderboard_rank,participant_count,student_id,student_name,
             score,max_score,time_taken_seconds,submitted_at
      from ranked
      where leaderboard_rank<=20 or student_id=?
      order by leaderboard_rank
      """, institutionId,courseId,assessmentId, context.studentId());

    List<Map<String, Object>> items = new ArrayList<>();
    Map<String, Object> currentStudent = null;
    long participantCount = 0;
    for (Map<String, Object> row : rankedRows) {
      Object rawCount = row.get("participant_count");
      if (rawCount instanceof Number number) participantCount = number.longValue();
      int rank = ((Number) row.get("leaderboard_rank")).intValue();
      long studentId = ((Number) row.get("student_id")).longValue();
      boolean isCurrentStudent = studentId == context.studentId();

      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("rank", rank);
      entry.put("student_name", row.get("student_name"));
      entry.put("score", row.get("score"));
      entry.put("max_score", row.get("max_score"));
      entry.put("time_taken_seconds", row.get("time_taken_seconds"));
      entry.put("is_current_student", isCurrentStudent);
      if (isCurrentStudent) currentStudent = entry;
      if (rank <= 20) items.add(entry);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Object> test = metadata.getFirst();
    result.put("assessment_id", assessmentId);
    result.put("assessment_title", test.get("assessment_title"));
    result.put("max_score", test.get("max_score"));
    result.put("participant_count", participantCount);
    result.put("items", items);
    result.put("current_student", currentStudent);
    result.put("ranking_criteria", List.of("Marks: highest first", "Time taken: shortest first"));
    return result;
  }

  @PostMapping("/learning/assessments/{assessmentId}/attempts")
  @Transactional
  public Map<String, Object> startOrResumeAttempt(
      @PathVariable long assessmentId,
      @RequestParam Long courseId,
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam(defaultValue = "false") boolean offlineMode,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    return startOrResumeAttemptInternal(assessmentId,courseId,language,offlineMode,context,requireInstitution(tenant));
  }

  Map<String,Object> startOrResumeAttempt(long assessmentId,String language,AuthContext context) {
    throw missingInstitutionContext();
  }

  private Map<String,Object> startOrResumeAttemptInternal(
      long assessmentId,Long courseId,String language,boolean offlineMode,AuthContext context,long institutionId) {
    context = authorization.requireStudent(context);
    language = normalizeLanguage(language);
    if(courseId==null || courseId<1L) throw badRequest("Select an enrolled course before starting this test.");
    List<Map<String,Object>> scopedCourse=jdbc.queryForList("""
      select p.id,p.display_name
      from student_program_enrollment spe
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_membership m on m.student_id=spe.student_id and m.institution_id=spe.institution_id and m.status='ACTIVE'
      join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
      join institution_course_assignment assignment on assignment.institution_id=spe.institution_id and assignment.status='ASSIGNED'
      join institution_course_release release on release.id=assignment.course_release_id and release.program_id=p.id
      where spe.student_id=? and spe.institution_id=? and spe.program_id=? and spe.status='ACTIVE'
    """,context.studentId(),institutionId,courseId);
    if(scopedCourse.isEmpty()) throw new SecurityException("Active enrolment in the selected institution course is required.");
    Long assessmentInCourse=jdbc.queryForObject("""
      select count(*)
      from assessment a
      join curriculum_subject s on s.id=a.subject_id and s.active=true
      join curriculum_program_subject cps on cps.program_id=? and cps.subject_id=s.id and cps.class_id=s.class_id
      join curriculum_program p on p.id=cps.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      where a.id=? and a.course_id=? and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
    """,Long.class,courseId,assessmentId,courseId);
    if(assessmentInCourse==null || assessmentInCourse==0L) throw notFound("Available assessment in the selected course",assessmentId);
    List<Map<String, Object>> assessmentLock = jdbc.queryForList("""
      with tenant_scope as (select ?::bigint as institution_id)
      select a.id from assessment a
      where a.id=? and a.course_id=? and (
        a.status='PUBLISHED'
        or exists(select 1 from student_assessment_attempt resumable
                  where resumable.assessment_id=a.id and resumable.student_id=?
                    and resumable.institution_id=(select institution_id from tenant_scope)
                    and resumable.status='IN_PROGRESS')
      )
      for update
      """, institutionId,assessmentId,courseId,context.studentId());
    if (assessmentLock.isEmpty()) throw notFound("Available assessment", assessmentId);
    List<Map<String, Object>> assessmentRows = jdbc.queryForList("""
      with tenant_scope as (select ?::bigint as institution_id)
      select a.id,coalesce(max(at.title),a.title) as title,
             coalesce(max(at.description),a.description) as description,
             a.duration_minutes,a.max_attempts,a.status,a.offline_enabled,a.negative_marking_fraction,
             s.id as subject_id,s.code as subject_code,coalesce(max(subject_tx.display_name),s.display_name) as subject_name,
             c.code as class_code,ch.id as chapter_id,coalesce(max(ct.display_name),ch.display_name) as chapter_name,
             selected_course.id as course_id,selected_course.code as course_code,selected_course.display_name as course_name,
             count(aq.id) as question_count,
             coalesce(sum(case when aq.question_snapshot->>'section_result_policy' in ('QUALIFYING_ONLY','PRACTICE_ONLY')
                       or aq.question_snapshot->>'section_code'='LANGUAGE_QUALIFYING' then 0 else aq.max_marks end),0) as max_score
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=? and st.active=true
      join curriculum_program_subject course_subject on course_subject.program_id=?
        and course_subject.subject_id=s.id and course_subject.class_id=s.class_id
      join curriculum_program selected_course on selected_course.id=course_subject.program_id
        and selected_course.status='ACTIVE' and selected_course.code<>'GENERAL'
      left join curriculum_chapter ch on ch.id=a.chapter_id
      left join assessment_question aq on aq.assessment_id=a.id
      left join lateral (
        select t.id,t.title,t.description from assessment_translation t
        where t.assessment_id=a.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('title',a.title,'description',a.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) at on true
      left join lateral (
        select t.id,t.display_name from curriculum_subject_translation t
        where t.subject_id=s.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
        order by t.version_no desc,t.id desc limit 1
      ) subject_tx on true
      left join lateral (
        select t.id,t.display_name from curriculum_chapter_translation t
        where t.chapter_id=ch.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
        order by t.version_no desc,t.id desc limit 1
      ) ct on true
      where a.id=?
        and (s.program_id=selected_course.id or (s.program_id is null and selected_course.class_id=s.class_id))
        and (
          a.status='PUBLISHED'
          or exists(select 1 from student_assessment_attempt resumable
                    where resumable.assessment_id=a.id and resumable.student_id=st.id
                      and resumable.institution_id=(select institution_id from tenant_scope)
                      and resumable.status='IN_PROGRESS')
        )
      group by a.id,s.id,s.code,s.display_name,c.code,ch.id,ch.display_name,selected_course.id,selected_course.code,selected_course.display_name
      """, institutionId,context.studentId(),courseId, language, language, language, assessmentId);
    if (assessmentRows.isEmpty()) throw notFound("Available assessment", assessmentId);
    Map<String, Object> assessment = assessmentRows.getFirst();

    List<Map<String, Object>> existing = jdbc.queryForList("""
      select id,offline_mode from student_assessment_attempt
      where assessment_id=? and student_id=? and institution_id=? and status='IN_PROGRESS'
      order by started_at desc limit 1 for update
      """, assessmentId, context.studentId(),institutionId);
    if (!existing.isEmpty()) {
      long existingId=((Number)existing.getFirst().get("id")).longValue();
      boolean existingOffline=Boolean.TRUE.equals(existing.getFirst().get("offline_mode"));
      if(offlineMode && !existingOffline) {
        throw conflict("This attempt was started online and cannot be converted to an offline attempt. Finish it online.");
      }
      if(existingOffline) {
        // Normal resume does not rotate the package token; an active worker may be uploading answers.
        return attemptDetails(existingId,context,false,institutionId);
      }
      return attemptDetails(existingId, context, false,institutionId);
    }
    if(offlineMode && !Boolean.TRUE.equals(assessment.get("offline_enabled"))) {
      throw conflict("This test is online-only. Offline attempts must be explicitly enabled by QuantaEdge content staff.");
    }

    // Enforce the same completion, publication and generated-version policy as the catalogue.
    // This runs only after resume has been handled, so saved attempts remain finishable.
    List<Map<String, Object>> eligibleForNewAttempt = jdbc.queryForList("""
      with tenant_scope as (select ?::bigint as institution_id)
      select a.id
      from assessment a
      join curriculum_subject s on s.id=a.subject_id and s.active=true
      join curriculum_class c on c.id=s.class_id and c.active=true
      join student st on st.id=? and st.active=true
      join curriculum_program_subject course_subject on course_subject.program_id=?
        and course_subject.subject_id=s.id and course_subject.class_id=s.class_id
      join curriculum_program selected_course on selected_course.id=course_subject.program_id
        and selected_course.status='ACTIVE' and selected_course.code<>'GENERAL'
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where a.id=?
        and (s.program_id=selected_course.id or (s.program_id is null and selected_course.class_id=s.class_id))
        and a.status='PUBLISHED' and
      """ + STUDENT_ASSESSMENT_ELIGIBILITY_SQL, institutionId,context.studentId(),courseId, assessmentId);
    if (eligibleForNewAttempt.isEmpty()) {
      throw conflict("Complete the required published lesson content before starting this test.");
    }

    Integer used = jdbc.queryForObject("""
      select count(*) from student_assessment_attempt
      where assessment_id=? and student_id=? and institution_id=? and status<>'ABANDONED'
      """, Integer.class, assessmentId, context.studentId(),institutionId);
    int maxAttempts = ((Number) assessment.get("max_attempts")).intValue();
    if (used != null && used >= maxAttempts) throw conflict("You have used all attempts allowed for this test.");
    Long questionCount = ((Number) assessment.get("question_count")).longValue();
    if (questionCount < 1) throw badRequest("This test has no questions and cannot be started.");

    int attemptNumber = (used == null ? 0 : used) + 1;
    Map<String, Object> assessmentSnapshot = new LinkedHashMap<>();
    assessmentSnapshot.put("assessmentId", assessmentId);
    assessmentSnapshot.put("courseId", courseId);
    assessmentSnapshot.put("courseName", scopedCourse.getFirst().get("display_name"));
    assessmentSnapshot.put("language", language);
    assessmentSnapshot.put("title", assessment.get("title"));
    assessmentSnapshot.put("description", assessment.get("description"));
    assessmentSnapshot.put("durationMinutes", assessment.get("duration_minutes"));
    assessmentSnapshot.put("attemptNumber", attemptNumber);
    assessmentSnapshot.put("questionCount", questionCount);
    assessmentSnapshot.put("maxScore", assessment.get("max_score"));
    assessmentSnapshot.put("negativeMarkingFraction", assessment.getOrDefault("negative_marking_fraction", BigDecimal.ZERO));
    assessmentSnapshot.put("subjectCode", assessment.get("subject_code"));
    assessmentSnapshot.put("subjectName", assessment.get("subject_name"));
    assessmentSnapshot.put("chapterName", assessment.get("chapter_name"));
    assessmentSnapshot.put("offlineMode",offlineMode);
    String offlineToken=offlineMode?newOfflinePackageToken():null;
    Long attemptId = jdbc.queryForObject("""
      insert into student_assessment_attempt(
        assessment_id,student_id,institution_id,attempt_number,status,assessment_snapshot,
        started_at,deadline_at,max_score,offline_mode,offline_sync_expires_at,offline_package_token_hash
      ) values(?,?,?,?,'IN_PROGRESS',?::jsonb,now(),now()+(? * interval '1 minute'),?,
        ?,case when ? then now()+(? * interval '1 minute') + interval '72 hours' else null end,?)
      returning id
      """, Long.class, assessmentId, context.studentId(),institutionId,attemptNumber, json(assessmentSnapshot),
        assessment.get("duration_minutes"), assessment.get("max_score"),
        offlineMode,offlineMode,assessment.get("duration_minutes"),
        offlineToken==null?null:offlinePackageTokenHash(offlineToken));

    jdbc.update("""
      insert into student_assessment_attempt_question(
        attempt_id,source_assessment_question_id,source_question_id,sequence_no,max_marks,question_snapshot
      )
      select ?,aq.id,aq.source_question_id,aq.sequence_no,aq.max_marks,
             case when aqt.id is null then
               jsonb_set(aq.question_snapshot,'{_translation_applied}','false'::jsonb,true)
             else
               jsonb_set(
                 jsonb_set(
                   jsonb_set(
                     jsonb_set(aq.question_snapshot,'{prompt}',to_jsonb(aqt.prompt),true),
                     '{explanation}',coalesce(to_jsonb(aqt.explanation),'null'::jsonb),true
                   ),
                   '{options}',aqt.options,true
                 ),
                 '{_translation_applied}','true'::jsonb,true
               )
             end
      from assessment_question aq
      left join lateral (
        select t.id,t.prompt,t.explanation,t.options
        from assessment_question_translation t
        where t.assessment_question_id=aq.id and t.language=? and t.translation_status='PUBLISHED'
          and t.source_hash::text=md5(jsonb_build_object(
            'prompt',aq.question_snapshot->>'prompt',
            'explanation',aq.question_snapshot->>'explanation',
            'options',coalesce((
              select jsonb_agg(jsonb_build_object(
                'key',coalesce(opt.value->>'key',opt.value->>'option_key'),
                'label',opt.value->>'label') order by opt.ordinality)
              from jsonb_array_elements(case
                when jsonb_typeof(aq.question_snapshot->'options')='array'
                then aq.question_snapshot->'options' else '[]'::jsonb end)
                with ordinality as opt(value,ordinality)
            ),'[]'::jsonb)
          )::text)
        order by t.version_no desc,t.id desc limit 1
      ) aqt on true
      where aq.assessment_id=? order by aq.sequence_no
      """, attemptId, language, assessmentId);
    jdbc.update("""
      insert into student_assessment_answer(attempt_question_id,response_payload,answer_status)
      select id,'""'::jsonb,'NOT_ANSWERED'
      from student_assessment_attempt_question where attempt_id=?
      """, attemptId);
    audit(assessmentId, attemptId, null, context.studentId(), "STUDENT", "ATTEMPT_STARTED",
        null, "IN_PROGRESS", "Student started test attempt " + attemptNumber + ".");
    Map<String,Object> started=new LinkedHashMap<>(attemptDetails(attemptId, context, false,institutionId));
    if(offlineToken!=null) started.put("offlinePackageToken",offlineToken);
    return started;
  }

  @GetMapping("/learning/assessment-attempts/{attemptId}")
  public Map<String, Object> studentAttempt(
      @PathVariable long attemptId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    requireAttemptInCourse(attemptId,courseId,tenant);
    return attemptDetails(attemptId, context, false,requireInstitution(tenant));
  }

  @PutMapping("/learning/assessment-attempts/{attemptId}/answers/{attemptQuestionId}")
  @Transactional
  public Map<String, Object> saveAnswer(
      @PathVariable long attemptId,
      @PathVariable long attemptQuestionId,
      @RequestParam Long courseId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    requireAttemptInCourse(attemptId,courseId,tenant);
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select att.id,att.status,att.deadline_at,att.student_id,att.offline_mode,
             att.offline_sync_expires_at,att.offline_package_token_hash
      from student_assessment_attempt att join student_assessment_attempt_question aq on aq.attempt_id=att.id
      where att.id=? and att.institution_id=? and aq.id=? for update of att
      """, attemptId,institutionId,attemptQuestionId);
    if (rows.isEmpty() || ((Number) rows.getFirst().get("student_id")).longValue() != context.studentId()) {
      throw notFound("Assessment answer", attemptQuestionId);
    }
    Map<String, Object> attempt = rows.getFirst();
    if (!"IN_PROGRESS".equals(String.valueOf(attempt.get("status")))) {
      throw conflict("Submitted test answers are locked and can no longer be changed.");
    }
    boolean offlineAttempt=Boolean.TRUE.equals(attempt.get("offline_mode"));
    if(offlineAttempt) requireOfflineSyncAuthorization(attempt,body.get("offlinePackageToken"));
    OffsetDateTime deadline = offsetDateTime(attempt.get("deadline_at"));
    if (!offlineAttempt && deadline != null && deadline.isBefore(OffsetDateTime.now())) {
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

  @PutMapping("/learning/assessment-attempts/{attemptId}/answers")
  @Transactional
  public Map<String, Object> saveAnswers(
      @PathVariable long attemptId,
      @RequestParam Long courseId,
      @RequestBody Map<String, Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    requireAttemptInCourse(attemptId,courseId,tenant);
    List<Map<String, Object>> attempts = jdbc.queryForList("""
      select id,status,deadline_at,student_id,offline_mode,offline_sync_expires_at,offline_package_token_hash
      from student_assessment_attempt where id=? and institution_id=? for update
      """, attemptId,institutionId);
    if (attempts.isEmpty()
        || ((Number) attempts.getFirst().get("student_id")).longValue() != context.studentId()) {
      throw notFound("Assessment attempt", attemptId);
    }
    Map<String, Object> attempt = attempts.getFirst();
    if (!"IN_PROGRESS".equals(String.valueOf(attempt.get("status")))) {
      throw conflict("Submitted test answers are locked and can no longer be changed.");
    }
    boolean offlineAttempt=Boolean.TRUE.equals(attempt.get("offline_mode"));
    if(offlineAttempt) requireOfflineSyncAuthorization(attempt,body.get("offlinePackageToken"));
    OffsetDateTime deadline = offsetDateTime(attempt.get("deadline_at"));
    if (!offlineAttempt && deadline != null && deadline.isBefore(OffsetDateTime.now())) {
      throw conflict("The test time has expired. Submit the attempt to finalize your saved answers.");
    }
    Object rawAnswers = body.get("answers");
    if (!(rawAnswers instanceof List<?> answers) || answers.size() > 200) {
      throw badRequest("Answers must be a list of up to 200 saved responses.");
    }
    java.util.HashSet<Long> seen = new java.util.HashSet<>();
    int saved = 0;
    for (Object raw : answers) {
      if (!(raw instanceof Map<?, ?> entry)) throw badRequest("Each saved answer must be an object.");
      long questionId = longValue(entry.get("attemptQuestionId"), "Attempt question ID");
      if (!seen.add(questionId)) throw badRequest("A question appears more than once in the save request.");
      Long owned = jdbc.queryForObject("""
        select count(*) from student_assessment_attempt_question
        where id=? and attempt_id=?
        """, Long.class, questionId, attemptId);
      if (owned == null || owned == 0) throw badRequest("A question does not belong to this test attempt.");
      Object value = entry.get("answer");
      if (value == null) value = "";
      jdbc.update("""
        update student_assessment_answer set response_payload=?::jsonb,saved_at=now()
        where attempt_question_id=?
        """, json(value), questionId);
      saved++;
    }
    return Map.of("saved", true, "savedCount", saved, "attemptId", attemptId);
  }

  @PostMapping("/learning/assessment-attempts/{attemptId}/offline-package-token")
  @Transactional
  public Map<String,Object> refreshOfflinePackageToken(
      @PathVariable long attemptId,
      @RequestParam Long courseId,
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    requireAttemptInCourse(attemptId,courseId,tenant);
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,assessment_id,student_id,status,offline_mode,offline_sync_expires_at
      from student_assessment_attempt where id=? and institution_id=? for update
      """,attemptId,institutionId);
    if(rows.isEmpty() || ((Number)rows.getFirst().get("student_id")).longValue()!=context.studentId()) {
      throw notFound("Assessment attempt",attemptId);
    }
    Map<String,Object> attempt=rows.getFirst();
    if(!Boolean.TRUE.equals(attempt.get("offline_mode"))) {
      throw conflict("This attempt is not an offline assessment.");
    }
    if(!"IN_PROGRESS".equals(String.valueOf(attempt.get("status")))) {
      throw conflict("Only an active offline attempt can refresh its package token.");
    }
    OffsetDateTime expiry=offsetDateTime(attempt.get("offline_sync_expires_at"));
    if(expiry==null || expiry.isBefore(OffsetDateTime.now())) {
      throw conflict("Offline sync has expired. Contact QuantaEdge support for this attempt.");
    }
    String token=newOfflinePackageToken();
    jdbc.update("""
      update student_assessment_attempt set offline_package_token_hash=?,updated_at=now()
      where id=? and student_id=? and offline_mode=true and status='IN_PROGRESS'
      """,offlinePackageTokenHash(token),attemptId,context.studentId());
    audit(((Number)attempt.get("assessment_id")).longValue(),attemptId,null,context.studentId(),"STUDENT",
        "OFFLINE_PACKAGE_TOKEN_REFRESHED","IN_PROGRESS","IN_PROGRESS",
        "Student explicitly refreshed a protected offline package token after a sync error.");
    return Map.of("attemptId",attemptId,"offlinePackageToken",token);
  }

  @PostMapping("/learning/assessment-attempts/{attemptId}/submit")
  @Transactional
  public Map<String, Object> submitAttempt(
      @PathVariable long attemptId,
      @RequestParam Long courseId,
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    requireAttemptInCourse(attemptId,courseId,tenant);
    return submitAttemptInternal(attemptId,body,context,requireInstitution(tenant));
  }

  Map<String,Object> submitAttempt(long attemptId,AuthContext context) {
    throw missingInstitutionContext();
  }

  private Map<String,Object> submitAttemptInternal(long attemptId,Map<String,Object> body,
      AuthContext context,long institutionId) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> attemptRows = jdbc.queryForList("""
      select id,assessment_id,student_id,status,auto_score,max_score,offline_mode,
             offline_sync_expires_at,offline_package_token_hash,assessment_snapshot::text as assessment_snapshot
      from student_assessment_attempt where id=? and institution_id=? for update
      """, attemptId,institutionId);
    if (attemptRows.isEmpty() || ((Number) attemptRows.getFirst().get("student_id")).longValue() != context.studentId()) {
      throw notFound("Assessment attempt", attemptId);
    }
    Map<String, Object> attempt = attemptRows.getFirst();
    boolean offlineAttempt=Boolean.TRUE.equals(attempt.get("offline_mode"));
    if(offlineAttempt) requireOfflineSyncAuthorization(attempt,body==null?null:body.get("offlinePackageToken"));
    String oldStatus = String.valueOf(attempt.get("status"));
    if ("RELEASED".equals(oldStatus)) return attemptDetails(attemptId, context, false,institutionId);
    if (!"IN_PROGRESS".equals(oldStatus)) throw conflict("This test attempt has already been submitted.");
    long assessmentId = ((Number) attempt.get("assessment_id")).longValue();

    List<Map<String, Object>> answerRows = jdbc.queryForList("""
      select aq.id as attempt_question_id,aq.sequence_no,aq.max_marks,aq.question_snapshot::text as question_snapshot,
             ans.id as answer_id,ans.response_payload::text as response_payload
      from student_assessment_attempt_question aq
      join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where aq.attempt_id=? order by aq.sequence_no for update of ans
      """, attemptId);
    Map<String,Object> attemptSnapshot=parseObject(String.valueOf(attempt.get("assessment_snapshot")));
    Object rawPenaltyFraction=attemptSnapshot.getOrDefault("negativeMarkingFraction",BigDecimal.ZERO);
    BigDecimal negativeFraction;
    try { negativeFraction=new BigDecimal(String.valueOf(rawPenaltyFraction)); }
    catch (RuntimeException ex) { throw new IllegalStateException("Stored assessment penalty configuration is invalid.",ex); }
    if(negativeFraction.signum()<0||negativeFraction.compareTo(BigDecimal.ONE)>0) {
      throw new IllegalStateException("Stored assessment negative-marking fraction is outside [0,1].");
    }
    BigDecimal autoScore = BigDecimal.ZERO;
    BigDecimal penaltyScore = BigDecimal.ZERO;
    boolean needsReview = false;
    for (Map<String, Object> row : answerRows) {
      Map<String, Object> snapshot = parseObject(String.valueOf(row.get("question_snapshot")));
      String questionType = String.valueOf(snapshot.getOrDefault("question_type", ""));
      String responseJson = String.valueOf(row.get("response_payload"));
      Object response = parseValue(responseJson);
      BigDecimal maxMarks = new BigDecimal(String.valueOf(row.get("max_marks")));
      boolean includeInScoredTotal=contributesToAggregate(snapshot);
      String answerStatus;
      Boolean correct = null;
      BigDecimal awarded;
      BigDecimal questionPenalty=BigDecimal.ZERO;
      if (isEmptyAnswer(response)) {
        // An unanswered question is not a wrong answer and must never attract negative marks.
        correct = null;
        awarded = BigDecimal.ZERO;
        answerStatus = "GRADED";
      } else if (isAutoGradedType(questionType)) {
        String answerPayload = String.valueOf(snapshot.getOrDefault("answer_payload", "{}"));
        QuestionAnswerService.Evaluation evaluation = answerService.evaluate(answerPayload, response);
        if (!evaluation.autoGraded()) {
          correct = null;
          awarded = null;
          answerStatus = "PENDING_REVIEW";
          needsReview = true;
        } else {
          correct = evaluation.correct();
          awarded = Boolean.TRUE.equals(correct) ? maxMarks : BigDecimal.ZERO;
          answerStatus = "AUTO_GRADED";
          if(includeInScoredTotal) {
            if(Boolean.TRUE.equals(correct)) {
              autoScore = autoScore.add(awarded);
            } else if(Boolean.FALSE.equals(correct)) {
              questionPenalty=maxMarks.multiply(negativeFraction).setScale(2,RoundingMode.HALF_UP);
              penaltyScore=penaltyScore.add(questionPenalty);
            }
          }
        }
      } else {
        correct = null;
        awarded = null;
        answerStatus = "PENDING_REVIEW";
        needsReview = true;
      }
      jdbc.update("""
        update student_assessment_answer set answer_status=?,is_correct=?,awarded_marks=?,penalty_marks=?,
          teacher_feedback=null,graded_at=case when ? in ('AUTO_GRADED','GRADED') then now() else null end,
          graded_by_staff_id=null,saved_at=now()
        where id=?
        """, answerStatus, correct, awarded, questionPenalty, answerStatus, row.get("answer_id"));
    }
    String next = needsReview ? "AWAITING_REVIEW" : "RELEASED";
    BigDecimal finalScore = needsReview ? null : autoScore.subtract(penaltyScore).max(BigDecimal.ZERO);
    jdbc.update("""
      update student_assessment_attempt set status=?,submitted_at=now(),auto_score=?,penalty_score=?,final_score=?,
        graded_at=case when ?='RELEASED' then now() else null end,
        released_at=case when ?='RELEASED' then now() else null end,updated_at=now()
      where id=?
      """, next, autoScore, penaltyScore, finalScore, next, next, attemptId);
    audit(assessmentId, attemptId, null, context.studentId(), "STUDENT", "ATTEMPT_SUBMITTED",
        "IN_PROGRESS", next, needsReview ? "Submitted; teacher review is required before the result is released."
            : "Submitted and automatically graded; result released.");
    if ("RELEASED".equals(next)) {
      audit(assessmentId, attemptId, null, context.studentId(), "STUDENT", "RESULT_AUTO_RELEASED",
          "IN_PROGRESS", "RELEASED", "Objective result released after automatic grading.");
    }
    return attemptDetails(attemptId, context, false,institutionId);
  }

  @GetMapping("/learning/assessment-results")
  public Map<String,Object> studentResults(
      @RequestParam(required = false, defaultValue = "hi") String language,
      @RequestParam(required = false) Long courseId,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(defaultValue="25") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireStudent(context);
    long institutionId=requireInstitution(tenant);
    language=normalizeLanguage(language);
    if(courseId==null || courseId<=0L) throw badRequest("Select an active course before accessing tests and results.");
    Long active=jdbc.queryForObject("""
      select count(*) from student_program_enrollment spe
      join curriculum_program p on p.id=spe.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
      join institution_course_assignment a on a.institution_id=spe.institution_id and a.status='ASSIGNED'
      join institution_course_release r on r.id=a.course_release_id and r.program_id=p.id
      where spe.student_id=? and spe.institution_id=? and spe.program_id=? and spe.status='ACTIVE'
      """,Long.class,context.studentId(),institutionId,courseId);
    if(active==null || active==0) throw new SecurityException("Active enrolment in this course is required.");
    int rowLimit=Math.max(1,Math.min(limit==null?25:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    String selectedSubject = safeText(subjectCode,40);
    if(selectedSubject.isBlank()) selectedSubject=null;
    String resultScope="""
         a.course_id=?
         and exists(select 1 from curriculum_program_subject cps
          join curriculum_program selected_course on selected_course.id=cps.program_id and selected_course.status='ACTIVE'
          join institution_course_assignment course_assignment
            on course_assignment.institution_id=(select institution_id from tenant_scope)
            and course_assignment.status='ASSIGNED'
          join institution_course_release course_release on course_release.id=course_assignment.course_release_id
            and course_release.program_id=selected_course.id
          where cps.program_id=? and cps.subject_id=s.id and cps.class_id=s.class_id
            and (s.program_id=cps.program_id or (s.program_id is null and selected_course.class_id=s.class_id)))
        and exists(select 1 from student_program_enrollment spe
          where spe.student_id=att.student_id and spe.institution_id=(select institution_id from tenant_scope)
            and spe.program_id=? and spe.status='ACTIVE')
        and (?::text is null or lower(s.code)=lower(?))
        """;
    String sql="""
      with tenant_scope as (select ?::bigint as institution_id),
      ranked as (
        select att.id as attempt_id,att.assessment_id,a.public_id as test_id,att.attempt_number,att.status,att.submitted_at,
               att.graded_at,att.released_at,att.final_score,att.max_score,a.assessment_type,a.is_generated,
               coalesce((select t.title from assessment_translation t
                 where t.assessment_id=a.id and t.language=? and t.translation_status='PUBLISHED'
                   and t.source_hash::text=md5(jsonb_build_object('title',a.title,'description',a.description)::text)
                 order by t.version_no desc,t.id desc limit 1),a.title) as assessment_title,
               s.code as subject_code,
               coalesce((select t.display_name from curriculum_subject_translation t
                 where t.subject_id=s.id and t.language=? and t.translation_status='PUBLISHED'
                   and t.source_hash::text=md5(jsonb_build_object('display_name',s.display_name)::text)
                 order by t.version_no desc,t.id desc limit 1),s.display_name) as subject_name,
               coalesce((select t.display_name from curriculum_chapter_translation t
                 where t.chapter_id=ch.id and t.language=? and t.translation_status='PUBLISHED'
                   and t.source_hash::text=md5(jsonb_build_object('display_name',ch.display_name,'description',ch.description)::text)
                 order by t.version_no desc,t.id desc limit 1),ch.display_name) as chapter_name,
               (select count(*) from student_assessment_attempt_question aq where aq.attempt_id=att.id) as question_count,
               row_number() over(partition by att.assessment_id order by att.released_at desc,att.id desc) as recency_rank,
               count(*) over(partition by att.assessment_id) as attempt_count
        from student_assessment_attempt att
        join assessment a on a.id=att.assessment_id
        join curriculum_subject s on s.id=a.subject_id
        join curriculum_class c on c.id=s.class_id
        join student st on st.id=att.student_id and st.active=true
        left join curriculum_chapter ch on ch.id=a.chapter_id
        where att.student_id=? and att.institution_id=(select institution_id from tenant_scope)
          and att.status='RELEASED' and __RESULT_SCOPE__
      ),
      grouped_attempts as (
        select assessment_id,
               coalesce(max(final_score) filter(where recency_rank<=3),0) as best_score,
               max(attempt_count) as attempt_count,
               jsonb_agg(jsonb_build_object(
                 'attempt_id',attempt_id,
                 'attempt_number',attempt_number,
                 'status',status,
                 'submitted_at',submitted_at,
                 'released_at',released_at,
                 'final_score',final_score,
                 'max_score',max_score
               ) order by released_at desc,attempt_id desc)
                 filter(where recency_rank<=3)::text as attempts_json
        from ranked
        group by assessment_id
      )
      select latest.attempt_id,latest.assessment_id,latest.test_id,latest.attempt_number,latest.status,
             latest.submitted_at,latest.released_at,latest.final_score,latest.max_score,
             latest.assessment_type,latest.is_generated,latest.assessment_title,latest.subject_code,latest.subject_name,
             latest.chapter_name,latest.question_count,
             grouped.best_score,grouped.attempt_count,grouped.attempts_json
      from ranked latest
      join grouped_attempts grouped on grouped.assessment_id=latest.assessment_id
      where latest.recency_rank=1
      order by latest.released_at desc,latest.attempt_id desc
      limit ? offset ?
      """.replace("__RESULT_SCOPE__",resultScope);
    List<Object> args=new ArrayList<>(List.of(institutionId,language,language,language,context.studentId()));
    args.add(courseId);args.add(courseId);args.add(courseId);
     args.add(selectedSubject);args.add(selectedSubject);
    args.add(rowLimit);args.add(rowOffset);
    List<Map<String,Object>> rows=jdbc.queryForList(sql,args.toArray());
    List<Map<String,Object>> items=new ArrayList<>();
    for(Map<String,Object> row:rows){
      Map<String,Object> item=new LinkedHashMap<>(row);
      Object attemptsJson=item.remove("attempts_json");
      item.put("attempts",attemptsJson==null?List.of():parseValue(String.valueOf(attemptsJson)));
      items.add(item);
    }
    String totalSql="""
      with tenant_scope as (select ?::bigint as institution_id)
      select count(distinct att.assessment_id)
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      join student st on st.id=att.student_id and st.active=true
      where att.student_id=? and att.institution_id=(select institution_id from tenant_scope)
        and att.status='RELEASED' and __RESULT_SCOPE__
      """.replace("__RESULT_SCOPE__",resultScope);
    Object[] totalArgs=new Object[]{institutionId,context.studentId(),courseId,courseId,courseId,selectedSubject,selectedSubject};
    Long total=jdbc.queryForObject(totalSql,Long.class,totalArgs);
    return Map.of("items",items,"total",total==null?0L:total,"limit",rowLimit,"offset",rowOffset);
  }

  public Map<String,Object> studentResults(String language,Long courseId,Integer limit,Integer offset,
      AuthContext context,InstitutionTenantContext tenant) {
    return studentResults(language,courseId,null,limit,offset,context,tenant);
  }

  public Map<String,Object> studentResults(String language,Integer limit,Integer offset,AuthContext context) {
    throw missingInstitutionContext();
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400,"INSTITUTION_CONTEXT_REQUIRED","Select the institution before accessing assessments.");
    }
    return tenant.institutionId();
  }

  private InstitutionTenantContextService.InvalidInstitutionContextException missingInstitutionContext() {
    return new InstitutionTenantContextService.InvalidInstitutionContextException(
        400,"INSTITUTION_CONTEXT_REQUIRED",
        "Legacy assessment calls must provide an explicit institution through the authenticated HTTP boundary.");
  }

  // ---------- Shared private helpers ----------

  private String newOfflinePackageToken() {
    byte[] token=new byte[32];
    OFFLINE_TOKEN_RANDOM.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  private String offlinePackageTokenHash(String token) {
    try {
      byte[] hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().withoutPadding().encodeToString(hash);
    } catch(Exception ex) {
      throw new IllegalStateException("Offline package token hashing is unavailable.",ex);
    }
  }

  private void requireOfflineSyncAuthorization(Map<String,Object> attempt,Object rawToken) {
    String supplied=rawToken==null?"":String.valueOf(rawToken);
    String expected=String.valueOf(attempt.getOrDefault("offline_package_token_hash",""));
    if(supplied.isBlank() || expected.isBlank() ||
       !MessageDigest.isEqual(offlinePackageTokenHash(supplied).getBytes(StandardCharsets.UTF_8),
                             expected.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This offline package is not valid for the active attempt.");
    }
    OffsetDateTime expiry=offsetDateTime(attempt.get("offline_sync_expires_at"));
    if(expiry==null || expiry.isBefore(OffsetDateTime.now())) {
      throw conflict("Offline sync has expired. The saved offline attempt cannot be submitted now; contact QuantaEdge support.");
    }
  }

  private Map<String, Object> attemptDetails(long attemptId, AuthContext context, boolean staffView) {
    if (!staffView) throw missingInstitutionContext();
    return attemptDetails(attemptId,context,staffView,null);
  }

  private Map<String, Object> attemptDetails(long attemptId, AuthContext context,
      boolean staffView, Long institutionId) {
    if (!staffView && institutionId == null) throw missingInstitutionContext();
    String tenantPredicate = institutionId == null ? "" : " and att.institution_id=?";
    Object[] queryArgs = institutionId == null
        ? new Object[]{attemptId} : new Object[]{attemptId,institutionId};
    List<Map<String, Object>> attempts = jdbc.queryForList("""
      select att.id as attempt_id,att.assessment_id,a.public_id as test_id,a.course_id as course_id,
             att.student_id,att.attempt_number,att.status,
             att.started_at,att.deadline_at,att.submitted_at,att.graded_at,att.released_at,
             att.auto_score,att.final_score,att.max_score,att.penalty_score,att.offline_mode,att.offline_sync_expires_at,
             att.assessment_snapshot::text as assessment_snapshot,
             st.display_name as student_name,st.class_code,a.title as assessment_title,
             a.assessment_type,a.is_generated,s.code as subject_code,s.display_name as subject_name,ch.display_name as chapter_name
      from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      join curriculum_subject s on s.id=a.subject_id
      join student st on st.id=att.student_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where att.id=?""" + tenantPredicate,queryArgs);
    if (attempts.isEmpty()) throw notFound("Assessment attempt", attemptId);
    Map<String, Object> attempt = new LinkedHashMap<>(attempts.getFirst());
    attempt.put("offlineMode",Boolean.TRUE.equals(attempt.get("offline_mode")));
    attempt.put("offlineSyncExpiresAt",attempt.get("offline_sync_expires_at"));
    Map<String,Object> assessmentSnapshot=parseObject(String.valueOf(attempt.get("assessment_snapshot")));
    if(!assessmentSnapshot.isEmpty()){
      attempt.put("assessment_title",assessmentSnapshot.getOrDefault("title",attempt.get("assessment_title")));
      attempt.put("subject_name",assessmentSnapshot.getOrDefault("subjectName",attempt.get("subject_name")));
      attempt.put("chapter_name",assessmentSnapshot.getOrDefault("chapterName",attempt.get("chapter_name")));
      attempt.put("language",assessmentSnapshot.getOrDefault("language","hi"));
    }
    if (!staffView && (context == null || context.studentId() == null
        || ((Number) attempt.get("student_id")).longValue() != context.studentId())) {
      throw notFound("Assessment attempt", attemptId);
    }
    if (staffView) requireAssignment(context, ((Number) attempt.get("assessment_id")).longValue());
    String displayLanguage=String.valueOf(assessmentSnapshot.getOrDefault("language","hi"));
    boolean resultReleased = "RELEASED".equals(String.valueOf(attempt.get("status")));
    attempt.remove("student_id");
    if (!resultReleased) {
      attempt.remove("final_score");
      attempt.remove("auto_score");
      attempt.remove("penalty_score");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select aq.id as attempt_question_id,aq.sequence_no,aq.max_marks,
             aq.question_snapshot::text as question_snapshot,
             ans.id as answer_id,ans.response_payload::text as response_payload,
             ans.answer_status,ans.is_correct,ans.awarded_marks,ans.penalty_marks,ans.teacher_feedback
      from student_assessment_attempt_question aq
      join student_assessment_answer ans on ans.attempt_question_id=aq.id
      where aq.attempt_id=? order by aq.sequence_no
      """, attemptId);
    List<Map<String, Object>> questions = new ArrayList<>();
    Map<String,Map<String,Object>> sectionSummary=new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> snapshot = parseObject(String.valueOf(row.get("question_snapshot")));
      Map<String, Object> item = publicSnapshot(snapshot,displayLanguage);
      item.put("attemptQuestionId", row.get("attempt_question_id"));
      item.put("answerId", row.get("answer_id"));
      item.put("maxMarks", row.get("max_marks"));
      item.put("answer", parseValue(String.valueOf(row.get("response_payload"))));
      item.put("answerStatus", row.get("answer_status"));
      // Explanations are also grading feedback: do not expose them in active or awaiting-review attempts.
      if (resultReleased || staffView) {
        Object explanation=localizedValue(snapshot.get("explanation_i18n"),displayLanguage,snapshot.get("explanation"));
        if (explanation!=null) item.put("explanation",explanation);
        item.put("isCorrect", row.get("is_correct"));
        item.put("awardedMarks", row.get("awarded_marks"));
        item.put("penaltyMarks", row.get("penalty_marks"));
        item.put("teacherFeedback", row.get("teacher_feedback"));
      }
      // Correct answers are disclosed to students only after the result is released.
      // This lets the student review identify the right option without leaking answer
      // keys while an attempt is still in progress or awaiting manual grading.
      if (resultReleased && !staffView) {
        Object correctAnswer = snapshot.get("answer_payload");
        // Stored question snapshots encode answer_payload as a JSON string, while
        // some legacy snapshots may already contain an object. Return a consistent
        // object shape to the review UI in either case.
        if (correctAnswer instanceof String serialized && !serialized.isBlank()) {
          correctAnswer = parseObject(serialized);
        }
        item.put("correctAnswer", correctAnswer);
      }
      if (staffView) {
        item.put("manualReviewRequired", "PENDING_REVIEW".equals(String.valueOf(row.get("answer_status"))));
        item.put("answerKey", snapshot.get("answer_payload"));
        item.put("teacherOptions", teacherOptions(snapshot));
      }
      questions.add(item);

      String sectionCode=safeText(snapshot.get("section_code"),50).toUpperCase(Locale.ROOT);
      if(!sectionCode.isBlank()) {
        String resultPolicy=sectionResultPolicy(snapshot);
        Map<String,Object> section=sectionSummary.computeIfAbsent(sectionCode,code->{
          Map<String,Object> created=new LinkedHashMap<>();
          created.put("section_code",code);
          created.put("section_name",localizedValue(snapshot.get("section_name_i18n"),displayLanguage,
              snapshot.getOrDefault("section_name",code)));
          created.put("result_policy",resultPolicy);
          created.put("question_count",0);
          created.put("correct_count",0);
          created.put("incorrect_count",0);
          created.put("unanswered_count",0);
          created.put("max_score",BigDecimal.ZERO);
          created.put("earned_marks",BigDecimal.ZERO);
          created.put("penalty_marks",BigDecimal.ZERO);
          created.put("net_score",BigDecimal.ZERO);
          return created;
        });
        section.put("question_count",((Number)section.get("question_count")).intValue()+1);
        BigDecimal qMax=new BigDecimal(String.valueOf(row.get("max_marks")));
        BigDecimal qEarned=row.get("awarded_marks")==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(row.get("awarded_marks")));
        BigDecimal qPenalty=row.get("penalty_marks")==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(row.get("penalty_marks")));
        section.put("max_score",((BigDecimal)section.get("max_score")).add(qMax));
        section.put("earned_marks",((BigDecimal)section.get("earned_marks")).add(qEarned));
        section.put("penalty_marks",((BigDecimal)section.get("penalty_marks")).add(qPenalty));
        if(isEmptyAnswer(parseValue(String.valueOf(row.get("response_payload"))))) {
          section.put("unanswered_count",((Number)section.get("unanswered_count")).intValue()+1);
        } else if(Boolean.TRUE.equals(row.get("is_correct"))) {
          section.put("correct_count",((Number)section.get("correct_count")).intValue()+1);
        } else if(Boolean.FALSE.equals(row.get("is_correct"))) {
          section.put("incorrect_count",((Number)section.get("incorrect_count")).intValue()+1);
        }
      }
    }
    attempt.put("questions", questions);
    if(resultReleased || staffView) {
      List<Map<String,Object>> scoredSections=new ArrayList<>();
      List<Map<String,Object>> qualifyingSections=new ArrayList<>();
      List<Map<String,Object>> practiceOnlySections=new ArrayList<>();
      for(Map<String,Object> section:sectionSummary.values()) {
        BigDecimal sectionNet=((BigDecimal)section.get("earned_marks")).subtract((BigDecimal)section.get("penalty_marks"));
        section.put("net_score",sectionNet);
        String policy=String.valueOf(section.get("result_policy"));
        if("SCORED".equals(policy)) scoredSections.add(section);
        else if("QUALIFYING_ONLY".equals(policy)) qualifyingSections.add(section);
        else practiceOnlySections.add(section);
      }
      if(!sectionSummary.isEmpty()) {
        attempt.put("section_results",scoredSections);
        if(!qualifyingSections.isEmpty()) attempt.put("qualifying_sections",qualifyingSections);
        if(!practiceOnlySections.isEmpty()) attempt.put("practice_only_sections",practiceOnlySections);
        attempt.put("negative_marking_fraction",assessmentSnapshot.getOrDefault("negativeMarkingFraction",BigDecimal.ZERO));
      }
    }
    if (assessmentSnapshot.containsKey("language")) {
      attempt.put("translation_complete", questions.stream()
          .allMatch(question -> Boolean.TRUE.equals(question.get("translated"))));
    }
    attempt.put("resultReleased", resultReleased);
    if (staffView) attempt.put("audit", auditHistory(((Number) attempt.get("assessment_id")).longValue(), attemptId));
    return attempt;
  }

  private Map<String, Object> assessmentDetails(long id, boolean includeQuestionBankDetails) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select a.id as assessment_id,a.public_id as test_id,a.title,a.description,a.duration_minutes,a.max_attempts,a.status,
             a.offline_enabled,a.negative_marking_fraction,a.source_package_id,a.source_package_version,a.source_package_sha256,
             a.source_exam_cycle,a.source_url,a.created_at,a.published_at,s.code as subject_code,s.display_name as subject_name,
             c.code as class_code,ch.id as chapter_id,ch.code as chapter_code,ch.display_name as chapter_name
      from assessment a join curriculum_subject s on s.id=a.subject_id
      join curriculum_class c on c.id=s.class_id
      left join curriculum_chapter ch on ch.id=a.chapter_id
      where a.id=?
      """, id);
    if (rows.isEmpty()) throw notFound("Assessment", id);
    Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("question_count", jdbc.queryForObject("select count(*) from assessment_question where assessment_id=?", Integer.class, id));
    result.put("max_score", jdbc.queryForObject("""
      select coalesce(sum(case when question_snapshot->>'section_result_policy' in ('QUALIFYING_ONLY','PRACTICE_ONLY')
          or question_snapshot->>'section_code'='LANGUAGE_QUALIFYING' then 0 else max_marks end),0)
      from assessment_question where assessment_id=?
      """, BigDecimal.class, id));
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

  private Map<String, Object> publicSnapshot(Map<String, Object> snapshot,String language) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : List.of("id", "question_type", "difficulty","sort_order","marks",
        "exam_format","topic","subtopic","skill","section_code","section_result_policy","topic_tags","subtopic_tags",
        "prompt_content_kind","code_line_indices","math_line_indices","source_code_preservation_notes",
        "stimulus_id","stimulus","stimulus_content_kind","stimulus_code_line_indices","stimulus_math_line_indices",
        "stimulus_source_languages","native_language_only","native_source_language","content_language_policy")) {
      if (snapshot.containsKey(key)) result.put(key, snapshot.get(key));
    }
    boolean nativeSourceOnly=Boolean.TRUE.equals(snapshot.get("native_language_only"));
    String nativeSourceLanguage=safeText(snapshot.get("native_source_language"),10).toLowerCase(Locale.ROOT);
    if ("e".equals(nativeSourceLanguage)) nativeSourceLanguage="en";
    if ("h".equals(nativeSourceLanguage)) nativeSourceLanguage="hi";
    Object localizedPrompt=nativeSourceOnly
        ? localizedSourceValue(snapshot.get("prompt_i18n"),nativeSourceLanguage,language,snapshot.get("prompt"))
        : localizedValue(snapshot.get("prompt_i18n"),language,snapshot.get("prompt"));
    if(localizedPrompt!=null) result.put("prompt",localizedPrompt);
    Object localizedStimulus=nativeSourceOnly
        ? localizedSourceValue(snapshot.get("stimulus_i18n"),nativeSourceLanguage,language,snapshot.get("stimulus"))
        : localizedValue(snapshot.get("stimulus_i18n"),language,snapshot.get("stimulus"));
    if(localizedStimulus instanceof String stimulusText && !stimulusText.isBlank()) result.put("stimulus",stimulusText);
    Object rawOptions = snapshot.get("options");
    List<Map<String, Object>> options = new ArrayList<>();
    boolean hasSourceLocale=localizedHas(snapshot.get("prompt_i18n"),language);
    if (rawOptions instanceof List<?> entries) {
      for (Object raw : entries) {
        if (!(raw instanceof Map<?, ?> option)) continue;
        Map<String, Object> safe = new LinkedHashMap<>();
        Object key = option.get("option_key");
        if (key == null) key = option.get("key");
        Object label = option.get("label");
        if (label == null) label = option.get("text");
        Object localizedLabel=nativeSourceOnly
            ? localizedSourceValue(option.get("text_i18n"),nativeSourceLanguage,language,label)
            : localizedValue(option.get("text_i18n"),language,label);
        if(localizedLabel!=null) label=localizedLabel;
        hasSourceLocale=hasSourceLocale||localizedHas(option.get("text_i18n"),language);
        safe.put("key", key);
        safe.put("label", nativeSourceOnly
            ? localizedSourceValue(option.get("text_i18n"),nativeSourceLanguage,language,label)
            : localizedValue(option.get("text_i18n"),language,label));
        for(String metadataKey:List.of("content_kind","code_line_indices","math_line_indices","source_code_preservation_notes")) {
          if(option.containsKey(metadataKey)) safe.put(metadataKey,option.get(metadataKey));
        }
        options.add(safe);
      }
    }
    result.put("options", options);
    Object rawPayload=snapshot.get("answer_payload");
    String responseMode="";
    if(rawPayload instanceof Map<?,?> payload) {
      Object kind=payload.get("kind");
      responseMode=kind==null?"":String.valueOf(kind);
    } else if(rawPayload instanceof String encoded && !encoded.isBlank()) {
      responseMode=String.valueOf(parseObject(encoded).getOrDefault("kind",""));
    }
    responseMode=responseMode.trim().toUpperCase(Locale.ROOT);
    if(!responseMode.isBlank()) result.put("response_mode",responseMode);
    result.put("translated", !nativeSourceOnly &&
        (Boolean.TRUE.equals(snapshot.get("_translation_applied"))||hasSourceLocale));
    return result;
  }

  private Object localizedSourceValue(Object rawLocalized,String nativeSourceLanguage,String requestedLanguage,Object fallback) {
    if(rawLocalized instanceof Map<?,?> values) {
      if(nativeSourceLanguage!=null&&!nativeSourceLanguage.isBlank()) {
        Object source=values.get(nativeSourceLanguage);
        if(source instanceof String text&&!text.isBlank()) return text;
      }
      // Older snapshots may lack native_source_language. Prefer an exact requested-language
      // variant only when the stored set has one language; otherwise do not guess a translation.
      if(values.size()==1) {
        Object only=values.values().iterator().next();
        if(only instanceof String text&&!text.isBlank()) return text;
      } else if(nativeSourceLanguage==null||nativeSourceLanguage.isBlank()) {
        Object selected=values.get(requestedLanguage);
        if(selected instanceof String text&&!text.isBlank()) return text;
      }
    }
    return fallback;
  }

  private boolean localizedHas(Object rawLocalized,String language) {
    return rawLocalized instanceof Map<?,?> values && values.get(language) instanceof String text && !text.isBlank();
  }

  private Object localizedValue(Object rawLocalized,String language,Object fallback) {
    if(rawLocalized instanceof Map<?,?> values) {
      Object selected=values.get(language);
      if(selected instanceof String text && !text.isBlank()) return text;
      Object english=values.get("en");
      if(english instanceof String text && !text.isBlank()) return text;
      Object hindi=values.get("hi");
      if(hindi instanceof String text && !text.isBlank()) return text;
    }
    return fallback;
  }

  private String sectionResultPolicy(Map<String,Object> snapshot) {
    String policy=safeText(snapshot.get("section_result_policy"),40).toUpperCase(Locale.ROOT);
    if(Set.of("SCORED","QUALIFYING_ONLY","PRACTICE_ONLY").contains(policy)) return policy;
    return "LANGUAGE_QUALIFYING".equalsIgnoreCase(safeText(snapshot.get("section_code"),50))
        ? "QUALIFYING_ONLY" : "SCORED";
  }

  private boolean contributesToAggregate(Map<String,Object> snapshot) {
    String policy=sectionResultPolicy(snapshot);
    return "SCORED".equals(policy);
  }

  private List<Map<String, Object>> teacherOptions(Map<String, Object> snapshot) {
    List<Map<String, Object>> result = new ArrayList<>();
    Object rawOptions = snapshot.get("options");
    if (!(rawOptions instanceof List<?> entries)) return result;
    for (Object raw : entries) {
      if (!(raw instanceof Map<?, ?> option)) continue;
      Map<String, Object> value = new LinkedHashMap<>();
      Object optionKey = option.containsKey("option_key") ? option.get("option_key") : option.get("key");
      value.put("key", optionKey);
      value.put("label", option.get("label"));
      value.put("isCorrect", Boolean.TRUE.equals(option.get("is_correct")) || Boolean.TRUE.equals(option.get("correct")));
      result.add(value);
    }
    return result;
  }

  private String normalizeLanguage(String language) {
    String normalized=language==null?"hi":language.trim().toLowerCase(Locale.ROOT);
    if("hi".equals(normalized)||"en".equals(normalized))return normalized;
    throw badRequest("Language must be 'hi' or 'en'.");
  }

  private void requireAssignedCourse(Long courseId, InstitutionTenantContext tenant) {
    if (courseId == null || courseId < 1L) throw badRequest("Select an active course.");
    if (tenant == null || tenant.institutionId() < 1L) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An active institution context is required.");
    }
    Long assigned = jdbc.queryForObject("""
      select count(*) from curriculum_program p
      join institution_course_release release on release.program_id=p.id
      join institution_course_assignment assignment
        on assignment.course_release_id=release.id and assignment.institution_id=?
           and assignment.status='ASSIGNED'
      where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      """, Long.class, tenant.institutionId(), courseId);
    if (assigned == null || assigned == 0L) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This course is not assigned to the active institution.");
    }
  }

  private void requireAssessmentInCourse(long assessmentId,Long courseId,InstitutionTenantContext tenant) {
    requireAssignedCourse(courseId,tenant);
    Long visible=jdbc.queryForObject("""
      select count(*) from assessment a where a.id=? and a.course_id=?
      """,Long.class,assessmentId,courseId);
    if(visible==null||visible==0L) throw notFound("Assessment in selected course",assessmentId);
  }

  private void requireAttemptInCourse(long attemptId,Long courseId,InstitutionTenantContext tenant) {
    requireAssignedCourse(courseId,tenant);
    if(tenant==null||tenant.institutionId()<1L) throw new SecurityException("Active institution context required.");
    Long visible=jdbc.queryForObject("""
      select count(*) from student_assessment_attempt att
      join assessment a on a.id=att.assessment_id
      where att.id=? and a.course_id=? and att.institution_id=?
      """,Long.class,attemptId,courseId,tenant.institutionId());
    if(visible==null||visible==0L) throw notFound("Assessment attempt in selected course",attemptId);
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
