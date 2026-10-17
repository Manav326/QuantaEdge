#!/usr/bin/env node
/**
 * Build a machine-readable inventory of routable UI pages and route handlers
 * in QuantaEdge's three Next.js App Router applications.
 *
 * The report is deliberately an inventory, not a claim that a route is fully
 * localized. Route-level translation certification is a separate audit step.
 */
import { readdir, writeFile, mkdir } from 'node:fs/promises';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const APP_ROUTE_ROOTS = [
  { app: 'web', appRoot: 'apps/web/app' },
  { app: 'admin', appRoot: 'apps/admin/app' },
  { app: 'people', appRoot: 'apps/people/app' },
];

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PAGE_FILES = new Set(['page.ts', 'page.tsx', 'page.js', 'page.jsx', 'page.mdx']);
const HANDLER_FILES = new Set(['route.ts', 'route.tsx', 'route.js', 'route.jsx']);

function isRouteGroup(segment) {
  return /^\([^/]+\)$/.test(segment);
}

function applyInterceptingSegment(segment, routeSegments) {
  let rest = segment;
  const prefixes = [];
  while (true) {
    const prefix = ['(...)', '(..)', '(.)'].find((candidate) => rest.startsWith(candidate));
    if (!prefix) break;
    prefixes.push(prefix);
    rest = rest.slice(prefix.length);
  }
  if (!prefixes.length || !rest) return false;

  for (const prefix of prefixes) {
    if (prefix === '(...)') routeSegments.length = 0;
    else if (prefix === '(..)') routeSegments.pop();
    // (.) intentionally keeps the current parent route.
  }
  routeSegments.push(rest);
  return true;
}

/**
 * Convert a path relative to an app/ directory into its public route identity.
 * Returns null for files below Next.js private folders (which start with an underscore).
 */
export function routeInfoFromAppRelativeFile(relativeFile, kind = 'page') {
  if (kind !== 'page' && kind !== 'route-handler') {
    throw new TypeError('Unsupported route kind: ' + kind);
  }

  const normalized = relativeFile.split(sep).join('/').replace(/^\.\//, '');
  const pieces = normalized.split('/').filter(Boolean);
  const filename = pieces.pop();
  const expectedNames = kind === 'page' ? PAGE_FILES : HANDLER_FILES;
  if (!expectedNames.has(filename)) return null;

  const routeSegments = [];
  const routeGroups = [];
  const parallelSlots = [];
  const interceptors = [];

  for (const segment of pieces) {
    if (segment.startsWith('_') || segment.startsWith('.')) return null;
    if (segment.startsWith('@')) {
      parallelSlots.push(segment.slice(1));
      continue;
    }
    if (isRouteGroup(segment)) {
      routeGroups.push(segment.slice(1, -1));
      continue;
    }
    if (applyInterceptingSegment(segment, routeSegments)) {
      interceptors.push(segment);
      continue;
    }
    routeSegments.push(segment);
  }

  return {
    route: routeSegments.length ? '/' + routeSegments.join('/') : '/',
    routeGroups,
    parallelSlots,
    interceptors,
    primaryRoute: parallelSlots.length === 0 && interceptors.length === 0,
  };
}

async function scanApp(app, appRoot, repoRoot) {
  const absoluteRoot = join(repoRoot, appRoot);
  const entries = [];

  async function walk(directory, relativeParts) {
    const children = await readdir(directory, { withFileTypes: true });
    children.sort((a, b) => a.name.localeCompare(b.name, 'en'));

    for (const child of children) {
      if (child.name.startsWith('.') || child.name.startsWith('_')) continue;
      const absolutePath = join(directory, child.name);
      if (child.isDirectory()) {
        await walk(absolutePath, [...relativeParts, child.name]);
        continue;
      }
      if (!child.isFile()) continue;

      let kind;
      if (PAGE_FILES.has(child.name)) kind = 'page';
      else if (HANDLER_FILES.has(child.name)) kind = 'route-handler';
      else continue;

      const relativeFile = [...relativeParts, child.name].join('/');
      const routeInfo = routeInfoFromAppRelativeFile(relativeFile, kind);
      if (!routeInfo) continue;
      entries.push({
        app,
        kind,
        route: routeInfo.route,
        file: [appRoot, ...relativeParts, child.name].join('/'),
        routeGroups: routeInfo.routeGroups,
        parallelSlots: routeInfo.parallelSlots,
        interceptors: routeInfo.interceptors,
        primaryRoute: routeInfo.primaryRoute,
      });
    }
  }

  await walk(absoluteRoot, []);
  entries.sort((a, b) =>
    a.route.localeCompare(b.route, 'en') ||
    a.kind.localeCompare(b.kind, 'en') ||
    a.file.localeCompare(b.file, 'en'),
  );
  return { app, appRoot, entries };
}

export async function buildRouteInventory(repoRoot = REPO_ROOT) {
  const apps = [];
  for (const { app, appRoot } of APP_ROUTE_ROOTS) {
    apps.push(await scanApp(app, appRoot, repoRoot));
  }

  const allEntries = apps.flatMap((item) => item.entries);
  const primaryPages = allEntries.filter((item) => item.kind === 'page' && item.primaryRoute);
  const grouped = new Map();
  for (const page of primaryPages) {
    const key = page.app + '\0' + page.route;
    const files = grouped.get(key) ?? [];
    files.push(page.file);
    grouped.set(key, files);
  }
  const duplicatePrimaryRoutes = [...grouped.entries()]
    .filter(([, files]) => files.length > 1)
    .map(([key, files]) => {
      const [app, route] = key.split('\0');
      return { app, route, files: files.sort() };
    })
    .sort((a, b) => a.app.localeCompare(b.app, 'en') || a.route.localeCompare(b.route, 'en'));

  const pageCount = allEntries.filter((item) => item.kind === 'page').length;
  const routeHandlerCount = allEntries.filter((item) => item.kind === 'route-handler').length;
  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    note: 'Inventory only; presence in this file does not certify localization coverage.',
    apps: apps.map(({ app, appRoot, entries }) => ({
      app,
      appRoot,
      pageCount: entries.filter((item) => item.kind === 'page').length,
      routeHandlerCount: entries.filter((item) => item.kind === 'route-handler').length,
      routes: entries,
    })),
    summary: {
      appCount: apps.length,
      pageCount,
      routeHandlerCount,
      primaryPageCount: primaryPages.length,
      duplicatePrimaryRoutes,
    },
  };
}

async function main(argv = process.argv.slice(2)) {
  const outputIndex = argv.indexOf('--output');
  const outputPath = outputIndex >= 0 ? argv[outputIndex + 1] : null;
  if (outputIndex >= 0 && (!outputPath || outputPath.startsWith('--'))) {
    throw new Error('--output requires a file path');
  }
  const report = await buildRouteInventory();
  const serialized = JSON.stringify(report, null, 2) + '\n';

  if (outputPath) {
    const destination = resolve(outputPath);
    await mkdir(dirname(destination), { recursive: true });
    await writeFile(destination, serialized, 'utf8');
    console.log('UI route inventory generated: ' + report.summary.pageCount + ' pages, ' +
      report.summary.routeHandlerCount + ' route handlers across ' + report.summary.appCount + ' apps -> ' + destination);
  } else {
    process.stdout.write(serialized);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error('UI route inventory failed: ' + error.message);
    process.exitCode = 1;
  });
}
