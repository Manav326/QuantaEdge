import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { buildRouteInventory, routeInfoFromAppRelativeFile } from './inventory_ui_routes.mjs';

test('root page maps to /', () => {
  assert.equal(routeInfoFromAppRelativeFile('page.tsx').route, '/');
});

test('normal nested and dynamic routes retain URL segments', () => {
  assert.deepEqual(routeInfoFromAppRelativeFile('student/[studentId]/settings/page.tsx').routeGroups, []);
  assert.equal(routeInfoFromAppRelativeFile('student/[studentId]/settings/page.tsx').route, '/student/[studentId]/settings');
});

test('route groups do not leak into the public route', () => {
  const route = routeInfoFromAppRelativeFile('(workspace)/content/(review)/page.tsx');
  assert.equal(route.route, '/content');
  assert.deepEqual(route.routeGroups, ['workspace', 'review']);
});

test('parallel route slots are tracked but excluded from the URL', () => {
  const route = routeInfoFromAppRelativeFile('student/@modal/(.)recordings/[id]/page.tsx');
  assert.equal(route.route, '/student/recordings/[id]');
  assert.deepEqual(route.parallelSlots, ['modal']);
  assert.deepEqual(route.interceptors, ['(.)recordings']);
  assert.equal(route.primaryRoute, false);
});

test('one-level intercepting routes resolve against the parent URL', () => {
  const route = routeInfoFromAppRelativeFile('feed/(..)photo/[id]/page.tsx');
  assert.equal(route.route, '/photo/[id]');
  assert.deepEqual(route.interceptors, ['(..)photo']);
});

test('root intercepting routes resolve from the application root', () => {
  const route = routeInfoFromAppRelativeFile('feed/(...)photo/[id]/page.tsx');
  assert.equal(route.route, '/photo/[id]');
});

test('private folders and non-route files are ignored', () => {
  assert.equal(routeInfoFromAppRelativeFile('_components/page.tsx'), null);
  assert.equal(routeInfoFromAppRelativeFile('components/Button.tsx'), null);
  assert.equal(routeInfoFromAppRelativeFile('api/route.ts', 'route-handler').route, '/api');
});

test('unknown route kinds fail explicitly', () => {
  assert.throws(() => routeInfoFromAppRelativeFile('page.tsx', 'layout'), /Unsupported route kind/);
});

test('full scan inventories page routes and route handlers across all app roots', async () => {
  const root = await mkdtemp(join(tmpdir(), 'qe-route-inventory-'));
  const files = [
    ['apps/web/app/page.tsx', 'page'],
    ['apps/web/app/student/[studentId]/page.tsx', 'page'],
    ['apps/web/app/_components/page.tsx', 'private page'],
    ['apps/admin/app/(workspace)/content/page.tsx', 'page'],
    ['apps/people/app/api/employees/route.ts', 'handler'],
  ];
  try {
    for (const [file, body] of files) {
      const absolute = join(root, file);
      await mkdir(join(absolute, '..'), { recursive: true });
      await writeFile(absolute, body, 'utf8');
    }
    for (const app of ['web', 'admin', 'people']) {
      await mkdir(join(root, 'apps', app, 'app'), { recursive: true });
    }

    const report = await buildRouteInventory(root);
    assert.equal(report.summary.appCount, 3);
    assert.equal(report.summary.pageCount, 3);
    assert.equal(report.summary.routeHandlerCount, 1);
    assert.ok(report.apps.find((app) => app.app === 'web').routes.some(
      (route) => route.route === '/student/[studentId]' && route.file.endsWith('page.tsx'),
    ));
    assert.ok(report.apps.find((app) => app.app === 'admin').routes.some(
      (route) => route.route === '/content' && route.routeGroups.includes('workspace'),
    ));
    assert.ok(report.apps.find((app) => app.app === 'people').routes.some(
      (route) => route.kind === 'route-handler' && route.route === '/api/employees',
    ));
    assert.equal(report.apps.find((app) => app.app === 'web').routes.some(
      (route) => route.file.includes('_components'),
    ), false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
