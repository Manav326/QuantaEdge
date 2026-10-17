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
assert.ok(androidNavigationContract.includes('listOf("today", "courses", "learn", "practice", "progress", "library")'), 'Android contract must own the five canonical student group IDs.');
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
assert.ok(dashboardPage.includes('qe-active-course-section') && dashboardPage.includes('/student/courses') && dashboardPage.includes('/student/marketplace'),
  'Dashboard must show the active course and link to dedicated Courses and Marketplace screens.');
assert.ok(!dashboardPage.includes("courses.map((course:any)=>("), 'Dashboard must not display the entire all-courses catalogue inline.');
assert.ok(androidApp.includes('FloatingTutorDock(') && androidApp.includes('route == AppRoute.LESSON && tutorOpen'),
  'Android AI Tutor must be a floating overlay scoped to the active lesson.');
assert.ok(androidApp.includes('StudentAllCoursesScreen(') && androidApp.includes('StudentMarketplaceScreen('),
  'Android must have dedicated All Courses and Marketplace routes.');

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
assert.ok(studentClasses.includes("'/api/v1/learning/live-classes/'+session.session_id+'/join'") && studentClasses.includes('youtube-nocookie.com/embed/') && studentClasses.includes("activeJoined.provider==='YOUTUBE_LIVE'"), 'Student web must use authorized join before showing/reopening the YouTube embed.');
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
assert.ok(androidApp.includes('StructuredLessonResourcesPanel(') && androidApp.includes('onOpenLearningDocument = ::openAssignedLearningDocument'), 'Android structured lessons must expose the published resource catalogue and reuse the protected document reader.');
assert.ok(androidLessonResources.includes('/api/v1/learning/resources?artifactId=') && androidLessonResources.includes('/progress?assignmentId=') && androidLessonResources.includes('renderPdfPreviewPage'), 'Android lesson resources must load only assigned published resources, save media progress and preview private PDFs in-app.');
assert.ok(androidLessonResources.includes('downloadAllowed') && androidLessonResources.includes('downloadUrl') && androidLessonResources.includes('allowDownload = true') && androidLessonResources.includes('openLocalFile'), 'Android may hand a resource to an external file viewer only through the API-supplied download URL when downloads are enabled.');
assert.ok(assignedResources.includes("item.streamUrl && !isVideo && !isAudio && kind !== 'IMAGE'") && assignedResources.includes('Open resource online'), 'Student web must be able to open a private assigned document online even when download is disabled.');

console.log('Navigation, localization, live-class authorization, resumable offline-video and student resource parity source contracts passed.');


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

assert.ok(courseEnrollmentApiContract.includes("o.status='PUBLISHED'") &&
  courseEnrollmentApiContract.includes("o.price_paise=0") &&
  courseEnrollmentApiContract.includes("o.currency='INR'") &&
  courseEnrollmentApiContract.includes('course_purchase cp') &&
  courseEnrollmentApiContract.includes("e.status in ('ACTIVE','PAUSED')"),
  'New self-enrolment must require an explicitly published free offer; existing enrollment/purchase may be resumed.');
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
  androidFloatingTutorContract.includes('userResized = true'),
  'The floating tutor must honour resize gestures on compact screens as well as wide screens.');


// Issue #66 dashboard live-class schedule and future-session visibility contracts.
const issue66StudentDashboardContract = read('../app/student/page.tsx');
const issue66LiveClassApiContract = read('../../../services/api/src/main/java/com/quantaedge/api/LearningClassController.java');
const issue66LiveClassTestsContract = read('../../../services/api/src/test/java/com/quantaedge/api/LearningClassControllerTest.java');
assert.ok(issue66StudentDashboardContract.includes('/api/v1/learning/live-classes?') &&
  issue66StudentDashboardContract.includes('formatUserTimestamp(upcoming ? item.starts_at : item.ends_at)') &&
  issue66StudentDashboardContract.includes('dashboardNow - 3 * 24 * 60 * 60 * 1000') &&
  issue66StudentDashboardContract.includes('dashboardClassesBySubject'),
  'The student dashboard must load authorized course-scoped live classes, show only future/current and the preceding three days, group by subject and format local user timestamps.');
assert.ok(issue66LiveClassApiContract.includes('@GetMapping("/learning/live-classes")') &&
  issue66LiveClassApiContract.includes("cs.ends_at>now()-interval '60 days'") &&
  !issue66LiveClassApiContract.slice(
    issue66LiveClassApiContract.indexOf('@GetMapping("/learning/live-classes")'),
    issue66LiveClassApiContract.indexOf('@PostMapping("/learning/live-classes/{sessionId}/join")')
  ).includes("starts_at<=now()+interval '24 hours'"),
  'The student class catalogue must keep recent history bounded while allowing scheduled classes beyond the next 24 hours; join eligibility remains a separate endpoint rule.');
assert.ok(issue66LiveClassTestsContract.includes('studentClassCatalogueIncludesFarFutureScheduledSessionsAndKeepsCountScopeAligned') &&
  issue66LiveClassTestsContract.includes('assertFalse(itemSql.getValue().contains("starts_at<=now()+interval \'24 hours\'"))'),
  'Backend tests must guard full upcoming schedule visibility in both item and count SQL.');
