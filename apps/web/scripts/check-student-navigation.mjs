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
const structuredLessonsClient = read('../app/student/structured-lessons/StructuredLessonsClient.tsx');
const structuredLessonDetailClient = read('../app/student/structured-lessons/StructuredLessonDetailClient.tsx');
const tutorDock = read('../app/student/learn/TutorDock.tsx');
const tutorService = read('../../../services/api/src/main/java/com/quantaedge/api/TutorService.java');
const tutorController = read('../../../services/api/src/main/java/com/quantaedge/api/TutorController.java');
const tutorRetriever = read('../../../services/api/src/main/java/com/quantaedge/api/TutorCurriculumRetriever.java');
const learningController = read('../../../services/api/src/main/java/com/quantaedge/api/LearningController.java');
const safeQuestionOptionsMigration = read('../../../services/api/src/main/resources/db/migration/V185__student_safe_question_option_projection.sql');
const translatedSafeQuestionOptionsMigration = read('../../../services/api/src/main/resources/db/migration/V190__translated_student_question_option_projection.sql');
const relationalStructuredLessonService = read('../../../services/api/src/main/java/com/quantaedge/api/RelationalStructuredLessonService.java');
const learningStateServiceTest = read('../../../services/api/src/test/java/com/quantaedge/api/LearningStateServiceTest.java');
const structuredLessonExperience = read('../app/student/structured-lessons/StructuredLessonExperience.tsx');
const structuredLessonContract = read('../app/student/structured-lessons/contract.ts');
const structuredQuestionRenderer = read('../app/student/structured-lessons/StructuredQuestionRenderer.tsx');
const tutorMigration = read('../../../services/api/src/main/resources/db/migration/V160__structured_artifact_ai_tutor_sessions.sql');
const structuredLessonsApi = read('../../../services/api/src/main/java/com/quantaedge/api/StructuredLessonController.java');
const certificatesPage = read('../app/student/certificates/page.tsx');
const studentProgressPage = read('../app/student/progress/page.tsx');
const studentBooksClient = read('../app/student/textbooks/TextbooksClient.tsx');
const studentMarketplacePage = read('../app/student/marketplace/page.tsx');
const parentMarketplacePage = read('../app/parent/courses/page.tsx');
const marketplaceCourseCard = read('../app/components/MarketplaceCourseCard.tsx');
const marketplaceCourseCardCss = read('../app/components/MarketplaceCourseCard.module.css');
const marketplaceI18n = read('../app/components/marketplaceI18n.ts');
const marketplaceTimestamp = read('../app/components/formatUserTimestamp.ts');
const guardianCourseProgramsApi = read('../../../services/api/src/main/java/com/quantaedge/api/GuardianController.java');
const certificateApi = read('../../../services/api/src/main/java/com/quantaedge/api/CertificateController.java');
const diagnosticApi = read('../../../services/api/src/main/java/com/quantaedge/api/DiagnosticController.java');
const courseOrderFinancialService = read('../../../services/api/src/main/java/com/quantaedge/api/CourseOrderFinancialService.java');
const learningMetricsService = read('../../../services/api/src/main/java/com/quantaedge/api/LearningMetricsService.java');
const recommendationApi = read('../../../services/api/src/main/java/com/quantaedge/api/RecommendationController.java');
const courseMarketplaceApi = read('../../../services/api/src/main/java/com/quantaedge/api/CourseMarketplaceController.java');
const studentCoursesPage = read('../app/student/courses/page.tsx');
const studentClassesClient = read('../app/student/classes/ClassesClient.tsx');
const studentDiagnosticPage = read('../app/student/diagnostic/page.tsx');
const studentCertificatesPage = read('../app/student/certificates/page.tsx');
const studentLessonDetailClient = read('../app/student/structured-lessons/StructuredLessonDetailClient.tsx');
const studentEmptyState = read('../app/student/components/StudentEmptyState.tsx');
const englishUiCatalog = read('../app/i18n/catalogs/english.json');
const hinglishUiCatalog = read('../app/i18n/catalogs/hinglish.json');
const androidApp = read('../../android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidRouteBody = (name, nextName) => {
  const start = androidApp.indexOf(`fun ${name}()`);
  const end = androidApp.indexOf(`fun ${nextName}()`, start + 1);
  assert.ok(start >= 0 && end > start, `Android route helper ${name} must remain present and bounded by ${nextName}.`);
  return androidApp.slice(start, end);
};
const androidCourseSubjectsRoute = androidRouteBody('renderSubjectLessonsRoute', 'renderLessonRoute');
const androidLessonRoute = androidRouteBody('renderLessonRoute', 'renderPracticeRoute');
const androidDiagnosticRoute = androidRouteBody('renderDiagnosticRoute', 'renderAssessmentsRoute');
const androidAssessmentResultsRoute = androidRouteBody('renderAssessmentResultsRoute', 'renderAssessmentReviewRoute');
const androidLeaderboardRoute = androidRouteBody('renderLeaderboardRoute', 'renderTutorRoute');
const androidRouteCompatibilityTests = read('../../android/app/src/test/java/com/quantaedge/android/ui/StudentNavigationContractTest.kt');
const androidLessonContentBlock = read('../../android/app/src/main/java/com/quantaedge/android/ui/LessonContentBlock.kt');
const androidStructuredRenderer = read('../../android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonRenderer.kt');
const androidResponseCompatibility = read('../../android/app/src/main/java/com/quantaedge/android/ui/LearningResponseCompatibility.kt');
const androidResponseCompatibilityTests = read('../../android/app/src/test/java/com/quantaedge/android/ui/LearningResponseCompatibilityTest.kt');
const androidLocalization = read('../../android/app/src/main/java/com/quantaedge/android/ui/AndroidLocalization.kt');
const androidProgressScreen = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentProgressScreen.kt');
const androidEnrollmentEmptyState = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentEnrollmentEmptyState.kt');
const androidMarketplaceScreen = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentMarketplaceScreen.kt');
const androidAllCoursesScreen = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentAllCoursesScreen.kt');
const androidClassesScreen = read('../../android/app/src/main/java/com/quantaedge/android/ui/LearningClassesScreen.kt');
const androidCertificatesScreen = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');
const androidNavigationContract = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentNavigationContract.kt');
const androidNavigationTests = read('../../android/app/src/test/java/com/quantaedge/android/ui/StudentNavigationContractTest.kt');
const androidGoogleCredentialFlow = read('../../android/app/src/main/java/com/quantaedge/android/data/GoogleCredentialFlow.kt');

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
// The shared account sign-in page accepts both Parent and regular Learner accounts.
 // Its destination() function must positively match role + workspace and reject all other
 // responses before performing a full navigation. Keep support for the former Parent-only guard
 // so future dedicated flows remain valid too.
const strictParentOnlyGuard = parentLogin.includes("body.role !== 'PARENT'") && parentLogin.includes("body.workspace !== 'PARENT'");
const sharedAccountGuard =
  parentLogin.includes("body.workspace === 'PARENT' && body.role === 'PARENT'") &&
  parentLogin.includes("body.workspace === 'STUDENT' && body.role === 'STUDENT'") &&
  parentLogin.includes("throw new Error('QuantaEdge could not confirm this account session. Please sign in again.')");
assert.ok(strictParentOnlyGuard || sharedAccountGuard,
  'Login must reject any response that does not confirm a valid role- and workspace-scoped session.');
assert.ok(parentLogin.includes("window.location.replace('/parent')") || parentLogin.includes("window.location.replace(destination(body))"),
  'Account login must perform a full navigation only after the scoped session has been confirmed.');

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
assert.ok(androidNavigationContract.includes('listOf("today", "courses", "learn", "practice", "progress", "library")'), 'Android contract must own the six canonical student group IDs.');
assert.ok(androidNavigationContract.includes('listOf("family", "children", "profile")'), 'Android contract must own the three parent group IDs.');

const androidCertificates = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');
const androidScanner = read('../../android/app/src/main/java/com/quantaedge/android/ui/PremiumCodeScannerScreen.kt');

const arrayStart = layout.indexOf('const studentNavGroups: StudentNavGroup[] = [');
const arrayEnd = layout.indexOf('\nconst parentItems: Item[] = [', arrayStart);
assert.notEqual(arrayStart, -1, 'Student navigation configuration must exist.');
assert.notEqual(arrayEnd, -1, 'Parent menu must follow the student navigation configuration.');
const studentGroups = layout.slice(arrayStart, arrayEnd);

const groupIds = [...studentGroups.matchAll(/\bid: '(today|courses|learn|practice|progress|library)'/g)].map((match) => match[1]);
assert.deepEqual(groupIds, ['today', 'courses', 'learn', 'practice', 'progress', 'library'], 'Student navigation must expose Courses & Marketplace beside the existing learning groups.');

const requiredGroupRoutes = [
  { group: 'today', routes: ['/student'] },
  { group: 'courses', routes: ['/student/courses', '/student/marketplace'] },
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

const coursePage = read('../app/student/courses/page.tsx');
const marketplacePage = read('../app/student/marketplace/page.tsx');
const dashboardPage = read('../app/student/page.tsx');
assert.ok(coursePage.includes("'/api/v1/students/me/programs'"), 'All Courses must load the authoritative learner course catalogue.');
assert.ok(coursePage.includes("'/api/v1/students/me/programs/id/'"), 'Switching the active course must go through the server-owned enrolment transition.');
assert.ok(marketplacePage.includes("'/api/v1/marketplace/courses/'") && marketplacePage.includes("'/api/v1/marketplace/orders/'"),
  'Marketplace checkout must use server-created Razorpay orders and server-side payment verification.');
assert.ok(marketplacePage.includes("'/api/v1/marketplace/orders/' + draft.localOrderId + '/reconcile'") &&
  marketplacePage.includes('sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(recoverableDraft)'),
  'Student web checkout must save the order before opening Razorpay and reconcile it when the callback is lost.');
assert.ok(parentMarketplacePage.includes("'/api/v1/marketplace/family/orders/' + draft.localOrderId + '/reconcile'") &&
  parentMarketplacePage.includes('sessionStorage.setItem(PENDING_VERIFY_STORAGE_KEY, JSON.stringify(recoverableDraft)'),
  'Parent web checkout must save the order before opening Razorpay and reconcile it when the callback is lost.');
assert.ok(courseMarketplaceApi.includes('@PostMapping("/orders/{localOrderId}/reconcile")') &&
  courseMarketplaceApi.includes('@PostMapping("/family/orders/{localOrderId}/reconcile")') &&
  courseMarketplaceApi.includes('inspectProviderOrderForNewAttempt'),
  'Server must reconcile both student and parent orders and refuse reuse of a paid Razorpay order.');
assert.ok(dashboardPage.includes('qe-active-course-section') && dashboardPage.includes('/student/courses') && dashboardPage.includes('/student/marketplace'),
  'Dashboard must show the active course and link to dedicated Courses and Marketplace screens.');
assert.ok(dashboardPage.includes("'&lessonId='+encodeURIComponent(String(rec.lesson.id))"),
  'The recommended Quick Action must open the exact recommended lesson, while the Learning action retains the course lesson/topic list.');
assert.ok(practiceClient.indexOf('const searchParams=useSearchParams();') < practiceClient.indexOf('useState(()=>searchParams.get'),
  'Practice must initialize URL-dependent state only after declaring the searchParams hook.');
assert.ok((certificateApi.match(/@RequestParam\("courseId"\) String suppliedCourseId/g)||[]).length >= 4,
  'Certificate GET routes must bind the public courseId query parameter consistently.');
assert.ok(guardianCourseProgramsApi.includes('@RequestParam(name="includeSubjects",defaultValue="false") boolean includeSubjects') &&
  read('../app/parent/children/page.tsx').includes("'/programs?includeSubjects=true'") &&
  guardianCourseProgramsApi.includes('if(includeSubjects)') && read('../app/parent/page.tsx').includes('/programs?includeSubjects=false'),
  'The parent dashboard must avoid loading unused subject details; course assignment screens retain the default subject catalogue.');
assert.ok(!dashboardPage.includes("courses.map((course:any)=>("), 'Dashboard must not display the entire all-courses catalogue inline.');

const emptyStatePages = [
  ['Learn', learnClient, 'student.empty.learn.message'],
  ['Practice', practiceClient, 'student.empty.practice.message'],
  ['Progress', studentProgressPage, 'student.empty.progress.message'],
  ['Books', studentBooksClient, 'student.empty.books.message'],
];
assert.ok(dashboardPage.includes('student.empty.dashboard.title') &&
  dashboardPage.includes('student.empty.dashboard.message') &&
  dashboardPage.includes('student.empty.dashboard.action') &&
  !dashboardPage.includes('Opening the course marketplace') &&
  !dashboardPage.includes("router.replace('/student/marketplace')"),
  'A learner without enrolments must stay on the dashboard and see a localized, explicit Marketplace action; the course-fetch effect must not redirect them.');
for (const [pageName, source, messageKey] of emptyStatePages) {
  assert.ok(source.includes(messageKey) && source.includes('noEnrolledCourses') &&
    source.includes('!loadingCourses') && source.includes('!courseError'),
    `${pageName} must show its localized empty state only after course loading succeeds with no enrolments.`);
}
for (const key of [
  'student.empty.dashboard.title', 'student.empty.dashboard.message', 'student.empty.dashboard.action',
  'student.empty.learn.message', 'student.empty.practice.message', 'student.empty.progress.message', 'student.empty.books.message'
]) {
  assert.ok(Object.hasOwn(JSON.parse(englishUiCatalog), key) && Object.hasOwn(JSON.parse(hinglishUiCatalog), key),
    `Both UI language catalogs must define ${key}.`);
}
const androidSignInFlow = androidApp.slice(
  androidApp.indexOf('suspend fun enterSignedInHome('),
  androidApp.indexOf('suspend fun refreshUser()')
);
assert.ok(androidSignInFlow.includes('route = AppRoute.STUDENT_HOME') &&
  !androidSignInFlow.includes('route = AppRoute.MARKETPLACE') &&
  androidApp.includes('Button(onClick = onMarketplace') &&
  androidApp.includes('fun studentHasNoCourses(): Boolean') &&
  androidApp.includes('studentProgramsLoaded') &&
  androidApp.includes('hasConfirmedNoEnrolledCourses'),
  'Android must keep first-time learners on the dashboard and open Marketplace only from its explicit action.');
assert.ok(androidLocalization.includes('empty_dashboard_title') &&
  androidApp.includes('Your lessons will appear here once you enroll in a course.') &&
  androidApp.includes('Your practice activities will appear here once you enroll in a course.') &&
  androidProgressScreen.includes('Your learning progress will appear here once you enroll in a course.') &&
  androidApp.includes('Your course books will appear here once you enroll in a course.'),
  'Android Learn, Practice, Progress and Books must have language-aware empty-state messages.');
assert.ok(androidApp.includes('if (noEnrolledCourses)') &&
  androidProgressScreen.includes('if (noEnrolledCourses)'),
  'Android must suppress course/subject filtering UI when no courses are enrolled.');
assert.ok(androidApp.includes('FloatingTutorDock(') &&
  androidApp.includes('lesson.longAny("id", "lesson_id") > 0L') &&
  androidApp.includes('structuredLessonArtifactId.isNotBlank()') &&
  !androidApp.includes('if (tutorMinimized)'),
  'Android AI Tutor must use one dock scoped to a canonical lesson or structured artifact, without a second LessonScreen bubble.');
assert.ok(!androidApp.includes('Text(if (language == "hi") "ट्यूटर" else "Tutor")'),
  'The Learn submenu must not expose the unusable Tutor text link.');
assert.ok(androidApp.includes('StudentAllCoursesScreen(') && androidApp.includes('StudentMarketplaceScreen('),
  'Android must have dedicated All Courses and Marketplace routes.');
assert.ok(androidApp.includes('setPendingCoursePayment("STUDENT", recoverableDraft)') &&
  androidApp.includes('"/api/v1/marketplace/orders/" + localOrderId + "/reconcile"') &&
  androidMarketplaceScreen.includes('pendingVerification') && androidMarketplaceScreen.includes('onRetryVerification'),
  'Android student checkout must persist and reconcile the exact order after a missing or failed callback.');
assert.ok(androidApp.includes('setPendingCoursePayment("PARENT", recoverableDraft)') &&
  androidApp.includes('"/api/v1/marketplace/family/orders/" + localOrderId + "/reconcile"') &&
  read('../../android/app/src/main/java/com/quantaedge/android/ui/ParentCourseMarketplaceScreen.kt').includes('onRetryVerification'),
  'Android parent checkout must persist and reconcile the exact order after a missing or failed callback.');
assert.ok(androidGoogleCredentialFlow.includes('.setFilterByAuthorizedAccounts(true)') &&
  androidGoogleCredentialFlow.includes('GetSignInWithGoogleOption.Builder(clientId)') &&
  androidGoogleCredentialFlow.includes('catch (error: GetCredentialCancellationException)') &&
  androidGoogleCredentialFlow.includes('isAccountReauthFailure(error)'),
  'Android Google sign-in must use the authorized-account bottom sheet first and explicitly recover the [16] re-authentication failure.');
assert.ok(androidGoogleCredentialFlow.includes('clearCredentialState(ClearCredentialStateRequest())') &&
  androidApp.includes('GoogleCredentialFlow.clearCredentialState(context.applicationContext)'),
  'Android logout must clear Credential Manager provider state so the next Google sign-in starts cleanly.');

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
const mobileParentStart = layout.indexOf('<nav className="qe-workspace-mobile-nav qe-parent-mobile-nav" aria-label={t(\'web.navigation.parent.label\')}>');
const mobileParentEnd = layout.indexOf('</nav>', mobileParentStart);
assert.notEqual(mobileParentStart, -1, 'Parent mobile navigation must exist.');
const mobileParentNav = layout.slice(mobileParentStart, mobileParentEnd);
assert.ok(!mobileParentNav.includes('logout(') && !mobileParentNav.includes('Logout'), 'Parent mobile primary navigation must contain only the three parent destinations.');
assert.ok(accountMenu.includes('student-account-menu__logout') && accountMenu.includes('onLogout();'), 'Student Logout must be available from the profile/account menu outside primary navigation.');
assert.ok(parentAccountMenu.includes('parent-account__logout') && parentAccountMenu.includes('onLogout();'), 'Parent Logout must be available from the parent account menu outside primary navigation.');
assert.ok(layout.includes('onLogout={() => void logout()} loggingOut={loggingOut}'), 'Account menus must reuse the existing session logout handler and in-progress state.');

// Android shares the canonical group IDs and selects its rail/bar by orientation rather than width.
const androidStudentGroupIds = [...androidApp.matchAll(/AppNavItem\("(today|courses|learn|practice|progress|library)"/g)].map((match) => match[1]);
assert.deepEqual(androidStudentGroupIds, ['today', 'courses', 'learn', 'practice', 'progress', 'library'], 'Android must render the same six student groups in web order.');
const androidParentGroupIds = [...androidApp.matchAll(/AppNavItem\("(family|children|profile)"/g)].map((match) => match[1]);
assert.deepEqual(androidParentGroupIds, ['family', 'children', 'profile'], 'Android parent navigation must keep its three role-specific destinations.');
assert.ok(androidApp.includes('LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE'), 'Android navigation must derive placement from device orientation.');
assert.ok(androidApp.includes('if (signedIn && landscapeLayout && route != AppRoute.TUTOR && liveBroadcastVideoId.isBlank() && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) NavigationRail'), 'Android landscape must use the left navigation rail outside the full-screen tutor and protected reader.');
assert.ok(androidApp.includes('if (signedIn && !landscapeLayout && route != AppRoute.TUTOR && liveBroadcastVideoId.isBlank() && !(route == AppRoute.DOCUMENT_READER && documentFullscreen)) NavigationBar'), 'Android portrait must use the bottom navigation bar outside the full-screen tutor and protected reader.');
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
assert.ok(practiceClient.includes('useStudentCourseScope') && practiceClient.includes('/api/v1/learning/practice/sessions/from-lesson') && practiceClient.includes('practiceSessionId:sessionId') && practiceClient.includes('courseId:selectedCourseId'), 'Practice must preserve the original resumable session UI while scoping session reads, answers and saved results to the selected course.');
assert.ok(learnClient.includes('useStudentCourseScope') && learnClient.includes('InstitutionCourseSubjectScope') && learnClient.includes('/api/v1/learning/lessons?subjectCode=') && learnClient.includes('qe-student-topic-row') && learnClient.includes('TutorDock') && learnClient.includes('courseKey') && learnClient.includes('canStartTopicPractice') && learnClient.includes('courseId={selectedCourseId!}') && learnClient.includes("encodeURIComponent(selectedCourseKey)") && learnClient.includes('mcq_answered_count') && learnClient.includes('practice_completed_attempts') && !learnClient.includes('classCode='), 'Learning must preserve chapter/topic navigation, inline MCQ marking, practice gate, tutor and progress history while using tenant/course-scoped APIs and public course IDs in generated links.');
const documentCatalogueGuard = learnClient.indexOf('if(loadingCourses||!subjectCode||selectedCourseId===null||selectedCourseId<=0){');
const documentCatalogueFetch = learnClient.indexOf("fetch('/api/v1/learning/documents?subjectCode='");
assert.ok(documentCatalogueGuard >= 0 && documentCatalogueFetch > documentCatalogueGuard &&
  learnClient.includes("const courseQuery=selectedCourseId!==null?'&courseId='+selectedCourseId:'';"),
  'Document catalogue requests must wait for a resolved numeric course ID and pass that exact course ID to the API.');

const coursesPage = read('../app/student/courses/page.tsx');
const workspaceLayout = read('../app/components/WorkspaceLayout.tsx');
const courseScope = read('../app/student/CourseScope.tsx');
const androidAllCourses = read('../../android/app/src/main/java/com/quantaedge/android/ui/StudentAllCoursesScreen.kt');
const parentPage = read('../app/parent/page.tsx');
assert.ok(coursesPage.includes("'/student/learn?courseId=' + encodeURIComponent(String(selected.public_id || courseId))") &&
  coursesPage.includes("String(course.public_id || id)") &&
  coursesPage.includes("!active && (") && coursesPage.includes('Focus on this course'), 'Course focus and subject shortcuts must open the exact selected course using its public ID.');
const certificateCourseBackfill = read('../../../services/api/src/main/resources/db/migration/V161__backfill_legacy_certificate_course_identity.sql');
assert.ok(certificateCourseBackfill.includes('WHERE cert.program_id IS NULL') &&
  certificateCourseBackfill.includes("program.code = 'CLASS_' || source_class.code") &&
  certificateCourseBackfill.includes('HAVING COUNT(DISTINCT program.id) = 1') &&
  certificateCourseBackfill.includes('SET program_id = match.course_id') &&
  certificateCourseBackfill.includes('do not regenerate or rewrite PDF/PNG files'),
  'Legacy certificate rows must be assigned to a course only when the existing class/subject mapping is unambiguous, preserving issued artifacts.');

assert.ok(workspaceLayout.includes("{ href: '/student/learn', message: 'web.navigation.student.learnTopics'") &&
  workspaceLayout.includes("{ href: '/student/structured-lessons', message: 'web.navigation.student.structuredLessons'") &&
  workspaceLayout.includes('showWorkspaceBack') && workspaceLayout.includes('goBackWithinWorkspace') &&
  JSON.parse(englishUiCatalog)['web.navigation.student.structuredLessons'] === 'Resume lesson' &&
  JSON.parse(hinglishUiCatalog)['web.navigation.student.structuredLessons'] === 'पाठ फिर से शुरू करें',
  'Learn navigation must distinguish Lessons and Topics from Resume Lesson, with a shared contextual Back action.');
const learningStateService = read('../../../services/api/src/main/java/com/quantaedge/api/LearningStateService.java');
assert.ok(learningStateService.includes("values(?,?,?,?,?,?,?,now(),?,?)") &&
  !learningStateService.includes("values(?,?,?,?,?,?,?,?,now(),?,?)"),
  'Course-scoped learner answer writes must bind institution/course columns and keep answered_at as the database timestamp.');
const structuredLessonContent = read('../app/student/structured-lessons/StructuredLessonContent.tsx');
assert.ok(!learnClient.includes("import StructuredLessonExperience from '../structured-lessons/StructuredLessonExperience';") &&
  !learnClient.includes('inlineStructuredLesson') &&
  !learnClient.includes('qe-structured-optional-insert') &&
  !learnClient.includes('Optional structured lesson view') &&
  learnClient.includes('learningBlocks.map(block=><Block') &&
  learnClient.includes("['IMAGE','DIAGRAM','GIF','VIDEO','AUDIO','ANIMATION']") &&
  learnClient.includes('const url=String(data.url||block.asset_url') &&
  structuredLessonContent.includes('showHeader = true') &&
  learnClient.includes('q.saved_answer') &&
  learnClient.includes('q.saved_correct') &&
  learningController.includes('result.put("structured_lesson", structuredPayload)') &&
  learningController.includes('coalesce(latest_attempt.answer_text,latest_attempt.selected_option) as saved_answer') &&
  learningController.includes('latest_attempt.correct as saved_correct') &&
  learningController.includes('qe_student_safe_question_options(q.id,q.question_type,qt.options)') &&
  safeQuestionOptionsMigration.includes("WHEN p_question_type='ORDER' THEN") &&
  safeQuestionOptionsMigration.includes("WHEN p_question_type='MATCH' THEN") &&
  translatedSafeQuestionOptionsMigration.includes('p_translated_options jsonb') &&
  translatedSafeQuestionOptionsMigration.includes('qe_student_safe_question_options(p_question_id, p_question_type)') &&
  relationalStructuredLessonService.includes('question.put("legacy_question_type", rawType)') &&
  relationalStructuredLessonService.includes('question.put("legacy_interaction_type", legacyInteraction)') &&
  relationalStructuredLessonService.includes('question.set("left_items", leftItems)') &&
  structuredQuestionRenderer.includes("question.legacy_interaction_type === 'ORDER'") &&
  !relationalStructuredLessonService.includes('if (options.size() < 2) continue;') &&
  structuredLessonExperience.includes('showExplanation = true') &&
  structuredLessonExperience.includes('{showExplanation && <StructuredLessonContent') &&
  learningStateServiceTest.includes('courseScopedAnswerInsertBindsTenantAndCourseWithMatchingPlaceholderCount') &&
  learningStateServiceTest.includes('values(?,?,?,?,?,?,?,now(),?,?)'),
  'Learn must render only the canonical ordered lesson blocks, while preserving the structured response contract for other clients.');

assert.ok(
  structuredLessonContract.includes('export function databaseLessonIdFromArtifactId') &&
  structuredLessonContract.includes('/^db-lesson-([1-9][0-9]{0,17})$/') &&
  structuredLessonsClient.includes("'qe:student:last-opened-lesson:course-'") &&
  structuredLessonsClient.includes("router.replace('/student/learn?' + target.toString())") &&
  structuredLessonsClient.includes("subjectCode: 'ALL'") &&
  structuredLessonDetailClient.includes('databaseLessonIdFromArtifactId(artifactId)') &&
  structuredLessonDetailClient.includes("router.replace('/student/learn?' + params.toString())"),
  'Resume Lesson must reopen the canonical Learn screen at the last course lesson and retain the Lessons and Topics return route.'
);

assert.ok(courseScope.includes('Number(course.id || course.course_id)') &&
  courseScope.includes('This course is not enrolled in your current institution account.') &&
  courseScope.includes('qe:student:activeCourseId'), 'Course scope must normalize IDs, reject unavailable explicit IDs and persist primary course focus.');
assert.ok(courseScope.includes("String(course.public_id || '').toUpperCase() === requestedText.toUpperCase()") &&
  structuredLessonsClient.includes('activeCourse?.public_id || courseId') &&
  structuredLessonDetailClient.includes('activeCourse?.public_id || courseId') &&
  structuredLessonsApi.includes('private long resolveCourseId(String suppliedCourseId)') &&
  structuredLessonsApi.includes('value.matches("[A-Z]{3}[A-Z0-9]{5}")'),
  'Web structured learning must route by 8-character public course IDs and resolve them server-side to the internal row ID.');
assert.ok(androidAllCourses.includes('if (!active)') &&
  androidAllCourses.includes('Focus on this course'),
  'An already-focused Android course must not add a new Open course action; other courses keep the original focus action.');
assert.ok(androidApp.includes('lesson.optJSONArray("blocks")?.objectItems()?.sortedWith(') &&
  androidApp.includes('it.optInt("sequence_no", Int.MAX_VALUE)') &&
  androidApp.includes('it.optLong("id", Long.MAX_VALUE)'),
  'Android legacy lessons must preserve backend sequence_no/id order.');
assert.ok(androidLessonContentBlock.includes('collectCompleteBlockText') &&
  androidLessonContentBlock.includes('LessonPlayableMedia(url = url, audioOnly = false') &&
  androidStructuredRenderer.includes('sortedBy { it.optInt("order", Int.MAX_VALUE) }') &&
  androidStructuredRenderer.includes('structuredBlockVisibleText') &&
  androidStructuredRenderer.includes('collectStructuredTextValue'),
  'Android must render all legacy/structured block types in order with media and safe unknown-type fallbacks.');

assert.ok(androidResponseCompatibility.includes('restoreLegacyLessonAnswers') &&
  androidResponseCompatibility.includes('!question.optBoolean("attempted")') &&
  androidResponseCompatibility.includes('question.optString("saved_answer")') &&
  androidResponseCompatibility.includes('question.optBoolean("saved_correct")') &&
  androidApp.includes('question.optBoolean("saved_correct") || answerLocks[id] == true') &&
  androidApp.includes('restoreLegacyLessonAnswers(loadedLesson, language)') &&
  androidApp.includes('loadStructuredResponsesForLegacyLesson(') &&
  androidApp.includes('Open optional structured lesson') &&
  androidApp.includes('fun submitStructuredQuestionResponse') &&
  androidApp.includes('fun saveStructuredActivityResponse') &&
  androidApp.includes('structuredLessonWithoutLegacyMcqDuplicates') &&
  androidResponseCompatibilityTests.includes('optionalStructuredLessonFiltersOnlyDuplicateLegacyMcqs') &&
  androidStructuredRenderer.includes('if (showPractice)') &&
  androidStructuredRenderer.includes('legacy_question_type_by_locale'),
  'Android must restore saved lesson answers, avoid duplicate legacy MCQs, retain other structured question types, and use shared save handlers.');
assert.ok(androidAllCourses.includes('onOpenSubject(code, id)') &&
  androidApp.includes('var structuredLessonMode by rememberSaveable { mutableStateOf(false) }') &&
  androidApp.includes('AppRoute.SUBJECT_LESSONS -> if (structuredLessonMode) openStructuredLessons(structuredSubjectFilter) else openSubject(currentSubject, currentCourseId)') &&
  androidApp.includes('val path = "/api/v1/learning/lessons?subjectCode=" + ApiClient.encodeQuery(subjectCode)') &&
  androidApp.includes('AppRoute.SUBJECT_LESSONS -> renderSubjectLessonsRoute()') &&
  androidCourseSubjectsRoute.includes('StudentCourseLearningScreen(') &&
  androidApp.includes('if (noEnrolledCourses)') &&
  androidCourseSubjectsRoute.includes('onRefreshLegacy = { openSubject(currentSubject, currentCourseId) }') &&
  androidCourseSubjectsRoute.includes('onOpenLegacyLesson = ::openLesson'),
  'Android course subjects must open the original course-scoped lesson list and lesson screen by default.');

assert.ok(
  androidNavigationContract.includes('databaseLessonIdFromArtifactId(artifactId: String): Long?') &&
  androidApp.includes('databaseLessonIdFromArtifactId(item.optString("artifactId"))') &&
  androidApp.includes('openLesson(JSONObject().put("id", databaseLessonId))') &&
  androidApp.includes('val openedFromStructuredCatalogue = structuredLessonMode') &&
  androidApp.includes('openedFromStructuredCatalogue || !lessonIsInCurrentList') &&
  androidApp.includes('val lessonSubjectCode = loadedLesson.textAny("subject_code", "subjectCode")') &&
  androidRouteCompatibilityTests.includes('databaseLessonIdFromArtifactId("db-lesson-42")') &&
  androidRouteCompatibilityTests.includes('assertEquals(null, databaseLessonIdFromArtifactId(artifactId))') &&
  androidRouteCompatibilityTests.includes('"artifact-v1"') &&
  androidRouteCompatibilityTests.includes('forEach { artifactId ->'),
  'Android relational lessons must use the legacy screen and matching course-scoped topic list; packaged artifacts keep structured routing.'
);

assert.ok(parentPage.includes('/programs') && parentPage.includes('/report?courseId=') &&
  parentPage.includes('childCourses.length>1') && parentPage.includes('Select an enrolled course'),
  'Parent reports must require explicit course selection for multi-course children.');
// Re-audited parent-child lifecycle must use course entitlements, not legacy subject tracks.
const parentChildrenPage = read('../app/parent/children/page.tsx');
const androidChildCreate = read('../../android/app/src/main/java/com/quantaedge/android/ui/ParentChildCreateScreen.kt');
const androidChildManage = read('../../android/app/src/main/java/com/quantaedge/android/ui/ParentChildManageScreen.kt');
const guardianApi = read('../../../services/api/src/main/java/com/quantaedge/api/GuardianController.java');
const tenantContextService = read('../../../services/api/src/main/java/com/quantaedge/api/InstitutionTenantContextService.java');
const authFilter = read('../../../services/api/src/main/java/com/quantaedge/api/AuthFilter.java');
const authService = read('../../../services/api/src/main/java/com/quantaedge/api/AuthService.java');
assert.ok(parentPage.includes('/certificates?courseId=') && parentPage.includes('/view?courseId=') && parentPage.includes('/download?courseId='),
  'Parent certificate history, preview and downloads must be scoped to the selected child course.');
assert.ok(androidApp.includes('/certificates?courseId=\" + courseId') && androidApp.includes('/view?courseId=\" + courseId') && androidApp.includes('/download?courseId=\" + courseId'),
  'Android parent certificate history and artifacts must use the selected child course.');
assert.ok(parentChildrenPage.includes('course.free_offer') && parentChildrenPage.includes('/parent/courses'),
  'Web child management must directly assign only free institution courses and route paid offers to Family Marketplace.');
assert.ok(androidApp.includes('onAssignCourse = ::assignCourseToChild') && androidApp.includes('courses = selectedChildCourses'),
  'Android child management must pass the child course catalogue to its course-assignment UI.');
assert.ok(androidChildCreate.includes('onCreate: (String, String, String, String, String, Boolean) -> Unit') && !androidChildCreate.includes('trackCodes'),
  'Child creation must not require legacy subject-only access selection.');
assert.ok(androidChildManage.includes('free_offer') && androidChildManage.includes('onAssignCourse') && androidChildManage.includes('Family Marketplace') && !androidChildManage.includes('onSaveTracks') && !androidChildManage.includes('trackCodes'),
  'Android child management must assign institution courses rather than edit Maths/Science tracks.');
assert.ok(guardianApi.includes('as course_names') && !guardianApi.includes('as track_codes'),
  'Parent child summaries must expose assigned course names, not legacy subject-track summaries.');
assert.ok(authFilter.includes('assertArchivedGuardianChildMembership') && authFilter.includes('restoringArchivedChild') &&
  tenantContextService.includes('gs.active=false') && tenantContextService.includes('assertArchivedGuardianChildMembership'),
  'Archived-child restoration must have its own selected-tenant authorization path without weakening active-child routes.');
assert.ok(authService.includes('LEARNER_ACCOUNT_CONVERSION') && authService.includes('ON CONFLICT(institution_id,user_id) DO NOTHING'),
  'Independent learner conversion must mirror active institution memberships without reactivating removed memberships.');
assert.ok(androidApp.includes('Course data could not be refreshed. Your selected course is preserved; please retry.') &&
  androidApp.includes('if (studentProgramsLoadFailed) {'),
  'Android startup must preserve primary course focus during a temporary course-catalogue outage.');

const textbooksClient = read('../app/student/textbooks/TextbooksClient.tsx');
assert.ok(textbooksClient.includes('displayChapterTitle(item, learningLanguage === \'en\')'), 'Textbook chapter titles must follow selected content language.');
const scopeComponent = read('../app/student/InstitutionCourseSubjectScope.tsx');
assert.ok(scopeComponent.includes('useTenantBranding') && scopeComponent.includes('activeCourse?.subjects') && scopeComponent.includes('brand?.displayName'), 'Shared learner scope must display the validated institution and derive subjects from the selected course.');
assert.ok(practiceClient.includes('subjectCode={practiceSubjectFilter}') &&
  practiceClient.includes('setPracticeSubjectFilter(next)') &&
  practiceClient.includes('pagedPracticeGroups.map(group=>') &&
  practiceClient.includes('practiceCatalogueEntries=useMemo<PracticeCatalogueEntry[]>') &&
  practiceClient.includes('PremiumPagination') &&
  practiceClient.includes("allSubjectsLabel={locale==='english'?'All subjects':'सभी विषय'}") &&
  practiceClient.includes('practiceSubjectGroups.filter(group=>group.code===practiceSubjectFilter)'),
  'Practice must retain course-derived subject filtering and URL state while rendering its paginated sequence through the shared pagination component.');
assert.ok(css.includes('.qe-practice-catalogue .qe-practice-course-subject-scope select') &&
  css.includes('.qe-practice-catalogue .qe-practice-course-subject-scope select[data-selected="true"]') &&
  css.includes('.qe-practice-next-row.is-eligible') &&
  css.includes('var(--qe-color-surface-elevated)') &&
  css.includes('var(--qe-color-primary-container)') &&
  css.includes('var(--qe-color-text-muted)') &&
  css.includes('var(--qe-color-focus)') &&
  css.includes('var(--qe-color-success-container)'),
  'Practice course/subject controls and sequence cards must use the shared semantic theme tokens for surfaces, text, focus and status in light and dark appearances.');
assert.ok(learningController.includes('as practice_question_count') &&
  learningController.includes("count(*) filter(where q.question_type<>'MCQ') as practice_question_count"),
  'The practice sequence must be based on the count of approved non-MCQ questions from the selected lesson catalogue.');
assert.ok(testsClient.includes('InstitutionCourseSubjectScope') && testsClient.includes('resultsParams.set(\'subjectCode\',subjectFilter)'), 'Tests and results must carry selected course and subject scope.');
const studentClassesScope = read('../app/student/classes/ClassesClient.tsx');
assert.ok(studentClassesScope.includes('InstitutionCourseSubjectScope') && studentClassesScope.includes('params.set(\'courseId\',String(activeCourseId))'), 'Live and recorded classes must be scoped to the selected course and its subjects.');
assert.ok(textbooksClient.includes('qe:student:textbooks:open:\' + libraryStorageScope') && !textbooksClient.includes('qe:student:textbooks:open:\' + classCode'), 'Textbook expansion state must be namespaced by course, not legacy class.');
const androidLearningClassesScreen = read('../../android/app/src/main/java/com/quantaedge/android/ui/LearningClassesScreen.kt');
assert.ok(androidLearningClassesScreen.includes('subjects: List<JSONObject>') && !androidLearningClassesScreen.includes('if (subject == "maths")') && !androidLearningClassesScreen.includes('if (subject == "science")'), 'Android live classes must derive the subject filter from the selected course rather than hardcoded subject codes.');
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

// End-to-end provider contract across Admin, student web, API and Android.
// Source assertions complement builds/unit tests; they do not claim live-account/device E2E.
const adminClasses = read('../../admin/app/classes/page.tsx');
const studentClasses = read('../app/student/classes/ClassesClient.tsx');
const assignedResources = read('../app/student/structured-lessons/AssignedLearningResources.tsx');
// Reuse androidApp loaded above for the shared navigation contract.
const androidLessonResources = read('../../android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonResourcesPanel.kt');
const androidClasses = read('../../android/app/src/main/java/com/quantaedge/android/ui/LearningClassesScreen.kt');
const androidBroadcastPlayer = read('../../android/app/src/main/java/com/quantaedge/android/ui/LiveBroadcastPlayerScreen.kt');
const androidRecordedPlayer = read('../../android/app/src/main/java/com/quantaedge/android/ui/RecordedPlayerScreen.kt');
const offlineVideoContract = read('../../android/app/src/main/java/com/quantaedge/android/data/OfflineLearningVideoContract.kt');
const offlineVideoStore = read('../../android/app/src/main/java/com/quantaedge/android/data/OfflineLearningVideoStore.kt');
const offlineVideoWorker = read('../../android/app/src/main/java/com/quantaedge/android/data/OfflineLearningVideoDownloadWorker.kt');
const offlineVideoTests = read('../../android/app/src/test/java/com/quantaedge/android/data/OfflineLearningVideoContractTest.kt');
const learningClassesApi = read('../../../services/api/src/main/java/com/quantaedge/api/LearningClassController.java');
const learningClassesApiTests = read('../../../services/api/src/test/java/com/quantaedge/api/LearningClassControllerTest.java');

assert.ok(adminClasses.includes('value="YOUTUBE_LIVE"') && adminClasses.includes("broadcastUrl:provider==='YOUTUBE_LIVE'?joinUrl.trim():null"), 'Admin must save the public YouTube broadcast reference separately.');
assert.ok(adminClasses.includes('type="number" min={1} max={1440} step={1}') && adminClasses.includes('checked={allowJoinAnytime}') && adminClasses.includes('joinWindowMinutes:allowJoinAnytime?0:Number(joinWindowMinutes)'), 'Admin must allow arbitrary minute entry or explicitly enable anytime joining for new sessions.');
assert.ok(adminClasses.includes('joinWindowAnytimeDrafts[session.session_id]') && adminClasses.includes('const value=allowAnytime?0:Number(raw)'), 'Admin must preserve custom minutes or anytime selection when editing scheduled sessions.');
assert.ok(studentClasses.includes('session.join_window_minutes') && studentClasses.includes('joinWindowMinutes*60_000') && !studentClasses.includes('start<=now+15*60*1000'), 'Student web must follow the saved Admin join window instead of hardcoding 15 minutes.');
assert.ok(androidClasses.includes('isLiveClassJoinWindowOpen') && androidClasses.includes('isLiveClassWaitingForJoinWindow') && androidClasses.includes('optInt("join_window_minutes"'), 'Android must use the same saved join window and explain when joining is not open.');
assert.ok(learningClassesApi.includes('cs.id as session_id,cs.title,cs.description,cs.provider,cs.youtube_video_id,cs.starts_at,cs.ends_at,cs.status,cs.join_window_minutes'), 'Student session API must return the saved Admin join window to clients.');
assert.ok(adminClasses.includes("tRef.current('admin.classes.scheduleSecureMissingFields'));return;}"), 'Admin scheduling validation must remain syntactically valid.');
assert.ok(studentClasses.includes("'/api/v1/learning/live-classes/'+session.session_id+'/join?courseId='+activeCourseId") && studentClasses.includes("'/api/v1/learning/live-classes/'+sessionId+'/join?courseId='+activeCourseId") && studentClasses.includes('youtube-nocookie.com/embed/') && studentClasses.includes("activeJoined.provider==='YOUTUBE_LIVE'"), 'Student web must use course-scoped authorized join before showing/reopening the YouTube embed.');
assert.ok(learningClassesApi.includes('result.put("youtubeVideoId", session.get("youtube_video_id"))') && learningClassesApi.includes("cs.provider='YOUTUBE_LIVE' or cs.private_join_url is not null") && learningClassesApi.includes('insert into learning_class_attendance'), 'API must return video ID separately from private URLs and persist attendance.');
assert.ok(learningClassesApiTests.includes('youtubeBroadcastJoinReturnsVideoIdAndRecordsAttendanceWithoutMeetingUrl'), 'API must test the YouTube join/attendance contract.');
assert.ok(androidClasses.includes('item.textAny("provider") == "YOUTUBE_LIVE"') && androidClasses.includes('t("Watch live broadcast", "लाइव प्रसारण देखें")'), 'Android class catalogue must support YouTube start/reopen.');
assert.ok(androidBroadcastPlayer.includes('isValidYouTubeVideoId(videoId)') && androidBroadcastPlayer.includes('https://www.youtube-nocookie.com/embed/$videoId') && androidBroadcastPlayer.includes('settings.allowFileAccess = false') && androidBroadcastPlayer.includes('settings.allowContentAccess = false'), 'Android must validate the broadcast ID and restrict the in-app player.');
assert.ok(androidRecordedPlayer.includes('/api/v1/learning/recorded-classes/') && androidRecordedPlayer.includes('offlineFile') && androidRecordedPlayer.includes('DefaultDataSource.Factory') && studentClasses.includes('/stream') && adminClasses.includes('/api/v1/admin/recorded-classes'), 'Admin upload, web stream, Android online playback and local-file replay contracts must remain wired.');
assert.ok(learningClassesApi.includes('left join lateral (') && learningClassesApi.includes('download.resource_id as download_resource_id') && learningClassesApi.includes('r.download_allowed=true') && learningClassesApi.includes("a.status='PUBLISHED'"), 'Android download references must originate only from a published catalogue placement with downloads explicitly enabled.');
assert.ok(learningClassesApiTests.includes('recordedListingCarriesPublishedDownloadReferenceOnlyWhenCatalogueAllowsIt'), 'API must cover recorded-class download metadata.');
assert.ok(offlineVideoWorker.includes('Range') && offlineVideoWorker.includes('Content-Range') && offlineVideoWorker.includes('sha256(partial)') && offlineVideoWorker.includes('authenticatedHttpClient') && offlineVideoWorker.includes('markComplete'), 'Offline transfer must use authenticated resumable HTTP and checksum verification before marking a file complete.');
assert.ok(offlineVideoStore.includes('filesDir, "offline_learning_videos/"') && offlineVideoStore.includes('removeOfflineCopy') && offlineVideoStore.includes('pendingProgressUploads'), 'Offline video files must stay in account-namespaced app-private storage, support deletion and preserve unsynced progress.');
assert.ok(offlineVideoContract.includes('MAX_VIDEO_BYTES') && offlineVideoContract.includes('parseContentRange') && offlineVideoTests.includes('interruptedTransferCanAppendOnlyAtTheExactAuthorizedOffset'), 'Offline transfer size/range boundaries must have unit coverage.');
assert.ok(androidClasses.includes('offlineDownloadedIds') && androidClasses.includes('onDownloadRecording') && androidClasses.includes('onRemoveOfflineRecording'), 'Android recorded-class UI must expose permitted download, local playback state and local-copy removal.');
assert.ok(androidApp.includes('StructuredLessonResourcesPanel(') &&
  androidApp.includes('onOpenDocument = ::openAssignedLearningDocument') &&
  androidApp.includes('onOpenLearningDocument = onOpenDocument'),
  'Android structured lessons must expose published resources and forward the protected document-reader callback through the route helper.');
assert.ok(androidLessonResources.includes('/api/v1/learning/resources?artifactId=') && androidLessonResources.includes('/progress?assignmentId=') && androidLessonResources.includes('renderPdfPreviewPage'), 'Android lesson resources must load only assigned published resources, save media progress and preview private PDFs in-app.');
assert.ok(androidLessonResources.includes('downloadAllowed') && androidLessonResources.includes('downloadUrl') && androidLessonResources.includes('allowDownload = true') && androidLessonResources.includes('openLocalFile'), 'Android may hand a resource to an external file viewer only through the API-supplied download URL when downloads are enabled.');
assert.ok(assignedResources.includes("item.streamUrl && !isVideo && !isAudio && kind !== 'IMAGE'") && assignedResources.includes('Open resource online'), 'Student web must be able to open a private assigned document online even when download is disabled.');




// Native messaging contract across API, student web, staff moderation and Android.
const studentChat = read('../app/student/classes/LearningChatPanel.tsx');
const staffChat = read('../../admin/app/classes/StaffLearningChatPanel.tsx');
const messagingApi = read('../../../services/api/src/main/java/com/quantaedge/api/LearningMessagingController.java');
const messagingService = read('../../../services/api/src/main/java/com/quantaedge/api/LearningMessagingService.java');
const messagingSocket = read('../../../services/api/src/main/java/com/quantaedge/api/LearningMessagingWebSocketConfig.java');
const messagingSchema = read('../../../services/api/src/main/resources/db/migration/V112__durable_learning_messaging.sql');
assert.ok(studentChat.includes('/learning/live-classes/') && studentChat.includes('/messages') && studentChat.includes('clientMessageId'), 'Student web messaging must open a class conversation, load history and use retry-safe sends.');
assert.ok(staffChat.includes('/moderation') && staffChat.includes('/reports') && staffChat.includes('/settings') && staffChat.includes('/mute'), 'Staff messaging must expose moderation, reports, slow mode and mute routes.');
assert.ok(messagingApi.includes('/learning/live-classes/{sessionId}/conversations') && messagingApi.includes('/learning/conversations/{conversationId}/messages'), 'The durable messaging REST endpoints must be present.');
assert.ok(messagingService.includes('student_track_enrollment') && messagingService.includes('teaching_assignment_id') && messagingService.includes('clientMessageId'), 'Messaging access must be bound to enrollment/assignment and idempotent message IDs.');
assert.ok(messagingSocket.includes('assertCanReadConversation') && messagingSocket.includes('/user/queue/learning-conversations/'), 'Realtime chat subscriptions must be membership-checked and conversation-scoped.');
assert.ok(messagingSchema.includes('learning_conversation_read_state') && messagingSchema.includes('learning_message_report') && messagingSchema.includes('learning_message_audit'), 'Messaging persistence must include read state, reports and moderation audit.');
assert.ok(androidClasses.includes('LearningChatPanel(sessionId = chatSessionId') && androidClasses.includes('/api/v1/learning/conversations/$activeConversationId/messages'), 'Android lecture cards must connect to durable messaging history and send endpoints.');

assert.ok(studentChat.includes('new WebSocket') && studentChat.includes('retryDelay') && studentChat.includes('SUBSCRIBE'), 'Student web chat must subscribe to realtime messages and retry failed WebSocket connections.');
assert.ok(staffChat.includes('new WebSocket') && staffChat.includes('retryDelay') && staffChat.includes('SUBSCRIBE'), 'Staff moderation chat must subscribe to realtime messages and retry failed WebSocket connections.');
assert.ok(androidClasses.includes('newWebSocket') && androidClasses.includes('realtimeReconnectToken') && androidClasses.includes('onClosed'), 'Android chat must subscribe to realtime messages and retry failed WebSocket connections.');

const messagingHandshake = read('../../../services/api/src/main/java/com/quantaedge/api/LearningMessagingHandshakeInterceptor.java');
assert.ok(messagingHandshake.includes('getOrEmpty(HttpHeaders.COOKIE)') && !messagingHandshake.includes('request.getCookies()'), 'WebSocket handshake must read cookies from ServerHttpRequest headers, not a nonexistent cookie API.');


// Issue #66 regression guards for course purchase ownership and retry-safe checkout.
const courseEnrollmentApiContract = read('../../../services/api/src/main/java/com/quantaedge/api/StudentProgramController.java');
const courseMarketplaceApiContract = read('../../../services/api/src/main/java/com/quantaedge/api/CourseMarketplaceController.java');
const coursePaymentSchemaContract = read('../../../services/api/src/main/resources/db/migration/V114__course_payment_order_idempotency.sql');
const androidApiClientContract = read('../../android/app/src/main/java/com/quantaedge/android/data/ApiClient.kt');
const androidMarketplaceContract = read('../../android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidFloatingTutorContract = read('../../android/app/src/main/java/com/quantaedge/android/ui/FloatingTutorDock.kt');
const studentMarketplaceContract = read('../app/student/marketplace/page.tsx');

assert.ok(courseEnrollmentApiContract.includes("offer.status='PUBLISHED'") &&
  courseEnrollmentApiContract.includes("offer.price_paise=0") &&
  courseEnrollmentApiContract.includes("offer.currency='INR'") &&
  courseEnrollmentApiContract.includes('offer.institution_id=?') &&
  courseEnrollmentApiContract.includes('institution_course_assignment assignment') &&
  courseEnrollmentApiContract.includes('course_purchase cp') &&
  courseEnrollmentApiContract.includes("e.status in ('ACTIVE','PAUSED')"),
  'New self-enrolment must require an explicitly published free offer assigned to the selected institution; existing institution-scoped enrollment/purchase may be resumed.');
assert.ok(courseMarketplaceApiContract.includes('@RequestHeader(value = "Idempotency-Key", required = false)') &&
  courseMarketplaceApiContract.includes("status in ('CREATING','PENDING')") &&
  courseMarketplaceApiContract.includes('client_request_key') &&
  courseMarketplaceApiContract.includes('isReusablePendingOrder'),
  'Checkout order creation must recover stale creation rows, validate idempotency keys and reuse an existing pending provider order.');
assert.ok(coursePaymentSchemaContract.includes('client_request_key varchar(64)') &&
  coursePaymentSchemaContract.includes('uq_course_payment_order_request_key'),
  'Checkout idempotency must be persisted and unique for each learner/course/request key.');
assert.ok(studentMarketplaceContract.includes("'Idempotency-Key': crypto.randomUUID()"),
  'Student web checkout must supply a unique idempotency key to the server.');
assert.ok(androidApiClientContract.includes('additionalHeaders: Map<String, String>') &&
  androidApiClientContract.includes('additionalHeaders.forEach'),
  'Android API client must support the request header required for checkout idempotency.');
assert.ok(androidMarketplaceContract.includes('"Idempotency-Key" to java.util.UUID.randomUUID().toString()'),
  'Android course checkout must supply an idempotency key.');
assert.ok(androidFloatingTutorContract.includes('userResized') &&
  androidFloatingTutorContract.includes('if (compact && !userResized)') &&
  androidFloatingTutorContract.includes('userResized = true') &&
  androidFloatingTutorContract.includes('availableWidth = (maxWidth - 16.dp).coerceAtLeast(1.dp)') &&
  androidFloatingTutorContract.includes('coerceIn(minOf(260f, maxResizeWidth), maxResizeWidth)'),
  'The floating tutor must honour resize gestures and keep the panel within very small, split-screen and IME-reduced viewports.');
assert.ok(androidFloatingTutorContract.includes('if (!opened || minimized)') &&
  androidFloatingTutorContract.includes('if (opened) onRestore() else onOpen()') &&
  androidFloatingTutorContract.includes('val bubbleBottomInset = 84f'),
  'The closed launcher and minimized tutor must share one bubble owner, positioned above the lesson action row by default.');



// Issue #66 dashboard live-class schedule and future-session visibility contracts.
const issue66TimestampFormatterContract = read('../app/components/formatUserTimestamp.ts');
const issue66StudentDashboardContract = read('../app/student/page.tsx');
const issue66LiveClassApiContract = read('../../../services/api/src/main/java/com/quantaedge/api/LearningClassController.java');
const issue66LiveClassTestsContract = read('../../../services/api/src/test/java/com/quantaedge/api/LearningClassControllerTest.java');
assert.ok(issue66StudentDashboardContract.includes('/api/v1/learning/live-classes?') &&
  issue66StudentDashboardContract.includes('&includeFuture=true&limit=100&offset=0') &&
  issue66StudentDashboardContract.includes('formatUserTimestamp(upcoming ? item.starts_at : item.ends_at)') &&
  issue66StudentDashboardContract.includes('dashboardNow - 3 * 24 * 60 * 60 * 1000') &&
  issue66StudentDashboardContract.includes('dashboardClassesBySubject'),
  'The student dashboard must load the full course-scoped live schedule, show only current/future and the preceding three days, group by subject, and format times consistently.');
assert.ok(issue66LiveClassApiContract.includes('@GetMapping("/learning/live-classes")') &&
  issue66LiveClassApiContract.includes('@RequestParam(defaultValue="false") Boolean includeFuture') &&
  issue66LiveClassApiContract.includes("(?=true or cs.status<>'SCHEDULED' or cs.starts_at<=now()+interval '24 hours')") &&
  issue66LiveClassApiContract.includes("cs.ends_at>now()-interval '60 days'") &&
  issue66LiveClassApiContract.includes("Boolean.TRUE.equals(includeFuture)"),
  'Full schedule visibility must be explicit for the dashboard while the normal class catalogue retains its 24-hour upcoming window and 60-day history boundary.');
assert.ok(androidApp.includes('&includeFuture=true') &&
  issue66LiveClassTestsContract.includes('studentClassCatalogueCanIncludeFarFutureScheduledSessionsWithoutChangingDefaultWindow') &&
  issue66LiveClassTestsContract.includes('eq(7L), eq(1L), eq(true), eq(""), eq(""), eq(71L), eq(71L), eq(25), eq(0)') &&
  issue66LiveClassTestsContract.includes('eq(Long.class), eq(7L), eq(1L), eq(true), eq(""), eq(""), eq(71L), eq(71L)'),
  'Android dashboard and API tests must exercise the full-schedule query mode.');
const issue66AndroidDashboardRefresh = androidApp.slice(
  androidApp.indexOf('suspend fun refreshDashboardClasses()'),
  androidApp.indexOf('suspend fun enterSignedInHome(')
);
assert.ok(issue66AndroidDashboardRefresh.includes('&includeFuture=true') &&
  issue66AndroidDashboardRefresh.includes('/api/v1/learning/live-classes?subjectCode=') &&
  !issue66AndroidDashboardRefresh.includes('/api/v1/learning/recorded-classes?subjectCode='),
  'Android dashboard Live Classes must request the full upcoming session schedule without mixing standalone recording-library items into live sessions.');
assert.ok(androidApp.includes('courseSessionEndTimestamp(it)') &&
  androidApp.includes('groupClassesBySubject(items: List<JSONObject>): Map<String, List<JSONObject>>') &&
  androidApp.includes('.toSortedMap().entries.take(3).associate { it.key to it.value }') &&
  androidApp.includes('showSubject: Boolean = true') &&
  androidApp.includes('item.optBoolean("has_recording", item.optBoolean("hasRecording"))'),
  'Android dashboard must include sessions currently in progress, use end timestamps for recency, group sessions by subject with a Map-compatible return type and disclose recording availability.');
assert.ok(issue66TimestampFormatterContract.includes("timeZone: 'Asia/Kolkata'") &&
  issue66TimestampFormatterContract.includes(String.raw`formattedTime.replace(/\b(am|pm)\b/i`),
  'Web timestamps must use the agreed India timezone and correctly normalize AM/PM casing.');
assert.ok(androidApp.includes('ZoneId.of("Asia/Kolkata")') &&
  !androidApp.includes('ZoneId.systemDefault()'),
  'Android dashboard timestamps and local datetime fallback must use the same agreed India timezone as web.');


assert.ok(studentEmptyState.includes('aria-live="polite"') && studentEmptyState.includes('primaryActionHref') &&
  studentEmptyState.includes('secondaryActionHref') && studentEmptyState.includes('onRetry'),
  'Student empty states must be accessible, visually reusable and offer relevant navigation/retry actions.');
assert.ok(studentMarketplacePage.includes('!loading && !loadError && offers.length === 0') &&
  studentMarketplacePage.includes('student.empty.marketplace.title') &&
  studentMarketplacePage.includes('onRetry={() => void loadOffers()}') &&
  studentMarketplacePage.includes('secondaryActionHref="/student/courses"'),
  'A genuinely empty marketplace must show a deliberate fallback with retry and a route to My Courses; load errors must remain a separate state.');
assert.ok(studentCoursesPage.includes('!loading && !error && owned.length === 0') &&
  studentCoursesPage.includes('student.empty.courses.title') &&
  studentCoursesPage.includes('primaryActionHref="/student/marketplace"'),
  'The course list must have a localized empty state only after a successful empty response.');
for (const [source, label] of [
  [structuredLessonsClient, 'Structured lessons'],
  [practiceClient, 'Practice'],
  [studentProgressPage, 'Progress'],
  [studentBooksClient, 'Books and chapters'],
  [studentClassesClient, 'Live and recorded classes'],
  [studentDiagnosticPage, 'Diagnostic'],
  [studentCertificatesPage, 'Certificates'],
  [testsClient, 'Tests and results'],
  [studentLessonDetailClient, 'Nested lesson detail'],
]) {
  assert.ok(source.includes('StudentEmptyState') && source.includes('noEnrolledCourses'),
    label + ' must use the shared no-enrolment fallback rather than an API-error or content-empty message.');
}
assert.ok(studentCertificatesPage.includes('!requestedCertificateId') &&
  studentCertificatesPage.includes('requestedCertificateId'),
  'The no-course certificate fallback must not intercept direct certificate lookup/verification links.');
assert.ok(studentClassesClient.includes(':(error||courseError)?null:filteredSessions.length===0') &&
  studentClassesClient.includes(':(error||courseError)?null:filteredRecordings.length===0'),
  'Live and recorded class empty messages must not appear alongside failed requests.');
assert.ok(testsClient.includes(':error?null:filteredTests.length===0') &&
  testsClient.includes(':error?null:results.length===0'),
  'Test and result empty messages must not misreport API failures as zero data.');
assert.ok(certificatesPage.includes('error && certificates.length === 0 ? null : !certificates.length ?'),
  'Certificate inventory must not say there are no certificates when its data could not be loaded.');
assert.ok(studentBooksClient.includes('!error && !courseError && visibleDocuments.length === 0'),
  'The textbook library must distinguish failed requests from a successfully empty published-resource list.');
assert.ok(structuredLessonsClient.includes('qe:student:last-opened-lesson:course-') &&
  structuredLessonsClient.includes("if (!lessonId)") &&
  structuredLessonsClient.includes('There are no published lessons available in this course yet.') &&
  structuredLessonsClient.includes('Open Lessons and Topics') &&
  structuredLessonsClient.includes("router.replace('/student/learn?' + target.toString())"),
  'Resume Lesson must handle missing history safely and offer a route to Lessons and Topics without opening a second catalogue.');
assert.ok(studentClassesClient.includes('StudentEmptyState icon="classes"') &&
  studentClassesClient.includes("retryLabel={translateUi(locale, 'legacy.web.refresh.e9337860')}"),
  'Empty live and recorded classes must use the shared card and offer a retry.');
assert.ok(testsClient.includes('StudentEmptyState icon="tests"') &&
  testsClient.includes('primaryActionHref="/student/learn"'),
  'Empty tests must offer a route into lessons, and test results must offer a refresh action.');
assert.ok(studentBooksClient.includes('StudentEmptyState icon="book"') &&
  studentBooksClient.includes("primaryActionLabel={translateUi(locale, 'legacy.web.continue.with.lessons.2a65104e')}"),
  'An enrolled course with no published PDFs needs a shared library empty state linking back to lessons.');
assert.ok(certificatesPage.includes("title={translateUi(locale, 'legacy.web.no.certificates.yet.46af6a77')}") &&
  certificatesPage.includes('primaryActionLabel={<UiText message="web.navigation.student.testsResults" />}'),
  'An enrolled course with no issued certificates needs a shared empty state leading to Tests & Results.');

assert.ok(androidEnrollmentEmptyState.includes('CardDefaults.cardElevation') &&
  androidEnrollmentEmptyState.includes('primaryLabel') && androidEnrollmentEmptyState.includes('onPrimary'),
  'Android must provide the same reusable, themed student empty-state card and actionable controls.');
assert.ok(androidLessonRoute.includes('if (studentHasNoCourses())') &&
  androidDiagnosticRoute.includes('if (studentHasNoCourses())') &&
  androidAssessmentResultsRoute.includes('if (studentHasNoCourses())') &&
  androidLeaderboardRoute.includes('if (studentHasNoCourses())') &&
  androidApp.includes('noEnrolledCourses = studentHasNoCourses()') &&
  androidApp.includes('onMarketplace = ::openMarketplace'),
  'Android nested lesson, diagnostic, test results, leaderboard and course-dependent screens must route confirmed no-enrolment states to Marketplace.');
assert.ok(androidMarketplaceScreen.includes('} else if (!loading && offers.isEmpty())') &&
  androidMarketplaceScreen.includes('onAllCourses') && androidMarketplaceScreen.includes('onRefresh'),
  'Android Marketplace must differentiate empty offers from failed loads and provide refresh and My Courses actions.');
assert.ok(androidAllCoursesScreen.includes('if (programsLoadFailed)') &&
  androidAllCoursesScreen.includes('StudentEnrollmentEmptyState'),
  'Android My Courses must keep course-fetch failures separate from a successfully empty enrolment list.');
assert.ok(androidClassesScreen.includes('noEnrolledCourses: Boolean = false') &&
  androidClassesScreen.includes('StudentEnrollmentEmptyState') &&
  androidCertificatesScreen.includes('if (noEnrolledCourses && !showScanner') &&
  androidCertificatesScreen.includes('secondaryLabel = localizedUiText("Scan certificate")') &&
  androidCertificatesScreen.includes('verificationCode.isBlank()') &&
  androidCertificatesScreen.includes('courseAccessLoadError'),
  'Android live/recorded classes and certificates must offer a deliberate no-enrolment state, preserve course lookup errors, and keep public certificate scanning/verification usable without enrolment.');
assert.ok(androidLocalization.includes('empty_leaderboard_title') &&
  androidLocalization.includes('empty_course_access_load_error') &&
  androidLocalization.includes('empty_marketplace_title'),
  'Android empty-state copy must be available through the central localization phrase catalogue.');

for (const relative of ['english.json', 'hinglish.json']) {
  const catalog = JSON.parse(read('../app/i18n/catalogs/' + relative));
  for (const key of [
    'student.empty.marketplace.title','student.empty.marketplace.message','student.empty.marketplace.refresh',
    'student.empty.courses.title','student.empty.courses.message','student.empty.learn.title','student.empty.learn.message',
    'student.empty.practice.title','student.empty.practice.message','student.empty.progress.title','student.empty.progress.message',
    'student.empty.books.title','student.empty.books.message','student.empty.classes.title','student.empty.classes.message',
    'student.empty.tests.title','student.empty.tests.message','student.empty.certificates.title','student.empty.certificates.message',
    'student.empty.diagnostic.title','student.empty.diagnostic.message','student.empty.lesson.title','student.empty.lesson.message',
  ]) assert.ok(typeof catalog[key] === 'string' && catalog[key].trim().length > 0,
    'Missing localized student empty-state copy: ' + key + ' in ' + relative);
}

const visualAuditGlobals = read('../app/globals.css');
const visualAuditTestsCss = read('../app/student/tests/Tests.module.css');
const visualAuditTextbooksCss = read('../app/student/textbooks/textbooks.css');
const visualAuditBillingCss = read('../app/components/BillingProfileEditor.module.css');
const visualAuditInboxCss = read('../app/components/NotificationInbox.module.css');
const visualAuditScopeCss = read('../app/student/InstitutionCourseSubjectScope.module.css');

assert.ok(
  visualAuditGlobals.includes('html[data-qe-appearance] .parent-courses-page__heading') &&
  visualAuditGlobals.includes('var(--qe-color-on-success-container)') &&
  visualAuditGlobals.includes('var(--qe-color-on-error-container)') &&
  visualAuditGlobals.includes('html[data-qe-appearance] .auth-method-switch'),
  'Family and authentication screens must use tenant-aware surfaces, foregrounds, and responsive shared-heading alignment.'
);
assert.ok(
  visualAuditTestsCss.includes('html[data-qe-appearance="dark"] .releasedResult') &&
  visualAuditTestsCss.includes('html[data-qe-appearance="dark"] .leaderboardFirst') &&
  visualAuditTestsCss.includes('var(--qe-color-on-success-container)'),
  'Released test results, exam navigation legend, and leaderboard surfaces must remain readable in dark mode.'
);
assert.ok(
  visualAuditTextbooksCss.includes('html[data-qe-appearance] .qe-chapter-copy h4') &&
  visualAuditTextbooksCss.includes('var(--qe-color-on-accent-container)') &&
  visualAuditTextbooksCss.includes('var(--qe-color-on-warning-container)'),
  'Textbook chapter copy and metadata chips must use semantic theme tokens in light and dark mode.'
);
assert.ok(
  visualAuditBillingCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditBillingCss.includes('var(--qe-color-on-success-container)') &&
  visualAuditBillingCss.includes('var(--qe-color-on-primary)'),
  'Billing forms must use generated contrast foregrounds for primary actions and feedback messages.'
);
assert.ok(
  visualAuditInboxCss.includes('var(--qe-color-on-accent)') &&
  visualAuditScopeCss.includes('color-mix(in srgb, var(--qe-color-focus'),
  'Notification badges and subject-scope focus indicators must respect tenant theme tokens; the rich renderer remains on its enforced shared stylesheet contract.'
);


const visualAuditPracticeAndMenuCss = read('../app/globals.css');
assert.ok(
  visualAuditPracticeAndMenuCss.includes('html[data-qe-appearance] .qe-practice-header') &&
  visualAuditPracticeAndMenuCss.includes('html[data-qe-appearance] .qe-practice-notice') &&
  visualAuditPracticeAndMenuCss.includes('var(--qe-color-on-primary-container)') &&
  visualAuditPracticeAndMenuCss.includes('html[data-qe-appearance] :where(.student-account-menu, .parent-account__menu)') &&
  visualAuditPracticeAndMenuCss.includes('var(--qe-color-on-error-container)'),
  'Practice question surfaces, notices, and both account menus must use institution-aware surface and contrast tokens in light and dark appearance.'
);


const visualAuditCertificateCss = read('../app/student/certificates/Certificates.module.css');
assert.ok(
  visualAuditCertificateCss.includes('html[data-qe-appearance] :where(.panel, .templateCard)') &&
  visualAuditCertificateCss.includes('html[data-qe-appearance] :where(.selected, .formatButtonActive)') &&
  visualAuditCertificateCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditCertificateCss.includes('var(--qe-color-on-success-container)') &&
  visualAuditCertificateCss.includes('html[data-qe-appearance] :where(.templateName, .templateDescription, .certificateInfo strong') &&
  visualAuditCertificateCss.includes('overflow-wrap: anywhere'),
  'Certificate app surfaces, selected controls, status messages, and long certificate titles must follow the tenant palette and wrap within their layout; document previews remain white.'
);


const visualAuditCoursesCss = read('../app/student/courses/courses.module.css');
assert.ok(
  visualAuditCoursesCss.includes('html[data-qe-appearance] .hero') &&
  visualAuditCoursesCss.includes('html[data-qe-appearance] :where(.courseCard, .empty)') &&
  visualAuditCoursesCss.includes('var(--qe-color-on-primary-container)') &&
  visualAuditCoursesCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditCoursesCss.includes('html[data-qe-appearance] .actions > *') &&
  visualAuditCoursesCss.includes('overflow-wrap: anywhere'),
  'Owned-course page surfaces, status controls, error messages, text wrapping, and action alignment must honour institution appearance in both modes.'
);


const visualAuditClassesCss = read('../app/student/classes/Classes.module.css');
assert.ok(
  visualAuditClassesCss.includes('html[data-qe-appearance] .shell') &&
  visualAuditClassesCss.includes('html[data-qe-appearance] :where(.statusLIVE, .notice, .joinedBanner)') &&
  visualAuditClassesCss.includes('var(--qe-color-on-primary-container)') &&
  visualAuditClassesCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditClassesCss.includes('html[data-qe-appearance] :where(.sessionGrid, .recordingGrid, .sessionCard') &&
  visualAuditClassesCss.includes('overflow-wrap: anywhere'),
  'Live-class lists, recording cards, player/chat controls, semantic status states, and long content must use the institution palette and safe wrapping in both appearances.'
);


const visualAuditTestsUniversalCss = read('../app/student/tests/Tests.module.css');
assert.ok(
  visualAuditTestsUniversalCss.includes('html[data-qe-appearance] .shell') &&
  visualAuditTestsUniversalCss.includes('html[data-qe-appearance] .optionSelected') &&
  visualAuditTestsUniversalCss.includes('html[data-qe-appearance] :where(.optionCorrect, .correctAnswerBox') &&
  visualAuditTestsUniversalCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditTestsUniversalCss.includes('html[data-qe-appearance] .leaderboardFirst') &&
  visualAuditTestsUniversalCss.includes('overflow-wrap: anywhere'),
  'Assessment catalogue, quiz selection/correctness states, released results, leaderboard and action layouts must use semantic theme tokens and safe text wrapping in both appearances.'
);


const visualAuditVerifyCss = read('../app/certificate/verify/Verify.module.css');
assert.ok(
  visualAuditVerifyCss.includes('html[data-qe-appearance] .card') &&
  visualAuditVerifyCss.includes('html[data-qe-appearance] .form input') &&
  visualAuditVerifyCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditVerifyCss.includes('var(--qe-color-on-success-container)') &&
  visualAuditVerifyCss.includes('html[data-qe-appearance] .scanControls > *') &&
  visualAuditVerifyCss.includes('overflow-wrap: anywhere'),
  'Public certificate verification surfaces, form focus, result statuses, and scanner controls must follow the active appearance and wrap safely.'
);



assert.ok(
  css.includes('.institution-portal [class*="sendButton"]') &&
  css.includes('var(--qe-color-on-accent, #fff)') &&
  css.includes('[class*="NotificationStudio_error"]') &&
  css.includes('var(--qe-color-on-error-container)'),
  'User Web institution notification and logo editors must retain contrast-aware accent foregrounds and theme-aware error states without diverging from shared editor styles.'
);


assert.ok(
  css.includes('a[class*="cta"], button[class*="cta"], [role="button"][class*="cta"]') &&
  css.includes('var(--qe-color-on-accent, var(--qe-color-on-primary, #fff))') &&
  css.includes('outline: 3px solid var(--qe-color-focus)'),
  'Rich notification action contrast and focus must be corrected in the User Web host without forking shared renderer sources.'
);


assert.ok(
  !visualAuditTestsUniversalCss.includes('color-scheme: light dark') &&
  visualAuditTestsUniversalCss.includes('html[data-qe-appearance] .resultScorePrimary') &&
  visualAuditTestsUniversalCss.includes('A numerical score is neutral information, not a pass/fail status.'),
  'Assessment controls must inherit the explicitly selected light/dark scheme, and raw scores must not receive an unconditional success state.'
);


const visualAuditInboxContrastCss = read('../app/components/NotificationInbox.module.css');
const visualAuditBillingContrastCss = read('../app/components/BillingProfileEditor.module.css');
const visualAuditEmptyStateContrastCss = read('../app/student/components/StudentEmptyState.module.css');
assert.ok(
  visualAuditInboxContrastCss.includes('var(--qe-color-accent-text,var(--qe-color-accent') &&
  visualAuditInboxContrastCss.includes('.error button') &&
  visualAuditBillingContrastCss.includes('var(--qe-color-on-primary-container,var(--qe-color-primary-text') &&
  visualAuditEmptyStateContrastCss.includes('var(--qe-color-primary-text, var(--qe-color-primary') &&
  css.includes('[class*="pushMedia"]'),
  'Notification inbox actions must use contrast-aware accent text, while billing disclosure icons, empty-state icons, and shared institution editor labels use semantic foreground tokens.'
);


const visualAuditStructuredLessonThemeCss = read('../app/student/structured-lessons/structured-lessons.css');
const visualAuditAssignedResourcesCss = read('../app/student/structured-lessons/AssignedLearningResources.module.css');
assert.ok(
  visualAuditStructuredLessonThemeCss.includes('color: var(--qe-color-on-primary-container)') &&
  visualAuditStructuredLessonThemeCss.includes('var(--qe-color-on-success-container)') &&
  visualAuditStructuredLessonThemeCss.includes('var(--qe-color-on-error-container)') &&
  visualAuditStructuredLessonThemeCss.includes('var(--qe-color-on-warning-container)') &&
  visualAuditStructuredLessonThemeCss.includes('.qe-structured-choice:has(input:checked)'),
  'Structured lesson selected choices, callouts and correctness/review feedback must use contrast-matched tenant container tokens.'
);
assert.ok(
  visualAuditAssignedResourcesCss.includes('color: var(--qe-color-on-primary-container') &&
  visualAuditAssignedResourcesCss.includes('flex-wrap: wrap') &&
  visualAuditAssignedResourcesCss.includes('overflow-wrap: anywhere'),
  'Assigned learning-resource headings, kind badges and actions must wrap without width collisions and use semantic foregrounds.'
);
assert.ok(
  css.includes('color-mix(in srgb, var(--qe-color-focus) 12%, transparent)') &&
  css.includes('color-mix(in srgb, var(--qe-color-focus) 16%, transparent)'),
  'Profile and course-focus rings must derive from the selected institution focus colour rather than a hard-coded orange.'
);


assert.ok(
  css.includes('color: var(--qe-color-on-primary-container);') &&
  css.includes('.qe-workspace-link.is-active') &&
  css.includes('.tutor-equation') &&
  css.includes('.parent-courses-page__count') &&
  css.includes('html[data-qe-appearance] .qe-language-switcher > span:first-child') &&
  css.includes('var(--qe-color-primary-text)'),
  'Active navigation, tinted badges, selected answer/tutor controls and language affordances must use the correct semantic foreground for their themed surface.'
);


assert.ok(
  css.includes('.topic-line > :first-child') &&
  css.includes('grid-template-columns: minmax(0, min(100px, 35%)) minmax(0, 1fr) minmax(36px, 45px)') &&
  css.includes('.parent-certificate-actions a') &&
  css.includes('white-space: normal') &&
  css.includes('overflow-wrap: anywhere'),
  'Student progress and parent report rows must wrap long localized topic names/counts, and certificate action links must fit narrow screens.'
);


const visualAuditScoreSemanticsCss = read('../app/student/tests/Tests.module.css');
assert.ok(
  visualAuditScoreSemanticsCss.includes('html[data-qe-appearance] .score > span') &&
  visualAuditScoreSemanticsCss.includes('A best-score percentage is a metric, not a pass/fail outcome.') &&
  !visualAuditScoreSemanticsCss.includes('html[data-qe-appearance] :where(.notice, .score > span)'),
  'Best-score percentages must stay neutral and theme-aware; only actual success notices use the success container.'
);


const visualAuditMarketplaceCardCss = read('../app/components/MarketplaceCourseCard.module.css');
const visualAuditBillingFocusCss = read('../app/components/BillingProfileEditor.module.css');
assert.ok(
  visualAuditMarketplaceCardCss.includes('color: var(--qe-color-on-primary-container, var(--qe-color-primary-text))') &&
  visualAuditMarketplaceCardCss.includes('color: var(--qe-color-primary-text, var(--qe-color-primary))'),
  'Marketplace course icon and price foregrounds must use the right semantic contrast tokens.'
);
assert.ok(
  visualAuditBillingFocusCss.includes('var(--qe-color-on-primary-container, var(--qe-color-primary-text') &&
  visualAuditBillingFocusCss.includes('color-mix(in srgb, var(--qe-color-focus) 14%, transparent)') &&
  css.includes('.qe-student-chapter > .qe-chapter-pdf-row:hover') &&
  css.includes('.institution-portal [class*="fieldGrid"]'),
  'Billing indicators, profile identity settings, course/resource hover surfaces and institution-portal editor focus rings must use generated theme contrast/focus colours.'
);


assert.ok(
  css.includes('[class*="RichNotificationRenderer_eyebrow"]') &&
  css.includes('[class*="RichNotificationRenderer_cardKicker"]') &&
  css.includes('var(--qe-color-accent-text, var(--qe-color-primary-text)) !important') &&
  css.includes('a[class*="cta"]'),
  'Rich notification CTA and accent labels must use institution contrast tokens in the User Web host while shared renderers remain byte-identical.'
);


const visualAuditScopeSelectedCss = read('../app/student/InstitutionCourseSubjectScope.module.css');
assert.ok(
  visualAuditScopeSelectedCss.includes('var(--qe-color-on-primary-container, var(--qe-color-primary-text') &&
  css.includes('[class*="RichNotificationRenderer_editorBlock"]:focus-visible') &&
  css.includes('outline: 3px solid var(--qe-color-focus)'),
  'Selected institution/course/subject controls and keyboard-focused rich-notification editor blocks must use tenant contrast and focus tokens.'
);


assert.ok(
  css.includes('html[data-qe-appearance] .tutor-modes button[aria-pressed="true"]:hover') &&
  css.includes('background: var(--qe-color-primary);') &&
  css.includes('color: var(--qe-color-on-primary);'),
  'A selected tutor mode must retain its solid-primary background and on-primary foreground during hover.'
);


assert.ok(
  css.includes('html[data-qe-appearance] .parent-certificate-actions a:hover') &&
  css.includes('background: var(--qe-color-primary);') &&
  css.includes('color: var(--qe-color-on-primary);'),
  'Hovered certificate links must retain the correct foreground when their surface changes from primaryContainer to primary.'
);

console.log('Navigation, localization, student empty-state fallbacks, live-class authorization, resumable offline-video, student resource parity, course checkout and issue #66 dashboard source contracts passed.');


assert.ok(tutorDock.includes('artifactId?: string') && tutorDock.includes('structuredQuestionId') &&
  tutorDock.includes('...(lessonId && lessonId > 0 ? { lessonId } : {})'),
  'The shared Web tutor dock must support both canonical database lessons and structured artifact contexts.');
assert.ok(tutorDock.lastIndexOf('useEffect(()') < tutorDock.indexOf('if (!open) return null;'),
  'All Web tutor hooks must be declared before conditional returns.');
assert.ok(learnClient.includes('return blocks.slice().sort((a,b) => a.sequence_no-b.sequence_no || a.id-b.id)') &&
  !learnClient.includes('LEARNER_BLOCK_STAGE') &&
  learnClient.includes('collectLegacyBlockText') &&
  learnClient.includes('const learningBlocks=useMemo(()=>orderedBlocks,[orderedBlocks]);') &&
  !learnClient.includes('recapBlock&&<section className="lesson-recap"'),
  'Every backend lesson block must render exactly once in sequence order, including unknown types and all recap blocks.');
assert.ok(learnClient.includes("Array.isArray(data.points) ? data.points : Array.isArray(data.keyPoints) ? data.keyPoints : []") &&
  learnClient.includes('const recapFallback = recapPoints.length ? [] : collectLegacyBlockText(data)') &&
  learnClient.includes('!recapPoints.length && !recapFallback.length'),
  'Legacy summary/recap blocks must show their content or a visible empty-content fallback, never silently render blank.');
assert.ok(structuredLessonContent.includes('collectStructuredBlockText') &&
  structuredLessonContent.includes('blocks.map(block =>') &&
  structuredLessonContent.includes('sort((a, b) => a.order - b.order') &&
  !structuredLessonContent.includes("if (!canDisplay && mode === 'student') return null;") &&
  structuredLessonContent.includes('qe-structured-unknown-block'),
  'Structured lesson blocks must keep backend order, show placeholders for blocked visuals and render unknown types safely.');
assert.ok(!learnClient.includes('qe-structured-optional-insert') &&
  !learnClient.includes('inlineStructuredLesson') &&
  !learnClient.includes('Optional structured lesson view') &&
  learnClient.includes('<TutorDock lessonId={lesson.id}') &&
  learnClient.includes('learningBlocks.map(block=><Block') &&
  learnClient.includes('<section className="concept-card practice-launch-card">') &&
  learnClient.includes('setTutorQuestionId(undefined);setTutorOpen(true)'),
  'The original Learn screen must use the canonical ordered blocks, retain its existing MCQ/practice/tutor controls, and omit the parallel optional renderer.');
assert.ok(structuredLessonDetailClient.includes('<TutorDock artifactId={artifactId}') &&
  structuredLessonDetailClient.includes('Ask the AI tutor about this topic'),
  'The standalone structured lesson detail must launch the shared tutor with its stable artifact ID.');
assert.ok(tutorController.includes('startStructuredSession') &&
  tutorController.includes('structuredQuestionId') &&
  tutorService.includes('COURSE, SUBJECT, CHAPTER AND CURRENT LESSON/TOPIC') &&
  tutorService.includes('boolean useLessonContext=true') &&
  tutorService.includes('ensureStructuredArtifactAccess'),
  'The API must resolve tenant/course scoped context server-side and include it for every tutor inference turn.');
assert.ok(tutorRetriever.includes('retrieveStructured') &&
  tutorRetriever.includes('ts.course_id is not null') &&
  tutorMigration.includes('structured_artifact_id') &&
  tutorMigration.includes('tutor_session_context_source_check'),
  'Tutor retrieval must respect the selected course and persist structured artifact session scope explicitly.');
assert.ok(tutorService.includes('key.contains("answer")') &&
  tutorService.includes('key.contains("correct")') &&
  tutorService.includes('toStudentPayload(structuredContext.artifact())') &&
  tutorService.includes('stripTutorPrivateFields(node)') &&
  tutorService.includes('normalized.contains("grading")'),
  'Tutor grounding must use learner-safe structured artifacts and recursively strip hidden answers/grading from legacy lesson blocks.');
assert.ok(androidApp.includes('put("artifactId", activeArtifactId)') &&
  androidApp.includes('Ask the AI tutor about this topic') &&
  androidApp.includes('tutorScopeArtifactId == structuredLessonArtifactId'),
  'Android must start the same artifact-scoped tutor session and expose a tutor action for structured lessons.');

// Both marketplace audiences must reuse the same course-summary component while keeping
// their distinct, correctly-authorized purchase actions and learner/parent capabilities.
assert.ok(studentMarketplacePage.includes("from '../../components/MarketplaceCourseCard'") &&
  studentMarketplacePage.includes('<MarketplaceCourseCard') &&
  parentMarketplacePage.includes("from '../../components/MarketplaceCourseCard'") &&
  parentMarketplacePage.includes('<MarketplaceCourseCard'),
  'Student and Parent marketplaces must use the single shared marketplace course card.');
assert.ok(marketplaceCourseCard.includes('courseCode') &&
  marketplaceCourseCard.includes('curriculumVersion') &&
  marketplaceCourseCard.includes('Current curriculum') &&
  marketplaceCourseCard.includes('वर्तमान पाठ्यक्रम') &&
  marketplaceCourseCard.includes('priceLabel') &&
  marketplaceCourseCard.includes('description') &&
  marketplaceCourseCard.includes('subjects') &&
  marketplaceCourseCard.includes('chapterCount') &&
  marketplaceCourseCard.includes('lessonCount') &&
  marketplaceCourseCard.includes("language === 'hi'") &&
  marketplaceCourseCardCss.includes('var(--qe-color-surface') &&
  marketplaceCourseCardCss.includes('var(--qe-color-primary') &&
  marketplaceCourseCardCss.includes('data-qe-appearance="dark"') &&
  marketplaceCourseCardCss.includes('data-qe-appearance="light"') &&
  studentMarketplacePage.includes('useLocale()') &&
  studentMarketplacePage.includes("const language = english ? 'en' : 'hi'") &&
  studentMarketplacePage.includes("language={language}") &&
  studentMarketplacePage.includes("tx('Course Marketplace"),
  'The common marketplace card must show course identity, syllabus coverage, commercial price and theme-aware surfaces.');

// Parent course listing must resolve its path ID as a learner ID under a verified institution,
// never bake in a selected learner or infer access from another tenant.
const guardianProgramsStart = guardianCourseProgramsApi.indexOf('@GetMapping("/children/{studentId}/programs")');
const guardianProgramsEnd = guardianCourseProgramsApi.indexOf('/** Paid offers', guardianProgramsStart);
const guardianProgramsRoute = guardianCourseProgramsApi.slice(guardianProgramsStart, guardianProgramsEnd);
assert.ok(guardianProgramsRoute.includes('@PathVariable long studentId') &&
  guardianProgramsRoute.includes('long institutionId=requireInstitution(tenant)') &&
  guardianProgramsRoute.includes('requireFullGuardianChildAccess(context.userId(),studentId)') &&
  guardianProgramsRoute.includes('requireChildInstitutionMembership(studentId,institutionId)') &&
  guardianProgramsRoute.includes('spe.institution_id=?') &&
  guardianProgramsRoute.includes('ce.institution_id=?') &&
  guardianProgramsRoute.includes('group by ps.sort_order,s.id,s.code,s.display_name,c.code,s.sort_order') &&
  guardianProgramsRoute.includes('Failed to load guardian child course catalogue'),
  'Guardian course catalogue must be tenant-scoped, child-authorized, and log SQL failures with safe request context.');

// Initial diagnostic is intentionally course-specific. Question selection uses published,
// reviewed question/lesson/chapter content, stores a frozen attempt snapshot, and records each
// answer through learning state before marking the complete attempt submitted.
assert.ok(diagnosticApi.includes('requireEnrolledCourse(studentId,courseId,institutionId)') &&
  diagnosticApi.includes("q.review_status in ('APPROVED','PUBLISHED')") &&
  diagnosticApi.includes("l.status='PUBLISHED'") &&
  diagnosticApi.includes('questionHistory.snapshot(questionId)') &&
  diagnosticApi.includes('learningState.recordAttempt') &&
  diagnosticApi.includes('"DIAGNOSTIC",institutionId,courseId') &&
  diagnosticApi.includes('Answer all diagnostic questions before submitting.'),
  'Initial diagnostic question selection and analytics writes must remain course/institution-scoped and regression-tested.');

// Both purchase entry points must call the same server-side order lifecycle. A missing seller/tax
// profile and an order awaiting provider reconciliation are deliberately different safe states.
assert.ok(courseMarketplaceApi.includes('@PostMapping("/courses/{courseId}/orders")') &&
  courseMarketplaceApi.includes('@PostMapping("/family/courses/{courseId}/orders")') &&
  (courseMarketplaceApi.match(/createOrderInternal\(/g) || []).length >= 3 &&
  courseMarketplaceApi.includes('inspectProviderOrderForNewAttempt') &&
  courseMarketplaceApi.includes('The previous payment attempt is still being reconciled') &&
  courseMarketplaceApi.includes('courseOrderFinancialService.prepareCheckout(') &&
  courseOrderFinancialService.includes('Checkout is not configured: HRMS must activate the legal seller'),
  'Student and family purchase routes must share the server lifecycle and retain seller/tax and no-double-payment guards.');
assert.ok(learningMetricsService.includes('result.put("diagnosticStats"') &&
  learningMetricsService.includes('result.put("diagnosticSubjects"') &&
  recommendationApi.includes('diagnostic_result.percent') &&
  recommendationApi.includes('PROGRESS_DIAGNOSTIC_MASTERY_AND_RELEASED_ASSESSMENTS') &&
  studentMarketplacePage.includes('useLocale()') &&
  studentMarketplacePage.includes("tx('Buy course'"),
  'The initial diagnostic baseline must be exposed separately and used in course-specific recommendations; marketplace labels must follow the selected language.');
assert.ok(studentMarketplacePage.includes('lang={language}') &&
  parentMarketplacePage.includes('lang={language}') &&
  marketplaceCourseCard.includes('lang={language}') &&
  parentMarketplacePage.includes('formatUserTimestamp(request.requested_at, language)') &&
  studentMarketplacePage.includes("new Intl.NumberFormat(english ? 'en-IN' : 'hi-IN'") &&
  parentMarketplacePage.includes("new Intl.NumberFormat(english ? 'en-IN' : 'hi-IN'") &&
  marketplaceTimestamp.includes("language === 'hi' ? 'hi-IN' : 'en-IN'") &&
  marketplaceI18n.includes('legal seller') &&
  marketplaceI18n.includes('previous payment attempt is still being reconciled') &&
  marketplaceI18n.includes('fallbackEnglish.trim().toLowerCase()') &&
  marketplaceCourseCardCss.includes('data-qe-appearance="dark"') &&
  marketplaceCourseCardCss.includes('data-qe-appearance="light"'),
  'Marketplace routes, shared card, timestamps, currency and high-risk checkout errors must be language- and appearance-aware.');

