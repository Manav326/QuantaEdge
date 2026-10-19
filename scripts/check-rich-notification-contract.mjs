import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(path, 'utf8');
const senderApps = ['web', 'admin', 'people'];
const sharedFiles = [
  'NotificationStudio.tsx',
  'NotificationStudio.module.css',
  'RichNotificationRenderer.tsx',
  'RichNotificationRenderer.module.css',
  'LogoGraphicsLab.tsx',
  'LogoGraphicsLab.module.css',
];

for (const file of sharedFiles) {
  const paths = senderApps.map(app => `apps/${app}/app/components/${file}`);
  const sources = paths.map(read);
  assert.ok(sources.every(source => source === sources[0]),
    `Sender workspaces must share the same ${file}: ${paths.join(', ')}`);
}
const studio = read('apps/web/app/components/NotificationStudio.tsx');
for (const required of [
  'Design Studio', 'Logo & Graphics', 'Templates', 'Campaigns & Delivery',
  'RichNotificationRenderer', 'LogoGraphicsLab', 'Preview & validate audience',
  'idempotencyKey', 'selectedInstitutionIds', 'cancelCampaign',
  'onReorderBlocks', 'backgroundColor', 'focalPoint',
]) {
  assert.ok(studio.includes(required), `Notification Studio is missing ${required}`);
}
for (const path of [
  'apps/admin/app/notifications/page.tsx',
  'apps/people/app/notifications/page.tsx',
  'apps/web/app/institution/page.tsx',
  'apps/web/app/student/notifications/page.tsx',
  'apps/web/app/parent/notifications/page.tsx',
  'apps/android/app/src/main/java/com/quantaedge/android/ui/NotificationInboxScreen.kt',
  'services/api/src/main/java/com/quantaedge/api/NotificationController.java',
  'services/api/src/main/java/com/quantaedge/api/NotificationMediaController.java',
  'services/api/src/main/java/com/quantaedge/api/NotificationContentValidator.java',
  'services/api/src/main/java/com/quantaedge/api/RichNotificationService.java',
  'services/api/src/main/java/com/quantaedge/api/NotificationDispatchScheduler.java',
  'services/api/src/test/java/com/quantaedge/api/NotificationContentValidatorTest.java',
  'services/api/src/main/resources/db/migration/V139__rich_notification_platform.sql',
  'services/api/src/main/resources/db/migration/V140__rich_notification_template_versioning.sql',
  'services/api/src/main/resources/db/migration/V141__retain_notification_templates_and_media_after_creator_deletion.sql',
  'docs/product/rich-notification-platform-contract.md',
]) {
  assert.doesNotThrow(() => read(path), `Required notification feature file is missing: ${path}`);
}
const webLayout = read('apps/web/app/components/WorkspaceLayout.tsx');
assert.ok(webLayout.includes('<NotificationBell role={role} />'),
  'The learner/parent workspace header must include the persistent notification bell.');
const androidShell = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
assert.ok(androidShell.includes('NotificationBellButton(language = language)') &&
  androidShell.includes('"/student/classes" -> openClasses(currentSubject)') &&
  androidShell.includes('"/parent/children"'),
  'Android signed-in shell must expose the notification inbox and map notification actions to native routes.');
const boundary = read('apps/web/app/components/SessionBoundary.tsx');
assert.ok(boundary.includes('X-QE-Institution-Code') && boundary.includes('institutionCodeForRequest()'),
  'Learner inbox requests must preserve the selected institution context.');
const brandProvider = read('apps/web/app/components/TenantBrandingProvider.tsx');
assert.ok(brandProvider.includes("{ name: 'qe-tenant-parent', path: '/parent' }"),
  'Parent routes must persist the institution code for tenant-scoped inbox/media requests.');
const service = read('services/api/src/main/java/com/quantaedge/api/RichNotificationService.java');
for (const required of [
  'audience_snapshot->\'institutionIds\' @> jsonb_build_array(?::bigint)',
  'scopedIdempotencyKey(', 'resolveAudience(', 'dispatchScheduledCampaign(', 'cancelCampaign(',
]) {
  assert.ok(service.includes(required), `Notification service is missing ${required}`);
}
const media = read('services/api/src/main/java/com/quantaedge/api/NotificationMediaController.java');
assert.ok(media.includes('X-Content-Type-Options') &&
  media.includes('sha256(bytes)') && media.includes('MAX_IMAGE_BYTES') &&
  media.includes('MAX_VIDEO_BYTES'),
  'Notification media must enforce types, bounds and integrity validation.');
const validator = read('services/api/src/main/java/com/quantaedge/api/NotificationContentValidator.java');
assert.ok(validator.includes('MAX_DOCUMENT_NODES') &&
  validator.includes('UNSAFE_KEYS') && validator.includes('validateTarget'),
  'Rich-content document validation and unsafe markup/link checks are required.');
console.log('Rich notification contract passed: shared sender studio, tenant-scoped inbox/media, safe rich content and Android/web entry points.');
