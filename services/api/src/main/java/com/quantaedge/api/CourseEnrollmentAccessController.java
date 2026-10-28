package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * HRMS controls for the shared logical-course expiry policy and individual enrollment access.
 *
 * The policy is keyed by institution_course_identity, not by an institution, offer, release, or
 * content version. One HRMS change therefore applies to future enrollments across all institutions
 * sharing that logical course. Existing enrollments keep their saved expiry snapshot.
 */
@RestController
@RequestMapping("/api/v1/hrms/platform/course-access")
public class CourseEnrollmentAccessController {
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;

  public CourseEnrollmentAccessController(
      JdbcTemplate jdbc, AuthorizationService authorization, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/courses")
  public List<Map<String,Object>> listCourses(
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requirePlatformOwner(context);
    return jdbc.queryForList("""
      select identity.id as course_identity_id,identity.public_id as course_identity_public_id,
        identity.stable_course_key,identity.display_name,identity.course_type_code,
        identity.source_institution_id,source.tenant_code as source_institution_code,
        coalesce(branding.display_name,source.display_name) as source_institution_name,
        current_program.id as current_course_id,current_program.code as current_course_code,
        current_program.content_version_no as current_content_version,
        policy.policy_mode,policy.calendar_years,policy.calendar_months,policy.calendar_days,
        policy.fixed_days,policy.policy_version,policy.updated_at as policy_updated_at,
        policy.change_note as policy_change_note,
        (select count(distinct assignment.institution_id)
          from institution_course_assignment assignment
          join institution_course_release release on release.id=assignment.course_release_id
          join curriculum_program assigned_program on assigned_program.id=release.program_id
          where assignment.status='ASSIGNED'
            and assigned_program.course_identity_id=identity.id) as assigned_institution_count,
        (select count(*) from (
            select distinct enrollment.institution_id,enrollment.student_id
            from student_program_enrollment enrollment
            where enrollment.course_identity_id=identity.id and enrollment.status='ACTIVE'
          ) logical_enrolments) as active_enrollment_count
      from institution_course_identity identity
      join institution_tenant source on source.id=identity.source_institution_id
      left join institution_branding branding on branding.institution_id=source.id
      left join lateral (
        select program.id,program.code,program.content_version_no
        from curriculum_program program
        where program.course_identity_id=identity.id and program.status='ACTIVE'
        order by program.content_version_no desc,program.id desc
        limit 1
      ) current_program on true
      left join course_expiry_policy policy on policy.course_identity_id=identity.id
      where identity.status='ACTIVE'
      order by identity.display_name,identity.stable_course_key,identity.id
      """);
  }

  @PutMapping("/courses/{courseIdentityId}/policy")
  @Transactional
  public Map<String,Object> savePolicy(
      @PathVariable long courseIdentityId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    String notes=requiredNotes(body);
    String mode=string(body,"mode").toUpperCase(Locale.ROOT);
    if(!List.of("CALENDAR_DURATION","FIXED_DAYS").contains(mode)) {
      throw new IllegalArgumentException("Choose calendar duration or fixed number of days.");
    }

    Integer years=null,months=null,days=null,fixedDays=null;
    if("CALENDAR_DURATION".equals(mode)) {
      years=nonNegativeInt(body,"years");
      months=nonNegativeInt(body,"months");
      days=nonNegativeInt(body,"days");
      if((long)years+months+days<1L) {
        throw new IllegalArgumentException("Enter at least one year, month, or day.");
      }
    } else {
      fixedDays=positiveInt(body,"fixedDays");
      if(fixedDays>36500) throw new IllegalArgumentException("Fixed duration cannot exceed 36500 days.");
    }

    List<Map<String,Object>> identities=jdbc.queryForList("""
      select id from institution_course_identity
      where id=? and status='ACTIVE'
      for update
      """,courseIdentityId);
    if(identities.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The logical course was not found or is archived.");

    List<Map<String,Object>> oldRows=jdbc.queryForList("""
      select course_identity_id,policy_mode,calendar_years,calendar_months,calendar_days,
        fixed_days,policy_version,change_note
      from course_expiry_policy where course_identity_id=?
      """,courseIdentityId);
    Map<String,Object> previous=oldRows.isEmpty()?Map.of():new LinkedHashMap<>(oldRows.getFirst());

    Long policyVersion=jdbc.queryForObject("""
      insert into course_expiry_policy(
        course_identity_id,policy_mode,calendar_years,calendar_months,calendar_days,fixed_days,
        policy_version,updated_by_staff_id,change_note,created_at,updated_at
      ) values(?,?,?,?,?,?,1,?,?,now(),now())
      on conflict(course_identity_id) do update set
        policy_mode=excluded.policy_mode,
        calendar_years=excluded.calendar_years,
        calendar_months=excluded.calendar_months,
        calendar_days=excluded.calendar_days,
        fixed_days=excluded.fixed_days,
        policy_version=course_expiry_policy.policy_version+1,
        updated_by_staff_id=excluded.updated_by_staff_id,
        change_note=excluded.change_note,
        updated_at=now()
      returning policy_version
      """,Long.class,courseIdentityId,mode,years,months,days,fixedDays,owner.staffId(),notes);

    Map<String,Object> next=new LinkedHashMap<>();
    next.put("courseIdentityId",courseIdentityId);
    next.put("mode",mode);
    next.put("years",years);
    next.put("months",months);
    next.put("days",days);
    next.put("fixedDays",fixedDays);
    next.put("policyVersion",policyVersion);
    next.put("changeNote",notes);
    jdbc.update("""
      insert into course_expiry_policy_event(
        course_identity_id,event_type,policy_version,previous_policy,new_policy,notes,actor_staff_id
      ) values(?,?,?,?::jsonb,?::jsonb,?,?)
      """,courseIdentityId,oldRows.isEmpty()?"POLICY_CREATED":"POLICY_UPDATED",policyVersion,
        json(previous),json(next),notes,owner.staffId());

    auditPolicyChange(owner,courseIdentityId,oldRows.isEmpty()?"COURSE_EXPIRY_POLICY_CREATED":"COURSE_EXPIRY_POLICY_UPDATED",
        Map.of("policyVersion",policyVersion,"mode",mode,"notes",notes));
    Map<String,Object> response=new LinkedHashMap<>(next);
    response.put("saved",true);
    response.put("appliesTo","ALL_INSTITUTIONS_SHARING_THIS_COURSE");
    response.put("existingEnrollmentsChanged",false);
    return response;
  }

  @GetMapping("/courses/{courseIdentityId}/enrollments")
  public Map<String,Object> listEnrollments(
      @PathVariable long courseIdentityId,
      @RequestParam(defaultValue="") String q,
      @RequestParam(defaultValue="50") int limit,
      @RequestParam(defaultValue="0") int offset,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requirePlatformOwner(context);
    assertCourseIdentityExists(courseIdentityId);
    if(limit<1||limit>100) throw new IllegalArgumentException("Page size must be between 1 and 100.");
    if(offset<0) throw new IllegalArgumentException("Offset cannot be negative.");
    String search=q==null?"":q.trim();
    if(search.length()>120) throw new IllegalArgumentException("Search must be 120 characters or fewer.");
    String pattern="%"+search.toLowerCase(Locale.ROOT)+"%";

    List<Map<String,Object>> rows=jdbc.queryForList("""
      with ranked_enrolments as (
        select enrollment.id as enrollment_id,enrollment.course_identity_id,enrollment.institution_id,
          tenant.tenant_code as institution_code,coalesce(branding.display_name,tenant.display_name) as institution_name,
          enrollment.student_id,student.public_user_id as student_public_id,
          student.display_name as student_name,enrollment.program_id,enrollment.status as enrollment_status,
          enrollment.enrolled_at,enrollment.course_expires_at,enrollment.course_access_blocked,
          enrollment.course_access_blocked_at,enrollment.course_access_blocked_by_staff_id,
          enrollment.course_expiry_mode_snapshot,enrollment.course_expiry_policy_version_snapshot,
          row_number() over (
            partition by enrollment.course_identity_id,enrollment.institution_id,enrollment.student_id
            order by case enrollment.status when 'ACTIVE' then 0 when 'PAUSED' then 1 else 2 end,
              enrollment.enrolled_at asc,enrollment.id asc
          ) as logical_enrollment_rank
        from student_program_enrollment enrollment
        join student on student.id=enrollment.student_id
        join institution_tenant tenant on tenant.id=enrollment.institution_id
        left join institution_branding branding on branding.institution_id=tenant.id
        where enrollment.course_identity_id=?
          and (?='' or lower(coalesce(student.display_name,'')) like ?
            or lower(coalesce(student.public_user_id,'')) like ?
            or lower(tenant.display_name) like ? or lower(tenant.tenant_code) like ?)
      )
      select ranked_enrolments.*,
        case when course_access_blocked then false
          when course_expires_at is not null and course_expires_at<=statement_timestamp() then false
          when enrollment_status='ACTIVE' then true
          else false end as effective_access,
        case when course_access_blocked then 'BLOCKED'
          when course_expires_at is not null and course_expires_at<=statement_timestamp() then 'EXPIRED'
          when enrollment_status<>'ACTIVE' then enrollment_status
          else 'ACTIVE' end as access_status
      from ranked_enrolments
      where logical_enrollment_rank=1
      order by enrolled_at desc,enrollment_id desc
      limit ? offset ?
      """,courseIdentityId,search,pattern,pattern,pattern,pattern,limit,offset);
    Long total=jdbc.queryForObject("""
      select count(*) from (
        select enrollment.course_identity_id,enrollment.institution_id,enrollment.student_id
        from student_program_enrollment enrollment
        join student on student.id=enrollment.student_id
        join institution_tenant tenant on tenant.id=enrollment.institution_id
        where enrollment.course_identity_id=?
          and (?='' or lower(coalesce(student.display_name,'')) like ?
            or lower(coalesce(student.public_user_id,'')) like ?
            or lower(tenant.display_name) like ? or lower(tenant.tenant_code) like ?)
        group by enrollment.course_identity_id,enrollment.institution_id,enrollment.student_id
      ) logical_enrolments
      """,Long.class,courseIdentityId,search,pattern,pattern,pattern,pattern);

    Map<String,Object> result=new LinkedHashMap<>();
    result.put("items",rows);
    result.put("total",total==null?0L:total);
    result.put("limit",limit);
    result.put("offset",offset);
    result.put("courseIdentityId",courseIdentityId);
    return result;
  }

  @PostMapping("/enrollments/{enrollmentId}/block")
  @Transactional
  public Map<String,Object> blockEnrollment(
      @PathVariable long enrollmentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    String notes=requiredNotes(body);
    Map<String,Object> row=lockEnrollment(enrollmentId);
    boolean wasBlocked=Boolean.TRUE.equals(row.get("course_access_blocked"));
    if(wasBlocked) throw new ResponseStatusException(HttpStatus.CONFLICT,"This enrollment is already manually blocked.");
    if(!List.of("ACTIVE","PAUSED").contains(String.valueOf(row.get("enrollment_status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"This course enrolment has ended; its history is retained and there is no active access to block.");
    }
    jdbc.update("""
      update student_program_enrollment
      set course_access_blocked=true,course_access_blocked_at=now(),
          course_access_blocked_by_staff_id=?,updated_at=now()
      where course_identity_id=? and institution_id=? and student_id=?
        and status in ('ACTIVE','PAUSED') and course_access_blocked=false
      """,owner.staffId(),row.get("course_identity_id"),row.get("institution_id"),row.get("student_id"));
    insertAccessEvent(row,"BLOCKED",row.get("course_expires_at"),row.get("course_expires_at"),false,true,notes,owner.staffId());
    auditEnrollmentChange(owner,row,"COURSE_ENROLLMENT_BLOCKED",Map.of("notes",notes));
    return enrollmentResult(enrollmentId,"BLOCKED");
  }

  @PostMapping("/enrollments/{enrollmentId}/unblock")
  @Transactional
  public Map<String,Object> unblockEnrollment(
      @PathVariable long enrollmentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    String notes=requiredNotes(body);
    Map<String,Object> row=lockEnrollment(enrollmentId);
    if(!Boolean.TRUE.equals(row.get("course_access_blocked"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"This enrollment is not manually blocked.");
    }
    if(!List.of("ACTIVE","PAUSED").contains(String.valueOf(row.get("enrollment_status")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"The course enrolment has ended. Unblocking does not renew or reactivate it.");
    }
    Instant expiresAt=asInstant(row.get("course_expires_at"));
    if(expiresAt!=null&&!expiresAt.isAfter(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"The course access has expired. Extend the expiry first, then unblock the enrollment.");
    }
    jdbc.update("""
      update student_program_enrollment
      set course_access_blocked=false,course_access_blocked_at=null,
          course_access_blocked_by_staff_id=null,updated_at=now()
      where course_identity_id=? and institution_id=? and student_id=?
        and status in ('ACTIVE','PAUSED') and course_access_blocked=true
      """,row.get("course_identity_id"),row.get("institution_id"),row.get("student_id"));
    insertAccessEvent(row,"UNBLOCKED",row.get("course_expires_at"),row.get("course_expires_at"),true,false,notes,owner.staffId());
    auditEnrollmentChange(owner,row,"COURSE_ENROLLMENT_UNBLOCKED",Map.of("notes",notes));
    return enrollmentResult(enrollmentId,"UNBLOCKED");
  }

  @PostMapping("/enrollments/{enrollmentId}/extend")
  @Transactional
  public Map<String,Object> extendEnrollment(
      @PathVariable long enrollmentId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    AuthContext owner=requirePlatformOwner(context);
    String notes=requiredNotes(body);
    Instant requestedExpiry=parseInstant(string(body,"expiresAt"));
    Instant now=Instant.now();
    if(!requestedExpiry.isAfter(now)) throw new IllegalArgumentException("The new expiry must be in the future.");
    Map<String,Object> row=lockEnrollment(enrollmentId);
    String status=String.valueOf(row.get("enrollment_status"));
    if("ENDED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"An ended enrollment cannot be extended. Start a separately authorized renewal instead.");
    Instant previousExpiry=asInstant(row.get("course_expires_at"));
    if(previousExpiry!=null&&!requestedExpiry.isAfter(previousExpiry)) {
      throw new IllegalArgumentException("An extension must be later than the current expiry. Use the block/unblock controls for access restrictions.");
    }
    jdbc.update("""
      update student_program_enrollment
      set course_expires_at=?,updated_at=now()
      where course_identity_id=? and institution_id=? and student_id=?
        and status in ('ACTIVE','PAUSED')
      """,Timestamp.from(requestedExpiry),row.get("course_identity_id"),row.get("institution_id"),row.get("student_id"));
    String action=previousExpiry==null?"EXPIRY_SET":"EXPIRY_EXTENDED";
    insertAccessEvent(row,action,row.get("course_expires_at"),Timestamp.from(requestedExpiry),
        Boolean.TRUE.equals(row.get("course_access_blocked")),Boolean.TRUE.equals(row.get("course_access_blocked")),
        notes,owner.staffId());
    auditEnrollmentChange(owner,row,previousExpiry==null?"COURSE_ENROLLMENT_EXPIRY_SET":"COURSE_ENROLLMENT_EXPIRY_EXTENDED",
        Map.of("previousExpiry",previousExpiry==null?"":previousExpiry.toString(),
          "newExpiry",requestedExpiry.toString(),"notes",notes));
    Map<String,Object> result=enrollmentResult(enrollmentId,action);
    result.put("expiresAt",requestedExpiry.toString());
    result.put("accessRestored",false);
    result.put("note","Expiry changed for this learner's logical course enrolment in this institution; all content versions share the change and any manual block remains in effect.");
    return result;
  }

  @GetMapping("/courses/{courseIdentityId}/audit")
  public Map<String,Object> courseAudit(
      @PathVariable long courseIdentityId,
      @RequestParam(defaultValue="100") int limit,
      @RequestAttribute(value="authContext",required=false) AuthContext context) {
    requirePlatformOwner(context);
    assertCourseIdentityExists(courseIdentityId);
    if(limit<1||limit>300) throw new IllegalArgumentException("Audit page size must be between 1 and 300.");
    List<Map<String,Object>> policyEvents=jdbc.queryForList("""
      select event.id,event.event_type,event.policy_version,event.previous_policy,event.new_policy,
        event.notes,event.actor_staff_id,event.created_at
      from course_expiry_policy_event event
      where event.course_identity_id=?
      order by event.created_at desc,event.id desc limit ?
      """,courseIdentityId,limit);
    List<Map<String,Object>> accessEvents=jdbc.queryForList("""
      select event.id,event.event_type,event.enrollment_id,event.institution_id,
        tenant.tenant_code as institution_code,coalesce(branding.display_name,tenant.display_name) as institution_name,
        event.student_id,student.display_name as student_name,event.previous_expiry,event.new_expiry,
        event.previous_blocked,event.new_blocked,event.notes,event.actor_staff_id,event.created_at
      from course_enrollment_access_event event
      join student on student.id=event.student_id
      join institution_tenant tenant on tenant.id=event.institution_id
      left join institution_branding branding on branding.institution_id=tenant.id
      where event.course_identity_id=?
      order by event.created_at desc,event.id desc limit ?
      """,courseIdentityId,limit);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("policyEvents",policyEvents);
    result.put("enrollmentEvents",accessEvents);
    return result;
  }

  private AuthContext requirePlatformOwner(AuthContext context) {
    return authorization.requirePermission(context,"QE_PLATFORM_OWNER");
  }

  private void assertCourseIdentityExists(long courseIdentityId) {
    Boolean found=jdbc.queryForObject("""
      select exists(select 1 from institution_course_identity where id=?)
      """,Boolean.class,courseIdentityId);
    if(!Boolean.TRUE.equals(found)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Course identity not found.");
  }

  private Map<String,Object> lockEnrollment(long enrollmentId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select enrollment.id,enrollment.course_identity_id,enrollment.institution_id,enrollment.student_id,
        enrollment.status as enrollment_status,enrollment.course_expires_at,enrollment.course_access_blocked,
        enrollment.program_id
      from student_program_enrollment enrollment
      where enrollment.id=? and enrollment.course_identity_id is not null
      for update
      """,enrollmentId);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"The course enrollment was not found or is not linked to a logical course identity.");
    Map<String,Object> row=new LinkedHashMap<>(rows.getFirst());
    // Use the same logical enrolment lock as the database trigger. This closes the race where a
    // new content-version row could be inserted while HRMS is blocking or extending the course.
    jdbc.queryForList("""
      select pg_advisory_xact_lock(hashtextextended(
        ?::text || ':' || ?::text || ':' || ?::text,0))
      """,row.get("course_identity_id"),row.get("institution_id"),row.get("student_id"));
    return row;
  }

  private void insertAccessEvent(Map<String,Object> row,String eventType,Object previousExpiry,Object newExpiry,
      boolean previousBlocked,boolean newBlocked,String notes,long actorStaffId) {
    jdbc.update("""
      insert into course_enrollment_access_event(
        enrollment_id,course_identity_id,institution_id,student_id,event_type,
        previous_expiry,new_expiry,previous_blocked,new_blocked,notes,actor_staff_id
      ) values(?,?,?,?,?,?,?,?,?,?,?)
      """,row.get("id"),row.get("course_identity_id"),row.get("institution_id"),row.get("student_id"),
        eventType,previousExpiry,newExpiry,previousBlocked,newBlocked,notes,actorStaffId);
  }

  private Map<String,Object> enrollmentResult(long enrollmentId,String action) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select enrollment.id as enrollment_id,enrollment.course_identity_id,enrollment.institution_id,
        enrollment.student_id,enrollment.status as enrollment_status,enrollment.enrolled_at,
        enrollment.course_expires_at,enrollment.course_access_blocked,enrollment.course_access_blocked_at,
        case when enrollment.course_access_blocked then false
          when enrollment.course_expires_at is not null and enrollment.course_expires_at<=statement_timestamp() then false
          when enrollment.status='ACTIVE' then true else false end as effective_access,
        case when enrollment.course_access_blocked then 'BLOCKED'
          when enrollment.course_expires_at is not null and enrollment.course_expires_at<=statement_timestamp() then 'EXPIRED'
          when enrollment.status<>'ACTIVE' then enrollment.status
          else 'ACTIVE' end as access_status
      from student_program_enrollment enrollment where enrollment.id=?
      """,enrollmentId);
    Map<String,Object> result=new LinkedHashMap<>(rows.getFirst());
    result.put("action",action);
    return result;
  }

  private void auditPolicyChange(AuthContext owner,long courseIdentityId,String action,Map<String,Object> details) {
    writeInstitutionAudit(owner,null,action,"COURSE_IDENTITY",String.valueOf(courseIdentityId),details);
  }

  private void auditEnrollmentChange(AuthContext owner,Map<String,Object> row,String action,Map<String,Object> details) {
    Map<String,Object> merged=new LinkedHashMap<>(details);
    merged.put("enrollmentId",row.get("id"));
    merged.put("studentId",row.get("student_id"));
    merged.put("courseIdentityId",row.get("course_identity_id"));
    writeInstitutionAudit(owner,number(row.get("institution_id")),action,"COURSE_ENROLLMENT",
        String.valueOf(row.get("id")),merged);
  }

  private void writeInstitutionAudit(AuthContext owner,Long institutionId,String action,String targetType,String targetId,
      Map<String,Object> details) {
    jdbc.update("""
      insert into institution_audit_log(
        institution_id,actor_platform_staff_id,action,target_type,target_id,details
      ) values(?,?,?,?,?,?::jsonb)
      """,institutionId,owner.staffId(),action,targetType,targetId,json(details));
  }

  private String requiredNotes(Map<String,Object> body) {
    String notes=string(body,"notes");
    if(notes.length()<3||notes.length()>1000
        ||notes.chars().anyMatch(ch->Character.isISOControl(ch)&&ch!='\n'&&ch!='\t')) {
      throw new IllegalArgumentException("Enter a written reason between 3 and 1000 characters.");
    }
    return notes;
  }

  private String string(Map<String,Object> body,String key) {
    Object raw=body==null?null:body.get(key);
    return raw==null?"":String.valueOf(raw).trim();
  }

  private int nonNegativeInt(Map<String,Object> body,String key) {
    int value=wholeNumber(body,key);
    if(value<0||value>100) throw new IllegalArgumentException(key+" must be between 0 and 100.");
    return value;
  }

  private int positiveInt(Map<String,Object> body,String key) {
    int value=wholeNumber(body,key);
    if(value<1) throw new IllegalArgumentException(key+" must be at least 1.");
    return value;
  }

  private int wholeNumber(Map<String,Object> body,String key) {
    Object raw=body==null?null:body.get(key);
    if(!(raw instanceof Number n)||n.doubleValue()!=n.intValue()) {
      throw new IllegalArgumentException(key+" must be a whole number.");
    }
    return n.intValue();
  }

  private Instant parseInstant(String value) {
    try { return Instant.parse(value); }
    catch(DateTimeParseException ex) {
      try { return OffsetDateTime.parse(value).toInstant(); }
      catch(DateTimeParseException ignored) {
        throw new IllegalArgumentException("Expiry must be an ISO-8601 date/time with a timezone.");
      }
    }
  }

  private Instant asInstant(Object value) {
    if(value==null) return null;
    if(value instanceof Timestamp timestamp) return timestamp.toInstant();
    if(value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
    if(value instanceof Instant instant) return instant;
    try { return Instant.parse(String.valueOf(value)); }
    catch(DateTimeParseException ex) { return null; }
  }

  private long number(Object value) {
    if(value instanceof Number numeric) return numeric.longValue();
    throw new IllegalStateException("Expected a numeric institution ID in course access row.");
  }

  private String json(Object value) {
    try { return objectMapper.writeValueAsString(value==null?Map.of():value); }
    catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to serialize course access audit data.",ex); }
  }
}
