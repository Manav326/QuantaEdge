import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRouteRegisterParity, inspectRouteRegisterParity } from './check_ui_route_register_parity.mjs';

const generated = [
  { app: 'web', kind: 'page', primaryRoute: true, route: '/', file: 'apps/web/app/page.tsx' },
  { app: 'web', kind: 'page', primaryRoute: true, route: '/student/notifications', file: 'apps/web/app/student/notifications/page.tsx' },
  { app: 'admin', kind: 'page', primaryRoute: true, route: '/notifications/inbox', file: 'apps/admin/app/notifications/inbox/page.tsx' },
  { app: 'people', kind: 'page', primaryRoute: true, route: '/qa', file: 'apps/people/app/qa/page.tsx' }
];
const registered = [
  { platform: 'web', entry_type: 'Next.js page', route_or_destination: '/', source_path: 'apps/web/app/page.tsx' },
  { platform: 'web', entry_type: 'Next.js page', route_or_destination: '/student/notifications', source_path: 'apps/web/app/student/notifications/page.tsx' },
  { platform: 'admin', entry_type: 'Next.js page', route_or_destination: '/notifications/inbox', source_path: 'apps/admin/app/notifications/inbox/page.tsx' },
  { platform: 'people-hrms', entry_type: 'Next.js page', route_or_destination: '/qa', source_path: 'apps/people/app/qa/page.tsx' },
  { platform: 'android', entry_type: 'Android destination', route_or_destination: 'AppRoute.LOGIN', source_path: 'apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt' }
];

test('route register parity accepts exact source routes and normalizes existing HRMS platform aliases', () => {
  const report = inspectRouteRegisterParity(generated, registered);
  assert.equal(report.generatedPageCount, 4);
  assert.equal(report.registeredPageCount, 4);
  assert.deepEqual(report.missingFromRegister, []);
  assert.deepEqual(report.noLongerInSource, []);
  assert.deepEqual(assertRouteRegisterParity(report), report);
});

test('route register parity rejects a newly added page not present in the QA screen register', () => {
  const report = inspectRouteRegisterParity(generated, registered.slice(0, 3));
  assert.equal(report.missingFromRegister.length, 1);
  assert.deepEqual(report.missingFromRegister[0], {
    app: 'people', route: '/qa', source: 'apps/people/app/qa/page.tsx'
  });
  assert.throws(() => assertRouteRegisterParity(report), /missing from docs\/qa\/ui-screen-inventory\.csv/);
});

test('route register parity rejects stale source entries and duplicate identities', () => {
  const duplicated = [...registered.slice(0, 4), {
    platform: 'web', entry_type: 'Next.js page', route_or_destination: '/',
    source_path: 'apps/web/app/page.tsx'
  }];
  const report = inspectRouteRegisterParity(generated, duplicated);
  assert.equal(report.duplicateRegistered.length, 1);
  assert.throws(() => assertRouteRegisterParity(report), /Duplicate UI route register identities/);
});
