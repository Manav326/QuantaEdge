#!/usr/bin/env node
/**
 * Validate cross-references between the Phase 1 QA audit CSV registers.
 *
 * This validates documentation consistency only. It does not run an app flow,
 * prove endpoint authorization, or certify a scenario as passed.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function parseCsv(input, label = "CSV") {
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;

  for (let i = 0; i < input.length; i += 1) {
    const char = input[i];
    if (quoted) {
      if (char === '"') {
        if (input[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else {
          quoted = false;
        }
      } else {
        cell += char;
      }
      continue;
    }

    if (char === '"' && cell.length === 0) {
      quoted = true;
    } else if (char === ",") {
      row.push(cell);
      cell = "";
    } else if (char === "\r" || char === "\n") {
      if (char === "\r" && input[i + 1] === "\n") i += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }

  if (quoted) throw new Error(`${label}: unterminated quoted field.`);
  if (cell.length > 0 || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }

  while (rows.length > 0 && rows.at(-1).every(value => value === "")) rows.pop();
  if (rows.length === 0) throw new Error(`${label}: file has no header row.`);

  const header = rows[0].map((value, index) => index === 0 ? value.replace(/^\uFEFF/, "").trim() : value.trim());
  if (header.some(value => value.length === 0)) throw new Error(`${label}: header contains an empty column name.`);
  if (new Set(header).size !== header.length) throw new Error(`${label}: header contains duplicate column names.`);

  const normalized = [header];
  for (let i = 1; i < rows.length; i += 1) {
    const values = rows[i];
    if (values.length !== header.length) {
      throw new Error(`${label}: row ${i + 1} has ${values.length} columns; expected ${header.length}.`);
    }
    normalized.push(values);
  }
  return normalized;
}

function toObjects(rows, label) {
  if (!rows?.length) throw new Error(`${label}: table is empty.`);
  const header = rows[0];
  return rows.slice(1).map((values, index) => {
    const obj = {};
    header.forEach((key, columnIndex) => { obj[key] = values[columnIndex] ?? ""; });
    Object.defineProperty(obj, "__row", { value: index + 2, enumerable: false });
    return obj;
  });
}

function requireColumns(objects, required, label) {
  const columns = new Set(objects.length ? Object.keys(objects[0]) : []);
  const missing = required.filter(column => !columns.has(column));
  if (missing.length) throw new Error(`${label}: missing required columns: ${missing.join(", ")}.`);
}

function assertUnique(objects, key, label) {
  const seen = new Map();
  for (const row of objects) {
    const value = String(row[key] ?? "").trim();
    if (!value) throw new Error(`${label}: empty ${key} at row ${row.__row}.`);
    if (seen.has(value)) throw new Error(`${label}: duplicate ${key} "${value}" at rows ${seen.get(value)} and ${row.__row}.`);
    seen.set(value, row.__row);
  }
}

function scenarioRefs(rows, column, knownIds, label) {
  const blank = [];
  const unknown = [];
  for (const row of rows) {
    const value = String(row[column] ?? "").trim();
    if (!value) {
      blank.push(row.__row);
      continue;
    }
    for (const raw of value.split(";")) {
      const id = raw.trim();
      if (!id || !knownIds.has(id)) unknown.push({ row: row.__row, id });
    }
  }
  if (blank.length) throw new Error(`${label}: ${blank.length} rows have no scenario reference (first CSV row ${blank[0]}).`);
  if (unknown.length) {
    const first = unknown[0];
    throw new Error(`${label}: unknown/empty scenario ID "${first.id}" at CSV row ${first.row}.`);
  }
}

export function validateTables(tables) {
  const labels = {
    uiInventory: "UI inventory",
    scenarios: "workflow scenarios",
    apiRoutes: "API route inventory",
    runtimeLog: "runtime execution log",
    uiStates: "UI state checklist"
  };
  const obj = {};
  for (const name of Object.keys(labels)) {
    if (!tables[name]) throw new Error(`Missing input table: ${name}.`);
    obj[name] = toObjects(tables[name], labels[name]);
    if (!obj[name].length) throw new Error(`${labels[name]}: no data rows found.`);
  }

  requireColumns(obj.uiInventory, ["platform", "identifier", "scenario_ids"], labels.uiInventory);
  requireColumns(obj.scenarios, ["case_id", "execution_status"], labels.scenarios);
  requireColumns(obj.apiRoutes, ["api_id", "source_path", "transport", "effective_path", "scenario_ids", "runtime_verification"], labels.apiRoutes);
  requireColumns(obj.runtimeLog, ["scenario_id", "result_status", "tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc", "screenshot_log_evidence_url"], labels.runtimeLog);
  requireColumns(obj.uiStates, ["screen_key", "scenario_ids", "runtime_verification"], labels.uiStates);

  assertUnique(obj.scenarios, "case_id", labels.scenarios);
  assertUnique(obj.runtimeLog, "scenario_id", labels.runtimeLog);
  assertUnique(obj.uiStates, "screen_key", labels.uiStates);
  assertUnique(obj.apiRoutes, "api_id", labels.apiRoutes);

  const knownIds = new Set(obj.scenarios.map(row => String(row.case_id).trim()));
  if (knownIds.size !== obj.scenarios.length) throw new Error("Workflow scenario IDs are not unique.");

  scenarioRefs(obj.uiInventory, "scenario_ids", knownIds, labels.uiInventory);
  scenarioRefs(obj.apiRoutes, "scenario_ids", knownIds, labels.apiRoutes);
  scenarioRefs(obj.uiStates, "scenario_ids", knownIds, labels.uiStates);

  const runtimeIds = new Set(obj.runtimeLog.map(row => String(row.scenario_id).trim()));
  const missingRuntime = [...knownIds].filter(id => !runtimeIds.has(id));
  const extraRuntime = [...runtimeIds].filter(id => !knownIds.has(id));
  if (missingRuntime.length || extraRuntime.length) {
    throw new Error([
      "Runtime execution log must have exactly one row for every workflow scenario.",
      missingRuntime.length ? `Missing: ${missingRuntime.join(", ")}.` : "",
      extraRuntime.length ? `Unknown: ${extraRuntime.join(", ")}.` : ""
    ].filter(Boolean).join(" "));
  }

  const submittedResults = new Set(["PASS", "PASSED", "FAIL", "FAILED"]);
  const blockedResults = new Set(["BLOCKED", "SKIPPED"]);
  const requiredEvidence = ["tested_commit_sha", "environment_name", "steps_executed", "expected_result", "actual_result", "tester", "executed_at_utc"];
  for (const row of obj.runtimeLog) {
    const status = String(row.result_status ?? "").trim().toUpperCase();
    if (submittedResults.has(status) || blockedResults.has(status)) {
      const missing = requiredEvidence.filter(column => !String(row[column] ?? "").trim());
      if (missing.length) {
        throw new Error(`Runtime log row ${row.__row} (${row.scenario_id}, ${status}) lacks evidence metadata: ${missing.join(", ")}.`);
      }
      if (submittedResults.has(status) && !String(row.screenshot_log_evidence_url ?? "").trim()) {
        throw new Error(`Runtime log row ${row.__row} (${row.scenario_id}, ${status}) requires an evidence URL.`);
      }
    }
  }

  if (obj.uiInventory.length !== obj.uiStates.length) {
    throw new Error(`UI state checklist has ${obj.uiStates.length} rows but UI inventory has ${obj.uiInventory.length}; keep one row per inventory entry.`);
  }

  const platformCounts = {};
  for (const row of obj.uiInventory) {
    const platform = String(row.platform ?? "unknown");
    platformCounts[platform] = (platformCounts[platform] ?? 0) + 1;
  }
  const scenarioStatusCounts = {};
  for (const row of obj.scenarios) {
    const status = String(row.execution_status ?? "UNSPECIFIED");
    scenarioStatusCounts[status] = (scenarioStatusCounts[status] ?? 0) + 1;
  }

  return {
    uiScreens: obj.uiInventory.length,
    uiStateRows: obj.uiStates.length,
    apiMappings: obj.apiRoutes.length,
    scenarios: obj.scenarios.length,
    runtimeLogRows: obj.runtimeLog.length,
    scenarioStatusCounts,
    platformCounts
  };
}

function readTable(root, filename) {
  const path = resolve(root, "docs", "qa", filename);
  let content;
  try {
    content = readFileSync(path, "utf8");
  } catch (error) {
    throw new Error(`Cannot read docs/qa/${filename}: ${error.message}`);
  }
  return parseCsv(content, `docs/qa/${filename}`);
}

export function validateQaRegisters(root = process.cwd()) {
  const tables = {
    uiInventory: readTable(root, "ui-screen-inventory.csv"),
    scenarios: readTable(root, "phase-1-workflow-scenario-register.csv"),
    apiRoutes: readTable(root, "phase-1-api-route-inventory.csv"),
    runtimeLog: readTable(root, "phase-1-runtime-execution-log.csv"),
    uiStates: readTable(root, "phase-1-ui-state-coverage.csv")
  };
  return validateTables(tables);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const summary = validateQaRegisters(process.cwd());
    console.log("QA register integrity check passed (documentation consistency only).");
    console.log(JSON.stringify(summary, null, 2));
  } catch (error) {
    console.error(`QA register integrity check failed: ${error.message}`);
    process.exitCode = 1;
  }
}
