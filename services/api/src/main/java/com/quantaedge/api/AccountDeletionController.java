package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
        identity_verification_method,identity_verified_at,requested_at,updated_at,cancelled_at,completed_at
      from account_deletion_request
      where subject_user_id=?
      order by requested_at desc limit 1
      """, subject.userId());
    if (rows.isEmpty()) return Map.of("hasRequest", false, "status", "NONE");
    return requestView(rows.getFirst());
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
    boolean includeLinkedChildren = Boolean.TRUE.equals(body.get("includeLinkedChildren"));

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
    if (!children.isEmpty() && !includeLinkedChildren) {
      return Map.of(
          "accepted", false,
          "requiresLinkedChildrenConfirmation", true,
          "linkedChildrenCount", children.size());
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
        linked_children_count,identity_verification_method,identity_verified_at,requested_at,updated_at)
      values (?,?,?,?,?,?,'REQUESTED',?,?,?,?,now(),now())
      """, requestId, subject.userId(), subject.publicUserId(), subject.studentId(),
        subject.publicStudentPublicUserId(), source, includeLinkedChildren, children.size(),
        verificationMethod, verifiedAt);

    if (includeLinkedChildren) {
      for (Map<String,Object> child : children) {
        jdbc.update("""
          insert into account_deletion_request_child(request_id,student_id,student_public_user_id)
          values (?,?,?)
          on conflict(request_id,student_public_user_id) do nothing
          """, requestId, ((Number) child.get("student_id")).longValue(), child.get("public_user_id"));
      }
    }

    jdbc.update("""
      insert into account_identity_audit(user_id,actor_student_id,event_type,details)
      values (?,?, 'ACCOUNT_DELETION_REQUESTED',
        jsonb_build_object('requestId',?,'source',?,'linkedChildrenCount',?,'includesLinkedChildren',?,
          'identityVerificationMethod',?))
      """, subject.userId(), subject.studentId(), requestId.toString(), source, children.size(),
        includeLinkedChildren, verificationMethod);

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("accepted", true);
    result.put("alreadyPending", false);
    result.put("requestId", requestId.toString());
    result.put("status", "REQUESTED");
    result.put("requestedAt", java.time.Instant.now().toString());
    result.put("linkedChildrenCount", children.size());
    result.put("includesLinkedChildren", includeLinkedChildren);
    result.put("identityVerificationMethod", verificationMethod);
    result.put("identityVerified", verifiedAt != null);
    return result;
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
      select request_id,subject_user_public_id,subject_student_public_user_id,
        request_source,status,includes_linked_children,linked_children_count,
        identity_verification_method,identity_verified_at,
        requested_at,updated_at,reviewed_at,cancelled_at,completed_at
      from account_deletion_request
      where status=?
      order by requested_at,request_id
      limit ?
      """, normalized, safeLimit);
    return Map.of("items", rows, "total", rows.size(), "status", normalized);
  }

  @GetMapping("/admin/account-deletion-requests/{requestId}")
  public Map<String,Object> requestDetails(@PathVariable UUID requestId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireReviewer(context);
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select request_id,subject_user_public_id,subject_student_public_user_id,
        request_source,status,includes_linked_children,linked_children_count,
        identity_verification_method,identity_verified_at,
        requested_at,updated_at,reviewed_by_staff_id,reviewed_at,cancelled_at,completed_at
      from account_deletion_request where request_id=?
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
      select distinct st.id as student_id,st.public_user_id
      from student st
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

  private Map<String,Object> requestView(Map<String,Object> row) {
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("hasRequest", true);
    result.put("requestId", String.valueOf(row.get("request_id")));
    result.put("status", row.get("status"));
    result.put("source", row.get("request_source"));
    result.put("includesLinkedChildren", Boolean.TRUE.equals(row.get("includes_linked_children")));
    result.put("linkedChildrenCount", ((Number) row.get("linked_children_count")).intValue());
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
