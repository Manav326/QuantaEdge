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

  private final JdbcTemplate jdbc;

  public InstitutionTenantContextService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns null only when the request did not select a tenant; it never guesses a default. */
  public InstitutionTenantContext resolve(String requestedCode) {
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
