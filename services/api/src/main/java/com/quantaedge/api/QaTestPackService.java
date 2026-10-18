package com.quantaedge.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Platform-owner QA pack lifecycle. Pack definitions and cleanup SQL are code-owned allow-lists;
 * callers cannot provide SQL, table names, file paths or provider locators.
 */
@Service
public class QaTestPackService {
  private static final String PACK_KEY = "TENANT_LEARNER_BOUNDARY_V1";
  private static final int PACK_VERSION = 1;
  private static final String PACK_DEFINITION = "TENANT_LEARNER_BOUNDARY_V1:1:two-tenants-parent-child-adult-learner-course7-progress-attempts";
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,119}$");
  private static final Pattern ENVIRONMENT_LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,79}$");
  private static final Pattern QA_DATABASE = Pattern.compile("(?i)^[a-z0-9][a-z0-9_-]*(?:_qa|_test|_ci|_integration)(?:_[a-z0-9]+)?$");

  private final JdbcTemplate jdbc;
  private final Environment springEnvironment;
  private final TransactionTemplate transaction;
  private final ObjectMapper mapper;
  private final boolean qaEnabled;
  private final boolean environmentApproved;
  private final String environmentLabel;
  private final String targetDatabaseName;
  private final String fixturePassword;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

  public QaTestPackService(
      JdbcTemplate jdbc,
      Environment springEnvironment,
      PlatformTransactionManager transactionManager,
      ObjectMapper mapper,
      @Value("${app.qa.enabled:false}") boolean qaEnabled,
      @Value("${app.qa.environment-approved:false}") boolean environmentApproved,
      @Value("${app.qa.environment-label:}") String environmentLabel,
      @Value("${app.qa.target-database:}") String targetDatabaseName,
      @Value("${app.qa.fixture-password:}") String fixturePassword) {
    this.jdbc = jdbc;
    this.springEnvironment = springEnvironment;
    this.transaction = new TransactionTemplate(transactionManager);
    this.mapper = mapper;
    this.qaEnabled = qaEnabled;
    this.environmentApproved = environmentApproved;
    this.environmentLabel = environmentLabel == null ? "" : environmentLabel.trim();
    this.targetDatabaseName = targetDatabaseName == null ? "" : targetDatabaseName.trim();
    this.fixturePassword = fixturePassword == null ? "" : fixturePassword;
  }

  public Map<String,Object> environmentStatus() {
    Gate gate = evaluateGate();
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("featureEnabled", qaEnabled);
    result.put("environmentApproved", environmentApproved);
    result.put("fixtureCredentialsConfigured", fixturePassword.length() >= 10 && fixturePassword.chars().noneMatch(Character::isISOControl));
    result.put("environmentLabel", environmentLabel.isBlank() ? null : environmentLabel);
    result.put("currentDatabase", gate.databaseName);
    result.put("configuredTargetDatabase", targetDatabaseName.isBlank() ? null : targetDatabaseName);
    result.put("databaseApproved", gate.databaseApproved);
    result.put("productionProfileDetected", gate.productionProfile);
    result.put("writeAllowed", gate.allowed);
    result.put("reason", gate.reason);
    result.put("defaultOff", true);
    result.put("acceptsCallerSelectedDatabase", false);
    result.put("checkedAt", Instant.now().toString());
    return result;
  }

  public List<Map<String,Object>> packs() {
    Map<String,Object> pack = new LinkedHashMap<>();
    pack.put("packKey", PACK_KEY);
    pack.put("version", PACK_VERSION);
    pack.put("title", "Tenant and learner boundary smoke pack");
    pack.put("summary", "Two synthetic institutions, two parent-child pairs, one independent learner, class-7 tracks, progress and contrasting question attempts.");
    pack.put("personas", List.of("Parent A", "Minor child A", "Parent B", "Minor child B", "Independent adult learner"));
    pack.put("createsExternalResources", false);
    pack.put("requiresPublishedClass7Question", true);
    pack.put("status", "AVAILABLE");
    pack.put("coverageLimit", "This starter pack does not yet seed HRMS/payroll, live-media or provider resources.");
    return List.of(pack);
  }

  public List<Map<String,Object>> listRuns() {
    return jdbc.queryForList("""
      select run_id,pack_key,pack_version,state,environment_label,target_database_label,
             planned_resource_count,created_resource_count,cleaned_resource_count,error_code,
             error_summary,created_at,updated_at,installed_at,uninstalled_at
      from qa_test_run order by created_at desc,run_id desc limit 50
      """);
  }

  public Map<String,Object> getRun(UUID runId) {
    Map<String,Object> run = findRun(runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QA run not found.");
    Map<String,Object> result = new LinkedHashMap<>(run);
    result.put("protected_baseline", readJsonMap(run.get("protected_baseline")));
    result.put("fixture_summary", readJsonMap(run.get("fixture_summary")));
    result.put("cleanup_report", readJsonMap(run.get("cleanup_report")));
    result.put("fixtureSummary", readJsonMap(run.get("fixture_summary")));
    result.put("resources", jdbc.queryForList("""
      select resource_key,resource_kind,resource_id,resource_ref,dependency_keys,
             cleanup_order,state,created_at,cleaned_at,cleanup_detail
      from qa_test_run_resource where run_id=? order by cleanup_order,resource_key
      """, runId));
    result.put("events", jdbc.queryForList("""
      select event_type,from_state,to_state,details,created_at
      from qa_test_run_event where run_id=? order by id
      """, runId));
    result.put("checks", jdbc.queryForList("""
      select check_key,status,expected,actual,details,tested_commit_sha,executed_at
      from qa_test_run_check where run_id=? order by executed_at desc,id desc
      """, runId));
    return result;
  }

  public Map<String,Object> uninstallPlan(UUID runId) {
    Map<String,Object> run = findRun(runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QA run not found.");
    List<Map<String,Object>> resources = jdbc.queryForList("""
      select resource_key,resource_kind,resource_id,cleanup_order,state
      from qa_test_run_resource where run_id=? and state<>'CLEANED'
      order by cleanup_order,resource_key
      """, runId);
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("runId", runId.toString());
    result.put("state", run.get("state"));
    result.put("resourceCount", resources.size());
    result.put("resources", resources);
    result.put("requiresExactRunIdConfirmation", true);
    result.put("willDeleteByBroadTestFlag", false);
    result.put("willDeleteSharedCurriculum", false);
    result.put("willDeleteQuantaEdgeTenant", false);
    result.put("cleanupOrder", "dependency-first; every resource must match both manifest ID and run ownership");
    result.put("environmentStatus", environmentStatus());
    return result;
  }

  public Map<String,Object> install(String rawPackKey, String rawIdempotencyKey, Long actorStaffId) {
    Gate gate = evaluateGate();
    requireWritableEnvironment(gate);
    String packKey = rawPackKey == null ? "" : rawPackKey.trim().toUpperCase(Locale.ROOT);
    String idempotencyKey = rawIdempotencyKey == null ? "" : rawIdempotencyKey.trim();
    if (!PACK_KEY.equals(packKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown QA pack. Select a versioned pack from the server-provided list.");
    }
    if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An 8–120 character idempotency key is required.");
    }
    if (fixturePassword.length() < 10 || fixturePassword.chars().anyMatch(Character::isISOControl)) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The QA-only fixture password configuration is invalid.");
    }

    Map<String,Object> prior = findIdempotentRun(packKey, PACK_VERSION, idempotencyKey);
    UUID runId;
    if (prior != null) {
      runId = UUID.fromString(String.valueOf(prior.get("run_id")));
      String priorState = String.valueOf(prior.get("state"));
      if ("INSTALLED".equals(priorState)) {
        Map<String,Object> replay = getRun(runId);
        replay.put("replayed", true);
        replay.put("credentialsIncluded", false);
        return replay;
      }
      if ("UNINSTALLED".equals(priorState) || "UNINSTALL_BLOCKED".equals(priorState)
          || "UNINSTALLING".equals(priorState)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "This idempotency key already belongs to an install/uninstall run. Use a new key for a distinct run.");
      }
      if ("INSTALLING".equals(priorState)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "This QA run is already installing.");
      }
      if (!"INSTALL_FAILED".equals(priorState)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "This QA run cannot be retried from its current state.");
      }
      int claimed = jdbc.update("""
        update qa_test_run set state='INSTALLING',error_code=null,error_summary=null,
          environment_label=?,target_database_label=?,updated_at=now()
        where run_id=? and state='INSTALL_FAILED'
        """, environmentLabel, gate.databaseName, runId);
      if (claimed != 1) {
        Map<String,Object> current = findIdempotentRun(packKey, PACK_VERSION, idempotencyKey);
        if (current != null && "INSTALLED".equals(String.valueOf(current.get("state")))) {
          Map<String,Object> replay = getRun(UUID.fromString(String.valueOf(current.get("run_id"))));
          replay.put("replayed", true);
          replay.put("credentialsIncluded", false);
          return replay;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Another request is already retrying this QA run.");
      }
      addEvent(runId, "INSTALL_RETRY_STARTED", priorState, "INSTALLING", actorStaffId,
          Map.of("reason", "same idempotency key resumes the failed pack run"));
    } else {
      runId = UUID.randomUUID();
      Map<String,Object> baseline = baselineSnapshot();
      int inserted = jdbc.update("""
        insert into qa_test_run(
          run_id,pack_key,pack_version,idempotency_key,state,environment_label,
          target_database_label,source_commit_sha,pack_definition_sha256,created_by_staff_id,
          protected_baseline
        ) values (?,?,?,?,'INSTALLING',?,?,?,?,?,?::jsonb)
        on conflict(pack_key,pack_version,idempotency_key) do nothing
        """,
        runId, packKey, PACK_VERSION, idempotencyKey, environmentLabel, gate.databaseName,
        sourceCommitSha(), sha256(PACK_DEFINITION), actorStaffId, json(baseline));
      if (inserted != 1) {
        Map<String,Object> concurrent = findIdempotentRun(packKey, PACK_VERSION, idempotencyKey);
        if (concurrent != null && "INSTALLED".equals(String.valueOf(concurrent.get("state")))) {
          Map<String,Object> replay = getRun(UUID.fromString(String.valueOf(concurrent.get("run_id"))));
          replay.put("replayed", true);
          replay.put("credentialsIncluded", false);
          return replay;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "An install with this idempotency key is already in progress or has failed. Refresh the run list and retry the same key only after reviewing its state.");
      }
      addEvent(runId, "INSTALL_STARTED", null, "INSTALLING", actorStaffId,
          Map.of("packKey", packKey, "packVersion", PACK_VERSION));
    }

    InstallOutcome outcome;
    try {
      outcome = transaction.execute(status -> {
        Map<String,Object> fixtureResult = seedTenantLearnerPack(runId, actorStaffId);
        int resourceCount = countResources(runId);
        String manifestHash = manifestChecksum(runId);
        int finalized = jdbc.update("""
          update qa_test_run set state='INSTALLED',planned_resource_count=?,
            created_resource_count=?,cleaned_resource_count=0,manifest_sha256=?,fixture_summary=?::jsonb,
            installed_at=now(),updated_at=now(),error_code=null,error_summary=null
          where run_id=? and state='INSTALLING'
          """, resourceCount, resourceCount, manifestHash, json(fixtureResult.get("fixtureSummary")), runId);
        if (finalized != 1) throw new IllegalStateException("QA run state changed while installation was in progress.");
        addEvent(runId, "INSTALL_SUCCEEDED", "INSTALLING", "INSTALLED", actorStaffId,
            Map.of("createdResourceCount", resourceCount, "manifestSha256", manifestHash));
        return new InstallOutcome(fixtureResult, resourceCount, manifestHash);
      });
      if (outcome == null) throw new IllegalStateException("Install transaction did not return an outcome.");
    } catch (Exception ex) {
      String summary = safeInstallError(ex);
      jdbc.update("""
        update qa_test_run set state='INSTALL_FAILED',error_code=?,error_summary=?,updated_at=now()
        where run_id=? and state='INSTALLING'
        """, errorCode(ex), summary, runId);
      addEvent(runId, "INSTALL_FAILED", "INSTALLING", "INSTALL_FAILED", actorStaffId,
          Map.of("errorCode", errorCode(ex), "summary", summary));
      Map<String,Object> result = getRun(runId);
      result.put("replayed", false);
      result.put("credentialsIncluded", false);
      result.put("message", "Installation transaction rolled back. Correct the blocker and retry with the same idempotency key.");
      return result;
    }
    Map<String,Object> result = getRun(runId);
    result.put("replayed", false);
    result.put("credentialsIncluded", true);
    result.put("oneTimeSyntheticCredentials", outcome.fixtureResult().get("credentials"));
    result.put("credentialHandling", "Plaintext credentials are returned only by this install response; they are not stored in the run manifest.");
    return result;
  }

  public Map<String,Object> uninstall(UUID runId, String confirmRunId, Long actorStaffId) {
    if (confirmRunId == null || !runId.toString().equalsIgnoreCase(confirmRunId.trim())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type the exact run ID to confirm uninstall.");
    }
    Map<String,Object> run = findRun(runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QA run not found.");
    if ("UNINSTALLED".equals(String.valueOf(run.get("state")))) {
      Map<String,Object> result = getRun(runId);
      result.put("replayed", true);
      return result;
    }
    requireWritableEnvironment(evaluateGate());
    String state = String.valueOf(run.get("state"));
    if (!List.of("INSTALLED","UNINSTALL_BLOCKED","UNINSTALLING","INSTALL_FAILED").contains(state)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This QA run cannot be uninstalled from state " + state + ".");
    }

    if (!"UNINSTALLING".equals(state)) {
      jdbc.update("update qa_test_run set state='UNINSTALLING',error_code=null,error_summary=null,updated_at=now() where run_id=?", runId);
      addEvent(runId, "UNINSTALL_STARTED", state, "UNINSTALLING", actorStaffId,
          Map.of("requestedBy", actorStaffId == null ? "unknown" : actorStaffId));
    }

    try {
      List<Map<String,Object>> resources = jdbc.queryForList("""
        select id,resource_key,resource_kind,resource_id,cleanup_order,state
        from qa_test_run_resource where run_id=? and state<>'CLEANED'
        order by cleanup_order,resource_key
        """, runId);
      for (Map<String,Object> resource : resources) {
        final long manifestId = number(resource.get("id"));
        transaction.execute(status -> {
          cleanResource(runId, resource, manifestId);
          return null;
        });
      }

      Map<String,Object> baselineBefore = readJsonMap(run.get("protected_baseline"));
      Map<String,Object> baselineAfter = baselineSnapshot();
      boolean baselineMatches = baselineSnapshotsMatch(baselineBefore, baselineAfter);
      Map<String,Object> cleanup = new LinkedHashMap<>();
      cleanup.put("baselineCheckPassed", baselineMatches);
      cleanup.put("baselineBefore", baselineBefore);
      cleanup.put("baselineAfter", baselineAfter);
      cleanup.put("resourcesRemaining", countUncleanedResources(runId));
      cleanup.put("verifiedAt", Instant.now().toString());
      if (!baselineMatches) {
        cleanup.put("warning", "Protected-row fingerprint/counts changed during the run. All manifest-owned resources may be cleaned, but automated preservation certification is blocked for manual review.");
        jdbc.update("""
          update qa_test_run set state='UNINSTALL_BLOCKED',cleaned_resource_count=?,
            cleanup_report=?::jsonb,error_code='PROTECTED_BASELINE_DRIFT',
            error_summary='Owned resources were processed, but protected baseline fingerprints changed; manual review is required.',
            updated_at=now()
          where run_id=?
          """, countCleanedResources(runId), json(cleanup), runId);
        addEvent(runId, "UNINSTALL_BASELINE_CHECK_BLOCKED", "UNINSTALLING", "UNINSTALL_BLOCKED",
            actorStaffId, Map.of("baselineCheckPassed", false));
      } else {
        jdbc.update("""
          update qa_test_run set state='UNINSTALLED',cleaned_resource_count=?,
            cleanup_report=?::jsonb,error_code=null,error_summary=null,
            uninstalled_at=now(),updated_at=now()
          where run_id=?
          """, countCleanedResources(runId), json(cleanup), runId);
        addEvent(runId, "UNINSTALL_SUCCEEDED", "UNINSTALLING", "UNINSTALLED", actorStaffId,
            Map.of("baselineCheckPassed", true, "cleanedResourceCount", countCleanedResources(runId)));
      }
    } catch (Exception ex) {
      String summary = safeCleanupError(ex);
      jdbc.update("""
        update qa_test_run set state='UNINSTALL_BLOCKED',error_code=?,error_summary=?,
          cleaned_resource_count=?,updated_at=now()
        where run_id=?
        """, errorCode(ex), summary, countCleanedResources(runId), runId);
      addEvent(runId, "UNINSTALL_BLOCKED", "UNINSTALLING", "UNINSTALL_BLOCKED", actorStaffId,
          Map.of("errorCode", errorCode(ex), "summary", summary));
    }
    Map<String,Object> result = getRun(runId);
    result.put("replayed", false);
    result.put("uninstallPlan", uninstallPlan(runId));
    return result;
  }

  public Map<String,Object> verifyRun(UUID runId, Long actorStaffId) {
    requireWritableEnvironment(evaluateGate());
    Map<String,Object> run = findRun(runId);
    if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QA run not found.");
    if (!"INSTALLED".equals(String.valueOf(run.get("state")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an installed QA run can be verified.");
    }
    List<Map<String,Object>> checks = new ArrayList<>();
    long tenantCount = scalarCount("select count(*) from institution_tenant where qa_test_run_id=?", runId);
    addCheck(runId, checks, "PACK_TWO_TENANTS", tenantCount == 2, "2 run-owned active institutions",
        Long.toString(tenantCount), Map.of("actual", tenantCount));

    long parentCount = scalarCount("select count(*) from user_account where qa_test_run_id=? and role='PARENT' and active=true", runId);
    addCheck(runId, checks, "PACK_TWO_PARENTS", parentCount == 2, "2 active synthetic parent accounts",
        Long.toString(parentCount), Map.of("actual", parentCount));

    long studentCount = scalarCount("select count(*) from student where qa_test_run_id=? and active=true", runId);
    addCheck(runId, checks, "PACK_THREE_LEARNERS", studentCount == 3, "3 active synthetic student profiles",
        Long.toString(studentCount), Map.of("actual", studentCount));

    long guardianCount = scalarCount("select count(*) from guardian_student where qa_test_run_id=? and active=true and consent_status='CONSENTED'", runId);
    addCheck(runId, checks, "PACK_TWO_CONSENTED_GUARDIAN_LINKS", guardianCount == 2,
        "2 active, consented parent-child relationships", Long.toString(guardianCount), Map.of("actual", guardianCount));

    long crossTenantGuardianLinks = scalarCount("""
      select count(*) from guardian_student gs
      join institution_user_membership um on um.user_id=gs.guardian_user_id and um.status='ACTIVE'
      join institution_membership sm on sm.student_id=gs.student_id and sm.status='ACTIVE'
      where gs.qa_test_run_id=? and um.qa_test_run_id=? and sm.qa_test_run_id=?
        and um.institution_id<>sm.institution_id
      """, runId, runId, runId);
    addCheck(runId, checks, "GUARDIAN_LINKS_STAY_IN_TENANT", crossTenantGuardianLinks == 0,
        "0 parent-child links crossing institution membership", Long.toString(crossTenantGuardianLinks),
        Map.of("unexpectedCrossTenantLinks", crossTenantGuardianLinks));

    long userMembershipCount = scalarCount("select count(*) from institution_user_membership where qa_test_run_id=? and status='ACTIVE'", runId);
    addCheck(runId, checks, "PACK_THREE_USER_MEMBERSHIPS", userMembershipCount == 3,
        "3 active user institution memberships", Long.toString(userMembershipCount), Map.of("actual", userMembershipCount));

    long studentMembershipCount = scalarCount("select count(*) from institution_membership where qa_test_run_id=? and status='ACTIVE'", runId);
    addCheck(runId, checks, "PACK_THREE_STUDENT_MEMBERSHIPS", studentMembershipCount == 3,
        "3 active student institution memberships", Long.toString(studentMembershipCount), Map.of("actual", studentMembershipCount));

    long offScopeProgress = scalarCount("""
      select count(*) from student_lesson_progress p
      where p.qa_test_run_id=? and not exists (
        select 1 from institution_membership m
        where m.student_id=p.student_id and m.institution_id=p.institution_id
          and m.status='ACTIVE' and m.qa_test_run_id=?
      )
      """, runId, runId);
    addCheck(runId, checks, "LEARNING_PROGRESS_HAS_TENANT_MEMBERSHIP", offScopeProgress == 0,
        "0 progress rows without matching active tenant membership", Long.toString(offScopeProgress),
        Map.of("offScopeRows", offScopeProgress));

    long progressCount = scalarCount("select count(*) from student_lesson_progress where qa_test_run_id=?", runId);
    addCheck(runId, checks, "PACK_THREE_PROGRESS_ROWS", progressCount == 3,
        "3 synthetic lesson progress rows", Long.toString(progressCount), Map.of("actual", progressCount));

    long correctAttempts = scalarCount("select count(*) from student_question_attempt where qa_test_run_id=? and correct=true", runId);
    long incorrectAttempts = scalarCount("select count(*) from student_question_attempt where qa_test_run_id=? and correct=false", runId);
    addCheck(runId, checks, "PACK_CONTRASTING_ANSWER_OUTCOMES", correctAttempts == 2 && incorrectAttempts == 1,
        "2 correct and 1 incorrect synthetic question attempt",
        "correct=" + correctAttempts + ", incorrect=" + incorrectAttempts,
        Map.of("correct", correctAttempts, "incorrect", incorrectAttempts));

    List<Map<String,Object>> resources = jdbc.queryForList("""
      select id,resource_key,resource_kind,resource_id,state
      from qa_test_run_resource where run_id=? order by resource_key
      """, runId);
    long missingOwnedResources = 0;
    for (Map<String,Object> resource : resources) {
      boolean found = resourceIsOwned(runId, resource);
      if (!found) missingOwnedResources++;
      String key = "RESOURCE_OWNED_" + String.valueOf(resource.get("resource_key")).replaceAll("[^A-Za-z0-9_]", "_");
      addCheck(runId, checks, key, found, "Exact manifest ID exists with this run ownership marker",
          found ? "row exists and is owned by this run" : "row missing or ownership marker mismatched",
          Map.of("resourceKind", String.valueOf(resource.get("resource_kind")),
              "resourceId", String.valueOf(resource.get("resource_id"))));
    }
    addCheck(runId, checks, "MANIFEST_RESOURCE_OWNERSHIP_COMPLETE", missingOwnedResources == 0,
        "Every manifest entry resolves to its exact run-owned row",
        "missingOrUnowned=" + missingOwnedResources + "; manifestRows=" + resources.size(),
        Map.of("missingOrUnowned", missingOwnedResources, "manifestRows", resources.size()));

    Map<String,Object> baselineBefore = readJsonMap(run.get("protected_baseline"));
    Map<String,Object> baselineAfter = baselineSnapshot();
    boolean baselineMatches = baselineSnapshotsMatch(baselineBefore, baselineAfter);
    addCheck(runId, checks, "PROTECTED_BASELINE_UNCHANGED", baselineMatches,
        "Protected non-QA institution/account/student fingerprints match the captured baseline",
        baselineMatches ? "baseline matches" : "protected counts or row fingerprints changed",
        Map.of("baselineBefore", baselineBefore, "baselineAfter", baselineAfter));

    long unexpectedExternalResources = scalarCount("""
      select count(*) from qa_test_run_resource
      where run_id=? and resource_kind not in (
        'INSTITUTION','INSTITUTION_BRANDING','USER_ACCOUNT','STUDENT','GUARDIAN_STUDENT',
        'INSTITUTION_USER_MEMBERSHIP','INSTITUTION_MEMBERSHIP','STUDENT_LOGIN_CREDENTIAL',
        'STUDENT_TRACK_ENROLLMENT','STUDENT_LESSON_PROGRESS','STUDENT_QUESTION_ATTEMPT'
      )
      """, runId);
    addCheck(runId, checks, "STARTER_PACK_HAS_NO_EXTERNAL_RESOURCES", unexpectedExternalResources == 0,
        "No external provider or storage resource is created by this starter pack",
        Long.toString(unexpectedExternalResources), Map.of("unexpectedExternalResources", unexpectedExternalResources));

    long failed = checks.stream().filter(check -> "FAIL".equals(check.get("status"))).count();
    long passed = checks.size() - failed;
    String overall = failed == 0 ? "PASS" : "FAIL";
    addEvent(runId, "VERIFY_COMPLETED", "INSTALLED", "INSTALLED", actorStaffId,
        Map.of("overallStatus", overall, "passedChecks", passed, "failedChecks", failed));
    Map<String,Object> result = getRun(runId);
    result.put("verification", Map.of("overallStatus", overall, "passedChecks", passed, "failedChecks", failed));
    return result;
  }

  private void addCheck(UUID runId, List<Map<String,Object>> output, String checkKey,
      boolean passed, String expected, String actual, Map<String,Object> details) {
    String status = passed ? "PASS" : "FAIL";
    jdbc.update("""
      insert into qa_test_run_check(run_id,check_key,status,expected,actual,details,tested_commit_sha)
      values (?,?,?,?,?,?::jsonb,?)
      """, runId, checkKey, status, expected, actual, json(details), sourceCommitSha());
    Map<String,Object> row = new LinkedHashMap<>();
    row.put("check_key", checkKey);
    row.put("status", status);
    row.put("expected", expected);
    row.put("actual", actual);
    row.put("details", details);
    row.put("executed_at", Instant.now().toString());
    output.add(row);
  }

  private long scalarCount(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private boolean resourceIsOwned(UUID runId, Map<String,Object> resource) {
    long id = number(resource.get("resource_id"));
    String kind = String.valueOf(resource.get("resource_kind"));
    String sql = switch (kind) {
      case "STUDENT_QUESTION_ATTEMPT" -> "select exists(select 1 from student_question_attempt where id=? and qa_test_run_id=?)";
      case "STUDENT_LESSON_PROGRESS" -> "select exists(select 1 from student_lesson_progress where id=? and qa_test_run_id=?)";
      case "STUDENT_TRACK_ENROLLMENT" -> "select exists(select 1 from student_track_enrollment where id=? and qa_test_run_id=?)";
      case "STUDENT_LOGIN_CREDENTIAL" -> "select exists(select 1 from student_login_credential where id=? and qa_test_run_id=?)";
      case "GUARDIAN_STUDENT" -> "select exists(select 1 from guardian_student where id=? and qa_test_run_id=?)";
      case "INSTITUTION_MEMBERSHIP" -> "select exists(select 1 from institution_membership where id=? and qa_test_run_id=?)";
      case "INSTITUTION_USER_MEMBERSHIP" -> "select exists(select 1 from institution_user_membership where id=? and qa_test_run_id=?)";
      case "STUDENT" -> "select exists(select 1 from student where id=? and qa_test_run_id=?)";
      case "USER_ACCOUNT" -> "select exists(select 1 from user_account where id=? and qa_test_run_id=?)";
      case "INSTITUTION_BRANDING" -> "select exists(select 1 from institution_branding where institution_id=? and qa_test_run_id=?)";
      case "INSTITUTION" -> "select exists(select 1 from institution_tenant where id=? and qa_test_run_id=?)";
      default -> throw new IllegalArgumentException("Unsupported manifest resource kind for verification.");
    };
    Boolean found = jdbc.queryForObject(sql, Boolean.class, id, runId);
    return Boolean.TRUE.equals(found);
  }

  private Map<String,Object> seedTenantLearnerPack(UUID runId, Long actorStaffId) {
    String slug = runId.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    String passwordHash = passwordEncoder.encode(fixturePassword);
    Long tenantA = insertTenant(runId, actorStaffId, "QAT" + slug + "A", "QA " + slug + " Institution A");
    Long tenantB = insertTenant(runId, actorStaffId, "QAT" + slug + "B", "QA " + slug + " Institution B");
    insertBranding(runId, "tenant-a-branding", tenantA, "QA Institution A", "institution-QAT" + slug + "A");
    insertBranding(runId, "tenant-b-branding", tenantB, "QA Institution B", "institution-QAT" + slug + "B");

    String parentAMobile = testMobile(runId, 1);
    String parentBMobile = testMobile(runId, 2);
    String learnerMobile = testMobile(runId, 3);
    String parentAUsername = "qa_parent_" + slug.toLowerCase(Locale.ROOT) + "a";
    String parentBUsername = "qa_parent_" + slug.toLowerCase(Locale.ROOT) + "b";
    String learnerUsername = "qa_learner_" + slug.toLowerCase(Locale.ROOT);

    Long parentA = insertUser(runId, "QA Parent A " + slug, "PARENT", parentAMobile, parentAUsername, passwordHash, "parent-a");
    Long parentB = insertUser(runId, "QA Parent B " + slug, "PARENT", parentBMobile, parentBUsername, passwordHash, "parent-b");
    Long independentLearner = insertUser(runId, "QA Independent Learner " + slug, "LEARNER",
        learnerMobile, learnerUsername, passwordHash, "independent-learner");

    insertUserMembership(runId, "parent-a-membership", tenantA, parentA, "institution-QAT" + slug + "A", "user-parent-a");
    insertUserMembership(runId, "parent-b-membership", tenantB, parentB, "institution-QAT" + slug + "B", "user-parent-b");
    insertUserMembership(runId, "adult-learner-membership", tenantA, independentLearner, "institution-QAT" + slug + "A", "user-independent-learner");

    Long childA = insertStudent(runId, "QA Child A " + slug, "en", null, "child-a");
    Long childB = insertStudent(runId, "QA Child B " + slug, "hi", null, "child-b");
    Long adultStudent = insertStudent(runId, "QA Adult Learner " + slug, "en", independentLearner, "adult-student");

    insertGuardianStudent(runId, "parent-a-child-a", parentA, childA, "user-parent-a", "student-child-a");
    insertGuardianStudent(runId, "parent-b-child-b", parentB, childB, "user-parent-b", "student-child-b");
    String childAUsername = "qa_child_" + slug.toLowerCase(Locale.ROOT) + "a";
    String childBUsername = "qa_child_" + slug.toLowerCase(Locale.ROOT) + "b";
    insertStudentCredential(runId, "child-a-credential", parentA, childA, childAUsername, passwordHash, "user-parent-a", "student-child-a");
    insertStudentCredential(runId, "child-b-credential", parentB, childB, childBUsername, passwordHash, "user-parent-b", "student-child-b");

    insertStudentMembership(runId, "child-a-institution-membership", tenantA, childA, "institution-QAT" + slug + "A", "student-child-a");
    insertStudentMembership(runId, "child-b-institution-membership", tenantB, childB, "institution-QAT" + slug + "B", "student-child-b");
    insertStudentMembership(runId, "adult-institution-membership", tenantA, adultStudent, "institution-QAT" + slug + "A", "student-adult-student");

    ensureRequiredTracks(runId, childA, tenantA, "student-child-a", "institution-QAT" + slug + "A");
    ensureRequiredTracks(runId, childB, tenantB, "student-child-b", "institution-QAT" + slug + "B");
    ensureRequiredTracks(runId, adultStudent, tenantA, "student-adult-student", "institution-QAT" + slug + "A");

    Map<String,Object> content = requiredPublishedClass7Question();
    Long lessonId = number(content.get("lesson_id"));
    Long questionId = number(content.get("question_id"));
    String correct = String.valueOf(content.get("correct_option"));
    String wrong = String.valueOf(content.get("wrong_option"));
    insertProgress(runId, "child-a-progress", childA, tenantA, lessonId, "student-child-a");
    insertProgress(runId, "child-b-progress", childB, tenantB, lessonId, "student-child-b");
    insertProgress(runId, "adult-progress", adultStudent, tenantA, lessonId, "student-adult-student");
    insertQuestionAttempt(runId, "child-a-correct-attempt", childA, tenantA, questionId, correct, true, "student-child-a");
    insertQuestionAttempt(runId, "child-b-wrong-attempt", childB, tenantB, questionId, wrong, false, "student-child-b");
    insertQuestionAttempt(runId, "adult-correct-attempt", adultStudent, tenantA, questionId, correct, true, "student-adult-student");

    Map<String,Object> credentials = new LinkedHashMap<>();
    credentials.put("parentA", Map.of("institutionCode", "QAT" + slug + "A", "mobile", parentAMobile, "username", parentAUsername, "password", fixturePassword));
    credentials.put("parentB", Map.of("institutionCode", "QAT" + slug + "B", "mobile", parentBMobile, "username", parentBUsername, "password", fixturePassword));
    credentials.put("childA", Map.of("institutionCode", "QAT" + slug + "A", "parentMobile", parentAMobile, "username", childAUsername, "password", fixturePassword));
    credentials.put("childB", Map.of("institutionCode", "QAT" + slug + "B", "parentMobile", parentBMobile, "username", childBUsername, "password", fixturePassword));
    credentials.put("independentLearner", Map.of("institutionCode", "QAT" + slug + "A", "mobile", learnerMobile, "username", learnerUsername, "password", fixturePassword));
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("credentials", credentials);
    result.put("fixtureSummary", Map.of("institutions", 2, "parentAccounts", 2, "minorChildren", 2,
        "independentLearners", 1, "guardianRelationships", 2, "learningProgressRows", 3, "questionAttempts", 3));
    return result;
  }

  private Long insertTenant(UUID runId, Long actorStaffId, String code, String name) {
    Long id = jdbc.queryForObject("""
      insert into institution_tenant(
        public_id,tenant_code,display_name,status,created_by_staff_id,updated_by_staff_id,qa_test_run_id
      ) values (?::uuid,?,?, 'ACTIVE',?,?,?) returning id
      """, Long.class, UUID.randomUUID(), code, name, actorStaffId, actorStaffId, runId);
    addResource(runId, "institution-" + code, "INSTITUTION", id, 100, List.of(), "institution_tenant");
    return id;
  }

  private void insertBranding(UUID runId, String key, Long institutionId, String displayName, String institutionResourceKey) {
    jdbc.update("""
      insert into institution_branding(institution_id,display_name,theme_tokens,qa_test_run_id)
      values (?,?,'{}'::jsonb,?)
      """, institutionId, displayName, runId);
    addResource(runId, key, "INSTITUTION_BRANDING", institutionId, 95,
        List.of(institutionResourceKey), "institution_branding");
  }

  private Long insertUser(UUID runId, String displayName, String role, String mobile, String username,
      String passwordHash, String resourceKey) {
    Long id = jdbc.queryForObject("""
      insert into user_account(
        public_id,mobile_e164,display_name,role,active,password_hash,username,
        mobile_verified_at,qa_test_run_id,created_at,updated_at
      ) values (?,?,?, ?,true,?,?,now(),?,now(),now()) returning id
      """, Long.class, UUID.randomUUID(), mobile, displayName, role, passwordHash, username, runId);
    addResource(runId, "user-" + resourceKey, "USER_ACCOUNT", id, 80, List.of(), "user_account");
    return id;
  }

  private void insertUserMembership(UUID runId, String key, Long institutionId, Long userId,
      String institutionResourceKey, String userResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into institution_user_membership(
        institution_id,user_id,status,joined_at,left_at,qa_test_run_id
      ) values (?,?,'ACTIVE',now(),null,?) returning id
      """, Long.class, institutionId, userId, runId);
    addResource(runId, key, "INSTITUTION_USER_MEMBERSHIP", id, 60,
        List.of(institutionResourceKey, userResourceKey), "institution_user_membership");
  }

  private Long insertStudent(UUID runId, String displayName, String language, Long linkedUserId, String resourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student(
        public_id,display_name,class_code,board,language,environment,active,user_account_id,qa_test_run_id
      ) values (?,?, '7','Bihar Board',?,'PRODUCTION',true,?,?) returning id
      """, Long.class, UUID.randomUUID(), displayName, language, linkedUserId, runId);
    addResource(runId, "student-" + resourceKey, "STUDENT", id, 70, List.of(), "student");
    return id;
  }

  private void insertGuardianStudent(UUID runId, String key, Long parentUserId, Long studentId,
      String parentResourceKey, String studentResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into guardian_student(
        guardian_user_id,student_id,relationship,consent_status,consented_at,active,
        consent_version,consent_source,consented_by,revoked_at,access_level,qa_test_run_id
      ) values (?,?,'PARENT','CONSENTED',now(),true,'qa-consent-v1','QA_TEST_PACK',?,null,'FULL',?)
      returning id
      """, Long.class, parentUserId, studentId, parentUserId, runId);
    addResource(runId, key, "GUARDIAN_STUDENT", id, 40,
        List.of(parentResourceKey, studentResourceKey), "guardian_student");
  }

  private void insertStudentCredential(UUID runId, String key, Long parentUserId, Long studentId,
      String username, String passwordHash, String parentResourceKey, String studentResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student_login_credential(
        guardian_user_id,student_id,username,password_hash,active,qa_test_run_id
      ) values (?,?,?, ?,true,?) returning id
      """, Long.class, parentUserId, studentId, username, passwordHash, runId);
    addResource(runId, key, "STUDENT_LOGIN_CREDENTIAL", id, 35,
        List.of(studentResourceKey, parentResourceKey), "student_login_credential");
  }

  private void insertStudentMembership(UUID runId, String key, Long institutionId, Long studentId,
      String institutionResourceKey, String studentResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into institution_membership(
        institution_id,student_id,status,joined_at,left_at,created_at,updated_at,qa_test_run_id
      ) values (?,?,'ACTIVE',now(),null,now(),now(),?) returning id
      """, Long.class, institutionId, studentId, runId);
    addResource(runId, key, "INSTITUTION_MEMBERSHIP", id, 50,
        List.of(institutionResourceKey, studentResourceKey), "institution_membership");
  }

  private void ensureRequiredTracks(UUID runId, Long studentId, Long institutionId,
      String studentResourceKey, String institutionResourceKey) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      insert into student_track_enrollment(student_id,subject_id,institution_id,status,qa_test_run_id)
      select ?,s.id,?,'ACTIVE',?
      from curriculum_subject s
      join curriculum_class c on c.id=s.class_id
      where c.code='7' and c.active=true and s.active=true and s.code in ('maths','science')
      returning id,subject_id
      """, studentId, institutionId, runId);
    if (rows.isEmpty()) throw new IllegalStateException("Class 7 active Maths/Science tracks are unavailable in the approved QA database.");
    for (Map<String,Object> row : rows) {
      long id = number(row.get("id"));
      addResource(runId, "track-enrollment-" + id, "STUDENT_TRACK_ENROLLMENT", id, 30,
          List.of(studentResourceKey, institutionResourceKey), "student_track_enrollment");
    }
  }

  private Map<String,Object> requiredPublishedClass7Question() {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select l.id as lesson_id,q.id as question_id,
        (select qo.option_key from question_option qo where qo.question_id=q.id and qo.is_correct=true order by qo.option_key limit 1) as correct_option,
        (select qo.option_key from question_option qo where qo.question_id=q.id and qo.is_correct=false order by qo.option_key limit 1) as wrong_option
      from lesson l
      join curriculum_chapter ch on ch.id=l.chapter_id
      join curriculum_subject s on s.id=ch.subject_id
      join curriculum_class c on c.id=s.class_id
      join question q on q.lesson_id=l.id
      where c.code='7' and c.active=true and s.active=true and s.code in ('maths','science')
        and ch.active=true
        and l.active=true and l.status='PUBLISHED'
        and exists(select 1 from question_option qo where qo.question_id=q.id and qo.is_correct=true)
        and exists(select 1 from question_option qo where qo.question_id=q.id and qo.is_correct=false)
      order by l.id,q.id limit 1
      """);
    if (rows.isEmpty()) throw new IllegalStateException("The approved QA database needs one published Class 7 Maths/Science lesson with a question containing both correct and incorrect options.");
    return rows.getFirst();
  }

  private void insertProgress(UUID runId, String key, Long studentId, Long institutionId, Long lessonId, String studentResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student_lesson_progress(
        student_id,institution_id,lesson_id,status,progress_percent,started_at,completed_at,last_opened_at,qa_test_run_id
      ) values (?,?,?,'IN_PROGRESS',35,now(),null,now(),?) returning id
      """, Long.class, studentId, institutionId, lessonId, runId);
    addResource(runId, key, "STUDENT_LESSON_PROGRESS", id, 20, List.of(studentResourceKey), "student_lesson_progress");
  }

  private void insertQuestionAttempt(UUID runId, String key, Long studentId, Long institutionId,
      Long questionId, String optionKey, boolean correct, String studentResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student_question_attempt(
        student_id,institution_id,question_id,selected_option,correct,answered_at,qa_test_run_id
      ) values (?,?,?,?,?,now(),?) returning id
      """, Long.class, studentId, institutionId, questionId, optionKey, correct, runId);
    addResource(runId, key, "STUDENT_QUESTION_ATTEMPT", id, 10, List.of(studentResourceKey), "student_question_attempt");
  }

  private void addResource(UUID runId, String key, String kind, Long resourceId, int cleanupOrder,
      List<String> dependencies, String internalTable) {
    Map<String,Object> ref = new LinkedHashMap<>();
    ref.put("table", internalTable);
    ref.put("primaryKey", "institution_branding".equals(internalTable) ? "institution_id" : "id");
    ref.put("runOwnershipColumn", "qa_test_run_id");
    ref.put("runId", runId.toString());
    ref.put("resourceId", resourceId);
    jdbc.update("""
      insert into qa_test_run_resource(
        run_id,resource_key,resource_kind,resource_id,resource_ref,dependency_keys,cleanup_order,state
      ) values (?,?,?, ?,?::jsonb,?::jsonb,?,'OWNED')
      """, runId, key, kind, String.valueOf(resourceId), json(ref), json(dependencies), cleanupOrder);
  }

  private void cleanResource(UUID runId, Map<String,Object> resource, long manifestId) {
    List<Map<String,Object>> lockedRows = jdbc.queryForList("""
      select state from qa_test_run_resource
      where id=? and run_id=? for update
      """, manifestId, runId);
    if (lockedRows.isEmpty()) {
      throw new IllegalStateException("Manifest entry disappeared or no longer belongs to this run.");
    }
    String currentState = String.valueOf(lockedRows.getFirst().get("state"));
    if ("CLEANED".equals(currentState)) return;
    if (!"OWNED".equals(currentState) && !"CLEANUP_PENDING".equals(currentState) && !"FAILED".equals(currentState)) {
      throw new IllegalStateException("Manifest resource has an unexpected cleanup state: " + currentState);
    }
    String kind = String.valueOf(resource.get("resource_kind"));
    long resourceId = number(resource.get("resource_id"));
    DeleteSpec spec = deleteSpec(kind);
    int deleted = jdbc.update(spec.deleteSql, resourceId, runId);
    if (deleted == 0) {
      List<Map<String,Object>> remaining = jdbc.queryForList(spec.ownerSql, resourceId);
      if (!remaining.isEmpty()) {
        Object owner = remaining.getFirst().get("qa_test_run_id");
        if (owner == null || !runId.toString().equalsIgnoreCase(String.valueOf(owner))) {
          throw new IllegalStateException("Ownership conflict for manifest resource " + resource.get("resource_key") + ".");
        }
        throw new IllegalStateException("Owned resource could not be deleted: " + resource.get("resource_key") + ".");
      }
    }
    jdbc.update("""
      update qa_test_run_resource set state='CLEANED',cleaned_at=now(),
        cleanup_detail=?::jsonb
      where id=? and run_id=? and state<>'CLEANED'
      """, json(Map.of("deletedRows", deleted, "confirmedAbsentOrOwned", true)), manifestId, runId);
  }

  private DeleteSpec deleteSpec(String kind) {
    return switch (kind) {
      case "STUDENT_QUESTION_ATTEMPT" -> new DeleteSpec(
          "delete from student_question_attempt where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student_question_attempt where id=?");
      case "STUDENT_LESSON_PROGRESS" -> new DeleteSpec(
          "delete from student_lesson_progress where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student_lesson_progress where id=?");
      case "STUDENT_TRACK_ENROLLMENT" -> new DeleteSpec(
          "delete from student_track_enrollment where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student_track_enrollment where id=?");
      case "STUDENT_LOGIN_CREDENTIAL" -> new DeleteSpec(
          "delete from student_login_credential where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student_login_credential where id=?");
      case "GUARDIAN_STUDENT" -> new DeleteSpec(
          "delete from guardian_student where id=? and qa_test_run_id=?",
          "select qa_test_run_id from guardian_student where id=?");
      case "INSTITUTION_MEMBERSHIP" -> new DeleteSpec(
          "delete from institution_membership where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_membership where id=?");
      case "INSTITUTION_USER_MEMBERSHIP" -> new DeleteSpec(
          "delete from institution_user_membership where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_user_membership where id=?");
      case "STUDENT" -> new DeleteSpec(
          "delete from student where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student where id=?");
      case "USER_ACCOUNT" -> new DeleteSpec(
          "delete from user_account where id=? and qa_test_run_id=?",
          "select qa_test_run_id from user_account where id=?");
      case "INSTITUTION_BRANDING" -> new DeleteSpec(
          "delete from institution_branding where institution_id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_branding where institution_id=?");
      case "INSTITUTION" -> new DeleteSpec(
          "delete from institution_tenant where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_tenant where id=?");
      default -> throw new IllegalArgumentException("Unsupported manifest resource kind.");
    };
  }

  private Map<String,Object> findIdempotentRun(String packKey, int packVersion, String key) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select run_id,pack_key,pack_version,idempotency_key,state
      from qa_test_run where pack_key=? and pack_version=? and idempotency_key=?
      """, packKey, packVersion, key);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Map<String,Object> findRun(UUID runId) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      select run_id,pack_key,pack_version,idempotency_key,state,environment_label,
        target_database_label,source_commit_sha,pack_definition_sha256,created_by_staff_id,
        planned_resource_count,created_resource_count,cleaned_resource_count,protected_baseline,
        fixture_summary,cleanup_report,error_code,error_summary,created_at,updated_at,installed_at,uninstalled_at
      from qa_test_run where run_id=?
      """, runId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void addEvent(UUID runId, String type, String from, String to, Long actor, Map<String,Object> details) {
    jdbc.update("""
      insert into qa_test_run_event(run_id,event_type,from_state,to_state,actor_staff_id,details)
      values (?,?,?,?,?,?::jsonb)
      """, runId, type, from, to, actor, json(details));
  }

  private boolean baselineSnapshotsMatch(Map<String,Object> before, Map<String,Object> after) {
    List<String> protectedTables = List.of("institution_tenant", "institution_branding", "user_account", "student", "guardian_student", "institution_user_membership", "institution_membership", "student_track_enrollment", "student_lesson_progress", "student_question_attempt");
    if (before == null || after == null || before.size() != protectedTables.size()
        || after.size() != protectedTables.size()) return false;
    for (String table : protectedTables) {
      Object beforeValue = before.get(table);
      Object afterValue = after.get(table);
      if (!(beforeValue instanceof Map<?,?> beforeRow) || !(afterValue instanceof Map<?,?> afterRow)) return false;
      if (number(beforeRow.get("count")) != number(afterRow.get("count"))) return false;
      if (!String.valueOf(beforeRow.get("fingerprint")).equals(String.valueOf(afterRow.get("fingerprint")))) return false;
    }
    return true;
  }

  private Map<String,Object> baselineSnapshot() {
    Map<String,Object> snapshot = new LinkedHashMap<>();
    Map<String,String> primaryKeys = Map.of(
        "institution_tenant", "id",
        "institution_branding", "institution_id",
        "user_account", "id",
        "student", "id",
        "guardian_student", "id",
        "institution_user_membership", "id",
        "institution_membership", "id",
        "student_track_enrollment", "id",
        "student_lesson_progress", "id",
        "student_question_attempt", "id");
    for (Map.Entry<String,String> tableEntry : primaryKeys.entrySet()) {
      String table = tableEntry.getKey();
      String primaryKey = tableEntry.getValue();
      Map<String,Object> result = jdbc.queryForMap(
          "select count(*) as row_count, md5(coalesce(string_agg(md5(to_jsonb(t)::text), '' order by t."
              + primaryKey + "),'')) as fingerprint from " + table + " t where qa_test_run_id is null");
      Map<String,Object> tableSnapshot = new LinkedHashMap<>();
      tableSnapshot.put("count", number(result.get("row_count")));
      tableSnapshot.put("fingerprint", String.valueOf(result.get("fingerprint")));
      snapshot.put(table, tableSnapshot);
    }
    return snapshot;
  }

  private Map<String,Object> readJsonMap(Object raw) {
    if (raw == null) return Map.of();
    try {
      JsonNode node = mapper.readTree(String.valueOf(raw));
      return mapper.convertValue(node, new TypeReference<Map<String,Object>>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("The protected-baseline manifest is missing or invalid.");
    }
  }

  private String manifestChecksum(UUID runId) {
    List<Map<String,Object>> resources = jdbc.queryForList("""
      select resource_key,resource_kind,resource_id,resource_ref,dependency_keys,cleanup_order
      from qa_test_run_resource where run_id=? order by resource_key
      """, runId);
    return sha256(json(resources));
  }

  private int countResources(UUID runId) {
    Integer count = jdbc.queryForObject("select count(*) from qa_test_run_resource where run_id=?", Integer.class, runId);
    return count == null ? 0 : count;
  }

  private int countUncleanedResources(UUID runId) {
    Integer count = jdbc.queryForObject("select count(*) from qa_test_run_resource where run_id=? and state<>'CLEANED'", Integer.class, runId);
    return count == null ? 0 : count;
  }

  private int countCleanedResources(UUID runId) {
    Integer count = jdbc.queryForObject("select count(*) from qa_test_run_resource where run_id=? and state='CLEANED'", Integer.class, runId);
    return count == null ? 0 : count;
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception ex) { throw new IllegalStateException("Cannot serialize QA manifest data.", ex); }
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder output = new StringBuilder(64);
      for (byte b : digest) output.append(String.format("%02x", b));
      return output.toString();
    } catch (Exception ex) { throw new IllegalStateException("SHA-256 is unavailable.", ex); }
  }

  private String testMobile(UUID runId, int offset) {
    BigInteger seed = new BigInteger(runId.toString().replace("-", ""), 16);
    int leadingDigit = 6 + seed.add(BigInteger.valueOf(offset)).mod(BigInteger.valueOf(4)).intValue();
    long suffix = seed.add(BigInteger.valueOf(offset * 97L))
        .mod(BigInteger.valueOf(1_000_000_000L)).longValue();
    return "+91" + leadingDigit + String.format(Locale.ROOT, "%09d", suffix);
  }

  private String sourceCommitSha() {
    String sha = System.getenv("GITHUB_SHA");
    if (sha == null || !sha.matches("(?i)^[a-f0-9]{40}$")) sha = System.getenv("APP_BUILD_SHA");
    return sha != null && sha.matches("(?i)^[a-f0-9]{40}$") ? sha : null;
  }

  private String errorCode(Exception ex) {
    String name = ex.getClass().getSimpleName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
    if (name.isBlank()) return "QA_LIFECYCLE_ERROR";
    return name.length() > 60 ? name.substring(0,60) : name;
  }

  private String safeInstallError(Exception ex) {
    if (ex instanceof IllegalStateException || ex instanceof IllegalArgumentException) {
      return shorten(String.valueOf(ex.getMessage()), 900);
    }
    return "Fixture transaction rolled back (" + errorCode(ex) + "). Inspect server logs in the approved QA environment.";
  }

  private String safeCleanupError(Exception ex) {
    if (ex instanceof IllegalStateException || ex instanceof IllegalArgumentException) {
      return shorten(String.valueOf(ex.getMessage()), 900);
    }
    return "Cleanup stopped without forcing deletion (" + errorCode(ex) + "). Inspect the manifest and FK/provider blocker.";
  }

  private String shorten(String value, int max) {
    if (value == null || value.isBlank()) return "No additional error detail was provided.";
    String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
    return normalized.length() <= max ? normalized : normalized.substring(0, max);
  }

  private long number(Object value) {
    if (value instanceof Number n) return n.longValue();
    if (value == null) return 0L;
    try { return Long.parseLong(String.valueOf(value)); }
    catch (NumberFormatException ex) { throw new IllegalStateException("Unexpected numeric value in QA manifest.", ex); }
  }

  private Gate evaluateGate() {
    String database = null;
    try { database = jdbc.queryForObject("select current_database()", String.class); }
    catch (Exception ignored) { /* A failed DB check must disable writes, not bypass the gate. */ }
    boolean safeDatabaseName = database != null && QA_DATABASE.matcher(database).matches()
        && !database.toLowerCase(Locale.ROOT).contains("prod");
    boolean exactConfiguredTarget = !targetDatabaseName.isBlank() && database != null
        && database.equalsIgnoreCase(targetDatabaseName)
        && QA_DATABASE.matcher(targetDatabaseName).matches()
        && !targetDatabaseName.toLowerCase(Locale.ROOT).contains("prod");
    String appEnvironment = springEnvironment.getProperty("app.environment", "local").trim().toLowerCase(Locale.ROOT);
    boolean prodProfile = Arrays.stream(springEnvironment.getActiveProfiles())
        .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
        .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    prodProfile = prodProfile || appEnvironment.equals("prod") || appEnvironment.equals("production")
        || appEnvironment.equals("live");
    boolean labelValid = ENVIRONMENT_LABEL.matcher(environmentLabel).matches()
        && !environmentLabel.toLowerCase(Locale.ROOT).contains("prod")
        && !environmentLabel.toLowerCase(Locale.ROOT).contains("live");
    boolean fixtureReady = fixturePassword.length() >= 10
        && fixturePassword.chars().noneMatch(Character::isISOControl);
    String reason = "QA writes are disabled.";
    boolean allowed = false;
    if (!qaEnabled) reason = "APP_QA_ENABLED is false; QA mutations are disabled by default.";
    else if (!environmentApproved) reason = "APP_QA_ENVIRONMENT_APPROVED is false; explicit environment approval is required.";
    else if (prodProfile) reason = "QA operations are forbidden in a production/live environment or profile.";
    else if (!labelValid) reason = "APP_QA_ENVIRONMENT_LABEL must be an explicit non-production label.";
    else if (!safeDatabaseName) reason = "Current database name is not on the allow-list. Use an isolated database ending in _qa, _test, _ci or _integration.";
    else if (targetDatabaseName.isBlank()) reason = "APP_QA_TARGET_DATABASE must explicitly name the isolated QA database.";
    else if (!exactConfiguredTarget) reason = "Connected database does not exactly match the server-configured APP_QA_TARGET_DATABASE.";
    else if (!fixtureReady) reason = "APP_QA_FIXTURE_PASSWORD must be explicitly configured with at least 10 non-control characters.";
    else {
      allowed = true;
      reason = "Connected database exactly matches the server-approved isolated QA target.";
    }
    return new Gate(database, exactConfiguredTarget, prodProfile, allowed, reason, fixtureReady);
  }

  private void requireWritableEnvironment(Gate gate) {
    if (!gate.allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, gate.reason);
  }

  private record Gate(String databaseName, boolean databaseApproved, boolean productionProfile,
      boolean allowed, String reason, boolean fixtureCredentialsConfigured) {}
  private record InstallOutcome(Map<String,Object> fixtureResult, int resourceCount, String manifestHash) {}
  private record DeleteSpec(String deleteSql, String ownerSql) {}
}
