import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const hosts = [
  readFileSync('apps/web/app/components/GlobalNotificationHost.tsx','utf8'),
  readFileSync('apps/admin/app/components/GlobalNotificationHost.tsx','utf8'),
  readFileSync('apps/people/app/components/GlobalNotificationHost.tsx','utf8')
];
assert.ok(hosts.every(source => source === hosts[0]), 'Notification hosts must remain identical across apps');
const host = hosts[0];
assert.ok(host.includes("type Tone = 'info' | 'success' | 'warning' | 'error'"), 'Warning tone must be supported');
assert.ok(host.includes('return createPortal(') && host.includes('document.body'), 'Notifications must portal to document.body');
assert.ok(host.includes("position: 'fixed'") && host.includes('zIndex: 2147483647'), 'Notifications must remain fixed above page content');
assert.ok(!host.includes('window.fetch =') && !host.includes('installGlobalFetchInterceptor'), 'Host must not monkey-patch global fetch');

const clients = [
  readFileSync('apps/web/app/components/apiClient.ts','utf8'),
  readFileSync('apps/admin/app/components/apiClient.ts','utf8'),
  readFileSync('apps/people/app/components/apiClient.ts','utf8')
];
assert.ok(clients.every(source => source === clients[0]), 'Apps must share the same API notification policy');
const client = clients[0];
assert.ok(client.includes('export async function apiFetch('), 'Central apiFetch helper is required');
assert.ok(client.includes('response.clone().text()') && client.includes('if (!response.ok) {'), 'Responses must be inspected without consuming caller bodies');
assert.ok(client.includes("notifyUser(message, 'error')"), 'API errors must reach the host');
assert.ok(client.includes('record.warning') && client.includes("'warning'"), 'Explicit API warnings must reach the host');
assert.ok(client.includes("notifyUser(networkErrorMessage(), 'error')"), 'Network errors must reach the host');
for (const path of ['apps/web/app/layout.tsx','apps/admin/app/layout.tsx','apps/people/app/layout.tsx']) {
  assert.ok(readFileSync(path,'utf8').includes('GlobalNotificationHost'), path + ' must mount the host');
}
console.log('Global notification contract passed: central API response handling and fixed viewport host.');
