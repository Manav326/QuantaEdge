import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const hostPaths = [
  'apps/web/app/components/GlobalNotificationHost.tsx',
  'apps/admin/app/components/GlobalNotificationHost.tsx',
  'apps/people/app/components/GlobalNotificationHost.tsx',
];
const hosts = hostPaths.map(path => readFileSync(path, 'utf8'));
assert.ok(hosts.every(source => source === hosts[0]), 'Web, Admin and People notification hosts must remain identical');
const host = hosts[0];
assert.ok(host.includes("type Tone = 'info' | 'success' | 'warning' | 'error'"), 'Warning tone must be supported');
assert.ok(host.includes('return createPortal(') && host.includes('document.body'), 'Notices must portal to document.body');
assert.ok(host.includes("position: 'fixed'") && host.includes('zIndex: 2147483647'), 'Notices must remain fixed above page content');
assert.ok(!host.includes('window.fetch =') && !host.includes('installGlobalFetchInterceptor'), 'Notification host must not monkey-patch global fetch');

const clientPaths = [
  'apps/web/app/components/apiClient.ts',
  'apps/admin/app/components/apiClient.ts',
  'apps/people/app/components/apiClient.ts',
];
const clients = clientPaths.map(path => readFileSync(path, 'utf8'));

// The request-context adapters are intentionally app/tenant-aware and need not be byte-identical.
// Compare the shared response-notification policy itself, which ends before each application's
// institution/workspace adapter, so tenant-specific security logic can evolve without causing a
// false failure or being removed to satisfy this contract.
const notificationPolicy = clients.map(source => {
  const start = source.indexOf("type Tone = 'info' | 'success' | 'warning' | 'error'");
  const end = source.indexOf('const INSTITUTION_CODE_PATTERN', start);
  assert.ok(start >= 0 && end > start, 'Each API client must expose the shared notification-policy section');
  return source.slice(start, end);
});
assert.ok(notificationPolicy.every(source => source === notificationPolicy[0]),
  'Web, Admin and People must share identical API notification parsing and delivery policy');
for (const [index, source] of clients.entries()) {
  assert.ok(source.includes('export async function apiFetch('), clientPaths[index] + ' must export central apiFetch');
  assert.ok(source.includes('response.clone().text()') && source.includes('if (!response.ok) {'),
    clientPaths[index] + ' must inspect errors without consuming caller bodies');
  assert.ok(source.includes('record.warning') && source.includes("'warning'"),
    clientPaths[index] + ' must deliver explicit API warnings');
  assert.ok(source.includes("notifyUser(networkErrorMessage(), 'error')"),
    clientPaths[index] + ' must deliver network failures');
}
const client = clients[0];

for (const path of ['apps/web/app/layout.tsx','apps/admin/app/layout.tsx','apps/people/app/layout.tsx']) {
  assert.ok(readFileSync(path, 'utf8').includes('GlobalNotificationHost'), path + ' must mount the notification host');
}
const migratedPaths = ["apps/web/app/student/page.tsx","apps/web/app/student/courses/page.tsx","apps/web/app/student/CourseScope.tsx","apps/web/app/student/progress/page.tsx","apps/web/app/student/profile/page.tsx","apps/web/app/student/certificates/page.tsx","apps/web/app/student/marketplace/page.tsx","apps/web/app/student/tests/TestsClient.tsx","apps/web/app/student/practice/PracticeClient.tsx","apps/web/app/student/structured-lessons/StructuredLessonsClient.tsx","apps/web/app/student/structured-lessons/StructuredLessonDetailClient.tsx","apps/web/app/student/structured-lessons/AssignedLearningResources.tsx","apps/web/app/student/classes/ClassesClient.tsx","apps/web/app/student/diagnostic/page.tsx","apps/web/app/parent/courses/page.tsx","apps/web/app/parent/children/page.tsx","apps/web/app/parent/profile/page.tsx","apps/web/app/parent/page.tsx","apps/web/app/login/page.tsx","apps/web/app/login/student/page.tsx","apps/web/app/signup/page.tsx","apps/web/app/components/AccountIdentitySettings.tsx","apps/web/app/components/WorkspaceLayout.tsx","apps/web/app/components/SessionBoundary.tsx","apps/web/app/components/TenantBrandingProvider.tsx","apps/web/app/components/LanguageProvider.tsx","apps/web/app/components/GoogleSignInButton.tsx","apps/web/app/session/page.tsx","apps/admin/app/session/page.tsx","apps/admin/app/login/page.tsx","apps/people/app/login/page.tsx","apps/people/app/change-password/page.tsx","apps/people/app/components/PeopleSessionBoundary.tsx","apps/people/app/components/PeopleWorkspaceLayout.tsx","apps/people/app/institutions/page.tsx","apps/admin/app/page.tsx","apps/admin/app/account-lifecycle/page.tsx","apps/admin/app/employees/page.tsx","apps/admin/app/certificates/page.tsx","apps/admin/app/students/page.tsx","apps/admin/app/parents/page.tsx","apps/admin/app/components/AdminCourseScope.tsx","apps/admin/app/components/AdminSidebar.tsx","apps/admin/app/resources/page.tsx","apps/admin/app/components/AdminWorkspaceLayout.tsx","apps/admin/app/components/AdminSessionBoundary.tsx","apps/web/app/student/account-lifecycle/page.tsx","apps/web/app/certificate/verify/page.tsx","apps/web/app/student/learn/TutorDock.tsx","apps/web/app/student/learn/InlineTextbookReader.tsx"];
for (const path of migratedPaths) {
  const source = readFileSync(path, 'utf8');
  assert.ok(source.includes('import { apiFetch }'), path + ' must import the central API client');
  assert.ok(!/(?<![\w.$])fetch\s*\(/.test(source), path + ' must not use direct fetch');
}
const workflow = readFileSync('.github/workflows/ci.yml', 'utf8');
assert.ok(workflow.includes('node scripts/check-global-notification-contract.mjs'), 'Contract must run in CI');
console.log('Global notification contract passed: all migrated APIs use centralized response handling.');
