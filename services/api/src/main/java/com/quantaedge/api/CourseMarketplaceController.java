package com.quantaedge.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Server-authoritative course marketplace and Razorpay checkout.
 *
 * The browser/mobile client receives only the public Razorpay key ID and a server-created
 * order. Course price, payment capture status and learner entitlement are verified here.
 */
@RestController
@RequestMapping("/api/v1/marketplace")
public class CourseMarketplaceController {
  private static final long ORDER_EXPIRY_MINUTES = 30L;
  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final ObjectMapper objectMapper;
  private final CourseOrderFinancialService courseOrderFinancialService;
  private final String keyId;
  private final String keySecret;
  private final String baseUrl;
  // Optional dual-mode credentials allow a read-only HRMS trace to inspect legacy orders from
  // either environment when a shared database contains both. The normal checkout still uses
  // the existing app.razorpay.key-id/key-secret pair.
  @Value("${app.razorpay.test.key-id:}") private String testModeKeyId = "";
  @Value("${app.razorpay.test.key-secret:}") private String testModeKeySecret = "";
  @Value("${app.razorpay.live.key-id:}") private String liveModeKeyId = "";
  @Value("${app.razorpay.live.key-secret:}") private String liveModeKeySecret = "";
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build();

  public CourseMarketplaceController(
      JdbcTemplate jdbc,
      AuthorizationService authorization,
      ObjectMapper objectMapper,
      CourseOrderFinancialService courseOrderFinancialService,
      @Value("${app.razorpay.key-id:}") String keyId,
      @Value("${app.razorpay.key-secret:}") String keySecret,
      @Value("${app.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
    this.courseOrderFinancialService = courseOrderFinancialService;
    this.keyId = keyId == null ? "" : keyId.trim();
    this.keySecret = keySecret == null ? "" : keySecret.trim();
    this.baseUrl = (baseUrl == null ? "https://api.razorpay.com/v1" : baseUrl.trim()).replaceAll("/+$", "");
  }

  /** Only explicitly published offers are discoverable; this is not a list of every curriculum row. */
  @GetMapping("/courses")
  public List<Map<String, Object>> courses(
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    if (tenant == null) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "Select an institution before opening the marketplace.");
    }
    long institutionId = tenant.institutionId();
    List<Map<String, Object>> offers = jdbc.queryForList("""
        select p.id as course_id, p.public_id, coalesce(identity.stable_course_key,p.code) as course_code,
          identity.public_id as course_identity_public_id,
          coalesce(nullif(o.title_override,''),p.display_name) as title,
          p.display_name as course_name, p.curriculum_version,p.course_type_code,
          (select count(*) from course_assessment_pack pack
            where pack.course_id=p.id and pack.status='PUBLISHED') as test_pack_count,
          (select count(*) from assessment a
            where a.course_id=p.id and a.status='PUBLISHED') as assessment_count,
          (select count(*) from assessment_question aq join assessment a on a.id=aq.assessment_id
            where a.course_id=p.id and a.status='PUBLISHED') as assessment_question_count,
          o.description, o.price_paise, o.currency, o.status as offer_status,
          (exists(select 1 from student_program_enrollment e
            where e.institution_id=? and e.student_id=? and qe_same_logical_course(e.program_id,p.id)
              and e.status in ('ACTIVE','PAUSED'))
            or exists(select 1 from course_entitlement ce
              where ce.institution_id=? and qe_same_logical_course(ce.course_id,p.id)
                and (ce.holder_student_id=? or ce.holder_user_id=(select user_account_id from student where id=?)))
            ) as has_access,
          exists(select 1 from course_purchase cp
            where cp.institution_id=? and cp.student_id=? and qe_same_logical_course(cp.course_id,p.id)) as purchased,
          exists(select 1 from student_login_credential c
            where c.student_id=? and c.active=true) as parent_managed,
          parent_request.status as parent_request_status,
          parent_request.requested_at as parent_request_requested_at
        from institution_marketplace_offer o
        join institution_course_assignment assignment
          on assignment.id=o.assignment_id and assignment.institution_id=o.institution_id
          and assignment.status='ASSIGNED'
        join institution_course_release release
          on release.id=o.course_release_id and release.program_id=o.course_id
        join institution_tenant tenant_scope
          on tenant_scope.id=o.institution_id and tenant_scope.status='ACTIVE'
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        left join institution_course_identity identity on identity.id=p.course_identity_id
        left join lateral (
          select r.status,r.requested_at
          from parent_course_purchase_request r
          where r.student_id=? and r.institution_id=o.institution_id and r.course_id=p.id
          order by r.requested_at desc,r.id desc limit 1
        ) parent_request on true
        where o.institution_id=? and o.status='PUBLISHED'
          and qe_marketplace_offer_publicly_sellable(o.id)
          and o.hrms_published_by_staff_id is not null
          and release.release_state='SHAREABLE'
          and course_marketplace_content_type_ready(release.program_id)
          and case when release.release_state='SHAREABLE' then course_release_fingerprint_current(release.program_id,release.content_fingerprint::text,release.updated_at) else false end
          and not exists (
            select 1
            from institution_marketplace_offer newer_offer
            join institution_course_assignment newer_assignment
              on newer_assignment.id=newer_offer.assignment_id
             and newer_assignment.institution_id=newer_offer.institution_id
             and newer_assignment.status='ASSIGNED'
            join institution_course_release newer_release
              on newer_release.id=newer_offer.course_release_id
             and newer_release.program_id=newer_offer.course_id
            join curriculum_program newer_program
              on newer_program.id=newer_release.program_id
             and newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'
             and newer_program.course_identity_id=p.course_identity_id
             and (
               newer_program.content_version_no>p.content_version_no
               or (newer_program.content_version_no=p.content_version_no and (
                 newer_release.release_version>release.release_version
                 or (newer_release.release_version=release.release_version and newer_release.id>release.id)
               ))
             )
            where newer_offer.institution_id=o.institution_id
              and newer_offer.status='PUBLISHED'
              and newer_offer.hrms_published_by_staff_id is not null
              and newer_release.release_state='SHAREABLE'
              and course_marketplace_content_type_ready(newer_release.program_id)
              and case when newer_release.release_state='SHAREABLE' then course_release_fingerprint_current(newer_release.program_id,newer_release.content_fingerprint::text,newer_release.updated_at) else false end
          )
        order by p.display_name,p.id
        """, institutionId, context.studentId(),
        institutionId, context.studentId(), context.studentId(),
        institutionId, context.studentId(),
        context.studentId(), context.studentId(), institutionId);
    for (Map<String, Object> offer : offers) {
      long courseId = ((Number) offer.get("course_id")).longValue();
      offer.put("subjects", jdbc.queryForList("""
          select s.code as subject_code, s.display_name as subject_name,
            count(distinct ch.id) as chapters, count(distinct l.id) as lessons
          from curriculum_program_subject ps
          join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
          join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
            and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
          left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
            and ch.content_status='PUBLISHED'
          left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
          where ps.program_id=?
          group by s.id,s.code,s.display_name,s.sort_order
          order by s.sort_order,s.id
          """, courseId));
      offer.put("is_enrolled", Boolean.TRUE.equals(offer.get("has_access")));
    }
    return offers;
  }

  /** Published offers for the parent workspace; these are not inferred from curriculum rows. */
  @GetMapping("/family/courses")
  public List<Map<String,Object>> familyCourses(
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    long parentId = context.userId();
    List<Map<String,Object>> offers = jdbc.queryForList("""
      with selected_tenant as (select ?::bigint as institution_id)
      select p.id as course_id,p.public_id,coalesce(identity.stable_course_key,p.code) as course_code,
         identity.public_id as course_identity_public_id,
        coalesce(nullif(o.title_override,''),p.display_name) as title,
        p.display_name as course_name,p.curriculum_version,p.course_type_code,
        (select count(*) from course_assessment_pack pack
          where pack.course_id=p.id and pack.status='PUBLISHED') as test_pack_count,
        (select count(*) from assessment a
          where a.course_id=p.id and a.status='PUBLISHED') as assessment_count,
        (select count(*) from assessment_question aq join assessment a on a.id=aq.assessment_id
          where a.course_id=p.id and a.status='PUBLISHED') as assessment_question_count,
        o.description,o.price_paise,o.currency,o.status as offer_status,
        (exists(select 1 from course_entitlement ce
          where ce.institution_id=(select institution_id from selected_tenant)
            and qe_same_logical_course(ce.course_id,p.id) and ce.holder_user_id=?)
         or exists(select 1 from student st
          join institution_membership m on m.student_id=st.id
            and m.institution_id=(select institution_id from selected_tenant) and m.status='ACTIVE'
          join student_program_enrollment e on e.student_id=st.id and e.institution_id=m.institution_id
            and qe_same_logical_course(e.program_id,p.id) and e.status in ('ACTIVE','PAUSED')
          where st.user_account_id=?)) as owned_by_parent,
        exists(select 1 from course_entitlement ce
          where ce.institution_id=(select institution_id from selected_tenant)
            and qe_same_logical_course(ce.course_id,p.id) and ce.holder_user_id=? and ce.transfer_count=0) as transferable_by_parent,
        (exists(select 1 from course_entitlement ce
          join guardian_student gs on gs.student_id=ce.holder_student_id
          join institution_membership m on m.student_id=gs.student_id
            and m.institution_id=ce.institution_id and m.status='ACTIVE'
          where ce.institution_id=(select institution_id from selected_tenant)
            and qe_same_logical_course(ce.course_id,p.id) and gs.guardian_user_id=?
            and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL')
         or exists(select 1 from guardian_student gs
          join institution_membership m on m.student_id=gs.student_id
            and m.institution_id=(select institution_id from selected_tenant) and m.status='ACTIVE'
          join student_program_enrollment e on e.student_id=gs.student_id
            and e.institution_id=m.institution_id and qe_same_logical_course(e.program_id,p.id) and e.status in ('ACTIVE','PAUSED')
          where gs.guardian_user_id=? and gs.active=true
            and gs.consent_status='CONSENTED' and gs.access_level='FULL')) as owned_by_child,
        (select coalesce(string_agg(distinct gs.student_id::text, ','), '')
         from guardian_student gs
         join institution_membership m on m.student_id=gs.student_id
           and m.institution_id=(select institution_id from selected_tenant) and m.status='ACTIVE'
         where gs.guardian_user_id=? and gs.active=true
           and gs.consent_status='CONSENTED' and gs.access_level='FULL'
           and (exists(select 1 from course_entitlement ce
                 where ce.institution_id=m.institution_id and qe_same_logical_course(ce.course_id,p.id)
                   and ce.holder_student_id=gs.student_id)
             or exists(select 1 from student_program_enrollment e
                 where e.student_id=gs.student_id and e.institution_id=m.institution_id
                   and qe_same_logical_course(e.program_id,p.id) and e.status in ('ACTIVE','PAUSED'))
           )) as owned_child_ids
      from institution_marketplace_offer o
      join institution_course_assignment assignment
        on assignment.id=o.assignment_id and assignment.institution_id=o.institution_id
        and assignment.status='ASSIGNED'
      join institution_course_release release
        on release.id=o.course_release_id and release.program_id=o.course_id
      join institution_tenant tenant_scope on tenant_scope.id=o.institution_id and tenant_scope.status='ACTIVE'
      join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
      left join institution_course_identity identity on identity.id=p.course_identity_id
      where o.institution_id=(select institution_id from selected_tenant) and o.status='PUBLISHED'
        and qe_marketplace_offer_publicly_sellable(o.id)
        and o.hrms_published_by_staff_id is not null
        and release.release_state='SHAREABLE'
        and course_marketplace_content_type_ready(release.program_id)
        and case when release.release_state='SHAREABLE' then course_release_fingerprint_current(release.program_id,release.content_fingerprint::text,release.updated_at) else false end
        and not exists (
          select 1
          from institution_marketplace_offer newer_offer
          join institution_course_assignment newer_assignment
            on newer_assignment.id=newer_offer.assignment_id
           and newer_assignment.institution_id=newer_offer.institution_id
           and newer_assignment.status='ASSIGNED'
          join institution_course_release newer_release
            on newer_release.id=newer_offer.course_release_id
           and newer_release.program_id=newer_offer.course_id
          join curriculum_program newer_program
            on newer_program.id=newer_release.program_id
           and newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'
           and newer_program.course_identity_id=p.course_identity_id
             and (
               newer_program.content_version_no>p.content_version_no
               or (newer_program.content_version_no=p.content_version_no and (
                 newer_release.release_version>release.release_version
                 or (newer_release.release_version=release.release_version and newer_release.id>release.id)
               ))
             )
          where newer_offer.institution_id=o.institution_id
            and newer_offer.status='PUBLISHED'
            and newer_offer.hrms_published_by_staff_id is not null
            and newer_release.release_state='SHAREABLE'
            and course_marketplace_content_type_ready(newer_release.program_id)
            and case when newer_release.release_state='SHAREABLE' then course_release_fingerprint_current(newer_release.program_id,newer_release.content_fingerprint::text,newer_release.updated_at) else false end
        )
      order by p.display_name,p.id
      """, institutionId, parentId, parentId, parentId, parentId, parentId, parentId);
    for (Map<String,Object> offer : offers) {
      long courseId = ((Number) offer.get("course_id")).longValue();
      offer.put("subjects", jdbc.queryForList("""
        select s.code as subject_code,s.display_name as subject_name,
          count(distinct ch.id) as chapters,count(distinct l.id) as lessons
        from curriculum_program_subject ps
        join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
        left join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
          and ch.content_status='PUBLISHED'
        left join lesson l on l.chapter_id=ch.id and l.active=true and l.status='PUBLISHED'
        where ps.program_id=?
        group by s.id,s.code,s.display_name,s.sort_order
        order by s.sort_order,s.id
        """, courseId));
    }
    return offers;
  }

  @PostMapping("/family/courses/{courseId}/enroll-free")
  @Transactional
  public Map<String,Object> enrollFreeFamilyCourse(
      @PathVariable long courseId,
      @RequestBody(required=false) FamilyCourseOrderRequest request,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    String recipient = request == null || request.recipientType() == null
        ? "" : request.recipientType().trim().toUpperCase(Locale.ROOT);
    long studentId;
    if ("SELF".equals(recipient)) {
      studentId = ensurePersonalLearningProfile(context.userId());
    } else if ("CHILD".equals(recipient)) {
      if (request.studentId() == null || request.studentId() <= 0) {
        throw new IllegalArgumentException("Choose a linked child before adding this course.");
      }
      studentId = request.studentId();
      requireFullGuardianChildAccess(context.userId(),studentId,institutionId);
    } else {
      throw new IllegalArgumentException("Choose whether this course is for you or a linked child.");
    }

    Boolean freeAndPublished = jdbc.queryForObject("""
      select exists(
        select 1 from institution_marketplace_offer o
        join institution_course_assignment a
          on a.id=o.assignment_id and a.institution_id=o.institution_id and a.status='ASSIGNED'
        join institution_course_release r on r.id=o.course_release_id and r.program_id=o.course_id
        join institution_tenant t on t.id=o.institution_id and t.status='ACTIVE'
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where o.institution_id=? and o.course_id=? and o.status='PUBLISHED'
          and qe_marketplace_offer_publicly_sellable(o.id)
          and o.hrms_published_by_staff_id is not null
          and r.release_state='SHAREABLE'
          and course_marketplace_content_type_ready(r.program_id)
          and case when r.release_state='SHAREABLE' then course_release_fingerprint_current(r.program_id,r.content_fingerprint::text,r.updated_at) else false end
          and not exists (
            select 1
            from institution_marketplace_offer newer_offer
            join institution_course_assignment newer_assignment
              on newer_assignment.id=newer_offer.assignment_id
             and newer_assignment.institution_id=newer_offer.institution_id
             and newer_assignment.status='ASSIGNED'
            join institution_course_release newer_release
              on newer_release.id=newer_offer.course_release_id
             and newer_release.program_id=newer_offer.course_id
            join curriculum_program newer_program
              on newer_program.id=newer_release.program_id
             and newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'
             and newer_program.course_identity_id=p.course_identity_id
             and (
               newer_program.content_version_no>p.content_version_no
               or (newer_program.content_version_no=p.content_version_no and (
                 newer_release.release_version>release.release_version
                 or (newer_release.release_version=release.release_version and newer_release.id>release.id)
               ))
             )
            where newer_offer.institution_id=o.institution_id
              and newer_offer.status='PUBLISHED'
              and newer_offer.hrms_published_by_staff_id is not null
              and newer_release.release_state='SHAREABLE'
              and course_marketplace_content_type_ready(newer_release.program_id)
              and case when newer_release.release_state='SHAREABLE' then course_release_fingerprint_current(newer_release.program_id,newer_release.content_fingerprint::text,newer_release.updated_at) else false end
          )
          and o.price_paise=0 and o.currency='INR'
      )
      """, Boolean.class, institutionId, courseId);
    if (!Boolean.TRUE.equals(freeAndPublished)) {
      throw new IllegalArgumentException("This course is not currently available as a free offer in this institution.");
    }
    if (hasAccessInInstitution(studentId, courseId, institutionId)) {
      throw new IllegalArgumentException("The selected learner already has access to this course in this institution.");
    }
    if ("SELF".equals(recipient)) {
      ensureCourseMembership(studentId,institutionId,context.studentId(),context.userId(),
          "PERSONAL_LEARNER_PROFILE",courseId);
    } else {
      requireStudentMembership(studentId,institutionId);
    }

    List<Map<String,Object>> subjects = jdbc.queryForList("""
      select s.id from curriculum_program_subject ps
      join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """, courseId);
    if (subjects.isEmpty()) throw new IllegalStateException("This course has no active subject tracks yet.");

    // A new course enrollment is additive; focused-course selection is independent of enrollment.
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',now(),null,now(),now())
      on conflict(institution_id,student_id,program_id) do update set
        status='ACTIVE',enrolled_at=case when student_program_enrollment.status='ACTIVE'
          then student_program_enrollment.enrolled_at else now() end,
        ended_at=null,updated_at=now()
      """, studentId, courseId, institutionId);
    jdbc.update("""
      insert into student_track_enrollment(student_id,subject_id,institution_id,status)
      select ?,s.id,?,'ACTIVE'
      from curriculum_program_subject ps
      join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      where ps.program_id=?
      on conflict(institution_id,student_id,subject_id) do update set status='ACTIVE',updated_at=now()
      """, studentId, institutionId, courseId);
    if ("CHILD".equals(recipient)) {
      jdbc.update("""
        update parent_course_purchase_request
        set status='PURCHASED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
        where guardian_user_id=? and student_id=? and institution_id=? and course_id=? and status='PENDING'
        """, context.userId(), context.userId(), studentId, institutionId, courseId);
    }
    return Map.of("enrolled", true, "studentId", studentId, "courseId", courseId,
        "institutionId", institutionId, "recipientType", recipient, "free", true);
  }

  @GetMapping("/family/entitlements")
  public List<Map<String,Object>> familyEntitlements(
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    return jdbc.queryForList("""
      select ce.id as entitlement_id,ce.course_id,ce.source_purchase_id,
        ce.holder_student_id,st.display_name as current_learner_name,
        ce.transfer_count,ce.created_at,p.public_id as course_public_id,
        coalesce(nullif(o.title_override,''),p.display_name) as course_name
      from course_entitlement ce
      join curriculum_program p on p.id=ce.course_id
      left join institution_marketplace_offer o on o.institution_id=ce.institution_id
        and o.course_id=p.id and o.status='PUBLISHED'
      left join student st on st.id=ce.holder_student_id
      join institution_tenant t on t.id=ce.institution_id and t.status='ACTIVE'
      where ce.holder_user_id=? and ce.institution_id=? and ce.transfer_count=0
        and p.status='ACTIVE' and p.code<>'GENERAL'
        and exists(select 1 from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id and r.program_id=ce.course_id
          where a.institution_id=ce.institution_id and a.status='ASSIGNED')
      order by ce.created_at desc,ce.id desc
      """, context.userId(), institutionId);
  }

  @PostMapping("/family/entitlements/{entitlementId}/transfer")
  @Transactional
  public Map<String,Object> transferEntitlement(
      @PathVariable long entitlementId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context,
      @RequestAttribute(value=InstitutionTenantContextService.REQUEST_ATTRIBUTE, required=false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    Object raw = body == null ? null : body.get("studentId");
    if (!(raw instanceof Number number) || number.longValue() <= 0) {
      throw new IllegalArgumentException("Choose a linked child to receive this course.");
    }
    long targetStudentId = number.longValue();
    long parentId = context.userId();
    requireFullGuardianChildAccess(parentId,targetStudentId,institutionId);
    Boolean targetMember = jdbc.queryForObject("""
      select exists(select 1 from institution_membership m
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join student st on st.id=m.student_id and st.active=true
        where m.institution_id=? and m.student_id=? and m.status='ACTIVE')
      """, Boolean.class, institutionId, targetStudentId);
    if (!Boolean.TRUE.equals(targetMember)) {
      throw new SecurityException("The receiving learner is not an active member of this institution.");
    }

    // Use the same lock order as refund preparation: payment order first, entitlement second.
    // This serializes a permanent transfer against reservation/settlement of a course refund.
    List<Map<String,Object>> sourceRows=jdbc.queryForList("""
      select ce.source_purchase_id,cp.order_id
      from course_entitlement ce
      left join course_purchase cp on cp.id=ce.source_purchase_id
      where ce.id=? and ce.institution_id=? and ce.holder_user_id=? and ce.transfer_count=0
      """,entitlementId,institutionId,parentId);
    if(sourceRows.isEmpty())
      throw new IllegalArgumentException("This institution-scoped course is not owned by your account or has already been transferred.");
    Map<String,Object> source=sourceRows.getFirst();
    if(source.get("source_purchase_id")==null||source.get("order_id")==null)
      throw new IllegalStateException("This entitlement has no purchase order link and cannot be transferred safely.");
    long sourcePurchaseId=((Number)source.get("source_purchase_id")).longValue();
    long paymentOrderId=((Number)source.get("order_id")).longValue();
    List<Map<String,Object>> orderLock=jdbc.queryForList(
        "select id from course_payment_order where id=? for update",paymentOrderId);
    if(orderLock.isEmpty())throw new IllegalStateException("The source course payment order no longer exists.");

    List<Map<String,Object>> rows = jdbc.queryForList("""
      select ce.id,ce.course_id,ce.source_purchase_id,ce.holder_student_id,
        ce.transfer_count,p.display_name as course_name
      from course_entitlement ce
      join curriculum_program p on p.id=ce.course_id and p.status='ACTIVE'
      join institution_tenant t on t.id=ce.institution_id and t.status='ACTIVE'
      where ce.id=? and ce.institution_id=? and ce.holder_user_id=? and ce.transfer_count=0
        and exists(select 1 from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id and r.program_id=ce.course_id
          where a.institution_id=ce.institution_id and a.status='ASSIGNED')
      for update of ce
      """, entitlementId, institutionId, parentId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("This institution-scoped course is not owned by your account or has already been transferred.");
    }
    Map<String,Object> entitlement = rows.getFirst();
    if (entitlement.get("source_purchase_id") == null) {
      throw new IllegalStateException("This entitlement has no purchase ledger record and cannot be transferred safely.");
    }
    long courseId = ((Number) entitlement.get("course_id")).longValue();
    long purchaseId = ((Number) entitlement.get("source_purchase_id")).longValue();
    if(purchaseId!=sourcePurchaseId)
      throw new IllegalStateException("The entitlement purchase link changed while transfer was being prepared.");
    Integer refundCount=jdbc.queryForObject("""
      select count(*) from course_payment_refund
      where course_payment_order_id=?
        and status in ('PROCESSING','PENDING','RECONCILIATION_REQUIRED','PROCESSED')
      """,Integer.class,paymentOrderId);
    if(refundCount!=null&&refundCount>0)
      throw new IllegalStateException("This purchase has an active or processed refund. Permanent transfer is blocked to avoid transferring refunded access.");
    Long currentStudentId = entitlement.get("holder_student_id") instanceof Number n ? n.longValue() : null;
    if (currentStudentId != null && currentStudentId == targetStudentId) {
      throw new IllegalArgumentException("This course is already assigned to that learner.");
    }
    if (hasAccessInInstitution(targetStudentId, courseId, institutionId)) {
      throw new IllegalArgumentException("That learner already has access to this course in this institution. No transfer was made.");
    }

    try {
      int purchaseChanged = jdbc.update("""
        update course_purchase set student_id=?
        where id=? and institution_id=? and course_id=?
          and (student_id=? or student_id is null)
        """, targetStudentId, purchaseId, institutionId, courseId, currentStudentId);
      if (purchaseChanged != 1) {
        throw new IllegalStateException("The purchase ledger no longer matches this institution-scoped entitlement.");
      }
    } catch (org.springframework.dao.DuplicateKeyException ex) {
      throw new IllegalArgumentException("That child already has a purchase for this course in this institution. No transfer was made.", ex);
    }
    int changed = jdbc.update("""
      update course_entitlement
      set holder_user_id=null,holder_student_id=?,transfer_count=1,
          transferred_at=now(),updated_at=now()
      where id=? and institution_id=? and holder_user_id=? and transfer_count=0
      """, targetStudentId, entitlementId, institutionId, parentId);
    if (changed != 1) throw new IllegalStateException("The entitlement changed while transferring. Reload and try again.");

    if (currentStudentId != null) {
      jdbc.update("""
        update student_program_enrollment
        set status='ENDED',ended_at=now(),updated_at=now()
        where student_id=? and institution_id=? and program_id=? and status in ('ACTIVE','PAUSED')
        """, currentStudentId, institutionId, courseId);
    }
    // Purchase/transfer activates access to this course without changing other enrollments.
    jdbc.update("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at
      ) values(?,?,?,'ACTIVE',now(),null,now(),now())
      on conflict(institution_id,student_id,program_id) do update set
        status='ACTIVE',enrolled_at=case when student_program_enrollment.status='ACTIVE'
          then student_program_enrollment.enrolled_at else now() end,
        ended_at=null,updated_at=now()
      """, targetStudentId, courseId, institutionId);
    jdbc.update("""
      insert into student_track_enrollment(student_id,subject_id,institution_id,status)
      select ?,s.id,?,'ACTIVE'
      from curriculum_program_subject ps
      join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
      where ps.program_id=?
      on conflict(institution_id,student_id,subject_id) do update set status='ACTIVE',updated_at=now()
      """, targetStudentId, institutionId, courseId);
    jdbc.update("""
      insert into course_entitlement_transfer_audit(
        institution_id,entitlement_id,course_id,from_user_id,to_student_id,transfer_number
      ) values(?,?,?,?,?,1)
      """, institutionId, entitlementId, courseId, parentId, targetStudentId);

    return Map.of("transferred", true, "permanent", true, "revocable", false,
        "entitlementId", entitlementId, "courseId", courseId, "institutionId", institutionId,
        "studentId", targetStudentId);
  }

  @PostMapping("/courses/{courseId}/orders")
  public Map<String, Object> createOrder(
      @PathVariable long courseId,
      @RequestHeader(value = "Idempotency-Key", required = false) String suppliedIdempotencyKey,
      @RequestBody(required = false) CourseOrderRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    long institutionId = requireTenant(tenant).institutionId();
    String key = normalizeIdempotencyKey(suppliedIdempotencyKey);
    return createOrderInternal(courseId, key, context.studentId(), null, null, "STUDENT", institutionId,
        request==null?null:request.discountCode(), request==null?null:request.billingAddressId());
  }

  /** Parents can buy for their own learning profile or directly for a linked child. */
  @PostMapping("/family/courses/{courseId}/orders")
  public Map<String, Object> createFamilyOrder(
      @PathVariable long courseId,
      @RequestHeader(value = "Idempotency-Key", required = false) String suppliedIdempotencyKey,
      @RequestBody(required = false) FamilyCourseOrderRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    long institutionId = requireTenant(tenant).institutionId();
    String key = normalizeIdempotencyKey(suppliedIdempotencyKey);
    String recipient = request == null || request.recipientType() == null
        ? "" : request.recipientType().trim().toUpperCase(Locale.ROOT);
    long targetStudentId;
    String orderRecipientType;
    if ("SELF".equals(recipient)) {
      targetStudentId = ensurePersonalLearningProfile(context.userId());
      ensureCourseMembership(targetStudentId,institutionId,context.studentId(),context.userId(),
          "PERSONAL_LEARNER_PROFILE",courseId);
      // STUDENT recipient means a learner profile is being funded. purchaser_user_id
      // separately retains the parent's ownership so it can be transferred later.
      orderRecipientType = "STUDENT";
    } else if ("CHILD".equals(recipient)) {
      if (request.studentId() == null || request.studentId() <= 0) {
        throw new IllegalArgumentException("Choose a linked child before purchasing this course.");
      }
      targetStudentId = request.studentId();
      requireFullGuardianChildAccess(context.userId(),targetStudentId,institutionId);
      orderRecipientType = "CHILD";
    } else {
      throw new IllegalArgumentException("Choose whether this course is for you or a linked child.");
    }
    return createOrderInternal(courseId, key, targetStudentId, context.userId(),
        targetStudentId, orderRecipientType, institutionId,request.discountCode(),request.billingAddressId());
  }

  static String normalizeDiscountCodeForRequest(String supplied) {
    if(supplied==null||supplied.isBlank()) return null;
    String code=supplied.trim().toUpperCase(Locale.ROOT);
    if(!code.matches("[A-Z0-9][A-Z0-9_-]{2,39}"))
      throw new IllegalArgumentException("Offer codes must be 3–40 letters, digits, hyphens or underscores.");
    return code;
  }

  static boolean discountCodeMatchesForRetry(String lockedCode,String requestedCode) {
    return java.util.Objects.equals(
        normalizeDiscountCodeForRequest(lockedCode),
        normalizeDiscountCodeForRequest(requestedCode));
  }

  private String normalizeIdempotencyKey(String supplied) {
    String key = supplied == null ? "" : supplied.trim();
    if (!isValidIdempotencyKey(key)) {
      throw new IllegalArgumentException("Checkout could not be identified safely. Please retry from the course marketplace.");
    }
    return key;
  }

  private long ensurePersonalLearningProfile(long parentUserId) {
    List<Map<String,Object>> existing = jdbc.queryForList("""
      select st.id
      from user_account u
      join student st on st.user_account_id=u.id and st.active=true
        and st.environment='PRODUCTION'
      where u.id=? and u.role='PARENT' and u.active=true
      for update of u,st
      """, parentUserId);
    if (!existing.isEmpty()) return ((Number) existing.getFirst().get("id")).longValue();
    List<Map<String,Object>> accounts = jdbc.queryForList("""
      select id,display_name from user_account
      where id=? and role='PARENT' and active=true
      for update
      """, parentUserId);
    if (accounts.isEmpty()) throw new SecurityException("An active parent account is required.");
    String displayName = String.valueOf(accounts.getFirst().get("display_name"));
    Long studentId = jdbc.queryForObject("""
      insert into student(
        public_id,display_name,class_code,board,language,environment,active,user_account_id
      ) values (?,?,'6','Bihar Board','hi','PRODUCTION',true,?)
      returning id
      """, Long.class, UUID.randomUUID(), displayName, parentUserId);
    return studentId;
  }

  private void requireFullGuardianChildAccess(long parentUserId,long studentId,long institutionId) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(
        select 1 from guardian_student gs
        join student st on st.id=gs.student_id
        join institution_membership m on m.student_id=st.id and m.institution_id=? and m.status='ACTIVE'
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        where gs.guardian_user_id=? and gs.student_id=?
          and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL'
          and st.active=true and st.environment='PRODUCTION'
      )
      """,Boolean.class,institutionId,parentUserId,studentId);
    if(!Boolean.TRUE.equals(allowed)) throw new SecurityException(
        "Full, consented parent access and active learner membership in the selected institution are required.");
  }

  private void requireStudentMembership(long studentId,long institutionId) {
    Boolean allowed=jdbc.queryForObject("""
      select exists(select 1 from institution_membership m
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join student st on st.id=m.student_id and st.active=true and st.environment='PRODUCTION'
        where m.student_id=? and m.institution_id=? and m.status='ACTIVE')
      """,Boolean.class,studentId,institutionId);
    if(!Boolean.TRUE.equals(allowed)) throw new SecurityException(
        "The selected learner is not an active member of this institution.");
  }

  private Map<String, Object> createOrderInternal(
      long courseId, String idempotencyKey, long targetStudentId,
      Long purchaserUserId, Long targetStudentForOrder, String recipientType, long institutionId,
      String requestedDiscountCode, Long requestedBillingAddressId) {
    requireConfigured();
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select p.id, p.course_identity_id, identity.public_id as course_identity_public_id,
          p.content_version_no, o.id as institution_offer_id,
          coalesce(nullif(o.title_override,''),p.display_name) as title,
          o.price_paise, o.currency, o.description
        from institution_marketplace_offer o
        join institution_course_assignment assignment
          on assignment.id=o.assignment_id and assignment.institution_id=o.institution_id
          and assignment.status='ASSIGNED'
        join institution_course_release release
          on release.id=o.course_release_id and release.program_id=o.course_id
        join institution_tenant tenant_scope
          on tenant_scope.id=o.institution_id and tenant_scope.status='ACTIVE'
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        join institution_course_identity identity on identity.id=p.course_identity_id
          and identity.status='ACTIVE' and identity.source_institution_id=release.source_institution_id
          and identity.course_type_code=p.course_type_code
        join student target_student on target_student.id=? and target_student.active=true
          and target_student.environment='PRODUCTION'
        join institution_membership member
          on member.student_id=target_student.id and member.institution_id=o.institution_id and member.status='ACTIVE'
        where o.institution_id=? and p.id=? and o.status='PUBLISHED'
          and qe_marketplace_offer_publicly_sellable(o.id)
          and o.hrms_published_by_staff_id is not null
          and release.release_state='SHAREABLE'
          and course_marketplace_content_type_ready(release.program_id)
          and case when release.release_state='SHAREABLE' then course_release_fingerprint_current(release.program_id,release.content_fingerprint::text,release.updated_at) else false end
          and not exists (
            select 1
            from institution_marketplace_offer newer_offer
            join institution_course_assignment newer_assignment
              on newer_assignment.id=newer_offer.assignment_id
             and newer_assignment.institution_id=newer_offer.institution_id
             and newer_assignment.status='ASSIGNED'
            join institution_course_release newer_release
              on newer_release.id=newer_offer.course_release_id
             and newer_release.program_id=newer_offer.course_id
            join curriculum_program newer_program
              on newer_program.id=newer_release.program_id
             and newer_program.status='ACTIVE' and newer_program.code<>'GENERAL'
             and newer_program.course_identity_id=p.course_identity_id
             and (
               newer_program.content_version_no>p.content_version_no
               or (newer_program.content_version_no=p.content_version_no and (
                 newer_release.release_version>release.release_version
                 or (newer_release.release_version=release.release_version and newer_release.id>release.id)
               ))
             )
            where newer_offer.institution_id=o.institution_id
              and newer_offer.status='PUBLISHED'
              and newer_offer.hrms_published_by_staff_id is not null
              and newer_release.release_state='SHAREABLE'
              and course_marketplace_content_type_ready(newer_release.program_id)
              and case when newer_release.release_state='SHAREABLE' then course_release_fingerprint_current(newer_release.program_id,newer_release.content_fingerprint::text,newer_release.updated_at) else false end
          )
          and o.price_paise>0
        """, targetStudentId,institutionId,courseId);
    if (rows.isEmpty()) throw new IllegalArgumentException("This paid course is not currently available to the selected institution.");
    // Course and history ownership are keyed by institution; another tenant's records are independent.
    if (hasAccessInInstitution(targetStudentId, courseId, institutionId)) {
      throw new IllegalArgumentException("The selected learner already has access to this course.");
    }
    if (purchaserUserId != null) {
      Boolean alreadyOwnedByParent = jdbc.queryForObject("""
        select exists(select 1 from course_entitlement entitlement
          where entitlement.holder_user_id=? and entitlement.institution_id=?
            and qe_same_logical_course(entitlement.course_id,?))
        """, Boolean.class, purchaserUserId, institutionId, courseId);
      if (Boolean.TRUE.equals(alreadyOwnedByParent)) {
        throw new IllegalArgumentException("You already own this course. Open your courses or transfer it to a linked child.");
      }
    }
    Map<String, Object> offer = rows.getFirst();
    Object rawIdentityId=offer.get("course_identity_id");
    if(!(rawIdentityId instanceof Number))
      throw new IllegalStateException("This course has no canonical logical identity; checkout is blocked pending source-owner reconciliation.");
    long courseIdentityId=((Number)rawIdentityId).longValue();
    long listedPricePaise = ((Number) offer.get("price_paise")).longValue();
    long amountPaise = listedPricePaise;
    String currency = String.valueOf(offer.get("currency")).toUpperCase(Locale.ROOT);
    String title = String.valueOf(offer.getOrDefault("title", "Course access"));
    long purchaserAccountId;
    if(purchaserUserId!=null) {
      purchaserAccountId=purchaserUserId;
    } else {
      Long linkedUserId=jdbc.queryForObject("select user_account_id from student where id=? and active=true",Long.class,targetStudentId);
      if(linkedUserId==null) throw new IllegalStateException("The purchaser account is not linked to the learner profile.");
      purchaserAccountId=linkedUserId;
    }
    Map<String,Object> buyerSnapshot=billingProfileSnapshot(purchaserAccountId,targetStudentId,requestedBillingAddressId);
    String institutionDisplayName=jdbc.queryForObject("""
      select coalesce(nullif(b.display_name,''),t.display_name)
      from institution_tenant t left join institution_branding b on b.institution_id=t.id
      where t.id=? and t.status='ACTIVE'
      """,String.class,institutionId);
    if(institutionDisplayName==null || institutionDisplayName.isBlank())
      throw new IllegalStateException("Institution payment branding is not configured.");

    List<Map<String, Object>> keyedOrders = jdbc.queryForList("""
        select id,institution_offer_id,course_identity_id,identity_contract_version,offer_price_paise,currency,provider_order_id,status,tax_profile_id,revenue_share_policy_id,
          listed_price_paise,discount_offer_id,discount_amount_paise,discount_reference,
          discount_snapshot::text as discount_snapshot_json,
          tax_calculation_snapshot::text as tax_calculation_snapshot_json,
          revenue_share_policy_snapshot::text as revenue_share_policy_snapshot_json,
          buyer_snapshot::text as buyer_snapshot_json
        from course_payment_order
        where student_id=? and institution_id=? and course_id=? and client_request_key=?
          and purchaser_user_id is not distinct from ?
        """, targetStudentId, institutionId, courseId, idempotencyKey, purchaserUserId);
    if (!keyedOrders.isEmpty()) {
      Map<String, Object> previous = keyedOrders.getFirst();
      String previousCode=previous.get("discount_reference")==null?null:String.valueOf(previous.get("discount_reference"));
      String requestedCode=normalizeDiscountCodeForRequest(requestedDiscountCode);
      if(!java.util.Objects.equals(previousCode,requestedCode))
        throw new IllegalArgumentException("This idempotency key was already used with a different offer code. Start a new checkout request.");
      String previousStatus = String.valueOf(previous.get("status"));
      if ("PENDING".equals(previousStatus)) {
        long currentOfferId=((Number)offer.get("institution_offer_id")).longValue();
        Object savedOfferRaw=previous.get("institution_offer_id");
        if(!(savedOfferRaw instanceof Number n) || n.longValue()!=currentOfferId) {
          expireSupersededUnpaidOrder(previous,institutionId,targetStudentId);
          throw new IllegalStateException("The previous checkout was confirmed unpaid and expired. Start a fresh checkout for the current offer.");
        }
        requireSameBillingAddressChoice(previous,buyerSnapshot);
        if (!hasLockedFinancialSnapshots(previous)) {
          repairLegacyPendingOrderSnapshots(previous,institutionId,
              ((Number)offer.get("institution_offer_id")).longValue(),listedPricePaise,currency,
              title,buyerSnapshot,requestedDiscountCode);
        }
        return checkoutResponse(previous, courseId, title, institutionDisplayName);
      } else if ("CREATING".equals(previousStatus)) {
        throw new IllegalStateException("Your course checkout is being prepared. Please retry shortly.");
      } else {
        throw new IllegalArgumentException("This checkout request has already ended. Start a new checkout from the marketplace.");
      }
    }

    List<Map<String, Object>> open = openOrders(targetStudentId, institutionId, courseIdentityId, courseId);
    if (open.size() > 1) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "More than one pending checkout exists for this logical course. Do not pay again; HRMS must reconcile the saved orders.");
    }
    if (!open.isEmpty()) {
      Map<String, Object> prior = open.getFirst();
      Object priorOfferRaw=prior.get("institution_offer_id");
      long currentOfferId=((Number)offer.get("institution_offer_id")).longValue();
      boolean sameOffer=priorOfferRaw instanceof Number n && n.longValue()==currentOfferId;
      String priorStatus=String.valueOf(prior.get("status"));
      Object priorPayerRaw=prior.get("purchaser_user_id");
      boolean samePayer=priorPayerRaw==null ? purchaserUserId==null
          : purchaserUserId!=null && priorPayerRaw instanceof Number n && n.longValue()==purchaserUserId.longValue();
      if (!sameOffer || !samePayer) {
        // Never return another payer's checkout or an old offer's price/order to this request.
        expireSupersededUnpaidOrder(prior,institutionId,targetStudentId);
      } else if ("PENDING".equals(priorStatus)) {
        requireSameBillingAddressChoice(prior,buyerSnapshot);
        if (!hasLockedFinancialSnapshots(prior)) {
          repairLegacyPendingOrderSnapshots(prior,institutionId,
              currentOfferId,listedPricePaise,currency,title,buyerSnapshot,requestedDiscountCode);
          return checkoutResponse(prior,courseId,title,institutionDisplayName);
        }
        String providerState = inspectProviderOrderForNewAttempt(prior);
        if ("PAID".equals(providerState)) {
          throw new IllegalStateException(
              "Razorpay reports that this course order is already paid. Retry verification for this same order; do not start another checkout.");
        }
        if (!"RETRYABLE".equals(providerState)) {
          throw new IllegalStateException(
              "The previous payment attempt is still being reconciled. Retry verification shortly; do not pay again.");
        }
        String previousCode=prior.get("discount_reference")==null?null:String.valueOf(prior.get("discount_reference"));
        if(!discountCodeMatchesForRetry(previousCode,requestedDiscountCode)) {
          throw new IllegalStateException(
              "A retryable checkout already exists with a different locked offer code. Finish or reconcile that checkout before switching codes; its amount cannot be changed.");
        }
        return checkoutResponse(prior,courseId,title,institutionDisplayName);
      } else {
        throw new IllegalStateException("Your course checkout is being prepared. Please retry shortly.");
      }
    }

    requireCompleteBillingSnapshot(buyerSnapshot);
    CourseOrderFinancialService.CheckoutSnapshot financialSnapshot =
        courseOrderFinancialService.prepareCheckout(
            ((Number)offer.get("institution_offer_id")).longValue(),
            institutionId,listedPricePaise,currency,title,buyerSnapshot,requestedDiscountCode);
    amountPaise=financialSnapshot.payablePaise();
    String receipt = "qe_" + UUID.randomUUID().toString().replace("-", "");
    String buyerSnapshotJson;
    String taxCalculationSnapshotJson;
    String revenueShareSnapshotJson;
    String discountSnapshotJson;
    try {
      buyerSnapshotJson = objectMapper.writeValueAsString(buyerSnapshot);
      taxCalculationSnapshotJson = objectMapper.writeValueAsString(financialSnapshot.taxCalculationSnapshot());
      revenueShareSnapshotJson = objectMapper.writeValueAsString(financialSnapshot.revenueSharePolicySnapshot());
      discountSnapshotJson = objectMapper.writeValueAsString(financialSnapshot.discountSnapshot());
    } catch (JsonProcessingException serializationError) {
      throw new IllegalStateException("Unable to serialize the purchaser and approved financial snapshots.", serializationError);
    }
    long localOrderId;
    try {
      localOrderId = jdbc.queryForObject("""
          insert into course_payment_order(
            student_id,purchaser_user_id,target_student_id,recipient_type,
            course_id,institution_id,institution_offer_id,course_identity_id,identity_contract_version,offer_price_paise,currency,
            discount_offer_id,listed_price_paise,discount_amount_paise,discount_reference,discount_snapshot,
            tax_profile_id,taxable_value_paise,output_tax_paise,tax_calculation_snapshot,
            revenue_share_policy_id,revenue_share_policy_snapshot,buyer_snapshot,
            receipt,client_request_key,status
          ) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?,?, 'CREATING')
          returning id
          """, Long.class, targetStudentId, purchaserUserId,
          purchaserUserId == null ? null : targetStudentForOrder, recipientType,
          courseId, institutionId, ((Number) offer.get("institution_offer_id")).longValue(),
          courseIdentityId, 1, amountPaise, currency, financialSnapshot.discountOfferId(),financialSnapshot.listedPricePaise(),
          financialSnapshot.discountPaise(),financialSnapshot.discountCode(),discountSnapshotJson,
          financialSnapshot.taxProfileId(),financialSnapshot.taxableValuePaise(),
          financialSnapshot.outputTaxPaise(),taxCalculationSnapshotJson,financialSnapshot.revenueSharePolicyId(),
          revenueShareSnapshotJson,buyerSnapshotJson,receipt,idempotencyKey);
    } catch (DuplicateKeyException conflict) {
      List<Map<String, Object>> racedOrders = openOrders(targetStudentId, institutionId, courseIdentityId, courseId);
      if (racedOrders.size() == 1 && "PENDING".equals(String.valueOf(racedOrders.getFirst().get("status")))) {
        Map<String,Object> racedOrder = racedOrders.getFirst();
        long currentOfferId=((Number)offer.get("institution_offer_id")).longValue();
        Object racedOfferRaw=racedOrder.get("institution_offer_id");
        Object racedPayerRaw=racedOrder.get("purchaser_user_id");
        boolean sameRacedPayer=racedPayerRaw==null ? purchaserUserId==null
            : purchaserUserId!=null && racedPayerRaw instanceof Number payer && payer.longValue()==purchaserUserId.longValue();
        if(!(racedOfferRaw instanceof Number n) || n.longValue()!=currentOfferId || !sameRacedPayer) {
          expireSupersededUnpaidOrder(racedOrder,institutionId,targetStudentId);
          throw new IllegalStateException("The earlier checkout was confirmed unpaid and expired. Start a fresh checkout for the current offer.");
        }
        requireSameBillingAddressChoice(racedOrder,buyerSnapshot);
        if (!hasLockedFinancialSnapshots(racedOrder)) {
          repairLegacyPendingOrderSnapshots(racedOrder,institutionId,
              currentOfferId,listedPricePaise,currency,title,buyerSnapshot,requestedDiscountCode);
        }
        return checkoutResponse(racedOrder, courseId, title, institutionDisplayName);
      }
      if (racedOrders.size() > 1) {
        throw new IllegalStateException("Multiple checkouts exist for this logical course. HRMS must reconcile them before another payment.");
      }
      if (!racedOrders.isEmpty()) {
        throw new IllegalStateException("Your course checkout is being prepared. Please retry shortly.", conflict);
      }
      throw new IllegalArgumentException("This checkout request has already been used or another checkout is open. Reload the marketplace and try again.", conflict);
    }

    if(financialSnapshot.discountOfferId()!=null) {
      try {
        courseOrderFinancialService.reserveDiscountRedemption(
            financialSnapshot.discountOfferId(),localOrderId,purchaserAccountId,
            ((Number)offer.get("institution_offer_id")).longValue(),
            financialSnapshot.discountCode(),financialSnapshot.listedPricePaise(),
            financialSnapshot.discountPaise());
      } catch(Exception reservationFailure) {
        jdbc.update("""
          update course_payment_order set status='FAILED',failure_code='DISCOUNT_SNAPSHOT_FAILED',updated_at=now()
          where id=? and status='CREATING'
          """,localOrderId);
        throw new IllegalStateException("The approved offer code could not be recorded safely. No Razorpay order was created.",reservationFailure);
      }
    }

    try {
      Map<String,String> notes = new LinkedHashMap<>();
      notes.put("quantaedge_order_id", Long.toString(localOrderId));
      notes.put("quantaedge_student_id", Long.toString(targetStudentId));
      notes.put("quantaedge_course_id", Long.toString(courseId));
      notes.put("quantaedge_institution_id", Long.toString(institutionId));
      notes.put("quantaedge_institution_offer_id", String.valueOf(offer.get("institution_offer_id")));
      if (purchaserUserId != null) notes.put("quantaedge_purchaser_user_id", Long.toString(purchaserUserId));
      String requestJson = objectMapper.writeValueAsString(Map.of(
          "amount", amountPaise,
          "currency", currency,
          "receipt", receipt,
          "notes", notes,
          "payment", Map.of(
              "capture", "automatic",
              "capture_options", Map.of(
                  "automatic_expiry_period", 12,
                  "manual_expiry_period", 7200,
                  "refund_speed", "optimum"))));
      JsonNode providerOrder = razorpayRequest("POST", "/orders", requestJson);
      String providerOrderId = providerOrder.path("id").asText("");
      long providerAmount = providerOrder.path("amount").asLong(-1);
      String providerCurrency = providerOrder.path("currency").asText("");
      if (!providerOrderId.matches("order_[A-Za-z0-9]+")
          || providerAmount != amountPaise || !currency.equalsIgnoreCase(providerCurrency)) {
        throw new IllegalStateException("Payment provider returned an order that did not match the course offer.");
      }
      int updated = jdbc.update("""
          update course_payment_order set provider_order_id=?,status='PENDING',updated_at=now()
          where id=? and student_id=? and institution_id=? and purchaser_user_id is not distinct from ? and status='CREATING'
          """, providerOrderId, localOrderId, targetStudentId, institutionId, purchaserUserId);
      if (updated != 1) {
        throw new IllegalStateException("This checkout expired while it was being prepared. Please start checkout again.");
      }
      Map<String,Object> checkout=checkoutResponse(localOrderId, providerOrderId, amountPaise, currency, courseId, title, institutionDisplayName);
      checkout.put("listedPricePaise",financialSnapshot.listedPricePaise());
      checkout.put("discountPaise",financialSnapshot.discountPaise());
      checkout.put("discountCode",financialSnapshot.discountCode());
      return checkout;
    } catch (Exception exception) {
      jdbc.update("""
          update course_payment_order set status='FAILED',failure_code='ORDER_CREATE_FAILED',updated_at=now()
          where id=? and status='CREATING'
          """, localOrderId);
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Payment could not be started. Please retry.", exception);
    }
  }

  private Map<String,Object> preserveLegacyBuyerSnapshot(
      Map<String,Object> order,Map<String,Object> currentSnapshot) {
    Map<String,Object> merged=new LinkedHashMap<>(currentSnapshot);
    Object raw=order==null?null:order.get("buyer_snapshot_json");
    if(raw==null||String.valueOf(raw).isBlank()||"{}".equals(String.valueOf(raw).trim())) return merged;
    try {
      Map<String,Object> prior=objectMapper.readValue(String.valueOf(raw),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
      // The saved order's customer and beneficiary details take precedence. Current address
      // book data fills only fields missing from a legacy snapshot; it never rewrites one.
      merged.putAll(prior);
      return merged;
    } catch(Exception ex) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The saved order's billing snapshot cannot be safely reconstructed. Leave this order unchanged and ask HRMS to reconcile it.",ex);
    }
  }

  private Map<String,Object> parseBillingAddressJson(Object raw) {
    if(raw==null) return Map.of();
    try {
      return objectMapper.readValue(String.valueOf(raw),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    } catch(Exception ex) {
      throw new IllegalStateException("The legacy purchaser billing address snapshot could not be read safely.",ex);
    }
  }

  private Map<String,Object> billingProfileSnapshot(long userAccountId,long beneficiaryStudentId,Long requestedBillingAddressId) {
    List<Map<String,Object>> rows=jdbc.queryForList("""
      select id,label,billing_type,legal_name,gstin,gst_state_code,
        billing_address::text as billing_address_json,contact_email,contact_phone,
        version,is_default
      from billing_customer_address
      where user_account_id=? and active=true
      order by is_default desc,id
      """,userAccountId);
    if(rows.isEmpty()) {
      if(requestedBillingAddressId!=null) {
        throw new IllegalArgumentException("Add a billing address in My Profile before paying for a course.");
      }
      // Older pending orders can predate the address book, including accounts that never
      // saved a profile. Permit this legacy snapshot only for order-recovery lookup below;
      // a new order is rejected unless it has an address-book ID and a complete postal address.
      List<Map<String,Object>> legacyRows=jdbc.queryForList("""
        select u.id,coalesce(nullif(p.legal_name,''),u.display_name) as legal_name,
          coalesce(p.billing_type,'INDIVIDUAL') as billing_type,p.gstin,p.gst_state_code,
          coalesce(p.billing_address,'{}'::jsonb)::text as billing_address_json,
          coalesce(p.contact_email,u.contact_email,u.email) as contact_email,
          coalesce(p.contact_phone,u.mobile_e164) as contact_phone,
          coalesce(p.version,0) as profile_version,u.display_name
        from user_account u left join billing_customer_profile p on p.user_account_id=u.id
        where u.id=? and u.active=true
        """,userAccountId);
      if(legacyRows.isEmpty()) throw new IllegalStateException("The purchaser billing identity could not be resolved.");
      Map<String,Object> legacy=legacyRows.getFirst();
      Map<String,Object> snapshot=new LinkedHashMap<>();
      snapshot.put("billingType",legacy.get("billing_type"));
      snapshot.put("legalName",legacy.get("legal_name"));
      snapshot.put("gstin",legacy.get("gstin"));
      snapshot.put("gstStateCode",legacy.get("gst_state_code"));
      snapshot.put("billingAddress",parseBillingAddressJson(legacy.get("billing_address_json")));
      snapshot.put("contactEmail",legacy.get("contact_email"));
      snapshot.put("contactPhone",legacy.get("contact_phone"));
      snapshot.put("billingProfileVersion",legacy.get("profile_version"));
      String beneficiaryName=jdbc.queryForObject("select display_name from student where id=?",String.class,beneficiaryStudentId);
      snapshot.put("beneficiaryName",beneficiaryName);
      return snapshot;
    }
    Map<String,Object> row=null;
    if(requestedBillingAddressId!=null) {
      for(Map<String,Object> candidate:rows) {
        if(((Number)candidate.get("id")).longValue()==requestedBillingAddressId.longValue()) {
          row=candidate;break;
        }
      }
      if(row==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The selected billing address does not belong to the purchaser account.");
    } else {
      // Older mobile clients cannot supply an address ID. Keep them compatible by selecting
      // the saved default while updated clients explicitly send the user's chosen address.
      row=rows.getFirst();
    }
    Map<String,Object> snapshot=new LinkedHashMap<>();
    snapshot.put("billingAddressId",((Number)row.get("id")).longValue());
    snapshot.put("billingAddressLabel",row.get("label"));
    snapshot.put("billingType",row.get("billing_type"));
    snapshot.put("legalName",row.get("legal_name"));
    snapshot.put("gstin",row.get("gstin"));
    snapshot.put("gstStateCode",row.get("gst_state_code"));
    try {
      snapshot.put("billingAddress",objectMapper.readValue(
          String.valueOf(row.get("billing_address_json")),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
    } catch(Exception ex) {
      throw new IllegalStateException("The purchaser billing address snapshot could not be read.",ex);
    }
    List<Map<String,Object>> accountRows=jdbc.queryForList("""
      select coalesce(p.version,0) as profile_version,
        coalesce(a.contact_email,u.contact_email,u.email) as contact_email,
        coalesce(a.contact_phone,u.mobile_e164) as contact_phone
      from user_account u
      left join billing_customer_profile p on p.user_account_id=u.id
      left join billing_customer_address a on a.id=? and a.user_account_id=u.id
      where u.id=? and u.active=true
      """,row.get("id"),userAccountId);
    if(accountRows.isEmpty()) throw new IllegalStateException("The purchaser billing identity could not be resolved.");
    Map<String,Object> account=accountRows.getFirst();
    snapshot.put("contactEmail",account.get("contact_email"));
    snapshot.put("contactPhone",account.get("contact_phone"));
    snapshot.put("billingProfileVersion",account.get("profile_version"));
    String beneficiaryName=jdbc.queryForObject("select display_name from student where id=?",String.class,beneficiaryStudentId);
    snapshot.put("beneficiaryName",beneficiaryName);
    return snapshot;
  }

  /**
   * Test-mode recovery may use a legacy profile snapshot without a billing-address-book ID,
   * but only when the exact beneficiary/purchaser resolves and all invoice-required address
   * fields exist. Live checkout continues to require an address-book row.
   */
  private void requireCompleteLegacyRecoveryBillingAddress(Map<String,Object> snapshot) {
    if(!hasCompleteLegacyRecoveryBillingAddress(snapshot)) {
      throw new IllegalArgumentException("A complete postal address is required to recover the legacy test order.");
    }
  }

  static boolean hasCompleteLegacyRecoveryBillingAddress(Map<String,Object> snapshot) {
    if(snapshot==null||!(snapshot.get("billingAddress") instanceof Map<?,?> address)) return false;
    for(String required:List.of("line1","city","state","postalCode")) {
      Object value=address.get(required);
      if(value==null||String.valueOf(value).isBlank()) return false;
    }
    return true;
  }

  private void requireCompleteBillingSnapshot(Map<String,Object> snapshot) {
    if(snapshot.get("billingAddressId")==null) {
      throw new IllegalArgumentException("Add a complete billing address in My Profile before paying for a course.");
    }
    Object raw=snapshot.get("billingAddress");
    if(!(raw instanceof Map<?,?> address)) {
      throw new IllegalArgumentException("Complete your saved billing address in My Profile before checking out.");
    }
    for(String required:List.of("line1","city","state","postalCode")) {
      Object value=address.get(required);
      if(value==null||String.valueOf(value).isBlank()) {
        throw new IllegalArgumentException("Complete your saved billing address in My Profile before checking out.");
      }
    }
  }

  private void requireSameBillingAddressChoice(Map<String,Object> prior,Map<String,Object> requestedSnapshot) {
    Object raw=prior.get("buyer_snapshot_json");
    if(raw==null||String.valueOf(raw).isBlank()||"{}".equals(String.valueOf(raw).trim())) return;
    try {
      Map<String,Object> saved=objectMapper.readValue(String.valueOf(raw),
          new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
      Object savedId=saved.get("billingAddressId");
      Object requestedId=requestedSnapshot.get("billingAddressId");
      if(savedId!=null) {
        // A legacy client recovering an old order may have no address-book row anymore.
        // In that case never replace the immutable order snapshot with a different address.
        if(requestedId==null) return;
        if(!String.valueOf(savedId).equals(String.valueOf(requestedId))) {
          throw new IllegalStateException("A checkout for a different billing address is still open. Finish or reconcile that order before changing the billing address.");
        }
        return;
      }
      // Orders created before address IDs existed retain their original immutable snapshot.
      // Do not block their recovery just because the live profile changed later.
    } catch(IllegalStateException ex) {
      throw ex;
    } catch(Exception ex) {
      throw new IllegalStateException("The saved order's billing address could not be checked safely. Reconcile this order before retrying.",ex);
    }
  }

  private List<Map<String, Object>> openOrders(
      long studentId, long institutionId, long courseIdentityId, long currentCourseId) {
    return jdbc.queryForList("""
        select id,purchaser_user_id,institution_offer_id,course_identity_id,identity_contract_version,
          offer_price_paise,currency,provider_order_id,status,listed_price_paise,
          discount_offer_id,discount_amount_paise,discount_reference,
          discount_snapshot::text as discount_snapshot_json,
          tax_profile_id,revenue_share_policy_id,
          tax_calculation_snapshot::text as tax_calculation_snapshot_json,
          revenue_share_policy_snapshot::text as revenue_share_policy_snapshot_json,
          buyer_snapshot::text as buyer_snapshot_json
        from course_payment_order
        where student_id=? and institution_id=?
          and (course_identity_id=? or (course_identity_id is null and qe_same_logical_course(course_id,?)))
          and status in ('CREATING','PENDING')
        order by id desc
        """, studentId, institutionId, courseIdentityId, currentCourseId);
  }

  /** Expire a superseded offer only when the provider proves the exact saved order is unpaid. */
  private void expireSupersededUnpaidOrder(
      Map<String,Object> prior,long institutionId,long targetStudentId) {
    Object rawOfferId=prior.get("institution_offer_id");
    Object rawOrderId=prior.get("id");
    if(!(rawOfferId instanceof Number savedOffer) || savedOffer.longValue()<=0
        || !(rawOrderId instanceof Number savedOrder)
        || !"PENDING".equals(String.valueOf(prior.get("status")))) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "A previous checkout exists for this course identity, but it cannot be safely replaced automatically. Do not pay again; reconcile the saved order first.");
    }

    String providerState;
    try {
      providerState=inspectProviderOrderForNewAttempt(prior);
    } catch(RuntimeException providerFailure) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The previous checkout could not be safely replaced because its payment status could not be confirmed. Do not pay again; reconcile the saved order first.");
    }

    // Reuse the existing reconciler as the authority when the lightweight provider check is ambiguous.
    // It confirms the exact saved order, amount and currency, and reports whether a retry is safe.
    if(!providerConfirmsRetryIsSafe(providerState)) {
      Long originalPayerUserId = prior.get("purchaser_user_id") instanceof Number payer
          ? payer.longValue() : null;
      boolean reconciledVerified=false;
      boolean reconciledRetrySafe=false;
      try {
        Map<String,Object> reconciliation = reconcileOrderInternal(
            savedOrder.longValue(), targetStudentId, originalPayerUserId, institutionId);
        reconciledVerified = reconciliationConfirmsExistingOrderVerified(reconciliation);
        reconciledRetrySafe = reconciliationConfirmsCheckoutRetryIsSafe(reconciliation);
      } catch(RuntimeException reconciliationFailure) {
        // An exception is not proof that payment failed; retain the fail-closed decision below.
      }
      if(!reconciledVerified) {
        Boolean nowVerified=jdbc.queryForObject("""
          select exists(select 1 from course_payment_order
            where id=? and institution_id=? and status='VERIFIED')
          """,Boolean.class,savedOrder.longValue(),institutionId);
        reconciledVerified=Boolean.TRUE.equals(nowVerified);
      }
      if(reconciledVerified) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The previous checkout has now been reconciled and verified. Refresh your courses; do not buy this course again.");
      }
      if(reconciledRetrySafe) providerState="RETRYABLE";
    }

    if("PAID".equals(providerState)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "A previous checkout for this course was reported paid. Automatic reconciliation did not complete; do not pay again. The saved order requires finance review.");
    }
    if(!providerConfirmsRetryIsSafe(providerState)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The previous checkout is not yet confirmed unpaid. Its saved order was checked for reconciliation, but the provider state remains ambiguous. Do not pay again; finance must review the saved order.");
    }

    int changed=jdbc.update("""
        update course_payment_order
        set status='EXPIRED',failure_code='COURSE_OFFER_SUPERSEDED_UNPAID',updated_at=now()
        where id=? and institution_id=? and status='PENDING'
          and institution_offer_id=? and provider_order_id=?
        """,savedOrder.longValue(),institutionId,savedOffer.longValue(),prior.get("provider_order_id"));
    if(changed!=1) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The previous checkout changed while being reconciled. Reload the course and check payment status before trying again.");
    }
  }

  private Map<String, Object> checkoutResponse(
      Map<String, Object> order, long courseId, String title, String institutionDisplayName) {
    if (!isReusablePendingOrder(order)) {
      throw new IllegalStateException("The previous course checkout is not ready. Please retry shortly.");
    }

    // A local PENDING row can outlive a successful provider payment when the browser
    // callback was interrupted or verification failed. Never hand a paid Razorpay order
    // back to checkout; the original payment must be reconciled instead of charged again.
    String providerOrderId = String.valueOf(order.get("provider_order_id"));
    JsonNode providerOrder = razorpayRequest("GET", "/orders/" + providerOrderId, null);
    long expectedAmount = ((Number) order.get("offer_price_paise")).longValue();
    if ("paid".equalsIgnoreCase(providerOrder.path("status").asText(""))
        || providerOrder.path("amount_paid").asLong(0) >= expectedAmount) {
      throw new IllegalStateException(
          "A payment has already completed for this course order. Retry verification for the saved payment; do not start another checkout.");
    }

    return checkoutResponse(
        ((Number) order.get("id")).longValue(),
        providerOrderId,
        expectedAmount,
        String.valueOf(order.get("currency")),
        courseId,
        title,
        institutionDisplayName);
  }

  private Map<String, Object> checkoutResponse(
      long localOrderId, String providerOrderId, long amountPaise,
      String currency, long courseId, String title, String institutionDisplayName) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("orderId", providerOrderId);
    result.put("localOrderId", localOrderId);
    result.put("amount", amountPaise);
    result.put("currency", currency);
    result.put("keyId", keyId);
    result.put("courseId", courseId);
    result.put("name", institutionDisplayName);
    result.put("description", title == null || title.isBlank() ? "Course access" : title);
    List<Map<String,Object>> pricingRows=jdbc.queryForList("""
      select discount_amount_paise,discount_reference
      from course_payment_order where id=?
      """,localOrderId);
    if(pricingRows.isEmpty()) throw new IllegalStateException("The local course order could not be loaded.");
    Map<String,Object> pricing=pricingRows.getFirst();
    Object discountRaw=pricing.get("discount_amount_paise");
    result.put("discountPaise",discountRaw instanceof Number n?n.longValue():0L);
    Object codeRaw=pricing.get("discount_reference");
    if(codeRaw!=null&&!String.valueOf(codeRaw).isBlank()) result.put("discountCode",String.valueOf(codeRaw));
    return result;
  }

  static boolean isValidIdempotencyKey(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{16,64}");
  }

  /** Refuse payment capture/finalization when the original fiscal policy snapshot is incomplete. */
  static void requireLockedFinancialSnapshotsForVerification(Map<String,Object> order) {
    if (!hasLockedFinancialSnapshots(order)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This existing order is missing its locked tax/revenue-share snapshot. Do not pay again; the same order requires explicit financial reconciliation.");
    }
  }

  static boolean hasLockedFinancialSnapshots(Map<String,Object> order) {
    if(order==null||order.get("tax_profile_id")==null||order.get("revenue_share_policy_id")==null) return false;
    String tax=String.valueOf(order.get("tax_calculation_snapshot_json"));
    String shares=String.valueOf(order.get("revenue_share_policy_snapshot_json"));
    return tax.contains("\"customerPayablePaise\"")
        && tax.contains("\"taxProfileId\"")
        && shares.contains("\"policyId\"")
        && shares.contains("\"recipients\"");
  }

  static boolean isReusablePendingOrder(Map<String, Object> order) {
    if (order == null || !"PENDING".equalsIgnoreCase(String.valueOf(order.get("status")))) return false;
    Object id = order.get("provider_order_id");
    Object amount = order.get("offer_price_paise");
    String currency = String.valueOf(order.get("currency"));
    return id != null && String.valueOf(id).matches("order_[A-Za-z0-9]+")
        && amount instanceof Number number && number.longValue() > 0
        && "INR".equalsIgnoreCase(currency);
  }

  /** Verify checkout signature and reconcile the payment against Razorpay before granting access. */
  @PostMapping("/orders/{localOrderId}/verify")
  @Transactional
  public Map<String, Object> verifyOrder(
      @PathVariable long localOrderId,
      @RequestBody VerifyCoursePaymentRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    return verifyOrderInternal(localOrderId, request, context.studentId(), null, requireTenant(tenant).institutionId());
  }

  /** Parent checkout verification is bound to the purchaser identity, never to a client-supplied learner. */
  @PostMapping("/family/orders/{localOrderId}/verify")
  @Transactional
  public Map<String, Object> verifyFamilyOrder(
      @PathVariable long localOrderId,
      @RequestBody VerifyCoursePaymentRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    return verifyOrderInternal(localOrderId, request, null, context.userId(), requireTenant(tenant).institutionId());
  }

  /** Reconcile a saved student order directly with Razorpay when a checkout callback is missing. */
  @PostMapping("/orders/{localOrderId}/reconcile")
  @Transactional
  public Map<String, Object> reconcileOrder(
      @PathVariable long localOrderId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireStudent(context);
    return reconcileOrderInternal(localOrderId, context.studentId(), null, requireTenant(tenant).institutionId());
  }

  /** Parent reconciliation stays bound to the authenticated purchaser and their institution. */
  @PostMapping("/family/orders/{localOrderId}/reconcile")
  @Transactional
  public Map<String, Object> reconcileFamilyOrder(
      @PathVariable long localOrderId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context,
      @RequestAttribute(value = InstitutionTenantContextService.REQUEST_ATTRIBUTE, required = false)
          InstitutionTenantContext tenant) {
    context = authorization.requireParent(context);
    return reconcileOrderInternal(localOrderId, null, context.userId(), requireTenant(tenant).institutionId());
  }

  private Map<String, Object> reconcileOrderInternal(
      long localOrderId, Long callerStudentId, Long callerUserId, long institutionId) {
    return reconcileOrderInternal(localOrderId, callerStudentId, callerUserId, institutionId, false, false);
  }

  private Map<String, Object> reconcileOrderInternal(
      long localOrderId, Long callerStudentId, Long callerUserId, long institutionId,
      boolean includeProviderDiagnostics) {
    return reconcileOrderInternal(localOrderId, callerStudentId, callerUserId, institutionId,
        includeProviderDiagnostics, false);
  }

  /**
   * Platform-owner entry point used by HRMS Payments & Reconciliation. The purchaser/beneficiary
   * IDs are loaded from the persisted order, never accepted from the caller.
   */
  @Transactional
  public Map<String, Object> reconcileOrderForHrms(long localOrderId, AuthContext context) {
    authorization.requirePermission(context, "QE_PLATFORM_OWNER");
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select id,student_id,purchaser_user_id,institution_id,course_id,status
        from course_payment_order where id=? for update
        """, localOrderId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Course payment order not found.");
    Map<String,Object> order = rows.getFirst();
    String state = String.valueOf(order.get("status"));
    long courseId = ((Number) order.get("course_id")).longValue();
    if ("VERIFIED".equals(state)) {
      return new LinkedHashMap<>(verifiedResult(localOrderId, courseId));
    }
    if (!List.of("PENDING", "EXPIRED").contains(state)) {
      throw new IllegalArgumentException(
          "This order is not eligible for provider reconciliation. Review its saved failure code and event history; do not mark it paid manually.");
    }
    if (order.get("institution_id") == null) {
      throw new IllegalStateException(
          "This legacy order has no institution scope. Do not reconcile or issue documents until finance resolves its ownership.");
    }
    Long callerStudentId = order.get("student_id") == null ? null : ((Number) order.get("student_id")).longValue();
    Long callerUserId = order.get("purchaser_user_id") == null ? null : ((Number) order.get("purchaser_user_id")).longValue();
    long institutionId = ((Number) order.get("institution_id")).longValue();
    return reconcileOrderInternal(localOrderId, callerStudentId, callerUserId, institutionId, true, true);
  }

  /**
   * Signed provider-webhook entry point. Resolve the local order solely from the already-saved
   * Razorpay order ID; the webhook payload never supplies price, buyer, institution or entitlement.
   * Existing server-side provider lookups and captured-payment checks remain authoritative.
   */
  @Transactional
  public Map<String,Object> reconcileOrderFromProviderEvent(String providerOrderId) {
    if(providerOrderId==null||!providerOrderId.matches("order_[A-Za-z0-9]+"))
      return Map.of("status","UNMATCHED","message","The provider event has no valid saved Razorpay order reference.");
    List<Map<String,Object>> rows=jdbc.queryForList("""
        select id,student_id,purchaser_user_id,institution_id,course_id,status
        from course_payment_order where provider_order_id=? for update
        """,providerOrderId);
    if(rows.isEmpty())
      return Map.of("status","UNMATCHED","message","No QuantaEdge course order references this Razorpay order. Finance must investigate.");
    Map<String,Object> order=rows.getFirst();
    long localOrderId=((Number)order.get("id")).longValue();
    long courseId=((Number)order.get("course_id")).longValue();
    String state=String.valueOf(order.get("status"));
    if("VERIFIED".equals(state)) {
      Map<String,Object> existing=new LinkedHashMap<>(verifiedResult(localOrderId,courseId));
      existing.put("message","This exact provider order is already verified; no duplicate purchase or invoice was created.");
      return existing;
    }
    if(!List.of("PENDING","EXPIRED").contains(state))
      return Map.of("status","ACTION_BLOCKED","localOrderId",localOrderId,
          "message","The local order is in "+state+" and cannot be automatically finalized. Keep the event for HRMS review.");
    if(order.get("institution_id")==null)
      return Map.of("status","RECONCILIATION_REQUIRED","localOrderId",localOrderId,
          "message","The saved order lacks institution scope. Do not grant access or collect another payment.");

    long institutionId=((Number)order.get("institution_id")).longValue();
    Long studentId=order.get("student_id")==null?null:((Number)order.get("student_id")).longValue();
    Long purchaserUserId=order.get("purchaser_user_id")==null?null:((Number)order.get("purchaser_user_id")).longValue();
    // Catch here, inside this proxied method, so a provider timeout can be recorded as an
    // actionable terminal inbox outcome instead of marking the outer webhook transaction rollback-only.
    try {
      return reconcileOrderInternal(localOrderId,studentId,purchaserUserId,institutionId,true,false);
    } catch(RuntimeException providerOrFinalizationFailure) {
      Map<String,Object> blocked=new LinkedHashMap<>();
      blocked.put("status","RECONCILIATION_REQUIRED");
      blocked.put("orderId",localOrderId);
      blocked.put("canRetryCheckout",false);
      blocked.put("message","The signed provider event was received, but authoritative payment verification/finalization did not complete. Reconcile this saved order in HRMS; do not collect another payment.");
      blocked.put("errorCategory",providerOrFinalizationFailure.getClass().getSimpleName());
      return blocked;
    }
  }

  private Map<String, Object> reconcileOrderInternal(
      long localOrderId, Long callerStudentId, Long callerUserId, long institutionId,
      boolean includeProviderDiagnostics, boolean allowTestModeSnapshotRepair) {
    requireConfigured();
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select id,student_id,purchaser_user_id,target_student_id,recipient_type,course_id,
          institution_id,institution_offer_id,course_identity_id,identity_contract_version,offer_price_paise,currency,provider_order_id,provider_payment_id,status,
          listed_price_paise,discount_offer_id,discount_amount_paise,discount_reference,
          discount_snapshot::text as discount_snapshot_json,
          tax_profile_id,revenue_share_policy_id,taxable_value_paise,output_tax_paise,
          tax_calculation_snapshot::text as tax_calculation_snapshot_json,
          revenue_share_policy_snapshot::text as revenue_share_policy_snapshot_json,
          buyer_snapshot::text as buyer_snapshot_json,
          to_char(created_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"') as order_created_at_iso
        from course_payment_order
        where id=? and institution_id=?
          and ((? is not null and student_id=? and purchaser_user_id is null)
            or (? is not null and purchaser_user_id=?))
        for update
        """, localOrderId, institutionId, callerStudentId, callerStudentId, callerUserId, callerUserId);
    if (rows.isEmpty()) throw new SecurityException("This payment order is not available to this account.");
    Map<String,Object> order = rows.getFirst();
    String state = String.valueOf(order.get("status"));
    long courseId = ((Number) order.get("course_id")).longValue();
    if ("VERIFIED".equals(state)) return verifiedResult(localOrderId, courseId);
    if (!List.of("PENDING", "EXPIRED").contains(state)) {
      throw new IllegalArgumentException("This payment order is not available for reconciliation.");
    }

    String providerOrderId = String.valueOf(order.get("provider_order_id"));
    if (!providerOrderId.matches("order_[A-Za-z0-9]+")) {
      throw new IllegalStateException(
          "The saved order has no verified Razorpay order reference. Do not pay again; contact support.");
    }
    long expectedAmount = ((Number) order.get("offer_price_paise")).longValue();
    String expectedCurrency = String.valueOf(order.get("currency"));
    JsonNode providerOrder = razorpayRequest("GET", "/orders/" + providerOrderId, null);
    if (!providerOrderId.equals(providerOrder.path("id").asText(""))
        || providerOrder.path("amount").asLong(-1) != expectedAmount
        || !expectedCurrency.equalsIgnoreCase(providerOrder.path("currency").asText(""))) {
      throw new IllegalStateException(
          "Razorpay order details do not match the saved course order. Do not pay again; contact support.");
    }

    JsonNode paymentList = razorpayRequest("GET", "/orders/" + providerOrderId + "/payments", null);
    JsonNode items = paymentList.path("items");
    JsonNode captured = null;
    JsonNode authorised = null;
    boolean paymentInProgress = false;
    if (items.isArray()) {
      for (JsonNode candidate : items) {
        String candidateId = candidate.path("id").asText("");
        if (!candidateId.matches("pay_[A-Za-z0-9]+")
            || !isPaymentForExactOrder(candidate, candidateId, providerOrderId, expectedAmount, expectedCurrency)) {
          continue;
        }
        String paymentStatus = candidate.path("status").asText("").toLowerCase(Locale.ROOT);
        if ("captured".equals(paymentStatus)) {
          captured = candidate;
          break;
        }
        if ("authorized".equals(paymentStatus)) authorised = candidate;
        else if (Set.of("created", "processing").contains(paymentStatus)) paymentInProgress = true;
      }
    }

    JsonNode selected = captured != null ? captured : authorised;
    if (selected != null) {
      boolean repairedFinancialSnapshots = false;
      String financialSnapshotRepairBasis = "";
      if (!hasLockedFinancialSnapshots(order) && allowTestModeSnapshotRepair) {
        financialSnapshotRepairBasis=repairFinancialSnapshotsDuringTestModeReconciliation(order,institutionId);
        repairedFinancialSnapshots = true;
      }
      String paymentId = selected.path("id").asText("");
      // The provider API confirms the exact persisted order, amount and currency. Generate
      // the same callback HMAC server-side only after those authenticated provider checks.
      String signature = hmacSha256(providerOrderId + "|" + paymentId, keySecret);
      Map<String,Object> verified = new LinkedHashMap<>(verifyOrderInternal(localOrderId,
          new VerifyCoursePaymentRequest(paymentId, providerOrderId, signature),
          callerStudentId, callerUserId, institutionId));
      if (repairedFinancialSnapshots) {
        verified.put("financialSnapshotsRepaired", true);
        verified.put("financialSnapshotRepairMode", "TEST_MODE_HRMS_RECONCILIATION");
        verified.put("financialSnapshotRepairBasis", financialSnapshotRepairBasis);
        verified.put("financialSnapshotRepairMessage",
            "Missing order snapshots were reconstructed only after the exact saved Razorpay order/payment was checked, no discount facts were present, and approved terms reproduced the existing payable amount.");
      }
      if (includeProviderDiagnostics) {
        verified.putAll(providerPaymentDiagnostics(providerOrder, items, providerOrderId, expectedAmount, expectedCurrency));
      }
      return verified;
    }

    if ("paid".equalsIgnoreCase(providerOrder.path("status").asText(""))
        || providerOrder.path("amount_paid").asLong(0) >= expectedAmount) {
      String message="Razorpay reports this order as paid, but the exact matching payment is not available. Do not pay again; finance/engineering must reconcile the provider references.";
      if (!includeProviderDiagnostics) throw new IllegalStateException(message);
      Map<String,Object> result=new LinkedHashMap<>();
      result.put("status","RECONCILIATION_REQUIRED");
      result.put("entitled",false);
      result.put("canRetryCheckout",false);
      result.put("orderId",localOrderId);
      result.put("courseId",courseId);
      result.put("message",message);
      result.putAll(providerPaymentDiagnostics(providerOrder,items,providerOrderId,expectedAmount,expectedCurrency));
      return result;
    }

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("status", paymentInProgress ? "PAYMENT_PROCESSING" : "AWAITING_PAYMENT");
    result.put("entitled", false);
    result.put("canRetryCheckout", !paymentInProgress);
    result.put("orderId", localOrderId);
    result.put("courseId", courseId);
    result.put("message", paymentInProgress
        ? "Razorpay is still processing the payment. Retry verification shortly; do not pay again."
        : "No authorised or captured payment was found for this order. It is safe to retry checkout; a new Razorpay order will be created.");
    if (includeProviderDiagnostics) {
      result.putAll(providerPaymentDiagnostics(providerOrder, items, providerOrderId, expectedAmount, expectedCurrency));
    }
    return result;
  }

  /**
   * Recover only test-mode legacy orders during an explicit HRMS reconciliation. This path never
   * fabricates a snapshot from today's policy: it requires approved tax/share terms effective when
   * the saved order was created, no coupon facts, complete persisted buyer details, and an exact
   * match to the existing offer amount. Live-mode orders remain blocked for manual finance review.
   */
  private String repairFinancialSnapshotsDuringTestModeReconciliation(
      Map<String,Object> order,long institutionId) {
    if(!isRazorpayTestModeKey(keyId)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This order is missing locked financial snapshots. Automatic reconstruction is limited to Razorpay test mode; preserve this order and route live-mode orders to explicit finance review.");
    }
    if(!hasNoDiscountFacts(order)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This order has coupon/discount facts that cannot be reconstructed automatically. The saved order is unchanged; finance must resolve its original discount snapshot.");
    }
    Object orderIdRaw=order.get("id");
    Object offerIdRaw=order.get("institution_offer_id");
    if(!(orderIdRaw instanceof Number orderIdNumber)||!(offerIdRaw instanceof Number offerIdNumber)) {
      throw new IllegalStateException("The saved order is missing its local order or institution offer reference.");
    }
    long orderId=orderIdNumber.longValue();
    long offerId=offerIdNumber.longValue();
    List<Map<String,Object>> offers=jdbc.queryForList("""
        select o.id,o.course_id,o.institution_id,o.price_paise,o.currency,
          coalesce(nullif(o.title_override,''),p.display_name) as course_title
        from institution_marketplace_offer o
        join curriculum_program p on p.id=o.course_id
        where o.id=? and o.institution_id=? and o.course_id=?
        """,offerId,institutionId,((Number)order.get("course_id")).longValue());
    if(offers.size()!=1) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The original institution Marketplace offer is no longer uniquely available. Snapshots were not reconstructed; review the offer history.");
    }
    Map<String,Object> offer=offers.getFirst();
    long offerPricePaise=((Number)offer.get("price_paise")).longValue();
    long savedPayable=((Number)order.get("offer_price_paise")).longValue();
    String savedCurrency=String.valueOf(order.get("currency"));
    String offerCurrency=String.valueOf(offer.get("currency"));
    if(!"INR".equalsIgnoreCase(savedCurrency)||!savedCurrency.equalsIgnoreCase(offerCurrency)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The saved order and current offer currency do not match. Snapshots were not reconstructed.");
    }
    Object priorListedRaw=order.get("listed_price_paise");
    if(priorListedRaw instanceof Number priorListed && priorListed.longValue()!=offerPricePaise) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The offer price differs from the original listed price saved on this order. Snapshots were not reconstructed.");
    }
    // Tax may be added on top of the listed price. The authoritative comparison is the
    // reconstructed customer payable below, not listed price versus payable.
    if(!(order.get("order_created_at_iso") instanceof String createdAtRaw)||createdAtRaw.isBlank()) {
      throw new IllegalStateException("The saved order creation time is unavailable; historical financial terms cannot be resolved.");
    }
    java.time.OffsetDateTime createdAt;
    try { createdAt=java.time.OffsetDateTime.parse(createdAtRaw); }
    catch(Exception exception) {
      throw new IllegalStateException("The saved order creation time is invalid; historical financial terms cannot be resolved.",exception);
    }
    Object buyerRaw=order.get("buyer_snapshot_json");
    Map<String,Object> buyerSnapshot;
    boolean reconstructedBuyerSnapshot=false;
    if(buyerRaw==null||String.valueOf(buyerRaw).isBlank()
        ||"{}".equals(String.valueOf(buyerRaw).trim())
        ||"null".equalsIgnoreCase(String.valueOf(buyerRaw).trim())) {
      if(!mayReconstructMissingBuyerSnapshotForTestMode(order,isRazorpayTestModeKey(keyId))) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The original buyer/billing snapshot is missing. Automatic reconstruction is restricted to test-mode orders with no discount facts; finance must review this order.");
      }
      if(!(order.get("student_id") instanceof Number beneficiaryId)) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The saved order has no beneficiary identity for a test-mode billing snapshot recovery.");
      }
      Long buyerAccountId=order.get("purchaser_user_id") instanceof Number purchaser
          ? purchaser.longValue() : null;
      if(buyerAccountId==null&&"CHILD".equalsIgnoreCase(String.valueOf(order.get("recipient_type")))) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The parent purchaser identity is missing from this child checkout. Finance must resolve ownership before recovery.");
      }
      if(buyerAccountId==null) {
        List<Map<String,Object>> linkedAccounts=jdbc.queryForList("""
            select user_account_id from student where id=? and active=true
            """,beneficiaryId.longValue());
        if(linkedAccounts.size()!=1
            ||!(linkedAccounts.getFirst().get("user_account_id") instanceof Number linkedUser)) {
          throw new org.springframework.web.server.ResponseStatusException(
              org.springframework.http.HttpStatus.CONFLICT,
              "The original student checkout cannot be linked to one active purchaser account. Finance must review this order.");
        }
        buyerAccountId=linkedUser.longValue();
      }
      try {
        buyerSnapshot=billingProfileSnapshot(buyerAccountId,beneficiaryId.longValue(),null);
        requireCompleteLegacyRecoveryBillingAddress(buyerSnapshot);
      } catch(RuntimeException billingProfileFailure) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The legacy test order has no complete active billing profile to reconstruct its buyer snapshot. Complete the purchaser billing profile before HRMS retries reconciliation.",
            billingProfileFailure);
      }
      buyerSnapshot.put("snapshotRecovery",Map.of(
          "mode","TEST_MODE_HRMS_RECONCILIATION",
          "source","CURRENT_ACTIVE_BILLING_PROFILE",
          "reconstructedAt",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
          "historicalBuyerSnapshotAvailable",false));
      reconstructedBuyerSnapshot=true;
    } else {
      try {
        buyerSnapshot=objectMapper.readValue(String.valueOf(buyerRaw),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
      } catch(Exception exception) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The original buyer/billing snapshot cannot be read safely. Finance must review this order.",exception);
      }
    }
    Object addressRaw=buyerSnapshot.get("billingAddress");
    if(!(addressRaw instanceof Map<?,?> address)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The original buyer snapshot does not contain a complete billing address. Finance must review this order.");
    }
    for(String required:List.of("line1","city","state","postalCode")) {
      Object value=address.get(required);
      if(value==null||String.valueOf(value).isBlank()) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT,
            "The original buyer snapshot has an incomplete billing address. Finance must review this order.");
      }
    }

    CourseOrderFinancialService.CheckoutSnapshot snapshot;
    String repairBasis;
    try {
      snapshot=courseOrderFinancialService.prepareCheckout(offerId,institutionId,offerPricePaise,offerCurrency,
          String.valueOf(offer.get("course_title")),buyerSnapshot,null,createdAt);
      repairBasis="APPROVED_TERMS_EFFECTIVE_AT_ORDER_CREATION";
    } catch(IllegalStateException historicalConfigUnavailable) {
      String reason=historicalConfigUnavailable.getMessage()==null?"":historicalConfigUnavailable.getMessage();
      boolean noHistoricalTax=reason.contains("approve an effective tax profile");
      boolean noHistoricalShares=reason.contains("approve an effective revenue-share policy");
      boolean noHistoricalTemplate=reason.contains("publish an invoice template");
      if(!(noHistoricalTax||noHistoricalShares||noHistoricalTemplate)) throw historicalConfigUnavailable;
      // In test mode it is acceptable to restore a synthetic sale under today's approved setup,
      // provided the saved amount matches exactly. The explicit snapshot marker prevents this from
      // being mistaken for the original historical policy basis. Live mode never takes this path.
      snapshot=courseOrderFinancialService.prepareCheckout(offerId,institutionId,offerPricePaise,offerCurrency,
          String.valueOf(offer.get("course_title")),buyerSnapshot,null);
      repairBasis="CURRENT_APPROVED_TERMS_FOR_TEST_MODE_RECOVERY";
    }
    if(snapshot.payablePaise()!=savedPayable||snapshot.discountPaise()!=0
        ||snapshot.listedPricePaise()!=offerPricePaise) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Approved tax/share terms effective at the original order time do not reproduce the saved payable exactly. Snapshots were not reconstructed.");
    }
    if(order.get("tax_profile_id") instanceof Number oldTaxId
        && oldTaxId.longValue()!=snapshot.taxProfileId()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The existing tax-profile reference conflicts with the approved profile effective at order creation. Snapshots were not reconstructed.");
    }
    if(order.get("revenue_share_policy_id") instanceof Number oldPolicyId
        && oldPolicyId.longValue()!=snapshot.revenueSharePolicyId()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The existing revenue-share reference conflicts with the approved policy effective at order creation. Snapshots were not reconstructed.");
    }
    if(order.get("taxable_value_paise") instanceof Number oldTaxable
        && oldTaxable.longValue()!=snapshot.taxableValuePaise()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The saved taxable amount conflicts with the reconstructed approved tax calculation. Snapshots were not reconstructed.");
    }
    if(order.get("output_tax_paise") instanceof Number oldTax
        && oldTax.longValue()!=snapshot.outputTaxPaise()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The saved tax amount conflicts with the reconstructed approved tax calculation. Snapshots were not reconstructed.");
    }

    Map<String,Object> taxSnapshot=new LinkedHashMap<>(snapshot.taxCalculationSnapshot());
    Map<String,Object> shareSnapshot=new LinkedHashMap<>(snapshot.revenueSharePolicySnapshot());
    Map<String,Object> recovery=new LinkedHashMap<>();
    recovery.put("mode","TEST_MODE_HRMS_RECONCILIATION");
    recovery.put("originalOrderCreatedAt",createdAt.toString());
    recovery.put("reconstructedAt",java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
    recovery.put("basis",repairBasis);
    recovery.put("buyerSnapshotSource",reconstructedBuyerSnapshot
        ? "CURRENT_ACTIVE_BILLING_PROFILE_TEST_MODE_RECOVERY" : "ORIGINAL_ORDER_SNAPSHOT");
    recovery.put("validation","Exact saved provider order amount/currency; no discount facts; "
        +(reconstructedBuyerSnapshot
            ? "current active purchaser billing profile was used only for TEST-mode recovery."
            : "original buyer snapshot preserved."));
    recovery.put("testModeOnly",true);
    taxSnapshot.put("snapshotRecovery",recovery);
    shareSnapshot.put("snapshotRecovery",recovery);
    String taxJson;
    String shareJson;
    String discountJson;
    String buyerJson;
    try {
      taxJson=objectMapper.writeValueAsString(taxSnapshot);
      shareJson=objectMapper.writeValueAsString(shareSnapshot);
      discountJson=objectMapper.writeValueAsString(snapshot.discountSnapshot());
      buyerJson=objectMapper.writeValueAsString(buyerSnapshot);
    } catch(JsonProcessingException exception) {
      throw new IllegalStateException("The validated financial recovery snapshots could not be serialized.",exception);
    }
    int updated=jdbc.update("""
        update course_payment_order set
          listed_price_paise=?,discount_offer_id=null,discount_amount_paise=0,discount_reference=null,
          discount_snapshot=?::jsonb,tax_profile_id=?,taxable_value_paise=?,output_tax_paise=?,
          tax_calculation_snapshot=?::jsonb,revenue_share_policy_id=?,revenue_share_policy_snapshot=?::jsonb,
          buyer_snapshot=?::jsonb,updated_at=now()
        where id=? and institution_id=? and status in ('PENDING','EXPIRED')
          and provider_order_id=? and offer_price_paise=? and upper(currency)=upper(?)
          and discount_offer_id is null and coalesce(discount_amount_paise,0)=0
          and nullif(btrim(discount_reference),'') is null
          and (buyer_snapshot is null or buyer_snapshot='{}'::jsonb or buyer_snapshot=?::jsonb)
          and (tax_profile_id is null or revenue_share_policy_id is null or not (
            tax_calculation_snapshot ? 'customerPayablePaise'
            and tax_calculation_snapshot ? 'taxProfileId'
            and revenue_share_policy_snapshot ? 'policyId'
            and jsonb_typeof(revenue_share_policy_snapshot->'recipients')='array'))
        """,snapshot.listedPricePaise(),discountJson,snapshot.taxProfileId(),
        snapshot.taxableValuePaise(),snapshot.outputTaxPaise(),taxJson,
        snapshot.revenueSharePolicyId(),shareJson,buyerJson,orderId,institutionId,
        String.valueOf(order.get("provider_order_id")),savedPayable,savedCurrency,buyerJson);
    if(updated!=1) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "The saved order changed while its test-mode recovery was being saved. No new order was created; reload and reconcile the existing order.");
    }
    return repairBasis;
  }

  static boolean isRazorpayTestModeKey(String keyId) {
    return keyId!=null && keyId.trim().matches("^rzp_test_[A-Za-z0-9]+$");
  }

  /**
   * The current billing address may stand in for missing historical buyer facts only for a
   * test-mode legacy order with no discount facts. Live orders and discounted sales are never
   * reconstructed from mutable current profile data.
   */
  static boolean mayReconstructMissingBuyerSnapshotForTestMode(
      Map<String,Object> order,boolean testMode) {
    if(!testMode||order==null||!hasNoDiscountFacts(order)) return false;
    Object raw=order.get("buyer_snapshot_json");
    return raw==null||String.valueOf(raw).isBlank()
        ||"{}".equals(String.valueOf(raw).trim())
        ||"null".equalsIgnoreCase(String.valueOf(raw).trim());
  }

  private Map<String,Object> providerPaymentDiagnostics(
      JsonNode providerOrder, JsonNode paymentItems, String expectedOrderId,
      long expectedAmountPaise, String expectedCurrency) {
    Map<String,Object> diagnostic = new LinkedHashMap<>();
    diagnostic.put("providerOrderId", expectedOrderId);
    diagnostic.put("providerOrderStatus", providerOrder.path("status").asText("unknown"));
    diagnostic.put("providerAmountPaidPaise", providerOrder.path("amount_paid").asLong(0));
    diagnostic.put("providerAttemptCount", providerOrder.path("attempts").asInt(0));
    List<Map<String,Object>> attempts = new ArrayList<>();
    if (paymentItems != null && paymentItems.isArray()) {
      for (JsonNode payment : paymentItems) {
        String paymentOrderId = payment.path("order_id").asText("");
        String paymentId = payment.path("id").asText("");
        if (!expectedOrderId.equals(paymentOrderId) || !paymentId.matches("pay_[A-Za-z0-9]+")) continue;
        Map<String,Object> attempt = new LinkedHashMap<>();
        attempt.put("paymentId", paymentId);
        attempt.put("status", payment.path("status").asText("unknown"));
        attempt.put("amountPaise", payment.path("amount").asLong(-1));
        attempt.put("currency", payment.path("currency").asText(""));
        attempt.put("matchesExpectedAmountAndCurrency",
            isPaymentForExactOrder(payment, paymentId, expectedOrderId, expectedAmountPaise, expectedCurrency));
        String method = safeProviderDiagnosticText(payment.path("method").asText(""), 32);
        String errorCode = safeProviderDiagnosticText(payment.path("error_code").asText(""), 80);
        String errorReason = safeProviderDiagnosticText(payment.path("error_reason").asText(""), 120);
        String errorDescription = safeProviderDiagnosticText(payment.path("error_description").asText(""), 240);
        if (!method.isBlank()) attempt.put("method", method);
        if (!errorCode.isBlank()) attempt.put("errorCode", errorCode);
        if (!errorReason.isBlank()) attempt.put("errorReason", errorReason);
        if (!errorDescription.isBlank()) attempt.put("errorDescription", errorDescription);
        attempts.add(attempt);
      }
    }
    diagnostic.put("paymentAttempts", attempts);
    return diagnostic;
  }

  private static String safeProviderDiagnosticText(String raw, int maxLength) {
    if (raw == null || raw.isBlank()) return "";
    String safe = raw.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replaceAll("\\s+", " ").trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
  }

  /**
   * A legacy PENDING row may be repaired only when its existing Razorpay order is conclusively
   * unpaid and current approved terms reproduce the exact amount already saved with that order.
   * Reusing that same provider order avoids creating two independently payable Razorpay orders.
   */
  private void repairLegacyPendingOrderSnapshots(
      Map<String,Object> localOrder,long institutionId,long institutionOfferId,
      long listedPricePaise,String currency,String title,Map<String,Object> buyerSnapshot,
      String requestedDiscountCode) {
    String storedDiscountCode=localOrder.get("discount_reference")==null
        ?null:String.valueOf(localOrder.get("discount_reference"));
    if (!discountCodeMatchesForRetry(storedDiscountCode,requestedDiscountCode)
        || !hasNoDiscountFacts(localOrder)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This older checkout has incomplete financial snapshots and coupon details that cannot be safely reconstructed automatically. The saved order was left unchanged; do not pay again. HRMS must reconcile this order.");
    }
    Object storedOfferId=localOrder.get("institution_offer_id");
    if(!(storedOfferId instanceof Number savedOfferId)||savedOfferId.longValue()!=institutionOfferId) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This existing checkout belongs to a different course-offer version. The order was left unchanged; do not pay again. HRMS must reconcile the saved offer and its financial terms.");
    }
    String providerState;
    try {
      providerState=inspectProviderOrderForNewAttempt(localOrder);
    } catch(RuntimeException providerCheckFailure) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This existing checkout is missing its locked financial snapshots and Razorpay status could not be confirmed. The saved order was left unchanged; do not pay again until it is reconciled.");
    }
    if("PAID".equals(providerState)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Razorpay reports that this existing checkout is paid, but its locked financial snapshots are missing. Do not pay again; finance/engineering must reconcile this exact order.");
    }
    if(!providerConfirmsRetryIsSafe(providerState)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This existing checkout is missing its locked financial snapshots and Razorpay has not confirmed it is safe to continue. The saved order was left unchanged; do not pay again. Retry after provider status settles or ask HRMS to reconcile this order.");
    }

    Map<String,Object> repairBuyerSnapshot = preserveLegacyBuyerSnapshot(localOrder,buyerSnapshot);
    CourseOrderFinancialService.CheckoutSnapshot snapshot =
        courseOrderFinancialService.prepareCheckout(
            institutionOfferId,institutionId,listedPricePaise,currency,title,repairBuyerSnapshot,null);
    long savedPayable=((Number)localOrder.get("offer_price_paise")).longValue();
    String savedCurrency=String.valueOf(localOrder.get("currency"));
    Object savedListedPrice=localOrder.get("listed_price_paise");
    if(snapshot.payablePaise()!=savedPayable
        || !currency.equalsIgnoreCase(savedCurrency)
        || (savedListedPrice instanceof Number number && number.longValue()!=listedPricePaise)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "Current approved pricing/tax terms do not exactly match this older Razorpay order. The order was left unchanged; do not pay again. HRMS/finance must reconcile it before checkout can continue.");
    }

    Object rawId=localOrder.get("id");
    if(!(rawId instanceof Number orderId)) {
      throw new IllegalStateException("The legacy course order has no valid local ID; its payment order was not changed.");
    }
    String buyerSnapshotJson;
    String taxSnapshotJson;
    String shareSnapshotJson;
    String discountSnapshotJson;
    try {
      buyerSnapshotJson=objectMapper.writeValueAsString(repairBuyerSnapshot);
      taxSnapshotJson=objectMapper.writeValueAsString(snapshot.taxCalculationSnapshot());
      shareSnapshotJson=objectMapper.writeValueAsString(snapshot.revenueSharePolicySnapshot());
      discountSnapshotJson=objectMapper.writeValueAsString(snapshot.discountSnapshot());
    } catch(JsonProcessingException exception) {
      throw new IllegalStateException("Approved financial snapshots could not be serialized; the saved order was not changed.",exception);
    }
    int updated=jdbc.update("""
        update course_payment_order set
          listed_price_paise=?,discount_offer_id=null,discount_amount_paise=0,discount_reference=null,
          discount_snapshot=?::jsonb,tax_profile_id=?,taxable_value_paise=?,output_tax_paise=?,
          tax_calculation_snapshot=?::jsonb,revenue_share_policy_id=?,
          revenue_share_policy_snapshot=?::jsonb,buyer_snapshot=?::jsonb,updated_at=now()
        where id=? and institution_id=? and status='PENDING' and provider_order_id=?
          and offer_price_paise=? and upper(currency)=upper(?)
          and discount_offer_id is null and coalesce(discount_amount_paise,0)=0
          and nullif(btrim(discount_reference),'') is null
          and (tax_profile_id is null or revenue_share_policy_id is null or not (
            tax_calculation_snapshot ? 'customerPayablePaise'
            and tax_calculation_snapshot ? 'taxProfileId'
            and revenue_share_policy_snapshot ? 'policyId'
            and revenue_share_policy_snapshot ? 'recipients'))
        """,
        snapshot.listedPricePaise(),discountSnapshotJson,snapshot.taxProfileId(),
        snapshot.taxableValuePaise(),snapshot.outputTaxPaise(),taxSnapshotJson,
        snapshot.revenueSharePolicyId(),shareSnapshotJson,buyerSnapshotJson,
        orderId.longValue(),institutionId,String.valueOf(localOrder.get("provider_order_id")),
        savedPayable,savedCurrency);
    if(updated!=1) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "This checkout changed while its financial snapshots were being repaired. The order was not replaced; reload and reconcile its current status before paying.");
    }
  }

    static boolean hasNoDiscountFacts(Map<String,Object> order) {
    if(order==null) return false;
    Object rawCode=order.get("discount_reference");
    if(rawCode!=null&&!String.valueOf(rawCode).isBlank()) return false;
    Object rawOffer=order.get("discount_offer_id");
    if(rawOffer!=null) return false;
    Object rawAmount=order.get("discount_amount_paise");
    if(rawAmount instanceof Number n&&n.longValue()!=0L) return false;
    String snapshot=order.get("discount_snapshot_json")==null?"":String.valueOf(order.get("discount_snapshot_json")).trim();
    if(snapshot.isBlank()||"{}".equals(snapshot)) return true;
    // PostgreSQL jsonb::text may include whitespace around separators.
    String compactSnapshot=snapshot.replaceAll("\\s+", "");
    return compactSnapshot.contains("\"discountType\":\"NONE\"")
        && compactSnapshot.contains("\"discountPaise\":0")
        && !compactSnapshot.contains("\"discountOfferId\"")
        && !compactSnapshot.contains("\"code\"");
  }

  static boolean providerConfirmsRetryIsSafe(String providerState) {
    return "RETRYABLE".equals(providerState);
  }

  static boolean reconciliationConfirmsExistingOrderVerified(Map<String,Object> reconciliation) {
    return reconciliation != null
        && "VERIFIED".equalsIgnoreCase(String.valueOf(reconciliation.getOrDefault("status","")))
        && Boolean.TRUE.equals(reconciliation.get("entitled"));
  }

  static boolean reconciliationConfirmsCheckoutRetryIsSafe(Map<String,Object> reconciliation) {
    return reconciliation != null
        && "AWAITING_PAYMENT".equalsIgnoreCase(String.valueOf(reconciliation.getOrDefault("status","")))
        && Boolean.FALSE.equals(reconciliation.get("entitled"))
        && Boolean.TRUE.equals(reconciliation.get("canRetryCheckout"));
  }

  private String inspectProviderOrderForNewAttempt(Map<String,Object> localOrder) {
    String providerOrderId = String.valueOf(localOrder.get("provider_order_id"));
    if (!providerOrderId.matches("order_[A-Za-z0-9]+")) return "IN_FLIGHT";
    JsonNode providerOrder = razorpayRequest("GET", "/orders/" + providerOrderId, null);
    long expectedAmount = ((Number) localOrder.get("offer_price_paise")).longValue();
    String currency = String.valueOf(localOrder.get("currency"));
    if (!providerOrderId.equals(providerOrder.path("id").asText(""))
        || providerOrder.path("amount").asLong(-1) != expectedAmount
        || !currency.equalsIgnoreCase(providerOrder.path("currency").asText(""))) {
      return "IN_FLIGHT";
    }
    if ("paid".equalsIgnoreCase(providerOrder.path("status").asText(""))
        || providerOrder.path("amount_paid").asLong(0) >= expectedAmount) return "PAID";

    JsonNode response = razorpayRequest("GET", "/orders/" + providerOrderId + "/payments", null);
    JsonNode items = response.path("items");
    if (!items.isArray()) return "IN_FLIGHT";
    boolean matchingPayment = false;
    for (JsonNode payment : items) {
      String paymentId = payment.path("id").asText("");
      if (!paymentId.matches("pay_[A-Za-z0-9]+")
          || !isPaymentForExactOrder(payment, paymentId, providerOrderId, expectedAmount, currency)) continue;
      matchingPayment = true;
      String status = payment.path("status").asText("").toLowerCase(Locale.ROOT);
      if (!Set.of("failed", "refunded", "cancelled", "voided").contains(status)) {
        return "IN_FLIGHT";
      }
    }
    if (!matchingPayment && providerOrder.path("attempts").asInt(0) > 0) return "IN_FLIGHT";
    String providerStatus = providerOrder.path("status").asText("").toLowerCase(Locale.ROOT);
    if (!Set.of("created", "attempted").contains(providerStatus)) return "IN_FLIGHT";
    return "RETRYABLE";
  }

  private Map<String,Object> verifyOrderInternal(
      long localOrderId, VerifyCoursePaymentRequest request, Long callerStudentId, Long callerUserId, long institutionId) {
    requireConfigured();
    String paymentId = request == null || request.paymentId() == null ? "" : request.paymentId().trim();
    String orderId = request == null || request.orderId() == null ? "" : request.orderId().trim();
    String signature = request == null || request.signature() == null ? "" : request.signature().trim();
    if (!paymentId.matches("pay_[A-Za-z0-9]+") || signature.isBlank()) {
      throw new IllegalArgumentException("The payment confirmation is incomplete. Please retry checkout verification.");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select id,student_id,purchaser_user_id,target_student_id,recipient_type,course_id,
          institution_id,institution_offer_id,course_identity_id,identity_contract_version,offer_price_paise,currency,provider_order_id,provider_payment_id,status,
          tax_profile_id,revenue_share_policy_id,
          tax_calculation_snapshot::text as tax_calculation_snapshot_json,
          revenue_share_policy_snapshot::text as revenue_share_policy_snapshot_json
        from course_payment_order
        where id=? and institution_id=?
          and ((? is not null and student_id=? and purchaser_user_id is null)
            or (? is not null and purchaser_user_id=?))
        for update
        """, localOrderId, institutionId, callerStudentId, callerStudentId, callerUserId, callerUserId);
    if (rows.isEmpty()) throw new SecurityException("This payment order is not available to this account.");
    Map<String, Object> order = rows.getFirst();
    String state = String.valueOf(order.get("status"));
    String savedPaymentId = (String) order.get("provider_payment_id");
    long courseId = ((Number) order.get("course_id")).longValue();
    if ("VERIFIED".equals(state)) {
      if (paymentId.equals(savedPaymentId)) return verifiedResult(localOrderId, courseId);
      throw new IllegalArgumentException("This order has already been verified against a different payment.");
    }
    if (!List.of("PENDING", "EXPIRED").contains(state)) {
      throw new IllegalArgumentException("This payment order is no longer payable.");
    }
    requireLockedFinancialSnapshotsForVerification(order);
    // Payment verification belongs to the exact historical offer on the saved order.
    // A later offer revision may archive this offer or withdraw its release while a valid
    // Razorpay payment is still settling. Current publication/fingerprint gates protect NEW
    // checkouts; they must not invalidate an already-created transaction's immutable terms.
    Object savedIdentityId = order.get("course_identity_id");
    Boolean originalOfferMatchesOrder = jdbc.queryForObject("""
        select exists(
          select 1
          from institution_marketplace_offer offer
          join institution_course_assignment assignment
            on assignment.id=offer.assignment_id
           and assignment.institution_id=offer.institution_id
          join institution_course_release release
            on release.id=offer.course_release_id
           and release.program_id=offer.course_id
          join institution_tenant tenant_scope
            on tenant_scope.id=offer.institution_id and tenant_scope.status='ACTIVE'
          join curriculum_program program on program.id=offer.course_id
          left join institution_course_identity identity on identity.id=program.course_identity_id
          where offer.id=? and offer.institution_id=? and offer.course_id=?
            and offer.hrms_published_by_staff_id is not null
            and (cast(? as bigint) is null or program.course_identity_id=cast(? as bigint))
            and (program.course_identity_id is null or (
              identity.source_institution_id=release.source_institution_id
              and identity.course_type_code=program.course_type_code
            ))
        )
        """, Boolean.class,
        ((Number) order.get("institution_offer_id")).longValue(), institutionId,
        ((Number) order.get("course_id")).longValue(), savedIdentityId, savedIdentityId);
    if (!Boolean.TRUE.equals(originalOfferMatchesOrder)) {
      throw new IllegalArgumentException("The saved checkout is not linked to its original institutional offer. No new payment was taken; finance must reconcile the saved order.");
    }
    String expectedOrderId = String.valueOf(order.get("provider_order_id"));
    if (!expectedOrderId.equals(orderId)) {
      throw new IllegalArgumentException("The payment order does not match. Please reopen the course checkout.");
    }
    String expectedSignature = hmacSha256(orderId + "|" + paymentId, keySecret);
    if (!constantTimeEquals(expectedSignature, signature)) {
      throw new IllegalArgumentException("Payment verification failed. No course access was granted.");
    }

    JsonNode payment = razorpayRequest("GET", "/payments/" + paymentId, null);
    long expectedAmount = ((Number) order.get("offer_price_paise")).longValue();
    String expectedCurrency = String.valueOf(order.get("currency"));
    if (!isPaymentForExactOrder(payment, paymentId, orderId, expectedAmount, expectedCurrency)) {
      throw new IllegalArgumentException("The payment ID, order, amount or currency does not match this course order. Course access remains locked.");
    }

    // Validate beneficiary and entitlement prerequisites before any external capture.
    // A local transaction rollback cannot undo a successful Razorpay capture.
    long targetStudentId = ((Number) order.get("student_id")).longValue();
    Long purchaserUserId = order.get("purchaser_user_id") == null
        ? null : ((Number) order.get("purchaser_user_id")).longValue();
    String recipientType = String.valueOf(order.get("recipient_type"));
    if ("CHILD".equals(recipientType)) {
      if (purchaserUserId == null) throw new SecurityException("The parent purchase identity is missing.");
      requireFullGuardianChildAccess(purchaserUserId,targetStudentId,institutionId);
    }
    requireStudentMembership(targetStudentId,institutionId);

    // Razorpay can return a successful checkout in the authorised state when automatic
    // capture is delayed or unavailable. Capture only after the signed callback, exact
    // persisted order, amount/currency and entitlement prerequisites have been checked.
    if ("authorized".equalsIgnoreCase(payment.path("status").asText(""))) {
      String captureRequest;
      try {
        captureRequest = objectMapper.writeValueAsString(
            Map.of("amount", expectedAmount, "currency", expectedCurrency));
      } catch (Exception exception) {
        throw new IllegalStateException("Payment capture could not be prepared. Retry verification; do not start another checkout.", exception);
      }
      try {
        razorpayRequest("POST", "/payments/" + paymentId + "/capture", captureRequest);
      } catch (IllegalStateException captureFailure) {
        // The capture may have succeeded even if the HTTP response was lost, or another
        // capture worker may have won the race. Re-read provider state before reporting
        // failure so an already-captured payment is still allowed to finish verification.
        JsonNode refreshed = razorpayRequest("GET", "/payments/" + paymentId, null);
        if (!isCapturedPaymentForExactOrder(refreshed, paymentId, orderId, expectedAmount, expectedCurrency)) {
          throw new IllegalStateException(
              "Razorpay has authorised this payment but capture is still pending. Retry verification; do not start another checkout.",
              captureFailure);
        }
        payment = refreshed;
      }
      if (!isCapturedPaymentForExactOrder(payment, paymentId, orderId, expectedAmount, expectedCurrency)) {
        payment = razorpayRequest("GET", "/payments/" + paymentId, null);
      }
    }
    if (!isCapturedPaymentForExactOrder(payment, paymentId, orderId, expectedAmount, expectedCurrency)) {
      throw new IllegalArgumentException(
          "The payment is not captured for this exact course order. Retry verification; do not start another checkout.");
    }

    Long purchaseId;
    try {
      purchaseId = jdbc.queryForObject("""
          insert into course_purchase(
            student_id,course_id,order_id,provider_payment_id,amount_paise,currency,purchaser_user_id,
            institution_id,institution_offer_id,course_identity_id,identity_contract_version
          ) values(?,?,?,?,?,?,?,?,?,?,?) returning id
          """, Long.class, targetStudentId, courseId, localOrderId,
          paymentId, expectedAmount, expectedCurrency, purchaserUserId, institutionId,
          ((Number) order.get("institution_offer_id")).longValue(),
          order.get("course_identity_id") instanceof Number identity ? identity.longValue() : null,
          order.get("identity_contract_version") instanceof Number contractVersion ? contractVersion.intValue() : 0);
    } catch (DuplicateKeyException ex) {
      // A concurrent payment or retry must never mint a second ownership record.
      List<Map<String,Object>> prior = jdbc.queryForList("""
        select id,provider_payment_id from course_purchase
        where institution_id=? and (order_id=? or (student_id=? and course_id=?))
        order by id limit 1
        """, institutionId, localOrderId, targetStudentId, courseId);
      if (prior.isEmpty() || !paymentId.equals(String.valueOf(prior.getFirst().get("provider_payment_id")))) {
        throw new IllegalStateException("This learner already has a purchase for the course. Contact support before retrying.", ex);
      }
      purchaseId = ((Number) prior.getFirst().get("id")).longValue();
    }

    Long entitlementOwnerUser = purchaserUserId != null && "STUDENT".equals(recipientType)
        ? purchaserUserId : null;
    jdbc.update("""
        insert into course_entitlement(
          course_id,source_purchase_id,holder_user_id,holder_student_id,granted_by_user_id,institution_id,
          course_identity_id,identity_contract_version
        ) values(?,?,?,?,?,?,?,?)
        on conflict(source_purchase_id) do nothing
        """, courseId, purchaseId, entitlementOwnerUser, targetStudentId, purchaserUserId, institutionId,
        order.get("course_identity_id") instanceof Number identity ? identity.longValue() : null,
        order.get("identity_contract_version") instanceof Number contractVersion ? contractVersion.intValue() : 0);

    // Course membership is switched server-side. A parent's self-purchase stays owned
    // by that parent and is assigned to their personal learning profile until transferred.
    // Purchase/transfer activates access to this course without changing other enrollments.
    jdbc.update("""
        insert into student_program_enrollment(student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at)
        values(?,?,?,'ACTIVE',now(),null,now(),now())
        on conflict(institution_id,student_id,program_id) do update
          set status='ACTIVE',
            enrolled_at=case when student_program_enrollment.status='ACTIVE'
              then student_program_enrollment.enrolled_at else now() end,
            ended_at=null,updated_at=now()
        """, targetStudentId, courseId, institutionId);
    jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,institution_id,status)
        select ?,s.id,?,'ACTIVE'
        from curriculum_program_subject ps
        join curriculum_program course_scope_program on course_scope_program.id=ps.program_id and course_scope_program.status='ACTIVE'
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
          and (s.program_id=ps.program_id or (s.program_id is null and course_scope_program.class_id=s.class_id and ps.class_id=s.class_id))
        where ps.program_id=?
        on conflict(institution_id,student_id,subject_id) do update
          set status='ACTIVE',updated_at=now()
        """, targetStudentId, institutionId, courseId);

    if (purchaserUserId != null && "CHILD".equals(recipientType)) {
      jdbc.update("""
        update parent_course_purchase_request
        set status='PURCHASED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
        where guardian_user_id=? and student_id=? and institution_id=? and course_id=? and status='PENDING'
        """, purchaserUserId, purchaserUserId, targetStudentId, institutionId, courseId);
    }
    CourseOrderFinancialService.FinalizedSale finalizedSale =
        courseOrderFinancialService.finalizeCapturedSale(localOrderId,paymentId,expectedAmount);
    int finalizedRows=jdbc.update("""
        update course_payment_order set status='VERIFIED',provider_payment_id=?,provider_signature=?,
          verified_at=now(),captured_amount_paise=?,captured_at=now(),fiscal_document_id=?,updated_at=now()
        where id=? and institution_id=? and status in ('PENDING','EXPIRED')
        """, paymentId, signature, expectedAmount, finalizedSale.fiscalDocumentId(),localOrderId,institutionId);
    if(finalizedRows!=1) throw new IllegalStateException("The course order changed while finalizing its fiscal document.");
    Map<String,Object> result = new LinkedHashMap<>(verifiedResult(localOrderId, courseId));
    result.put("purchasedFor", "CHILD".equals(recipientType) ? "CHILD"
        : purchaserUserId == null ? "LEARNER" : "PARENT");
    result.put("studentId", targetStudentId);
    result.put("invoiceDocumentId", finalizedSale.fiscalDocumentId());
    result.put("invoiceNumber", finalizedSale.documentNumber());
    result.put("institutionSharePaise", finalizedSale.allocatedToInstitutionsPaise());
    result.put("platformRemainderPaise", finalizedSale.platformRemainderPaise());
    result.put("transferable", purchaserUserId != null && "STUDENT".equals(recipientType));
    return result;
  }

  /**
   * @deprecated Historical platform-wide Marketplace editor. Learner-facing offers are now
   * institution-scoped and may only be published through the HRMS launch stepper.
   */
  @Deprecated(since="2026-10", forRemoval=false)
  @GetMapping("/admin/courses")
  public List<Map<String, Object>> adminCourses(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    throw legacyGlobalMarketplaceGone();
  }

  /**
   * @deprecated Historical platform-wide offer mutation endpoint. It cannot approve or publish
   * the current tenant-scoped Marketplace catalogue.
   */
  @Deprecated(since="2026-10", forRemoval=false)
  @PutMapping("/admin/courses/{courseId}")
  public Map<String, Object> saveOffer(
      @PathVariable long courseId,
      @RequestBody SaveCourseOfferRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    throw legacyGlobalMarketplaceGone();
  }

  private ResponseStatusException legacyGlobalMarketplaceGone() {
    return new ResponseStatusException(HttpStatus.GONE,
        "DEPRECATED_GLOBAL_MARKETPLACE: Configure and publish institution offers through the HRMS Marketplace launch stepper. The legacy global editor is retained temporarily for migration compatibility.");
  }

  private InstitutionTenantContext requireTenant(InstitutionTenantContext tenant) {
    if (tenant == null) {
      throw new InstitutionTenantContextService.InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "Select the institution before using course commerce.");
    }
    return tenant;
  }

  /**
   * A parent who is already a member of this institution through a consented child may explicitly
   * create their own learner profile there. It does not enroll children or arbitrary accounts.
   */
  private long ensureCourseMembership(long studentId,long institutionId,Long actorStudentId,
      Long actorUserId,String source,long courseId) {
    List<Map<String,Object>> previous=jdbc.queryForList("""
      select id,status from institution_membership
      where institution_id=? and student_id=? for update
      """,institutionId,studentId);
    String previousStatus=previous.isEmpty()?"":String.valueOf(previous.getFirst().get("status"));
    if("SUSPENDED".equals(previousStatus)) {
      throw new SecurityException("Membership in the selected institution is suspended. Contact the institution administrator.");
    }
    Boolean authorized=jdbc.queryForObject("""
      select exists(select 1 from institution_membership m
        join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
        join guardian_student gs on gs.student_id=m.student_id
          and gs.guardian_user_id=? and gs.active=true and gs.consent_status='CONSENTED'
        where m.institution_id=? and m.status='ACTIVE')
      """,Boolean.class,actorUserId,institutionId);
    if(!Boolean.TRUE.equals(authorized)) throw new SecurityException(
        "A parent must already be linked to a learner in the selected institution before creating a personal learner profile.");
    Long membershipId=jdbc.queryForObject("""
      insert into institution_membership(
        institution_id,student_id,join_code_id,status,joined_at,left_at,created_at,updated_at
      ) values(?,?,null,'ACTIVE',now(),null,now(),now())
      on conflict(institution_id,student_id) do update set
        status='ACTIVE',joined_at=case when institution_membership.status='ACTIVE'
          then institution_membership.joined_at else now() end,
        left_at=null,updated_at=now()
      returning id
      """,Long.class,institutionId,studentId);
    if(membershipId==null)throw new IllegalStateException("Institution membership could not be recorded.");
    if(previous.isEmpty()) {
      jdbc.update("""
        insert into institution_membership_event(
          institution_id,membership_id,student_id,event_type,actor_student_id,details
        ) values(?,?,?,'MEMBERSHIP_CREATED',?,jsonb_build_object('source',?,'courseId',?,'actorUserId',?))
        """,institutionId,membershipId,studentId,actorStudentId,source,courseId,actorUserId);
    } else if(!"ACTIVE".equals(previousStatus)) {
      jdbc.update("""
        insert into institution_membership_event(
          institution_id,membership_id,student_id,event_type,actor_student_id,details
        ) values(?,?,?,'MEMBERSHIP_REACTIVATED',?,jsonb_build_object('source',?,'courseId',?,'actorUserId',?))
        """,institutionId,membershipId,studentId,actorStudentId,source,courseId,actorUserId);
    }
    return membershipId;
  }

  private boolean hasAccessInInstitution(long studentId, long courseId, long institutionId) {
    Boolean value = jdbc.queryForObject("""
        select exists(
          select 1 from student_program_enrollment e
          where e.student_id=? and e.institution_id=? and qe_same_logical_course(e.program_id,?) and e.status in ('ACTIVE','PAUSED')
          union all
          select 1 from course_purchase p
          where p.student_id=? and p.institution_id=? and qe_same_logical_course(p.course_id,?)
          union all
          select 1 from course_entitlement ce
          where ce.institution_id=? and qe_same_logical_course(ce.course_id,?) and (ce.holder_student_id=? or ce.holder_user_id=(select user_account_id from student where id=?))
        )
        """, Boolean.class, studentId, institutionId, courseId, studentId, institutionId, courseId,
        institutionId, courseId, studentId, studentId);
    return Boolean.TRUE.equals(value);
  }

  private boolean hasAccess(long studentId, long courseId) {
    Boolean value = jdbc.queryForObject("""
        select exists(
          select 1 from student_program_enrollment e
          where e.student_id=? and e.program_id=? and e.status in ('ACTIVE','PAUSED')
          union all
          select 1 from course_purchase p where p.student_id=? and p.course_id=?
        )
        """, Boolean.class, studentId, courseId, studentId, courseId);
    return Boolean.TRUE.equals(value);
  }

  static boolean isPaymentForExactOrder(
      JsonNode payment, String paymentId, String orderId, long amount, String currency) {
    return payment != null
        && paymentId != null && paymentId.equals(payment.path("id").asText(""))
        && orderId != null && orderId.equals(payment.path("order_id").asText(""))
        && payment.path("amount").asLong(-1) == amount
        && currency != null && currency.equalsIgnoreCase(payment.path("currency").asText(""));
  }

  static boolean isCapturedPaymentForExactOrder(
      JsonNode payment, String paymentId, String orderId, long amount, String currency) {
    return isPaymentForExactOrder(payment, paymentId, orderId, amount, currency)
        && "captured".equalsIgnoreCase(payment.path("status").asText(""));
  }

  private Map<String, Object> verifiedResult(long localOrderId, long courseId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "VERIFIED");
    result.put("entitled", true);
    result.put("orderId", localOrderId);
    result.put("courseId", courseId);
    List<Map<String,Object>> invoiceRows=jdbc.queryForList("""
      select d.id as invoice_document_id,d.document_number,d.total_value_paise,d.tax_value_paise
      from course_payment_order o left join fiscal_document d on d.id=o.fiscal_document_id
      where o.id=? and o.status='VERIFIED'
      """,localOrderId);
    if(!invoiceRows.isEmpty()&&invoiceRows.getFirst().get("invoice_document_id")!=null) {
      Map<String,Object> invoice=invoiceRows.getFirst();
      result.put("invoiceDocumentId",invoice.get("invoice_document_id"));
      result.put("invoiceNumber",invoice.get("document_number"));
      result.put("invoiceTotalPaise",invoice.get("total_value_paise"));
      result.put("invoiceTaxPaise",invoice.get("tax_value_paise"));
    }
    return result;
  }

  /**
   * Read-only Razorpay inspection for the HRMS delivery-chain view. This deliberately does not
   * reconcile, capture, mark paid, repair snapshots, grant access, or issue documents.
   *
   * The active checkout credentials are tried first, followed by optional test/live credentials.
   * A result is trusted only when the provider returns the exact saved order ID. For legacy orders
   * created before provider mode was recorded, checking both configured modes avoids guessing.
   */
  public Map<String,Object> inspectProviderStatusForHrms(
      String providerOrderId,long expectedAmountPaise,String expectedCurrency) {
    Map<String,Object> unavailable=new LinkedHashMap<>();
    unavailable.put("providerQueryAvailable",false);
    unavailable.put("providerOrderId",providerOrderId==null?"":providerOrderId);
    unavailable.put("configuredProviderMode",configuredProviderMode());
    if(providerOrderId==null||!providerOrderId.matches("order_[A-Za-z0-9]+")) {
      unavailable.put("providerStatus","NO_PROVIDER_REFERENCE");
      unavailable.put("message","This local order has no valid saved Razorpay order reference. Local transaction stages are still shown.");
      unavailable.put("credentialModesTried",List.of());
      return unavailable;
    }
    List<ProviderCredential> credentials=providerCredentialsForInspection();
    List<String> modesTried=new ArrayList<>();
    String lastError="";
    Map<String,Object> amountMismatch=null;
    for(ProviderCredential credential:credentials) {
      modesTried.add(credential.mode());
      try {
        JsonNode providerOrder=razorpayReadRequest(
            "/orders/"+providerOrderId,credential.keyId(),credential.keySecret());
        String returnedId=providerOrder.path("id").asText("");
        if(!providerOrderId.equals(returnedId)) continue;
        String returnedCurrency=providerOrder.path("currency").asText("");
        long returnedAmount=providerOrder.path("amount").asLong(-1);
        boolean amountMatches=returnedAmount==expectedAmountPaise
            && expectedCurrency!=null&&expectedCurrency.equalsIgnoreCase(returnedCurrency);
        JsonNode paymentResponse=razorpayReadRequest(
            "/orders/"+providerOrderId+"/payments",credential.keyId(),credential.keySecret());
        JsonNode items=paymentResponse.path("items");
        Map<String,Object> result=new LinkedHashMap<>(providerPaymentDiagnostics(
            providerOrder,items,providerOrderId,expectedAmountPaise,expectedCurrency));
        result.put("providerQueryAvailable",true);
        result.put("configuredProviderMode",configuredProviderMode());
        result.put("matchedProviderMode",credential.mode());
        result.put("credentialModesTried",new ArrayList<>(modesTried));
        result.put("providerOrderMatches",true);
        result.put("providerAmountPaise",returnedAmount);
        result.put("providerCurrency",returnedCurrency);
        result.put("providerAmountCurrencyMatch",amountMatches);
        result.put("providerStatus",providerOutcome(providerOrder,items,providerOrderId,
            expectedAmountPaise,expectedCurrency,amountMatches));
        result.put("message",amountMatches
            ? "Razorpay was queried using "+credential.mode()+" credentials for this exact order. No transaction data was changed."
            : "The Razorpay order exists, but its amount/currency does not match the saved QuantaEdge order. No transaction data was changed.");
        if(amountMatches) return result;
        amountMismatch=result;
      } catch(RuntimeException providerError) {
        lastError=safeProviderDiagnosticText(providerError.getMessage(),240);
      }
    }
    if(amountMismatch!=null) return amountMismatch;
    unavailable.put("providerStatus","UNCONFIRMED");
    unavailable.put("credentialModesTried",modesTried);
    unavailable.put("message",lastError.isBlank()
        ? "Razorpay could not confirm this order using the configured credentials. Check that the matching test/live key pair is configured; no transaction data was changed."
        : "Razorpay could not confirm this order using the configured credentials ("+lastError+"). Check that the matching test/live key pair is configured; no transaction data was changed.");
    return unavailable;
  }

  private String providerOutcome(JsonNode providerOrder,JsonNode paymentItems,String orderId,
      long expectedAmount,String expectedCurrency,boolean amountMatches) {
    if(!amountMatches) return "ORDER_AMOUNT_CURRENCY_MISMATCH";
    if(paymentItems!=null&&paymentItems.isArray()) {
      for(JsonNode payment:paymentItems) {
        String paymentId=payment.path("id").asText("");
        if(!paymentId.matches("pay_[A-Za-z0-9]+")
            ||!isPaymentForExactOrder(payment,paymentId,orderId,expectedAmount,expectedCurrency)) continue;
        String status=payment.path("status").asText("").toLowerCase(Locale.ROOT);
        if("captured".equals(status)) return "CAPTURED";
      }
      for(JsonNode payment:paymentItems) {
        String paymentId=payment.path("id").asText("");
        if(!paymentId.matches("pay_[A-Za-z0-9]+")
            ||!isPaymentForExactOrder(payment,paymentId,orderId,expectedAmount,expectedCurrency)) continue;
        String status=payment.path("status").asText("").toLowerCase(Locale.ROOT);
        if("authorized".equals(status)) return "AUTHORIZED";
        if(Set.of("created","processing").contains(status)) return "PROCESSING";
      }
    }
    if("paid".equalsIgnoreCase(providerOrder.path("status").asText(""))
        ||providerOrder.path("amount_paid").asLong(0)>=expectedAmount) return "PAID_NO_EXACT_PAYMENT";
    return "NOT_PAID";
  }

  private String configuredProviderMode() {
    if(isRazorpayTestModeKey(keyId)) return "TEST";
    if(keyId!=null&&keyId.trim().matches("^rzp_live_[A-Za-z0-9]+$")) return "LIVE";
    return keyId==null||keyId.isBlank()?"UNCONFIGURED":"UNKNOWN";
  }

  private List<ProviderCredential> providerCredentialsForInspection() {
    Map<String,ProviderCredential> byKeyId=new LinkedHashMap<>();
    addProviderCredential(byKeyId,keyId,keySecret,configuredProviderMode());
    addProviderCredential(byKeyId,testModeKeyId,testModeKeySecret,"TEST");
    addProviderCredential(byKeyId,liveModeKeyId,liveModeKeySecret,"LIVE");
    return new ArrayList<>(byKeyId.values());
  }

  private static void addProviderCredential(
      Map<String,ProviderCredential> credentials,String suppliedKeyId,String suppliedSecret,String mode) {
    String id=suppliedKeyId==null?"":suppliedKeyId.trim();
    String secret=suppliedSecret==null?"":suppliedSecret.trim();
    if(!id.isBlank()&&!secret.isBlank()) credentials.putIfAbsent(id,new ProviderCredential(mode,id,secret));
  }

  private JsonNode razorpayReadRequest(String path,String requestKeyId,String requestKeySecret) {
    if(requestKeyId==null||requestKeyId.isBlank()||requestKeySecret==null||requestKeySecret.isBlank())
      throw new IllegalStateException("Matching provider credentials are not configured.");
    try {
      String basic=Base64.getEncoder().encodeToString(
          (requestKeyId+":"+requestKeySecret).getBytes(StandardCharsets.UTF_8));
      HttpRequest request=HttpRequest.newBuilder(URI.create(baseUrl+path))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Authorization","Basic "+basic)
          .header("Accept","application/json")
          .GET().build();
      HttpResponse<String> response=httpClient.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if(response.statusCode()<200||response.statusCode()>=300)
        throw new IllegalStateException("provider returned HTTP "+response.statusCode());
      return objectMapper.readTree(response.body());
    } catch(IllegalStateException exception) {
      throw exception;
    } catch(Exception exception) {
      throw new IllegalStateException("provider could not be reached",exception);
    }
  }

  private record ProviderCredential(String mode,String keyId,String keySecret) {}

  private void requireConfigured() {
    if (keyId.isBlank() || keySecret.isBlank()) {
      throw new IllegalStateException("Course checkout is not available right now. Please try again later.");
    }
  }

  private JsonNode razorpayRequest(String method, String path, String body) {
    requireConfigured();
    try {
      String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(java.time.Duration.ofSeconds(15))
          .header("Authorization", "Basic " + basic)
          .header("Accept", "application/json");
      if ("GET".equalsIgnoreCase(method)) {
        builder.GET();
      } else if ("POST".equalsIgnoreCase(method)) {
        builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
      } else {
        throw new IllegalArgumentException("Unsupported payment-provider request method.");
      }
      HttpResponse<String> response = httpClient.send(builder.build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Payment provider could not complete the request (HTTP "
            + response.statusCode() + ").");
      }
      return objectMapper.readTree(response.body());
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Payment provider could not be reached. Please retry.", exception);
    }
  }

  static String hmacSha256(String data, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Payment verification is unavailable.", exception);
    }
  }

  private static boolean constantTimeEquals(String expected, String supplied) {
    if (expected == null || supplied == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        supplied.getBytes(StandardCharsets.US_ASCII));
  }

  public record VerifyCoursePaymentRequest(String paymentId, String orderId, String signature) {}
  public record CourseOrderRequest(String discountCode, Long billingAddressId) {
    public CourseOrderRequest(String discountCode) { this(discountCode,null); }
  }
  public record FamilyCourseOrderRequest(String recipientType, Long studentId, String discountCode, Long billingAddressId) {
    public FamilyCourseOrderRequest(String recipientType, Long studentId) { this(recipientType,studentId,null,null); }
    public FamilyCourseOrderRequest(String recipientType, Long studentId, String discountCode) { this(recipientType,studentId,discountCode,null); }
  }
  public record SaveCourseOfferRequest(String title, String description, long pricePaise, String currency, String status) {}
}
