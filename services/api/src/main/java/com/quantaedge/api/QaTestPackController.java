package com.quantaedge.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HRMS platform-owner-only API for isolated synthetic QA test-pack lifecycles. */
@RestController
@RequestMapping("/api/v1/hrms/platform/qa")
public class QaTestPackController {
  private final AuthorizationService authorization;
  private final QaTestPackService qa;

  public QaTestPackController(AuthorizationService authorization, QaTestPackService qa) {
    this.authorization = authorization;
    this.qa = qa;
  }

  @GetMapping("/status")
  public Map<String,Object> status(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireOwner(context);
    return qa.environmentStatus();
  }

  @GetMapping("/packs")
  public Map<String,Object> packs(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireOwner(context);
    return Map.of("items", qa.packs());
  }

  @GetMapping("/runs")
  public Map<String,Object> runs(
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireOwner(context);
    return Map.of("items", qa.listRuns());
  }

  @PostMapping("/runs/install")
  public Map<String,Object> install(
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    AuthContext owner = requireOwner(context);
    return qa.install(text(body, "packKey"), text(body, "idempotencyKey"), owner.staffId());
  }

  @PostMapping("/runs/{runId}/verify")
  public Map<String,Object> verify(
      @PathVariable UUID runId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    AuthContext owner = requireOwner(context);
    return qa.verifyRun(runId, owner.staffId());
  }

  @GetMapping("/runs/{runId}")
  public Map<String,Object> run(
      @PathVariable UUID runId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireOwner(context);
    return qa.getRun(runId);
  }

  @GetMapping("/runs/{runId}/uninstall-plan")
  public Map<String,Object> uninstallPlan(
      @PathVariable UUID runId,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    requireOwner(context);
    return qa.uninstallPlan(runId);
  }

  @PostMapping("/runs/{runId}/uninstall")
  public Map<String,Object> uninstall(
      @PathVariable UUID runId,
      @RequestBody(required=false) Map<String,Object> body,
      @RequestAttribute(value="authContext", required=false) AuthContext context) {
    AuthContext owner = requireOwner(context);
    return qa.uninstall(runId, text(body, "confirmRunId"), owner.staffId());
  }

  private AuthContext requireOwner(AuthContext context) {
    try {
      return authorization.requirePermission(context, "QE_PLATFORM_OWNER");
    } catch (SecurityException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HRMS platform-owner permission is required.", ex);
    }
  }

  private String text(Map<String,Object> body, String key) {
    Object value = body == null ? null : body.get(key);
    return value == null ? "" : String.valueOf(value).trim();
  }
}
