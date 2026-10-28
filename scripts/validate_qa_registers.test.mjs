import test from "node:test";
import assert from "node:assert/strict";

import { parseCsv, validateTables } from "./validate_qa_registers.mjs";

test("CSV parser handles commas, escaped quotes, CRLF, and embedded newlines", () => {
  const parsed = parseCsv(
    'name,detail\r\n"alpha, beta","said ""hello"""\r\n"line one\nline two",tail\r\n',
    "fixture"
  );
  assert.deepEqual(parsed, [
    ["name", "detail"],
    ["alpha, beta", 'said "hello"'],
    ["line one\nline two", "tail"]
  ]);
});

test("CSV parser rejects an unterminated quoted field", () => {
  assert.throws(() => parseCsv('id,value\n1,"broken', "fixture"), /unterminated quoted field/);
});

test("register validator accepts complete one-case fixture tables", () => {
  const tables = {
    uiInventory: [
      ["platform", "identifier", "scenario_ids"],
      ["web", "/", "CASE-1"]
    ],
    scenarios: [
      ["case_id", "execution_status"],
      ["CASE-1", "NOT_EXECUTED"]
    ],
    apiRoutes: [
      ["api_id", "source_path", "transport", "effective_path", "scenario_ids", "runtime_verification"],
      ["API-1", "src/Controller.java", "GET", "/api/demo", "CASE-1", "NOT_EXECUTED"]
    ],
    runtimeLog: [
      ["scenario_id", "result_status", "tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc", "screenshot_log_evidence_url"],
      ["CASE-1", "NOT_EXECUTED", "", "", "", "", "", "", "", ""]
    ],
    uiStates: [
      ["screen_key", "scenario_ids", "runtime_verification"],
      ["UI-1", "CASE-1", "NOT_EXECUTED"]
    ]
  };
  assert.deepEqual(validateTables(tables), {
    uiScreens: 1,
    uiStateRows: 1,
    apiMappings: 1,
    scenarios: 1,
    runtimeLogRows: 1,
    scenarioStatusCounts: { NOT_EXECUTED: 1 },
    platformCounts: { web: 1 }
  });
});

test("register validator rejects a dangling scenario ID", () => {
  const tables = {
    uiInventory: [["platform", "identifier", "scenario_ids"], ["web", "/", "MISSING"]],
    scenarios: [["case_id", "execution_status"], ["CASE-1", "NOT_EXECUTED"]],
    apiRoutes: [["api_id", "source_path", "transport", "effective_path", "scenario_ids", "runtime_verification"], ["API-1", "src.java", "GET", "/a", "CASE-1", "NOT_EXECUTED"]],
    runtimeLog: [["scenario_id", "result_status", "tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc", "screenshot_log_evidence_url"], ["CASE-1", "NOT_EXECUTED", "", "", "", "", "", "", "", ""]],
    uiStates: [["screen_key", "scenario_ids", "runtime_verification"], ["UI-1", "CASE-1", "NOT_EXECUTED"]]
  };
  assert.throws(() => validateTables(tables), /unknown.*scenario ID "MISSING"/);
});

test("register validator requires evidence fields before a scenario can be marked passed", () => {
  const tables = {
    uiInventory: [["platform", "identifier", "scenario_ids"], ["web", "/", "CASE-1"]],
    scenarios: [["case_id", "execution_status"], ["CASE-1", "PASS"]],
    apiRoutes: [["api_id", "source_path", "transport", "effective_path", "scenario_ids", "runtime_verification"], ["API-1", "src.java", "GET", "/a", "CASE-1", "NOT_EXECUTED"]],
    runtimeLog: [["scenario_id", "result_status", "tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc", "screenshot_log_evidence_url"], ["CASE-1", "PASS", "", "", "", "", "", "", "", ""]],
    uiStates: [["screen_key", "scenario_ids", "runtime_verification"], ["UI-1", "CASE-1", "NOT_EXECUTED"]]
  };
  assert.throws(() => validateTables(tables), /lacks evidence metadata/);
});

test("register validator requires one runtime-log row for each scenario", () => {
  const tables = {
    uiInventory: [["platform", "identifier", "scenario_ids"], ["web", "/", "CASE-1"]],
    scenarios: [["case_id", "execution_status"], ["CASE-1", "NOT_EXECUTED"], ["CASE-2", "NOT_EXECUTED"]],
    apiRoutes: [["api_id", "source_path", "transport", "effective_path", "scenario_ids", "runtime_verification"], ["API-1", "src.java", "GET", "/a", "CASE-1", "NOT_EXECUTED"]],
    runtimeLog: [["scenario_id", "result_status", "tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc", "screenshot_log_evidence_url"], ["CASE-1", "NOT_EXECUTED", "", "", "", "", "", "", "", ""]],
    uiStates: [["screen_key", "scenario_ids", "runtime_verification"], ["UI-1", "CASE-1", "NOT_EXECUTED"]]
  };
  assert.throws(() => validateTables(tables), /exactly one row for every workflow scenario/);
});


function componentAuditFixture() {
  const auditHeader = [
    "audit_id", "platform", "route_or_destination", "source_path", "scenario_ids", "component_audit_ids",
    "source_component_trace_status", "component_inventory_status", "breakpoint_layout_status",
    "overflow_and_scroll_status", "light_theme_status", "dark_theme_status",
    "english_layout_status", "hindi_layout_status", "loading_empty_error_states",
    "form_validation_states", "permissions_and_data_states", "interaction_and_accessibility",
    "runtime_verification", "exact_commit_sha", "environment_and_viewports", "evidence_url",
    "defect_ids", "overall_status", "reviewer_notes"
  ];
  const auditRow = [
    "UI-1", "web", "/", "apps/web/app/page.tsx", "CASE-1", "CMP-1",
    "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED",
    "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED",
    "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED",
    "NOT_EXECUTED", "", "", "", "", "NOT_STARTED", "Inventory only; not a pass."
  ];
  return {
    uiInventory: [["platform", "identifier", "scenario_ids"], ["web", "/", "CASE-1"]],
    scenarios: [["case_id", "execution_status"], ["CASE-1", "NOT_EXECUTED"]],
    apiRoutes: [
      ["api_id", "source_path", "transport", "effective_path", "scenario_ids", "runtime_verification"],
      ["API-1", "src/Controller.java", "GET", "/api/demo", "CASE-1", "NOT_EXECUTED"]
    ],
    runtimeLog: [
      ["scenario_id", "result_status", "tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc", "screenshot_log_evidence_url"],
      ["CASE-1", "NOT_EXECUTED", "", "", "", "", "", "", "", ""]
    ],
    uiStates: [
      ["screen_key", "platform", "route_or_destination", "source_path", "scenario_ids", "runtime_verification"],
      ["UI-1", "web", "/", "apps/web/app/page.tsx", "CASE-1", "NOT_EXECUTED"]
    ],
    componentAudit: [auditHeader, auditRow],
    componentInstances: [
      [
        "component_audit_id", "platform", "component_name", "component_source_path",
        "component_kind", "consuming_audit_ids", "source_review_status",
        "layout_responsive_status", "overflow_and_scroll_status", "light_dark_theme_status",
        "english_hindi_layout_status", "interaction_accessibility_status", "runtime_verification",
        "exact_commit_sha", "environment_and_viewports", "evidence_url", "defect_ids",
        "overall_status", "reviewer_notes"
      ],
      [
        "CMP-1", "web", "Fixture root", "apps/web/app/layout.tsx", "root-layout", "UI-1",
        "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED", "NOT_REVIEWED",
        "NOT_REVIEWED", "NOT_EXECUTED", "", "", "", "", "NOT_STARTED", "Inventory only."
      ]
    ]
  };
}

test("component audit register mirrors one route/state row and preserves non-passing defaults", () => {
  const result = validateTables(componentAuditFixture());
  assert.equal(result.routeComponentAuditRows, 1);
  assert.deepEqual(result.routeComponentAuditStatusCounts, { NOT_STARTED: 1 });
});

test("component audit register rejects a route that does not match its UI state row", () => {
  const tables = componentAuditFixture();
  tables.componentAudit[1][2] = "/wrong-route";
  assert.throws(() => validateTables(tables), /route_or_destination mismatch/);
});


test("component registry rejects a route reference missing from the component consumer list", () => {
  const tables = componentAuditFixture();
  tables.componentInstances[1][5] = "UNKNOWN-ROUTE";
  assert.throws(() => validateTables(tables), /references unknown consumer route/);
});

test("component registry requires a signed-off component to have review evidence", () => {
  const tables = componentAuditFixture();
  const header = tables.componentInstances[0];
  const row = tables.componentInstances[1];
  row[header.indexOf("overall_status")] = "SIGNED_OFF";
  row[header.indexOf("runtime_verification")] = "MANUAL_VERIFIED";
  row[header.indexOf("exact_commit_sha")] = "abc123";
  row[header.indexOf("environment_and_viewports")] = "Chrome 390px light English";
  row[header.indexOf("evidence_url")] = "https://example.invalid/evidence";
  assert.throws(() => validateTables(tables), /has unchecked categories/);
});

test("component registry enforces reverse consumer links for each route", () => {
  const tables = componentAuditFixture();
  tables.componentAudit[1][5] = "CMP-UNKNOWN";
  assert.throws(() => validateTables(tables), /unknown component/);
});
