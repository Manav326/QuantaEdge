
package com.quantaedge.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/content")
public class AdminContentController {
  private static final Set<String> CHAPTER_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
  private static final Set<String> LESSON_STATUSES = Set.of("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED");
  private static final Set<String> BLOCK_TYPES = Set.of(
      "EXPLANATION", "IMAGE", "DIAGRAM", "VIDEO", "QUESTION", "MCQ", "TRUE_FALSE",
      "MATCH", "ORDER", "INPUT", "HINT", "AI_HELP", "SUMMARY", "CHALLENGE",
      "PREREQUISITE", "WORKED_EXAMPLE", "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE", "RECAP", "AUDIO", "ANIMATION");
  private static final Set<String> LEARNER_RENDERED_BLOCK_TYPES = Set.of(
      "EXPLANATION", "PREREQUISITE", "WORKED_EXAMPLE", "IMAGE", "DIAGRAM", "VIDEO",
      "AUDIO", "ANIMATION", "GUIDED_PRACTICE", "INDEPENDENT_PRACTICE", "CHALLENGE",
      "HINT", "AI_HELP", "SUMMARY", "RECAP");
  private static final Set<String> QUESTION_TYPES = Set.of(
      "MCQ", "TRUE_FALSE", "INPUT", "MATCH", "ORDER", "ASSERTION_REASON",
      "CASE_BASED", "SHORT_ANSWER", "LONG_ANSWER", "NUMERICAL", "DIAGRAM", "MAP", "SOURCE_BASED");
  private static final Set<String> DIFFICULTIES = Set.of("FOUNDATION", "CORE", "CHALLENGE");

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final QuestionHistoryService questionHistory;
  private final AssessmentCatalogService assessmentCatalog;

  @Value("${app.institution.default-code:}")
  private String defaultInstitutionCode = "";

  public AdminContentController(JdbcTemplate jdbc, ObjectMapper mapper, AuthorizationService authorization,
      StaffAuditService staffAudit, QuestionHistoryService questionHistory,
      AssessmentCatalogService assessmentCatalog) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.questionHistory = questionHistory;
    this.assessmentCatalog = assessmentCatalog;
  }

  /** Trusted tenant identity for the current Education Admin staff session. */
  @GetMapping("/institution-context")
  public Map<String,Object> institutionContext(
      @RequestAttribute(value="authContext",required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    authorization.requireAuth(context);
    if(context.staffId()==null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "An authenticated Education Admin staff account is required.");
    }
    if(tenant==null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "This staff account has no active HRMS institution binding.");
    }
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select b.assigned_at,b.assigned_by_staff_id,t.id as institution_id,
             t.tenant_code,t.display_name,t.status
      from institution_staff_binding b
      join institution_tenant t on t.id=b.institution_id
      where b.staff_id=? and b.institution_id=? and b.status='ACTIVE'
        and t.status='ACTIVE'
      """,context.staffId(),tenant.institutionId());
    if(rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "This staff account is not currently assigned to the resolved institution by HRMS.");
    }
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    result.put("courseRelationshipScope","SOURCE_ONLY_EDIT_SHARED_READ_ONLY");
    result.put("institutionSwitchingAllowed",false);
    return result;
  }

  /**
   * @deprecated Institution switching has been retired. The active institution is provided by
   * the authenticated HRMS-managed staff binding at /institution-context.
   */
  @GetMapping("/institutions")
  @Deprecated(since="2026-10",forRemoval=false)
  public List<Map<String,Object>> institutions(
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    throw new ResponseStatusException(HttpStatus.GONE,
        "DEPRECATED_INSTITUTION_SELECTOR: Education Admin institutions are assigned in HRMS. Use /api/v1/admin/content/institution-context; institution switching is not supported.");
  }

  @GetMapping("/programs")
  public List<Map<String,Object>> programs(
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_VIEW");
    long institutionId = requireInstitution(tenant);
    List<Map<String,Object>> courses=jdbc.queryForList("""
      select p.id,p.id as course_id,p.public_id,p.public_id_prefix,p.code,p.display_name,
             p.curriculum_version,p.course_type_code,p.content_version_no,p.course_identity_id,
             p.supersedes_program_id,c.code as curriculum_class_code,
             scoped.release_id,scoped.release_version,scoped.release_state,
             scoped.source_institution_id,
             case when scoped.source_institution_id=scoped.assigned_institution_id
               then 'SOURCED' else 'SHARED' end as course_relationship
      from curriculum_program p
      left join curriculum_class c on c.id=p.class_id and c.active=true
      join lateral (
        select release.id as release_id,release.release_version,release.release_state,
               release.source_institution_id,assignment.institution_id as assigned_institution_id
        from institution_course_release release
        join institution_course_assignment assignment
          on assignment.course_release_id=release.id and assignment.institution_id=?
             and assignment.status='ASSIGNED'
        where release.program_id=p.id
        order by release.release_version desc,release.id desc
        limit 1
      ) scoped on true
      where p.status='ACTIVE' and p.code<>'GENERAL'
      order by p.display_name,p.id
      """, institutionId);
    for(Map<String,Object> course:courses) {
      long courseId=((Number)course.get("course_id")).longValue();
      List<Map<String,Object>> subjects=jdbc.queryForList("""
        select s.id as subject_id,s.code as subject_code,s.display_name as subject_name,
          s.class_id,c.code as curriculum_class_code,c.display_name as curriculum_class_name,
          cps.sort_order as course_subject_sort_order
        from curriculum_program_subject cps
        join curriculum_program p on p.id=cps.program_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join curriculum_subject s on s.id=cps.subject_id and s.class_id=cps.class_id and s.active=true
        join curriculum_class c on c.id=s.class_id and c.active=true
        where cps.program_id=?
          and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id and cps.class_id=s.class_id))
        order by cps.sort_order,s.sort_order,s.code
        """,courseId);
      course.put("subjects",subjects);
    }
    return courses;
  }

  /**
   * Metadata-only inventory for classless assessment courses in Content Studio.
   * Deliberately does not use the teacher grading queue's staff-assignment filter or expose
   * attempt/learner data. Question snapshots still require the separate CONTENT_REVIEW route.
   */
  @GetMapping("/courses/{courseId}/assessment-inventory")
  public List<Map<String,Object>> assessmentCourseInventory(
      @PathVariable long courseId,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_VIEW");
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    Boolean assessmentOnly=jdbc.queryForObject("""
      select exists(
        select 1 from curriculum_program p
        join curriculum_program_delivery_mode mode on mode.program_id=p.id
          and mode.delivery_mode='ASSESSMENT_ONLY'
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
          and p.course_type_code='ASSESSMENT'
      )
      """,Boolean.class,courseId);
    if(!Boolean.TRUE.equals(assessmentOnly))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The selected course does not use the classless ASSESSMENT_ONLY delivery model.");
    return jdbc.queryForList("""
      select a.id as assessment_id,a.public_id as test_id,a.title,a.description,a.status,
        a.assessment_type,a.portable_assessment_type,a.source_exam_cycle,
        a.source_package_id,a.source_package_version,a.duration_minutes,a.max_attempts,
        pack.pack_code as test_pack_code,pack.pack_version as test_pack_version,
        pack.title as test_pack_title,pack.status as test_pack_status,
        (select count(*) from assessment_question aq where aq.assessment_id=a.id) as question_count,
        (select coalesce(sum(case
          when coalesce(aq.question_snapshot->>'section_result_policy','SCORED') in ('QUALIFYING_ONLY','PRACTICE_ONLY')
            or coalesce(aq.question_snapshot->>'section_code','')='LANGUAGE_QUALIFYING' then 0
          else aq.max_marks end),0)
         from assessment_question aq where aq.assessment_id=a.id) as max_score
      from assessment a
      left join course_assessment_pack_item pack_item on pack_item.assessment_id=a.id
      left join course_assessment_pack pack
        on pack.id=pack_item.pack_id and pack.course_id=a.course_id
      where a.course_id=?
      order by coalesce(a.source_exam_cycle,a.title),a.id
      """,courseId);
  }

  @GetMapping
  public List<Map<String, Object>> list(
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestParam(required = false) String classCode,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String programCode,
      @RequestParam(required = false) Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_VIEW");
    long institutionId = requireInstitution(tenant);
    if (courseId == null || courseId < 1L) {
      throw badRequest("Select an institution-assigned course before viewing curriculum content.");
    }
    requireAssignedCourse(courseId, institutionId);
    StringBuilder sql = new StringBuilder("""
      select c.code as class_code, c.public_id as class_public_id, c.display_name as class_name,
             p.id as course_id,p.public_id as course_public_id,p.code as program_code,p.display_name as program_name,
             s.code as subject_code, s.display_name as subject_name,
             ch.id as chapter_id, ch.code as chapter_code,
             ch.display_name as chapter_name, ch.description as chapter_description,
             coalesce(ch.teaching_order,ch.sort_order) as chapter_sort_order, ch.content_status as chapter_status,
             ch.active as chapter_active, ch.curriculum_source,
             ch.curriculum_source_url, ch.curriculum_source_edition,
             ch.curriculum_source_pages, ch.curriculum_source_verified,
             l.id as lesson_id, l.code as lesson_code, l.title as lesson_title,
             l.summary as lesson_summary, l.estimated_minutes,
             l.sort_order as lesson_sort_order, l.status as lesson_status,
             l.active as lesson_active, l.alignment_source_title,
             l.alignment_source_url, l.alignment_source_edition,
             l.alignment_page_range, l.alignment_source_verified,
             (select count(*) from lesson_block b where b.lesson_id=l.id) as block_count,
             (select count(*) from question q where q.lesson_id=l.id) as question_count,
             coalesce((select max(q.sort_order) from question q where q.lesson_id=l.id),0) as max_question_sort_order
      from curriculum_class c
      join curriculum_subject s on s.class_id=c.id
      left join curriculum_program p on p.id=s.program_id
      left join curriculum_chapter ch on ch.subject_id=s.id
      left join lesson l on l.chapter_id=ch.id
      where 1=1
      """);
    List<Object> args = new ArrayList<>();
    if (courseId != null) {
      sql.append("""
        and exists(select 1 from curriculum_program scope_program
          join curriculum_program_subject cps on cps.program_id=scope_program.id
          where scope_program.id=? and scope_program.status='ACTIVE' and cps.subject_id=s.id
            and (s.program_id=scope_program.id or (s.program_id is null and scope_program.class_id=s.class_id and cps.class_id=s.class_id)))
        """);
      args.add(courseId);
    } else if (programCode == null || programCode.isBlank()) {
      sql.append(" and s.program_id is null");
    } else {
      sql.append(" and upper(p.code)=upper(?) and p.status='ACTIVE'");
      args.add(programCode.trim());
    }
    if (classCode != null && !classCode.isBlank()) {
      sql.append(" and c.code=?");
      args.add(classCode.trim());
    }
    if (subjectCode != null && !subjectCode.isBlank()) {
      sql.append(" and s.code=?");
      args.add(subjectCode.trim());
    }
    if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
      sql.append(" and (upper(coalesce(l.status,''))=? or upper(coalesce(ch.content_status,''))=?)");
      args.add(status.trim().toUpperCase());
      args.add(status.trim().toUpperCase());
    }
    sql.append(" order by c.sort_order,s.sort_order,coalesce(ch.teaching_order,ch.sort_order),l.sort_order,l.id");
    return jdbc.queryForList(sql.toString(), args.toArray());
  }

  @GetMapping("/questions/review-queue")
  public Map<String,Object> questionReviewQueue(
      @RequestParam(required=false, defaultValue="ALL") String status,
      @RequestParam Long courseId,
      @RequestParam(required=false) String classCode,
      @RequestParam(required=false) String subjectCode,
      @RequestParam(required=false) String query,
      @RequestParam(required=false, defaultValue="100") Integer limit,
      @RequestParam(required=false, defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireAuth(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    if (!authorization.hasPermission(context,"CONTENT_VIEW")
        && !authorization.hasPermission(context,"CONTENT_REVIEW")) {
      throw new SecurityException("Content review access required");
    }
    String normalizedStatus=status==null?"ALL":status.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("ALL","DRAFT","REVIEW","APPROVED","PUBLISHED","REJECTED").contains(normalizedStatus)) {
      throw badRequest("Choose a valid question status filter.");
    }
    int rowLimit=Math.max(1,Math.min(limit==null?100:limit,250));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));

    StringBuilder fromWhere=new StringBuilder("""
      from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where exists (
        select 1 from curriculum_program_subject scoped_mapping
        join curriculum_program scoped_course on scoped_course.id=scoped_mapping.program_id
          and scoped_course.status='ACTIVE' and scoped_course.code<>'GENERAL'
        where scoped_mapping.program_id=? and scoped_mapping.subject_id=s.id
          and (s.program_id=scoped_course.id
            or (s.program_id is null and scoped_course.class_id=s.class_id
              and scoped_mapping.class_id=s.class_id))
      )
      """);
    List<Object> filterArgs=new ArrayList<>();
    filterArgs.add(courseId);
    if (!"ALL".equals(normalizedStatus)) {
      fromWhere.append(" and upper(coalesce(q.review_status,'DRAFT'))=?");
      filterArgs.add(normalizedStatus);
    }
    if (classCode!=null&&!classCode.isBlank()) {
      fromWhere.append(" and c.code=?");
      filterArgs.add(classCode.trim());
    }
    if (subjectCode!=null&&!subjectCode.isBlank()) {
      fromWhere.append(" and s.code=?");
      filterArgs.add(subjectCode.trim());
    }
    if (query!=null&&!query.isBlank()) {
      fromWhere.append(" and (q.prompt ilike ? or coalesce(q.topic,'') ilike ? or ch.display_name ilike ? or l.title ilike ?)");
      String pattern="%"+query.trim().substring(0,Math.min(query.trim().length(),160))+"%";
      filterArgs.add(pattern);filterArgs.add(pattern);filterArgs.add(pattern);filterArgs.add(pattern);
    }

    String select="""
      select q.id as question_id,q.lesson_id,q.question_type,q.prompt,q.explanation,q.difficulty,
             q.sort_order,q.active,q.review_status,q.review_notes,q.reviewed_at,
             q.marks,q.exam_format,q.source_kind,q.source_title,q.source_ref,q.source_year,
             q.board,q.topic,q.subtopic,q.skill,q.tags::text as tags,
             l.title as lesson_title,l.status as lesson_status,
             ch.id as chapter_id,ch.display_name as chapter_name,
             c.code as class_code,c.display_name as class_name,
             s.code as subject_code,s.display_name as subject_name,
             coalesce((select jsonb_agg(jsonb_build_object(
                 'key',qo.option_key,'label',qo.label,'is_correct',qo.is_correct
               ) order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb)::text as options,
             (select count(*) from question_review_history h where h.question_id=q.id) as history_count,
             (select h.change_reason from question_review_history h
               where h.question_id=q.id order by h.occurred_at desc,h.id desc limit 1) as latest_history_reason
      """;
    List<Object> pageArgs=new ArrayList<>(filterArgs);
    pageArgs.add(rowLimit);
    pageArgs.add(rowOffset);
    List<Map<String,Object>> items=jdbc.queryForList(select+fromWhere+" "+"""
      order by case upper(coalesce(q.review_status,'DRAFT')) when 'REJECTED' then 0 when 'REVIEW' then 1
        when 'DRAFT' then 2 when 'APPROVED' then 3 when 'PUBLISHED' then 4 else 5 end,
        l.id,q.sort_order,q.id
      limit ? offset ?
      """,pageArgs.toArray());
    Long total=jdbc.queryForObject("select count(*) "+fromWhere,Long.class,filterArgs.toArray());
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("items",items);
    result.put("total",total==null?0L:total);
    result.put("limit",rowLimit);
    result.put("offset",rowOffset);
    return result;
  }

  @GetMapping("/questions/review-summary")
  public Map<String,Object> questionReviewSummary(
      @RequestParam Long courseId,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireAuth(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    if (!authorization.hasPermission(context,"CONTENT_VIEW")
        && !authorization.hasPermission(context,"CONTENT_REVIEW")) {
      throw new SecurityException("Question review access required");
    }
    List<Map<String,Object>> byStatus=jdbc.queryForList("""
      select coalesce(nullif(upper(q.review_status),''),'DRAFT') as review_status,
             q.active,count(*) as count
      from question q
      join lesson l on l.id=q.lesson_id
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      where exists (
        select 1 from curriculum_program_subject scoped_mapping
        join curriculum_program scoped_course on scoped_course.id=scoped_mapping.program_id
          and scoped_course.status='ACTIVE' and scoped_course.code<>'GENERAL'
        where scoped_mapping.program_id=? and scoped_mapping.subject_id=s.id
          and (s.program_id=scoped_course.id
            or (s.program_id is null and scoped_course.class_id=s.class_id
              and scoped_mapping.class_id=s.class_id))
      )
      group by coalesce(nullif(upper(q.review_status),''),'DRAFT'),q.active
      order by review_status,q.active desc
      """,courseId);
    long total=0,activeCount=0,inactiveCount=0;
    for(Map<String,Object> row:byStatus) {
      long count=row.get("count") instanceof Number n?n.longValue():0L;
      total+=count;
      if(Boolean.TRUE.equals(row.get("active"))) activeCount+=count;
      else inactiveCount+=count;
    }
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("totalQuestions",total);
    result.put("activeQuestions",activeCount);
    result.put("inactiveQuestions",inactiveCount);
    result.put("byStatus",byStatus);
    return result;
  }

  @GetMapping("/questions/{questionId}/history")
  public Map<String,Object> questionHistory(
      @PathVariable long questionId,
      @RequestParam Long courseId,
      @RequestParam(defaultValue="10") Integer limit,
      @RequestParam(defaultValue="0") Integer offset,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    context=authorization.requireAuth(context);
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    Boolean inCourse=jdbc.queryForObject("""
      select exists(
        select 1 from question q
        join lesson l on l.id=q.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_program_subject mapping on mapping.program_id=? and mapping.subject_id=s.id
          and mapping.class_id=s.class_id
        join curriculum_program selected_course on selected_course.id=mapping.program_id
          and selected_course.status='ACTIVE' and selected_course.code<>'GENERAL'
        where q.id=? and (s.program_id=selected_course.id
          or (s.program_id is null and selected_course.class_id=s.class_id))
      )
      """,Boolean.class,courseId,questionId);
    if(!Boolean.TRUE.equals(inCourse)) throw notFound("Question",questionId);
    if (!authorization.hasPermission(context,"CONTENT_VIEW")
        && !authorization.hasPermission(context,"CONTENT_REVIEW")) {
      throw new SecurityException("Question history access required");
    }
    Map<String,Object> current=questionHistory.snapshot(questionId);
    if (current.isEmpty()) throw notFound("Question",questionId);
    int rowLimit=Math.max(1,Math.min(limit==null?10:limit,100));
    int rowOffset=Math.max(0,Math.min(offset==null?0:offset,10_000_000));
    Long total=jdbc.queryForObject("select count(*) from question_review_history where question_id=?",Long.class,questionId);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("question",current);
    result.put("history",questionHistory.history(questionId,rowLimit,rowOffset));
    result.put("total",total==null?0L:total);
    result.put("limit",rowLimit);
    result.put("offset",rowOffset);
    return result;
  }

  @GetMapping("/chapters/{chapterId}")
  public Map<String, Object> chapter(
      @PathVariable long chapterId,
      @RequestParam Long courseId,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_VIEW");
    requireChapterInCourse(chapterId, courseId, requireInstitution(tenant));
    return chapterById(chapterId);
  }

  @GetMapping("/lessons/{lessonId}")
  public Map<String, Object> lesson(
      @PathVariable long lessonId,
      @RequestParam Long courseId,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_VIEW");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    return lessonById(lessonId);
  }

  @PostMapping("/chapters")
  @Transactional
  public Map<String, Object> createChapter(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_CREATE");
    long institutionId = requireInstitution(tenant);
    Long requestedCourseId = optionalLong(body.get("courseId"), "courseId");
    if (requestedCourseId == null) throw badRequest("Select an institution-assigned course before creating a chapter.");
    requireAssignedCourse(requestedCourseId, institutionId);
    requireEditableCourse(requestedCourseId, institutionId);
    String classCode = requiredText(body.get("classCode"), 30);
    String subjectCode = requiredText(body.get("subjectCode"), 40);
    String code = requiredText(body.get("code"), 60).toLowerCase();
    if (!code.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw badRequest("Chapter code must use lowercase letters, numbers, and hyphens.");
    }
    String name = requiredText(body.get("displayName"), 160);
    String description = optionalText(body.get("description"), 500);
    String curriculumSource = optionalText(body.get("curriculumSource"), 500);
    String sourceUrl = optionalText(body.get("curriculumSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("curriculumSourceEdition"), 160);
    String sourcePages = optionalText(body.get("curriculumSourcePages"), 160);
    String status = String.valueOf(body.getOrDefault("status", "DRAFT")).trim().toUpperCase();
    if (!CHAPTER_STATUSES.contains(status)) throw badRequest("Chapter status must be DRAFT, PUBLISHED, or ARCHIVED.");
    if (!"DRAFT".equals(status)) {
      throw badRequest("Create chapters as draft; use the separate publication action after source and lesson checks pass.");
    }
    int sortOrder = integerValue(body.getOrDefault("sortOrder", 1), 1, 10000, "sortOrder");
    List<Map<String, Object>> subjects = jdbc.queryForList("""
      select s.id from curriculum_subject s
      join curriculum_class c on c.id=s.class_id and c.active=true
      join curriculum_program p on p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      join curriculum_program_subject ps on ps.program_id=p.id
        and ps.class_id=s.class_id and ps.subject_id=s.id
      where c.code=? and s.code=? and s.active=true
        and (s.program_id=p.id or (s.program_id is null and p.class_id=s.class_id))
      """, requestedCourseId, classCode, subjectCode);
    if (subjects.isEmpty()) throw badRequest("The selected class, course, and subject track does not exist or is inactive.");
    long subjectId = ((Number) subjects.getFirst().get("id")).longValue();
    Long chapterId = jdbc.queryForObject("""
      insert into curriculum_chapter(subject_id,code,display_name,description,sort_order,teaching_order,active,content_status,
                                     curriculum_source,curriculum_source_url,curriculum_source_edition,
                                     curriculum_source_pages,curriculum_source_verified)
      values(?,?,?,?,?,?,?,?,?,?,?,?,false)
      returning id
      """, Long.class, subjectId, code, name, description, sortOrder, sortOrder,
      !"ARCHIVED".equals(status), status, curriculumSource, sourceUrl, sourceEdition, sourcePages);
    staffAudit.recordAction(context,"/api/v1/admin/content/chapters/"+chapterId+"/created",
        "Created chapter '"+name+"' in class "+classCode+" / "+subjectCode+" as DRAFT.");
    return chapterById(chapterId);
  }

  @PostMapping("/lessons")
  @Transactional
  public Map<String, Object> createLesson(
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorize(token, context, "CONTENT_CREATE");
    long institutionId = requireInstitution(tenant);
    Long courseId = optionalLong(body.get("courseId"), "courseId");
    if (courseId == null) throw badRequest("Select an institution-assigned course before creating a micro-topic.");
    long chapterId = longValue(body.get("chapterId"), "chapterId");
    requireChapterInCourse(chapterId, courseId, institutionId);
    requireEditableCourse(courseId, institutionId);
    chapterById(chapterId);
    String code = requiredText(body.get("code"), 100).toLowerCase();
    if (!code.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw badRequest("Lesson code must use lowercase letters, numbers, and hyphens.");
    }
    String title = requiredText(body.get("title"), 240);
    String summary = optionalText(body.get("summary"), 800);
    int minutes = integerValue(body.getOrDefault("estimatedMinutes", 10), 1, 120, "estimatedMinutes");
    int sortOrder = integerValue(body.getOrDefault("sortOrder", 1), 1, 10000, "sortOrder");
    String sourceTitle = optionalText(body.get("alignmentSourceTitle"), 300);
    String sourceUrl = optionalText(body.get("alignmentSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("alignmentSourceEdition"), 160);
    String sourcePages = optionalText(body.get("alignmentPageRange"), 160);
    String status = String.valueOf(body.getOrDefault("status", "DRAFT")).trim().toUpperCase();
    if (!LESSON_STATUSES.contains(status)) throw badRequest("Lesson status must be DRAFT, REVIEW, PUBLISHED, or ARCHIVED.");
    if (!"DRAFT".equals(status)) {
      throw badRequest("Create micro-topics as drafts; use Submit for review, the review queue, and the separate Publish action for later states.");
    }
    Long lessonId = jdbc.queryForObject("""
      insert into lesson(chapter_id,code,title,summary,estimated_minutes,status,sort_order,active,
                         alignment_source_title,alignment_source_url,alignment_source_edition,
                         alignment_page_range,alignment_source_verified)
      values(?,?,?,?,?,?,?,?,?,?,?, ?,false)
      returning id
      """, Long.class, chapterId, code, title, summary, minutes, status, sortOrder,
      !"ARCHIVED".equals(status), sourceTitle, sourceUrl, sourceEdition, sourcePages);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/created",
        "Created micro-topic '"+title+"' under chapter "+chapterId+" as DRAFT.");
    return lessonById(lessonId);
  }

  @PatchMapping("/chapters/{chapterId}")
  @Transactional
  public Map<String, Object> updateChapter(
      @PathVariable long chapterId,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requireAuth(context);
    Long courseId = optionalLong(body.get("courseId"), "courseId");
    if (courseId == null) throw badRequest("Select a course before saving this chapter.");
    requireChapterInCourse(chapterId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current = chapterById(chapterId);
    String name = requiredText(body.get("displayName"), 160);
    String description = optionalText(body.get("description"), 500);
    String status = requiredText(body.get("status"), 20).toUpperCase();
    if (!CHAPTER_STATUSES.contains(status)) throw badRequest("Chapter status must be DRAFT, PUBLISHED, or ARCHIVED.");
    String curriculumSource = optionalText(body.get("curriculumSource"), 500);
    String sourceUrl = optionalText(body.get("curriculumSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("curriculumSourceEdition"), 160);
    String sourcePages = optionalText(body.get("curriculumSourcePages"), 160);
    boolean wasSourceVerified = Boolean.TRUE.equals(current.get("curriculum_source_verified"));
    boolean sourceDetailsChanged = !Objects.equals(curriculumSource,current.get("curriculum_source"))
        || !Objects.equals(sourceUrl,current.get("curriculum_source_url"))
        || !Objects.equals(sourceEdition,current.get("curriculum_source_edition"))
        || !Objects.equals(sourcePages,current.get("curriculum_source_pages"));
    boolean sourceVerified = booleanValue(body.get("curriculumSourceVerified"), false);
    int sortOrder = integerValue(body.get("sortOrder"), 1, 10000, "sortOrder");
    boolean canVerifySource = authorization.hasPermission(context,"CONTENT_REVIEW");
    if (sourceDetailsChanged && wasSourceVerified && !canVerifySource) {
      sourceVerified = false;
    } else if (sourceVerified && (!wasSourceVerified || sourceDetailsChanged) && !canVerifySource) {
      throw badRequest("A content reviewer must verify the official source before it can be marked verified.");
    }
    boolean fieldsChanged = !Objects.equals(name,current.get("chapter_name"))
        || !Objects.equals(description,current.get("chapter_description"))
        || sortOrder != ((Number)current.get("chapter_sort_order")).intValue()
        || !Objects.equals(curriculumSource,current.get("curriculum_source"))
        || !Objects.equals(sourceUrl,current.get("curriculum_source_url"))
        || !Objects.equals(sourceEdition,current.get("curriculum_source_edition"))
        || !Objects.equals(sourcePages,current.get("curriculum_source_pages"))
        || sourceVerified != Boolean.TRUE.equals(current.get("curriculum_source_verified"));
    String oldStatus=String.valueOf(current.get("chapter_status"));
    if (status.equals(oldStatus) && !fieldsChanged) {
      if ("PUBLISHED".equals(status) || "ARCHIVED".equals(status)) {
        authorization.requirePermission(context,"CONTENT_PUBLISH");
      } else {
        authorization.requirePermission(context,"CONTENT_EDIT");
      }
      return current;
    }
    if (fieldsChanged) authorization.requirePermission(context,"CONTENT_EDIT");
    if (!status.equals(oldStatus) || "PUBLISHED".equals(status) || "ARCHIVED".equals(status)
        || "PUBLISHED".equals(oldStatus) || "ARCHIVED".equals(oldStatus)) {
      authorization.requirePermission(context,"CONTENT_PUBLISH");
    } else if (!fieldsChanged) {
      authorization.requirePermission(context,"CONTENT_EDIT");
    }
    // Editing a live chapter creates a new draft that must pass publication checks again.
    if ("PUBLISHED".equals(oldStatus) && fieldsChanged && "PUBLISHED".equals(status)) {
      status = "DRAFT";
    }
    if (sourceVerified) requireCompleteSourceReference(curriculumSource, sourceUrl, sourceEdition, sourcePages, "chapter");
    if ("PUBLISHED".equals(status)) {
      if (!sourceVerified) throw badRequest("A chapter cannot be published until its official source edition and page mapping are verified.");
      Long readyLessons = current.get("publish_ready_lesson_count") instanceof Number n
          ? n.longValue() : 0L;
      if (readyLessons == null || readyLessons == 0) {
        throw badRequest("Add at least one lesson with active teaching blocks and valid multiple-choice questions before publishing this chapter.");
      }
    }
    int changed = jdbc.update("""
      update curriculum_chapter
      set display_name=?, description=?, sort_order=?, teaching_order=?, content_status=?, active=?,
          curriculum_source=?, curriculum_source_url=?, curriculum_source_edition=?,
          curriculum_source_pages=?, curriculum_source_verified=?
      where id=?
      """, name, description, sortOrder, sortOrder, status, !"ARCHIVED".equals(status),
      curriculumSource, sourceUrl, sourceEdition, sourcePages, sourceVerified, chapterId);
    if (changed == 0) throw notFound("Chapter", chapterId);
    assessmentCatalog.refreshGeneratedAssessmentsForChapter(chapterId);
    String chapterAction = "PUBLISHED".equals(status) ? "publish"
        : ("PUBLISHED".equals(oldStatus) && "DRAFT".equals(status) ? "unpublish" : "updated");
    String chapterActionText = "PUBLISHED".equals(status) ? "Published chapter"
        : ("PUBLISHED".equals(oldStatus) && "DRAFT".equals(status) ? "Unpublished chapter" : "Saved chapter");
    staffAudit.recordAction(context,"/api/v1/admin/content/chapters/"+chapterId+"/"+chapterAction,
        chapterActionText+" '"+name+"'; resulting workflow status="+status+".");
    return chapterById(chapterId);
  }

  @PostMapping("/chapters/{chapterId}/publish")
  @Transactional
  public Map<String,Object> publishChapter(
      @PathVariable long chapterId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    requireChapterInCourse(chapterId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current=chapterById(chapterId);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("displayName",current.get("chapter_name"));
    body.put("description",current.get("chapter_description"));
    body.put("status","PUBLISHED");
    body.put("courseId", courseId);
    body.put("sortOrder",((Number)current.get("chapter_sort_order")).intValue());
    body.put("curriculumSource",current.get("curriculum_source"));
    body.put("curriculumSourceUrl",current.get("curriculum_source_url"));
    body.put("curriculumSourceEdition",current.get("curriculum_source_edition"));
    body.put("curriculumSourcePages",current.get("curriculum_source_pages"));
    body.put("curriculumSourceVerified",Boolean.TRUE.equals(current.get("curriculum_source_verified")));
    return updateChapter(chapterId,body,null,context,tenant);
  }

  @PostMapping("/chapters/{chapterId}/unpublish")
  @Transactional
  public Map<String,Object> unpublishChapter(
      @PathVariable long chapterId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    requireChapterInCourse(chapterId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current=chapterById(chapterId);
    Map<String,Object> body=new LinkedHashMap<>();
    body.put("displayName",current.get("chapter_name"));
    body.put("description",current.get("chapter_description"));
    body.put("status","DRAFT");
    body.put("courseId", courseId);
    body.put("sortOrder",((Number)current.get("chapter_sort_order")).intValue());
    body.put("curriculumSource",current.get("curriculum_source"));
    body.put("curriculumSourceUrl",current.get("curriculum_source_url"));
    body.put("curriculumSourceEdition",current.get("curriculum_source_edition"));
    body.put("curriculumSourcePages",current.get("curriculum_source_pages"));
    body.put("curriculumSourceVerified",Boolean.TRUE.equals(current.get("curriculum_source_verified")));
    return updateChapter(chapterId,body,null,context,tenant);
  }

  @PostMapping("/chapters/{chapterId}/verify-source")
  @Transactional
  public Map<String,Object> verifyChapterSource(
      @PathVariable long chapterId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_REVIEW");
    requireChapterInCourse(chapterId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current=chapterById(chapterId);
    requireCompleteSourceReference(
        current.get("curriculum_source")==null?null:String.valueOf(current.get("curriculum_source")),
        current.get("curriculum_source_url")==null?null:String.valueOf(current.get("curriculum_source_url")),
        current.get("curriculum_source_edition")==null?null:String.valueOf(current.get("curriculum_source_edition")),
        current.get("curriculum_source_pages")==null?null:String.valueOf(current.get("curriculum_source_pages")),
        "chapter");
    jdbc.update("update curriculum_chapter set curriculum_source_verified=true where id=?",chapterId);
    staffAudit.recordAction(context,"/api/v1/admin/content/chapters/"+chapterId+"/verify-source",
        "Verified official curriculum source for chapter '"+current.get("chapter_name")+"'.");
    return chapterById(chapterId);
  }

  @PostMapping("/lessons/{lessonId}/verify-source")
  @Transactional
  public Map<String,Object> verifyLessonSource(
      @PathVariable long lessonId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_REVIEW");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current=lessonById(lessonId);
    requireCompleteSourceReference(
        current.get("alignment_source_title")==null?null:String.valueOf(current.get("alignment_source_title")),
        current.get("alignment_source_url")==null?null:String.valueOf(current.get("alignment_source_url")),
        current.get("alignment_source_edition")==null?null:String.valueOf(current.get("alignment_source_edition")),
        current.get("alignment_page_range")==null?null:String.valueOf(current.get("alignment_page_range")),
        "micro-topic");
    jdbc.update("update lesson set alignment_source_verified=true where id=?",lessonId);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/verify-source",
        "Verified official textbook mapping for micro-topic '"+current.get("lesson_title")+"'.");
    return lessonById(lessonId);
  }

  @PostMapping("/lessons/{lessonId}/preview-check")
  @Transactional
  public Map<String,Object> markLessonPreviewChecked(
      @PathVariable long lessonId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_SUBMIT");
    Long courseId = optionalLong(body.get("courseId"), "courseId");
    if (courseId == null) throw badRequest("Select a course before confirming this lesson preview.");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    long expectedRevision=longValue(body.get("contentRevision"),"contentRevision");
    Long currentRevision;
    try {
      currentRevision=jdbc.queryForObject("select content_revision from lesson where id=?",Long.class,lessonId);
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw notFound("Lesson",lessonId);
    }
    if(currentRevision==null || currentRevision.longValue()!=expectedRevision) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The saved topic changed after this preview loaded. Refresh the topic, inspect the latest preview, and confirm that version.");
    }
    int updated=jdbc.update("""
        update lesson
        set preview_checked_revision=content_revision,preview_checked_at=now(),
            preview_checked_by_staff_id=?
        where id=? and content_revision=?
        """,context.staffId(),lessonId,expectedRevision);
    if(updated!=1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The saved topic changed while confirming the preview. Refresh and check the latest version.");
    }
    Map<String,Object> result=lessonById(lessonId);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/preview-check",
        "Confirmed learner preview for micro-topic '"+result.get("lesson_title")+"' at content revision "+expectedRevision+".");
    return result;
  }

  @PostMapping("/lessons/{lessonId}/submit")
  @Transactional
  public Map<String,Object> submitLessonForReview(
      @PathVariable long lessonId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_SUBMIT");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current=lessonById(lessonId);
    String currentStatus=String.valueOf(current.get("lesson_status"));
    if ("PUBLISHED".equals(currentStatus)||"ARCHIVED".equals(currentStatus)) {
      throw badRequest("Unpublish or restore this lesson before submitting it for review.");
    }
    validateLessonForSubmission(lessonId);
    jdbc.update("update lesson set status='REVIEW', active=true where id=?",lessonId);
    // Keep rejected questions parked with their feedback until the author edits them.
    List<Long> resubmittedQuestionIds=jdbc.queryForList(
        "select id from question where lesson_id=? and active=true and review_status='DRAFT' order by id",
        Long.class,lessonId);
    Map<Long,Map<String,Object>> resubmittedBefore=new LinkedHashMap<>();
    for(Long questionId:resubmittedQuestionIds) resubmittedBefore.put(questionId,questionHistory.snapshot(questionId));
    jdbc.update("""
        update question
        set review_status='REVIEW',
            review_notes=coalesce(review_notes,'Submitted with this micro-topic for review.'),
            reviewed_at=null,reviewed_by_staff_id=null
        where lesson_id=? and active=true and review_status='DRAFT'
        """,lessonId);
    for(Long questionId:resubmittedQuestionIds) {
      questionHistory.recordChange(questionId,lessonId,"STATUS_CHANGED",context,
          "Question submitted for review with its micro-topic.",resubmittedBefore.get(questionId),questionHistory.snapshot(questionId));
    }
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/submit",
        "Submitted micro-topic '"+current.get("lesson_title")+"' for review.");
    assessmentCatalog.refreshGeneratedAssessmentsForLesson(lessonId);
    return lessonById(lessonId);
  }

  @PostMapping("/lessons/{lessonId}/publish")
  @Transactional
  public Map<String,Object> publishLesson(
      @PathVariable long lessonId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current=lessonById(lessonId);
    String currentStatus=String.valueOf(current.get("lesson_status"));
    if (!"REVIEW".equals(currentStatus) && !"ARCHIVED".equals(currentStatus)) {
      throw badRequest("Submit the micro-topic for review before publishing it.");
    }
    validateLessonForPublishing(lessonId);
    jdbc.update("update lesson set status='PUBLISHED', active=true where id=?",lessonId);
    assessmentCatalog.refreshGeneratedAssessmentsForLesson(lessonId);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/publish",
        "Published micro-topic '"+current.get("lesson_title")+"'.");
    return lessonById(lessonId);
  }

  @PostMapping("/lessons/{lessonId}/unpublish")
  @Transactional
  public Map<String,Object> unpublishLesson(
      @PathVariable long lessonId,
      @RequestParam Long courseId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_PUBLISH");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    lessonById(lessonId);
    jdbc.update("update lesson set status='DRAFT', active=true where id=?",lessonId);
    Map<String,Object> result=lessonById(lessonId);
    assessmentCatalog.refreshGeneratedAssessmentsForLesson(lessonId);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/unpublish",
        "Unpublished micro-topic '"+result.get("lesson_title")+"' and returned it to draft.");
    return result;
  }

  @PostMapping("/lessons/{lessonId}/questions/{questionId}/review")
  @Transactional
  public Map<String,Object> reviewQuestion(
      @PathVariable long lessonId,
      @PathVariable long questionId,
      @RequestParam Long courseId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE,required=false)
          InstitutionTenantContext tenant) {
    authorization.requirePermission(context,"CONTENT_REVIEW");
    long institutionId=requireInstitution(tenant);
    requireAssignedCourse(courseId,institutionId);
    requireEditableCourse(courseId,institutionId);
    Boolean inCourse=jdbc.queryForObject("""
      select exists(
        select 1 from question q
        join lesson l on l.id=q.lesson_id
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id
        join curriculum_program_subject mapping on mapping.program_id=? and mapping.subject_id=s.id
          and mapping.class_id=s.class_id
        join curriculum_program selected_course on selected_course.id=mapping.program_id
          and selected_course.status='ACTIVE' and selected_course.code<>'GENERAL'
        where q.id=? and q.lesson_id=? and (s.program_id=selected_course.id
          or (s.program_id is null and selected_course.class_id=s.class_id))
      )
      """,Boolean.class,courseId,questionId,lessonId);
    if(!Boolean.TRUE.equals(inCourse)) throw notFound("Question",questionId);
    String status=requiredText(body.get("status"),20).toUpperCase();
    if (!Set.of("DRAFT","REVIEW","APPROVED","REJECTED").contains(status)) {
      throw badRequest("Review status must be Draft, Needs review, Approved or Rejected.");
    }
    Map<String,Object> lessonForReview=lessonById(lessonId);
    String lessonStatus=String.valueOf(lessonForReview.get("lesson_status")).toUpperCase(Locale.ROOT);
    if (!Set.of("REVIEW","PUBLISHED").contains(lessonStatus)) {
      throw badRequest("Submit a draft micro-topic for review first. Questions on already-published micro-topics can be reviewed without unpublishing the lesson.");
    }
    String notes=optionalText(body.get("reviewNotes"),1200);
    if ("REJECTED".equals(status) && (notes==null || notes.isBlank())) {
      throw badRequest("Add clear feedback so the author knows what must be corrected.");
    }
    List<Map<String,Object>> currentQuestionRows=jdbc.queryForList(
        "select review_status from question where id=? and lesson_id=?",questionId,lessonId);
    if(currentQuestionRows.isEmpty()) throw notFound("Question",questionId);
    String currentQuestionStatus=String.valueOf(currentQuestionRows.getFirst().getOrDefault("review_status","DRAFT")).toUpperCase(Locale.ROOT);
    if (Set.of("APPROVED","REJECTED").contains(status) && !"REVIEW".equals(currentQuestionStatus)) {
      throw badRequest("Move this question to REVIEW first. Approval and rejection are only allowed for questions explicitly submitted for review.");
    }
    if ("APPROVED".equals(status)) validateQuestionForReview(lessonId,questionId);
    Map<String,Object> beforeSnapshot=questionHistory.snapshot(questionId);
    int changed=jdbc.update("""
        update question
        set review_status=?, review_notes=?,
            reviewed_at=case when ?='APPROVED' then now() else null end,
            reviewed_by_staff_id=case when ?='APPROVED' then ? else null end
        where id=? and lesson_id=?
        """,status,notes,status,status,context.staffId(),questionId,lessonId);
    if (changed==0) throw notFound("Question",questionId);
    Map<String,Object> afterSnapshot=questionHistory.snapshot(questionId);
    questionHistory.recordChange(questionId,lessonId,"REVIEW_DECISION",context,
        notes==null||notes.isBlank()?"Reviewer set status to "+status+".":notes,beforeSnapshot,afterSnapshot);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/questions/"+questionId+"/review",
        "Question review state changed to "+status+"; reviewer notes "+(notes==null||notes.isBlank()?"not supplied":"supplied")+".");
    assessmentCatalog.refreshGeneratedAssessmentsForLesson(lessonId);
    return jdbc.queryForMap("""
        select id,lesson_id,question_type,prompt,review_status,review_notes,reviewed_at,active
        from question where id=? and lesson_id=?
        """,questionId,lessonId);
  }

  @PostMapping("/lessons/{lessonId}/questions/append-drafts")
  @Transactional
  public Map<String,Object> appendDraftQuestions(
      @PathVariable long lessonId,
      @RequestParam Long courseId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context=authorization.requirePermission(context,"CONTENT_EDIT");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> lesson=lessonById(lessonId);
    String lessonStatus=String.valueOf(lesson.get("lesson_status")).toUpperCase(Locale.ROOT);
    if ("ARCHIVED".equals(lessonStatus)
        || Boolean.FALSE.equals(lesson.get("lesson_active"))
        || Boolean.FALSE.equals(lesson.get("chapter_active"))) {
      throw badRequest("Practice questions can only be appended to an active, non-archived lesson.");
    }
    List<Map<String,Object>> requested=objectList(body.get("questions"),"questions");
    if (requested.isEmpty() || requested.size()>200) {
      throw badRequest("Append between 1 and 200 draft questions per request.");
    }

    Set<String> existingContentFingerprints=new HashSet<>();
    Set<String> existingPackageIds=new HashSet<>();
    List<Map<String,Object>> stored=jdbc.queryForList("""
        select q.question_type,q.prompt,q.explanation,q.difficulty,q.marks,q.exam_format,
               q.answer_payload::text as answer_payload,q.tags::text as tags,
               coalesce((select jsonb_agg(jsonb_build_object(
                 'key',qo.option_key,'label',qo.label,'correct',qo.is_correct,'sort_order',qo.sort_order
               ) order by qo.sort_order) from question_option qo where qo.question_id=q.id),'[]'::jsonb)::text as options
        from question q where q.lesson_id=?
        """,lessonId);
    for (Map<String,Object> row:stored) {
      existingContentFingerprints.add(questionContentFingerprint(row));
      for (String tag:questionTagValues(row.get("tags"))) {
        if (tag.startsWith("qe-practice-id:")) existingPackageIds.add(tag);
      }
    }

    List<Map<String,Object>> additions=new ArrayList<>();
    int skipped=0;
    for (Map<String,Object> question:requested) {
      if (question.get("id")!=null) {
        throw badRequest("The append-drafts endpoint accepts new questions only; existing question IDs are not allowed.");
      }
      List<String> tags=questionTagValues(question.get("tags"));
      String packageTag=tags.stream().filter(tag->tag.startsWith("qe-practice-id:")).findFirst()
          .orElseThrow(()->badRequest("Each appended question needs a stable qe-practice-id tag for safe retries."));
      requiredText(question.get("prompt"),1000);
      String contentFingerprint=questionContentFingerprint(question);
      // An ID-tag match guarantees safe retries. Otherwise skip only an exact
      // content+answer duplicate; equal prompt text alone is not enough because
      // different choices or expected answers can be legitimate distinct questions.
      if (existingPackageIds.contains(packageTag) || existingContentFingerprints.contains(contentFingerprint)) {
        skipped++;
        continue;
      }
      existingPackageIds.add(packageTag);
      existingContentFingerprints.add(contentFingerprint);
      additions.add(question);
    }

    if (!additions.isEmpty()) updateQuestions(lessonId,additions,context,false);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/questions/append-drafts",
        "Appended "+additions.size()+" new practice question(s) as DRAFT; lesson status remained "+lessonStatus+".");
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("lessonId",lessonId);
    result.put("lessonStatus",lessonStatus);
    result.put("requested",requested.size());
    result.put("added",additions.size());
    result.put("skippedDuplicate",skipped);
    result.put("newQuestionStatus","DRAFT");
    result.put("lessonStatusChanged",false);
    result.put("teachingBlocksChanged",false);
    result.put("publishedOrApproved",false);
    return result;
  }

  String questionContentFingerprint(Map<String,Object> question) {
    Map<String,Object> signature=new LinkedHashMap<>();
    signature.put("question_type",String.valueOf(first(question,"question_type","questionType")).toUpperCase(Locale.ROOT));
    signature.put("prompt",normalizeQuestionPrompt(String.valueOf(question.get("prompt"))));
    signature.put("explanation",question.get("explanation")==null?"":String.valueOf(question.get("explanation")).trim());
    signature.put("difficulty",String.valueOf(question.get("difficulty")).toUpperCase(Locale.ROOT));
    signature.put("marks",question.get("marks"));
    signature.put("exam_format",question.get("exam_format")==null?question.get("examFormat"):question.get("exam_format"));
    signature.put("answer_payload",canonicalJson(parsePotentialJson(first(question,"answer_payload","answerPayload"))));
    signature.put("options",canonicalOptions(question.get("options")));
    try {
      return mapper.writeValueAsString(canonicalJson(signature));
    } catch(JacksonException ex) {
      throw new IllegalStateException("Could not fingerprint question content.",ex);
    }
  }

  private Object parsePotentialJson(Object raw) {
    if (!(raw instanceof String text)) return raw;
    try {
      return mapper.readValue(text,Object.class);
    } catch(JacksonException ex) {
      return raw;
    }
  }

  private Object canonicalJson(Object value) {
    if(value instanceof Map<?,?> map) {
      Map<String,Object> sorted=new java.util.TreeMap<>();
      for(Map.Entry<?,?> entry:map.entrySet()) {
        if(entry.getKey() instanceof String key) sorted.put(key,canonicalJson(entry.getValue()));
      }
      return sorted;
    }
    if(value instanceof List<?> list) {
      List<Object> ordered=new ArrayList<>();
      for(Object item:list) ordered.add(canonicalJson(item));
      return ordered;
    }
    return value;
  }

  private List<Map<String,Object>> canonicalOptions(Object raw) {
    Object parsed=parsePotentialJson(raw);
    if(!(parsed instanceof List<?> values)) return List.of();
    List<Map<String,Object>> options=new ArrayList<>();
    int index=0;
    for(Object item:values) {
      if(!(item instanceof Map<?,?> option)) continue;
      index++;
      Map<String,Object> normalized=new LinkedHashMap<>();
      Object key=first(stringKeyed(option),"key","option_key");
      Object label=option.get("label");
      Object correct=first(stringKeyed(option),"correct","is_correct");
      Object order=first(stringKeyed(option),"sort_order","sortOrder");
      normalized.put("key",key==null?"":String.valueOf(key));
      normalized.put("label",label==null?"":String.valueOf(label).trim());
      normalized.put("correct",booleanValue(correct,false));
      normalized.put("sort_order",order instanceof Number number?number.intValue():index);
      options.add(normalized);
    }
    return options;
  }

  private Map<String,Object> stringKeyed(Map<?,?> source) {
    Map<String,Object> result=new LinkedHashMap<>();
    for(Map.Entry<?,?> entry:source.entrySet()) {
      if(entry.getKey() instanceof String key) result.put(key,entry.getValue());
    }
    return result;
  }

  private String normalizeQuestionPrompt(String value) {
    return Normalizer.normalize(value,Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
  }

  private List<String> questionTagValues(Object raw) {
    Object parsed;
    try {
      parsed=mapper.readValue(jsonText(raw,"tags","[]"),Object.class);
    } catch(JacksonException ex) {
      throw badRequest("Question tags must be a valid JSON array.");
    }
    if (!(parsed instanceof List<?> values)) throw badRequest("Question tags must be a JSON array.");
    List<String> result=new ArrayList<>();
    for(Object value:values) {
      if(value==null) continue;
      String tag=String.valueOf(value).trim();
      if(!tag.isBlank()) result.add(tag);
    }
    return result;
  }

  @PatchMapping("/lessons/{lessonId}")
  @Transactional
  public Map<String, Object> updateLesson(
      @PathVariable long lessonId,
      @RequestBody Map<String, Object> body,
      @RequestHeader(value = "X-Admin-Token", required = false) String token,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    authorization.requireAuth(context);
    Long courseId = optionalLong(body.get("courseId"), "courseId");
    if (courseId == null) throw badRequest("Select a course before saving this lesson.");
    requireLessonInCourse(lessonId, courseId, requireInstitution(tenant));
    requireEditableCourse(courseId, requireInstitution(tenant));
    Map<String,Object> current = lessonById(lessonId);
    String title = requiredText(body.get("title"), 240);
    String summary = optionalText(body.get("summary"), 800);
    int minutes = integerValue(body.get("estimatedMinutes"), 1, 120, "estimatedMinutes");
    int sortOrder = integerValue(body.get("sortOrder"), 1, 10000, "sortOrder");
    String sourceTitle = optionalText(body.get("alignmentSourceTitle"), 300);
    String sourceUrl = optionalText(body.get("alignmentSourceUrl"), 2000);
    String sourceEdition = optionalText(body.get("alignmentSourceEdition"), 160);
    String sourcePages = optionalText(body.get("alignmentPageRange"), 160);
    boolean wasSourceVerified = Boolean.TRUE.equals(current.get("alignment_source_verified"));
    boolean sourceDetailsChanged = !Objects.equals(sourceTitle,current.get("alignment_source_title"))
        || !Objects.equals(sourceUrl,current.get("alignment_source_url"))
        || !Objects.equals(sourceEdition,current.get("alignment_source_edition"))
        || !Objects.equals(sourcePages,current.get("alignment_page_range"));
    boolean sourceVerified = booleanValue(body.get("alignmentSourceVerified"), false);
    boolean canVerifySource = authorization.hasPermission(context,"CONTENT_REVIEW");
    if (sourceDetailsChanged && wasSourceVerified && !canVerifySource) {
      sourceVerified = false;
    } else if (sourceVerified && (!wasSourceVerified || sourceDetailsChanged) && !canVerifySource) {
      throw badRequest("A content reviewer must verify the official source before it can be marked verified.");
    }
    String status = requiredText(body.get("status"), 20).toUpperCase();
    if (!LESSON_STATUSES.contains(status)) throw badRequest("Lesson status must be DRAFT, REVIEW, PUBLISHED, or ARCHIVED.");
    authorization.requirePermission(context,"CONTENT_EDIT");
    String previousStatus=String.valueOf(current.get("lesson_status"));
    if ("REVIEW".equals(status) && !"REVIEW".equals(previousStatus)) {
      throw badRequest("Use the separate Submit for review action so the topic is validated before reviewers receive it.");
    }
    if ("PUBLISHED".equals(status) || "ARCHIVED".equals(status)
        || "PUBLISHED".equals(previousStatus)
        || "ARCHIVED".equals(previousStatus)) {
      authorization.requirePermission(context,"CONTENT_PUBLISH");
    }
    if ("PUBLISHED".equals(status) && !"PUBLISHED".equals(previousStatus)) {
      throw badRequest("Use the separate Publish action after review checks are complete.");
    }
    // Any save to submitted, published or archived content creates a draft that must be previewed/submitted again.
    if (Set.of("PUBLISHED","REVIEW","ARCHIVED").contains(previousStatus)) status="DRAFT";
    if (sourceVerified) requireCompleteSourceReference(sourceTitle, sourceUrl, sourceEdition, sourcePages, "lesson");
    if ("PUBLISHED".equals(status) && !sourceVerified) {
      throw badRequest("A lesson cannot be published until its textbook/teacher-guide edition and page alignment are verified.");
    }

    if (body.containsKey("blocks")) replaceBlocks(lessonId, body.get("blocks"), context);
    boolean questionContentChanged = body.containsKey("questions") && updateQuestions(lessonId, body.get("questions"), context);
    if ("PUBLISHED".equals(status) && questionContentChanged) {
      status = "REVIEW";
    } else if ("PUBLISHED".equals(status)) {
      validateLessonForPublishing(lessonId);
    }

    jdbc.update("""
      update lesson
      set title=?, summary=?, estimated_minutes=?, sort_order=?, status=?, active=?,
          alignment_source_title=?, alignment_source_url=?, alignment_source_edition=?,
          alignment_page_range=?, alignment_source_verified=?,
          content_revision=content_revision+1,preview_checked_revision=null,
          preview_checked_at=null,preview_checked_by_staff_id=null
      where id=?
      """, title, summary, minutes, sortOrder, status, !"ARCHIVED".equals(status),
      sourceTitle, sourceUrl, sourceEdition, sourcePages, sourceVerified, lessonId);
    Map<String,Object> result=lessonById(lessonId);
    staffAudit.recordAction(context,"/api/v1/admin/content/lessons/"+lessonId+"/updated",
        "Saved micro-topic '"+result.get("lesson_title")+"'; resulting workflow status="+result.get("lesson_status")+".");
    assessmentCatalog.refreshGeneratedAssessmentsForLesson(lessonId);
    return result;
  }

  static String numericAnswerKeyValidationError(Map<String,Object> answer) {
    if (!"NUMERIC".equals(answer.get("kind"))) {
      return "Add a numeric answer key before approval.";
    }
    BigDecimal expected = numericDecimal(answer.get("value"));
    if (expected == null) return "The numeric answer key must contain a valid number.";
    Object rawTolerance = answer.get("tolerance");
    BigDecimal tolerance = numericDecimal(rawTolerance == null ? "0" : rawTolerance);
    if (tolerance == null) return "Numeric tolerance must be a valid number.";
    if (tolerance.signum() < 0) return "Numeric tolerance cannot be negative.";
    return null;
  }

  private static BigDecimal numericDecimal(Object raw) {
    if (!(raw instanceof Number) && !(raw instanceof String)) return null;
    String value = String.valueOf(raw).trim();
    if (value.isEmpty()) return null;
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private void validateQuestionForReview(long lessonId,long questionId) {
    Map<String,Object> q;
    try {
      q=jdbc.queryForMap("""
          select question_type,prompt,source_kind,source_id,source_ref,source_year,
                 source_title,board,answer_payload::text as answer_payload
          from question where id=? and lesson_id=?
          """,questionId,lessonId);
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw notFound("Question",questionId);
    }
    String type=String.valueOf(q.get("question_type")).toUpperCase();
    if (!Set.of("MCQ","TRUE_FALSE","INPUT","NUMERICAL","SHORT_ANSWER","LONG_ANSWER").contains(type)) {
      throw badRequest("Use MCQ, TRUE_FALSE, INPUT, NUMERICAL, SHORT_ANSWER, or LONG_ANSWER for assessed questions.");
    }
    if (q.get("prompt")==null||String.valueOf(q.get("prompt")).isBlank()) {
      throw badRequest("Add a question prompt before approval.");
    }
    Map<String,Object> answer;
    try {
      Object parsed=mapper.readValue(String.valueOf(q.get("answer_payload")),Object.class);
      if (!(parsed instanceof Map<?,?> map)) throw badRequest("Question answer key must be an object.");
      answer=new LinkedHashMap<>();
      for (Map.Entry<?,?> entry:map.entrySet()) {
        if (entry.getKey() instanceof String key) answer.put(key,entry.getValue());
      }
    } catch (JacksonException ex) {
      throw badRequest("Stored question answer data is invalid.");
    }
    if ("MCQ".equals(type)||"TRUE_FALSE".equals(type)) {
      List<Map<String,Object>> options=jdbc.queryForList("""
          select option_key,label,is_correct from question_option
          where question_id=? order by sort_order
          """,questionId);
      long correct=options.stream().filter(o->Boolean.TRUE.equals(o.get("is_correct"))).count();
      boolean blankOption=options.stream().anyMatch(option->!meaningfulTeachingText(option.get("label")));
      if (options.size()<2||correct!=1||blankOption) {
        throw badRequest("Approval requires at least two answer options and exactly one correct answer.");
      }
      String correctKey=String.valueOf(options.stream().filter(o->Boolean.TRUE.equals(o.get("is_correct"))).findFirst().orElseThrow().get("option_key"));
      if (!"OPTION".equals(answer.get("kind"))||!correctKey.equals(String.valueOf(answer.get("value")))) {
        throw badRequest("The answer key does not match the marked correct option.");
      }
    } else if (Set.of("INPUT","SHORT_ANSWER","LONG_ANSWER").contains(type)) {
      if (!"TEXT".equals(answer.get("kind"))||String.valueOf(answer.getOrDefault("value","")).isBlank()) {
        throw badRequest("Add a staff-only reference answer or marking rubric before approval.");
      }
    } else {
      String numericKeyError = numericAnswerKeyValidationError(answer);
      if (numericKeyError != null) throw badRequest(numericKeyError);
    }
    String sourceKind=String.valueOf(q.get("source_kind")).toUpperCase();
    if (!"AUTHOR_CREATED".equals(sourceKind)) {
      if (q.get("source_id")==null||q.get("source_ref")==null||String.valueOf(q.get("source_ref")).isBlank()
          ||q.get("source_year")==null||q.get("source_title")==null||q.get("board")==null) {
        throw badRequest("Add source title, board, year and exact page/reference before approving this sourced question.");
      }
      Long validSource=jdbc.queryForObject("""
          select count(*) from content_source
          where id=? and upper(status) in ('VERIFIED','APPROVED','PUBLISHED')
          """,Long.class,q.get("source_id"));
      if(validSource==null||validSource==0) {
        throw badRequest("Verify the referenced source before approving this question.");
      }
    }
  }

  private Map<String,Object> readBlockContent(Object raw) {
    if(raw==null)return Map.of();
    try {
      Object parsed=mapper.readValue(String.valueOf(raw),Object.class);
      if(!(parsed instanceof Map<?,?> values))return Map.of();
      Map<String,Object> result=new LinkedHashMap<>();
      for(Map.Entry<?,?> entry:values.entrySet()) {
        if(entry.getKey() instanceof String key)result.put(key,entry.getValue());
      }
      return result;
    } catch(JacksonException ex) {
      throw badRequest("Stored teaching block content is invalid JSON.");
    }
  }

  private boolean meaningfulTeachingText(Object value) {
    if(value==null)return false;
    String text=String.valueOf(value)
        .replaceAll("<[^>]*>"," ")
        .replace("&nbsp;"," ").replace("&#160;"," ").replace("&amp;","&")
        .replaceAll("\\s+"," ").trim().toLowerCase(java.util.Locale.ROOT);
    if(text.isBlank())return false;
    return !(text.startsWith("यहाँ अपना explanation लिखें")
        || text.startsWith("समस्या या प्रश्न यहाँ लिखें")
        || text.equals("पहला चरण लिखें") || text.equals("अगला चरण लिखें")
        || text.equals("पहला मुख्य विचार") || text.equals("दूसरा मुख्य विचार")
        || text.equals("याद रखने वाली बात")
        || text.equals("explain the core idea") || text.equals("show the important pattern")
        || text.equals("connect it to a real example") || text.equals("add teaching content")
        || text.equals("विद्यार्थी इस visual से क्या समझें?")
        || text.equals("इस media से विद्यार्थी क्या सीखेंगे?")
        || text.equals("अगला छोटा कदम क्या होगा?")
        || text.equals("विद्यार्थी के लिए अभ्यास निर्देश लिखें")
        || text.startsWith("ऐसा संकेत दें जो सोचने में मदद करे")
        || text.equals("write explanation here") || text.equals("write content here")
        || text.equals("untitled") || text.equals("answer choice"));
  }

  private Object firstBlockValue(Map<String,Object> content,String... keys) {
    for(String key:keys) {
      Object value=content.get(key);
      if(value!=null&&!String.valueOf(value).isBlank())return value;
    }
    return null;
  }

  private boolean everyMeaningfulItem(Object value) {
    if(!(value instanceof List<?> items)||items.isEmpty())return false;
    return items.stream().allMatch(this::meaningfulTeachingText);
  }

  boolean blockHasPublishableContent(String type,Map<String,Object> content) {
    switch(type) {
      case "EXPLANATION":
      case "PREREQUISITE":
        return meaningfulTeachingText(firstBlockValue(content,"html","body","description"));
      case "WORKED_EXAMPLE":
        return meaningfulTeachingText(firstBlockValue(content,"problem","prompt"))
            && everyMeaningfulItem(content.get("steps"))
            && meaningfulTeachingText(content.get("answer"));
      case "IMAGE":
      case "DIAGRAM":
      case "ANIMATION":
      case "VIDEO":
      case "AUDIO":
        return String.valueOf(content.getOrDefault("url","")).matches("(?i)^https://.+")
            && meaningfulTeachingText(content.get("description"));
      case "GUIDED_PRACTICE":
      case "INDEPENDENT_PRACTICE":
      case "CHALLENGE":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","body"));
      case "SUMMARY":
      case "RECAP":
        return everyMeaningfulItem(content.get("points"));
      case "HINT":
        return meaningfulTeachingText(firstBlockValue(content,"body","hint"));
      case "AI_HELP":
        return true;
      case "MCQ":
      case "TRUE_FALSE":
      case "QUESTION":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","question","body"))
            && content.get("options") instanceof List<?> options && options.size()>=2;
      case "MATCH":
      case "ORDER":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","instructions","body"));
      case "INPUT":
        return meaningfulTeachingText(firstBlockValue(content,"prompt","question","body"));
      default:
        return meaningfulTeachingText(firstBlockValue(content,"prompt","body","description","text"));
    }
  }

  void validateLearnerLessonStages(Set<String> activeBlockTypes) {
    if(!activeBlockTypes.contains("EXPLANATION")) {
      throw badRequest("Add a complete Explanation block so the learner receives the core teaching before practice.");
    }
    if(!activeBlockTypes.contains("GUIDED_PRACTICE")
        && !activeBlockTypes.contains("INDEPENDENT_PRACTICE")
        && !activeBlockTypes.contains("CHALLENGE")) {
      throw badRequest("Add guided practice, independent practice, or a challenge before the assessment.");
    }
    if(!activeBlockTypes.contains("SUMMARY") && !activeBlockTypes.contains("RECAP")) {
      throw badRequest("Add a Recap block with meaningful takeaways after the learner assessment.");
    }
  }

  private void validateLessonForSubmission(long lessonId) {
    Map<String,Object> lesson=lessonById(lessonId);
    if(lesson.get("lesson_title")==null||String.valueOf(lesson.get("lesson_title")).isBlank()
        ||lesson.get("lesson_summary")==null||String.valueOf(lesson.get("lesson_summary")).isBlank()) {
      throw badRequest("Add a topic title and learner goal before submitting for review.");
    }
    Object minutes=lesson.get("estimated_minutes");
    if(!(minutes instanceof Number n)||n.intValue()<1||n.intValue()>120) {
      throw badRequest("Set a valid learning time from 1 to 120 minutes before submitting for review.");
    }
    Long parentReady=jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=? and l.active=true and ch.active=true
        and s.active=true and c.active=true
      """,Long.class,lessonId);
    if(parentReady==null||parentReady==0) {
      throw badRequest("Ensure the chapter, class and subject are active before submitting this micro-topic. The chapter may remain a draft during review.");
    }
    Long sourceReady=jdbc.queryForObject("""
      select count(*) from lesson
      where id=? and alignment_source_verified=true
        and nullif(btrim(alignment_source_title),'') is not null
        and lower(coalesce(alignment_source_url,'')) like 'https://%'
        and nullif(btrim(alignment_source_edition),'') is not null
        and nullif(btrim(alignment_page_range),'') is not null
      """,Long.class,lessonId);
    if(sourceReady==null||sourceReady==0) {
      throw badRequest("Complete and verify the textbook source title, HTTPS URL, edition and page range before submitting.");
    }
    List<Map<String,Object>> blocks=jdbc.queryForList("""
      select id,block_type,content::text as content
      from lesson_block where lesson_id=? and active=true order by sequence_no,id
      """,lessonId);
    if(blocks.isEmpty()) throw badRequest("Add at least one complete teaching block before submitting for review.");
    boolean hasCoreBlock=false;
    Set<String> activeBlockTypes=new HashSet<>();
    for(Map<String,Object> row:blocks) {
      String type=String.valueOf(row.get("block_type")).toUpperCase();
      if(!LEARNER_RENDERED_BLOCK_TYPES.contains(type)) {
        throw badRequest("The "+type.replace('_',' ').toLowerCase(java.util.Locale.ROOT)+
            " block is a legacy format not rendered on the student screen. Move question content to the lesson Questions section or deactivate this block before submitting.");
      }
      if(!blockHasPublishableContent(type,readBlockContent(row.get("content")))) {
        throw badRequest("Complete the "+type.replace('_',' ').toLowerCase(java.util.Locale.ROOT)+
            " teaching block or deactivate it before submitting for review.");
      }
      activeBlockTypes.add(type);
      if(!"AI_HELP".equals(type))hasCoreBlock=true;
    }
    if(!hasCoreBlock)throw badRequest("Add at least one complete teaching block beyond the AI tutor entry.");
    validateLearnerLessonStages(activeBlockTypes);
    Long activeQuestions=jdbc.queryForObject(
        "select count(*) from question where lesson_id=? and active=true",Long.class,lessonId);
    if(activeQuestions==null||activeQuestions==0) {
      throw badRequest("Add at least one active practice question before submitting for review.");
    }
    validatePreviewCheck(lessonId);
  }

  private void validateLessonForPublishing(long lessonId) {
    validateLessonForPublishing(lessonId,true);
  }

  private void validatePreviewCheck(long lessonId) {
    Long checked=jdbc.queryForObject("""
      select count(*) from lesson
      where id=? and content_revision=preview_checked_revision
      """,Long.class,lessonId);
    if(checked==null||checked==0) {
      throw badRequest("Open the current saved learner preview and confirm it before submitting or publishing this micro-topic.");
    }
  }

  private void validateLessonForPublishing(long lessonId,boolean requirePublishedChapter) {
    validatePreviewCheck(lessonId);
    Long parentReady = jdbc.queryForObject("""
      select count(*) from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=? and l.active=true
        and (not ? or (ch.active=true and ch.content_status='PUBLISHED'))
        and s.active=true and c.active=true
      """, Long.class, lessonId, requirePublishedChapter);
    if (parentReady == null || parentReady == 0) {
      throw badRequest("Publish the parent chapter first and ensure its class and subject are active.");
    }
    Map<String,Object> lessonIdentity = jdbc.queryForMap("""
      select c.code as class_code, s.code as subject_code, s.display_name as subject_name
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=?
      """, lessonId);
    Long sourceReady = jdbc.queryForObject("""
      select count(*) from lesson
      where id=? and alignment_source_verified=true
        and nullif(btrim(alignment_source_title),'') is not null
        and lower(coalesce(alignment_source_url,'')) like 'https://%'
        and nullif(btrim(alignment_source_edition),'') is not null
        and nullif(btrim(alignment_page_range),'') is not null
      """,Long.class,lessonId);
    if(sourceReady==null||sourceReady==0) {
      throw badRequest("Complete and verify the textbook source title, HTTPS URL, edition and page range before publishing.");
    }

    List<Map<String,Object>> activeBlocks = jdbc.queryForList("""
      select block_type,content::text as content
      from lesson_block where lesson_id=? and active=true order by sequence_no
      """,lessonId);
    if(activeBlocks.isEmpty()) {
      throw badRequest("Add at least one active teaching block before publishing this lesson.");
    }
    boolean hasCoreTeachingBlock=false;
    Set<String> activeBlockTypes=new HashSet<>();
    for(Map<String,Object> row:activeBlocks) {
      String type=String.valueOf(row.get("block_type")).toUpperCase();
      if(!LEARNER_RENDERED_BLOCK_TYPES.contains(type)) {
        throw badRequest("The "+type.replace('_',' ').toLowerCase(java.util.Locale.ROOT)+
            " block is a legacy format not rendered on the student screen. Move question content to the lesson Questions section or deactivate this block before publishing.");
      }
      Map<String,Object> content=readBlockContent(row.get("content"));
      if(!blockHasPublishableContent(type,content)) {
        throw badRequest("Complete the "+type.replace('_',' ').toLowerCase(java.util.Locale.ROOT)+
            " teaching block or deactivate it before publishing.");
      }
      String visualIssue=visualPublicationValidationError(type,content);
      if(visualIssue!=null) throw badRequest(visualIssue);
      String sourceMappingIssue=visualSourceMappingValidationError(content,
          String.valueOf(lessonIdentity.getOrDefault("class_code","")),
          String.valueOf(lessonIdentity.getOrDefault("subject_code","")),
          String.valueOf(lessonIdentity.getOrDefault("subject_name","")));
      if(sourceMappingIssue!=null) throw badRequest(sourceMappingIssue);
      activeBlockTypes.add(type);
      if(!"AI_HELP".equals(type)) hasCoreTeachingBlock=true;
    }
    if(!hasCoreTeachingBlock) {
      throw badRequest("Add at least one complete teaching block beyond the AI tutor entry.");
    }
    validateLearnerLessonStages(activeBlockTypes);
    Long questions = jdbc.queryForObject(
        "select count(*) from question where lesson_id=? and active=true", Long.class, lessonId);
    if (questions == null || questions == 0) {
      throw badRequest("Add at least one active practice question before publishing this lesson.");
    }
    Long invalidQuestions = jdbc.queryForObject("""
      select count(*) from question q
      where q.lesson_id=? and q.active=true
        and (
          q.question_type not in ('MCQ','TRUE_FALSE','INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER')
          or q.review_status not in ('APPROVED','PUBLISHED')
          or nullif(btrim(q.prompt),'') is null
          or (q.question_type in ('MCQ','TRUE_FALSE') and (
            (select count(*) from question_option qo where qo.question_id=q.id)<2
            or exists(select 1 from question_option qo where qo.question_id=q.id and nullif(btrim(qo.label),'') is null)
            or (select count(*) from question_option qo where qo.question_id=q.id and qo.is_correct)<>1
            or coalesce(q.answer_payload->>'kind','')<>'OPTION'
            or q.answer_payload->>'value' is distinct from (
              select qo.option_key from question_option qo where qo.question_id=q.id and qo.is_correct limit 1
            )
          ))
          or (q.question_type in ('INPUT','SHORT_ANSWER','LONG_ANSWER') and (coalesce(q.answer_payload->>'kind','')<>'TEXT' or nullif(btrim(q.answer_payload->>'value'),'') is null))
          or (q.question_type='NUMERICAL' and (coalesce(q.answer_payload->>'kind','')<>'NUMERIC' or q.answer_payload->'value' is null or q.answer_payload->'value'='null'::jsonb))
          or (q.source_kind<>'AUTHOR_CREATED' and (
            q.source_id is null or nullif(btrim(q.source_ref),'') is null or q.source_year is null
            or nullif(btrim(q.source_title),'') is null or nullif(btrim(q.board),'') is null
            or not exists(select 1 from content_source src where src.id=q.source_id and upper(src.status) in ('VERIFIED','APPROVED','PUBLISHED'))
          ))
        )
      """, Long.class, lessonId);
    if (invalidQuestions != null && invalidQuestions > 0) {
      throw badRequest("Every published question must have a valid grading setup: MCQ/true-false need exactly one correct option; input, short-answer and long-answer questions need a staff-only TEXT reference answer or rubric; numerical questions need a NUMERIC answer; and source-backed questions need verified provenance.");
    }
  }

  private Map<String, Object> chapterById(long chapterId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select ch.id as chapter_id, ch.code as chapter_code,
             ch.display_name as chapter_name, ch.description as chapter_description,
             coalesce(ch.teaching_order,ch.sort_order) as chapter_sort_order, ch.content_status as chapter_status,
             ch.active as chapter_active, ch.curriculum_source,
             ch.curriculum_source_url, ch.curriculum_source_edition,
             ch.curriculum_source_pages, ch.curriculum_source_verified,
             c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name,
             (select count(*) from lesson l where l.chapter_id=ch.id) as lesson_count
      from curriculum_chapter ch
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where ch.id=?
      """, chapterId);
    if (rows.isEmpty()) throw notFound("Chapter", chapterId);
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    List<Long> activeLessons=jdbc.queryForList(
        "select id from lesson where chapter_id=? and active=true order by sort_order,id",Long.class,chapterId);
    long publishReady=0;
    for(Long lessonId:activeLessons) {
      try {
        validateLessonForPublishing(lessonId,false);
        publishReady++;
      } catch(ResponseStatusException notReady) {
        // A chapter may contain draft topics, but at least one topic must be genuinely publishable.
      }
    }
    result.put("publish_ready_lesson_count",publishReady);
    return result;
  }

  private Map<String, Object> lessonById(long lessonId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
      select l.id as lesson_id, l.code as lesson_code, l.title as lesson_title,
             l.summary as lesson_summary, l.estimated_minutes,
             l.sort_order as lesson_sort_order, l.status as lesson_status,
             l.active as lesson_active, l.alignment_source_title,
             l.alignment_source_url, l.alignment_source_edition,
             l.alignment_page_range, l.alignment_source_verified,
             l.content_revision, l.preview_checked_revision, l.preview_checked_at,
             l.preview_checked_by_staff_id,
             ch.id as chapter_id, ch.code as chapter_code,
             ch.display_name as chapter_name, ch.description as chapter_description,
             ch.content_status as chapter_status, ch.active as chapter_active,
             ch.curriculum_source, ch.curriculum_source_url, ch.curriculum_source_edition,
             ch.curriculum_source_pages, ch.curriculum_source_verified,
             c.code as class_code, c.display_name as class_name,
             s.code as subject_code, s.display_name as subject_name
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      where l.id=?
      """, lessonId);
    if (rows.isEmpty()) throw notFound("Lesson", lessonId);
    Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("blocks", jdbc.queryForList("""
      select id, sequence_no, block_type, content::text as content, active
      from lesson_block where lesson_id=? order by sequence_no,id
      """, lessonId));
    result.put("questions", jdbc.queryForList("""
      select q.id, q.question_type, q.prompt, q.explanation, q.difficulty,
             q.sort_order, q.active, q.review_status, q.review_notes, q.reviewed_at,
             q.marks, q.exam_format, q.source_kind, q.source_title, q.source_ref,
             q.source_year, q.source_id, q.board, q.topic, q.subtopic, q.skill,
             q.tags::text as tags, q.answer_payload::text as answer_payload,
             (select upper(src.status) from content_source src where src.id=q.source_id) as source_status,
             coalesce(
               (select jsonb_agg(jsonb_build_object(
                 'key',qo.option_key,'label',qo.label,'correct',qo.is_correct,
                 'sort_order',qo.sort_order
               ) order by qo.sort_order) from question_option qo where qo.question_id=q.id),
               '[]'::jsonb
             )::text as options
      from question q where q.lesson_id=? order by q.sort_order,q.id
      """, lessonId));
    return result;
  }

  private void replaceBlocks(long lessonId, Object value, AuthContext context) {
    List<Map<String, Object>> blocks = objectList(value, "blocks");
    if (blocks.isEmpty() || blocks.size() > 100) throw badRequest("A lesson must contain between 1 and 100 content blocks.");
    Map<String,Object> lessonIdentity=null;
    Set<String> existingSourceVisualUrls=new HashSet<>();
    List<Map<String,Object>> existingSourceVisuals=jdbc.queryForList(
        "select content::text as content from lesson_block where lesson_id=? and nullif(btrim(content->>'source_candidate_id'),'') is not null",
        lessonId);
    for(Map<String,Object> row:existingSourceVisuals) {
      Map<String,Object> previous=readBlockContent(row.get("content"));
      String previousUrl=String.valueOf(previous.getOrDefault("url","")).trim();
      if(!previousUrl.isBlank()) existingSourceVisualUrls.add(previousUrl);
    }
    Set<Integer> sequences = new HashSet<>();
    for (Map<String, Object> block : blocks) {
      int sequence = integerValue(first(block, "sequence_no", "sequenceNo"), 1, 10000, "sequence_no");
      if (!sequences.add(sequence)) throw badRequest("Content block sequence numbers must be unique.");
      String type = requiredText(first(block, "block_type", "blockType"), 40).toUpperCase();
      if (!BLOCK_TYPES.contains(type)) throw badRequest("Unsupported content block type: " + type);
      if (!(block.get("content") instanceof Map<?, ?> rawContent)) throw badRequest("Each block's content must be a JSON object.");
      Map<String,Object> content=new LinkedHashMap<>();
      rawContent.forEach((key,item)->content.put(String.valueOf(key),item));
      String candidateId=optionalText(content.get("source_candidate_id"),120);
      String submittedMediaUrl=String.valueOf(content.getOrDefault("url","")).trim();
      if(candidateId==null&&!submittedMediaUrl.isBlank()
          &&existingSourceVisualUrls.contains(submittedMediaUrl)
          &&!authorization.hasPermission(context,"CONTENT_REVIEW")) {
        throw badRequest("Only a content reviewer can detach source provenance from an existing textbook visual.");
      }
      if(candidateId!=null) {
        if(lessonIdentity==null) {
          lessonIdentity=jdbc.queryForMap("""
            select c.code as class_code, s.code as subject_code, s.display_name as subject_name
            from lesson l
            join curriculum_chapter ch on ch.id=l.chapter_id
            join curriculum_subject s on s.id=ch.subject_id
            join curriculum_class c on c.id=s.class_id
            where l.id=?
            """, lessonId);
        }
        String sourceMappingIssue=visualSourceMappingValidationError(content,
            String.valueOf(lessonIdentity.getOrDefault("class_code","")),
            String.valueOf(lessonIdentity.getOrDefault("subject_code","")),
            String.valueOf(lessonIdentity.getOrDefault("subject_name","")));
        if(sourceMappingIssue!=null) throw badRequest(sourceMappingIssue);
        String visualStatus=String.valueOf(content.getOrDefault("visual_review_status","NEEDS_REVIEW")).trim().toUpperCase(Locale.ROOT);
        String rightsStatus=String.valueOf(content.getOrDefault("rights_status","SOURCE_REFERENCE_ONLY")).trim().toUpperCase(Locale.ROOT);
        boolean requestsReviewSensitiveState="APPROVED".equals(visualStatus)||"LICENSE_CONFIRMED".equals(rightsStatus);
        if(requestsReviewSensitiveState&&!authorization.hasPermission(context,"CONTENT_REVIEW")) {
          List<Map<String,Object>> prior=jdbc.queryForList(
              "select content::text as content from lesson_block where lesson_id=? and content->>'source_candidate_id'=?",
              lessonId,candidateId);
          boolean unchangedApproved=prior.stream().map(row->readBlockContent(row.get("content")))
              .anyMatch(previous->Objects.equals(previous,content)
                  &&"APPROVED".equalsIgnoreCase(String.valueOf(previous.get("visual_review_status")))
                  &&"LICENSE_CONFIRMED".equalsIgnoreCase(String.valueOf(previous.get("rights_status"))));
          if(!unchangedApproved) throw badRequest("Only a content reviewer can approve a textbook visual or confirm its publication rights.");
        } else if(requestsReviewSensitiveState) {
          content.put("visual_reviewed_at",java.time.Instant.now().toString());
        }
      }
      block.put("content",content);
      toJson(content);
    }
    jdbc.update("delete from lesson_block where lesson_id=?", lessonId);
    for (Map<String, Object> block : blocks) {
      jdbc.update("""
        insert into lesson_block(lesson_id,sequence_no,block_type,content,active)
        values(?,?,?,?::jsonb,?)
        """, lessonId,
        integerValue(first(block, "sequence_no", "sequenceNo"), 1, 10000, "sequence_no"),
        requiredText(first(block, "block_type", "blockType"), 40).toUpperCase(),
        toJson(block.get("content")), booleanValue(block.get("active"), true));
    }
  }

  static String visualPublicationValidationError(String type, Map<String,Object> content) {
    if(content==null) return null;
    String candidateId=String.valueOf(content.getOrDefault("source_candidate_id","")).trim();
    String mediaUrl=String.valueOf(content.getOrDefault("url","")).trim();
    if(candidateId.isBlank()) {
      if(isGeneratedV3VisualAssetUrl(mediaUrl)) {
        return "Preserve the source candidate metadata for generated V3 textbook visual assets.";
      }
      return null;
    }
    if(!Set.of("IMAGE","DIAGRAM","ANIMATION").contains(String.valueOf(type).toUpperCase(Locale.ROOT))) {
      return "A textbook visual candidate must use an image, diagram, or animation block.";
    }
    String visualKind=String.valueOf(content.getOrDefault("visual_kind","TEXTBOOK_FIGURE")).trim().toUpperCase(Locale.ROOT);
    if(!Set.of("PHOTO","ILLUSTRATION","DIAGRAM","CHART","TABLE","INFOGRAPHIC","SEQUENCE").contains(visualKind)) {
      return "Classify the textbook visual before publication; unclassified figures remain drafts.";
    }
    if(!"APPROVED".equalsIgnoreCase(String.valueOf(content.get("visual_review_status")))) {
      return "A content reviewer must approve each textbook visual before lesson publication.";
    }
    String rights=String.valueOf(content.getOrDefault("rights_status","SOURCE_REFERENCE_ONLY")).trim().toUpperCase(Locale.ROOT);
    if(!"LICENSE_CONFIRMED".equals(rights)) {
      return "Confirm the rights for each source visual before lesson publication.";
    }
    String alt=String.valueOf(content.getOrDefault("alt","")).trim();
    if(alt.isBlank()) return "Add meaningful alternative text for each textbook visual.";
    String altHi=String.valueOf(content.getOrDefault("alt_hi","")).trim();
    if(altHi.isBlank()) return "Add meaningful Hindi alternative text for each textbook visual.";
    String title=String.valueOf(content.getOrDefault("title","")).trim();
    if(title.isBlank()) return "Add a meaningful title for each textbook visual.";
    String titleHi=String.valueOf(content.getOrDefault("title_hi","")).trim();
    if(titleHi.isBlank()) return "Add a Hindi title for each textbook visual.";
    String description=String.valueOf(content.getOrDefault("description","")).trim();
    String descriptionHi=String.valueOf(content.getOrDefault("description_hi","")).trim();
    if(description.isBlank()!=descriptionHi.isBlank()) return "Provide the visual description in both English and Hindi, or leave both blank.";
    String caption=String.valueOf(content.getOrDefault("caption","")).trim();
    String captionHi=String.valueOf(content.getOrDefault("caption_hi","")).trim();
    if(caption.isBlank()!=captionHi.isBlank()) return "Provide the visual caption in both English and Hindi, or leave both blank.";
    String sourceBook=String.valueOf(content.getOrDefault("source_book_id","")).trim();
    String pdfHash=String.valueOf(content.getOrDefault("source_pdf_sha256","")).trim().toLowerCase(Locale.ROOT);
    String assetHash=String.valueOf(content.getOrDefault("source_asset_sha256","")).trim().toLowerCase(Locale.ROOT);
    Object rawPage=content.get("source_pdf_page_number");
    int page=rawPage instanceof Number n?n.intValue():-1;
    if(sourceBook.isBlank()||!pdfHash.matches("[0-9a-f]{64}")||!assetHash.matches("[0-9a-f]{64}")||page<1) {
      return "The textbook visual is missing its exact source-book, page, or SHA-256 provenance.";
    }
    String url=String.valueOf(content.getOrDefault("url","")).trim();
    if(!url.matches("(?i)^https://.+")) return "The approved textbook visual must resolve to an HTTPS media URL.";
    return null;
  }

  static String visualSourceMappingValidationError(Map<String,Object> content,
      String lessonClassCode, String lessonSubjectCode, String lessonSubjectName) {
    if(content==null||String.valueOf(content.getOrDefault("source_candidate_id","")).trim().isBlank()) return null;
    String sourceClass=normalizeVisualClass(Objects.toString(content.get("source_class"),""));
    String lessonClass=normalizeVisualClass(lessonClassCode);
    if(sourceClass.isBlank()||lessonClass.isBlank()||!sourceClass.equals(lessonClass)) {
      return "The textbook visual source class must match the lesson class.";
    }
    String sourceSubject=normalizeVisualSubject(Objects.toString(content.get("source_subject"),""));
    String lessonCodeSubject=normalizeVisualSubject(lessonSubjectCode);
    String lessonNameSubject=normalizeVisualSubject(lessonSubjectName);
    if(sourceSubject.isBlank()||(!sourceSubject.equals(lessonCodeSubject)&&!sourceSubject.equals(lessonNameSubject))) {
      return "The textbook visual source subject must match the lesson subject.";
    }
    return null;
  }

  private static String normalizeVisualClass(String raw) {
    return raw==null?"":raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^(?:class|grade)[\\s_-]*","");
  }

  private static String normalizeVisualSubject(String raw) {
    String value=Normalizer.normalize(raw==null?"":raw.trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    if(value.contains("science")||value.contains("विज्ञान")||value.contains("vigyan")) return "science";
    if(value.contains("math")||value.contains("गणित")||value.contains("ganit")) return "mathematics";
    return value.replaceAll("[^a-z0-9]+","");
  }

  private static boolean isGeneratedV3VisualAssetUrl(String rawUrl) {
    String url=rawUrl==null?"":rawUrl.trim().toLowerCase(Locale.ROOT);
    return url.contains("/apps/web/public/content/visual-assets/")
        ||url.contains("/content/visual-assets/")
        ||url.contains("/data/textbook-visual-candidates-v3/")
        ||url.contains("raw.githubusercontent.com/manav326/quantaedge/main/data/textbook-visuals/");
  }

  private boolean updateQuestions(long lessonId, Object value, AuthContext context) {
    return updateQuestions(lessonId,value,context,true);
  }

  private boolean updateQuestions(long lessonId, Object value, AuthContext context, boolean deactivateOmitted) {
    List<Map<String, Object>> questions = objectList(value, "questions");
    List<Map<String, Object>> current = jdbc.queryForList("select id from question where lesson_id=?", lessonId);
    Set<Long> currentIds = new HashSet<>();
    current.forEach(row -> currentIds.add(((Number) row.get("id")).longValue()));
    Set<Long> submittedIds = new HashSet<>();
    boolean questionContentChanged = false;

    for (Map<String, Object> q : questions) {
      boolean create = q.get("id") == null;
      long id = create ? 0L : longValue(q.get("id"), "question id");
      if (!create && (!currentIds.contains(id) || submittedIds.contains(id))) {
        throw badRequest("Question IDs must be unique and belong to this lesson.");
      }
      Map<String,Object> beforeSnapshot=create?Map.of():questionHistory.snapshot(id);
      String type = requiredText(first(q, "question_type", "questionType"), 30).toUpperCase();
      String difficulty = requiredText(q.get("difficulty"), 20).toUpperCase();
      String prompt = requiredText(q.get("prompt"), 1000);
      String explanation = optionalText(q.get("explanation"), 1200);
      String reviewStatus = "DRAFT";
      String reviewNotes = null; // Review state can only change through the permissioned review endpoint.
      int order = integerValue(first(q, "sort_order", "sortOrder"), 1, 10000, "sort_order");
      Integer marks = optionalInteger(first(q, "marks", "marks"), 1, 100, "marks");
      String examFormat = optionalText(first(q, "exam_format", "examFormat"), 40);
      String sourceKind = String.valueOf(first(q, "source_kind", "sourceKind") == null
          ? "AUTHOR_CREATED" : first(q, "source_kind", "sourceKind")).trim().toUpperCase();
      String sourceTitle = optionalText(first(q, "source_title", "sourceTitle"), 300);
      String sourceRef = optionalText(first(q, "source_ref", "sourceRef"), 500);
      Integer sourceYear = optionalInteger(first(q, "source_year", "sourceYear"), 1800, 2100, "sourceYear");
      Long sourceId = optionalLong(first(q, "source_id", "sourceId"), "sourceId");
      String board = optionalText(q.get("board"), 80);
      String topic = optionalText(q.get("topic"), 200);
      String subtopic = optionalText(q.get("subtopic"), 200);
      String skill = optionalText(q.get("skill"), 200);
      String tagsJson = jsonText(q.get("tags"), "tags", "[]");
      String answerPayload = jsonText(first(q, "answer_payload", "answerPayload"), "answer_payload", "{}");

      if (!QUESTION_TYPES.contains(type)) throw badRequest("Unsupported question type: " + type);
      if (!DIFFICULTIES.contains(difficulty)) throw badRequest("Unsupported question difficulty: " + difficulty);
      if (!Set.of("DRAFT","REVIEW","APPROVED","PUBLISHED","REJECTED").contains(reviewStatus)) {
        throw badRequest("Question review status must be DRAFT, REVIEW, APPROVED, PUBLISHED, or REJECTED.");
      }
      if (!Set.of("AUTHOR_CREATED","STATE_TEXTBOOK","BOARD_PAST_PAPER","TEXTBOOK_DERIVED").contains(sourceKind)) {
        throw badRequest("Unsupported question source kind.");
      }
      if (!"AUTHOR_CREATED".equals(sourceKind)) {
        if (sourceId == null || sourceRef == null || sourceYear == null || board == null || sourceTitle == null) {
          throw badRequest("Source-backed questions need a registered source, exact reference/page, year, board and source title.");
        }
        Long sourceCount = jdbc.queryForObject("select count(*) from content_source where id=?", Long.class, sourceId);
        if (sourceCount == null || sourceCount == 0) throw badRequest("Registered content source not found.");
        String sourceStatus = jdbc.queryForObject("select status from content_source where id=?", String.class, sourceId);
        if (Set.of("APPROVED","PUBLISHED").contains(reviewStatus)
            && !Set.of("VERIFIED","APPROVED","PUBLISHED").contains(String.valueOf(sourceStatus).toUpperCase())) {
          throw badRequest("This source is still a candidate/reference. Verify its edition and page mapping before approving source-backed questions.");
        }
      }

      List<Map<String, Object>> options = objectList(q.get("options"), "question options");
      if (("MCQ".equals(type) || "TRUE_FALSE".equals(type)) && options.size() < 2) {
        throw badRequest("Multiple-choice and true/false questions need at least two options.");
      }
      if (!Set.of("MCQ", "TRUE_FALSE").contains(type) && !options.isEmpty()) {
        throw badRequest("Only MCQ and true/false questions may use choice options in the current student UI.");
      }
      if (options.size() > 10) throw badRequest("A question cannot have more than 10 options.");
      Set<String> optionKeys = new HashSet<>();
      int correctCount = 0;
      String correctKey = null;
      for (Map<String, Object> option : options) {
        String key = requiredText(first(option, "key", "option_key"), 20);
        requiredText(option.get("label"), 500);
        if (!optionKeys.add(key)) throw badRequest("Option keys must be unique within a question.");
        if (booleanValue(first(option, "correct", "is_correct"), false)) {
          correctCount++;
          correctKey = key;
        }
      }
      if (("MCQ".equals(type) || "TRUE_FALSE".equals(type)) && correctCount != 1) {
        throw badRequest("Each multiple-choice question must have exactly one correct answer.");
      }
      if ("MCQ".equals(type) || "TRUE_FALSE".equals(type)) {
        answerPayload = jsonText(toJson(Map.of("kind", "OPTION", "value", correctKey)), "answer_payload", "{}");
      }

      if (create) {
        questionContentChanged = true;
        if ("APPROVED".equals(reviewStatus) || "PUBLISHED".equals(reviewStatus)) {
          reviewStatus = "DRAFT";
          reviewNotes = "New question requires a separate review and approval.";
        }
        Long createdId = jdbc.queryForObject("""
          insert into question(
            lesson_id,question_type,prompt,explanation,difficulty,sort_order,active,
            review_status,review_notes,reviewed_at,marks,exam_format,source_kind,source_title,
            source_ref,source_year,source_id,board,topic,subtopic,skill,tags,answer_payload
          )
          values(?,?,?,?,?,?,?, ?,?,null, ?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
          returning id
          """, Long.class, lessonId, type, prompt, explanation, difficulty, order,
          booleanValue(q.get("active"), true), reviewStatus, reviewNotes,
          marks, examFormat, sourceKind, sourceTitle, sourceRef, sourceYear,
          sourceId, board, topic, subtopic, skill, tagsJson, answerPayload);
        id = createdId;
      } else {
        Map<String, Object> existing = jdbc.queryForMap("""
          select question_type,prompt,explanation,difficulty,review_status,review_notes,marks,exam_format,
                 source_kind,source_title,source_ref,source_year,source_id,board,topic,subtopic,skill,
                 tags::text as tags,answer_payload::text as answer_payload
          from question where id=? and lesson_id=?
          """, id, lessonId);
        reviewStatus = String.valueOf(existing.get("review_status"));
        reviewNotes = existing.get("review_notes")==null?null:String.valueOf(existing.get("review_notes"));
        boolean changed = !type.equals(String.valueOf(existing.get("question_type")))
            || !prompt.equals(String.valueOf(existing.get("prompt")))
            || !java.util.Objects.equals(explanation, existing.get("explanation"))
            || !difficulty.equals(String.valueOf(existing.get("difficulty")))
            || !java.util.Objects.equals(marks, existing.get("marks") == null ? null : ((Number) existing.get("marks")).intValue())
            || !java.util.Objects.equals(examFormat, existing.get("exam_format"))
            || !sourceKind.equals(String.valueOf(existing.get("source_kind")))
            || !java.util.Objects.equals(sourceTitle, existing.get("source_title"))
            || !java.util.Objects.equals(sourceRef, existing.get("source_ref"))
            || !java.util.Objects.equals(sourceYear, existing.get("source_year") == null ? null : ((Number) existing.get("source_year")).intValue())
            || !java.util.Objects.equals(sourceId, existing.get("source_id") == null ? null : ((Number) existing.get("source_id")).longValue())
            || !java.util.Objects.equals(board, existing.get("board"))
            || !java.util.Objects.equals(topic, existing.get("topic"))
            || !java.util.Objects.equals(subtopic, existing.get("subtopic"))
            || !java.util.Objects.equals(skill, existing.get("skill"))
            || !tagsJson.equals(jsonText(existing.get("tags"), "tags", "[]"))
            || !answerPayload.equals(jsonText(existing.get("answer_payload"), "answer_payload", "{}"));

        List<Map<String, Object>> oldOptions = jdbc.queryForList("""
          select option_key,label,is_correct,sort_order from question_option
          where question_id=? order by sort_order
          """, id);
        if (oldOptions.size() != options.size()) changed = true;
        for (int i = 0; !changed && i < options.size(); i++) {
          Map<String, Object> incoming = options.get(i);
          Map<String, Object> previous = oldOptions.get(i);
          Object suppliedOrder = first(incoming, "sort_order", "sortOrder");
          int incomingOrder = suppliedOrder instanceof Number ? ((Number) suppliedOrder).intValue() : i + 1;
          changed = !requiredText(first(incoming, "key", "option_key"), 20).equals(String.valueOf(previous.get("option_key")))
              || !requiredText(incoming.get("label"), 500).equals(String.valueOf(previous.get("label")))
              || booleanValue(first(incoming, "correct", "is_correct"), false) != Boolean.TRUE.equals(previous.get("is_correct"))
              || incomingOrder != ((Number) previous.get("sort_order")).intValue();
        }
        if (changed) {
          questionContentChanged = true;
          if (Set.of("APPROVED","PUBLISHED","REVIEW","REJECTED").contains(String.valueOf(existing.get("review_status")).toUpperCase())) {
            reviewStatus = "DRAFT";
            reviewNotes = "Question content or provenance changed; review and approve again before publishing.";
          }
        }
        jdbc.update("""
          update question set question_type=?,prompt=?,explanation=?,difficulty=?,sort_order=?,active=?,
              review_status=?,review_notes=?,reviewed_at=case when ?='APPROVED' then coalesce(reviewed_at,now()) else null end,
              reviewed_by_staff_id=case when ?='APPROVED' then reviewed_by_staff_id else null end,
              marks=?,exam_format=?,source_kind=?,source_title=?,source_ref=?,source_year=?,source_id=?,board=?,
              topic=?,subtopic=?,skill=?,tags=?::jsonb,answer_payload=?::jsonb
          where id=? and lesson_id=?
          """, type, prompt, explanation, difficulty, order, booleanValue(q.get("active"), true),
          reviewStatus, reviewNotes, reviewStatus, reviewStatus, marks, examFormat, sourceKind, sourceTitle, sourceRef,
          sourceYear, sourceId, board, topic, subtopic, skill, tagsJson, answerPayload, id, lessonId);
      }
      submittedIds.add(id);
      jdbc.update("delete from question_option where question_id=?", id);
      int index = 1;
      for (Map<String, Object> option : options) {
        Object suppliedOrder = first(option, "sort_order", "sortOrder");
        int optionOrder = suppliedOrder instanceof Number ? integerValue(suppliedOrder, 1, 10000, "option sort_order") : index;
        jdbc.update("""
          insert into question_option(question_id,option_key,label,is_correct,sort_order)
          values(?,?,?,?,?)
          """, id, requiredText(first(option, "key", "option_key"), 20),
          requiredText(option.get("label"), 500),
          booleanValue(first(option, "correct", "is_correct"), false), optionOrder);
        index++;
      }
      Map<String,Object> afterSnapshot=questionHistory.snapshot(id);
      questionHistory.recordChange(id,lessonId,create?"CREATED":"CONTENT_CHANGED",context,
          create?"Question created as draft.":"Question content, options, status, ordering, or activation changed.",
          beforeSnapshot,afterSnapshot);
    }
    if (deactivateOmitted) {
      for (Long id : currentIds) {
        if (!submittedIds.contains(id)) {
          Map<String,Object> beforeSnapshot=questionHistory.snapshot(id);
          int changed=jdbc.update("update question set active=false where id=? and lesson_id=? and active=true",id,lessonId);
          if(changed>0) questionHistory.recordChange(id,lessonId,"CONTENT_CHANGED",context,
              "Question removed from the editor and deactivated; history is retained.",beforeSnapshot,questionHistory.snapshot(id));
        }
      }
    }
    return questionContentChanged;
  }

  private String jsonText(Object value, String name, String fallback) {
    if (value == null) return fallback;
    if (value instanceof String text) {
      if (text.isBlank()) return fallback;
      try { return mapper.readTree(text).toString(); }
      catch (JacksonException ex) { throw badRequest(name + " must contain valid JSON."); }
    }
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException ex) { throw badRequest(name + " must contain valid JSON."); }
  }

  private static Integer optionalInteger(Object value, int min, int max, String name) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    final int result;
    try { result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim()); }
    catch (NumberFormatException ex) { throw badRequest(name + " must be an integer."); }
    if (result < min || result > max) throw badRequest(name + " must be between " + min + " and " + max + ".");
    return result;
  }

  private static Long optionalLong(Object value, String name) {
    if (value == null || String.valueOf(value).isBlank()) return null;
    try { long result = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value).trim()); if (result < 1) throw badRequest(name + " must be positive."); return result; }
    catch (NumberFormatException ex) { throw badRequest(name + " must be an integer."); }
  }

  private String toJson(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JacksonException ex) { throw badRequest("Content must contain valid JSON."); }
  }

  private void requireCompleteSourceReference(String title, String url, String edition, String pages, String type) {
    if (title == null || title.isBlank() || url == null || !url.startsWith("https://")
        || edition == null || edition.isBlank() || pages == null || pages.isBlank()) {
      throw badRequest("Verified " + type + " source requires an official HTTPS URL, exact edition/session and page range.");
    }
  }

  private long requireInstitution(InstitutionTenantContext tenant) {
    if (tenant == null || tenant.institutionId() < 1L) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An active institution context is required.");
    }
    return tenant.institutionId();
  }

  private void requireChapterInCourse(long chapterId, long courseId, long institutionId) {
    requireAssignedCourse(courseId, institutionId);
    Boolean inCourse = jdbc.queryForObject("""
      select exists(
        select 1
        from curriculum_chapter ch
        join curriculum_subject s on s.id=ch.subject_id and s.active=true
        join curriculum_program_subject mapping
          on mapping.program_id=? and mapping.subject_id=s.id and mapping.class_id=s.class_id
        join curriculum_program p on p.id=mapping.program_id
          and p.status='ACTIVE' and p.code<>'GENERAL'
        where ch.id=? and (s.program_id=p.id
          or (s.program_id is null and p.class_id=s.class_id))
      )
      """, Boolean.class, courseId, chapterId);
    if (!Boolean.TRUE.equals(inCourse)) throw notFound("Chapter", chapterId);
  }

  private void requireLessonInCourse(long lessonId, long courseId, long institutionId) {
    requireAssignedCourse(courseId, institutionId);
    Boolean inCourse = jdbc.queryForObject("""
      select exists(
        select 1
        from lesson l
        join curriculum_chapter ch on ch.id=l.chapter_id
        join curriculum_subject s on s.id=ch.subject_id and s.active=true
        join curriculum_program_subject mapping
          on mapping.program_id=? and mapping.subject_id=s.id and mapping.class_id=s.class_id
        join curriculum_program p on p.id=mapping.program_id
          and p.status='ACTIVE' and p.code<>'GENERAL'
        where l.id=? and (s.program_id=p.id
          or (s.program_id is null and p.class_id=s.class_id))
      )
      """, Boolean.class, courseId, lessonId);
    if (!Boolean.TRUE.equals(inCourse)) throw notFound("Lesson", lessonId);
  }

  /**
   * Assigned courses are visible to a tenant, but only a tenant that owns an active source
   * release may mutate course content. This is separate from staff permission checks.
   */
  private void requireEditableCourse(long courseId,long institutionId) {
    if(courseId<1L) throw badRequest("Select a valid course.");
    Boolean sourceOwned=jdbc.queryForObject("""
      select exists(
        select 1
        from institution_course_release release
        join institution_course_assignment assignment
          on assignment.course_release_id=release.id
         and assignment.institution_id=? and assignment.status='ASSIGNED'
        where release.program_id=?
          and release.source_institution_id=assignment.institution_id
      )
      """,Boolean.class,institutionId,courseId);
    if(!Boolean.TRUE.equals(sourceOwned)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Only the source institution may edit this course. Shared courses are read-only in Education Admin.");
    }
    Boolean alreadyReleased=jdbc.queryForObject("""
      select
        exists(select 1 from institution_course_release r
          where r.program_id=? and r.source_institution_id=?
            and r.release_state in ('IN_QUALITY_REVIEW','QUALITY_APPROVED','SHAREABLE','LEGACY_MIGRATED','WITHDRAWN'))
        or exists(select 1 from institution_marketplace_offer o
          where o.course_id=?
            and (o.status='PUBLISHED' or o.hrms_published_by_staff_id is not null))
      """,Boolean.class,courseId,institutionId,courseId);
    if(Boolean.TRUE.equals(alreadyReleased)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This course version has entered the release or Marketplace workflow and is immutable. Create a draft version first; edits here must never change a published release.");
    }
  }

  private void requireAssignedCourse(long courseId,long institutionId) {
    if(courseId<1L) throw badRequest("Select a valid course.");
    Boolean assigned=jdbc.queryForObject("""
      select exists(
        select 1 from curriculum_program p
        join institution_course_assignment assignment
          on assignment.institution_id=? and assignment.status='ASSIGNED'
        join institution_course_release release
          on release.id=assignment.course_release_id and release.program_id=p.id
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
      )
      """,Boolean.class,institutionId,courseId);
    if(!Boolean.TRUE.equals(assigned))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "The selected course is not assigned to the authenticated institution.");
  }

  private void authorize(String suppliedToken, AuthContext context, String permission) {
    // Shared browser tokens never grant access. Every task is checked against the authenticated identity.
    authorization.requirePermission(context,permission);
  }

  private static List<Map<String, Object>> objectList(Object value, String name) {
    if (!(value instanceof List<?> list)) throw badRequest(name + " must be a JSON array.");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) throw badRequest("Each entry in " + name + " must be an object.");
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw badRequest("Invalid object key in " + name + ".");
        converted.put(key, entry.getValue());
      }
      result.add(converted);
    }
    return result;
  }

  private static Object first(Map<String, Object> map, String preferred, String alternate) {
    return map.containsKey(preferred) ? map.get(preferred) : map.get(alternate);
  }

  private static String requiredText(Object value, int maxLength) {
    if (!(value instanceof String text) || text.isBlank() || text.trim().length() > maxLength)
      throw badRequest("A non-empty text value no longer than " + maxLength + " characters is required.");
    return text.trim();
  }

  private static String optionalText(Object value, int maxLength) {
    if (value == null) return null;
    if (!(value instanceof String text) || text.trim().length() > maxLength)
      throw badRequest("Text must be no longer than " + maxLength + " characters.");
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static int integerValue(Object value, int min, int max, String name) {
    if (!(value instanceof Number number)) throw badRequest(name + " must be a number.");
    int result = number.intValue();
    if (result < min || result > max) throw badRequest(name + " must be between " + min + " and " + max + ".");
    return result;
  }

  private static long longValue(Object value, String name) {
    if (!(value instanceof Number number)) throw badRequest(name + " must be numeric.");
    return number.longValue();
  }

  private static boolean booleanValue(Object value, boolean fallback) {
    return value instanceof Boolean bool ? bool : fallback;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String type, long id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " " + id + " was not found.");
  }
}
