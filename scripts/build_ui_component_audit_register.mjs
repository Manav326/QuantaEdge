#!/usr/bin/env node
/**
 * Expand the route-level audit into a full source-module candidate register.
 *
 * The graph only discovers literal local imports and stylesheet references. It
 * does not prove a conditional component rendered or that visual behavior passed.
 * Existing curated component review/evidence is preserved; new module rows start
 * unreviewed and need explicit follow-up before any sign-off.
 */
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseCsv } from './validate_qa_registers.mjs';

export const COMPONENT_AUDIT_COLUMNS = [
  'component_audit_id', 'platform', 'component_name', 'component_source_path',
  'component_kind', 'consuming_audit_ids', 'source_review_status',
  'layout_responsive_status', 'overflow_and_scroll_status', 'light_dark_theme_status',
  'english_hindi_layout_status', 'interaction_accessibility_status',
  'runtime_verification', 'exact_commit_sha', 'environment_and_viewports',
  'evidence_url', 'defect_ids', 'overall_status', 'reviewer_notes'
];

export function recordsFromCsv(content, label) {
  const rows = parseCsv(content, label);
  const headers = rows[0];
  return rows.slice(1).map((values, index) => {
    const record = {};
    headers.forEach((header, columnIndex) => { record[header] = values[columnIndex] ?? ''; });
    record.__row = index + 2;
    return record;
  });
}

export function toCsv(rows, columns) {
  const escape = value => {
    const text = value == null ? '' : String(value);
    return /[",\r\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
  };
  return [columns.join(','), ...rows.map(row => columns.map(column => escape(row[column])).join(','))].join('\n') + '\n';
}

export function componentAuditIdForSource(sourcePath) {
  const digest = createHash('sha256').update(String(sourcePath)).digest('hex').slice(0, 12).toUpperCase();
  return 'CMP-SOURCE-' + digest;
}

export function normalizeAuditPlatform(platform) {
  const value = String(platform ?? '').trim();
  if (value === 'people' || value === 'people-hrms' || value === 'hrms') return 'hrms';
  return value;
}

function sourcePlatform(sourcePath, consumingPlatforms) {
  if (String(sourcePath).startsWith('apps/web/')) return 'web';
  if (String(sourcePath).startsWith('apps/admin/')) return 'admin';
  if (String(sourcePath).startsWith('apps/people/')) return 'hrms';
  if (String(sourcePath).startsWith('apps/android/')) return 'android';
  const platforms = [...new Set(consumingPlatforms.map(normalizeAuditPlatform))].sort();
  return platforms.length === 1 ? platforms[0] : platforms.length ? 'shared' : 'unknown';
}

function sourceName(sourcePath) {
  const name = String(sourcePath).split('/').pop() || String(sourcePath);
  return name.replace(/\.(tsx|jsx|css|scss)$/i, '');
}

function appendUnique(target, values) {
  const result = [...target];
  const known = new Set(result);
  for (const value of values) {
    const normalized = String(value ?? '').trim();
    if (normalized && !known.has(normalized)) {
      result.push(normalized);
      known.add(normalized);
    }
  }
  return result;
}

/**
 * Build review tables in memory.
 *
 * graph: JSON output from inventory_ui_component_imports.mjs
 * routeAuditRows: records parsed from route-component-audit-register.csv
 * curatedComponentRows: records parsed from component-instance-audit-register.csv
 */
export function buildSourceModuleAudit(graph, routeAuditRows, curatedComponentRows) {
  if (!graph || !Array.isArray(graph.routes)) throw new Error('Source graph must contain a routes array.');
  if (!Array.isArray(routeAuditRows) || !routeAuditRows.length) throw new Error('Route audit register has no data rows.');
  if (!Array.isArray(curatedComponentRows) || !curatedComponentRows.length) throw new Error('Curated component register has no data rows.');

  const routeBySource = new Map();
  const routeById = new Map();
  for (const route of routeAuditRows) {
    const source = String(route.source_path ?? '').trim();
    const id = String(route.audit_id ?? '').trim();
    if (!source || !id) throw new Error('Every route audit row needs source_path and audit_id.');
    if (routeById.has(id)) throw new Error('Duplicate route audit_id: ' + id);
    routeById.set(id, route);
    if (!routeBySource.has(source)) routeBySource.set(source, []);
    routeBySource.get(source).push(route);
  }

  const curatedBySource = new Map();
  const curatedById = new Map();
  for (const component of curatedComponentRows) {
    const source = String(component.component_source_path ?? '').trim();
    const id = String(component.component_audit_id ?? '').trim();
    if (!source || !id) throw new Error('Every curated component row needs component_source_path and component_audit_id.');
    if (curatedById.has(id)) throw new Error('Duplicate curated component_audit_id: ' + id);
    curatedById.set(id, component);
    if (!curatedBySource.has(source)) curatedBySource.set(source, []);
    curatedBySource.get(source).push(component);
  }

  const routeConsumersBySource = new Map();
  const moduleKindsBySource = new Map();
  const routeAdditions = new Map();
  let mappedRoutes = 0;

  for (const graphRoute of graph.routes) {
    const sourcePath = String(graphRoute.sourceFile ?? '').trim();
    const matchingRoutes = routeBySource.get(sourcePath) ?? [];
    if (matchingRoutes.length !== 1) {
      throw new Error('Expected exactly one route audit row for generated page ' + sourcePath + '; found ' + matchingRoutes.length + '.');
    }
    const routeAudit = matchingRoutes[0];
    const routeId = String(routeAudit.audit_id);
    mappedRoutes += 1;
    const candidates = new Map();
    for (const path of graphRoute.uiModuleCandidates ?? []) candidates.set(String(path), 'tsx-jsx-module-candidate');
    for (const path of graphRoute.stylesheetModules ?? []) candidates.set(String(path), 'stylesheet-module');

    const additions = [];
    for (const [path, kind] of candidates) {
      moduleKindsBySource.set(path, kind);
      if (!routeConsumersBySource.has(path)) routeConsumersBySource.set(path, new Set());
      routeConsumersBySource.get(path).add(routeId);
      const curated = curatedBySource.get(path) ?? [];
      if (curated.length) {
        additions.push(...curated.map(item => String(item.component_audit_id)));
      } else {
        additions.push(componentAuditIdForSource(path));
      }
    }
    routeAdditions.set(routeId, appendUnique(
      String(routeAudit.component_audit_ids ?? '').split(';').map(value => value.trim()).filter(Boolean),
      additions
    ));
  }

  // Preserve curated rows and their statuses. Add graph-discovered consumer links
  // without downgrading any prior source review or runtime evidence.
  const componentById = new Map();
  for (const original of curatedComponentRows) {
    const row = {};
    for (const column of COMPONENT_AUDIT_COLUMNS) row[column] = String(original[column] ?? '');
    const consumers = routeConsumersBySource.get(String(original.component_source_path)) ?? new Set();
    row.consuming_audit_ids = appendUnique(
      String(row.consuming_audit_ids ?? '').split(';').map(value => value.trim()).filter(Boolean),
      [...consumers].sort()
    ).join(';');
    componentById.set(row.component_audit_id, row);
  }

  let generatedRows = 0;
  for (const [path, kind] of [...moduleKindsBySource.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    if ((curatedBySource.get(path) ?? []).length) continue;
    const consumers = [...(routeConsumersBySource.get(path) ?? new Set())].sort();
    const platforms = consumers.map(id => routeById.get(id)?.platform ?? '');
    const id = componentAuditIdForSource(path);
    if (componentById.has(id)) {
      const existing = componentById.get(id);
      if (existing.component_source_path !== path) throw new Error('Stable component ID collision for ' + path);
      existing.consuming_audit_ids = appendUnique(
        String(existing.consuming_audit_ids ?? '').split(';').filter(Boolean), consumers
      ).join(';');
      continue;
    }
    componentById.set(id, {
      component_audit_id: id,
      platform: sourcePlatform(path, platforms),
      component_name: sourceName(path),
      component_source_path: path,
      component_kind: kind,
      consuming_audit_ids: consumers.join(';'),
      source_review_status: 'NOT_REVIEWED',
      layout_responsive_status: 'NOT_REVIEWED',
      overflow_and_scroll_status: 'NOT_REVIEWED',
      light_dark_theme_status: 'NOT_REVIEWED',
      english_hindi_layout_status: 'NOT_REVIEWED',
      interaction_accessibility_status: 'NOT_REVIEWED',
      runtime_verification: 'NOT_EXECUTED',
      exact_commit_sha: '',
      environment_and_viewports: '',
      evidence_url: '',
      defect_ids: '',
      overall_status: 'NOT_STARTED',
      reviewer_notes: 'Generated from the static local-import graph. Module-level candidate only: inspect component declarations and conditional branches; this row is not a visual/runtime pass.'
    });
    generatedRows += 1;
  }

  const outputRoutes = routeAuditRows.map(row => ({
    ...row,
    component_audit_ids: (routeAdditions.get(String(row.audit_id)) ?? String(row.component_audit_ids ?? '').split(';').filter(Boolean)).join(';')
  }));
  const components = [...componentById.values()].sort((a, b) =>
    String(a.component_source_path).localeCompare(String(b.component_source_path))
  );

  const knownComponentIds = new Set(components.map(component => String(component.component_audit_id)));
  for (const route of outputRoutes) {
    const refs = String(route.component_audit_ids ?? '').split(';').map(value => value.trim()).filter(Boolean);
    const unknown = refs.filter(id => !knownComponentIds.has(id));
    if (unknown.length) throw new Error('Route ' + route.audit_id + ' references unknown components: ' + unknown.join(', '));
  }
  for (const component of components) {
    const consumers = String(component.consuming_audit_ids ?? '').split(';').map(value => value.trim()).filter(Boolean);
    const unknown = consumers.filter(id => !routeById.has(id));
    if (unknown.length) throw new Error('Component ' + component.component_audit_id + ' references unknown routes: ' + unknown.join(', '));
  }

  const allCandidatePaths = [...moduleKindsBySource.keys()];
  const allUiPaths = new Set();
  for (const graphRoute of graph.routes) for (const path of graphRoute.uiModuleCandidates ?? []) allUiPaths.add(String(path));
  const allStylesheetPaths = new Set();
  for (const graphRoute of graph.routes) for (const path of graphRoute.stylesheetModules ?? []) allStylesheetPaths.add(String(path));

  return {
    componentRows: components,
    routeRows: outputRoutes,
    summary: {
      generatedPageRoutes: graph.routes.length,
      matchedRouteRows: mappedRoutes,
      routeRegisterRows: routeAuditRows.length,
      componentRegisterRows: components.length,
      curatedComponentRows: curatedComponentRows.length,
      generatedModuleRows: generatedRows,
      uniqueUiModuleCandidates: allUiPaths.size,
      uniqueStylesheetModules: allStylesheetPaths.size,
      uniqueGraphTrackedPaths: allCandidatePaths.length,
      uiRouteComponentLinks: outputRoutes.reduce((count, route) =>
        count + String(route.component_audit_ids ?? '').split(';').filter(Boolean).length, 0),
      runtimeVerifiedGeneratedRows: 0,
      note: 'This is an expanded source-module candidate register. JSX/TSX files may declare multiple components; conditional rendering and browser/device visual states still require explicit review and evidence.'
    }
  };
}

function cliOption(args, name) {
  const index = args.indexOf(name);
  if (index < 0) return null;
  const value = args[index + 1];
  if (!value || value.startsWith('--')) throw new Error(name + ' requires a value.');
  return value;
}

async function main(argv = process.argv.slice(2)) {
  const root = resolve(cliOption(argv, '--root') ?? process.cwd());
  const graphPath = cliOption(argv, '--graph');
  const componentOutput = cliOption(argv, '--component-output');
  const routeOutput = cliOption(argv, '--route-output');
  if (!graphPath || !componentOutput || !routeOutput) {
    throw new Error('Usage: node scripts/build_ui_component_audit_register.mjs --root . --graph graph.json --component-output components.csv --route-output routes.csv');
  }

  const graph = JSON.parse(readFileSync(resolve(root, graphPath), 'utf8'));
  const routeCsv = readFileSync(resolve(root, 'docs/ui-ux-audit/route-component-audit-register.csv'), 'utf8');
  const componentCsv = readFileSync(resolve(root, 'docs/ui-ux-audit/component-instance-audit-register.csv'), 'utf8');
  const result = buildSourceModuleAudit(
    graph,
    recordsFromCsv(routeCsv, 'route-component-audit-register.csv'),
    recordsFromCsv(componentCsv, 'component-instance-audit-register.csv')
  );
  const componentPath = resolve(componentOutput);
  const routePath = resolve(routeOutput);
  await mkdir(dirname(componentPath), { recursive: true });
  await mkdir(dirname(routePath), { recursive: true });
  await writeFile(componentPath, toCsv(result.componentRows, COMPONENT_AUDIT_COLUMNS), 'utf8');
  const routeHeaders = parseCsv(routeCsv, 'route-component-audit-register.csv')[0];
  await writeFile(routePath, toCsv(result.routeRows, routeHeaders), 'utf8');
  console.log('Expanded route/component source register generated.');
  console.log(JSON.stringify(result.summary, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error('Component audit register generation failed: ' + error.message);
    process.exitCode = 1;
  });
}
