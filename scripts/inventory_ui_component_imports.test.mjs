import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { buildUiComponentImportInventory, localImportSpecifiers } from './inventory_ui_component_imports.mjs';

test('literal import scanner follows source imports and ignores comments and rendered copy', () => {
  const source = [
    "import Panel from './components/Panel';",
    "export { shared } from '../shared/index';",
    "const lazy = import('./lazy/Panel');",
    "const legacy = require('../legacy');",
    "import React from 'react';",
    "// import Ghost from './comment-only';",
    "/* export { missing } from './block-comment'; */",
    `const label = "import Fake from './rendered-copy'";`,
  ].join('\n');
  assert.deepEqual(localImportSpecifiers(source), [
    '../legacy', '../shared/index', './components/Panel', './lazy/Panel'
  ]);
});

test('route component graph contains layouts, transitive UI files, styles, and configured aliases', async () => {
  const fixture = await mkdtemp(join(tmpdir(), 'qe-ui-import-inventory-'));
  const files = [
    ['apps/web/app/layout.tsx', "import './layout.css'; import Shell from './components/Shell'; export default function RootLayout({children}) { return <Shell>{children}</Shell>; }"],
    ['apps/web/app/layout.css', 'body { color: black; }'],
    ['apps/web/app/components/Shell.tsx', "import Button from './Button'; export default function Shell({children}) { return <section><Button />{children}</section>; }"],
    ['apps/web/app/components/Button.tsx', "export default function Button() { return <button>go</button>; }"],
    ['apps/web/app/page.tsx', "import Panel from './components/Panel'; export default function Page() { return <Panel />; }"],
    ['apps/web/app/components/Panel.tsx', "import Button from './Button'; export default function Panel() { return <Button />; }"],
    ['apps/admin/app/page.tsx', 'export default function AdminPage() { return <main>Admin</main>; }'],
    ['apps/people/tsconfig.json', JSON.stringify({ compilerOptions: { paths: { '@/*': ['./*'] } } })],
    ['apps/people/app/page.tsx', "import PeopleShell from '@/components/PeopleShell'; export default function PeoplePage() { return <PeopleShell />; }"],
    ['apps/people/components/PeopleShell.tsx', 'export default function PeopleShell() { return <section>HRMS</section>; }'],
  ];
  try {
    for (const [path, body] of files) {
      const absolute = join(fixture, path);
      await mkdir(join(absolute, '..'), { recursive: true });
      await writeFile(absolute, body, 'utf8');
    }
    const report = await buildUiComponentImportInventory(fixture);
    assert.equal(report.summary.appCount, 3);
    assert.equal(report.summary.pageRouteCount, 3);
    const web = report.routes.find(route => route.app === 'web' && route.route === '/');
    assert.ok(web, 'web root route is present');
    for (const path of [
      'apps/web/app/layout.tsx',
      'apps/web/app/layout.css',
      'apps/web/app/components/Shell.tsx',
      'apps/web/app/components/Panel.tsx',
      'apps/web/app/components/Button.tsx',
    ]) assert.ok(web.modules.includes(path), 'web route includes ' + path);
    const people = report.routes.find(route => route.app === 'people' && route.route === '/');
    assert.ok(people.modules.includes('apps/people/components/PeopleShell.tsx'), 'configured @/ alias resolves from app tsconfig');
    assert.equal(report.summary.routeGraphsWithUnresolvedLocalImports, 0);
  } finally {
    await rm(fixture, { recursive: true, force: true });
  }
});

test('repository source graph covers all three Next apps and reports trace limitations', async () => {
  const report = await buildUiComponentImportInventory(resolve('.'));
  assert.equal(report.summary.appCount, 3);
  assert.ok(report.summary.pageRouteCount > 0);
  assert.ok(Array.isArray(report.unresolvedLocalImports));
  assert.match(report.note, /runtime|visual states/i);
  for (const route of report.routes) {
    assert.ok(route.sourceFile.startsWith('apps/'));
    assert.ok(route.moduleCount > 0);
    assert.ok(Array.isArray(route.modules));
    assert.ok(Array.isArray(route.uiModuleCandidates));
  }
});
