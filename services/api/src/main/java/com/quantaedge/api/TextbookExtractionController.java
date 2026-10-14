package com.quantaedge.api;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
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

  public TextbookExtractionController(
      AuthorizationService authorization, StaffAuditService staffAudit,
      TextbookExtractionService extraction) {
    this.authorization = authorization;
    this.staffAudit = staffAudit;
    this.extraction = extraction;
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
      @RequestAttribute(value = "authContext", required = false) AuthContext context) throws java.io.IOException {
    context = authorization.requirePermission(context, "CONTENT_EDIT");
    Map<String, Object> imported = extraction.importGhcrBook(manifest, pages, context.staffId());
    staffAudit.recordAction(context, "/api/v1/admin/textbook-extraction/ghcr-books",
        "Imported complete-book extracted pages as draft; source remains unavailable to tutor until explicit approval.");
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
