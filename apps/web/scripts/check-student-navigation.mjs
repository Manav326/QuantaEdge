import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), 'utf8');
const layout = read('../app/components/WorkspaceLayout.tsx');
const css = read('../app/globals.css');
const accountMenu = read('../app/components/StudentAccountMenu.tsx');
const parentAccountMenu = read('../app/components/ParentAccountMenu.tsx');
const languageProvider = read('../app/components/LanguageProvider.tsx');
const sessionProxy = read('../proxy.ts');
const testsClient = read('../app/student/tests/TestsClient.tsx');
const practiceClient = read('../app/student/practice/PracticeClient.tsx');
const learnClient = read('../app/student/learn/LearnClient.tsx');
const certificatesPage = read('../app/student/certificates/page.tsx');
const androidApp = read('../../android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidNavigationContract = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentNavigationContract.kt');
const androidNavigationTests = read('../../android/app/src/test/java/com/quantaedge/android/ui/StudentNavigationContractTest.kt');

// Parent and Student sessions are separate identities. The proxy may only use
// the matching workspace cookie as a routing hint; the API/client guard validates identity.
const sessionBoundary = read('../app/components/SessionBoundary.tsx');
const sessionLanding = read('../app/session/page.tsx');
const studentLogin = read('../app/login/student/page.tsx');
const parentLogin = read('../app/login/page.tsx');

for (const cookieName of ['QE_PARENT_SESSION', 'QE_PARENT_REFRESH']) {
  assert.ok(sessionProxy.includes(cookieName), `Parent route guard must recognise ${cookieName}.`);
}
for (const cookieName of ['QE_STUDENT_SESSION', 'QE_STUDENT_REFRESH']) {
  assert.ok(sessionProxy.includes(cookieName), `Student route guard must recognise ${cookieName}.`);
}
assert.ok(sessionProxy.includes("if (studentRoute && !hasWorkspaceSessionHint(request, 'STUDENT'))"),
  'Student routes must check Student-scoped cookies, not any learner cookie.');
assert.ok(sessionProxy.includes("if (parentRoute && !hasWorkspaceSessionHint(request, 'PARENT'))"),
  'Parent routes must check Parent-scoped cookies, not any learner cookie.');
assert.ok(!sessionProxy.includes('if (publicEntry && learnerSession)'),
  'A Parent cookie must not hijack the Student login route before the user can sign in.');

for (const [label, source] of [['SessionBoundary', sessionBoundary], ['session landing', sessionLanding]]) {
  assert.ok(source.includes("session.role === 'STUDENT'") || source.includes("identity.role === 'STUDENT'"), `${label} must verify the Student role explicitly.`);
  assert.ok(source.includes("session.role === 'PARENT'") || source.includes("identity.role === 'PARENT'"),
    `${label} must verify the Parent role explicitly.`);
  assert.ok(source.includes("workspace === 'STUDENT'") || source.includes("session.workspace === 'STUDENT'"),
    `${label} must verify the Student workspace explicitly.`);
  assert.ok(source.includes("workspace === 'PARENT'") || source.includes("session.workspace === 'PARENT'"),
    `${label} must verify the Parent workspace explicitly.`);
}
assert.ok(studentLogin.includes("body.role !== 'STUDENT'") && studentLogin.includes("body.workspace !== 'STUDENT'"),
  'Student login must reject any response that does not confirm a Student-scoped session.');
assert.ok(studentLogin.includes("window.location.replace('/student')"),
  'Student login must perform a full navigation after scoped cookies are issued.');
assert.ok(parentLogin.includes("body.role !== 'PARENT'") && parentLogin.includes("body.workspace !== 'PARENT'"),
  'Parent login must reject any response that does not confirm a Parent-scoped session.');
assert.ok(parentLogin.includes("window.location.replace('/parent')"),
  'Parent login must perform a full navigation after scoped cookies are issued.');

// Route inventory and pure navigation functions must be exhaustive over AppRoute.
// Kotlin's exhaustive enum when-expressions also fail compilation when a new route is not categorized.
const enumStart = androidNavigationContract.indexOf('internal enum class AppRoute {');
const enumEnd = androidNavigationContract.indexOf('\n}', enumStart);
assert.notEqual(enumStart, -1, 'Android AppRoute enum must be explicitly declared.');
assert.notEqual(enumEnd, -1, 'Android AppRoute enum must close correctly.');
const enumBody = androidNavigationContract.slice(enumStart, enumEnd);
const appRouteIds = [...enumBody.matchAll(/\\b([A-Z][A-Z0-9_]+)\\b/g)].map((match) => match[1]);
const studentMappingStart = androidNavigationContract.indexOf('fun studentGroupForRoute(');
const parentMappingStart = androidNavigationContract.indexOf('fun parentGroupForRoute(', studentMappingStart);
assert.ok(studentMappingStart >= 0 && parentMappingStart > studentMappingStart, 'Student and parent route mapping functions must be separately testable.');
const studentMapping = androidNavigationContract.slice(studentMappingStart, parentMappingStart);
const parentMapping = androidNavigationContract.slice(parentMappingStart);
const studentMappedRoutes = [...new Set([...studentMapping.matchAll(/AppRoute\\.([A-Z_]+)/g)].map((match) => match[1]))].sort();
const parentMappedRoutes = [...new Set([...parentMapping.matchAll(/AppRoute\\.([A-Z_]+)/g)].map((match) => match[1]))].sort();
assert.deepEqual(studentMappedRoutes, [...appRouteIds].sort(), 'Every Android route must have a documented student-group owner or explicit auth/parent exception.');
assert.deepEqual(parentMappedRoutes, [...appRouteIds].sort(), 'Every Android route must have documented parent-group ownership or an explicit non-parent exception.');
assert.ok(androidNavigationContract.includes('listOf("today", "learn", "practice", "progress", "library")'), 'Android contract must own the five canonical student group IDs.');
assert.ok(androidNavigationContract.includes('listOf("family", "children", "profile")'), 'Android contract must own the three parent group IDs.');

const androidCertificates = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');
const androidScanner = read('../../android/app/src/main/java/com/quantaedge/android/ui/PremiumCodeScannerScreen.kt');

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
const desktopNestedStart = layout.indexOf('<div className="qe-workspace-subnav"');
const desktopNestedEnd = layout.indexOf('</div>', desktopNestedStart);
const mobileNestedStart = layout.indexOf('<div className="qe-workspace-mobile-subnav-links">');
const mobileNestedEnd = layout.indexOf('</div>', mobileNestedStart);
assert.ok(desktopNestedStart >= 0 && desktopNestedEnd > desktopNestedStart, 'Desktop nested links must exist.');
assert.ok(mobileNestedStart >= 0 && mobileNestedEnd > mobileNestedStart, 'Mobile nested links must exist.');
assert.ok(!layout.slice(desktopNestedStart, desktopNestedEnd).includes('qe-workspace-icon'), 'Desktop nested links must be text-first, without competing feature icons.');
assert.ok(!layout.slice(mobileNestedStart, mobileNestedEnd).includes('item.icon'), 'Mobile nested links must be text-first, without competing feature icons.');
assert.ok(css.includes('.qe-workspace-subnav[hidden]') && css.includes('.qe-workspace-mobile-subnav[hidden]'), 'Closed submenu targets must be visually hidden.');
assert.ok(css.includes('.qe-workspace-mobile-nav-backdrop'), 'Mobile submenu backdrop styles must exist.');

// Mobile bottom bars contain primary navigation only; Logout remains in utility/account areas.
const mobileStudentStart = layout.indexOf('<nav className="qe-workspace-mobile-nav qe-student-mobile-nav"');
const mobileStudentEnd = layout.indexOf('</nav>', mobileStudentStart);
assert.notEqual(mobileStudentStart, -1, 'Student mobile primary navigation must exist.');
assert.notEqual(mobileStudentEnd, -1, 'Student mobile primary navigation must close.');
const mobileStudentNav = layout.slice(mobileStudentStart, mobileStudentEnd);
assert.ok(!mobileStudentNav.includes('logout(') && !mobileStudentNav.includes('Logout'), 'Student mobile primary navigation must not include Logout as a sixth item.');
const mobileParentStart = layout.indexOf('<nav className="qe-workspace-mobile-nav" aria-label={t(\'web.navigation.parent.label\')}>');
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
assert.ok(androidApp.includes('if (signedIn && landscapeLayout && route != AppRoute.TUTOR && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) NavigationRail'), 'Android landscape must use the left navigation rail outside the full-screen tutor and protected reader.');
assert.ok(androidApp.includes('if (signedIn && !landscapeLayout && route != AppRoute.TUTOR && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) NavigationBar'), 'Android portrait must use the bottom navigation bar outside the full-screen tutor and protected reader.');
assert.ok(!androidApp.includes('screenWidthDp >= 600'), 'Screen width must not choose Android primary navigation placement.');
// Testable, non-Compose route ownership: all detailed Android destinations map to one of the shared five groups.
for (const routeGroup of [
  'AppRoute.PROGRESS, AppRoute.CERTIFICATES -> "progress"',
  'AppRoute.RESOURCES, AppRoute.DOCUMENT_READER -> "library"',
  'AppRoute.PRACTICE, AppRoute.DIAGNOSTIC, AppRoute.ASSESSMENTS, AppRoute.ASSESSMENT_ATTEMPT',
  'AppRoute.SUBJECT_LESSONS, AppRoute.LESSON, AppRoute.TUTOR, AppRoute.CLASSES',
  'AppRoute.ASSESSMENT_RESULTS, AppRoute.ASSESSMENT_REVIEW, AppRoute.LEADERBOARD'
]) assert.ok(androidNavigationContract.includes(routeGroup), `Android route ownership must retain mapping: ${routeGroup}`);
assert.ok(androidApp.includes('StudentNavigationContract.studentGroupForRoute(destination, lastStudentNavGroup)'), 'Compose navigation must use the independently testable route contract.');
assert.ok(androidApp.includes('StudentNavigationContract.parentGroupForRoute(route, parentSection)'), 'Parent navigation must use its role-specific route contract.');
assert.ok(androidNavigationTests.includes('fun canonicalGroupsMatchTheWebOrder()'), 'Android group-order unit test is required.');
assert.ok(androidNavigationTests.includes('fun profileReturnsToTheLastValidStudentGroup()'), 'Profile return behaviour must be unit-tested.');
assert.ok(androidNavigationTests.includes('fun layoutPlacementIsDrivenByOrientationNotWidth()'), 'Orientation behaviour must be unit-tested.');

// App language, learning content language and certificate language are separate preferences.
const uiSetterStart = languageProvider.indexOf('const setLocale = useCallback');
const learningSetterStart = languageProvider.indexOf('const setLearningLanguage = useCallback');
assert.ok(uiSetterStart >= 0 && learningSetterStart > uiSetterStart, 'Language provider must expose distinct UI and learning setters.');
assert.ok(!languageProvider.slice(uiSetterStart, learningSetterStart).includes('/api/v1/students/me/language'), 'Changing app UI language must not PATCH the learning-language preference.');
assert.ok(languageProvider.slice(learningSetterStart).includes('/api/v1/students/me/language'), 'Learning-language changes must persist to the student preference API.');
assert.ok(languageProvider.includes("'qe-learning-language'") && languageProvider.includes("'qe-certificate-language'"), 'Learning and certificate language preferences must have independent persistence keys.');
assert.ok(testsClient.includes('const language=learningLanguage;') && testsClient.includes('language:certificateLanguage'), 'Tests must use learning language for content and certificate language for issuance.');
assert.ok(testsClient.includes("const uiLanguage=locale==='english'?'en':'hi';") && testsClient.includes('certificateTrackName(cert.subject_code, cert.subject_name, uiLanguage)'), 'Test-result labels must follow app language independently of content language.');
assert.ok(practiceClient.includes("'/api/v1/learning/practice/sessions/'+requestedSessionId+'?language='+learningLanguage"), 'Reopening practice must use learning-content language, not app UI language.');
assert.ok(learnClient.includes('textbookChapterTitle(item, learningLanguage)'), 'Lesson PDF titles must follow selected content language.');
assert.ok(certificatesPage.includes('certificateLanguage') && certificatesPage.includes('setCertificateLanguage'), 'The certificate centre must expose an independent certificate-language preference.');
assert.ok(androidApp.includes('mutableStateOf(preferences.getString("certificateLanguage"') && androidApp.includes('.put("language", certificateLanguage)'), 'Android certificate issuance must use its separately persisted certificate language.');
assert.ok(androidCertificates.includes('onCertificateLanguageChange("hi")') && androidCertificates.includes('onCertificateLanguageChange("en")'), 'Android certificate centre must expose Hindi/English certificate-language choices.');

// Keep Android-only native/offline capabilities protected while regrouping common navigation.
for (const protectedCapability of [
  'OfflineAssessmentStore(context, resourceOwner)',
  'ProtectedResourceStore(context, resourceOwner)',
  'ProtectedResourceDownloadWorker.enqueue(context, resourceOwner, id)'
]) assert.ok(androidApp.includes(protectedCapability), `Android-only capability must remain: ${protectedCapability}`);
assert.ok(androidCertificates.includes('PremiumCodeScannerScreen(') && androidCertificates.includes('parseQuantaEdgeCertificateQr(rawValue, BuildConfig.DEBUG)'), 'The certificate centre must route scans through the branded scanner and validate scanned payloads.');
assert.ok(androidScanner.includes('BarcodeScanning.getClient(scannerOptions(mode))') && androidScanner.includes('CameraSelector.DEFAULT_BACK_CAMERA'), 'The native scanner must analyse live camera frames with ML Kit.');
assert.ok(androidScanner.includes('ScannerMode.QR_ONLY') && androidScanner.includes('ScannerMode.BARCODE_ONLY'), 'The scanner must retain explicit QR-only and linear-barcode modes.');

console.log('Navigation/language contract passed: five student groups, three parent destinations, orientation, independent language preferences, and Android-only offline/security features.');
