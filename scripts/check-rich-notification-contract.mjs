import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const read = (path) => readFileSync(path, 'utf8');
const senderApps = ['web', 'admin', 'people'];
const migrationDirectory = 'services/api/src/main/resources/db/migration';
const migrationVersions = new Map();
for (const filename of readdirSync(migrationDirectory).filter(name => /^V\d+__.*\.sql$/.test(name))) {
  const version = filename.match(/^V(\d+)__/)?.[1];
  const previous = migrationVersions.get(version);
  assert.ok(!previous, `Flyway version ${version} is duplicated: ${previous} and ${filename}`);
  migrationVersions.set(version, filename);
}

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
  'services/api/src/main/java/com/quantaedge/api/AndroidPushService.java',
  'services/api/src/main/java/com/quantaedge/api/NotificationDeviceController.java',
  'services/api/src/main/java/com/quantaedge/api/NotificationPushDispatchScheduler.java',
  'services/api/src/test/java/com/quantaedge/api/AndroidPushServiceTest.java',
  'apps/android/app/src/main/java/com/quantaedge/android/data/NotificationPushRegistration.kt',
  'apps/android/app/src/main/java/com/quantaedge/android/data/NotificationPushMessagingService.kt',
  'services/api/src/test/java/com/quantaedge/api/NotificationContentValidatorTest.java',
  'services/api/src/test/java/com/quantaedge/api/RichNotificationAudienceIsolationTest.java',
  'services/api/src/test/java/com/quantaedge/api/RichNotificationAudienceIsolationIntegrationTest.java',
  'services/api/src/main/resources/db/migration/V142__rich_notification_platform.sql',
  'services/api/src/main/resources/db/migration/V143__rich_notification_template_versioning.sql',
  'services/api/src/main/resources/db/migration/V144__rich_notification_template_active_name.sql',
  'services/api/src/main/resources/db/migration/V145__retain_notification_templates_and_media_after_creator_deletion.sql',
  'services/api/src/main/resources/db/migration/V146__android_push_notification_devices.sql',
  'services/api/src/main/resources/db/migration/V165__notification_preview_proof.sql',
  'docs/product/rich-notification-platform-contract.md',
]) {
  assert.doesNotThrow(() => read(path), `Required notification feature file is missing: ${path}`);
}
const audienceService = read('services/api/src/main/java/com/quantaedge/api/RichNotificationService.java');
for (const required of [
  'applyAudienceExclusions(', 'excludedRecipientKeys(', 'recipientDetails(', 'hasActiveLearners(',
  'candidateRecipientCount', 'excludedRecipientCount', 'as institution_name',
  'withoutIndividualAudienceExclusions(', 'issuePreviewToken(', 'consumePreviewToken(',
  'request_fingerprint', 'previewTokenRequired', "read_at=case when ? in ('READ','DISMISSED')"
]) {
  assert.ok(audienceService.includes(required), 'Audience review is missing ' + required);
}
const audienceController = read('services/api/src/main/java/com/quantaedge/api/NotificationController.java');
for (const required of ['active_learner_count', 'eligible_parent_count', 'active_admin_count']) {
  assert.ok(audienceController.includes(required), 'Institution audience options are missing ' + required);
}
for (const app of senderApps) {
  const studioSource = read('apps/' + app + '/app/components/NotificationStudio.tsx');
  const styleSource = read('apps/' + app + '/app/components/NotificationStudio.module.css');
  for (const required of ['Recipient review', 'toggleRecipientIncluded', 'excludedInstitutionIds', 'recipientReviewQuery', 'previewToken', 'No active learners — excluded from HRMS platform notifications']) {
    assert.ok(studioSource.includes(required), app + ' notification sender is missing ' + required);
  }
  assert.ok(styleSource.includes('.audienceReview') && styleSource.includes('.reviewRecipientList'),
    app + ' notification sender is missing grouped recipient-review styles');
}

const receiverInboxService = read('services/api/src/main/java/com/quantaedge/api/RichNotificationService.java');
for (const required of [
  'inboxForEducationStaff(', 'inboxForHrmsStaff(', 'markReadForStaff(',
  "r.staff_account_id=? and r.institution_id=?", "c.sender_workspace='HRMS'"
]) {
  assert.ok(receiverInboxService.includes(required), 'Cross-workspace inbox service is missing ' + required);
}
const receiverController = read('services/api/src/main/java/com/quantaedge/api/NotificationController.java');
for (const required of [
  '/admin/notifications/inbox', '/hrms/notifications/inbox',
  'requireEducationStaffInboxActor(', 'hrmsInboxStaffId('
]) {
  assert.ok(receiverController.includes(required), 'Cross-workspace inbox authorization is missing ' + required);
}
const adminInbox = read('apps/admin/app/components/StaffNotificationInbox.tsx');
const peopleInbox = read('apps/people/app/components/StaffNotificationInbox.tsx');
assert.ok(adminInbox.includes('/api/v1/admin/notifications/inbox'), 'Education Admin inbox must use its scoped API.');
assert.ok(peopleInbox.includes('/api/v1/hrms/notifications/inbox'), 'HRMS inbox must use its scoped API.');
assert.ok(read('apps/admin/app/components/AdminWorkspaceLayout.tsx').includes('<StaffNotificationBell workspace="admin" />'),
  'Education Admin header needs a permission-aware notification bell.');
assert.ok(read('apps/people/app/components/PeopleWorkspaceLayout.tsx').includes('<StaffNotificationBell workspace="hrms" />'),
  'HRMS header needs a notification bell.');
const webInstitutionNotifications = read('apps/web/app/institution/notifications/page.tsx');
assert.ok(webInstitutionNotifications.includes('InstitutionNotificationsRedirect') &&
  webInstitutionNotifications.includes('NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN') &&
  !webInstitutionNotifications.includes('NotificationStudio'),
  'The legacy institution notification route must redirect to Admin rather than host a third notification studio.');
const adminNotificationsPage = read('apps/admin/app/notifications/page.tsx');
const adminNotificationsClient = read('apps/admin/app/notifications/AdminNotificationsPageClient.tsx');
assert.ok(adminNotificationsPage.includes('AdminNotificationsPageClient') &&
  adminNotificationsClient.includes("identityType === 'INSTITUTION'") &&
  adminNotificationsClient.includes('<NotificationStudio surface="admin" />') &&
  adminNotificationsClient.includes('<NotificationStudio surface="institution" />'),
  'Admin notifications route must preserve the staff studio and select the institution-scoped surface for institution principals.');
const adminInboxClient = read('apps/admin/app/components/StaffNotificationInbox.tsx');
assert.ok(adminInboxClient.includes('/api/v1/admin/notifications/inbox'),
  'Education Admin must keep the scoped notification inbox after the old institution portal is retired.');
assert.ok(adminInboxClient.includes("headers.delete('X-QE-Institution-Code')") &&
  !adminInboxClient.includes("'QUANTA-EDGE'"),
  'Admin notification inbox must use the server HRMS staff binding and never pin all institutions to QUANTA-EDGE.');
assert.ok(!read('apps/web/app/institution/page.tsx').includes('NotificationInboxClient'),
  'The learner-facing app must not retain a duplicate institution-admin notification inbox.');

const webLayout = read('apps/web/app/components/WorkspaceLayout.tsx');
assert.ok(webLayout.includes('<NotificationBell role={role} />'),
  'The learner/parent workspace header must include the persistent notification bell.');
const androidShell = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
assert.ok(androidShell.includes('NotificationBellButton(') &&
  androidShell.includes('requestedRecipientId = launchIntent?.getStringExtra("qe_notification_recipient_id")') &&
  androidShell.includes('"/student/classes" -> openClasses(currentSubject)') &&
  androidShell.includes('"/parent/children"'),
  'Android signed-in shell must expose the notification inbox, open push-selected recipients and map notification actions to native routes.');
const boundary = read('apps/web/app/components/SessionBoundary.tsx');
assert.ok(boundary.includes('X-QE-Institution-Code') && boundary.includes('institutionCodeForRequest()'),
  'Learner inbox requests must preserve the selected institution context.');
const brandProvider = read('apps/web/app/components/TenantBrandingProvider.tsx');
assert.ok(brandProvider.includes("{ name: 'qe-tenant-parent', path: '/parent' }"),
  'Parent routes must persist the institution code for tenant-scoped inbox/media requests.');
const service = read('services/api/src/main/java/com/quantaedge/api/RichNotificationService.java');
for (const required of [
  'audience_snapshot->\'institutionIds\' @> jsonb_build_array(?::bigint)',
  'scopedIdempotencyKey(', 'resolveAudience(', 'dispatchScheduledCampaign(', 'cancelCampaign(', 'markUnread(', 'dismissNotification(',
]) {
  assert.ok(service.includes(required), `Notification service is missing ${required}`);
}
const notificationController = read('services/api/src/main/java/com/quantaedge/api/NotificationController.java');
for (const route of [
  '/notifications/{recipientId}/read',
  '/notifications/{recipientId}/unread',
  '/notifications/{recipientId}/dismiss',
  '/notifications/read-all',
]) {
  assert.ok(notificationController.includes(route), `Notification inbox action is missing: ${route}`);
}
const webInbox = read('apps/web/app/components/NotificationInboxClient.tsx');
assert.ok(webInbox.includes('ARCHIVED') && webInbox.includes('/dismiss') && webInbox.includes('/unread'),
  'Web inbox must expose archive, restore and mark-unread controls.');
for (const app of senderApps) {
  const renderer = read(`apps/${app}/app/components/RichNotificationRenderer.tsx`);
  assert.ok(renderer.includes('normalizeRichDocument') &&
    renderer.includes('JSON.parse(serialized)') &&
    renderer.includes('content_document') &&
    renderer.includes('version: 1'),
    `${app} rich notification renderer must normalize stringified/wrapped version-1 documents.`);
}
const notificationService = read('services/api/src/main/java/com/quantaedge/api/RichNotificationService.java');
assert.ok(notificationService.includes('parseContentDocument') &&
  notificationService.includes('decodedBlocks') &&
  notificationService.includes('document.put("version", 1)'),
  'Notification API responses must normalize legacy JSONB-encoded documents before returning them.');
const notificationValidator = read('services/api/src/main/java/com/quantaedge/api/NotificationContentValidator.java');
assert.ok(notificationValidator.includes('((ObjectNode) document).put("version", 1)'),
  'New notification documents must persist the canonical numeric version expected by all renderers.');
const androidInbox = read('apps/android/app/src/main/java/com/quantaedge/android/ui/NotificationInboxScreen.kt');
assert.ok(androidInbox.includes('normalizeNotificationDocument') && androidInbox.includes('JSONTokener'),
  'Android inbox must handle serialized notification content the same way as web.');

assert.ok(androidInbox.includes('filterArchived') && androidInbox.includes('/dismiss') && androidInbox.includes('/unread'),
  'Android inbox must expose archive, restore and mark-unread controls.');

const media = read('services/api/src/main/java/com/quantaedge/api/NotificationMediaController.java');
assert.ok(media.includes('X-Content-Type-Options') &&
  media.includes('sha256(bytes)') && media.includes('MAX_IMAGE_BYTES') &&
  media.includes('MAX_VIDEO_BYTES'),
  'Notification media must enforce types, bounds and integrity validation.');
const validator = read('services/api/src/main/java/com/quantaedge/api/NotificationContentValidator.java');
assert.ok(validator.includes('MAX_DOCUMENT_NODES') &&
  validator.includes('UNSAFE_KEYS') && validator.includes('validateTarget'),
  'Rich-content document validation and unsafe markup/link checks are required.');
const pushService = read('services/api/src/main/java/com/quantaedge/api/AndroidPushService.java');
assert.ok(pushService.includes('FirebaseMessaging') && pushService.includes('UNREGISTERED') &&
  pushService.includes('if (!isConfigured()) return 0;') && pushService.includes('isRecipientCurrentlyEligible') &&
  pushService.includes('eligibilityInstitutionIds') && pushService.includes('audience_snapshot_json') &&
  pushService.includes("next_attempt_at=now()+interval '10 minutes'") && pushService.includes('if(claimed!=1) continue;'),
  'Android push must be provider-backed, retryable/configuration-safe without discarding queued attempts, and recheck current recipient eligibility.');
const deviceController = read('services/api/src/main/java/com/quantaedge/api/NotificationDeviceController.java');
assert.ok(deviceController.includes('assertActiveMembership') && deviceController.includes('QE_PLATFORM_OWNER') &&
  deviceController.includes('tokenHash') && deviceController.includes('revokeDevice'),
  'Android registration must be authenticated, tenant-bound, revocable, and token-private.');
const pushClient = read('apps/android/app/src/main/java/com/quantaedge/android/data/NotificationPushRegistration.kt');
assert.ok(pushClient.includes('POST_NOTIFICATIONS') && pushClient.includes('FCM_CLIENT_CONFIGURED') &&
  pushClient.includes('/api/v1/notifications/devices') && pushClient.includes('revokeCurrent'),
  'Android push registration must handle optional configuration, notification permission, token registration and logout.');
const audienceTests = read('services/api/src/test/java/com/quantaedge/api/RichNotificationAudienceIsolationTest.java');
for (const required of [
  '101L', '202L', '303L', 'directStudentAudienceMustBelongToTheSelectedInstitutionAcrossThreeTenants',
  'hrmsCampaignRejectsAnInactiveInstitutionInsteadOfSilentlySkippingIt',
  'courseAudienceMustBeAssignedToTheSelectedTenantBeforeResolution',
  'parentDirectTargetRequiresAnActiveConsentedGuardianRelationshipInThatTenant',
  'staffTargetsFailClosedUntilCanonicalTenantMappingExists',
  'previewAppliesInstitutionExclusionsBeforeDeduplicatingCrossTenantRecipients',
  'individualHrmsTargetsAreFilteredBeforeDeduplicatingAcrossInstitutionMemberships',
  'newCampaignSendRejectsAnUnknownOrExpiredPreviewProof',
  'markingSingleStaffNotificationReadSetsReadTimestamp',
]) {
  assert.ok(audienceTests.includes(required), `P1 notification audience regression coverage is missing ${required}`);
}

const audienceIntegration = read('services/api/src/test/java/com/quantaedge/api/RichNotificationAudienceIsolationIntegrationTest.java');
for (const required of [
  'qe.qa.lifecycle.integration', 'Flyway.configure()', 'TENANT_LEARNER_BOUNDARY_V1',
  'institutionA', 'institutionB', 'institutionC', 'HRMS_PLATFORM', "consent_status='CONSENTED'",
  'assertThrows(SecurityException.class',
]) {
  assert.ok(audienceIntegration.includes(required), `P1 notification database acceptance coverage is missing ${required}`);
}

console.log('Rich notification contract passed: shared sender studio, tenant-scoped inbox/media, safe rich content and Android/web entry points.');
