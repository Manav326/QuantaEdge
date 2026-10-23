#!/usr/bin/env node
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { dirname, resolve, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseCsv } from '../../../scripts/validate_qa_registers.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const STRICT = process.argv.includes('--strict') || process.env.QE_E2E_PRODUCTION_GATE === 'true';
const SCENARIO_PATH = resolve(ROOT, 'docs/qa/phase-1-workflow-scenario-register.csv');
const COVERAGE_PATH = resolve(ROOT, 'tests/ui-e2e/scenario-coverage.csv');
const reportPath = process.env.QE_E2E_JSON_REPORT || resolve(ROOT, 'tests/ui-e2e/test-results/results.json');
const reportOutputPath = process.env.QE_E2E_COVERAGE_REPORT || resolve(ROOT, 'tests/ui-e2e/test-results/coverage-report.json');

function assertUnique(rows, key, label) {
  const seen = new Set();
  const duplicates = [];
  for (const row of rows) {
    const value = String(row[key] || '').trim();
    if (!value) throw new Error(label + ' contains a blank ' + key + '.');
    if (seen.has(value)) duplicates.push(value);
    seen.add(value);
  }
  if (duplicates.length) throw new Error(label + ' has duplicate IDs: ' + [...new Set(duplicates)].join(', '));
}

function csvObjects(input, label) {
  const table = parseCsv(input, label);
  if (table.length < 2) throw new Error(label + ' has no data rows.');
  const header = table[0].map(value => String(value).trim());
  if (header.some(value => !value)) throw new Error(label + ' has a blank column name.');
  return table.slice(1).map((values, rowIndex) => {
    if (values.length !== header.length) {
      throw new Error(label + ' row ' + (rowIndex + 2) + ' has ' + values.length +
        ' fields; expected ' + header.length + '.');
    }
    return Object.fromEntries(header.map((key, index) => [key, String(values[index] ?? '')]));
  });
}

async function fileExists(path) {
  try { await access(path); return true; } catch { return false; }
}

function collectSpecs(suites, inheritedFile = '', output = []) {
  for (const suite of suites || []) {
    const file = String(suite.file || inheritedFile).replace(/\\/g, '/');
    for (const spec of suite.specs || []) {
      const specFile = String(spec.file || file).replace(/\\/g, '/');
      const testEntries = spec.tests || [];
      const passed = testEntries.some(entry =>
        Array.isArray(entry.results) && entry.results.length > 0 &&
        entry.results[entry.results.length - 1]?.status === 'passed'
      );
      output.push({ file: specFile, title: String(spec.title || ''), passed });
    }
    collectSpecs(suite.suites || [], file, output);
  }
  return output;
}

async function main() {
  const [scenarioText, coverageText] = await Promise.all([
    readFile(SCENARIO_PATH, 'utf8'),
    readFile(COVERAGE_PATH, 'utf8')
  ]);
  // The repository-wide validator parser returns a 2D table; convert it to records
  // before referring to columns by name. Keep one strict parser for all CSV input.
  const scenarios = csvObjects(scenarioText, SCENARIO_PATH);
  const mappings = csvObjects(coverageText, COVERAGE_PATH);
  assertUnique(scenarios, 'case_id', 'Workflow scenario register');
  assertUnique(mappings, 'scenario_id', 'Scenario coverage register');

  const knownIds = new Set(scenarios.map(row => String(row.case_id).trim()));
  const unknownMappings = mappings.filter(row => !knownIds.has(String(row.scenario_id).trim()))
    .map(row => String(row.scenario_id).trim());
  const invalidStatuses = mappings.filter(row =>
    !['PARTIAL', 'COMPLETE', 'MANUAL_VERIFIED', 'NOT_IMPLEMENTED'].includes(row.coverage_status)
  ).map(row => String(row.scenario_id).trim());
  const mappingById = new Map(mappings.map(row => [String(row.scenario_id).trim(), row]));
  const missing = scenarios.filter(row => {
    const mapping = mappingById.get(String(row.case_id).trim());
    return !mapping || !['COMPLETE', 'MANUAL_VERIFIED'].includes(mapping.coverage_status);
  }).map(row => String(row.case_id).trim());

  // Mapping coverage answers: do we have an explicit test/evidence path for this scenario?
  // It is intentionally separate from verification coverage; PARTIAL mappings must never
  // satisfy the production gate below.
  const mappedScenarios = scenarios.filter(row => {
    const mapping = mappingById.get(String(row.case_id).trim());
    return mapping && ['PARTIAL', 'COMPLETE', 'MANUAL_VERIFIED'].includes(mapping.coverage_status) &&
      String(mapping.test_file || '').split(';').some(file => file.trim());
  });
  const mappedScenarioPercent = scenarios.length
    ? Number((mappedScenarios.length / scenarios.length * 100).toFixed(1))
    : 0;

  const malformedPaths = [];
  const suiteRoot = resolve(ROOT, 'tests/ui-e2e');
  for (const mapping of mappings) {
    for (const file of String(mapping.test_file || '').split(';').map(value => value.trim()).filter(Boolean)) {
      const path = resolve(suiteRoot, file);
      if (!path.startsWith(suiteRoot + '/') || !(await fileExists(path))) {
        malformedPaths.push(String(mapping.scenario_id) + ' -> ' + file);
      }
    }
  }

  const testedSha = process.env.GITHUB_SHA || process.env.QE_E2E_SOURCE_SHA || '';
  let report = null;
  let reportError = '';
  try {
    report = JSON.parse(await readFile(reportPath, 'utf8'));
  } catch (error) {
    reportError = error && error.code === 'ENOENT'
      ? 'Playwright JSON report not found.'
      : 'Playwright JSON report could not be parsed.';
  }
  const specs = report ? collectSpecs(report.suites || []) : [];
  const runtimeGaps = [];
  for (const mapping of mappings.filter(row => row.coverage_status === 'COMPLETE')) {
    const id = String(mapping.scenario_id).trim();
    const candidates = String(mapping.test_file || '').split(';')
      .map(value => value.trim()).filter(Boolean).map(basename);
    const matching = specs.filter(spec =>
      candidates.some(file => spec.file.endsWith('/' + file) || spec.file === file) &&
      spec.title.includes('[' + id + ']')
    );
    if (!matching.some(spec => spec.passed)) {
      runtimeGaps.push(id + ' has no passing exact-scenario browser test in this run');
    }
  }

  const manualGaps = mappings.filter(row => row.coverage_status === 'MANUAL_VERIFIED').filter(row =>
    !/^[0-9a-f]{40}$/i.test(String(row.tested_commit_sha || '')) ||
    String(row.tested_commit_sha).toLowerCase() !== testedSha.toLowerCase() ||
    !String(row.evidence_url || '').startsWith('https://')
  ).map(row => String(row.scenario_id) + ' lacks current-commit manual evidence');

  const reportBody = {
    schemaVersion: 1,
    testedCommitSha: testedSha || 'local-unpinned',
    generatedAt: new Date().toISOString(),
    strictProductionGate: STRICT,
    scenarioCount: scenarios.length,
    mappedScenarioCount: mappedScenarios.length,
    mappedScenarioPercent,
    unmappedScenarioCount: scenarios.length - mappedScenarios.length,
    fullyVerifiedScenarioCount: scenarios.length - missing.length,
    completeAutomationMappings: mappings.filter(row => row.coverage_status === 'COMPLETE').length,
    verifiedManualMappings: mappings.filter(row => row.coverage_status === 'MANUAL_VERIFIED').length,
    partialMappings: mappings.filter(row => row.coverage_status === 'PARTIAL').length,
    unimplementedMappings: mappings.filter(row => row.coverage_status === 'NOT_IMPLEMENTED').length,
    missingOrPartialScenarios: missing,
    unknownMappings,
    invalidStatuses,
    malformedPaths,
    runtimeGaps,
    manualGaps,
    reportPath,
    reportError,
    ready: missing.length === 0 && unknownMappings.length === 0 && invalidStatuses.length === 0 &&
      malformedPaths.length === 0 && runtimeGaps.length === 0 && manualGaps.length === 0 && !reportError
  };
  await mkdir(dirname(reportOutputPath), { recursive: true });
  await writeFile(reportOutputPath, JSON.stringify(reportBody, null, 2) + '\n', { mode: 0o600 });

  process.stdout.write(
    'Scenario map: ' + reportBody.mappedScenarioCount + '/' + scenarios.length +
    ' scenarios mapped (' + reportBody.mappedScenarioPercent + '%); ' +
    reportBody.fullyVerifiedScenarioCount + '/' + scenarios.length + ' fully verified; ' +
    reportBody.partialMappings + ' partial mappings; ' + reportBody.unmappedScenarioCount + ' unmapped.\n' +
    'Coverage report: ' + reportOutputPath + '\n'
  );
  if (unknownMappings.length) process.stderr.write('Unknown coverage IDs: ' + unknownMappings.join(', ') + '\n');
  if (invalidStatuses.length) process.stderr.write('Invalid coverage statuses: ' + invalidStatuses.join(', ') + '\n');
  if (malformedPaths.length) process.stderr.write('Missing test files: ' + malformedPaths.join(', ') + '\n');
  if (runtimeGaps.length) process.stderr.write('Runtime coverage gaps: ' + runtimeGaps.join('; ') + '\n');
  if (manualGaps.length) process.stderr.write('Manual evidence gaps: ' + manualGaps.join('; ') + '\n');
  if (missing.length) process.stderr.write('Not fully verified: ' + missing.join(', ') + '\n');
  if (reportError) process.stderr.write(reportError + '\n');
  if (unknownMappings.length || invalidStatuses.length || malformedPaths.length ||
      (STRICT && (missing.length || runtimeGaps.length || manualGaps.length || reportError))) {
    process.exitCode = 1;
  }
}

main().catch(error => {
  process.stderr.write('Scenario coverage validation failed: ' +
    (error instanceof Error ? error.message : String(error)) + '\n');
  process.exitCode = 1;
});
