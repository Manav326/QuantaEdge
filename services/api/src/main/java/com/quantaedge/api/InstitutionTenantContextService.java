package com.quantaedge.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves the client-supplied tenant selector into an active institution row.
 *
 * The header is an untrusted selector, never an identity credential or entitlement. Protected
 * handlers must still prove institution membership and the applicable course/offer access.
 * Institution-admin requests are deliberately resolved from institution_admin_session instead.
 */
@Service
public class InstitutionTenantContextService {
  public static final String HEADER_NAME = "X-QE-Institution-Code";
  public static final String REQUEST_ATTRIBUTE = "institutionTenantContext";
  private static final Pattern TENANT_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,31}$");
  private static final String FIND_ACTIVE_TENANT = """
      select id, tenant_code, display_name
      from institution_tenant
      where tenant_code=? and status='ACTIVE'
      """;

  /**
   * Tenant-scoped learner surfaces must never fall back to platform-wide course data when the
   * caller omits a tenant selector. Add route families here only after their APIs use the same
   * tenant context and per-course access service.
   */
  private static final List<String> TENANT_REQUIRED_PREFIXES = List.of(
      // Legacy and current learner routes must share the same fail-closed tenant boundary.
      "/api/v1/students/",
      "/api/v1/diagnostic",
      "/api/v1/recommendations",
      "/api/v1/learning/sessions",
      "/api/v1/learning/assessments",
      "/api/v1/learning/assessment-attempts",
      "/api/v1/students/me/programs",
      "/api/v1/students/me/certificates",
      "/api/v1/marketplace",
      "/api/v1/parent/course-requests",
      "/api/v1/lessons",
      "/api/v1/questions",
      "/api/v1/structured-lessons",
      "/api/v1/practice",
      "/api/v1/learning/",
      "/api/v1/notifications",
      "/api/v1/guardians/children",
      "/api/v1/certificates",
      "/api/v1/tutor",
      "/api/v1/learning-chat"
  );

  private final JdbcTemplate jdbc;
  private final String defaultInstitutionCode;
  private final String contextMode;

  @Autowired
  public InstitutionTenantContextService(
      JdbcTemplate jdbc,
      @Value("${app.institution.default-code:}") String configuredDefaultCode,
      @Value("${app.institution.context-mode:ROUTED}") String configuredContextMode) {
    this.jdbc = jdbc;
    String mode = configuredContextMode == null ? "ROUTED" : configuredContextMode.trim().toUpperCase(Locale.ROOT);
    if (!mode.equals("ROUTED") && !mode.equals("FIXED")) {
      throw new IllegalArgumentException("APP_INSTITUTION_CONTEXT_MODE must be ROUTED or FIXED.");
    }
    this.contextMode = mode;
    this.defaultInstitutionCode = normalizeOptionalCode(configuredDefaultCode, "default institution code");
    if ("FIXED".equals(this.contextMode) && this.defaultInstitutionCode == null) {
      throw new IllegalArgumentException(
          "APP_DEFAULT_INSTITUTION_CODE is required when APP_INSTITUTION_CONTEXT_MODE=FIXED.");
    }
  }

  /** Keeps existing focused unit tests source-compatible with a routed, no-default deployment. */
  public InstitutionTenantContextService(JdbcTemplate jdbc) {
    this(jdbc, "", "ROUTED");
  }

  private static String normalizeOptionalCode(String raw, String settingName) {
    if (raw == null || raw.isBlank()) return null;
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if (!TENANT_CODE.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Configured " + settingName + " is invalid.");
    }
    return normalized;
  }

  /**
   * Chooses the tenant selector using generic deployment policy. ROUTED mode allows an explicit
   * school route/build context and uses the configured default only as a compatibility fallback.
   * FIXED mode pins this deployment to one institution and rejects attempts to select another.
   * In either mode the returned code still has to resolve to an active institution and pass
   * membership/course/offer checks; this method never grants access.
   */
  private static String normalizeRequestedCode(String requestedCode) {
    if (requestedCode == null || requestedCode.isBlank()) return null;
    String normalized = requestedCode.trim().toUpperCase(Locale.ROOT);
    if (!TENANT_CODE.matcher(normalized).matches()) {
      throw new InvalidInstitutionContextException(
          400, "INVALID_INSTITUTION_CODE", "The selected institution code is invalid.");
    }
    return normalized;
  }

  public String selectTenantCode(String requestedCode) {
    String requested = normalizeRequestedCode(requestedCode);
    if ("FIXED".equals(contextMode)) {
      if (requested != null && !requested.equals(defaultInstitutionCode)) {
        throw new InvalidInstitutionContextException(
            403, "INSTITUTION_CONTEXT_MISMATCH",
            "This deployment is configured for a different institution.");
      }
      return defaultInstitutionCode;
    }
    return requested != null ? requested : defaultInstitutionCode;
  }

  public String contextMode() { return contextMode; }
  public String defaultInstitutionCode() { return defaultInstitutionCode; }

  /** Returns null only when the request did not select a tenant; it never guesses a tenant. */
  public InstitutionTenantContext resolve(String requestedCode) {

    // Header is intentionally optional at this low-level resolver. Protected endpoints call
    // requireSelector() so public branding, authentication, join, and membership discovery can
    // operate before the caller has an institution membership.
    if (requestedCode == null || requestedCode.isBlank()) return null;

    String code = requestedCode.trim().toUpperCase(Locale.ROOT);
    if (!TENANT_CODE.matcher(code).matches()) {
      throw new InvalidInstitutionContextException(
          400, "INVALID_INSTITUTION_CODE", "The selected institution code is invalid.");
    }

    List<Map<String,Object>> matches = jdbc.queryForList(FIND_ACTIVE_TENANT, code);
    if (matches.isEmpty()) {
      throw new InvalidInstitutionContextException(
          403, "INSTITUTION_UNAVAILABLE", "The selected institution is unavailable.");
    }

    Map<String,Object> row = matches.getFirst();
    Object rawId = row.get("id");
    if (!(rawId instanceof Number institutionId)) {
      throw new IllegalStateException("The institution row has no numeric ID.");
    }
    Object rawCode = row.get("tenant_code");
    Object rawName = row.get("display_name");
    String canonicalCode = rawCode == null ? code
        : rawCode.toString().trim().toUpperCase(Locale.ROOT);
    String displayName = rawName == null ? "" : rawName.toString().trim();
    return new InstitutionTenantContext(institutionId.longValue(), canonicalCode, displayName);
  }

  public static boolean requiresInstitutionContext(String requestPath) {
    if (requestPath == null) return false;

    // Public identity, account/session, institution join, institution-admin and HRMS-control
    // routes are intentionally not classified as learner-content APIs.
    if (requestPath.startsWith("/api/v1/auth/")
        || requestPath.startsWith("/api/v1/institution/")
        || requestPath.startsWith("/api/v1/hrms/")
        || requestPath.startsWith("/api/v1/hr/")
        || requestPath.startsWith("/api/v1/admin/")) return false;
    return TENANT_REQUIRED_PREFIXES.stream().anyMatch(prefix ->
        requestPath.equals(prefix) || requestPath.startsWith(prefix));
  }

  /**
   * Education Admin tenant is derived from an HRMS-managed staff binding. Browser tenant headers
   * are selectors only for learner/institution entry flows; they can never choose an Education Admin
   * workspace's institution.
   */
  public InstitutionTenantContext requireStaffInstitution(Long staffId) {
    if (staffId == null || staffId < 1L) {
      throw new InvalidInstitutionContextException(
          403, "EDUCATION_ADMIN_STAFF_REQUIRED",
          "An authenticated Education Admin staff identity is required.");
    }
    List<Map<String,Object>> rows = jdbc.queryForList("""
        select t.id,t.tenant_code,t.display_name
        from institution_staff_binding b
        join staff_account s on s.id=b.staff_id and s.active=true
        join institution_tenant t on t.id=b.institution_id and t.status='ACTIVE'
        where b.staff_id=? and b.status='ACTIVE'
        """, staffId);
    if (rows.isEmpty()) {
      throw new InvalidInstitutionContextException(
          403, "INSTITUTION_STAFF_BINDING_REQUIRED",
          "This Education Admin staff account has no active HRMS institution assignment. Ask an HRMS platform owner to assign the institution. Institution switching is not available in Education Admin.");
    }
    Map<String,Object> row = rows.getFirst();
    Object rawId = row.get("id");
    Object rawCode = row.get("tenant_code");
    if (!(rawId instanceof Number institutionId) || rawCode == null
        || rawCode.toString().isBlank()) {
      throw new IllegalStateException("The active HRMS institution binding is incomplete.");
    }
    return new InstitutionTenantContext(
        institutionId.longValue(), rawCode.toString().trim().toUpperCase(Locale.ROOT),
        row.get("display_name") == null ? "" : row.get("display_name").toString().trim());
  }

  /**
   * During a rolling client deployment, reject old clients that still submit a different
   * institution selector rather than ever honouring it. A matching header carries no authority.
   */
  public void rejectMismatchedStaffSelector(String requestedCode, InstitutionTenantContext bound) {
    if (requestedCode == null || requestedCode.isBlank()) return;
    String requested = normalizeRequestedCode(requestedCode);
    if (bound == null || !bound.tenantCode().equals(requested)) {
      throw new InvalidInstitutionContextException(
          403, "INSTITUTION_CONTEXT_MISMATCH",
          "This Education Admin account is bound to a different institution by HRMS. The selected institution header is not an authorization mechanism.");
    }
  }

  public InstitutionTenantContext requireSelector(String requestedCode) {
    String selectedCode = selectTenantCode(requestedCode);
    if (selectedCode == null || selectedCode.isBlank()) {
      throw new InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED",
          "Select the institution link for this learning request. No default institution is configured.");
    }
    InstitutionTenantContext tenant = resolve(selectedCode);
    if (tenant == null) {
      throw new InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED", "An institution context is required.");
    }
    return tenant;
  }

  /**
   * Proves the signed-in identity belongs to this exact institution. A tenant selector cannot
   * create a membership. Parents need a currently consented guardian-child link to at least one
   * active learner in this institution; specific child routes must still validate the target child.
   */
  public void assertActiveMembership(AuthContext caller, InstitutionTenantContext tenant) {
    if (caller == null || tenant == null) {
      throw new InvalidInstitutionContextException(
          403, "INSTITUTION_MEMBERSHIP_REQUIRED",
          "An active membership in the selected institution is required.");
    }

    Boolean member;
    if (caller.studentId() != null) {
      member = jdbc.queryForObject("""
          select exists(
            select 1
            from institution_membership m
            join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
            join student st on st.id=m.student_id and st.active=true
            where m.institution_id=? and m.student_id=? and m.status='ACTIVE'
          )
          """, Boolean.class, tenant.institutionId(), caller.studentId());
    } else if (caller.isParent()) {
      member = jdbc.queryForObject("""
          select exists(
            select 1
            from institution_user_membership m
            join user_account u on u.id=m.user_id and u.active=true
            join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
            where m.institution_id=? and m.user_id=? and m.status='ACTIVE'
          )
          """,Boolean.class,tenant.institutionId(),caller.userId());
    } else {
      member = false;
    }

    if (!Boolean.TRUE.equals(member)) {
      throw new InvalidInstitutionContextException(
          403, "INSTITUTION_MEMBERSHIP_REQUIRED",
          "Your account has no active membership in the selected institution.");
    }
  }

  /**
   * Validates the exact child selected by a parent operation, not merely that some child of the
   * parent belongs to the selected institution. Controller checks may impose a stricter access
   * level (for example FULL for profile changes); this check establishes the tenant boundary.
   */
  public void assertGuardianChildMembership(
      AuthContext caller, InstitutionTenantContext tenant, long studentId) {
    if (caller == null || tenant == null || !caller.isParent() || studentId < 1) {
      throw new InvalidInstitutionContextException(
          403, "GUARDIAN_CHILD_ACCESS_REQUIRED",
          "A valid parent session and selected child are required.");
    }
    Boolean allowed = jdbc.queryForObject("""
        select exists(
          select 1
          from guardian_student gs
          join student st on st.id=gs.student_id and st.active=true
          join institution_membership m on m.student_id=st.id
            and m.institution_id=? and m.status='ACTIVE'
          join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
          where gs.guardian_user_id=? and gs.student_id=?
            and gs.active=true and gs.consent_status='CONSENTED'
        )
        """, Boolean.class, tenant.institutionId(), caller.userId(), studentId);
    if (!Boolean.TRUE.equals(allowed)) {
      throw new InvalidInstitutionContextException(
          403, "GUARDIAN_CHILD_INSTITUTION_MISMATCH",
          "The selected child is not actively linked to this parent in the selected institution.");
    }
  }

  /** Separate restore-only proof: this parent previously held consent for the archived child in this tenant. */
  public void assertArchivedGuardianChildMembership(
      AuthContext caller, InstitutionTenantContext tenant, long studentId) {
    if (caller == null || tenant == null || !caller.isParent() || studentId < 1) {
      throw new InvalidInstitutionContextException(
          403, "GUARDIAN_CHILD_ACCESS_REQUIRED",
          "A valid parent session and selected child are required.");
    }
    Boolean allowed = jdbc.queryForObject("""
        select exists(
          select 1
          from guardian_student gs
          join student st on st.id=gs.student_id and st.environment='PRODUCTION'
          join institution_membership m on m.student_id=st.id
            and m.institution_id=? and m.status='ACTIVE'
          join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
          where gs.guardian_user_id=? and gs.student_id=?
            and gs.active=false and gs.consent_status='REVOKED'
        )
        """, Boolean.class, tenant.institutionId(), caller.userId(), studentId);
    if (!Boolean.TRUE.equals(allowed)) {
      throw new InvalidInstitutionContextException(
          403, "ARCHIVED_GUARDIAN_CHILD_ACCESS_REQUIRED",
          "This archived child is not available to this parent in the selected institution.");
    }
  }
  public static final class InvalidInstitutionContextException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    public InvalidInstitutionContextException(int statusCode, String errorCode, String message) {
      super(message);
      this.statusCode = statusCode;
      this.errorCode = errorCode;
    }

    public int statusCode() { return statusCode; }
    public String errorCode() { return errorCode; }
  }
}
