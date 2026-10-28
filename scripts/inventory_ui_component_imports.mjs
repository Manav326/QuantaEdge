#!/usr/bin/env node
/**
 * Inventory the transitive local UI module graph for every Next.js page route.
 *
 * This is source tracing, not proof a conditional component rendered or that
 * runtime/visual behavior passed. Literal dynamic imports are followed;
 * computed import paths remain outside this static inventory.
 */
import { existsSync, readFileSync, statSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildRouteInventory } from './inventory_ui_routes.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE_EXTENSIONS = ['.tsx', '.ts', '.jsx', '.js', '.mjs', '.cjs', '.css', '.scss', '.json'];
const RESOLUTION_EXTENSIONS = [...SOURCE_EXTENSIONS, '.svg', '.png', '.jpg', '.jpeg', '.webp', '.avif', '.woff', '.woff2'];
const NEXT_SEGMENT_FILES = ['layout', 'template', 'loading', 'error', 'not-found', 'default', 'global-error'];

function normalizeFile(path, repoRoot) {
  return relative(repoRoot, path).split(sep).join('/');
}

function isFile(path) {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
}

/**
 * Finds literal local imports while ignoring comment text and ordinary strings.
 * The token walk avoids mistaking an example in a comment or rendered JSX copy
 * for a real source dependency.
 */
export function localImportSpecifiers(source) {
  const found = new Set();
  const addIfLocal = value => {
    if (value && (value.startsWith('.') || value.startsWith('@/'))) found.add(value);
  };

  for (let index = 0; index < source.length;) {
    const current = source[index];
    const next = source[index + 1];

    if (current === '/' && next === '/') {
      index += 2;
      while (index < source.length && source[index] !== '\n') index += 1;
      continue;
    }
    if (current === '/' && next === '*') {
      index += 2;
      while (index < source.length && !(source[index] === '*' && source[index + 1] === '/')) index += 1;
      index = Math.min(source.length, index + 2);
      continue;
    }
    if (current === "'" || current === '"' || current === '`') {
      const quote = current;
      index += 1;
      while (index < source.length) {
        if (source[index] === '\\') { index += 2; continue; }
        if (source[index] === quote) { index += 1; break; }
        index += 1;
      }
      continue;
    }

    if (/[A-Za-z_$]/.test(current)) {
      const start = index;
      index += 1;
      while (index < source.length && /[A-Za-z0-9_$]/.test(source[index])) index += 1;
      const token = source.slice(start, index);
      if (token === 'import' || token === 'export' || token === 'require') {
        const tail = source.slice(start, Math.min(source.length, start + 8000));
        let match = null;
        if (token === 'import') {
          match = tail.match(/^import\s*\(\s*(['"])([^'"]+)\1\s*\)/);
          if (!match) match = tail.match(/^import\s*(['"])([^'"]+)\1/);
          if (!match) match = tail.match(/^import\b(?:(?!;)[\s\S]){0,2000}?\bfrom\s*(['"])([^'"]+)\1/);
        } else if (token === 'export') {
          match = tail.match(/^export\s+(?:type\s+)?(?:\*|\{[\s\S]{0,3000}?\})(?:\s+as\s+[A-Za-z_$][\\w$]*)?\s+from\s*(['"])([^'"]+)\1/);
        } else {
          match = tail.match(/^require\s*\(\s*(['"])([^'"]+)\1\s*\)/);
        }
        if (match) addIfLocal(match[2]);
      }
      continue;
    }

    // CSS @import is not an identifier by itself, so recognize it separately.
    if (current === '@' && source.slice(index, index + 7) === '@import') {
      const tail = source.slice(index, Math.min(source.length, index + 1000));
      const match = tail.match(/^@import\s*(['"])([^'"]+)\1/);
      if (match) addIfLocal(match[2]);
    }
    index += 1;
  }
  return [...found].sort();
}

function aliasCandidates(specifier, appRoot, repoRoot) {
  const packageRoot = appRoot.endsWith('/app') ? appRoot.slice(0, -4) : appRoot;
  const appDirectory = resolve(repoRoot, packageRoot);
  const tsconfigPath = join(appDirectory, 'tsconfig.json');
  const candidates = [];
  if (existsSync(tsconfigPath) && isFile(tsconfigPath)) {
    try {
      const config = JSON.parse(readFileSync(tsconfigPath, 'utf8'));
      const paths = config.compilerOptions?.paths ?? {};
      const baseUrl = resolve(appDirectory, config.compilerOptions?.baseUrl ?? '.');
      for (const [pattern, targets] of Object.entries(paths)) {
        const star = pattern.indexOf('*');
        const prefix = star < 0 ? pattern : pattern.slice(0, star);
        const suffix = star < 0 ? '' : pattern.slice(star + 1);
        if (!specifier.startsWith(prefix) || !specifier.endsWith(suffix)) continue;
        const matched = specifier.slice(prefix.length, specifier.length - suffix.length || undefined);
        for (const target of targets) {
          const expanded = String(target).replace('*', matched);
          candidates.push(resolve(baseUrl, expanded));
        }
      }
    } catch {
      // An unreadable tsconfig is reported by existing app checks; fallback roots
      // still let the inventory explain whether the import could be resolved.
    }
  }
  // Generic fallbacks support legacy aliases even where tsconfig does not declare one.
  candidates.push(resolve(repoRoot, appRoot, specifier.slice(2)));
  candidates.push(resolve(repoRoot, packageRoot, specifier.slice(2)));
  return [...new Set(candidates)];
}

function resolveLocalImport(specifier, fromFile, appRoot, repoRoot) {
  const bases = specifier.startsWith('@/') ? aliasCandidates(specifier, appRoot, repoRoot)
    : specifier.startsWith('.') ? [resolve(dirname(fromFile), specifier)] : [];
  if (bases.length === 0) return { kind: 'external', path: null };
  const candidates = [];
  for (const base of bases) {
    if (extname(base)) candidates.push(base);
    else {
      candidates.push(base);
      for (const extension of RESOLUTION_EXTENSIONS) candidates.push(base + extension);
      for (const extension of RESOLUTION_EXTENSIONS) candidates.push(join(base, 'index' + extension));
    }
  }
  for (const candidate of candidates) {
    if (!isFile(candidate)) continue;
    return {
      kind: SOURCE_EXTENSIONS.includes(extname(candidate)) ? 'source' : 'asset',
      path: candidate,
    };
  }
  return { kind: 'unresolved', path: bases[0] };
}

function ancestorRouteFiles(pageFile, appRoot, repoRoot) {
  const pageDirectory = dirname(pageFile);
  const appDirectory = resolve(repoRoot, appRoot);
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
        if (isFile(candidate)) files.push(candidate);
      }
    }
  }
  return [...new Set(files)];
}

function isUiComponentSource(path) {
  const extension = extname(path);
  if (!['.tsx', '.jsx'].includes(extension)) return false;
  const name = path.split(sep).pop() ?? '';
  return !/^(page|layout|template|loading|error|not-found|default|global-error)\\.(tsx|jsx)$/.test(name);
}

export async function buildUiComponentImportInventory(repoRoot = REPO_ROOT) {
  repoRoot = resolve(repoRoot);
  const routeInventory = await buildRouteInventory(repoRoot);
  const moduleCache = new Map();

  async function inspectModule(file, appRoot) {
    const key = normalizeFile(file, repoRoot);
    if (moduleCache.has(key)) return moduleCache.get(key);
    if (!SOURCE_EXTENSIONS.includes(extname(file))) {
      const item = { path: key, type: 'asset', imports: [], unresolvedImports: [] };
      moduleCache.set(key, item);
      return item;
    }

    let source;
    try {
      source = readFileSync(file, 'utf8');
    } catch (error) {
      const item = { path: key, type: 'unreadable', imports: [], unresolvedImports: [{ from: key, reason: String(error.message) }] };
      moduleCache.set(key, item);
      return item;
    }

    const imports = [];
    const unresolvedImports = [];
    for (const specifier of localImportSpecifiers(source)) {
      const resolution = resolveLocalImport(specifier, file, appRoot, repoRoot);
      if (resolution.kind === 'external') continue;
      if (resolution.kind === 'unresolved') {
        unresolvedImports.push({ specifier, from: key, expected: normalizeFile(resolution.path, repoRoot) });
        continue;
      }
      imports.push({
        specifier,
        path: normalizeFile(resolution.path, repoRoot),
        type: resolution.kind,
      });
    }
    const item = {
      path: key,
      type: isUiComponentSource(file) ? 'ui-module-candidate'
        : ['.css', '.scss'].includes(extname(file)) ? 'stylesheet' : 'source-module',
      imports: imports.sort((a, b) => a.path.localeCompare(b.path)),
      unresolvedImports,
    };
    moduleCache.set(key, item);
    return item;
  }

  const routeGraphs = [];
  const appSummary = [];
  for (const app of routeInventory.apps) {
    const pages = app.routes.filter(entry => entry.kind === 'page');
    const uniqueModules = new Set();
    let unresolvedLocalImports = 0;
    for (const entry of pages) {
      const routeRoots = [...ancestorRouteFiles(entry.file, app.appRoot, repoRoot), resolve(repoRoot, entry.file)];
      const visited = new Set();
      const unresolved = [];
      const queue = [...routeRoots];
      while (queue.length) {
        const current = queue.shift();
        const key = normalizeFile(current, repoRoot);
        if (visited.has(key)) continue;
        visited.add(key);
        const module = await inspectModule(current, app.appRoot);
        unresolved.push(...module.unresolvedImports);
        uniqueModules.add(key);
        for (const imported of module.imports) {
          if (imported.type === 'source' && !visited.has(imported.path)) queue.push(resolve(repoRoot, imported.path));
          else if (imported.type === 'asset') uniqueModules.add(imported.path);
        }
      }
      unresolvedLocalImports += unresolved.length;
      routeGraphs.push({
        app: app.app,
        route: entry.route,
        sourceFile: entry.file,
        routeGroups: entry.routeGroups,
        parallelSlots: entry.parallelSlots,
        interceptors: entry.interceptors,
        includedRouteConventions: routeRoots.slice(0, -1).map(file => normalizeFile(file, repoRoot)),
        moduleCount: visited.size,
        sourceModuleCount: [...visited].filter(path => SOURCE_EXTENSIONS.includes(extname(path))).length,
        uiModuleCandidates: [...visited].filter(path => isUiComponentSource(resolve(repoRoot, path))).sort(),
        stylesheetModules: [...visited].filter(path => ['.css', '.scss'].includes(extname(path))).sort(),
        unresolvedLocalImports: unresolved,
        modules: [...visited].sort(),
      });
    }
    appSummary.push({
      app: app.app,
      pageRoutes: pages.length,
      tracedRoutes: pages.length,
      uniqueSourceAndAssetModules: uniqueModules.size,
      unresolvedLocalImportsAcrossRouteWalks: unresolvedLocalImports,
    });
  }

  const unresolvedByPath = new Map();
  for (const route of routeGraphs) {
    for (const issue of route.unresolvedLocalImports) {
      const key = [issue.from, issue.specifier ?? '', issue.expected ?? issue.reason ?? ''].join('::');
      if (!unresolvedByPath.has(key)) unresolvedByPath.set(key, { ...issue, routes: [] });
      unresolvedByPath.get(key).routes.push(route.app + ':' + route.route);
    }
  }
  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    note: 'Static local-import tracing only. Conditional rendering and browser/device visual states still require runtime evidence.',
    parser: 'Literal static imports, exports-from, literal dynamic imports, literal require calls, CSS @import; relative and configured @/ alias paths.',
    summaries: appSummary,
    summary: {
      appCount: appSummary.length,
      pageRouteCount: routeGraphs.length,
      routeGraphsWithUnresolvedLocalImports: routeGraphs.filter(route => route.unresolvedLocalImports.length > 0).length,
      unresolvedLocalImportReferences: unresolvedByPath.size,
      uniqueResolvedSourceAndAssetPaths: moduleCache.size,
    },
    unresolvedLocalImports: [...unresolvedByPath.values()].sort((a, b) => a.from.localeCompare(b.from) || (a.specifier ?? '').localeCompare(b.specifier ?? '')),
    routes: routeGraphs,
  };
}

async function main(argv = process.argv.slice(2)) {
  const outputIndex = argv.indexOf('--output');
  const outputPath = outputIndex >= 0 ? argv[outputIndex + 1] : null;
  if (outputIndex >= 0 && (!outputPath || outputPath.startsWith('--'))) throw new Error('--output requires a file path');
  const report = await buildUiComponentImportInventory(REPO_ROOT);
  const serialized = JSON.stringify(report, null, 2) + '\n';
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
