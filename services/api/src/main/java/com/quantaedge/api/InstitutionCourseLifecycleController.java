package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Institution-admin course origination, release approval and local offer lifecycle.
 *
 * Institution scope always comes from the separate institution_admin_session cookie. A client
 * tenant header is never used to determine which institution admin is allowed to manage.
 */
@RestController
@RequestMapping("/api/v1/institution")
public class InstitutionCourseLifecycleController {
  private final JdbcTemplate jdbc;
  private final InstitutionAuthService institutionAuth;
  private final ObjectMapper objectMapper;

  public InstitutionCourseLifecycleController(
      JdbcTemplate jdbc, InstitutionAuthService institutionAuth, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.institutionAuth = institutionAuth;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/courses")
  public List<Map<String,Object>> courses(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select a.id as assignment_id,a.assignment_source,a.status as assignment_status,
          p.id as course_id,p.public_id,p.code as course_code,p.display_name,p.curriculum_version,
          p.class_id,r.id as release_id,r.release_version,r.status as release_status,
          r.quality_evidence::text as quality_evidence
        from institution_course_assignment a
        join curriculum_program p on p.id=a.program_id
        left join institution_course_release r on r.id=a.course_release_id
        where a.institution_id=? and a.status='ACTIVE'
        order by p.display_name,p.id,r.release_version desc nulls last,a.id
        """, principal.institutionId());
    for (Map<String,Object> row : rows) {
      long courseId = ((Number) row.get("course_id")).longValue();
      row.put("subjects", jdbc.queryForList("""
          select s.id,s.code as subject_code,s.display_name as subject_name,ps.is_required,ps.sort_order,
            count(distinct ch.id) as published_chapters,count(distinct l.id) as published_lessons
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
          left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
          where ps.program_id=?
          group by s.id,s.code,s.display_name,ps.is_required,ps.sort_order,s.sort_order
          order by ps.sort_order,s.sort_order,s.id
          """, courseId));
    }
    return rows;
  }

  /** Creates an institution-owned course shell with its own program-specific subject tracks. */
  @PostMapping("/courses")
  @Transactional
  public Map<String,Object> createCourse(
      @RequestBody Map<String,Object> body, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    String classCode = requiredText(body, "classCode", 1, 20, "Class code");
    String requestedCode = requiredText(body, "code", 2, 38, "Course code").toUpperCase(Locale.ROOT);
    if (!requestedCode.matches("^[A-Z0-9][A-Z0-9_-]{1,37}$")) {
      throw new IllegalArgumentException("Course code must use 2–38 letters, digits, hyphens or underscores.");
    }
    String displayName = requiredText(body, "displayName", 2, 200, "Course name");
    String curriculumVersion = optionalText(body.get("curriculumVersion"), "CURRENT", 1, 120, "Curriculum version");
    String streamCode = optionalText(body.get("streamCode"), null, 0, 60, "Stream code");
    Object rawSubjects = body.get("subjects");
    if (!(rawSubjects instanceof List<?> suppliedSubjects) || suppliedSubjects.isEmpty() || suppliedSubjects.size() > 30) {
      throw new IllegalArgumentException("Add between 1 and 30 subject tracks to this course.");
    }

    List<Map<String,String>> subjects = new ArrayList<>();
    Set<String> seenCodes = new HashSet<>();
    int sequence = 1;
    for (Object raw : suppliedSubjects) {
      if (!(raw instanceof Map<?,?> subject)) throw new IllegalArgumentException("Each subject must have a code and name.");
      String code = requiredText(mapOf(subject), "code", 2, 40, "Subject code").toLowerCase(Locale.ROOT);
      if (!code.matches("^[a-z0-9][a-z0-9_-]{1,39}$") || !seenCodes.add(code)) {
        throw new IllegalArgumentException("Subject codes must be unique and use 2–40 lowercase letters, digits, hyphens or underscores.");
      }
      String name = requiredText(mapOf(subject), "displayName", 2, 100, "Subject name");
      subjects.add(Map.of("code",code,"displayName",name,"sortOrder",String.valueOf(sequence++)));
    }

    List<Map<String,Object>> classes = jdbc.queryForList(
        "select id,code from curriculum_class where code=? and active=true", classCode);
    if (classes.isEmpty()) throw new IllegalArgumentException("Select an active curriculum class.");
    long classId = ((Number) classes.getFirst().get("id")).longValue();
    String internalCode = "I" + principal.institutionId() + "_" + requestedCode;
    Long courseId;
    String publicId;
    try {
      courseId = jdbc.queryForObject("""
          insert into curriculum_program(
            class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix
          ) values(?,?,?,?,?,'ACTIVE',?,'INS') returning id
          """, Long.class, classId, internalCode, displayName, streamCode, curriculumVersion,
          "Institution-origin course for tenant " + principal.tenantCode());
      publicId = jdbc.queryForObject("select public_id from curriculum_program where id=?", String.class, courseId);
      for (Map<String,String> subject : subjects) {
        int sortOrder = Integer.parseInt(subject.get("sortOrder"));
        Long subjectId = jdbc.queryForObject("""
            insert into curriculum_subject(class_id,code,display_name,sort_order,active,program_id)
            values(?,?,?,?,true,?) returning id
            """, Long.class, classId, subject.get("code"), subject.get("displayName"), sortOrder, courseId);
        jdbc.update("""
            insert into curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order)
            values(?,?,?,true,?)
            """, courseId, classId, subjectId, sortOrder);
      }
      jdbc.update("""
          insert into institution_course_assignment(
            institution_id,program_id,course_release_id,assignment_source,status,
            assigned_by_institution_admin_id,assigned_at,created_at,updated_at
          ) values(?,?,NULL,'ORIGIN','ACTIVE',?,now(),now(),now())
          """, principal.institutionId(), courseId, principal.adminId());
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A course with that code and curriculum version already exists for this institution.", ex);
    }
    recordEvent(principal.institutionId(), null, null, null, "COURSE_CREATED", principal.adminId(),
        Map.of("courseId",courseId,"courseCode",internalCode,"publicId",publicId,"subjectCount",subjects.size()));
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("created",true);
    result.put("courseId",courseId);
    result.put("publicId",publicId);
    result.put("courseCode",internalCode);
    result.put("displayName",displayName);
    result.put("curriculumVersion",curriculumVersion);
    result.put("subjectCount",subjects.size());
    result.put("status","ACTIVE");
    return result;
  }

  @GetMapping("/course-releases")
  public List<Map<String,Object>> courseReleases(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    return jdbc.queryForList("""
        select r.id as release_id,r.program_id as course_id,p.public_id,p.code as course_code,
          p.display_name,p.curriculum_version,r.release_version,r.status,r.quality_evidence::text as quality_evidence,
          r.content_fingerprint,r.submitted_at,r.origin_approved_at,r.rejection_reason
        from institution_course_release r
        join curriculum_program p on p.id=r.program_id
        where r.origin_institution_id=?
        order by r.program_id,r.release_version desc,r.id desc
        """, principal.institutionId());
  }

  /** A release can be submitted only after the existing content-review workflow has published
   * every mapped subject track. The evidence/fingerprint is captured and checked again on approval.
   */
  @PostMapping("/courses/{programId}/release-requests")
  @Transactional
  public Map<String,Object> submitRelease(
      @PathVariable long programId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    List<Map<String,Object>> courseRows = jdbc.queryForList("""
        select p.id,p.code,p.display_name,p.status
        from curriculum_program p
        join institution_course_assignment owner_assignment on owner_assignment.program_id=p.id
          and owner_assignment.institution_id=? and owner_assignment.course_release_id is null
          and owner_assignment.assignment_source in ('ORIGIN','LEGACY_BOOTSTRAP')
          and owner_assignment.status='ACTIVE'
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
        for update of p
        """, principal.institutionId(), programId);
    if (courseRows.isEmpty()) throw new SecurityException("This active course is not owned by the current institution.");
    List<Map<String,Object>> existing = jdbc.queryForList("""
        select id,status from institution_course_release
        where origin_institution_id=? and program_id=? and status in ('ORIGIN_REVIEW','SHAREABLE')
        order by release_version desc limit 1
        """, principal.institutionId(), programId);
    if (!existing.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This course already has a release under origin review or approved for sharing.");
    }

    Map<String,Object> evidence = jdbc.queryForMap("""
        select count(distinct ps.subject_id) as subject_count,
          count(distinct case when l.id is not null then ps.subject_id end) as subjects_with_published_lessons,
          count(distinct ch.id) as published_chapters,
          count(distinct l.id) as published_lessons
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where ps.program_id=?
        """, programId);
    long subjects = number(evidence.get("subject_count"));
    long coveredSubjects = number(evidence.get("subjects_with_published_lessons"));
    long chapters = number(evidence.get("published_chapters"));
    long lessons = number(evidence.get("published_lessons"));
    if (subjects == 0 || coveredSubjects != subjects || chapters == 0 || lessons == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This course is not ready for institution approval. Every mapped active subject needs published, content-reviewed lesson content.");
    }
    evidence.put("capturedAt", java.time.Instant.now().toString());
    String fingerprint = contentFingerprint(programId);
    int version = jdbc.queryForObject("""
        select coalesce(max(release_version),0)+1
        from institution_course_release where origin_institution_id=? and program_id=?
        """, Integer.class, principal.institutionId(), programId);
    Long releaseId = jdbc.queryForObject("""
        insert into institution_course_release(
          origin_institution_id,program_id,release_version,status,quality_evidence,content_fingerprint,
          submitted_by_institution_admin_id,submitted_at,created_at,updated_at
        ) values(?,?,?,'ORIGIN_REVIEW',?::jsonb,?,?,now(),now(),now()) returning id
        """, Long.class, principal.institutionId(), programId, version, json(evidence), fingerprint, principal.adminId());
    Long assignmentId = jdbc.queryForObject("""
        insert into institution_course_assignment(
          institution_id,program_id,course_release_id,assignment_source,status,
          assigned_by_institution_admin_id,assigned_at,created_at,updated_at
        ) values(?,?,?,'ORIGIN','ACTIVE',?,now(),now(),now()) returning id
        """, Long.class, principal.institutionId(), programId, releaseId, principal.adminId());
    recordEvent(principal.institutionId(), releaseId, assignmentId, null, "RELEASE_SUBMITTED",
        principal.adminId(), Map.of("programId",programId,"releaseVersion",version,"contentFingerprint",fingerprint));
    return Map.of("submitted",true,"releaseId",releaseId,"programId",programId,
        "releaseVersion",version,"status","ORIGIN_REVIEW","qualityEvidence",evidence);
  }

  @PostMapping("/course-releases/{releaseId}/approve")
  @Transactional
  public Map<String,Object> approveRelease(
      @PathVariable long releaseId, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select r.id,r.program_id,r.status,r.content_fingerprint,r.release_version
        from institution_course_release r
        where r.id=? and r.origin_institution_id=?
        for update
        """, releaseId, principal.institutionId());
    if (rows.isEmpty()) throw new SecurityException("This course release is not owned by the current institution.");
    Map<String,Object> row = rows.getFirst();
    if (!"ORIGIN_REVIEW".equals(String.valueOf(row.get("status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a release awaiting origin approval can be approved.");
    }
    long programId = ((Number)row.get("program_id")).longValue();
    String expectedFingerprint = String.valueOf(row.get("content_fingerprint")).trim();
    String currentFingerprint = contentFingerprint(programId);
    if (!expectedFingerprint.equals(currentFingerprint)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Course content changed after release submission. Submit a fresh release for review.");
    }
    int changed = jdbc.update("""
        update institution_course_release set status='SHAREABLE',
          origin_approved_by_institution_admin_id=?,origin_approved_at=now(),updated_at=now()
        where id=? and origin_institution_id=? and status='ORIGIN_REVIEW'
        """, principal.adminId(), releaseId, principal.institutionId());
    if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,"The release changed during approval.");
    recordEvent(principal.institutionId(), releaseId, null, null, "RELEASE_APPROVED",
        principal.adminId(), Map.of("programId",programId));
    return Map.of("approved",true,"releaseId",releaseId,"programId",programId,"status","SHAREABLE");
  }

  @PostMapping("/course-releases/{releaseId}/reject")
  @Transactional
  public Map<String,Object> rejectRelease(
      @PathVariable long releaseId, @RequestBody Map<String,Object> body, HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    String reason = requiredText(body,"reason",3,500,"Rejection reason");
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select id,program_id,status from institution_course_release
        where id=? and origin_institution_id=? for update
        """, releaseId, principal.institutionId());
    if (rows.isEmpty()) throw new SecurityException("This course release is not owned by the current institution.");
    if (!"ORIGIN_REVIEW".equals(String.valueOf(rows.getFirst().get("status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a release awaiting origin approval can be rejected.");
    }
    jdbc.update("""
        update institution_course_release set status='REJECTED',rejected_by_institution_admin_id=?,
          rejected_at=now(),rejection_reason=?,updated_at=now() where id=?
        """, principal.adminId(),reason,releaseId);
    recordEvent(principal.institutionId(),releaseId,null,null,"RELEASE_REJECTED",principal.adminId(),Map.of("reason",reason));
    return Map.of("rejected",true,"releaseId",releaseId,"status","REJECTED");
  }

  @PostMapping("/course-releases/{releaseId}/revoke")
  @Transactional
  public Map<String,Object> revokeRelease(@PathVariable long releaseId,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal = requirePrincipal(request);
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select id,program_id,status from institution_course_release
        where id=? and origin_institution_id=? for update
        """,releaseId,principal.institutionId());
    if(rows.isEmpty()) throw new SecurityException("This course release is not owned by the current institution.");
    if(!"SHAREABLE".equals(String.valueOf(rows.getFirst().get("status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only a shareable release can be revoked.");
    }
    List<Map<String,Object>> assignmentRows = jdbc.queryForList("""
        select id,institution_id from institution_course_assignment
        where course_release_id=? and status='ACTIVE' for update
        """,releaseId);
    jdbc.update("""
        update institution_course_release set status='REVOKED',revoked_by_institution_admin_id=?,
          revoked_at=now(),updated_at=now() where id=?
        """,principal.adminId(),releaseId);
    jdbc.update("""
        update institution_marketplace_offer set status='ARCHIVED',updated_at=now()
        where course_release_id=? and status<>'ARCHIVED'
        """,releaseId);
    jdbc.update("""
        update institution_course_assignment set status='REVOKED',revoked_at=now(),updated_at=now()
        where course_release_id=? and status='ACTIVE'
        """,releaseId);
    for(Map<String,Object> assignment:assignmentRows) {
      long tenantId=((Number)assignment.get("institution_id")).longValue();
      Long actorAdminId=tenantId==principal.institutionId()?principal.adminId():null;
      recordEvent(tenantId,releaseId,((Number)assignment.get("id")).longValue(),null,
          "RELEASE_REVOKED",actorAdminId,Map.of("originInstitutionId",principal.institutionId()));
    }
    return Map.of("revoked",true,"releaseId",releaseId,"status","REVOKED","assignmentsRevoked",assignmentRows.size());
  }

  @GetMapping("/course-assignments")
  public List<Map<String,Object>> courseAssignments(HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    return jdbc.queryForList("""
        select a.id as assignment_id,a.assignment_source,a.status as assignment_status,
          a.course_release_id as release_id,r.origin_institution_id,r.release_version,
          p.id as course_id,p.public_id,p.code as course_code,p.display_name,p.curriculum_version,
          o.id as offer_id,o.status as offer_status,o.price_paise,o.currency,o.title_override,o.description,
          o.approved_at,o.published_at
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id and r.status='SHAREABLE'
        join curriculum_program p on p.id=a.program_id and p.status='ACTIVE'
        left join institution_marketplace_offer o on o.assignment_id=a.id
        where a.institution_id=? and a.status='ACTIVE'
        order by p.display_name,p.id,r.release_version desc
        """,principal.institutionId());
  }

  @PostMapping("/course-assignments/{assignmentId}/offers")
  @Transactional
  public Map<String,Object> configureOffer(
      @PathVariable long assignmentId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    List<Map<String,Object>> assignments=jdbc.queryForList("""
        select a.id,a.institution_id,a.program_id,a.course_release_id,a.status assignment_status,
          r.status release_status,p.code,p.display_name
        from institution_course_assignment a
        join institution_course_release r on r.id=a.course_release_id
        join curriculum_program p on p.id=a.program_id
        where a.id=? and a.institution_id=? for update of a
        """,assignmentId,principal.institutionId());
    if(assignments.isEmpty()) throw new SecurityException("This course assignment is not available to the current institution.");
    Map<String,Object> assignment=assignments.getFirst();
    if(!"ACTIVE".equals(String.valueOf(assignment.get("assignment_status")))
        || !"SHAREABLE".equals(String.valueOf(assignment.get("release_status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Only an active assignment of a shareable course release can have an offer.");
    }
    String courseCode=String.valueOf(assignment.get("code"));
    long pricePaise;
    if(Set.of("CLASS_6","CLASS_7","CLASS_8").contains(courseCode)) {
      Object raw=body.get("pricePaise");
      pricePaise=raw instanceof Number?number(raw):100000L;
      if(pricePaise!=100000L) throw new IllegalArgumentException("Class 6, Class 7 and Class 8 offers must be priced at INR 1,000.");
    } else {
      if(!(body.get("pricePaise") instanceof Number)) throw new IllegalArgumentException("Price in paise is required.");
      pricePaise=number(body.get("pricePaise"));
      if(pricePaise<0) throw new IllegalArgumentException("Price cannot be negative.");
    }
    String title=optionalText(body.get("titleOverride"),null,0,200,"Offer title");
    String description=optionalText(body.get("description"),null,0,5000,"Offer description");
    if(title!=null && title.isBlank()) throw new IllegalArgumentException("Offer title cannot be blank.");
    Long offerId;
    try {
      offerId=jdbc.queryForObject("""
          insert into institution_marketplace_offer(
            institution_id,assignment_id,course_release_id,program_id,title_override,description,
            price_paise,currency,status,created_by_institution_admin_id,updated_by_institution_admin_id,
            created_at,updated_at
          ) values(?,?,?,?,?,?,?,'INR','DRAFT',?,?,now(),now())
          on conflict(assignment_id) do update set title_override=excluded.title_override,
            description=excluded.description,price_paise=excluded.price_paise,currency='INR',
            status='DRAFT',rejection_reason=null,updated_by_institution_admin_id=excluded.updated_by_institution_admin_id,
            approved_by_institution_admin_id=null,approved_at=null,published_by_institution_admin_id=null,
            published_at=null,updated_at=now()
          returning id
          """,Long.class,principal.institutionId(),assignmentId,
          ((Number)assignment.get("course_release_id")).longValue(),
          ((Number)assignment.get("program_id")).longValue(),title,description,pricePaise,
          principal.adminId(),principal.adminId());
    } catch(DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"An offer for this course assignment already exists.",ex);
    }
    recordEvent(principal.institutionId(),((Number)assignment.get("course_release_id")).longValue(),
        assignmentId,offerId,"OFFER_DRAFTED",principal.adminId(),
        Map.of("pricePaise",pricePaise,"currency","INR","status","DRAFT"));
    return Map.of("saved",true,"offerId",offerId,"assignmentId",assignmentId,
        "courseId",assignment.get("program_id"),"pricePaise",pricePaise,"currency","INR","status","DRAFT");
  }

  @PostMapping("/course-offers/{offerId}/submit")
  @Transactional
  public Map<String,Object> submitOffer(@PathVariable long offerId,HttpServletRequest request) {
    return transitionOffer(offerId,"SUBMIT",null,request);
  }

  @PostMapping("/course-offers/{offerId}/approve")
  @Transactional
  public Map<String,Object> approveOffer(@PathVariable long offerId,HttpServletRequest request) {
    return transitionOffer(offerId,"APPROVE",null,request);
  }

  @PostMapping("/course-offers/{offerId}/publish")
  @Transactional
  public Map<String,Object> publishOffer(@PathVariable long offerId,HttpServletRequest request) {
    return transitionOffer(offerId,"PUBLISH",null,request);
  }

  @PostMapping("/course-offers/{offerId}/reject")
  @Transactional
  public Map<String,Object> rejectOffer(
      @PathVariable long offerId,@RequestBody Map<String,Object> body,HttpServletRequest request) {
    return transitionOffer(offerId,"REJECT",requiredText(body,"reason",3,500,"Rejection reason"),request);
  }

  private Map<String,Object> transitionOffer(
      long offerId,String action,String reason,HttpServletRequest request) {
    InstitutionAuthService.InstitutionPrincipal principal=requirePrincipal(request);
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select o.id,o.status,o.program_id,o.assignment_id,o.course_release_id,o.price_paise,o.currency,
          a.status as assignment_status,r.status as release_status
        from institution_marketplace_offer o
        join institution_course_assignment a on a.id=o.assignment_id and a.institution_id=o.institution_id
        join institution_course_release r on r.id=o.course_release_id and r.program_id=o.program_id
        where o.id=? and o.institution_id=?
        for update of o,a,r
        """,offerId,principal.institutionId());
    if(rows.isEmpty()) throw new SecurityException("This Marketplace offer is not available to the current institution.");
    Map<String,Object> row=rows.getFirst();
    String state=String.valueOf(row.get("status"));
    String next;
    String sql;
    switch(action) {
      case "SUBMIT" -> {
        if(!Set.of("DRAFT","REJECTED").contains(state)) throw conflict("Only a draft or rejected offer can be submitted for review.");
        if(!"ACTIVE".equals(String.valueOf(row.get("assignment_status")))
            || !"SHAREABLE".equals(String.valueOf(row.get("release_status")))) throw conflict("The assigned course release is not currently shareable.");
        next="IN_REVIEW";
        sql="update institution_marketplace_offer set status='IN_REVIEW',rejection_reason=null,updated_at=now() where id=?";
      }
      case "APPROVE" -> {
        if(!"IN_REVIEW".equals(state)) throw conflict("Only an offer in review can be approved.");
        if(!"ACTIVE".equals(String.valueOf(row.get("assignment_status")))
            || !"SHAREABLE".equals(String.valueOf(row.get("release_status")))) throw conflict("The assigned course release is not currently shareable.");
        next="APPROVED";
        sql="update institution_marketplace_offer set status='APPROVED',approved_by_institution_admin_id=?,approved_at=now(),updated_at=now() where id=?";
      }
      case "PUBLISH" -> {
        if(!"APPROVED".equals(state)) throw conflict("An offer must be approved before it can be published.");
        if(!"ACTIVE".equals(String.valueOf(row.get("assignment_status")))
            || !"SHAREABLE".equals(String.valueOf(row.get("release_status")))) throw conflict("The assigned course release is not currently shareable.");
        next="PUBLISHED";
        sql="update institution_marketplace_offer set status='PUBLISHED',published_by_institution_admin_id=?,published_at=now(),updated_at=now() where id=?";
      }
      case "REJECT" -> {
        if(!"IN_REVIEW".equals(state)) throw conflict("Only an offer in review can be rejected.");
        next="REJECTED";
        sql="update institution_marketplace_offer set status='REJECTED',rejection_reason=?,updated_at=now() where id=?";
      }
      default -> throw new IllegalArgumentException("Unsupported offer transition.");
    }
    switch(action) {
      case "APPROVE" -> jdbc.update(sql,principal.adminId(),offerId);
      case "PUBLISH" -> jdbc.update(sql,principal.adminId(),offerId);
      case "REJECT" -> jdbc.update(sql,reason,offerId);
      default -> jdbc.update(sql,offerId);
    }
    long releaseId=((Number)row.get("course_release_id")).longValue();
    long assignmentId=((Number)row.get("assignment_id")).longValue();
    recordEvent(principal.institutionId(),releaseId,assignmentId,offerId,"OFFER_"+next,
        principal.adminId(),Map.of("from",state,"to",next));
    return Map.of("updated",true,"offerId",offerId,"status",next);
  }

  private String contentFingerprint(long programId) {
    return jdbc.queryForObject("""
        select md5(coalesce(string_agg(entity_type||':'||entity_id::text||':'||row_json,E'\n'
            order by entity_type,entity_id),'')) as fingerprint
        from (
          select 'program'::text entity_type,p.id entity_id,to_jsonb(p)::text row_json
          from curriculum_program p where p.id=?
          union all
          select 'subject',s.id,to_jsonb(ps)::text||to_jsonb(s)::text
          from curriculum_program_subject ps
          join curriculum_program p on p.id=ps.program_id
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
            and (s.program_id=ps.program_id or s.program_id is null)
          where ps.program_id=?
          union all
          select 'chapter',ch.id,to_jsonb(ch)::text
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
          join curriculum_chapter ch on ch.subject_id=s.id
          where ps.program_id=?
          union all
          select 'lesson',l.id,to_jsonb(l)::text
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
          join curriculum_chapter ch on ch.subject_id=s.id
          join lesson l on l.chapter_id=ch.id
          where ps.program_id=?
        ) content_rows
        """,String.class,programId,programId,programId,programId);
  }

  private InstitutionAuthService.InstitutionPrincipal requirePrincipal(HttpServletRequest request) {
    String token=cookieValue(request,AuthService.INSTITUTION_COOKIE);
    InstitutionAuthService.InstitutionPrincipal principal=institutionAuth.current(token);
    if(principal==null) throw new SecurityException("Institution authentication required.");
    if(principal.mustChangePassword()) throw new SecurityException("Change the initial institution password before continuing.");
    return principal;
  }

  private static String cookieValue(HttpServletRequest request,String name) {
    if(request.getCookies()==null) return null;
    for(Cookie cookie:request.getCookies()) {
      if(name.equals(cookie.getName())&&cookie.getValue()!=null&&!cookie.getValue().isBlank()) return cookie.getValue();
    }
    return null;
  }

  private void recordEvent(long institutionId,Long releaseId,Long assignmentId,Long offerId,
      String type,Long actorAdminId,Map<String,Object> details) {
    jdbc.update("""
        insert into institution_course_lifecycle_event(
          institution_id,course_release_id,assignment_id,offer_id,event_type,actor_institution_admin_id,details
        ) values(?,?,?,?,?,?,?::jsonb)
        """,institutionId,releaseId,assignmentId,offerId,type,actorAdminId,json(details));
  }

  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value); }
    catch(JsonProcessingException ex) { throw new IllegalArgumentException("Lifecycle details could not be serialized.",ex); }
  }

  private static Map<String,Object> mapOf(Map<?,?> raw) {
    Map<String,Object> result=new LinkedHashMap<>();
    raw.forEach((key,value)->result.put(String.valueOf(key),value));
    return result;
  }

  private static String requiredText(Map<String,Object> body,String key,int min,int max,String label) {
    String value=body==null||body.get(key)==null?"":String.valueOf(body.get(key)).trim();
    if(value.length()<min||value.length()>max) throw new IllegalArgumentException(label+" must be "+min+"–"+max+" characters.");
    return value;
  }

  private static String optionalText(Object raw,String defaultValue,int min,int max,String label) {
    if(raw==null) return defaultValue;
    String value=String.valueOf(raw).trim();
    if(value.isEmpty()&&min==0) return null;
    if(value.length()<min||value.length()>max) throw new IllegalArgumentException(label+" must be "+min+"–"+max+" characters.");
    return value;
  }

  private static long number(Object raw) {
    if(raw instanceof Number n) return n.longValue();
    try { return Long.parseLong(String.valueOf(raw)); }
    catch(RuntimeException ex) { throw new IllegalArgumentException("A numeric value was expected."); }
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
}
