# Phase 1 — Source Review Log (Pass 1)

**Review style:** static source scan  
**Files examined:** Web/Admin/People page entry files; Android navigation contract and shared Compose route switch; selected standalone Android screens  
**Baseline:** repository `main`; route-page source was read after documentation commit `837b587d1260360abad3a24020f269c8bdd60cb2`.  
**Result:** **partial source review only**. The 59 Web/Admin/People route-entry files were scanned for top-level handlers, representative API paths and state/error signals. Follow-up source checks resolved the thin Web route entries into their actual client components and identified Admin `/legacy-content` as an explicit redirect to `/content`. Several key client components were then scanned, but imported subcomponents, permission assertions, request/response contracts, database writes and all rendered states are not fully traced. All entries remain runtime-unverified.

## 1. Web: student/parent app

| Route | Source observations from the page entry | Representative API dependencies / calls | Follow-up |
|---|---|---|---|
| `/` | Public home component; source has no obvious direct API call in the route file | None observed in entry file | Inspect all links/CTA targets, shared shell and responsive states |
| `/certificate/verify` | Form submission, scan start/stop and identifier input | `/api/v1/certificates/verify/` | Exercise valid, unknown, revoked, malformed and scanner-denied cases |
| `/institution` | Institution portal; login, password change, logout and copy institution/learner links | `/api/v1/institution/auth/change-password`, `/api/v1/institution/auth/logout`; other calls flow through helpers | Trace initial auth and every tenant-switch/branding/API call; verify role boundary |
| `/login` | Mobile OTP and username/password submit paths; input normalization | `/api/v1/auth/request-otp`, `/api/v1/auth/verify-otp`, `/api/v1/auth/account-login` | Trace Google button component, account recovery, lock/throttle behavior and return destination |
| `/login/student` | Student credential submit path; identifier, username and password handling | `/api/v1/auth/student-login` | Verify parent-linked and student credential variants, disabled/suspended states |
| `/signup` | OTP request/verify; name/mobile form | `/api/v1/auth/account-signup/request-otp`, `/api/v1/auth/account-signup/verify-otp` | Trace Google sign-up component and adult/minor/parent variants against server rules |
| `/session` | Session resolution and logout/switch-account controls | `/api/v1/auth/me`, `/api/v1/auth/logout` | Test expiry, revoked session, retry and role-based landing |
| `/privacy` | Static legal page | None observed | Verify complete content, locale behavior, headings and accessibility |
| `/terms` | Static legal page | None observed | Verify complete content, locale behavior, headings and accessibility |
| `/school/[institutionCode]` | Institution-code resolver invoked from route | Direct endpoint not visible in this entry file | Trace `resolveInstitutionCode` helper and invitation validation outcomes |
| `/parent` | Loads authenticated guardian and child list; loads child-specific report/certificate data; refresh/select-child actions | `/api/v1/auth/me`, `/api/v1/guardians/children`, child `report` and `certificates` routes | Verify child switch, no-child/error states and no cross-guardian access |
| `/parent/children` | Child create/update, profile and credential save, track selection, course assignment, archive/restore | `/api/v1/guardians/account`, `/api/v1/guardians/children`, archived/child/track routes | Map every action to consent, age, child-limit and retained-history rules |
| `/parent/courses` | Family offer/order/entitlement flow, paid checkout and verification, free enrollment, request dismissal/transfer, learning-session open | `/api/v1/marketplace/family/courses`, `/api/v1/guardians/children`, `/api/v1/parent/course-requests`, family entitlements/orders and `/api/v1/auth/my-learning-session` | Exercise provider sandbox only; verify signature, idempotency, child entitlement and cancellation |
| `/parent/profile` | Profile load/save, image prepare/change/remove, parent switching | `/api/v1/auth/me`, `/api/v1/auth/switch-parent`, guardian profile/profile-photo | Test malformed/oversized image, invalid input, locale persistence and auth switching |
| `/student` | Student record, programs, recommendations, live classes and learning documents; refresh action | `/api/v1/students/me`, recommendations, programs, live-classes and learning-documents routes | Verify dashboard empty/loading/failure, class/course scope, normal vs minor and parent role |
| `/student/account-lifecycle` | Adult verification, transition OTP, parent-eligibility request and conversion actions | account lifecycle, adult-verification, learner-transition OTP/verify, parent-eligibility and transition routes | Verify eligibility, forbidden minor changes, audit trail and session/profile consistency |
| `/student/certificates` | Load records/previews, choose PDF/PNG, issue certificate, select language/track/achievement | `/api/v1/students/me/certificates/`, `/api/v1/students/me/certificates/issue` | Test eligibility, ownership, issue/revoke/revision, errors, actual file contents and QR |
| `/student/classes` | Route entry mounts `ClassesClient`; the client has live/recorded tabs, subject filter, join/rejoin/leave, playback open/close and playback-progress handlers | Client: learning live-class/recorded-class list and action routes | Finish action-to-API response mapping; test join windows, attendance, playback persistence and failures |
| `/student/courses` | Load enrolled course list and activate course | `/api/v1/students/me/programs`, student/program activation routes | Verify course listing is separate from dashboard, availability and access after activation |
| `/student/diagnostic` | Loads diagnostic/attempt, answer and next-step actions | `/api/v1/diagnostic`, `/api/v1/diagnostic/attempts/` | Test answer persistence, completion, interrupted attempts and score calculation |
| `/student/learn` | Route entry wraps `LearnClient` in `Suspense`; client includes lesson loading, question answer submission, resource handling, lesson/track position, tutor opening and previous/next navigation | Client: learning questions/lessons, student profile, recommendations, learning sessions and documents | Trace tutor/resource child components and all mutations; test lesson progression, error recovery and scoped context |
| `/student/marketplace` | Offers, free enrollment, parent purchase request, paid checkout and payment verification | `/api/v1/marketplace/courses`, order routes and student program activation | Confirm publication/entitlement gate and no live charge in test |
| `/student/practice` | 333-character route entry; no direct API calls in entry | None observed in route file | Follow actual rendered practice flow and connect attempts/feedback to API/data |
| `/student/profile` | Profile load/save, photo preparation, lifecycle/adult/parent transitions | student profile/profile-photo and account lifecycle/transition routes | Verify verified-vs-editable fields, cooldown, minor constraints and save failures |
| `/student/progress` | Progress/attempt reporting and assessment-result certificate issue action | student record, certificate and assessment-eligibility endpoints | Check aggregate accuracy against source attempts and certificate eligibility |
| `/student/structured-lessons` | Route entry mounts `StructuredLessonsClient`; client loads a published lesson catalogue and links into detail | `/api/v1/learning/structured-lessons?language=` | Validate filters, language, access denial and empty/API-failure states |
| `/student/structured-lessons/[artifactId]` | Dynamic entry reads `artifactId` with `useParams` and passes it to `StructuredLessonDetailClient` | Client: `/api/v1/learning/structured-lessons/` | Verify invalid/stale artifact, unpublished content, question submission and activity save |
| `/student/tests` | Route entry wraps `TestsClient` in `Suspense`; client has tests/results tabs, start/resume test, result/review, leaderboard and certificate issuance entry points | learning assessments/results/attempts; certificate assessment eligibility and issue | Test attempt lifecycle end to end: answer/save/submit/reopen, result consistency, unauthorized attempt and certificate eligibility |
| `/student/textbooks` | Route entry wraps `TextbooksClient` in `Suspense`; client includes library filters, document open/return, pagination, zoom, fit-to-page/width, fullscreen and thumbnails | learning document list and individual document routes | Verify protected document authorization, page bounds, network/offline/cache behavior, keyboard and responsive reader states |

### Student/parent pass-1 observations

- Parent/family pages expose multi-record operations; data fixtures must preserve guardian-to-child, child-to-track/course and report/attempt links.
- Web marketplace entry pages include Razorpay checkout paths. QA must use sandbox/test order credentials and server-authoritative verification; never use live payment details.
- The four small routes `/student/learn`, `/student/practice`, `/student/structured-lessons`, `/student/structured-lessons/[artifactId]`, `/student/tests`, plus `/student/textbooks` and `/student/classes`, require direct wrapper/destination resolution so the same feature is not counted twice or missed.
- Route-level API matches are representative static-string findings. Helper functions, imported components, URL builders and request wrappers can hide additional dependencies; endpoint list is not exhaustive.

## 2. Web: Educational Admin

| Route | Source observations | Representative API dependencies / calls | Follow-up |
|---|---|---|---|
| `/` | Admin dashboard, analytics/tutor metrics, logout/reload behavior | `/api/v1/auth/me`, `/api/v1/admin/overview`, admin analytics/tutor analytics, logout | Validate role-specific metric visibility and all dashboard links/error paths |
| `/login` | OTP request/verify and locale control | auth OTP routes and `/api/v1/auth/me` | Test access throttling, denied staff, OTP failures and return path |
| `/session` | Resolve session, switch/log out | `/api/v1/auth/me`, `/api/v1/auth/logout` | Verify revoked session and correct workspace landing |
| `/account-lifecycle` | Adult-verification/parent-eligibility review and approve/reject actions with references | admin account-lifecycle review routes | Verify reviewer authorization, reason/reference validation and immutable audit |
| `/assessments` | Authoring and review tabs, question-bank search/selection, grader/status/offline-policy actions | admin assessments, staff, content catalogue, question bank/reviews, attempts and certificates | Test author→review→publish→attempt→grade/result and permission failures |
| `/certificates` | Search/filter/page, audit expansion and revoke-with-reason | admin certificates routes | Verify ownership/eligibility, revoke, audit revisions, date/locale and file verification |
| `/classes` | Live/recorded tabs, filters and session/recording management | admin live-classes, recorded-classes, class chapters/staff and approved teaching assignments | Test state transitions, eligibility, provider/calendar errors and recording lifecycle |
| `/content` | Large Content Studio including rich text, learner-block ordering, visual source and structure checks | admin content programs/chapters/lessons/questions and review-summary routes | Inspect the full author/review/publish workflow, validation, revision history and student read path |
| `/employees` | Staff directory/edit/create, role-permission drafting, audit tab | admin staff, permissions and staff-audit routes | Verify least privilege, grant/revoke, suspend/reactivate and stale sessions |
| `/learning-resources` | Resource CRUD/upload/status, targets, assignments and ordering | admin learning-resources, targets and assignments routes | Test file validation, access scope, multiple placements and cleanup |
| `/legacy-content` | Explicit Next.js server redirect to `/content`; not a separate content feature | None in redirect entry | Verify runtime redirect and ensure only `/content` is counted as the functional screen |
| `/marketplace` | Course offer edit/save and catalogue load | admin marketplace course routes | Verify offer visibility, price, publish controls and student-facing entitlement |
| `/parents` | Parent list/search, expand children, parent/student status, child track updates | admin parents and student/track status routes | Verify scope, guardian links, consent and suspended account behavior |
| `/questions` | Queue/search/status filter/history; approve/reject actions | admin question review queue/summary, lesson and question routes | Verify review permissions, question correctness/provenance and published visibility |
| `/resources` | Large textbook/resource registry with facets, intake, PDFs/documents and governance | curriculum, admin content/source-ingestion, learning documents/PDF routes | Trace each tab, download/upload/review and no unapproved publication |
| `/source-ingestion` | Source search, start/refresh jobs, cache/book intake and extraction work | admin source registry/jobs/books/gaps, textbook extraction assets, curriculum/content routes | Verify idempotency, resume, source hashes, whole-book constraints and draft-only behavior |
| `/structured-lesson-preview` | Artifact load/import-text/file inspect, validation errors, English/Hindi/raw view | No direct API found in scanned entry | Test malformed schema, unsafe/unapproved visuals, accessibility and parity with learner renderer |
| `/students` | Student search/filter/paging via admin list | `/api/v1/admin/students?` | Verify profile identity, role/status and institution scoping |
| `/translations` | Search/select/edit paginated translation records, statuses and quality warnings | admin translations routes | Check placeholder parity, missing translations, review/publish and locale fallback |

## 3. Web: People Operations / HRMS

| Route | Source observations | Representative API dependencies / calls | Follow-up |
|---|---|---|---|
| `/` | Employee/departments/designations tabs, search, inactive filter, employee create/edit/history, logout | `/api/v1/auth/me`, HR employee/department/designation/employment-type routes, logout | Verify org scope, create/edit state, history and permission-specific controls |
| `/login` | HRMS-specific username/password login | `/api/v1/auth/hrms-login`, `/api/v1/auth/me` | Test workspace/session separation, throttle and denied grants |
| `/change-password` | Current/new/confirm password form | `/api/v1/auth/hrms/change-password` | Validate policy, mismatch, forced-change completion and session handling |
| `/institutions` | Platform owner/access gates, create, edit institution, status, course authorization, branding uploads | `/api/v1/hrms/platform/institutions/` family | Verify view-only default, edit per card, image crop/size, code uniqueness and tenant isolation |
| `/access` | Search/select employee, provision access, toggle roles and account access | HRMS access-users and permission routes | Confirm only authorized platform/HR administrators; revocation and session invalidation |
| `/workforce` | Vacancy/candidate, performance cycle/review, asset and exit domains | HR organization, recruitment, performance, employee, asset, exit routes | Test all nested tabs, status/approval transitions, audit and invalid employee references |
| `/attendance` | Attendance, leave, corrections, approval rules, holidays, balances/ledger and working days | HR attendance/summary, leave requests/types, org, correction, employee and leave-balance routes | Verify permissions per tab, immutable ledger, correction review and India holiday overrides |
| `/payroll` | Components, effective-dated compensation, periods/runs, calculate/recalculate/submit/approve/pay/payslips | HR organization, payroll components/compensation/periods/runs routes | Validate overlapping dates, mixed currencies, idempotency and test/real separation |
| `/assignments` | Assignment/period/report tabs, workload policy, period and teaching-assignment actions | HR org, subjects, academic periods, teaching assignments, employees/report/policy routes | Check eligibility/conflicts, manager scope, approval and class visibility |
| `/my-classes` | Assigned live classes, link retrieval/copy and logout | `/api/v1/teaching/my-live-classes`, teaching live-class routes, logout | Verify only assigned eligible sessions, link privacy and provider failure |
| `/self-service` | Employee own profile, attendance/correction, leave/balances, payslips, profile changes | HR self-service profile/attendance/leave/balances/payslip/profile-change routes | Negative test another employee ID, cancel/withdraw semantics and record retention |

## 4. Android navigation/source scan

The route enum and the `QuantaEdgeApp.kt` destination switch were scanned. The switch maps the declared navigation contract to shared-shell renderers and component screens; some routes intentionally reuse the shell rather than have one screen file per destination.

Representative mappings found:
- Welcome/login/account signup/parent OTP flows → Compose shell and account screens.
- All Courses and Marketplace → `StudentAllCoursesScreen`, `StudentMarketplaceScreen`.
- Parent child/profile/course/report routes → parent components and shell-owned report destinations.
- Subject lessons/lesson/practice/diagnostic/assessments/attempt/results/review → lesson/catalogue, practice, diagnostic and assessment Compose functions.
- Resources/document reader → resource and protected document reader flows.
- Profile/account lifecycle/progress → profile, lifecycle and progress components.
- Tutor → `TutorScreen`; the floating dock is a separate component and needs layout, resize/minimize, drag and lesson-context testing.
- Classes/recording player → learning-class/chat view, live player and protected recorded player.
- Certificates/leaderboard → certificate center and server-authoritative leaderboard.

Standalone components scanned in this pass include identity settings, account lifecycle, course catalogue, student and parent marketplace, child create/manage, parent profile, student certificates/profile/progress, live classes/chat, live and recorded players, tutor, and structured-lesson renderer.

The native shell contains role-aware student/parent navigation and custom back behavior. The test plan must explicitly verify destination changes after login/logout/role transitions; restore/back behavior; deep links; split/landscape navigation; lost network; Android picker/camera/QR access where used; and account-scoped offline resource storage.

Android screen/API dependencies are partly passed as lambdas from the shell. Endpoint absence in an individual composable must not be interpreted as absence of a backend dependency.

## 4A. Thin route and primary client follow-up

Source inspection has now resolved these route relationships (this corrects the earlier “destination unclear” wording for the thin entries):

| Route | Primary client component | Key behavior discovered | Representative API paths observed in client |
|---|---|---|---|
| `/student/classes` | `ClassesClient.tsx` | Live/recorded tab switch, subject filter, join/rejoin/leave, open/close recording, playback progress | `/api/v1/learning/live-classes`, `/api/v1/learning/recorded-classes` |
| `/student/learn` | `LearnClient.tsx` | Load lesson, submit practice-in-lesson answers, navigate lessons/track, load recommendations/resources, open lesson-scoped tutor | `/api/v1/learning/questions`, `/api/v1/learning/lessons`, `/api/v1/learning/sessions`, student/program/docs endpoints |
| `/student/practice` | `PracticeClient.tsx` | Answer, retry, next, order input, match input, reset and submit | `/api/v1/learning/practice/sessions`, `/api/v1/learning/questions`, lesson endpoints |
| `/student/structured-lessons` | `StructuredLessonsClient.tsx` | Load catalogue and navigate to a lesson artifact | `/api/v1/learning/structured-lessons?language=` |
| `/student/structured-lessons/[artifactId]` | `StructuredLessonDetailClient.tsx` | Load artifact, submit question, save activity | `/api/v1/learning/structured-lessons/` |
| `/student/tests` | `TestsClient.tsx` | Tests/results tabs, start/resume attempts, leaderboard/result views and certificate issue action | learning assessments, attempts/results, certificate eligibility/issue endpoints |
| `/student/textbooks` | `TextbooksClient.tsx` | Document list/filters, open/return, page navigation, zoom/fit/fullscreen/thumbnails | `/api/v1/learning/documents` and document-specific routes |
| Admin `/legacy-content` | Redirects to `/content` | Route alias, not an independent content workflow | No endpoint in redirect entry |

This is improved source evidence, but still not a completed UI audit: callbacks must be traced to exact request/response contracts and server authorization; every error/empty/validation state must be tested; no browser/device execution has been performed.

## 5. Gaps revealed by pass 1

1. **Source entry is not a whole screen audit.** Imported UI components, hooks and shell logic may contain additional actions/endpoints; next pass must trace each primary control to its effective request and persisted mutation.
2. **Endpoint list is preliminary.** Literal-string extraction can miss helper-built URLs, query strings, indirect client calls and imported functions.
3. **Permissions require server inspection.** UI conditions do not certify backend enforcement; trace endpoint role/permission and tenant context.
4. **Database consequences remain open.** Map every write to entities/foreign keys, uniqueness constraints, cascades/restrict rules, media assets, audit records and session invalidation.
5. **Runtime still open.** All screens remain marked not runtime-verified pending browser/device execution evidence.
6. **QA pack is not yet implemented.** No global test-data installation ownership mechanism was demonstrated by the preliminary `is_test_data` search.

The representative controller and guard scan is documented in the [API, Workspace and Permission Boundary Map](./phase-1-api-permission-map.md). A separate [435-row API route inventory](./phase-1-api-route-inventory.csv) has now been extracted from Spring request/STOMP mapping annotations across 48 source files. Its scenario IDs are candidate traceability links; full method/filter/service review is still required. Remaining priorities are in the [consolidated coverage gap plan](./phase-1-coverage-gaps.md).

## 6. Next source-review pass

For each inventory row:

1. Trace imports and shared shell/layout to the final rendered component.
2. Enumerate every actionable control, keyboard/touch interaction, form constraint, dialog and destructive confirmation.
3. Trace the exact API method/path, headers/workspace/tenant context, authentication and required permission.
4. Record request/response and expected successful/failed database state changes.
5. Link positive, negative, validation, boundary, stale-session, retry and idempotency tests.
6. Assign stable QA case IDs and note which cases can be automated versus require manual/device verification.

Use `SOURCE_SCAN_PARTIAL` for the current level of review; upgrade to `SOURCE_REVIEWED` only after the referenced components, permissions and action→API contract have all been traced.
