package com.quantaedge.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authorized controls for private textbook extraction. Extraction never publishes content. */
@RestController
@RequestMapping("/api/v1/admin/textbook-extraction")
public class TextbookExtractionController {
  private final AuthorizationService authorization;
  private final StaffAuditService staffAudit;
  private final TextbookExtractionService extraction;
  private final String adminContentToken;
  private final String appEnvironment;

  public TextbookExtractionController(
      AuthorizationService authorization, StaffAuditService staffAudit,
      TextbookExtractionService extraction,
      @Value("${app.admin.content-token:}") String adminContentToken,
      @Value("${app.environment:production}") String appEnvironment) {
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.extraction = extraction;
    this.adminContentToken = adminContentToken == null ? "" : adminContentToken;
    this.appEnvironment = appEnvironment == null ? "local" : appEnvironment;
  }

  /**
   * Validate the narrowly scoped local automation credential or a normal staff session.
   * This credential is intentionally available only for draft imports in non-production environments.
   */
  @GetMapping("/auth-check")
  public Map<String, Object> authCheck(
      @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    if (isValidLocalImportToken(suppliedToken)) {
      return Map.of("authenticated", true, "authMode", "ADMIN_TOKEN", "scope", "TEXTBOOK_DRAFT_IMPORT");
    }
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    return Map.of("authenticated", true, "authMode", "STAFF_SESSION",
        "workspace", context.workspace() == null ? "STAFF" : context.workspace(),
        "staffId", context.staffId());
  }

  private boolean isValidLocalImportToken(String suppliedToken) {
    String environment = appEnvironment.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("local", "dev", "development").contains(environment)
        || adminContentToken.isBlank() || suppliedToken == null || suppliedToken.isBlank()) {
      return false;
    }
    return MessageDigest.isEqual(
        adminContentToken.getBytes(StandardCharsets.UTF_8),
        suppliedToken.getBytes(StandardCharsets.UTF_8));
  }

  @PostMapping("/assets/{assetId}")
  public Map<String, Object> queueExtraction(
      @PathVariable long assetId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    Map<String, Object> job = extraction.queue(assetId, context.staffId());
    if ("PENDING".equals(String.valueOf(job.get("status")))) extraction.start(assetId);
    staffAudit.recordAction(context, "/api/v1/admin/textbook-extraction/assets/" + assetId,
        "Queued asynchronous page-level extraction for an approved textbook PDF; no content was published.");
    return Map.of("queued", true, "job", job);
  }

  @PostMapping(value = "/ghcr-books", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> importGhcrBook(
      @RequestPart("manifest") MultipartFile manifest,
      @RequestPart("pages") MultipartFile pages,
      @RequestHeader(value = "X-Admin-Token", required = false) String suppliedToken,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) throws java.io.IOException {
    boolean machineToken = isValidLocalImportToken(suppliedToken);
    Long importingStaffId = null;
    if (!machineToken) {
      context = authorization.requirePermission(context, "CONTENT_EDIT");
      importingStaffId = context.staffId();
    }
    Map<String, Object> imported = extraction.importGhcrBook(manifest, pages, importingStaffId);
    if (machineToken) {
      staffAudit.recordMachineAction("/api/v1/admin/textbook-extraction/ghcr-books",
          "Draft-only GHCR textbook import authorized by the local X-Admin-Token. Source approval and publication were not performed.");
    } else {
      staffAudit.recordAction(context, "/api/v1/admin/textbook-extraction/ghcr-books",
          "Imported complete-book extracted pages as draft; source remains unavailable to tutor until explicit approval.");
    }
    return imported;
  }

  @PostMapping("/ghcr-books/{sourceBookId}/approve")
  public Map<String, Object> approveGhcrBook(
      @PathVariable long sourceBookId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    Map<String, Object> approved = extraction.approveGhcrBook(sourceBookId, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/textbook-extraction/ghcr-books/" + sourceBookId + "/approve",
        "Approved a source-reviewed GHCR textbook page index for tutor retrieval.");
    return approved;
  }

  @GetMapping("/assets/{assetId}")
  public Map<String, Object> extractionStatus(
      @PathVariable long assetId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context, "CONTENT_EDIT");
    return extraction.status(assetId);
  }
}
