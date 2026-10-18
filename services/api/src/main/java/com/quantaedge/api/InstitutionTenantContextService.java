package com.quantaedge.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
      "/api/v1/students/me/programs",
      "/api/v1/marketplace",
      "/api/v1/parent/course-requests",
      "/api/v1/lessons",
      "/api/v1/questions",
      "/api/v1/structured-lessons",
      "/api/v1/practice",
      "/api/v1/learning/",
      "/api/v1/guardians/children/",
      "/api/v1/certificates",
      "/api/v1/tutor",
      "/api/v1/learning-chat"
  );

  private final JdbcTemplate jdbc;

  public InstitutionTenantContextService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns null only when the request did not select a tenant; it never guesses a default. */
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

  public InstitutionTenantContext requireSelector(String requestedCode) {
    if (requestedCode == null || requestedCode.isBlank()) {
      throw new InvalidInstitutionContextException(
          400, "INSTITUTION_CONTEXT_REQUIRED",
          "Select the institution link for this learning request. No default institution is assumed.");
    }
    InstitutionTenantContext tenant = resolve(requestedCode);
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
            from guardian_student gs
            join student st on st.id=gs.student_id and st.active=true
            join institution_membership m on m.student_id=st.id
              and m.institution_id=? and m.status='ACTIVE'
            join institution_tenant t on t.id=m.institution_id and t.status='ACTIVE'
            where gs.guardian_user_id=? and gs.active=true
              and gs.consent_status='CONSENTED'
          )
          """, Boolean.class, tenant.institutionId(), caller.userId());
    } else {
      member = false;
    }

    if (!Boolean.TRUE.equals(member)) {
      throw new InvalidInstitutionContextException(
          403, "INSTITUTION_MEMBERSHIP_REQUIRED",
          "Your account has no active membership in the selected institution.");
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
