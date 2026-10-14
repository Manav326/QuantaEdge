package com.quantaedge.api;

import java.util.Map;
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
    staffAudit.recordAction(context, "/api/v1/admin/textbook-extraction/assets/" + assetId,
        "Queued asynchronous page-level extraction for an approved textbook PDF; no content was published.");
    return Map.of("queued", true, "job", job);
  }

  @GetMapping("/assets/{assetId}")
  public Map<String, Object> extractionStatus(
      @PathVariable long assetId,
      @RequestAttribute(value = "authContext", required = false) AuthContext context) {
    authorization.requirePermission(context, "CONTENT_EDIT");
    return extraction.status(assetId);
  }
}
