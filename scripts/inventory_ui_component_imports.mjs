#!/usr/bin/env node
/**
 * Inventory the transitive, local UI module graph for every Next.js page route.
 *
 * This is source tracing, not proof that a conditional component rendered or
 * that any visual/runtime behavior passed. Dynamic imports with literal paths
 * are followed; computed import paths are surfaced only by manual review.
 */
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, readdir, writeFile } from 'node:fs/promises';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildRouteInventory } from './inventory_ui_routes.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE_EXTENSIONS = ['.tsx', '.ts', '.jsx', '.js', '.mjs', '.cjs', '.css', '.scss', '.json'];
const RESOLUTION_EXTENSIONS = [...SOURCE_EXTENSIONS, '.svg', '.png', '.jpg', '.jpeg', '.webp', '.avif', '.woff', '.woff2'];
const NEXT_SEGMENT_FILES = ['layout', 'template', 'loading', 'error', 'not-found', 'default', 'global-error'];
const IGNORED_DIRS = new Set(['node_modules', '.next', '.git', 'build', 'dist', 'coverage']);

function normalizeFile(path) {
  return relative(REPO_ROOT, path).split(sep).join('/');
}

function localImportSpecifiers(source, path) {
  const found = new Set();
  const patterns = [
    /\\b(?:import|export)\\s+(?:type\\s+)?(?:[^;"']|\\r?\\n)*?\\bfrom\\s*(['"])([^'"]+)\\1/g,
    /\\bimport\\s*(['"])([^'"]+)\\1/g,
    /\\bimport\\s*\\(\\s*(['"])([^'"]+)\\1\\s*\\)/g,
    /\\brequire\\s*\\(\\s*(['"])([^'"]+)\\1\\s*\\)/g,
    /@import\\s*(['"])([^'"]+)\\1/g,
  ];
  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      const specifier = match[2];
      if (specifier?.startsWith('.') || specifier?.startsWith('@/')) found.add(specifier);
    }
  }
  return [...found].sort();
}

function resolveLocalImport(specifier, fromFile, appRoot) {
  let base;
  if (specifier.startsWith('@/')) base = join(REPO_ROOT, appRoot, specifier.slice(2));
  else if (specifier.startsWith('.')) base = resolve(dirname(fromFile), specifier);
  else return { kind: 'external', path: null };

  const candidates = [];
  if (extname(base)) candidates.push(base);
  else {
    candidates.push(base);
    for (const extension of RESOLUTION_EXTENSIONS) candidates.push(base + extension);
    for (const extension of RESOLUTION_EXTENSIONS) candidates.push(join(base, 'index' + extension));
  }
  for (const candidate of candidates) {
    if (!existsSync(candidate)) continue;
    try {
      if (readFileSync(candidate, 'utf8') !== undefined) {
        return {
          kind: SOURCE_EXTENSIONS.includes(extname(candidate)) ? 'source' : 'asset',
          path: candidate,
        };
      }
    } catch {
      // Binary assets are resolved by existence and never opened as source.
      return { kind: 'asset', path: candidate };
    }
  }
  return { kind: 'unresolved', path: base };
}

function ancestorRouteFiles(pageFile, appRoot) {
  const pageDirectory = dirname(pageFile);
  const appDirectory = resolve(REPO_ROOT, appRoot);
  const relativeDirectory = relative(appDirectory, pageDirectory);
  const directories = [appDirectory];
  if (relativeDirectory && !relativeDirectory.startsWith('..')) {
    let current = appDirectory;
    for (const segment of relativeDirectory.split(sep).filter(Boolean)) {
      current = join(current, segment);
      directories.push(current);
    }
  }
  const files = [];
  for (const directory of directories) {
    for (const stem of NEXT_SEGMENT_FILES) {
      for (const extension of SOURCE_EXTENSIONS.slice(0, 4)) {
        const candidate = join(directory, stem + extension);
        if (existsSync(candidate)) files.push(candidate);
      }
    }
  }
  return [...new Set(files)];
}

async function readSource(path) {
  return readFileSync(path, 'utf8');
}

function isUiComponentSource(path) {
  const extension = extname(path);
  if (!['.tsx', '.jsx'].includes(extension)) return false;
  const name = path.split(sep).pop() ?? '';
  return !/^(page|layout|template|loading|error|not-found|default|global-error)\\.(tsx|jsx)$/.test(name);
}

export async function buildUiComponentImportInventory(repoRoot = REPO_ROOT) {
  if (resolve(repoRoot) !== REPO_ROOT) {
    throw new Error('The inventory currently resolves modules relative to the checked-out repository root.');
  }
  const routeInventory = await buildRouteInventory(repoRoot);
  const moduleCache = new Map();
  async function inspectModule(file, appRoot) {
    const key = normalizeFile(file);
    if (moduleCache.has(key)) return moduleCache.get(key);
    const isAsset = !SOURCE_EXTENSIONS.includes(extname(file));
    if (isAsset) {
      const item = { path: key, type: 'asset', imports: [], unresolvedImports: [] };
      moduleCache.set(key, item);
      return item;
    }
    let source;
    try {
      source = await readSource(file);
    } catch (error) {
      const item = { path: key, type: 'unreadable', imports: [], unresolvedImports: [String(error.message)] };
      moduleCache.set(key, item);
      return item;
    }

    const importPaths = localImportSpecifiers(source, file);
    const imports = [];
    const unresolvedImports = [];
    for (const specifier of importPaths) {
      const resolution = resolveLocalImport(specifier, file, appRoot);
      if (resolution.kind === 'external') continue;
      if (resolution.kind === 'unresolved') {
        unresolvedImports.push({ specifier, from: key, expected: normalizeFile(resolution.path) });
        continue;
      }
      const targetPath = normalizeFile(resolution.path);
      imports.push({ specifier, path: targetPath, type: resolution.kind });
    }
    const item = {
      path: key,
      type: isUiComponentSource(file) ? 'ui-module-candidate' : extname(file) === '.css' || extname(file) === '.scss' ? 'stylesheet' : 'source-module',
      imports: imports.sort((a, b) => a.path.localeCompare(b.path)),
      unresolvedImports,
    };
    moduleCache.set(key, item);
    return item;
  }

  const routeGraphs = [];
  const appSummary = new Map();
  for (const app of routeInventory.apps) {
    const pages = app.routes.filter(entry => entry.kind === 'page');
    const summary = { app: app.app, pageRoutes: pages.length, tracedRoutes: 0, uniqueModules: new Set(), unresolvedLocalImports: 0 };
    for (const entry of pages) {
      const appRoot = app.appRoot;
      const routeRoots = [...ancestorRouteFiles(entry.file, appRoot), resolve(REPO_ROOT, entry.file)];
      const visited = new Set();
      const unresolved = [];
      const queue = routeRoots;
      while (queue.length) {
        const current = queue.shift();
        const key = normalizeFile(current);
        if (visited.has(key)) continue;
        visited.add(key);
        const module = await inspectModule(current, appRoot);
        unresolved.push(...module.unresolvedImports);
        summary.uniqueModules.add(module.path);
        for (const imported of module.imports) {
          const absolute = resolve(REPO_ROOT, imported.path);
          if (!visited.has(imported.path) && imported.type === 'source') queue.push(absolute);
          else if (imported.type === 'asset') summary.uniqueModules.add(imported.path);
        }
      }
      summary.tracedRoutes += 1;
      summary.unresolvedLocalImports += unresolved.length;
      routeGraphs.push({
        app: app.app,
        route: entry.route,
        sourceFile: entry.file,
        routeGroups: entry.routeGroups,
        parallelSlots: entry.parallelSlots,
        interceptors: entry.interceptors,
        includedRouteConventions: routeRoots.slice(0, -1).map(normalizeFile),
        moduleCount: visited.size,
        sourceModuleCount: [...visited].filter(path => SOURCE_EXTENSIONS.includes(extname(path))).length,
        uiModuleCandidates: [...visited].filter(path => isUiComponentSource(resolve(REPO_ROOT, path))).sort(),
        stylesheetModules: [...visited].filter(path => ['.css', '.scss'].includes(extname(path))).sort(),
        unresolvedLocalImports: unresolved,
        modules: [...visited].sort(),
      });
    }
    appSummary.set(app.app, {
      app: app.app,
      pageRoutes: summary.pageRoutes,
      tracedRoutes: summary.tracedRoutes,
      uniqueSourceAndAssetModules: summary.uniqueModules.size,
      unresolvedLocalImportsAcrossRouteWalks: summary.unresolvedLocalImports,
    });
  }

  const unresolvedByPath = new Map();
  for (const route of routeGraphs) {
    for (const issue of route.unresolvedLocalImports) {
      const key = [issue.from, issue.specifier, issue.expected].join('::');
      if (!unresolvedByPath.has(key)) unresolvedByPath.set(key, { ...issue, routes: [] });
      unresolvedByPath.get(key).routes.push(route.app + ':' + route.route);
    }
  }
  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    note: 'Static local-import tracing only. It does not prove that conditional components rendered or that UI runtime/visual states passed.',
    parser: 'Literal static imports, exports-from, literal dynamic imports, literal require calls, CSS @import; local relative and @/ alias paths.',
    summaries: [...appSummary.values()],
    summary: {
      appCount: appSummary.size,
      pageRouteCount: routeGraphs.length,
      routeGraphsWithUnresolvedLocalImports: routeGraphs.filter(route => route.unresolvedLocalImports.length > 0).length,
      unresolvedLocalImportReferences: [...unresolvedByPath.values()].length,
      uniqueResolvedSourceAndAssetPaths: moduleCache.size,
    },
    unresolvedLocalImports: [...unresolvedByPath.values()].sort((a, b) => a.from.localeCompare(b.from) || a.specifier.localeCompare(b.specifier)),
    routes: routeGraphs,
  };
}

async function main(argv = process.argv.slice(2)) {
  const outputIndex = argv.indexOf('--output');
  const outputPath = outputIndex >= 0 ? argv[outputIndex + 1] : null;
  if (outputIndex >= 0 && (!outputPath || outputPath.startsWith('--'))) throw new Error('--output requires a file path');
  const report = await buildUiComponentImportInventory();
  const serialized = JSON.stringify(report, null, 2) + '\\n';
  if (outputPath) {
    const destination = resolve(outputPath);
    await mkdir(dirname(destination), { recursive: true });
    await writeFile(destination, serialized, 'utf8');
    console.log('UI component import inventory generated: ' + report.summary.pageRouteCount +
      ' page routes, ' + report.summary.uniqueResolvedSourceAndAssetPaths + ' resolved local paths, ' +
      report.summary.unresolvedLocalImportReferences + ' unresolved local import references -> ' + destination);
  } else process.stdout.write(serialized);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error('UI component import inventory failed: ' + error.message);
    process.exitCode = 1;
  });
}
