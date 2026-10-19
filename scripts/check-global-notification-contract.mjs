import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const hostPaths = [
  'apps/web/app/components/GlobalNotificationHost.tsx',
  'apps/admin/app/components/GlobalNotificationHost.tsx',
  'apps/people/app/components/GlobalNotificationHost.tsx',
];
const hosts = hostPaths.map(path => readFileSync(path, 'utf8'));
assert.ok(hosts.every(source => source === hosts[0]), 'Web, Admin and People notification hosts must remain identical');

const source = hosts[0];
const installation = source.indexOf('installGlobalFetchInterceptor();');
const component = source.indexOf('export default function GlobalNotificationHost()');
assert.ok(installation >= 0 && component > installation, 'The API interceptor must install at module initialization, before component effects');
assert.ok(source.includes('queuedNotifications.push({ message: clean, tone });'), 'Notices must buffer before the host subscribes');
assert.ok(source.includes('queuedNotifications.splice(0).forEach'), 'Buffered notices must display after the host subscribes');
assert.ok(source.includes('if (!response.ok) {'), 'Every failed API response must be routed to the notification host');
assert.ok(source.includes('response.clone().text()'), 'Inspecting API errors must preserve the original response body');
assert.ok(source.includes("notifyUser(networkErrorMessage(), 'error')"), 'Network errors must use the global notification path');
assert.ok(source.includes('if (isApi && !isAbort)'), 'Aborted requests must not produce error notices');
assert.ok(source.includes('return createPortal(') && source.includes('document.body'), 'Notices must portal outside page layout containers');
assert.ok(source.includes("position: 'fixed'") && source.includes('zIndex: 2147483647'), 'Notices must remain fixed above page content');

for (const path of ['apps/web/app/layout.tsx', 'apps/admin/app/layout.tsx', 'apps/people/app/layout.tsx']) {
  const layout = readFileSync(path, 'utf8');
  assert.ok(layout.includes('GlobalNotificationHost'), `${path} must mount the notification host at the app root`);
}

const workflow = readFileSync('.github/workflows/ci.yml', 'utf8');
assert.ok(workflow.includes('node scripts/check-global-notification-contract.mjs'), 'The notification contract must run in CI');
console.log('Global notification contract passed: early API interception, buffered errors, and fixed viewport host.');
assert.ok(!source.includes('if (window.fetch === wrappedFetch) window.fetch = originalFetch;'), 'Component cleanup must not reference module-scoped interceptor locals');
