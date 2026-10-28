import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildSourceModuleAudit,
  componentAuditIdForSource,
  COMPONENT_AUDIT_COLUMNS
} from './build_ui_component_audit_register.mjs';

function route(id, source, components = '') {
  return {
    audit_id: id,
    platform: 'web',
    route_or_destination: id === 'UI-WEB-ROOT' ? '/' : '/student',
    source_path: source,
    scenario_ids: 'UX-001',
    component_audit_ids: components,
    overall_status: 'NOT_STARTED'
  };
}

function component(id, source, consumers, status = 'NOT_REVIEWED') {
  return {
    component_audit_id: id,
    platform: 'web',
    component_name: source.split('/').pop(),
    component_source_path: source,
    component_kind: 'navigation-component',
    consuming_audit_ids: consumers,
    source_review_status: status,
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
    overall_status: 'IN_PROGRESS',
    reviewer_notes: 'Curated evidence must be retained.'
  };
}

function graph() {
  return {
    summary: { appCount: 3, pageRouteCount: 2, routeGraphsWithUnresolvedLocalImports: 0 },
    routes: [
      {
        app: 'web',
        route: '/',
        sourceFile: 'apps/web/app/page.tsx',
        uiModuleCandidates: ['apps/web/app/components/Shared.tsx', 'apps/web/app/components/HomeCard.tsx'],
        stylesheetModules: ['apps/web/app/workspace.css']
      },
      {
        app: 'web',
        route: '/student',
        sourceFile: 'apps/web/app/student/page.tsx',
        uiModuleCandidates: ['apps/web/app/components/Shared.tsx', 'apps/web/app/components/StudentPanel.tsx'],
        stylesheetModules: ['apps/web/app/workspace.css']
      }
    ]
  };
}

test('source module inventory adds every discovered UI candidate and stylesheet, including consumers', () => {
  const sharedPath = 'apps/web/app/components/Shared.tsx';
  const shared = component('CMP-WEB-SHARED', sharedPath, 'UI-WEB-ROOT', 'SOURCE_REVIEWED');
  const result = buildSourceModuleAudit(
    graph(),
    [
      route('UI-WEB-ROOT', 'apps/web/app/page.tsx', 'CMP-WEB-ROOT-LAYOUT'),
      route('UI-WEB-STUDENT', 'apps/web/app/student/page.tsx')
    ],
    [shared, component('CMP-WEB-ROOT-LAYOUT', 'apps/web/app/layout.tsx', 'UI-WEB-ROOT;UI-WEB-STUDENT')]
  );

  assert.equal(result.summary.generatedPageRoutes, 2);
  assert.equal(result.summary.matchedRouteRows, 2);
  assert.equal(result.summary.uniqueUiModuleCandidates, 3);
  assert.equal(result.summary.uniqueStylesheetModules, 1);
  assert.equal(result.summary.curatedComponentRows, 2);
  assert.equal(result.summary.generatedModuleRows, 3);
  assert.equal(result.componentRows.length, 5);

  const sharedAfter = result.componentRows.find(row => row.component_audit_id === 'CMP-WEB-SHARED');
  assert.equal(sharedAfter.source_review_status, 'SOURCE_REVIEWED');
  assert.equal(sharedAfter.consuming_audit_ids, 'UI-WEB-ROOT;UI-WEB-STUDENT');

  const homeId = componentAuditIdForSource('apps/web/app/components/HomeCard.tsx');
  const studentId = componentAuditIdForSource('apps/web/app/components/StudentPanel.tsx');
  const cssId = componentAuditIdForSource('apps/web/app/workspace.css');
  const rootAfter = result.routeRows.find(row => row.audit_id === 'UI-WEB-ROOT');
  const studentAfter = result.routeRows.find(row => row.audit_id === 'UI-WEB-STUDENT');

  assert.deepEqual(new Set(rootAfter.component_audit_ids.split(';')), new Set(['CMP-WEB-ROOT-LAYOUT', 'CMP-WEB-SHARED', homeId, cssId]));
  assert.deepEqual(new Set(studentAfter.component_audit_ids.split(';')), new Set(['CMP-WEB-ROOT-LAYOUT', 'CMP-WEB-SHARED', studentId, cssId]));

  for (const id of [homeId, studentId, cssId]) {
    const row = result.componentRows.find(candidate => candidate.component_audit_id === id);
    assert.ok(row);
    assert.equal(row.source_review_status, 'NOT_REVIEWED');
    assert.equal(row.runtime_verification, 'NOT_EXECUTED');
    assert.equal(row.overall_status, 'NOT_STARTED');
  }
});

test('source component IDs are stable and route/component references reconcile', () => {
  const path = 'apps/admin/app/components/StablePanel.tsx';
  assert.equal(componentAuditIdForSource(path), componentAuditIdForSource(path));
  const result = buildSourceModuleAudit(
    { routes: [{ app: 'admin', route: '/questions', sourceFile: 'apps/admin/app/questions/page.tsx', uiModuleCandidates: [path], stylesheetModules: [] }] },
    [route('UI-ADMIN-QUESTIONS', 'apps/admin/app/questions/page.tsx')],
    []
  );
  assert.equal(result.componentRows[0].component_audit_id, componentAuditIdForSource(path));
  assert.equal(result.componentRows[0].platform, 'admin');
  assert.equal(result.componentRows[0].consuming_audit_ids, 'UI-ADMIN-QUESTIONS');
  assert.ok(result.routeRows[0].component_audit_ids.includes(componentAuditIdForSource(path)));
  assert.equal(result.componentRows.length, COMPONENT_AUDIT_COLUMNS.length > 0 ? 1 : 0);
});

test('source graph cannot silently skip or ambiguously match a page route', () => {
  const graphWithUnknown = {
    routes: [{ app: 'web', route: '/missing', sourceFile: 'apps/web/app/missing/page.tsx', uiModuleCandidates: [], stylesheetModules: [] }]
  };
  assert.throws(
    () => buildSourceModuleAudit(graphWithUnknown, [route('UI-WEB-ROOT', 'apps/web/app/page.tsx')], []),
    /Expected exactly one route audit row/
  );

  const duplicatedRouteRows = [
    route('UI-WEB-ROOT', 'apps/web/app/page.tsx'),
    route('UI-WEB-DUPLICATE', 'apps/web/app/page.tsx')
  ];
  const oneGraphRoute = {
    routes: [{ app: 'web', route: '/', sourceFile: 'apps/web/app/page.tsx', uiModuleCandidates: [], stylesheetModules: [] }]
  };
  assert.throws(
    () => buildSourceModuleAudit(oneGraphRoute, duplicatedRouteRows, []),
    /found 2/
  );
});
