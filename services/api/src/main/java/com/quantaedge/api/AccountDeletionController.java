package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountDeletionController {
  private static final List<String> OPEN_STATUSES =
      List.of("REQUESTED", "UNDER_REVIEW", "AWAITING_LINKED_CHILD_DECISION");
  private static final List<String> QUEUE_STATUSES =
      List.of("REQUESTED", "UNDER_REVIEW", "AWAITING_LINKED_CHILD_DECISION", "CANCELLED", "COMPLETED", "REJECTED");
  private static final Set<String> REASON_CATEGORIES = Set.of(
      "NO_LONGER_NEEDED", "PRIVACY_CONCERN", "SECURITY_CONCERN",
      "DUPLICATE_ACCOUNT", "MOVING_TO_ANOTHER_SERVICE", "DATA_CONCERN", "OTHER");

  private final JdbcTemplate jdbc;
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final AccountDeletionDependencyAssessmentService dependencyAssessment;
  private final AuthService authService;
  private final GoogleIdentityService googleIdentity;

  @Autowired
  public AccountDeletionController(JdbcTemplate jdbc, AuthorizationService authorization,
      StaffAuditService staffAudit, AccountDeletionDependencyAssessmentService dependencyAssessment,
      AuthService authService, GoogleIdentityService googleIdentity) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.dependencyAssessment = dependencyAssessment;
    this.authService = authService;
    this.googleIdentity = googleIdentity;
  }

  /** Compatibility constructor for focused request-controller tests. */
  AccountDeletionController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService staffAudit,
      AccountDeletionDependencyAssessmentService dependencyAssessment) {
    this(jdbc, authorization, staffAudit, dependencyAssessment, null, null);
  }

  /** Compatibility constructor for focused request-controller tests. */
  AccountDeletionController(JdbcTemplate jdbc, AuthorizationService authorization, StaffAuditService staffAudit) {
    this(jdbc, authorization, staffAudit, new AccountDeletionDependencyAssessmentService(jdbc), null, null);
  }

  @PostMapping("/account/me/deletion-request/otp")
  public Map<String,Object> requestDeletionOtp(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    DeletionSubject subject = subjectFor(context);
    if (authService == null) throw new IllegalStateException("Account-deletion identity verification is not configured.");
    return authService.requestAccountDeletionOtp(subject.userId());
  }

  @PostMapping("/account/me/deletion-request/verify-otp")
  public Map<String,Object> verifyDeletionOtp(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    DeletionSubject subject = subjectFor(context);
    if (authService == null) throw new IllegalStateException("Account-deletion identity verification is not configured.");
    UUID verificationId = authService.verifyAccountDeletionOtp(
        subject.userId(), String.valueOf(body.getOrDefault("otp", "")));
    return Map.of("verified",true,"verificationId",verificationId.toString(),
        "method","MOBILE_OTP","expiresInSeconds",600);
  }

  @PostMapping("/account/me/deletion-request/verify-google")
  public Map<String,Object> verifyDeletionGoogle(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    DeletionSubject subject = subjectFor(context);
    if (googleIdentity == null) throw new IllegalStateException("Google identity verification is not configured.");
    return googleIdentity.verifyAccountDeletionIdentity(subject.userId(),
        String.valueOf(body.getOrDefault("credential", "")));
  }

  @GetMapping("/account/me/deletion-request")
  public Map<String,Object> currentRequest(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    DeletionSubject subject = subjectFor(context);
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select request_id,status,request_source,includes_linked_children,linked_children_count,
        identity_verification_method,identity_verified_at,reason_category,reason_details,
        requested_at,updated_at,cancelled_at,completed_at
      from account_deletion_request
      where subject_user_id=?
      order by requested_at desc limit 1
      """, subject.userId());
    Map<String,Object> result = rows.isEmpty()
        ? new LinkedHashMap<>() : new LinkedHashMap<>(requestView(rows.getFirst()));
    if (rows.isEmpty()) {
      result.put("hasRequest", false);
      result.put("status", "NONE");
    }
    List<Map<String,Object>> children = linkedChildren(subject.userId());
    result.put("linkedChildren", linkedChildrenForView(children));
    result.put("linkedChildrenCount", children.size());
    result.put("requiresChildResolution", !children.isEmpty());
    return result;
  }

  @PostMapping("/account/me/deletion-request")
  @Transactional
  public Map<String,Object> requestDeletion(@RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    DeletionSubject subject = subjectFor(context);
    if (!"DELETE".equals(String.valueOf(body.getOrDefault("confirmation", "")))) {
      throw new IllegalArgumentException("Type DELETE to confirm the account-deletion request.");
    }
    String source = String.valueOf(body.getOrDefault("source", "WEB")).trim().toUpperCase(Locale.ROOT);
    if (!List.of("WEB", "ANDROID", "OTHER").contains(source)) {
      throw new IllegalArgumentException("Unsupported account-deletion request source.");
    }
    String reasonCategory = String.valueOf(body.getOrDefault("reasonCategory", "")).trim().toUpperCase(Locale.ROOT);
    String reasonDetails = String.valueOf(body.getOrDefault("reasonDetails", "")).trim();
    validateReason(reasonCategory, reasonDetails);


    List<Map<String,Object>> open = jdbc.queryForList("""
      select request_id,status,request_source,includes_linked_children,linked_children_count,
        requested_at,updated_at,cancelled_at,completed_at
      from account_deletion_request
      where subject_user_id=? and status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
      order by requested_at desc limit 1
      """, subject.userId());
    if (!open.isEmpty()) {
      Map<String,Object> result = new LinkedHashMap<>(requestView(open.getFirst()));
      result.put("accepted", true);
      result.put("alreadyPending", true);
      return result;
    }

    List<Map<String,Object>> children = linkedChildren(subject.userId());
    if (!children.isEmpty()) {
      Map<String,Object> blocked = new LinkedHashMap<>();
      blocked.put("accepted", false);
      blocked.put("requiresChildResolution", true);
      blocked.put("linkedChildrenCount", children.size());
      blocked.put("linkedChildren", linkedChildrenForView(children));
      blocked.put("message", "Resolve every linked child account before submitting the parent account-deletion request.");
      return blocked;
    }

    Map<String,Object> proof = null;
    String verificationMethod;
    Object verifiedAt;
    boolean manualIdentityReview = Boolean.TRUE.equals(body.get("manualIdentityReview"));
    if (manualIdentityReview) {
      // This records a review requirement; it does not verify identity or authorize erasure.
      verificationMethod = "MANUAL_REVIEW_REQUIRED";
      verifiedAt = null;
    } else {
      String verificationValue = String.valueOf(body.getOrDefault("verificationId", "")).trim();
      UUID verificationId;
      try {
        verificationId = UUID.fromString(verificationValue);
      } catch (IllegalArgumentException ex) {
        throw new SecurityException("Verify your identity again before submitting the deletion request.");
      }
      List<Map<String,Object>> proofs = jdbc.queryForList("""
        select verification_id,method,verified_at
        from account_deletion_verification
        where verification_id=? and user_account_id=? and consumed_at is null and expires_at>now()
        for update
        """, verificationId, subject.userId());
      if (proofs.isEmpty()) {
        throw new SecurityException("Your identity verification has expired or was already used. Verify again.");
      }
      proof = proofs.getFirst();
      int proofConsumed = jdbc.update("""
        update account_deletion_verification set consumed_at=now()
        where verification_id=? and user_account_id=? and consumed_at is null and expires_at>now()
        """, verificationId, subject.userId());
      if (proofConsumed != 1) {
        throw new SecurityException("Your identity verification has expired or was already used. Verify again.");
      }
      verificationMethod = String.valueOf(proof.get("method"));
      verifiedAt = proof.get("verified_at");
    }

    UUID requestId = UUID.randomUUID();
    jdbc.update("""
      insert into account_deletion_request(
        request_id,subject_user_id,subject_user_public_id,subject_student_id,
        subject_student_public_user_id,request_source,status,includes_linked_children,
        linked_children_count,identity_verification_method,identity_verified_at,
        requested_at,updated_at,requested_by_user_id,reason_category,reason_details)
      values (?,?,?,?,?,?,'REQUESTED',false,0,?,?,now(),now(),?,?,?)
      """, requestId, subject.userId(), subject.publicUserId(), subject.studentId(),
        subject.publicStudentPublicUserId(), source, verificationMethod, verifiedAt,
        subject.userId(), reasonCategory, nullableReasonDetails(reasonDetails));

    jdbc.update("""
      insert into account_identity_audit(user_id,actor_student_id,event_type,details)
      values (?,?, 'ACCOUNT_DELETION_REQUESTED',
        jsonb_build_object('requestId',?,'source',?,'reasonCategory',?,
          'identityVerificationMethod',?))
      """, subject.userId(), subject.studentId(), requestId.toString(), source, reasonCategory,
        verificationMethod);

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("accepted", true);
    result.put("alreadyPending", false);
    result.put("requestId", requestId.toString());
    result.put("status", "REQUESTED");
    result.put("requestedAt", java.time.Instant.now().toString());
    result.put("linkedChildrenCount", 0);
    result.put("includesLinkedChildren", false);
    result.put("reasonCategory", reasonCategory);
    result.put("reasonDetails", reasonDetails);
    result.put("identityVerificationMethod", verificationMethod);
    result.put("identityVerified", verifiedAt != null);
    return result;
  }

  @PostMapping("/account/me/deletion-request/children/{studentPublicUserId}")
  @Transactional
  public Map<String,Object> requestChildDeletion(@PathVariable String studentPublicUserId,
      @RequestBody Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
    long requesterUserId = context.userId();
    if (!"DELETE".equals(String.valueOf(body.getOrDefault("confirmation", "")))) {
      throw new IllegalArgumentException("Type DELETE to confirm the child account-deletion request.");
    }
    String source = String.valueOf(body.getOrDefault("source", "WEB")).trim().toUpperCase(Locale.ROOT);
    if (!List.of("WEB", "ANDROID", "OTHER").contains(source)) {
      throw new IllegalArgumentException("Unsupported account-deletion request source.");
    }
    String reasonCategory = String.valueOf(body.getOrDefault("reasonCategory", "")).trim().toUpperCase(Locale.ROOT);
    String reasonDetails = String.valueOf(body.getOrDefault("reasonDetails", "")).trim();
    validateReason(reasonCategory, reasonDetails);

    List<Map<String,Object>> children = linkedChildren(requesterUserId);
    Map<String,Object> child = children.stream()
        .filter(item -> studentPublicUserId.equals(String.valueOf(item.get("public_user_id"))))
        .findFirst().orElseThrow(() -> new SecurityException(
            "Only an active parent-managed child linked to this guardian can be selected."));
    String adultStatus = String.valueOf(child.getOrDefault("adult_verification_status", "NOT_REVIEWED"));
    if ("VERIFIED".equals(adultStatus) && Boolean.TRUE.equals(child.get("adult_verified"))) {
      return Map.of(
          "accepted", false,
          "requiresIndependentAccountConversion", true,
          "studentPublicUserId", studentPublicUserId,
          "message", "This child has verified adult eligibility. The adult learner must complete the existing independent-account conversion flow instead of being deleted by a guardian.");
    }
    if (!"REJECTED".equals(adultStatus)) {
      return Map.of(
          "accepted", false,
          "requiresAdultVerificationReview", true,
          "studentPublicUserId", studentPublicUserId,
          "adultVerificationStatus", adultStatus,
          "message", "A reviewer must establish the child's adult-eligibility status before a guardian-requested deletion can be considered.");
    }

    long studentId = ((Number) child.get("student_id")).longValue();
    List<Map<String,Object>> open = jdbc.queryForList("""
      select request_id,status
      from account_deletion_request
      where subject_student_id=? and status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
      order by requested_at desc limit 1
      """, studentId);
    if (!open.isEmpty()) {
      return Map.of("accepted", true, "alreadyPending", true,
          "requestId", String.valueOf(open.getFirst().get("request_id")),
          "status", String.valueOf(open.getFirst().get("status")),
          "studentPublicUserId", studentPublicUserId);
    }

    Map<String,Object> proof = consumeIdentityProof(requesterUserId, body, true);
    UUID requestId = UUID.randomUUID();
    String publicId = String.valueOf(child.get("public_user_id"));
    jdbc.update("""
      insert into account_deletion_request(
        request_id,subject_user_id,subject_user_public_id,subject_student_id,
        subject_student_public_user_id,request_source,status,includes_linked_children,
        linked_children_count,identity_verification_method,identity_verified_at,
        requested_at,updated_at,requested_by_user_id,reason_category,reason_details)
      values (?,null,null,?,?,?,'REQUESTED',false,0,?,?,now(),now(),?,?,?)
      """, requestId, studentId, publicId, source,
        proof.get("method"), proof.get("verifiedAt"), requesterUserId,
        reasonCategory, nullableReasonDetails(reasonDetails));

    jdbc.update("""
      insert into account_identity_audit(user_id,actor_student_id,event_type,details)
      values (?,?, 'ACCOUNT_DELETION_REQUESTED',
        jsonb_build_object('requestId',?,'source',?,'targetStudentPublicUserId',?,
          'reasonCategory',?,'requestedByGuardian',true))
      """, requesterUserId, context.studentId(), requestId.toString(), source,
        publicId, reasonCategory);

    return Map.of("accepted", true, "alreadyPending", false,
        "requestId", requestId.toString(), "status", "REQUESTED",
        "studentPublicUserId", publicId, "requestedAt", java.time.Instant.now().toString(),
        "identityVerificationMethod", proof.get("method"));
  }

  @DeleteMapping("/account/me/deletion-request/children/{studentPublicUserId}")
  @Transactional
  public Map<String,Object> cancelChildDeletion(@PathVariable String studentPublicUserId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = authorization.requireParent(context);
    List<Map<String,Object>> requests = jdbc.queryForList("""
      select request_id from account_deletion_request
      where subject_student_public_user_id=? and requested_by_user_id=?
        and status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
      order by requested_at desc limit 1 for update
      """, studentPublicUserId, context.userId());
    if (requests.isEmpty()) return Map.of("cancelled", false, "status", "NONE");
    String requestId = String.valueOf(requests.getFirst().get("request_id"));
    int changed = jdbc.update("""
      update account_deletion_request
      set status='CANCELLED',cancelled_at=now(),updated_at=now()
      where request_id=? and status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
      """, UUID.fromString(requestId));
    if (changed == 1) {
      jdbc.update("""
        insert into account_identity_audit(user_id,actor_student_id,event_type,details)
        values (?,?, 'ACCOUNT_DELETION_REQUEST_CANCELLED',jsonb_build_object('requestId',?,'childRequest',true))
        """, context.userId(), context.studentId(), requestId);
    }
    return Map.of("cancelled", changed == 1, "status", changed == 1 ? "CANCELLED" : "UNCHANGED");
  }

  @DeleteMapping("/account/me/deletion-request")
  @Transactional
  public Map<String,Object> cancelRequest(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    DeletionSubject subject = subjectFor(context);
    List<Map<String,Object>> requests = jdbc.queryForList("""
      select request_id from account_deletion_request
      where subject_user_id=? and status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
      order by requested_at desc limit 1 for update
      """, subject.userId());
    if (requests.isEmpty()) return Map.of("cancelled", false, "status", "NONE");

    String requestId = String.valueOf(requests.getFirst().get("request_id"));
    int changed = jdbc.update("""
      update account_deletion_request
      set status='CANCELLED',cancelled_at=now(),updated_at=now()
      where request_id=? and status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
      """, UUID.fromString(requestId));
    if (changed == 1) {
      jdbc.update("""
        insert into account_identity_audit(user_id,actor_student_id,event_type,details)
        values (?,?, 'ACCOUNT_DELETION_REQUEST_CANCELLED',jsonb_build_object('requestId',?))
        """, subject.userId(), subject.studentId(), requestId);
    }
    return Map.of("cancelled", changed == 1, "status", changed == 1 ? "CANCELLED" : "UNCHANGED");
  }

  @GetMapping("/admin/account-deletion-requests")
  public Map<String,Object> requestQueue(
      @RequestParam(defaultValue="REQUESTED") String status,
      @RequestParam(defaultValue="50") int limit,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireReviewer(context);
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!QUEUE_STATUSES.contains(normalized)) throw new IllegalArgumentException("Unsupported request status.");
    int safeLimit = Math.max(1, Math.min(limit, 100));
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select deletion.request_id,deletion.subject_user_public_id,deletion.subject_student_public_user_id,
        deletion.request_source,deletion.status,deletion.includes_linked_children,deletion.linked_children_count,
        deletion.identity_verification_method,deletion.identity_verified_at,
        deletion.reason_category,deletion.reason_details,requester.public_user_id as requested_by_public_user_id,
        deletion.requested_at,deletion.updated_at,deletion.reviewed_at,deletion.cancelled_at,deletion.completed_at
      from account_deletion_request deletion
      left join user_account requester on requester.id=deletion.requested_by_user_id
      where deletion.status=?
      order by deletion.requested_at,deletion.request_id
      limit ?
      """, normalized, safeLimit);
    return Map.of("items", rows, "total", rows.size(), "status", normalized);
  }

  @GetMapping("/admin/account-deletion-requests/{requestId}")
  public Map<String,Object> requestDetails(@PathVariable UUID requestId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireReviewer(context);
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select deletion.request_id,deletion.subject_user_public_id,deletion.subject_student_public_user_id,
        deletion.request_source,deletion.status,deletion.includes_linked_children,deletion.linked_children_count,
        deletion.identity_verification_method,deletion.identity_verified_at,
        deletion.reason_category,deletion.reason_details,requester.public_user_id as requested_by_public_user_id,
        deletion.requested_at,deletion.updated_at,deletion.reviewed_by_staff_id,deletion.reviewed_at,
        deletion.cancelled_at,deletion.completed_at
      from account_deletion_request deletion
      left join user_account requester on requester.id=deletion.requested_by_user_id
      where deletion.request_id=?
      """, requestId);
    if (rows.isEmpty()) throw new IllegalArgumentException("Account-deletion request was not found.");
    List<Map<String,Object>> children = jdbc.queryForList("""
      select student_public_user_id,resolution,created_at
      from account_deletion_request_child where request_id=?
      order by student_public_user_id
      """, requestId);
    Map<String,Object> result = new LinkedHashMap<>(rows.getFirst());
    result.put("linkedChildren", children);
    return result;
  }

  @GetMapping("/admin/account-deletion-requests/{requestId}/assessment")
  public Map<String,Object> dependencyAssessment(@PathVariable UUID requestId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = requireReviewer(context);
    Map<String,Object> report = dependencyAssessment.assess(requestId);
    Object affected = report.get("summary") instanceof Map<?,?> summary
        ? summary.get("affectedRelationships") : "unknown";
    staffAudit.recordAction(context, "/api/v1/admin/account-deletion-requests/" + requestId + "/assessment",
        "Ran read-only account-deletion dependency assessment; affected FK relationships=" + affected
            + ". No account or learner data was changed.");
    return report;
  }

  @PostMapping("/admin/account-deletion-requests/{requestId}/start-review")
  @Transactional
  public Map<String,Object> startReview(@PathVariable UUID requestId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    context = requireReviewer(context);
    List<Map<String,Object>> pendingRows = jdbc.queryForList("""
      select subject_user_id,subject_student_id,requested_by_user_id
      from account_deletion_request where request_id=? and status='REQUESTED'
      """, requestId);
    if (pendingRows.isEmpty()) throw new IllegalStateException("Only a newly requested deletion can enter review.");
    Object subjectUser = pendingRows.getFirst().get("subject_user_id");
    Object subjectStudent = pendingRows.getFirst().get("subject_student_id");
    Object requesterUser = pendingRows.getFirst().get("requested_by_user_id");
    if (subjectUser == null && subjectStudent instanceof Number studentNumber
        && requesterUser instanceof Number requesterNumber) {
      List<Map<String,Object>> reviewedMinor = jdbc.queryForList("""
        select st.id from student st
        join student_adult_verification av on av.student_id=st.id and av.status='REJECTED'
        where st.id=? and st.active=true and st.user_account_id is null
          and (
            exists (select 1 from guardian_student gs
              where gs.guardian_user_id=? and gs.student_id=st.id
                and gs.active=true and gs.consent_status<>'REVOKED')
            or exists (select 1 from student_login_credential slc
              where slc.guardian_user_id=? and slc.student_id=st.id and slc.active=true)
          )
        for update of st
        """, studentNumber.longValue(), requesterNumber.longValue(), requesterNumber.longValue());
      if (reviewedMinor.isEmpty()) {
        throw new IllegalStateException("Guardian-requested child deletion can enter review only when the child remains linked and an authorized reviewer has confirmed under-18 status.");
      }
    }
    if (subjectUser instanceof Number subjectNumber && requesterUser instanceof Number requesterNumber
        && subjectNumber.longValue() == requesterNumber.longValue()
        && !linkedChildren(subjectNumber.longValue()).isEmpty()) {
      throw new IllegalStateException("This parent account still has linked children. Resolve every child account before beginning review.");
    }
    int changed = jdbc.update("""
      update account_deletion_request
      set status='UNDER_REVIEW',reviewed_by_staff_id=?,reviewed_at=now(),updated_at=now()
      where request_id=? and status='REQUESTED'
      """, context.staffId(), requestId);
    if (changed != 1) throw new IllegalStateException("Only a newly requested deletion can enter review.");
    staffAudit.recordAction(context, "/api/v1/admin/account-deletion-requests/" + requestId + "/start-review",
        "Started review of an account-deletion request. Completion requires the approved retention-aware erasure workflow.");
    return Map.of("requestId", requestId.toString(), "status", "UNDER_REVIEW");
  }

  private DeletionSubject subjectFor(AuthContext context) {
    context = authorization.requireAuth(context);
    if (context.isEmployee()) throw new SecurityException("Staff accounts must use the authorized staff lifecycle process.");
    Long userId = context.userId();
    if (userId == null && context.studentId() != null) {
      List<Map<String,Object>> linked = jdbc.queryForList("""
        select user_account_id from student
        where id=? and active=true
        """, context.studentId());
      if (!linked.isEmpty() && linked.getFirst().get("user_account_id") != null) {
        userId = ((Number) linked.getFirst().get("user_account_id")).longValue();
      }
    }
    if (userId == null) {
      throw new SecurityException("A parent-managed learner must ask their verified guardian to manage account deletion.");
    }

    List<Map<String,Object>> users = jdbc.queryForList("""
      select id,public_user_id,role from user_account
      where id=? and active=true
      """, userId);
    if (users.isEmpty()) throw new SecurityException("The account is inactive or unavailable.");
    Map<String,Object> user = users.getFirst();
    String role = String.valueOf(user.get("role"));
    if (!List.of("PARENT", "LEARNER").contains(role)) {
      throw new SecurityException("This account type cannot request deletion through learner self-service.");
    }

    List<Map<String,Object>> students = jdbc.queryForList("""
      select id,public_user_id from student
      where user_account_id=? and active=true
      order by id limit 1
      """, userId);
    Long studentId = students.isEmpty() ? null : ((Number) students.getFirst().get("id")).longValue();
    String studentPublicUserId = students.isEmpty() ? null : String.valueOf(students.getFirst().get("public_user_id"));
    return new DeletionSubject(userId, String.valueOf(user.get("public_user_id")), studentId, studentPublicUserId);
  }

  private List<Map<String,Object>> linkedChildren(long userId) {
    return jdbc.queryForList("""
      select distinct st.id as student_id,st.public_user_id,st.display_name,
        coalesce(av.status,'NOT_REVIEWED') as adult_verification_status,
        (av.status='VERIFIED' and av.expires_at>now()) as adult_verified,
        child_request.status as child_deletion_request_status
      from student st
      left join student_adult_verification av on av.student_id=st.id
      left join lateral (
        select deletion.status
        from account_deletion_request deletion
        where deletion.subject_student_id=st.id
          and deletion.status in ('REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION')
        order by deletion.requested_at desc limit 1
      ) child_request on true
      where st.active=true
        and st.user_account_id is null
        and (
          exists (select 1 from guardian_student gs
            where gs.guardian_user_id=? and gs.student_id=st.id
              and gs.active=true and gs.consent_status<>'REVOKED')
          or exists (select 1 from student_login_credential slc
            where slc.guardian_user_id=? and slc.student_id=st.id and slc.active=true)
        )
      order by st.id
      """, userId, userId);
  }

  private Map<String,Object> consumeIdentityProof(long userId, Map<String,Object> body, boolean guardianRequest) {
    Map<String,Object> result = new LinkedHashMap<>();
    boolean manualIdentityReview = Boolean.TRUE.equals(body.get("manualIdentityReview"));
    if (manualIdentityReview) {
      result.put("method", "MANUAL_REVIEW_REQUIRED");
      result.put("verifiedAt", null);
      return result;
    }
    String verificationValue = String.valueOf(body.getOrDefault("verificationId", "")).trim();
    UUID verificationId;
    try {
      verificationId = UUID.fromString(verificationValue);
    } catch (IllegalArgumentException ex) {
      throw new SecurityException("Verify your identity again before submitting the deletion request.");
    }
    List<Map<String,Object>> proofs = jdbc.queryForList("""
      select verification_id,method,verified_at
      from account_deletion_verification
      where verification_id=? and user_account_id=? and consumed_at is null and expires_at>now()
      for update
      """, verificationId, userId);
    if (proofs.isEmpty()) {
      throw new SecurityException("Your identity verification has expired or was already used. Verify again.");
    }
    Map<String,Object> proof = proofs.getFirst();
    int changed = jdbc.update("""
      update account_deletion_verification set consumed_at=now()
      where verification_id=? and user_account_id=? and consumed_at is null and expires_at>now()
      """, verificationId, userId);
    if (changed != 1) {
      throw new SecurityException("Your identity verification has expired or was already used. Verify again.");
    }
    String method = String.valueOf(proof.get("method"));
    result.put("method", guardianRequest ? "GUARDIAN_" + method : method);
    result.put("verifiedAt", proof.get("verified_at"));
    return result;
  }

  private static void validateReason(String category, String details) {
    if (!REASON_CATEGORIES.contains(category)) {
      throw new IllegalArgumentException("Choose a reason for the account-deletion request.");
    }
    if (details.length() > 1500) {
      throw new IllegalArgumentException("Additional details must be 1,500 characters or fewer.");
    }
    if ("OTHER".equals(category) && details.trim().length() < 5) {
      throw new IllegalArgumentException("Add a short explanation when selecting Other.");
    }
  }

  private static String nullableReasonDetails(String details) {
    return details == null || details.isBlank() ? null : details.trim();
  }

  private List<Map<String,Object>> linkedChildrenForView(List<Map<String,Object>> children) {
    List<Map<String,Object>> result = new java.util.ArrayList<>();
    for (Map<String,Object> child : children) {
      Map<String,Object> item = new LinkedHashMap<>();
      item.put("publicUserId", child.get("public_user_id"));
      item.put("displayName", child.get("display_name") == null ? "" : child.get("display_name"));
      item.put("adultVerificationStatus", child.get("adult_verification_status"));
      item.put("adultVerified", Boolean.TRUE.equals(child.get("adult_verified")));
      item.put("deletionRequestStatus", child.get("child_deletion_request_status"));
      result.add(item);
    }
    return result;
  }

  private Map<String,Object> requestView(Map<String,Object> row) {
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("hasRequest", true);
    result.put("requestId", String.valueOf(row.get("request_id")));
    result.put("status", row.get("status"));
    result.put("source", row.get("request_source"));
    result.put("includesLinkedChildren", Boolean.TRUE.equals(row.get("includes_linked_children")));
    result.put("linkedChildrenCount", ((Number) row.get("linked_children_count")).intValue());
    if (row.containsKey("reason_category")) result.put("reasonCategory", row.get("reason_category"));
    if (row.containsKey("reason_details")) result.put("reasonDetails", row.get("reason_details"));
    result.put("requestedAt", String.valueOf(row.get("requested_at")));
    result.put("updatedAt", String.valueOf(row.get("updated_at")));
    result.put("cancelledAt", row.get("cancelled_at") == null ? null : String.valueOf(row.get("cancelled_at")));
    result.put("completedAt", row.get("completed_at") == null ? null : String.valueOf(row.get("completed_at")));
    return result;
  }

  private AuthContext requireReviewer(AuthContext context) {
    context = authorization.requirePermission(context, "ACCOUNT_LIFECYCLE_REVIEW");
    if (context.staffId() == null) throw new SecurityException("Authorized staff review access is required.");
    return context;
  }

  private record DeletionSubject(
      long userId, String publicUserId, Long studentId, String publicStudentPublicUserId) {}
}
