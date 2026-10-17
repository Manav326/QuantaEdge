package com.quantaedge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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
  private final String keyId;
  private final String keySecret;
  private final String baseUrl;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(5)).build();

  public CourseMarketplaceController(
      JdbcTemplate jdbc,
      AuthorizationService authorization,
      ObjectMapper objectMapper,
      @Value("${app.razorpay.key-id:}") String keyId,
      @Value("${app.razorpay.key-secret:}") String keySecret,
      @Value("${app.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.objectMapper = objectMapper;
    this.keyId = keyId == null ? "" : keyId.trim();
    this.keySecret = keySecret == null ? "" : keySecret.trim();
    this.baseUrl = (baseUrl == null ? "https://api.razorpay.com/v1" : baseUrl.trim()).replaceAll("/+$", "");
  }

  /** Only explicitly published offers are discoverable; this is not a list of every curriculum row. */
  @GetMapping("/courses")
  public List<Map<String, Object>> courses(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    List<Map<String, Object>> offers = jdbc.queryForList("""
        select p.id as course_id, p.public_id, p.code as course_code,
          coalesce(nullif(o.title_override,''),p.display_name) as title,
          p.display_name as course_name, p.curriculum_version,
          o.description, o.price_paise, o.currency, o.status as offer_status,
          exists(select 1 from student_program_enrollment e
            where e.student_id=? and e.program_id=p.id and e.status in ('ACTIVE','PAUSED')) as has_access,
          exists(select 1 from course_purchase cp
            where cp.student_id=? and cp.course_id=p.id) as purchased,
          exists(select 1 from student_login_credential c
            where c.student_id=? and c.active=true) as parent_managed
        from course_marketplace_offer o
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where o.status='PUBLISHED'
        order by p.display_name,p.id
        """, context.studentId(), context.studentId(), context.studentId());
    for (Map<String, Object> offer : offers) {
      long courseId = ((Number) offer.get("course_id")).longValue();
      offer.put("subjects", jdbc.queryForList("""
          select s.code as subject_code, s.display_name as subject_name,
            count(distinct ch.id) as chapters, count(distinct l.id) as lessons
          from curriculum_program_subject ps
          join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
            and s.class_id=ps.class_id and s.active=true
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
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
    long parentId = context.userId();
    List<Map<String,Object>> offers = jdbc.queryForList("""
      select p.id as course_id,p.public_id,p.code as course_code,
        coalesce(nullif(o.title_override,''),p.display_name) as title,
        p.display_name as course_name,p.curriculum_version,o.description,
        o.price_paise,o.currency,o.status as offer_status,
        (exists(select 1 from course_entitlement ce
          where ce.course_id=p.id and ce.holder_user_id=?)
         or exists(select 1 from student st join student_program_enrollment e on e.student_id=st.id
          where st.user_account_id=? and e.program_id=p.id and e.status in ('ACTIVE','PAUSED'))) as owned_by_parent,
        (exists(select 1 from course_entitlement ce
          join guardian_student gs on gs.student_id=ce.holder_student_id
          where ce.course_id=p.id and gs.guardian_user_id=?
            and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL')
         or exists(select 1 from guardian_student gs
          join student_program_enrollment e on e.student_id=gs.student_id
          where gs.guardian_user_id=? and e.program_id=p.id and e.status in ('ACTIVE','PAUSED')
            and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL')) as owned_by_child,
        (select coalesce(string_agg(distinct gs.student_id::text, ','), '')
         from guardian_student gs
         where gs.guardian_user_id=? and gs.active=true
           and gs.consent_status='CONSENTED' and gs.access_level='FULL'
           and (exists(select 1 from course_entitlement ce
                 where ce.course_id=p.id and ce.holder_student_id=gs.student_id)
             or exists(select 1 from student_program_enrollment e
                 where e.student_id=gs.student_id and e.program_id=p.id and e.status in ('ACTIVE','PAUSED'))
           )) as owned_child_ids
      from course_marketplace_offer o
      join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
      where o.status='PUBLISHED'
      order by p.display_name,p.id
      """, parentId, parentId, parentId, parentId, parentId);
    for (Map<String,Object> offer : offers) {
      long courseId = ((Number) offer.get("course_id")).longValue();
      offer.put("subjects", jdbc.queryForList("""
        select s.code as subject_code,s.display_name as subject_name,
          count(distinct ch.id) as chapters,count(distinct l.id) as lessons
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
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

  /** Add a published free course to the parent's own or linked child's learning profile. */
  @PostMapping("/family/courses/{courseId}/enroll-free")
  @Transactional
  public Map<String,Object> enrollFreeFamilyCourse(
      @PathVariable long courseId,
      @RequestBody(required=false) FamilyCourseOrderRequest request,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
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
      requireFullGuardianChildAccess(context.userId(), studentId);
    } else {
      throw new IllegalArgumentException("Choose whether this course is for you or a linked child.");
    }

    Boolean freeAndPublished = jdbc.queryForObject("""
      select exists(
        select 1 from course_marketplace_offer o
        join curriculum_program p on p.id=o.course_id and p.status='ACTIVE' and p.code<>'GENERAL'
        where o.course_id=? and o.status='PUBLISHED' and o.price_paise=0 and o.currency='INR'
      )
      """, Boolean.class, courseId);
    if (!Boolean.TRUE.equals(freeAndPublished)) {
      throw new IllegalArgumentException("This course is not currently available as a free offer.");
    }
    if (hasAccess(studentId, courseId)) throw new IllegalArgumentException("The selected learner already has access to this course.");

    List<Map<String,Object>> subjects = jdbc.queryForList("""
      select s.id from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
        and s.class_id=ps.class_id and s.active=true
      where ps.program_id=? order by ps.sort_order,s.sort_order,s.id
      """, courseId);
    if (subjects.isEmpty()) throw new IllegalStateException("This course has no active subject tracks yet.");

    jdbc.update("""
      update student_program_enrollment set status='PAUSED',updated_at=now()
      where student_id=? and status='ACTIVE' and program_id<>?
      """, studentId, courseId);
    jdbc.update("""
      insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
      values(?,?,'ACTIVE',now(),null,now(),now())
      on conflict(student_id,program_id) do update set
        status='ACTIVE',enrolled_at=case when student_program_enrollment.status='ACTIVE'
          then student_program_enrollment.enrolled_at else now() end,
        ended_at=null,updated_at=now()
      """, studentId, courseId);
    jdbc.update("""
      insert into student_track_enrollment(student_id,subject_id,status)
      select ?,s.id,'ACTIVE'
      from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
        and s.class_id=ps.class_id and s.active=true
      where ps.program_id=?
      on conflict(student_id,subject_id) do update set status='ACTIVE',updated_at=now()
      """, studentId, courseId);
    if ("CHILD".equals(recipient)) {
      jdbc.update("""
        update parent_course_purchase_request
        set status='PURCHASED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
        where guardian_user_id=? and student_id=? and course_id=? and status='PENDING'
        """, context.userId(), context.userId(), studentId, courseId);
    }
    return Map.of("enrolled", true, "studentId", studentId, "courseId", courseId,
        "recipientType", recipient, "free", true);
  }

  /** Only a parent's own, not-yet-transferred purchases are eligible for assignment to a child. */
  @GetMapping("/family/entitlements")
  public List<Map<String,Object>> familyEntitlements(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
    return jdbc.queryForList("""
      select ce.id as entitlement_id,ce.course_id,ce.source_purchase_id,
        ce.holder_student_id,st.display_name as current_learner_name,
        ce.transfer_count,ce.created_at,p.public_id as course_public_id,
        coalesce(nullif(o.title_override,''),p.display_name) as course_name
      from course_entitlement ce
      join curriculum_program p on p.id=ce.course_id
      left join course_marketplace_offer o on o.course_id=p.id
      left join student st on st.id=ce.holder_student_id
      where ce.holder_user_id=? and ce.transfer_count=0
        and p.status='ACTIVE' and p.code<>'GENERAL'
      order by ce.created_at desc,ce.id desc
      """, context.userId());
  }

  /** One-way transfer. The parent's owner reference is removed permanently; the child becomes the sole holder. */
  @PostMapping("/family/entitlements/{entitlementId}/transfer")
  @Transactional
  public Map<String,Object> transferEntitlement(
      @PathVariable long entitlementId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
    Object raw = body.get("studentId");
    if (!(raw instanceof Number number) || number.longValue() <= 0) {
      throw new IllegalArgumentException("Choose a linked child to receive this course.");
    }
    long targetStudentId = number.longValue();
    long parentId = context.userId();
    requireFullGuardianChildAccess(parentId, targetStudentId);

    List<Map<String,Object>> rows = jdbc.queryForList("""
      select ce.id,ce.course_id,ce.source_purchase_id,ce.holder_student_id,
        ce.transfer_count,p.display_name as course_name
      from course_entitlement ce
      join curriculum_program p on p.id=ce.course_id and p.status='ACTIVE'
      where ce.id=? and ce.holder_user_id=? and ce.transfer_count=0
      for update of ce
      """, entitlementId, parentId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("This course is not owned by your account or has already been transferred.");
    }
    Map<String,Object> entitlement = rows.getFirst();
    if (entitlement.get("source_purchase_id") == null) {
      throw new IllegalStateException("This entitlement has no purchase ledger record and cannot be transferred safely.");
    }
    long courseId = ((Number) entitlement.get("course_id")).longValue();
    long purchaseId = ((Number) entitlement.get("source_purchase_id")).longValue();
    long currentStudentId = ((Number) entitlement.get("holder_student_id")).longValue();
    if (currentStudentId == targetStudentId) {
      throw new IllegalArgumentException("This course is already assigned to that learner.");
    }
    if (hasAccess(targetStudentId, courseId)) {
      throw new IllegalArgumentException("That learner already has access to this course. No transfer was made.");
    }

    try {
      jdbc.update("""
        update course_purchase set student_id=?
        where id=? and student_id=? and course_id=?
        """, targetStudentId, purchaseId, currentStudentId, courseId);
    } catch (DuplicateKeyException ex) {
      throw new IllegalArgumentException("That child already has a purchase for this course. No transfer was made.", ex);
    }
    int changed = jdbc.update("""
      update course_entitlement
      set holder_user_id=null,holder_student_id=?,transfer_count=1,
          transferred_at=now(),updated_at=now()
      where id=? and holder_user_id=? and transfer_count=0
      """, targetStudentId, entitlementId, parentId);
    if (changed != 1) throw new IllegalStateException("The entitlement changed while transferring. Reload and try again.");

    jdbc.update("""
      update student_program_enrollment
      set status='ENDED',ended_at=now(),updated_at=now()
      where student_id=? and program_id=? and status in ('ACTIVE','PAUSED')
      """, currentStudentId, courseId);
    jdbc.update("""
      update student_program_enrollment set status='PAUSED',updated_at=now()
      where student_id=? and status='ACTIVE' and program_id<>?
      """, targetStudentId, courseId);
    jdbc.update("""
      insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
      values(?,?,'ACTIVE',now(),null,now(),now())
      on conflict(student_id,program_id) do update set
        status='ACTIVE',enrolled_at=case when student_program_enrollment.status='ACTIVE'
          then student_program_enrollment.enrolled_at else now() end,
        ended_at=null,updated_at=now()
      """, targetStudentId, courseId);
    jdbc.update("""
      insert into student_track_enrollment(student_id,subject_id,status)
      select ?,s.id,'ACTIVE'
      from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
        and s.class_id=ps.class_id and s.active=true
      where ps.program_id=?
      on conflict(student_id,subject_id) do update set status='ACTIVE',updated_at=now()
      """, targetStudentId, courseId);
    jdbc.update("""
      insert into course_entitlement_transfer_audit(
        entitlement_id,course_id,from_user_id,to_student_id,transfer_number
      ) values(?,?,?,?,1)
      """, entitlementId, courseId, parentId, targetStudentId);

    return Map.of("transferred", true, "permanent", true, "revocable", false,
        "entitlementId", entitlementId, "courseId", courseId, "studentId", targetStudentId);
  }

  /** Create an order for an already authenticated learner. Amount and recipient always come from server records. */
  @PostMapping("/courses/{courseId}/orders")
  public Map<String, Object> createOrder(
      @PathVariable long courseId,
      @RequestHeader(value = "Idempotency-Key", required = false) String suppliedIdempotencyKey,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    String key = normalizeIdempotencyKey(suppliedIdempotencyKey);
    return createOrderInternal(courseId, key, context.studentId(), null, null, "STUDENT");
  }

  /** Parents can buy for their own learning profile or directly for a linked child. */
  @PostMapping("/family/courses/{courseId}/orders")
  public Map<String, Object> createFamilyOrder(
      @PathVariable long courseId,
      @RequestHeader(value = "Idempotency-Key", required = false) String suppliedIdempotencyKey,
      @RequestBody(required = false) FamilyCourseOrderRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireParent(context);
    String key = normalizeIdempotencyKey(suppliedIdempotencyKey);
    String recipient = request == null || request.recipientType() == null
        ? "" : request.recipientType().trim().toUpperCase(Locale.ROOT);
    long targetStudentId;
    String orderRecipientType;
    if ("SELF".equals(recipient)) {
      targetStudentId = ensurePersonalLearningProfile(context.userId());
      // STUDENT recipient means a learner profile is being funded. purchaser_user_id
      // separately retains the parent's ownership so it can be transferred later.
      orderRecipientType = "STUDENT";
    } else if ("CHILD".equals(recipient)) {
      if (request.studentId() == null || request.studentId() <= 0) {
        throw new IllegalArgumentException("Choose a linked child before purchasing this course.");
      }
      targetStudentId = request.studentId();
      requireFullGuardianChildAccess(context.userId(), targetStudentId);
      orderRecipientType = "CHILD";
    } else {
      throw new IllegalArgumentException("Choose whether this course is for you or a linked child.");
    }
    return createOrderInternal(courseId, key, targetStudentId, context.userId(),
        targetStudentId, orderRecipientType);
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

  private void requireFullGuardianChildAccess(long parentUserId, long studentId) {
    Boolean allowed = jdbc.queryForObject("""
      select exists(
        select 1 from guardian_student gs
        join student st on st.id=gs.student_id
        where gs.guardian_user_id=? and gs.student_id=?
          and gs.active=true and gs.consent_status='CONSENTED' and gs.access_level='FULL'
          and st.active=true and st.environment='PRODUCTION'
      )
      """, Boolean.class, parentUserId, studentId);
    if (!Boolean.TRUE.equals(allowed)) throw new SecurityException("Full parent access to this child is required.");
  }

  private Map<String, Object> createOrderInternal(
      long courseId, String idempotencyKey, long targetStudentId,
      Long purchaserUserId, Long targetStudentForOrder, String recipientType) {
    requireConfigured();
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select p.id, coalesce(nullif(o.title_override,''),p.display_name) as title,
          o.price_paise, o.currency, o.description
        from course_marketplace_offer o
        join curriculum_program p on p.id=o.course_id
        where p.id=? and p.status='ACTIVE' and p.code<>'GENERAL'
          and o.status='PUBLISHED' and o.price_paise>0
        """, courseId);
    if (rows.isEmpty()) throw new IllegalArgumentException("This paid course is not currently available.");
    if (hasAccess(targetStudentId, courseId)) {
      throw new IllegalArgumentException("The selected learner already has access to this course.");
    }
    if (purchaserUserId != null) {
      Boolean alreadyOwnedByParent = jdbc.queryForObject("""
        select exists(select 1 from course_entitlement
          where holder_user_id=? and course_id=?)
        """, Boolean.class, purchaserUserId, courseId);
      if (Boolean.TRUE.equals(alreadyOwnedByParent)) {
        throw new IllegalArgumentException("You already own this course. Open your courses or transfer it to a linked child.");
      }
    }
    Map<String, Object> offer = rows.getFirst();
    long amountPaise = ((Number) offer.get("price_paise")).longValue();
    String currency = String.valueOf(offer.get("currency")).toUpperCase(Locale.ROOT);
    String title = String.valueOf(offer.getOrDefault("title", "Course access"));

    jdbc.update("""
        update course_payment_order set status='EXPIRED',updated_at=now()
        where student_id=? and course_id=? and purchaser_user_id is not distinct from ?
          and status in ('CREATING','PENDING')
          and created_at < now() - (? * interval '1 minute')
        """, targetStudentId, courseId, purchaserUserId, ORDER_EXPIRY_MINUTES);

    List<Map<String, Object>> keyedOrders = jdbc.queryForList("""
        select id,offer_price_paise,currency,provider_order_id,status
        from course_payment_order
        where student_id=? and course_id=? and client_request_key=?
          and purchaser_user_id is not distinct from ?
        """, targetStudentId, courseId, idempotencyKey, purchaserUserId);
    if (!keyedOrders.isEmpty()) {
      Map<String, Object> previous = keyedOrders.getFirst();
      String previousStatus = String.valueOf(previous.get("status"));
      if ("PENDING".equals(previousStatus)) return checkoutResponse(previous, courseId, title);
      if ("CREATING".equals(previousStatus)) {
        throw new IllegalStateException("Your course checkout is being prepared. Please retry shortly.");
      }
      throw new IllegalArgumentException("This checkout request has already ended. Start a new checkout from the marketplace.");
    }

    List<Map<String, Object>> open = openOrders(targetStudentId, courseId, purchaserUserId);
    if (!open.isEmpty()) {
      Map<String, Object> prior = open.getFirst();
      if ("PENDING".equals(String.valueOf(prior.get("status")))) {
        return checkoutResponse(prior, courseId, title);
      }
      throw new IllegalStateException("Your course checkout is being prepared. Please retry shortly.");
    }

    String receipt = "qe_" + UUID.randomUUID().toString().replace("-", "");
    long localOrderId;
    try {
      localOrderId = jdbc.queryForObject("""
          insert into course_payment_order(
            student_id,purchaser_user_id,target_student_id,recipient_type,
            course_id,offer_price_paise,currency,receipt,client_request_key,status
          ) values(?,?,?,?,?,?,?,?,?,'CREATING')
          returning id
          """, Long.class, targetStudentId, purchaserUserId,
          purchaserUserId == null ? null : targetStudentForOrder, recipientType,
          courseId, amountPaise, currency, receipt, idempotencyKey);
    } catch (DuplicateKeyException conflict) {
      List<Map<String, Object>> racedOrders = openOrders(targetStudentId, courseId, purchaserUserId);
      if (!racedOrders.isEmpty() && "PENDING".equals(String.valueOf(racedOrders.getFirst().get("status")))) {
        return checkoutResponse(racedOrders.getFirst(), courseId, title);
      }
      if (!racedOrders.isEmpty()) {
        throw new IllegalStateException("Your course checkout is being prepared. Please retry shortly.", conflict);
      }
      throw new IllegalArgumentException("This checkout request has already been used or another checkout is open. Reload the marketplace and try again.", conflict);
    }

    try {
      Map<String,String> notes = new LinkedHashMap<>();
      notes.put("quantaedge_order_id", Long.toString(localOrderId));
      notes.put("quantaedge_student_id", Long.toString(targetStudentId));
      notes.put("quantaedge_course_id", Long.toString(courseId));
      if (purchaserUserId != null) notes.put("quantaedge_purchaser_user_id", Long.toString(purchaserUserId));
      String requestJson = objectMapper.writeValueAsString(Map.of(
          "amount", amountPaise, "currency", currency, "receipt", receipt, "notes", notes));
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
          where id=? and student_id=? and purchaser_user_id is not distinct from ? and status='CREATING'
          """, providerOrderId, localOrderId, targetStudentId, purchaserUserId);
      if (updated != 1) {
        throw new IllegalStateException("This checkout expired while it was being prepared. Please start checkout again.");
      }
      return checkoutResponse(localOrderId, providerOrderId, amountPaise, currency, courseId, title);
    } catch (Exception exception) {
      jdbc.update("""
          update course_payment_order set status='FAILED',failure_code='ORDER_CREATE_FAILED',updated_at=now()
          where id=? and status='CREATING'
          """, localOrderId);
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("Payment could not be started. Please retry.", exception);
    }
  }

  private List<Map<String, Object>> openOrders(long studentId, long courseId, Long purchaserUserId) {
    return jdbc.queryForList("""
        select id,offer_price_paise,currency,provider_order_id,status
        from course_payment_order
        where student_id=? and course_id=?
          and purchaser_user_id is not distinct from ?
          and status in ('CREATING','PENDING')
        order by id desc
        limit 1
        """, studentId, courseId, purchaserUserId);
  }

  private Map<String, Object> checkoutResponse(
      Map<String, Object> order, long courseId, String title) {
    if (!isReusablePendingOrder(order)) {
      throw new IllegalStateException("The previous course checkout is not ready. Please retry shortly.");
    }
    return checkoutResponse(
        ((Number) order.get("id")).longValue(),
        String.valueOf(order.get("provider_order_id")),
        ((Number) order.get("offer_price_paise")).longValue(),
        String.valueOf(order.get("currency")),
        courseId,
        title);
  }

  private Map<String, Object> checkoutResponse(
      long localOrderId, String providerOrderId, long amountPaise,
      String currency, long courseId, String title) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("orderId", providerOrderId);
    result.put("localOrderId", localOrderId);
    result.put("amount", amountPaise);
    result.put("currency", currency);
    result.put("keyId", keyId);
    result.put("courseId", courseId);
    result.put("name", "QuantaEdge");
    result.put("description", title == null || title.isBlank() ? "Course access" : title);
    return result;
  }

  static boolean isValidIdempotencyKey(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{16,64}");
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
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireStudent(context);
    return verifyOrderInternal(localOrderId, request, context.studentId(), null);
  }

  /** Parent checkout verification is bound to the purchaser identity, never to a client-supplied learner. */
  @PostMapping("/family/orders/{localOrderId}/verify")
  @Transactional
  public Map<String, Object> verifyFamilyOrder(
      @PathVariable long localOrderId,
      @RequestBody VerifyCoursePaymentRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireParent(context);
    return verifyOrderInternal(localOrderId, request, null, context.userId());
  }

  private Map<String,Object> verifyOrderInternal(
      long localOrderId, VerifyCoursePaymentRequest request, Long callerStudentId, Long callerUserId) {
    requireConfigured();
    String paymentId = request == null || request.paymentId() == null ? "" : request.paymentId().trim();
    String orderId = request == null || request.orderId() == null ? "" : request.orderId().trim();
    String signature = request == null || request.signature() == null ? "" : request.signature().trim();
    if (!paymentId.matches("pay_[A-Za-z0-9]+") || signature.isBlank()) {
      throw new IllegalArgumentException("The payment confirmation is incomplete. Please retry checkout verification.");
    }
    List<Map<String, Object>> rows = jdbc.queryForList("""
        select id,student_id,purchaser_user_id,target_student_id,recipient_type,course_id,
          offer_price_paise,currency,provider_order_id,provider_payment_id,status
        from course_payment_order
        where id=? and (student_id=? or purchaser_user_id=?)
        for update
        """, localOrderId, callerStudentId, callerUserId);
    if (rows.isEmpty()) throw new SecurityException("This payment order is not available to this account.");
    Map<String, Object> order = rows.getFirst();
    String state = String.valueOf(order.get("status"));
    String savedPaymentId = (String) order.get("provider_payment_id");
    long courseId = ((Number) order.get("course_id")).longValue();
    if ("VERIFIED".equals(state)) {
      if (paymentId.equals(savedPaymentId)) return verifiedResult(localOrderId, courseId);
      throw new IllegalArgumentException("This order has already been verified against a different payment.");
    }
    if (!"PENDING".equals(state)) throw new IllegalArgumentException("This payment order is no longer payable.");
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
    if (!orderId.equals(payment.path("order_id").asText(""))
        || payment.path("amount").asLong(-1) != expectedAmount
        || !expectedCurrency.equalsIgnoreCase(payment.path("currency").asText(""))
        || !"captured".equalsIgnoreCase(payment.path("status").asText(""))) {
      throw new IllegalArgumentException("The payment is not captured for this exact course order. Course access remains locked.");
    }

    long targetStudentId = ((Number) order.get("student_id")).longValue();
    Long purchaserUserId = order.get("purchaser_user_id") == null
        ? null : ((Number) order.get("purchaser_user_id")).longValue();
    String recipientType = String.valueOf(order.get("recipient_type"));
    Long purchaseId;
    try {
      purchaseId = jdbc.queryForObject("""
          insert into course_purchase(
            student_id,course_id,order_id,provider_payment_id,amount_paise,currency,purchaser_user_id
          ) values(?,?,?,?,?,?,?) returning id
          """, Long.class, targetStudentId, courseId, localOrderId,
          paymentId, expectedAmount, expectedCurrency, purchaserUserId);
    } catch (DuplicateKeyException ex) {
      // A concurrent payment or retry must never mint a second ownership record.
      List<Map<String,Object>> prior = jdbc.queryForList("""
        select id,provider_payment_id from course_purchase
        where order_id=? or (student_id=? and course_id=?)
        order by id limit 1
        """, localOrderId, targetStudentId, courseId);
      if (prior.isEmpty() || !paymentId.equals(String.valueOf(prior.getFirst().get("provider_payment_id")))) {
        throw new IllegalStateException("This learner already has a purchase for the course. Contact support before retrying.", ex);
      }
      purchaseId = ((Number) prior.getFirst().get("id")).longValue();
    }

    Long entitlementOwnerUser = purchaserUserId != null && "STUDENT".equals(recipientType)
        ? purchaserUserId : null;
    jdbc.update("""
        insert into course_entitlement(
          course_id,source_purchase_id,holder_user_id,holder_student_id,granted_by_user_id
        ) values(?,?,?,?,?)
        on conflict(source_purchase_id) do nothing
        """, courseId, purchaseId, entitlementOwnerUser, targetStudentId, purchaserUserId);

    // Course membership is switched server-side. A parent's self-purchase stays owned
    // by that parent and is assigned to their personal learning profile until transferred.
    jdbc.update("""
        update student_program_enrollment set status='PAUSED',updated_at=now()
        where student_id=? and status='ACTIVE' and program_id<>?
        """, targetStudentId, courseId);
    jdbc.update("""
        insert into student_program_enrollment(student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at)
        values(?,?,'ACTIVE',now(),null,now(),now())
        on conflict(student_id,program_id) do update
          set status='ACTIVE',
            enrolled_at=case when student_program_enrollment.status='ACTIVE'
              then student_program_enrollment.enrolled_at else now() end,
            ended_at=null,updated_at=now()
        """, targetStudentId, courseId);
    jdbc.update("""
        insert into student_track_enrollment(student_id,subject_id,status)
        select ?,s.id,'ACTIVE'
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.program_id=ps.program_id
          and s.class_id=ps.class_id and s.active=true
        where ps.program_id=?
        on conflict(student_id,subject_id) do update
          set status='ACTIVE',updated_at=now()
        """, targetStudentId, courseId);

    if (purchaserUserId != null && "CHILD".equals(recipientType)) {
      jdbc.update("""
        update parent_course_purchase_request
        set status='PURCHASED',resolved_at=now(),resolved_by_user_id=?,updated_at=now()
        where guardian_user_id=? and student_id=? and course_id=? and status='PENDING'
        """, purchaserUserId, purchaserUserId, targetStudentId, courseId);
    }
    jdbc.update("""
        update course_payment_order set status='VERIFIED',provider_payment_id=?,provider_signature=?,
          verified_at=now(),updated_at=now()
        where id=? and status='PENDING'
        """, paymentId, signature, localOrderId);
    Map<String,Object> result = new LinkedHashMap<>(verifiedResult(localOrderId, courseId));
    result.put("purchasedFor", "CHILD".equals(recipientType) ? "CHILD"
        : purchaserUserId == null ? "LEARNER" : "PARENT");
    result.put("studentId", targetStudentId);
    result.put("transferable", purchaserUserId != null && "STUDENT".equals(recipientType));
    return result;
  }

  @GetMapping("/admin/courses")
  public List<Map<String, Object>> adminCourses(
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requireAdmin(context);
    return jdbc.queryForList("""
        select p.id as course_id,p.code,p.display_name,p.curriculum_version,p.status as course_status,
          o.title_override,o.description,o.price_paise,o.currency,o.status as offer_status,
          o.updated_at
        from curriculum_program p
        left join course_marketplace_offer o on o.course_id=p.id
        where p.status='ACTIVE' and p.code<>'GENERAL'
        order by p.display_name,p.id
        """);
  }

  /** Publish/update offer metadata and server-side price through an explicit Admin action. */
  @PutMapping("/admin/courses/{courseId}")
  public Map<String, Object> saveOffer(
      @PathVariable long courseId,
      @RequestBody SaveCourseOfferRequest request,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requireAdmin(context);
    if (request == null || request.pricePaise() < 0L) {
      throw new IllegalArgumentException("Enter a valid course price.");
    }
    String currency = request.currency() == null ? "INR" : request.currency().trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("[A-Z]{3}") || !"INR".equals(currency)) {
      throw new IllegalArgumentException("Course checkout currently supports INR only.");
    }
    String status = request.status() == null ? "DRAFT" : request.status().trim().toUpperCase(Locale.ROOT);
    if (!List.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status)) {
      throw new IllegalArgumentException("Choose a valid marketplace status.");
    }
    String title = request.title() == null ? "" : request.title().trim();
    String description = request.description() == null ? "" : request.description().trim();
    if (title.length() > 200 || description.length() > 4000) {
      throw new IllegalArgumentException("The course title or description is too long.");
    }
    Integer count = jdbc.queryForObject("""
        select count(*) from curriculum_program where id=? and status='ACTIVE' and code<>'GENERAL'
        """, Integer.class, courseId);
    if (count == null || count != 1) throw new IllegalArgumentException("Choose an active course.");
    jdbc.update("""
        insert into course_marketplace_offer(
          course_id,title_override,description,price_paise,currency,status,created_by_staff_id,updated_by_staff_id
        ) values(?,?,?,?,?,?,?,?)
        on conflict(course_id) do update set
          title_override=excluded.title_override,description=excluded.description,
          price_paise=excluded.price_paise,currency=excluded.currency,status=excluded.status,
          updated_by_staff_id=excluded.updated_by_staff_id,updated_at=now()
        """, courseId, title.isBlank() ? null : title, description.isBlank() ? null : description,
        request.pricePaise(), currency, status, context.staffId(), context.staffId());
    return jdbc.queryForMap("""
        select p.id as course_id,coalesce(nullif(o.title_override,''),p.display_name) as title,
          o.description,o.price_paise,o.currency,o.status as offer_status,o.updated_at
        from course_marketplace_offer o join curriculum_program p on p.id=o.course_id
        where o.course_id=?
        """, courseId);
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

  private Map<String, Object> verifiedResult(long localOrderId, long courseId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "VERIFIED");
    result.put("entitled", true);
    result.put("orderId", localOrderId);
    result.put("courseId", courseId);
    return result;
  }

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
  public record FamilyCourseOrderRequest(String recipientType, Long studentId) {}
  public record SaveCourseOfferRequest(String title, String description, long pricePaise, String currency, String status) {}
}
