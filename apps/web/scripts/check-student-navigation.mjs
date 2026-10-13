import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), 'utf8');
const layout = read('../app/components/WorkspaceLayout.tsx');
const css = read('../app/globals.css');
const accountMenu = read('../app/components/StudentAccountMenu.tsx');
const parentAccountMenu = read('../app/components/ParentAccountMenu.tsx');
const languageProvider = read('../app/components/LanguageProvider.tsx');
const testsClient = read('../app/student/tests/TestsClient.tsx');
const certificatesPage = read('../app/student/certificates/page.tsx');
const androidApp = read('../../android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidCertificates = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');

const arrayStart = layout.indexOf('const studentNavGroups: StudentNavGroup[] = [');
const arrayEnd = layout.indexOf('\nconst parentItems: Item[] = [', arrayStart);
assert.notEqual(arrayStart, -1, 'Student navigation configuration must exist.');
assert.notEqual(arrayEnd, -1, 'Parent menu must follow the student navigation configuration.');
const studentGroups = layout.slice(arrayStart, arrayEnd);

const groupIds = [...studentGroups.matchAll(/\bid: '(today|learn|practice|progress|library)'/g)].map((match) => match[1]);
assert.deepEqual(groupIds, ['today', 'learn', 'practice', 'progress', 'library'], 'Student navigation must have exactly five ordered top-level groups.');

const requiredGroupRoutes = [
  { group: 'today', routes: ['/student'] },
  { group: 'learn', routes: ['/student/learn', '/student/classes'] },
  { group: 'practice', routes: ['/student/practice', '/student/tests', '/student/diagnostic'] },
  { group: 'progress', routes: ['/student/progress', '/student/certificates'] },
  { group: 'library', routes: ['/student/textbooks'] },
];

for (const { group, routes } of requiredGroupRoutes) {
  const start = studentGroups.indexOf(`id: '${group}'`);
  assert.notEqual(start, -1, `The ${group} group must be defined.`);
  const nextGroupIndex = groupIds.indexOf(group) + 1;
  const nextId = groupIds[nextGroupIndex];
  const end = nextId ? studentGroups.indexOf(`id: '${nextId}'`, start + 1) : studentGroups.length;
  const groupSource = studentGroups.slice(start, end);
  for (const route of routes) {
    assert.ok(groupSource.includes(route), `Expected route ${route} in the ${group} group.`);
  }
}

assert.ok(!studentGroups.includes('/student/profile'), 'Profile must not be a primary navigation destination.');
assert.ok(accountMenu.includes("href=\"/student/profile\"") || accountMenu.includes("href='/student/profile'"), 'Profile & Settings must remain accessible from the student account menu.');

const parentStart = layout.indexOf('const parentItems: Item[] = [');
const parentEnd = layout.indexOf('\nexport default function WorkspaceLayout', parentStart);
const parentItems = layout.slice(parentStart, parentEnd);
for (const route of ['/parent', '/parent/children', '/parent/profile']) {
  assert.ok(parentItems.includes(route), `Parent route ${route} must remain configured.`);
}

assert.ok(layout.includes('visibleStudentNavGroups.map(group => group.href ?'), 'Desktop student navigation must use the grouped configuration.');
assert.ok(layout.includes('qe-student-mobile-nav'), 'Mobile student navigation must use the grouped configuration.');
assert.ok(layout.includes("event.key === 'Escape'"), 'Mobile navigation must close on Escape.');
assert.ok(layout.includes('qe-workspace-mobile-nav-backdrop'), 'Mobile navigation must support dismissal by tapping the backdrop.');
assert.ok(layout.includes('hidden={expandedStudentGroup !== group.id}'), 'Desktop submenu target must remain mounted for aria-controls.');
assert.ok(layout.includes('hidden={mobileOpenGroup !== group.id}'), 'Mobile submenu targets must remain mounted for aria-controls.');
assert.ok(css.includes('.qe-workspace-subnav[hidden]') && css.includes('.qe-workspace-mobile-subnav[hidden]'), 'Closed submenu targets must be visually hidden.');
assert.ok(css.includes('.qe-workspace-mobile-nav-backdrop'), 'Mobile submenu backdrop styles must exist.');

// Mobile bottom bars contain primary navigation only; Logout remains in utility/account areas.
const mobileStudentStart = layout.indexOf('<nav className="qe-workspace-mobile-nav qe-student-mobile-nav"');
const mobileStudentEnd = layout.indexOf('</nav>', mobileStudentStart);
assert.notEqual(mobileStudentStart, -1, 'Student mobile primary navigation must exist.');
assert.notEqual(mobileStudentEnd, -1, 'Student mobile primary navigation must close.');
const mobileStudentNav = layout.slice(mobileStudentStart, mobileStudentEnd);
assert.ok(!mobileStudentNav.includes('logout(') && !mobileStudentNav.includes('Logout'), 'Student mobile primary navigation must not include Logout as a sixth item.');
const mobileParentStart = layout.indexOf('<nav className="qe-workspace-mobile-nav" aria-label="Parent navigation">');
const mobileParentEnd = layout.indexOf('</nav>', mobileParentStart);
assert.notEqual(mobileParentStart, -1, 'Parent mobile navigation must exist.');
const mobileParentNav = layout.slice(mobileParentStart, mobileParentEnd);
assert.ok(!mobileParentNav.includes('logout(') && !mobileParentNav.includes('Logout'), 'Parent mobile primary navigation must contain only the three parent destinations.');
assert.ok(accountMenu.includes('student-account-menu__logout') && accountMenu.includes('onLogout();'), 'Student Logout must be available from the profile/account menu outside primary navigation.');
assert.ok(parentAccountMenu.includes('parent-account__logout') && parentAccountMenu.includes('onLogout();'), 'Parent Logout must be available from the parent account menu outside primary navigation.');
assert.ok(layout.includes('onLogout={() => void logout()} loggingOut={loggingOut}'), 'Account menus must reuse the existing session logout handler and in-progress state.');

// Android shares the canonical group IDs and selects its rail/bar by orientation rather than width.
const androidStudentGroupIds = [...androidApp.matchAll(/AppNavItem\("(today|learn|practice|progress|library)"/g)].map((match) => match[1]);
assert.deepEqual(androidStudentGroupIds, ['today', 'learn', 'practice', 'progress', 'library'], 'Android must render the same five student groups in web order.');
const androidParentGroupIds = [...androidApp.matchAll(/AppNavItem\("(family|children|profile)"/g)].map((match) => match[1]);
assert.deepEqual(androidParentGroupIds, ['family', 'children', 'profile'], 'Android parent navigation must keep its three role-specific destinations.');
assert.ok(androidApp.includes('LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE'), 'Android navigation must derive placement from device orientation.');
assert.ok(androidApp.includes('if (signedIn && landscapeLayout) NavigationRail'), 'Android landscape must use the left navigation rail.');
assert.ok(androidApp.includes('if (signedIn && !landscapeLayout) NavigationBar'), 'Android portrait must use the bottom navigation bar.');
assert.ok(!androidApp.includes('screenWidthDp >= 600'), 'Screen width must not choose Android primary navigation placement.');
for (const routeGroup of [
  'AppRoute.PROGRESS, AppRoute.CERTIFICATES -> "progress"',
  'AppRoute.RESOURCES, AppRoute.DOCUMENT_READER -> "library"',
  'AppRoute.PRACTICE, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT'
]) assert.ok(androidApp.includes(routeGroup), `Android route ownership must retain mapping: ${routeGroup}`);

// App language, learning content language and certificate language are separate preferences.
const uiSetterStart = languageProvider.indexOf('const setLocale = useCallback');
const learningSetterStart = languageProvider.indexOf('const setLearningLanguage = useCallback');
assert.ok(uiSetterStart >= 0 && learningSetterStart > uiSetterStart, 'Language provider must expose distinct UI and learning setters.');
assert.ok(!languageProvider.slice(uiSetterStart, learningSetterStart).includes('/api/v1/students/me/language'), 'Changing app UI language must not PATCH the learning-language preference.');
assert.ok(languageProvider.slice(learningSetterStart).includes('/api/v1/students/me/language'), 'Learning-language changes must persist to the student preference API.');
assert.ok(languageProvider.includes("'qe-learning-language'") && languageProvider.includes("'qe-certificate-language'"), 'Learning and certificate language preferences must have independent persistence keys.');
assert.ok(testsClient.includes('const language=learningLanguage;') && testsClient.includes('language:certificateLanguage'), 'Tests must use learning language for content and certificate language for issuance.');
assert.ok(certificatesPage.includes('certificateLanguage') && certificatesPage.includes('setCertificateLanguage'), 'The certificate centre must expose an independent certificate-language preference.');
assert.ok(androidApp.includes('mutableStateOf(preferences.getString("certificateLanguage"') && androidApp.includes('.put("language", certificateLanguage)'), 'Android certificate issuance must use its separately persisted certificate language.');
assert.ok(androidCertificates.includes('onCertificateLanguageChange("hi")') && androidCertificates.includes('onCertificateLanguageChange("en")'), 'Android certificate centre must expose Hindi/English certificate-language choices.');

// Keep Android-only native/offline capabilities protected while regrouping common navigation.
for (const protectedCapability of [
  'OfflineAssessmentStore(context, resourceOwner)',
  'ProtectedResourceStore(context, resourceOwner)',
  'ProtectedResourceDownloadWorker.enqueue(context, resourceOwner, id)'
]) assert.ok(androidApp.includes(protectedCapability), `Android-only capability must remain: ${protectedCapability}`);
assert.ok(androidCertificates.includes('GmsBarcodeScanning.getClient(activity).startScan()'), 'Native certificate QR/barcode scanning must remain available.');

console.log('Navigation/language contract passed: five student groups, three parent destinations, orientation, independent language preferences, and Android-only offline/security features.');
