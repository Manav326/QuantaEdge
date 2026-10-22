import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseCsv, type CsvRow } from './csv';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');

export type ScreenRow = CsvRow & {
  platform: string;
  route_or_destination: string;
  source_path: string;
  scenario_ids: string;
};

export type PageRoute = {
  app: 'web' | 'admin' | 'people';
  route: string;
  file: string;
  routeGroups: string[];
  primaryRoute: boolean;
};

export type UiManifest = {
  sourceSha: string;
  generatedAt: string;
  registerSha: string;
  screens: ScreenRow[];
  routes: PageRoute[];
  summary: {
    registeredWebPages: number;
    sourceWebPages: number;
    registeredNativeRoutes: number;
    scenarios: number;
  };
};

export async function loadUiManifest(): Promise<UiManifest> {
  const inventoryPath = resolve(ROOT, 'docs/qa/ui-screen-inventory.csv');
  const scenarioPath = resolve(ROOT, 'docs/qa/phase-1-workflow-scenario-register.csv');
  const inventoryText = await readFile(inventoryPath, 'utf8');
  const screens = parseCsv(inventoryText, inventoryPath) as ScreenRow[];
  const scenarios = parseCsv(await readFile(scenarioPath, 'utf8'), scenarioPath);

  // Import the repository's source scanner, not a checked-in/generated JSON snapshot.
  const scannerPath = resolve(ROOT, 'scripts/inventory_ui_routes.mjs');
  const { buildRouteInventory } = await import(scannerPath) as {
    buildRouteInventory: (repoRoot?: string) => Promise<any>;
  };
  const raw = await buildRouteInventory(ROOT);
  const routes: PageRoute[] = raw.apps.flatMap((app: any) =>
    app.routes
      .filter((route: any) => route.kind === 'page' && route.primaryRoute)
      .map((route: any) => ({
        app: app.app as PageRoute['app'],
        route: route.route as string,
        file: route.file as string,
        routeGroups: route.routeGroups as string[],
        primaryRoute: Boolean(route.primaryRoute)
      }))
  );

  const registered = screens.filter(screen => screen.platform !== 'android');
  const scenarioIds = new Set(scenarios.map(scenario => String(scenario.case_id || '').trim()).filter(Boolean));
  const missingScenarioRefs = screens.flatMap(screen =>
    screen.scenario_ids.split(';').map(id => id.trim()).filter(Boolean)
      .filter(id => !scenarioIds.has(id)).map(id => screen.source_path + ' -> ' + id)
  );
  if (missingScenarioRefs.length) {
    throw new Error('UI screen register refers to unknown workflow scenarios: ' + missingScenarioRefs.join(', '));
  }
  const registeredSources = new Set(registered.map(screen => screen.source_path));
  const sourcePaths = new Set(routes.map(route => route.file));
  const missingSources = routes.filter(route => !registeredSources.has(route.file)).map(route => route.file);
  const staleSources = [...registeredSources].filter(source => !sourcePaths.has(source));

  if (missingSources.length || staleSources.length) {
    throw new Error([
      'Current UI screen register does not match the current source route inventory.',
      missingSources.length ? 'Unregistered source routes: ' + missingSources.join(', ') : '',
      staleSources.length ? 'Stale/non-primary registered paths: ' + staleSources.join(', ') : '',
      'Regenerate/review docs/qa/ui-screen-inventory.csv before accepting this E2E run.'
    ].filter(Boolean).join('\n'));
  }

  return {
    sourceSha: process.env.GITHUB_SHA || process.env.QE_E2E_SOURCE_SHA || 'local-unpinned',
    generatedAt: new Date().toISOString(),
    registerSha: createHash('sha256').update(inventoryText).update('\n').update(await readFile(scenarioPath)).digest('hex'),
    screens,
    routes,
    summary: {
      registeredWebPages: registered.length,
      sourceWebPages: routes.length,
      registeredNativeRoutes: screens.filter(screen => screen.platform === 'android').length,
      scenarios: scenarios.length
    }
  };
}

export function expandRoute(route: string): string {
  return route
    .replace(/\[\[\.{3}([^\]]+)\]\]/g, 'qe-e2e-missing-path')
    .replace(/\[\.{3}([^\]]+)\]/g, 'qe-e2e-missing-path')
    .replace(/\[([^\]]+)\]/g, (_match, parameter: string) =>
      parameter === 'institutionCode' ? 'QAT-UNKNOWN' :
      parameter === 'artifactId' ? 'qe-e2e-missing-artifact' :
      'qe-e2e-missing-' + parameter.replace(/[^a-z0-9-]/gi, '-')
    );
}
export function routeApp(sourcePath: string): 'web' | 'admin' | 'people' {
  if (sourcePath.startsWith('apps/web/')) return 'web';
  if (sourcePath.startsWith('apps/admin/')) return 'admin';
  if (sourcePath.startsWith('apps/people/')) return 'people';
  throw new Error('Unknown web application source path: ' + sourcePath);
}

export function appBaseUrl(app: 'web' | 'admin' | 'people'): string {
  const key = app === 'web' ? 'QE_E2E_WEB_URL' : app === 'admin' ? 'QE_E2E_ADMIN_URL' : 'QE_E2E_HRMS_URL';
  const fallback = app === 'web' ? 'http://localhost:3000' :
    app === 'admin' ? 'http://localhost:3001' : 'http://localhost:3002';
  return (process.env[key] || fallback).replace(/\/$/, '');
}
