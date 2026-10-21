# Phase 1 — Android Navigation and Screen Composition Map

**Status:** Static composition review; no emulator/device execution performed in this pass.  
**Primary files:** `apps/android/app/src/main/java/com/quantaedge/android/ui/StudentNavigationContract.kt`, `QuantaEdgeApp.kt`, and the standalone Compose screen files referenced below.

## 1. Navigation model observed in source

The current UI shell stores the active destination as a `rememberSaveable` `AppRoute` value in `QuantaEdgeApp.kt`. Destination rendering is a large `when(route)` switch; some screens are functions in the shared shell and some are separate Compose components.

The shell also has:
- A saved lesson return route and assessment-result return route.
- Parent vs student navigation groups driven by `StudentNavigationContract`.
- Different bottom/navigation-rail placement based on orientation/layout policy.
- A custom `BackHandler` that maps routes back to their parent flow.
- Session restoration and role-dependent reset/recovery branches.
- A lesson-scoped tutor overlay/dock with state independent from the page route.

This means destination existence and component-file existence are not interchangeable. The route, renderer branch, return-route behavior, workspace/role and saved state must be tested together.

## 2. Destination-to-renderer table

| `AppRoute` destination | Rendered component / source | Core behaviors to exercise | Navigation/authorization risk |
|---|---|---|---|
| `WELCOME` | `WelcomeScreen.kt` → `WelcomeScreen` | Student/parent entry, signup and login entry, first-run state | Public vs signed-in landing; reopening app |
| `LOGIN` | `QuantaEdgeApp.kt` → `LoginScreen` | Google credential flow, OTP/credential path, parent/student mode, validation/errors | Workspace-specific session; back to welcome and returning after auth |
| `PARENT_SIGNUP` | `QuantaEdgeApp.kt` → `ParentOtpFlowScreen` (signup mode) | Required parent details, OTP verify and account creation | Duplicate identity, invalid OTP, registration retry |
| `PARENT_RESET` | `QuantaEdgeApp.kt` → `ParentOtpFlowScreen` (reset mode) | Credential recovery and verification | Reset must not grant access before verification; rate limit |
| `ACCOUNT_SIGNUP` | `QuantaEdgeApp.kt` → `AccountSignupScreen` | Adult/student signup and identity setup | Age/role gates, Google verification, duplicate identity |
| `STUDENT_HOME` | `QuantaEdgeApp.kt` → `StudentHomeScreen` | Dashboard widgets, course access, class links, refresh/retry | Not a substitute for dedicated Courses/Marketplace navigation; loading/API failure |
| `ALL_COURSES` | `StudentAllCoursesScreen.kt` → `StudentAllCoursesScreen` | Dedicated course catalog, course states and open/activate | Hidden/unpublished course and unentitled lesson denial |
| `MARKETPLACE` | `StudentMarketplaceScreen.kt` → `StudentMarketplaceScreen` | Published offers, free enrollment, paid checkout and verification | Server-authoritative entitlement; sandbox payment only |
| `PARENT_HOME` | `QuantaEdgeApp.kt` → `ParentHomeScreen` | Child list and family summary/report entry | Parent must not see unrelated children; select-child state |
| `PARENT_COURSES` | `ParentCourseMarketplaceScreen.kt` → `ParentCourseMarketplaceScreen` | Family offers, child course request/purchase/entitlement | Correct target student; payment/idempotency; child access boundary |
| `PARENT_REPORT` | `QuantaEdgeApp.kt` → `ParentReportScreen` | Selected-child report and progress | Parent-child link/consent and tenant scope on every request |
| `PARENT_PROFILE` | `ParentProfileScreen.kt` → `ParentProfileScreen` | Parent profile/photo/language save | Validated photo, editable field boundaries and session role |
| `ACCOUNT_IDENTITY` | `AccountIdentitySettingsScreen.kt` → `AccountIdentitySettingsScreen` | Verified identity, eligible editable fields, linked account status | Prevent unverified edits, account collision and cooldown bypass |
| `PARENT_CHILD_CREATE` | `ParentChildCreateScreen.kt` → `ParentChildCreateScreen` | Create/link child and required profile data | Consent, class, generated IDs, tenant membership |
| `PARENT_CHILD_MANAGE` | `ParentChildManageScreen.kt` → `ParentChildManageScreen` | Edit details/credentials and supported child actions | Only linked child; retain history and correct role |
| `PARENT_CHILD_ARCHIVED` | `ParentArchivedChildrenScreen.kt` → `ParentArchivedChildrenScreen` | List archived children and restore | Restore permission, duplicates and historical record preservation |
| `ACCOUNT_LIFECYCLE` | `AccountLifecycleScreen.kt` → `AccountLifecycleScreen` | Eligibility display and adult/parent lifecycle actions | Verification/confirmation, minor restrictions, stale session reset |
| `SUBJECT_LESSONS` | `QuantaEdgeApp.kt` → `StructuredLessonCatalogueScreen` when structured mode is active; otherwise `LessonListScreen` | Class/subject/course lesson selection, catalogue filters | Different renderer branches need separate test coverage; return to correct course context |
| `LESSON` | `QuantaEdgeApp.kt` → structured lesson blocks with `StructuredLessonRenderer` when structured mode is active; otherwise `LessonScreen` | Explain/activity/questions/Recap/resources/previous-next and tutor context | Renderer parity; invalid/unpublished lesson; scoped progress and return route |
| `PRACTICE` | `QuantaEdgeApp.kt` → `PracticeScreen` | Question types, answer/clear/retry/next and completion | Answer persistence, wrong-answer state, duplicate submission |
| `DIAGNOSTIC` | `QuantaEdgeApp.kt` → `DiagnosticScreen` | Start/resume, answer progression and outcome | Duplicate/in-progress attempt and result persistence |
| `ASSESSMENTS` | `QuantaEdgeApp.kt` → `AssessmentListScreen` | Assessment catalog, availability, timer/start/resume | Published access, course scope and attempt eligibility |
| `ASSESSMENT_ATTEMPT` | `QuantaEdgeApp.kt` → `AssessmentAttemptScreen` | Timer, answer state, previous/next, save/submit | Server-authoritative state; session/process death; double submit |
| `ASSESSMENT_RESULTS` | `QuantaEdgeApp.kt` → `ResultsScreen` | Results/history/certificate links | Correct student/attempt ID and preserved results-return route |
| `ASSESSMENT_REVIEW` | `QuantaEdgeApp.kt` → `AssessmentAttemptScreen` in review mode | Read attempt and explanation/answer feedback as permitted | Review mode must not accidentally reopen editing or submitting |
| `RESOURCES` | `QuantaEdgeApp.kt` → `ResourcesScreen` | Lesson resources, files and external resource actions | Resource course/tenant scope and unavailable download state |
| `DOCUMENT_READER` | `QuantaEdgeApp.kt` → `DocumentReaderScreen`; related textbook helper in `StudentTextbookQuickLinks.kt` | Open protected document, page/cache/network retry, fullscreen/back handling | Authenticated owner key, tenant scoping and no cache leakage on account switch |
| `PROFILE` | `QuantaEdgeApp.kt` → `StudentProfileScreen` / profile wrapper | Profile and verified identity, profile-photo/edit interactions | Adult/minor constraints and student-vs-parent profile destination |
| `PROGRESS` | `StudentProgressScreen.kt` → `StudentProgressScreen` | Progress summary, attempt/certificate actions | Aggregations match saved source state, refresh/empty errors |
| `TUTOR` | `TutorScreen.kt` → `TutorScreen`; lesson-scoped floating dock in shell | Chat, error/retry, close/minimize/restore, drag/resize where dock exposes them | Lesson/student context must remain correct during navigation and resize |
| `CLASSES` | `QuantaEdgeApp.kt` → `LiveBroadcastPlayerScreen` if a live broadcast is active; otherwise `LearningClassesScreen` | Class catalog, join window, attendance, chat/reconnect, leave | Two renderer branches; teacher/course eligibility; private meeting link |
| `RECORDING_PLAYER` | `RecordedPlayerScreen.kt` → `RecordedPlayerScreen` | Playback, save progress, resume, errors | Entitlement, tenant and archive/revoke boundaries |
| `CERTIFICATES` | `StudentCertificatesScreen.kt` → `StudentCertificatesScreen` | Eligibility/issue/list/download/QR verification | PDF/PNG format, certificate recipient/status and file destination |
| `LEADERBOARD` | `AssessmentLeaderboardScreen.kt` → `AssessmentLeaderboardScreen` | Rankings, empty/outside-top-20 state and assessment selection | Server-authoritative rankings; no cross-tenant/student data leakage |

## 3. Return and back-navigation contracts to test

The shared shell's `BackHandler` maps:
- Login → welcome; account signup/parent signup/reset → login.
- All Courses, Marketplace, Certificates and Leaderboard → student home.
- Parent courses/report/profile/child create/manage/archive → parent home.
- Account identity → profile selected by the current role; lifecycle → profile.
- Lesson → saved lesson return route; Practice/Tutor → lesson.
- Recording player → classes; document reader → resources.
- Assessment attempt → assessment list; assessment review → results.
- Results → saved result-return route only for recognized originating destinations; otherwise student home.
- Classes → parent home for parent role, otherwise student home.
- Root student/parent homes remain their own roots.

These branches are source-observed, not device-verified. Verify normal Back, system Back, gesture navigation, rotation, process recreation and restoration after auth/session changes. A mismatch can strand users or return them to another role's area.

## 4. Existing unit-test linkage

The inspected `StudentNavigationContractTest.kt` includes checks for canonical group ordering, Today→student home, lesson/class belonging to Learn, course catalog/marketplace return behavior, assessment/practice/result grouping, profile return group, explicit welcome/archive/account navigation ownership, parent-role-specific routes and orientation-driven placement.

These tests are valuable contract coverage but do not prove that each destination renders correctly, that the Android API succeeds, or that all BackHandler branches behave on physical devices.

## 5. Required Android verification pass

For each of the 34 inventory destinations:
1. Start from a fresh session with the intended persona/workspace.
2. Reach the destination using normal app navigation and by supported deep-link/restore path.
3. Capture screen at common and compact display sizes; include keyboard/IME where forms exist.
4. Exercise primary and secondary actions, loading/empty/error/forbidden/retry states.
5. Press system Back and app Back; rotate/recreate process where supported.
6. Verify API/database changes and that wrong role/tenant/child/course/attempt IDs are denied.
7. Compare equivalent Web behavior for course catalog, books, lesson, practice, parent reports/profile, certificates and marketplace.
8. Record build SHA, device/emulator/API level, case ID, result, screenshot/log and issue reference.

Until that run is recorded, the destination remains `SOURCE_SCAN_PARTIAL` and `DEVICE_NOT_VERIFIED`.
