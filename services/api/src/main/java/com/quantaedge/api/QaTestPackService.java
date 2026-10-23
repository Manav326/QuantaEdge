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
  private static final int PACK_VERSION = 3;
  private static final String PACK_DEFINITION = "TENANT_LEARNER_BOUNDARY_V1:3:three-institutions-hrms-approved-cross-institution-course-release-institution-bound-users-and-learners-run-owned-synthetic-curriculum";
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
    pack.put("summary", "Three synthetic institutions under HRMS; QuantaEdge remains the owner of Classes 6/7/8. HRMS-approved test assignments expose Class 7 to Institutions A/B and Class 6 to C; run-owned lesson/enrolment/outcome fixtures verify tenant isolation.");
    pack.put("minimumInstitutions", 3);
    pack.put("institutionCourseCoverage", List.of("QuantaEdge owns Classes 6/7/8", "HRMS explicitly assigns QuantaEdge Class 7 to QA Institutions A and B", "HRMS explicitly assigns QuantaEdge Class 6 to QA Institution C", "Institution C does not inherit Class 7 without an explicit assignment"));
    pack.put("personas", List.of("QuantaEdge course owner", "Institution Admin A", "Parent A", "Minor child A", "Institution Admin B", "Parent B", "Minor child B", "Institution Admin C", "Independent adult learner C", "HRMS content reviewer"));
    pack.put("createsExternalResources", false);
    pack.put("createsRunOwnedSyntheticCurriculum", true);
    pack.put("preservesQuantaEdgeClassCourses", List.of("CLASS_6", "CLASS_7", "CLASS_8"));
    pack.put("createsNoOrphanUsersOrCourseEnrollments", true);
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
        select id,resource_key,resource_kind,resource_id,resource_ref,cleanup_order,state
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
    addCheck(runId, checks, "PACK_THREE_TENANTS", tenantCount == 3, "At least 3 run-owned active institutions",
        Long.toString(tenantCount), Map.of("actual", tenantCount));

    long ownedAdminAccounts = scalarCount("select count(*) from institution_admin_account where qa_test_run_id=?", runId);
    addCheck(runId, checks, "PACK_FOUR_INSTITUTION_ADMINS", ownedAdminAccounts == 4,
        "4 run-owned institution admins: one QuantaEdge course owner and one per QA institution",
        Long.toString(ownedAdminAccounts), Map.of("actual", ownedAdminAccounts));

    long ownedStaffReviewers = scalarCount("select count(*) from staff_account where qa_test_run_id=? and role='CONTENT_REVIEWER'", runId);
    addCheck(runId, checks, "PACK_HRMS_COURSE_REVIEWER", ownedStaffReviewers == 1,
        "1 run-owned HRMS-side course-quality reviewer", Long.toString(ownedStaffReviewers),
        Map.of("actual", ownedStaffReviewers));

    long ownedHrmsPlatformAdmins = scalarCount("select count(*) from staff_account where qa_test_run_id=? and role='ADMIN'", runId);
    addCheck(runId, checks, "PACK_HRMS_PLATFORM_ADMIN", ownedHrmsPlatformAdmins == 1,
        "1 run-owned non-login HRMS/platform-owner actor for assignment operations",
        Long.toString(ownedHrmsPlatformAdmins), Map.of("actual", ownedHrmsPlatformAdmins));

    long quantaEdgeOwnedClassCourses = scalarCount("""
      select count(distinct p.id)
      from curriculum_program p
      join institution_tenant qe on qe.tenant_code='QUANTA-EDGE' and qe.status='ACTIVE'
      join institution_course_release r on r.program_id=p.id and r.source_institution_id=qe.id
      join institution_course_assignment a on a.course_release_id=r.id and a.institution_id=qe.id and a.status='ASSIGNED'
      join institution_course_entitlement e on e.program_id=p.id and e.institution_id=qe.id and e.status='ACTIVE'
      where p.code in ('CLASS_6','CLASS_7','CLASS_8') and p.curriculum_version='CURRENT' and p.status='ACTIVE'
      """);
    addCheck(runId, checks, "QUANTAEDGE_OWNS_CLASSES_6_7_8",
        quantaEdgeOwnedClassCourses == 3,
        "QuantaEdge remains the institution owner of active Classes 6, 7 and 8",
        Long.toString(quantaEdgeOwnedClassCourses), Map.of("actual", quantaEdgeOwnedClassCourses));

    long courseReleaseCount = scalarCount("select count(*) from institution_course_release where qa_test_run_id=?", runId);
    long orphanCourseReleases = scalarCount("""
      select count(*) from institution_course_release r
      where r.qa_test_run_id=? and not exists(
        select 1 from institution_tenant t
        where t.id=r.source_institution_id and t.tenant_code='QUANTA-EDGE' and t.status='ACTIVE'
      )
      """, runId);
    addCheck(runId, checks, "COURSE_RELEASES_KEEP_QUANTAEDGE_AS_SOURCE_OWNER",
        courseReleaseCount == 2 && orphanCourseReleases == 0,
        "2 run-owned test releases whose protected source institution is QuantaEdge; no orphan course",
        "releases=" + courseReleaseCount + ", orphaned=" + orphanCourseReleases,
        Map.of("releases", courseReleaseCount, "orphaned", orphanCourseReleases));

    long courseAssignmentCount = scalarCount("select count(*) from institution_course_assignment where qa_test_run_id=?", runId);
    long orphanAssignments = scalarCount("""
      select count(*) from institution_course_assignment a
      where a.qa_test_run_id=? and (
        not exists(select 1 from institution_tenant t where t.id=a.institution_id and t.qa_test_run_id=?)
        or not exists(select 1 from institution_course_release r where r.id=a.course_release_id and r.qa_test_run_id=?)
      )
      """, runId, runId, runId);
    addCheck(runId, checks, "COURSE_ASSIGNMENTS_ARE_TENANT_BOUND",
        courseAssignmentCount == 3 && orphanAssignments == 0,
        "3 institution-bound assignments with owned destination tenant and owned course release",
        "assignments=" + courseAssignmentCount + ", orphaned=" + orphanAssignments,
        Map.of("assignments", courseAssignmentCount, "orphaned", orphanAssignments));

    long tenantsWithoutAssignedCourse = scalarCount("""
      select count(*) from institution_tenant t
      where t.qa_test_run_id=? and not exists(
        select 1 from institution_course_assignment a
        where a.institution_id=t.id and a.status='ASSIGNED' and a.qa_test_run_id=?
      )
      """, runId, runId);
    addCheck(runId, checks, "EVERY_INSTITUTION_HAS_AN_ASSIGNED_COURSE",
        tenantsWithoutAssignedCourse == 0,
        "Every synthetic institution has at least one active, institution-scoped course assignment",
        Long.toString(tenantsWithoutAssignedCourse), Map.of("institutionsWithoutCourses", tenantsWithoutAssignedCourse));

    long hrmsApprovedCrossAssignments = scalarCount("""
      select count(*) from institution_course_assignment a
      join institution_course_release r on r.id=a.course_release_id
      join institution_tenant source_tenant on source_tenant.id=r.source_institution_id
      join institution_tenant target_tenant on target_tenant.id=a.institution_id
      where a.qa_test_run_id=? and r.qa_test_run_id=?
        and source_tenant.tenant_code='QUANTA-EDGE' and target_tenant.qa_test_run_id=?
        and source_tenant.id<>target_tenant.id
        and r.release_state='SHAREABLE' and a.assignment_source='HRMS' and a.status='ASSIGNED'
      """, runId, runId, runId);
    addCheck(runId, checks, "HRMS_APPROVED_CROSS_INSTITUTION_COURSE",
        hrmsApprovedCrossAssignments == 3,
        "Three explicit HRMS assignments of QuantaEdge-owned course releases to the test institutions",
        Long.toString(hrmsApprovedCrossAssignments), Map.of("actual", hrmsApprovedCrossAssignments));

    long thirdTenantReceivedForeignCourse = scalarCount("""
      select count(*) from institution_course_assignment a
      join institution_course_release r on r.id=a.course_release_id
      join institution_tenant source_tenant on source_tenant.id=r.source_institution_id
      join institution_tenant third_tenant on third_tenant.id=a.institution_id
      join curriculum_program p on p.id=r.program_id and p.qa_test_run_id=? and left(p.code,6)='QA_C7_'
      where a.qa_test_run_id=? and r.qa_test_run_id=?
        and source_tenant.tenant_code='QUANTA-EDGE' and third_tenant.qa_test_run_id=?
        and right(third_tenant.tenant_code,1)='C'
        and a.status='ASSIGNED' and source_tenant.id<>third_tenant.id
      """, runId, runId, runId, runId);
    addCheck(runId, checks, "THIRD_INSTITUTION_DOES_NOT_INHERIT_COURSE",
        thirdTenantReceivedForeignCourse == 0,
        "Institution C must not receive QuantaEdge Class 7 without its own explicit HRMS assignment",
        Long.toString(thirdTenantReceivedForeignCourse), Map.of("unexpectedAssignments", thirdTenantReceivedForeignCourse));

    long courseEntitlementCount = scalarCount("select count(*) from institution_course_entitlement where qa_test_run_id=?", runId);
    long orphanCourseEntitlements = scalarCount("""
      select count(*) from institution_course_entitlement e
      where e.qa_test_run_id=? and (
        not exists(select 1 from institution_tenant t where t.id=e.institution_id and t.qa_test_run_id=?)
        or not exists(select 1 from curriculum_program p where p.id=e.program_id and p.status='ACTIVE')
      )
      """, runId, runId);
    addCheck(runId, checks, "COURSE_ENTITLEMENTS_ARE_TENANT_BOUND",
        courseEntitlementCount == 3 && orphanCourseEntitlements == 0,
        "3 institution-bound course entitlements with active course templates",
        "entitlements=" + courseEntitlementCount + ", orphaned=" + orphanCourseEntitlements,
        Map.of("entitlements", courseEntitlementCount, "orphaned", orphanCourseEntitlements));

    long unownedUserAccounts = scalarCount("""
      select count(*) from user_account u where u.qa_test_run_id=? and not exists(
        select 1 from institution_user_membership m
        where m.user_id=u.id and m.status='ACTIVE' and m.qa_test_run_id=?
          and exists(select 1 from institution_tenant t where t.id=m.institution_id and t.qa_test_run_id=?)
      )
      """, runId, runId, runId);
    long unownedStudentProfiles = scalarCount("""
      select count(*) from student st where st.qa_test_run_id=? and not exists(
        select 1 from institution_membership m
        where m.student_id=st.id and m.status='ACTIVE' and m.qa_test_run_id=?
          and exists(select 1 from institution_tenant t where t.id=m.institution_id and t.qa_test_run_id=?)
      )
      """, runId, runId, runId);
    addCheck(runId, checks, "NO_ORPHAN_USERS_OR_STUDENTS",
        unownedUserAccounts == 0 && unownedStudentProfiles == 0,
        "Every run-owned login identity and student belongs to a run-owned institution",
        "unownedUsers=" + unownedUserAccounts + ", unownedStudents=" + unownedStudentProfiles,
        Map.of("unownedUsers", unownedUserAccounts, "unownedStudents", unownedStudentProfiles));

    long courseEnrollmentCount = scalarCount("select count(*) from student_program_enrollment where qa_test_run_id=?", runId);
    long invalidCourseEnrollments = scalarCount("""
      select count(*) from student_program_enrollment e
      where e.qa_test_run_id=? and (
        not exists(select 1 from institution_membership m
          where m.student_id=e.student_id and m.institution_id=e.institution_id
            and m.status='ACTIVE' and m.qa_test_run_id=?)
        or not exists(select 1 from institution_course_assignment a
          join institution_course_release r on r.id=a.course_release_id
          where a.institution_id=e.institution_id and a.status='ASSIGNED'
            and a.qa_test_run_id=? and r.program_id=e.program_id and r.release_state='SHAREABLE')
      )
      """, runId, runId, runId);
    addCheck(runId, checks, "LEARNER_COURSE_ENROLLMENTS_MATCH_TENANT_ASSIGNMENTS",
        courseEnrollmentCount == 3 && invalidCourseEnrollments == 0,
        "Every active learner course enrollment matches that learner's institution membership and an assigned course release",
        "enrollments=" + courseEnrollmentCount + ", invalid=" + invalidCourseEnrollments,
        Map.of("enrollments", courseEnrollmentCount, "invalid", invalidCourseEnrollments));

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

    long ownedLessons = scalarCount("select count(*) from lesson where qa_test_run_id=?", runId);
    long ownedQuestions = scalarCount("select count(*) from question where qa_test_run_id=?", runId);
    long ownedOptions = scalarCount("select count(*) from question_option where qa_test_run_id=?", runId);
    addCheck(runId, checks, "PACK_OWNED_CURRICULUM_FIXTURE",
        ownedLessons == 2 && ownedQuestions == 2 && ownedOptions == 4,
        "Exactly two run-owned published lessons (Class 6/7), two questions and four options",
        "lessons=" + ownedLessons + ", questions=" + ownedQuestions + ", options=" + ownedOptions,
        Map.of("lessons", ownedLessons, "questions", ownedQuestions, "options", ownedOptions));

    List<Map<String,Object>> resources = jdbc.queryForList("""
      select id,resource_key,resource_kind,resource_id,resource_ref,state
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
        'INSTITUTION','INSTITUTION_BRANDING','INSTITUTION_ADMIN_ACCOUNT','STAFF_ACCOUNT',
        'USER_ACCOUNT','STUDENT','GUARDIAN_STUDENT','INSTITUTION_USER_MEMBERSHIP','INSTITUTION_MEMBERSHIP',
        'STUDENT_LOGIN_CREDENTIAL','STUDENT_COURSE_ENROLLMENT','STUDENT_TRACK_ENROLLMENT',
        'STUDENT_LESSON_PROGRESS','STUDENT_QUESTION_ATTEMPT','COURSE_RELEASE','COURSE_ASSIGNMENT',
        'COURSE_ENTITLEMENT','LESSON','QUESTION','QUESTION_OPTION'
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
    if ("PROGRAM_SUBJECT_MAP".equals(kind)) {
      Map<String,Object> ref=readJsonMap(resource.get("resource_ref"));
      Boolean found=jdbc.queryForObject("""
        select exists(select 1 from curriculum_program_subject
          where program_id=? and subject_id=? and qa_test_run_id=?)
        """,Boolean.class,id,number(ref.get("subjectId")),runId);
      return Boolean.TRUE.equals(found);
    }
    String sql = switch (kind) {
      case "COURSE_IDENTITY" -> "select exists(select 1 from institution_course_identity where id=? and qa_test_run_id=?)";
      case "COURSE" -> "select exists(select 1 from curriculum_program where id=? and qa_test_run_id=?)";
      case "COURSE_SUBJECT" -> "select exists(select 1 from curriculum_subject where id=? and qa_test_run_id=?)";
      case "COURSE_CHAPTER" -> "select exists(select 1 from curriculum_chapter where id=? and qa_test_run_id=?)";
      case "STUDENT_QUESTION_ATTEMPT" -> "select exists(select 1 from student_question_attempt where id=? and qa_test_run_id=?)";
      case "STUDENT_COURSE_ENROLLMENT" -> "select exists(select 1 from student_program_enrollment where id=? and qa_test_run_id=?)";
      case "COURSE_ENTITLEMENT" -> "select exists(select 1 from institution_course_entitlement where id=? and qa_test_run_id=?)";
      case "COURSE_ASSIGNMENT" -> "select exists(select 1 from institution_course_assignment where id=? and qa_test_run_id=?)";
      case "COURSE_RELEASE" -> "select exists(select 1 from institution_course_release where id=? and qa_test_run_id=?)";
      case "INSTITUTION_ADMIN_ACCOUNT" -> "select exists(select 1 from institution_admin_account where id=? and qa_test_run_id=?)";
      case "STAFF_ACCOUNT" -> "select exists(select 1 from staff_account where id=? and qa_test_run_id=?)";
      case "LESSON" -> "select exists(select 1 from lesson where id=? and qa_test_run_id=?)";
      case "QUESTION" -> "select exists(select 1 from question where id=? and qa_test_run_id=?)";
      case "QUESTION_OPTION" -> "select exists(select 1 from question_option where id=? and qa_test_run_id=?)";
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
    String lowerSlug = slug.toLowerCase(Locale.ROOT);
    String passwordHash = passwordEncoder.encode(fixturePassword);

    // HRMS owns the tenant registry. Three synthetic tenant institutions exercise cross-tenant
    // assignment: A/B receive QuantaEdge Class 7, while C receives QuantaEdge Class 6 only.
    Long tenantA = insertTenant(runId, actorStaffId, "QAT" + slug + "A", "QA " + slug + " Institution A");
    Long tenantB = insertTenant(runId, actorStaffId, "QAT" + slug + "B", "QA " + slug + " Institution B");
    Long tenantC = insertTenant(runId, actorStaffId, "QAT" + slug + "C", "QA " + slug + " Institution C");
    insertBranding(runId, "tenant-a-branding", tenantA, "QA Institution A", "institution-QAT" + slug + "A");
    insertBranding(runId, "tenant-b-branding", tenantB, "QA Institution B", "institution-QAT" + slug + "B");
    insertBranding(runId, "tenant-c-branding", tenantC, "QA Institution C", "institution-QAT" + slug + "C");

    String parentAMobile = testMobile(runId, 1);
    String parentBMobile = testMobile(runId, 2);
    String learnerMobile = testMobile(runId, 3);
    String parentAUsername = "qa_parent_" + lowerSlug + "a";
    String parentBUsername = "qa_parent_" + lowerSlug + "b";
    String learnerUsername = "qa_learner_" + lowerSlug;

    Long parentA = insertUser(runId, "QA Parent A " + slug, "PARENT", parentAMobile, parentAUsername, passwordHash, "parent-a");
    Long parentB = insertUser(runId, "QA Parent B " + slug, "PARENT", parentBMobile, parentBUsername, passwordHash, "parent-b");
    Long independentLearner = insertUser(runId, "QA Independent Learner C " + slug, "LEARNER",
        learnerMobile, learnerUsername, passwordHash, "independent-learner");

    insertUserMembership(runId, "parent-a-membership", tenantA, parentA, "institution-QAT" + slug + "A", "user-parent-a");
    insertUserMembership(runId, "parent-b-membership", tenantB, parentB, "institution-QAT" + slug + "B", "user-parent-b");
    insertUserMembership(runId, "adult-learner-membership", tenantC, independentLearner, "institution-QAT" + slug + "C", "user-independent-learner");

    Long childA = insertStudent(runId, "QA Child A " + slug, "7", "en", null, "child-a");
    Long childB = insertStudent(runId, "QA Child B " + slug, "7", "hi", null, "child-b");
    Long adultStudent = insertStudent(runId, "QA Adult Learner C " + slug, "6", "en", independentLearner, "adult-student");

    insertGuardianStudent(runId, "parent-a-child-a", parentA, childA, "user-parent-a", "student-child-a");
    insertGuardianStudent(runId, "parent-b-child-b", parentB, childB, "user-parent-b", "student-child-b");
    String childAUsername = "qa_child_" + lowerSlug + "a";
    String childBUsername = "qa_child_" + lowerSlug + "b";
    insertStudentCredential(runId, "child-a-credential", parentA, childA, childAUsername, passwordHash, "user-parent-a", "student-child-a");
    insertStudentCredential(runId, "child-b-credential", parentB, childB, childBUsername, passwordHash, "user-parent-b", "student-child-b");

    insertStudentMembership(runId, "child-a-institution-membership", tenantA, childA, "institution-QAT" + slug + "A", "student-child-a");
    insertStudentMembership(runId, "child-b-institution-membership", tenantB, childB, "institution-QAT" + slug + "B", "student-child-b");
    insertStudentMembership(runId, "adult-institution-membership", tenantC, adultStudent, "institution-QAT" + slug + "C", "student-adult-student");

    Long quantaEdgeId = jdbc.queryForObject("""
      select id from institution_tenant where tenant_code='QUANTA-EDGE' and status='ACTIVE'
      """, Long.class);
    Long class6ProgramId = requiredQuantaEdgeCourseId(quantaEdgeId, "CLASS_6");
    Long class7ProgramId = requiredQuantaEdgeCourseId(quantaEdgeId, "CLASS_7");
    Long class8ProgramId = requiredQuantaEdgeCourseId(quantaEdgeId, "CLASS_8");
    verifyQuantaEdgeOwnsClassCourses(quantaEdgeId, class6ProgramId, class7ProgramId, class8ProgramId);

    Map<String,Object> courseGraph = insertInstitutionCourseGraph(
        runId, slug, tenantA, tenantB, tenantC, quantaEdgeId, class7ProgramId, class6ProgramId);
    @SuppressWarnings("unchecked")
    Map<String,Object> testCourseIds = (Map<String,Object>) courseGraph.get("courseIds");
    Long qaClass7ProgramId = number(testCourseIds.get("class7ProgramId"));
    Long qaClass6ProgramId = number(testCourseIds.get("class6ProgramId"));
    String class7ReleaseKey = String.valueOf(courseGraph.get("release_class7_resource_key"));
    String class6ReleaseKey = String.valueOf(courseGraph.get("release_class6_resource_key"));
    Map<String,Object> content7 = insertSyntheticCourseQuestion(runId, qaClass7ProgramId, "7");
    Map<String,Object> content6 = insertSyntheticCourseQuestion(runId, qaClass6ProgramId, "6");
    promoteQaCourseRelease(runId, number(courseGraph.get("release_class7_id")),
        number(courseGraph.get("qaCourseOwnerAdminId")), number(courseGraph.get("qaCourseReviewStaffId")),
        "QA-only course graph completed content setup before explicit distribution.");
    promoteQaCourseRelease(runId, number(courseGraph.get("release_class6_id")),
        number(courseGraph.get("qaCourseOwnerAdminId")), number(courseGraph.get("qaCourseReviewStaffId")),
        "QA-only course graph completed content setup before explicit distribution.");
    Long lesson7Id = number(content7.get("lesson_id"));
    Long question7Id = number(content7.get("question_id"));
    Long lesson6Id = number(content6.get("lesson_id"));
    Long question6Id = number(content6.get("question_id"));

    insertStudentCourseEnrollment(runId, "child-a-course-enrollment", childA, tenantA, qaClass7ProgramId,
        "student-child-a", "institution-QAT" + slug + "A", class7ReleaseKey);
    insertStudentCourseEnrollment(runId, "child-b-course-enrollment", childB, tenantB, qaClass7ProgramId,
        "student-child-b", "institution-QAT" + slug + "B", class7ReleaseKey);
    insertStudentCourseEnrollment(runId, "adult-course-enrollment-class-6", adultStudent, tenantC, qaClass6ProgramId,
        "student-adult-student", "institution-QAT" + slug + "C", class6ReleaseKey);

    ensureRequiredTracks(runId, childA, tenantA, qaClass7ProgramId, "student-child-a", "institution-QAT" + slug + "A");
    ensureRequiredTracks(runId, childB, tenantB, qaClass7ProgramId, "student-child-b", "institution-QAT" + slug + "B");
    ensureRequiredTracks(runId, adultStudent, tenantC, qaClass6ProgramId, "student-adult-student", "institution-QAT" + slug + "C");

    insertProgress(runId, "child-a-progress-class-7", childA, tenantA, lesson7Id,
        "student-child-a", "institution-QAT" + slug + "A", String.valueOf(content7.get("lesson_resource_key")));
    insertProgress(runId, "child-b-progress-class-7", childB, tenantB, lesson7Id,
        "student-child-b", "institution-QAT" + slug + "B", String.valueOf(content7.get("lesson_resource_key")));
    insertProgress(runId, "adult-progress-class-6", adultStudent, tenantC, lesson6Id,
        "student-adult-student", "institution-QAT" + slug + "C", String.valueOf(content6.get("lesson_resource_key")));

    insertQuestionAttempt(runId, "child-a-correct-class-7", childA, tenantA, question7Id, "B", true,
        "student-child-a", "institution-QAT" + slug + "A", String.valueOf(content7.get("question_resource_key")));
    insertQuestionAttempt(runId, "child-b-wrong-class-7", childB, tenantB, question7Id, "A", false,
        "student-child-b", "institution-QAT" + slug + "B", String.valueOf(content7.get("question_resource_key")));
    insertQuestionAttempt(runId, "adult-correct-class-6", adultStudent, tenantC, question6Id, "B", true,
        "student-adult-student", "institution-QAT" + slug + "C", String.valueOf(content6.get("question_resource_key")));

    Map<String,Object> credentials = new LinkedHashMap<>();
    credentials.put("institutionAdminA", courseGraph.get("institution_admin_a_credentials"));
    credentials.put("institutionAdminB", courseGraph.get("institution_admin_b_credentials"));
    credentials.put("institutionAdminC", courseGraph.get("institution_admin_c_credentials"));
    credentials.put("quantaEdgeCourseOwnerAdmin", courseGraph.get("quantaEdgeOwnerAdminCredentials"));
    credentials.put("parentA", Map.of("institutionCode", "QAT" + slug + "A", "mobile", parentAMobile, "username", parentAUsername, "password", fixturePassword));
    credentials.put("parentB", Map.of("institutionCode", "QAT" + slug + "B", "mobile", parentBMobile, "username", parentBUsername, "password", fixturePassword));
    credentials.put("childA", Map.of("institutionCode", "QAT" + slug + "A", "parentMobile", parentAMobile, "username", childAUsername, "password", fixturePassword));
    credentials.put("childB", Map.of("institutionCode", "QAT" + slug + "B", "parentMobile", parentBMobile, "username", childBUsername, "password", fixturePassword));
    credentials.put("independentLearner", Map.of("institutionCode", "QAT" + slug + "C", "mobile", learnerMobile, "username", learnerUsername, "password", fixturePassword));
    credentials.put("hrmsCourseReviewer", Map.of("role", "CONTENT_REVIEWER",
        "scope", "Synthetic HRMS course-review audit identity; not a login", "notLoginEnabled", true,
        "institutionCode", "HRMS"));

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("credentials", credentials);
    result.put("fixtureSummary", Map.ofEntries(
        Map.entry("institutions", 3),
        Map.entry("institutionAdminAccounts", 4),
        Map.entry("hrmsPlatformAdminActors", 1),
        Map.entry("parentAccounts", 2),
        Map.entry("minorChildren", 2),
        Map.entry("independentLearners", 1),
        Map.entry("guardianRelationships", 2),
        Map.entry("quantaEdgeCourseReleaseRevisions", 2),
        Map.entry("courseAssignments", 3),
        Map.entry("hrmsApprovedCrossInstitutionAssignments", 3),
        Map.entry("unassignedThirdInstitutionChecks", 1),
        Map.entry("institutionCourseEntitlements", 3),
        Map.entry("institutionScopedCourseEnrollments", 3),
        Map.entry("learningProgressRows", 3),
        Map.entry("questionAttempts", 3)));
    return result;
  }

  /**
   * QuantaEdge remains the source institution for Classes 6/7/8. The QA run creates two removable
   * release revisions, assigned by HRMS as Class 7 to A/B and Class 6 to C; curriculum master rows
   * and the QuantaEdge tenant itself are referenced, not owned by this QA run.
   */
  private Map<String,Object> insertInstitutionCourseGraph(
      UUID runId, String slug, Long tenantA, Long tenantB, Long tenantC,
      Long quantaEdgeId, Long class7ProgramId, Long class6ProgramId) {
    jdbc.queryForObject("select id from institution_tenant where id=? for update", Long.class, quantaEdgeId);

    Long reviewerId = insertQaCourseReviewer(runId, slug);
    Long hrmsPlatformAdminId = insertQaHrmsPlatformAdmin(runId, slug);
    Long adminA = insertInstitutionAdmin(runId, "institution-admin-a", tenantA,
        "qa_institution_" + slug.toLowerCase(Locale.ROOT) + "a", "QA Institution A Admin", hrmsPlatformAdminId,
        "institution-QAT" + slug + "A");
    Long adminB = insertInstitutionAdmin(runId, "institution-admin-b", tenantB,
        "qa_institution_" + slug.toLowerCase(Locale.ROOT) + "b", "QA Institution B Admin", hrmsPlatformAdminId,
        "institution-QAT" + slug + "B");
    Long adminC = insertInstitutionAdmin(runId, "institution-admin-c", tenantC,
        "qa_institution_" + slug.toLowerCase(Locale.ROOT) + "c", "QA Institution C Admin", hrmsPlatformAdminId,
        "institution-QAT" + slug + "C");
    Long quantaEdgeOwnerAdmin = insertInstitutionAdmin(runId, "institution-admin-quantaedge",
        quantaEdgeId, "qa_quantaedge_" + slug.toLowerCase(Locale.ROOT),
        "QA QuantaEdge Course Owner", hrmsPlatformAdminId, "institution-QUANTA-EDGE");

    // QA lessons/questions must never be inserted into the protected production Class 6/7 graph.
    // Create separate run-owned curriculum graphs and distribute only after their synthetic content
    // is complete. Existing QuantaEdge courses remain read-only references for this test pack.
    Long qaClass7ProgramId = insertQaOwnedCourseMaster(
        runId, slug, "7", quantaEdgeId, quantaEdgeOwnerAdmin, reviewerId, class7ProgramId);
    Long qaClass6ProgramId = insertQaOwnedCourseMaster(
        runId, slug, "6", quantaEdgeId, quantaEdgeOwnerAdmin, reviewerId, class6ProgramId);
    Long release7 = insertQuantaEdgeShareableRelease(runId, "course-release-class-7",
        quantaEdgeId, quantaEdgeOwnerAdmin, reviewerId, qaClass7ProgramId,
        "Run-owned QA release; Class 7 fixture course is owned by QuantaEdge.");
    Long release6 = insertQuantaEdgeShareableRelease(runId, "course-release-class-6",
        quantaEdgeId, quantaEdgeOwnerAdmin, reviewerId, qaClass6ProgramId,
        "Run-owned QA release; Class 6 fixture course is owned by QuantaEdge.");

    Long assignmentA = insertCourseAssignment(runId, "course-assignment-class-7-to-a",
        tenantA, release7, "HRMS", hrmsPlatformAdminId,
        List.of("institution-QAT" + slug + "A", "course-release-class-7"));
    Long assignmentB = insertCourseAssignment(runId, "course-assignment-class-7-to-b",
        tenantB, release7, "HRMS", hrmsPlatformAdminId,
        List.of("institution-QAT" + slug + "B", "course-release-class-7"));
    Long assignmentC = insertCourseAssignment(runId, "course-assignment-class-6-to-c",
        tenantC, release6, "HRMS", hrmsPlatformAdminId,
        List.of("institution-QAT" + slug + "C", "course-release-class-6"));

    insertInstitutionCourseEntitlement(runId, "course-entitlement-a-class-7", tenantA, class7ProgramId,
        hrmsPlatformAdminId, "institution-QAT" + slug + "A", "course-release-class-7");
    insertInstitutionCourseEntitlement(runId, "course-entitlement-b-class-7", tenantB, class7ProgramId,
        hrmsPlatformAdminId, "institution-QAT" + slug + "B", "course-release-class-7");
    insertInstitutionCourseEntitlement(runId, "course-entitlement-c-class-6", tenantC, class6ProgramId,
        hrmsPlatformAdminId, "institution-QAT" + slug + "C", "course-release-class-6");

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("release_class7_id", release7);
    result.put("release_class6_id", release6);
    result.put("release_class7_resource_key", "course-release-class-7");
    result.put("release_class6_resource_key", "course-release-class-6");
    result.put("institution_admin_a_credentials", Map.of("institutionCode", "QAT" + slug + "A",
        "username", "qa_institution_" + slug.toLowerCase(Locale.ROOT) + "a", "password", fixturePassword));
    result.put("institution_admin_b_credentials", Map.of("institutionCode", "QAT" + slug + "B",
        "username", "qa_institution_" + slug.toLowerCase(Locale.ROOT) + "b", "password", fixturePassword));
    result.put("institution_admin_c_credentials", Map.of("institutionCode", "QAT" + slug + "C",
        "username", "qa_institution_" + slug.toLowerCase(Locale.ROOT) + "c", "password", fixturePassword));
    result.put("quantaEdgeOwnerAdminCredentials", Map.of("institutionCode", "QUANTA-EDGE",
        "username", "qa_quantaedge_" + slug.toLowerCase(Locale.ROOT), "password", fixturePassword));
    result.put("qaCourseOwnerAdminId", quantaEdgeOwnerAdmin);
    result.put("qaCourseReviewStaffId", reviewerId);
    result.put("courseIds", Map.of("class6ProgramId", qaClass6ProgramId, "class7ProgramId", qaClass7ProgramId,
        "class7AssignmentAId", assignmentA, "class7AssignmentBId", assignmentB,
        "class6AssignmentCId", assignmentC));
    return result;
  }

  private Long requiredQuantaEdgeCourseId(Long quantaEdgeId, String courseCode) {
    List<Long> ids = jdbc.queryForList("""
      select p.id
      from curriculum_program p
      join institution_course_release r on r.program_id=p.id and r.source_institution_id=?
      join institution_course_assignment a on a.course_release_id=r.id and a.institution_id=? and a.status='ASSIGNED'
      join institution_course_entitlement e on e.program_id=p.id and e.institution_id=? and e.status='ACTIVE'
      where p.code=? and p.curriculum_version='CURRENT' and p.status='ACTIVE'
      order by p.id limit 1
      """, Long.class, quantaEdgeId, quantaEdgeId, quantaEdgeId, courseCode);
    if (ids.isEmpty()) throw new IllegalStateException("QuantaEdge does not own an active assigned " + courseCode + " course.");
    return ids.getFirst();
  }

  private void verifyQuantaEdgeOwnsClassCourses(Long quantaEdgeId, Long class6ProgramId,
      Long class7ProgramId, Long class8ProgramId) {
    Long owned = jdbc.queryForObject("""
      select count(distinct p.id)
      from curriculum_program p
      join institution_course_release r on r.program_id=p.id and r.source_institution_id=?
      join institution_course_assignment a on a.course_release_id=r.id and a.institution_id=? and a.status='ASSIGNED'
      join institution_course_entitlement e on e.program_id=p.id and e.institution_id=? and e.status='ACTIVE'
      where p.id in (?,?,?) and p.status='ACTIVE'
      """, Long.class, quantaEdgeId, quantaEdgeId, quantaEdgeId,
        class6ProgramId, class7ProgramId, class8ProgramId);
    if (owned == null || owned != 3L) throw new IllegalStateException("QuantaEdge must remain the active owner of Classes 6, 7 and 8.");
  }

  /** Creates disposable course metadata while referencing only the real curriculum class. */
  private Long insertQaOwnedCourseMaster(UUID runId, String slug, String classCode,
      Long quantaEdgeId, Long ownerAdminId, Long reviewerStaffId, Long sourceProgramId) {
    Map<String,Object> source=jdbc.queryForMap("""
      select class_id,stream_code,course_type_code from curriculum_program
      where id=? and status='ACTIVE' and code<>'GENERAL'
      """,sourceProgramId);
    long classId=number(source.get("class_id"));
    String type=source.get("course_type_code")==null?"K12":String.valueOf(source.get("course_type_code"));
    String code="QA_C"+classCode+"_"+slug;
    String name="QA Class "+classCode+" lifecycle fixture "+slug;
    Long identityId=jdbc.queryForObject("""
      insert into institution_course_identity(
        source_institution_id,stable_course_key,display_name,course_type_code,
        created_by_staff_id,created_by_institution_admin_id,qa_test_run_id
      ) values(?,?,?,?,?,?,?) returning id
      """,Long.class,quantaEdgeId,"qa-pack-"+classCode+"-"+slug,name,type,reviewerStaffId,ownerAdminId,runId);
    addResource(runId,"course-identity-class-"+classCode,"COURSE_IDENTITY",identityId,40,
        List.of("institution-admin-quantaedge"),"institution_course_identity");

    Long programId=jdbc.queryForObject("""
      insert into curriculum_program(
        class_id,code,display_name,stream_code,curriculum_version,status,notes,public_id_prefix,
        course_type_code,course_identity_id,content_version_no,qa_test_run_id
      ) values(?,?,?,?,?,'ACTIVE',?,?,?, ?,1,?) returning id
      """,Long.class,classId,code,name,source.get("stream_code"),"QA-"+slug,
      "Run-owned QA fixture; production curriculum is not modified.","INS",type,identityId,runId);
    addResource(runId,"course-class-"+classCode,"COURSE",programId,39,
        List.of("course-identity-class-"+classCode),"curriculum_program");

    String subjectCode="qa-maths-"+slug.toLowerCase(Locale.ROOT);
    Long subjectId=jdbc.queryForObject("""
      insert into curriculum_subject(class_id,code,display_name,sort_order,active,program_id,qa_test_run_id)
      values(?,?,?,1,true,?,?) returning id
      """,Long.class,classId,subjectCode,"QA Mathematics "+slug,programId,runId);
    addResource(runId,"course-subject-class-"+classCode,"COURSE_SUBJECT",subjectId,38,
        List.of("course-class-"+classCode),"curriculum_subject");
    jdbc.update("""
      insert into curriculum_program_subject(program_id,class_id,subject_id,is_required,sort_order,qa_test_run_id)
      values(?,?,?,true,1,?)
      """,programId,classId,subjectId,runId);
    addProgramSubjectMapResource(runId,"course-subject-map-class-"+classCode,programId,subjectId,
        List.of("course-class-"+classCode,"course-subject-class-"+classCode));

    Long chapterId=jdbc.queryForObject("""
      insert into curriculum_chapter(
        subject_id,code,display_name,description,sort_order,active,content_status,qa_test_run_id
      ) values(?,'qa-lifecycle',?,?,999,true,'PUBLISHED',?) returning id
      """,Long.class,subjectId,"QA lifecycle fixture Class "+classCode,
      "Synthetic QA-only chapter; never production curriculum.",runId);
    addResource(runId,"course-chapter-class-"+classCode,"COURSE_CHAPTER",chapterId,36,
        List.of("course-subject-class-"+classCode),"curriculum_chapter");
    return programId;
  }

  private void addProgramSubjectMapResource(UUID runId,String key,Long programId,Long subjectId,
      List<String> dependencies) {
    Map<String,Object> ref=new LinkedHashMap<>();
    ref.put("subjectId",subjectId);
    ref.put("programId",programId);
    jdbc.update("""
      insert into qa_test_run_resource(
        run_id,resource_key,resource_kind,resource_id,resource_ref,dependency_keys,cleanup_order,state
      ) values(?,?,'PROGRAM_SUBJECT_MAP',?,?::jsonb,?::jsonb,37,'OWNED')
      """,runId,key,String.valueOf(programId),json(ref),json(dependencies));
  }

  private void promoteQaCourseRelease(UUID runId,long releaseId,long ownerAdminId,long reviewerStaffId,
      String reviewNote) {
    Long programId=jdbc.queryForObject("""
      select program_id from institution_course_release
      where id=? and qa_test_run_id=? and release_state='DRAFT'
      """,Long.class,releaseId,runId);
    String fingerprint=courseFingerprint(programId);
    int changed=jdbc.update("""
      update institution_course_release set release_state='SHAREABLE',content_fingerprint=?,
        submitted_by_institution_admin_id=?,submitted_at=now(),
        quality_reviewed_by_staff_id=?,quality_reviewed_at=now(),quality_review_notes=?,
        origin_approved_by_admin_id=?,origin_approved_at=now(),shareable_at=now(),updated_at=now()
      where id=? and source_institution_id=(select institution_id from institution_admin_account where id=?)
        and qa_test_run_id=? and release_state='DRAFT'
      """,fingerprint,ownerAdminId,reviewerStaffId,reviewNote,ownerAdminId,releaseId,ownerAdminId,runId);
    if(changed!=1)throw new IllegalStateException("The isolated QA course release could not transition from draft to shareable.");
  }

  private Long insertQuantaEdgeShareableRelease(UUID runId, String resourceKey, Long quantaEdgeId,
      Long quantaEdgeAdminId, Long reviewerStaffId, Long programId, String reviewNote) {
    Long nextVersion = jdbc.queryForObject("""
      select coalesce(max(release_version),0)+1
      from institution_course_release where source_institution_id=? and program_id=?
      """, Long.class, quantaEdgeId, programId);
    Long id = jdbc.queryForObject("""
      insert into institution_course_release(
        source_institution_id,program_id,release_version,release_state,content_fingerprint,
        legacy_migration,created_at,updated_at,qa_test_run_id
      ) values(?,?,?,'DRAFT',?,false,now(),now(),?) returning id
      """, Long.class, quantaEdgeId, programId, nextVersion, courseFingerprint(programId),runId);
    addResource(runId, resourceKey, "COURSE_RELEASE", id, 22,
        List.of("institution-admin-quantaedge", "staff-course-reviewer"), "institution_course_release");
    return id;
  }

  private Long insertQaCourseReviewer(UUID runId, String slug) {
    Long id = jdbc.queryForObject("""
      insert into staff_account(public_id,mobile_e164,display_name,role,active,qa_test_run_id)
      values(?,?,?,'CONTENT_REVIEWER',true,?) returning id
      """, Long.class, UUID.randomUUID(), testMobile(runId, 9), "QA HRMS Course Reviewer " + slug, runId);
    addResource(runId, "staff-course-reviewer", "STAFF_ACCOUNT", id, 48, List.of(), "staff_account");
    return id;
  }


  /** Run-owned, non-login actor representing HRMS/platform-owner assignment authority. */
  private Long insertQaHrmsPlatformAdmin(UUID runId, String slug) {
    Long id = jdbc.queryForObject("""
      insert into staff_account(public_id,mobile_e164,display_name,role,active,qa_test_run_id)
      values(?,?,?,'ADMIN',true,?) returning id
      """, Long.class, UUID.randomUUID(), testMobile(runId, 10),
      "QA HRMS Platform Admin " + slug, runId);
    addResource(runId, "staff-hrms-platform-admin", "STAFF_ACCOUNT", id, 48,
        List.of(), "staff_account");
    return id;
  }

  private Long insertInstitutionAdmin(UUID runId, String key, Long institutionId, String username,
      String displayName, Long createdByStaffId, String institutionResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into institution_admin_account(
        institution_id,username,display_name,password_hash,active,must_change_password,
        created_by_staff_id,qa_test_run_id
      ) values(?,?,?,?,true,true,?,?) returning id
      """, Long.class, institutionId, username, displayName, passwordEncoder.encode(fixturePassword),
      createdByStaffId, runId);
    addResource(runId, key, "INSTITUTION_ADMIN_ACCOUNT", id, 47,
        List.of(institutionResourceKey, "staff-hrms-platform-admin"), "institution_admin_account");
    return id;
  }

  private Long insertCourseAssignment(UUID runId, String key, Long institutionId, Long releaseId,
      String source, Long actorStaffId, List<String> dependencies) {
    Long id = jdbc.queryForObject("""
      insert into institution_course_assignment(
        institution_id,course_release_id,status,assignment_source,assigned_by_staff_id,
        assigned_at,created_at,updated_at,qa_test_run_id
      ) values(?,?,'ASSIGNED',?,?,now(),now(),now(),?) returning id
      """, Long.class, institutionId, releaseId, source, actorStaffId, runId);
    addResource(runId, key, "COURSE_ASSIGNMENT", id, 21,
        java.util.stream.Stream.concat(dependencies.stream(), java.util.stream.Stream.of("staff-hrms-platform-admin"))
            .distinct().toList(), "institution_course_assignment");
    return id;
  }

  private void insertInstitutionCourseEntitlement(UUID runId, String key, Long institutionId,
      Long programId, Long actorStaffId, String institutionResourceKey, String courseResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into institution_course_entitlement(
        institution_id,program_id,status,valid_from,granted_by_staff_id,qa_test_run_id
      ) values(?,?,'ACTIVE',now(),?,?) returning id
      """, Long.class, institutionId, programId, actorStaffId, runId);
    addResource(runId, key, "COURSE_ENTITLEMENT", id, 20,
        List.of(institutionResourceKey, courseResourceKey, "staff-hrms-platform-admin"), "institution_course_entitlement");
  }

  private String courseFingerprint(Long programId) {
    String value = jdbc.queryForObject("""
      select md5(coalesce(string_agg(entity_type||':'||entity_id::text||':'||row_json,E'\\n'
          order by entity_type,entity_id),'')) as fingerprint
      from (
        select 'program'::text entity_type,p.id entity_id,to_jsonb(p)::text row_json
        from curriculum_program p where p.id=?
        union all
        select 'subject',s.id,to_jsonb(ps)::text||to_jsonb(s)::text
        from curriculum_program_subject ps
        join curriculum_program p on p.id=ps.program_id
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
          and (s.program_id=ps.program_id or s.program_id is null)
        where ps.program_id=?
        union all
        select 'chapter',ch.id,to_jsonb(ch)::text
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
        join curriculum_chapter ch on ch.subject_id=s.id where ps.program_id=?
        union all
        select 'lesson',l.id,to_jsonb(l)::text
        from curriculum_program_subject ps
        join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id
        join curriculum_chapter ch on ch.subject_id=s.id
        join lesson l on l.chapter_id=ch.id where ps.program_id=?
      ) content_rows
      """, String.class, programId, programId, programId, programId);
    return value == null ? "d41d8cd98f00b204e9800998ecf8427e" : value.trim();
  }

  private void insertStudentCourseEnrollment(UUID runId, String key, Long studentId, Long institutionId,
      Long programId, String studentResourceKey, String institutionResourceKey, String courseReleaseResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student_program_enrollment(
        student_id,program_id,institution_id,status,enrolled_at,ended_at,created_at,updated_at,qa_test_run_id
      ) values(?,?,?,'ACTIVE',now(),null,now(),now(),?) returning id
      """, Long.class, studentId, programId, institutionId, runId);
    addResource(runId, key, "STUDENT_COURSE_ENROLLMENT", id, 32,
        List.of(studentResourceKey, institutionResourceKey, courseReleaseResourceKey), "student_program_enrollment");
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

  private Long insertStudent(UUID runId, String displayName, String classCode, String language,
      Long linkedUserId, String resourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student(
        public_id,display_name,class_code,board,language,environment,active,user_account_id,qa_test_run_id
      ) values (?,?,?,'Bihar Board',?,'PRODUCTION',true,?,?) returning id
      """, Long.class, UUID.randomUUID(), displayName, classCode, language, linkedUserId, runId);
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

  private void ensureRequiredTracks(UUID runId, Long studentId, Long institutionId, Long programId,
      String studentResourceKey, String institutionResourceKey) {
    List<Map<String,Object>> rows = jdbc.queryForList("""
      insert into student_track_enrollment(student_id,subject_id,institution_id,status,qa_test_run_id)
      select ?,s.id,?,'ACTIVE',?
      from curriculum_program_subject ps
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
      where ps.program_id=?
      returning id,subject_id
      """, studentId, institutionId, runId, programId);
    if (rows.isEmpty()) throw new IllegalStateException("Class 7 active Maths/Science tracks are unavailable in the approved QA database.");
    for (Map<String,Object> row : rows) {
      long id = number(row.get("id"));
      addResource(runId, "track-enrollment-" + id, "STUDENT_TRACK_ENROLLMENT", id, 30,
          List.of(studentResourceKey, institutionResourceKey), "student_track_enrollment");
    }
  }

  /**
   * Build a namespaced synthetic lesson/question/options only in the approved isolated QA
   * database. The chapter is shared curriculum and is referenced read-only; all created rows carry
   * the run UUID, are individually recorded in the manifest, and are removed by exact IDs.
   */
  private Map<String,Object> insertSyntheticCourseQuestion(UUID runId, Long programId, String classCode) {
    String uuidSlug = runId.toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
    String slug = "class" + classCode + "-" + uuidSlug;
    String lessonKey = "qa-lesson-" + slug;
    String questionKey = "qa-question-" + slug;
    String optionAKey = "qa-question-option-a-" + slug;
    String optionBKey = "qa-question-option-b-" + slug;
    List<Long> chapters = jdbc.queryForList("""
      select ch.id
      from curriculum_program p
      join curriculum_class c on c.id=p.class_id and c.code=?
      join curriculum_program_subject ps on ps.program_id=p.id and ps.class_id=c.id
      join curriculum_subject s on s.id=ps.subject_id and s.class_id=ps.class_id and s.active=true
        and (s.program_id=ps.program_id or s.program_id is null)
      join curriculum_chapter ch on ch.subject_id=s.id and ch.active=true
      where p.id=? and p.status='ACTIVE' and s.code like 'qa-maths-%'
      order by ch.id limit 1
      """, Long.class, classCode, programId);
    if (chapters.isEmpty()) {
      throw new IllegalStateException("QuantaEdge Class " + classCode + " has no active Maths chapter for its isolated QA fixture.");
    }

    Long lessonId = jdbc.queryForObject("""
      insert into lesson(chapter_id,code,title,summary,estimated_minutes,status,sort_order,active,qa_test_run_id)
      values (?,?,?,?,5,'PUBLISHED',999,true,?) returning id
      """, Long.class, chapters.getFirst(), "qa-lifecycle-" + slug,
      "QA-only synthetic Class " + classCode + " lesson " + uuidSlug,
      "Run-owned synthetic fixture for institution-scoped QA; not production teaching content.", runId);
    addResource(runId, lessonKey, "LESSON", lessonId, 35, List.of(), "lesson");

    Long questionId = jdbc.queryForObject("""
      insert into question(
        lesson_id,question_type,prompt,explanation,difficulty,sort_order,active,
        source_kind,source_title,source_ref,tags,marks,exam_format,topic,subtopic,skill,
        answer_payload,review_status,review_notes,reviewed_at,qa_test_run_id
      ) values (
        ?,'MCQ','QA fixture: choose the correct lifecycle answer.',
        'Synthetic options exercise institution-scoped progress and attempt ownership.',
        'CORE',999,true,'AUTHOR_CREATED','QuantaEdge QA synthetic fixture',?,
        jsonb_build_array('qa-test-fixture','synthetic','not-production-curriculum'),
        1,'MCQ','QA lifecycle','Run ownership','Option selection',
        jsonb_build_object('kind','OPTION','value','B'),'APPROVED',
        'Synthetic QA fixture; not a production curriculum question.',now(),?
      ) returning id
      """, Long.class, lessonId, "qa-lifecycle:" + runId + ":class" + classCode, runId);
    addResource(runId, questionKey, "QUESTION", questionId, 34, List.of(lessonKey), "question");

    Long optionAId = jdbc.queryForObject("""
      insert into question_option(question_id,option_key,label,is_correct,sort_order,qa_test_run_id)
      values (?,'A','Incorrect synthetic option',false,1,?) returning id
      """, Long.class, questionId, runId);
    addResource(runId, optionAKey, "QUESTION_OPTION", optionAId, 33, List.of(questionKey), "question_option");

    Long optionBId = jdbc.queryForObject("""
      insert into question_option(question_id,option_key,label,is_correct,sort_order,qa_test_run_id)
      values (?,'B','Correct synthetic option',true,2,?) returning id
      """, Long.class, questionId, runId);
    addResource(runId, optionBKey, "QUESTION_OPTION", optionBId, 33, List.of(questionKey), "question_option");

    Map<String,Object> result = new LinkedHashMap<>();
    result.put("lesson_id", lessonId);
    result.put("question_id", questionId);
    result.put("correct_option", "B");
    result.put("wrong_option", "A");
    result.put("lesson_resource_key", lessonKey);
    result.put("question_resource_key", questionKey);
    result.put("option_a_resource_key", optionAKey);
    result.put("option_b_resource_key", optionBKey);
    return result;
  }

  private void insertProgress(UUID runId, String key, Long studentId, Long institutionId, Long lessonId,
      String studentResourceKey, String institutionResourceKey, String lessonResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student_lesson_progress(
        student_id,institution_id,lesson_id,status,progress_percent,started_at,completed_at,last_opened_at,qa_test_run_id
      ) values (?,?,?,'IN_PROGRESS',35,now(),null,now(),?) returning id
      """, Long.class, studentId, institutionId, lessonId, runId);
    addResource(runId, key, "STUDENT_LESSON_PROGRESS", id, 20,
        List.of(studentResourceKey, institutionResourceKey, lessonResourceKey), "student_lesson_progress");
  }

  private void insertQuestionAttempt(UUID runId, String key, Long studentId, Long institutionId,
      Long questionId, String optionKey, boolean correct, String studentResourceKey,
      String institutionResourceKey, String questionResourceKey) {
    Long id = jdbc.queryForObject("""
      insert into student_question_attempt(
        student_id,institution_id,question_id,selected_option,correct,answered_at,qa_test_run_id
      ) values (?,?,?,?,?,now(),?) returning id
      """, Long.class, studentId, institutionId, questionId, optionKey, correct, runId);
    addResource(runId, key, "STUDENT_QUESTION_ATTEMPT", id, 10,
        List.of(studentResourceKey, institutionResourceKey, questionResourceKey), "student_question_attempt");
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
    if ("PROGRAM_SUBJECT_MAP".equals(kind)) {
      Map<String,Object> ref=readJsonMap(resource.get("resource_ref"));
      long subjectId=number(ref.get("subjectId"));
      int deleted=jdbc.update("""
        delete from curriculum_program_subject where program_id=? and subject_id=? and qa_test_run_id=?
        """,resourceId,subjectId,runId);
      if(deleted==0) {
        Boolean remaining=jdbc.queryForObject("""
          select exists(select 1 from curriculum_program_subject where program_id=? and subject_id=?)
          """,Boolean.class,resourceId,subjectId);
        if(Boolean.TRUE.equals(remaining))
          throw new IllegalStateException("Owned course subject mapping could not be deleted: "+resource.get("resource_key")+".");
      }
      jdbc.update("""
        update qa_test_run_resource set state='CLEANED',cleaned_at=now(),
          cleanup_detail=?::jsonb
        where id=? and run_id=? and state<>'CLEANED'
        """,json(Map.of("deletedRows",deleted,"confirmedAbsentOrOwned",true)),manifestId,runId);
      return;
    }
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
      case "COURSE_IDENTITY" -> new DeleteSpec(
          "delete from institution_course_identity where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_course_identity where id=?");
      case "COURSE" -> new DeleteSpec(
          "delete from curriculum_program where id=? and qa_test_run_id=?",
          "select qa_test_run_id from curriculum_program where id=?");
      case "COURSE_SUBJECT" -> new DeleteSpec(
          "delete from curriculum_subject where id=? and qa_test_run_id=?",
          "select qa_test_run_id from curriculum_subject where id=?");
      case "COURSE_CHAPTER" -> new DeleteSpec(
          "delete from curriculum_chapter where id=? and qa_test_run_id=?",
          "select qa_test_run_id from curriculum_chapter where id=?");
      case "STUDENT_QUESTION_ATTEMPT" -> new DeleteSpec(
          "delete from student_question_attempt where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student_question_attempt where id=?");
      case "STUDENT_COURSE_ENROLLMENT" -> new DeleteSpec(
          "delete from student_program_enrollment where id=? and qa_test_run_id=?",
          "select qa_test_run_id from student_program_enrollment where id=?");
      case "COURSE_ENTITLEMENT" -> new DeleteSpec(
          "delete from institution_course_entitlement where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_course_entitlement where id=?");
      case "COURSE_ASSIGNMENT" -> new DeleteSpec(
          "delete from institution_course_assignment where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_course_assignment where id=?");
      case "COURSE_RELEASE" -> new DeleteSpec(
          "delete from institution_course_release where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_course_release where id=?");
      case "INSTITUTION_ADMIN_ACCOUNT" -> new DeleteSpec(
          "delete from institution_admin_account where id=? and qa_test_run_id=?",
          "select qa_test_run_id from institution_admin_account where id=?");
      case "STAFF_ACCOUNT" -> new DeleteSpec(
          "delete from staff_account where id=? and qa_test_run_id=?",
          "select qa_test_run_id from staff_account where id=?");
      case "QUESTION_OPTION" -> new DeleteSpec(
          "delete from question_option where id=? and qa_test_run_id=?",
          "select qa_test_run_id from question_option where id=?");
      case "QUESTION" -> new DeleteSpec(
          "delete from question where id=? and qa_test_run_id=?",
          "select qa_test_run_id from question where id=?");
      case "LESSON" -> new DeleteSpec(
          "delete from lesson where id=? and qa_test_run_id=?",
          "select qa_test_run_id from lesson where id=?");
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
    List<String> protectedTables = List.of("institution_course_identity", "curriculum_program", "curriculum_subject", "curriculum_program_subject", "curriculum_chapter", "institution_marketplace_offer", "institution_tenant", "institution_branding", "institution_admin_account", "staff_account", "institution_course_release", "institution_course_assignment", "institution_course_entitlement", "student_program_enrollment", "user_account", "student", "guardian_student", "student_login_credential", "institution_user_membership", "institution_membership", "student_track_enrollment", "student_lesson_progress", "student_question_attempt", "lesson", "question", "question_option");
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
    Map<String,String> primaryKeys = Map.ofEntries(
        Map.entry("institution_course_identity", "id"),
        Map.entry("curriculum_program", "id"),
        Map.entry("curriculum_subject", "id"),
        Map.entry("curriculum_program_subject", "program_id, t.subject_id"),
        Map.entry("curriculum_chapter", "id"),
        Map.entry("institution_marketplace_offer", "id"),
        Map.entry("institution_tenant", "id"),
        Map.entry("institution_branding", "institution_id"),
        Map.entry("institution_admin_account", "id"),
        Map.entry("staff_account", "id"),
        Map.entry("institution_course_release", "id"),
        Map.entry("institution_course_assignment", "id"),
        Map.entry("institution_course_entitlement", "id"),
        Map.entry("student_program_enrollment", "id"),
        Map.entry("student_login_credential", "id"),
        Map.entry("user_account", "id"),
        Map.entry("student", "id"),
        Map.entry("guardian_student", "id"),
        Map.entry("institution_user_membership", "id"),
        Map.entry("institution_membership", "id"),
        Map.entry("student_track_enrollment", "id"),
        Map.entry("student_lesson_progress", "id"),
        Map.entry("student_question_attempt", "id"),
        Map.entry("lesson", "id"),
        Map.entry("question", "id"),
        Map.entry("question_option", "id"));
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
