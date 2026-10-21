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
