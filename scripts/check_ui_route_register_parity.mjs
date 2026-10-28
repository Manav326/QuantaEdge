#!/usr/bin/env node
/**
 * Require every actual Next.js page route to have exactly one source-register row.
 * This is a source inventory contract, not evidence of runtime or visual QA.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { buildRouteInventory } from './inventory_ui_routes.mjs';
import { parseCsv } from './validate_qa_registers.mjs';

export function normalizeUiPlatform(platform) {
  const value = String(platform ?? '').trim();
  return value === 'people' || value === 'people-hrms' || value === 'hrms' ? 'people' : value;
}

function routeKey(route) {
  return [
    normalizeUiPlatform(route.platform ?? route.app),
    String(route.route_or_destination ?? route.route ?? '').trim(),
    String(route.source_path ?? route.file ?? '').trim()
  ].join('::');
}

export function inspectRouteRegisterParity(generatedPageRoutes, uiInventoryRows) {
  const pages = generatedPageRoutes.filter(route => route.kind === 'page' && route.primaryRoute !== false);
  const registeredPages = uiInventoryRows.filter(row => String(row.entry_type ?? '').trim() === 'Next.js page');
  const generatedKeys = new Map();
  const registeredKeys = new Map();

  for (const route of pages) {
    const key = routeKey(route);
    if (!generatedKeys.has(key)) generatedKeys.set(key, []);
    generatedKeys.get(key).push(route);
  }
  for (const row of registeredPages) {
    const key = routeKey(row);
    if (!registeredKeys.has(key)) registeredKeys.set(key, []);
    registeredKeys.get(key).push(row);
  }

  const duplicateGenerated = [...generatedKeys.entries()]
    .filter(([, values]) => values.length > 1)
    .map(([key]) => key);
  const duplicateRegistered = [...registeredKeys.entries()]
    .filter(([, values]) => values.length > 1)
    .map(([key]) => key);
  const missingFromRegister = [...generatedKeys.keys()]
    .filter(key => !registeredKeys.has(key))
    .map(key => {
      const route = generatedKeys.get(key)[0];
      return { app: normalizeUiPlatform(route.app), route: route.route, source: route.file };
    });
  const noLongerInSource = [...registeredKeys.keys()]
    .filter(key => !generatedKeys.has(key))
    .map(key => {
      const row = registeredKeys.get(key)[0];
      return { app: normalizeUiPlatform(row.platform), route: row.route_or_destination, source: row.source_path };
    });

  return {
    generatedPageCount: pages.length,
    registeredPageCount: registeredPages.length,
    missingFromRegister,
    noLongerInSource,
    duplicateGenerated,
    duplicateRegistered
  };
}

export function assertRouteRegisterParity(report) {
  const problems = [];
  if (report.missingFromRegister.length) {
    problems.push('Generated page routes missing from docs/qa/ui-screen-inventory.csv: ' +
      report.missingFromRegister.map(r => r.app + ':' + r.route + ' (' + r.source + ')').join('; '));
  }
  if (report.noLongerInSource.length) {
    problems.push('Registered page routes no longer present in source: ' +
      report.noLongerInSource.map(r => r.app + ':' + r.route + ' (' + r.source + ')').join('; '));
  }
  if (report.duplicateGenerated.length) problems.push('Duplicate generated route identities: ' + report.duplicateGenerated.join('; '));
  if (report.duplicateRegistered.length) problems.push('Duplicate UI route register identities: ' + report.duplicateRegistered.join('; '));
  if (report.generatedPageCount !== report.registeredPageCount) {
    problems.push('Generated page count (' + report.generatedPageCount + ') differs from registered page count (' + report.registeredPageCount + ').');
  }
  if (problems.length) throw new Error(problems.join('\n'));
  return report;
}

export async function checkUiRouteRegisterParity(root = process.cwd()) {
  const inventory = await buildRouteInventory(root);
  const csv = readFileSync(resolve(root, 'docs/qa/ui-screen-inventory.csv'), 'utf8');
  const rows = parseCsv(csv, 'docs/qa/ui-screen-inventory.csv');
  const header = rows[0];
  const objects = rows.slice(1).map(values => Object.fromEntries(header.map((key, index) => [key, values[index] ?? ''])));
  const generated = inventory.apps.flatMap(app => app.routes.map(route => ({ ...route, app: app.app })));
  return assertRouteRegisterParity(inspectRouteRegisterParity(generated, objects));
}

async function main(argv = process.argv.slice(2)) {
  const rootIndex = argv.indexOf('--root');
  const root = rootIndex >= 0 ? resolve(argv[rootIndex + 1] || '.') : process.cwd();
  const report = await checkUiRouteRegisterParity(root);
  const byApp = {};
  for (const route of report.missingFromRegister) byApp[route.app] = (byApp[route.app] ?? 0) + 1;
  console.log('UI route register parity passed: ' + report.generatedPageCount +
    ' generated Next.js page routes match exactly one inventory row; Android destinations remain independently inventoried.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error('UI route register parity failed: ' + error.message);
    process.exitCode = 1;
  });
}
