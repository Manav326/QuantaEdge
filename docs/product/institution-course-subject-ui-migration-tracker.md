# Institution → Course → Subject UI Migration Tracker

**Merged branch:** `feat/institution-course-subject-ui-migration` → `main`  
**Source of route inventory:** `docs/qa/ui-screen-inventory.csv`  
**Target contract:** all authenticated learner and staff learning interactions are scoped by the resolved institution, then the selected/assigned course, then a subject that belongs to that course. No student-facing class-root or legacy subject-track fallback is accepted. An institution label comes from the trusted tenant context; it is not a user-supplied authorization value.

## Merge status — 2026-10-19

- **Merged into `main`:** PR [#84](https://github.com/Manav326/QuantaEdge/pull/84), squash commit `d6dc124c295985875ff2798a8b3c20ce800c7136`.
- **Hierarchy retained:** Institutions → Course → Subject → published structured lesson.
- **Current verification:** post-merge CI has not yet been verified green. Check the [GitHub Actions runs](https://github.com/Manav326/QuantaEdge/actions) for the newest `QuantaEdge Build and Publish` run on `main`; pushes made while updating this tracker can cancel superseded runs.
- **Deferred by explicit instruction:** pre-merge API verification had 13 failing tests and 4 errors out of 429 tests. These test cases were not treated as a merge blocker and must be revisited after the merged code is available for runtime validation. This does **not** mean the tests passed.
- The route-by-route and runtime QA checkboxes below remain open until their actual scenarios are executed.


## Non-negotiable implementation rules

1. The institution is resolved from the authenticated session / validated institution context. It must not be inferred from a class code, client-controlled ID, or local-storage value.
2. Course options come only from the selected institution's authorized course catalogue and the learner's real entitlement/access.
3. Subject options come only from the active course's `subjects` mapping. Subject changes reset pagination/selection; course changes clear the subject unless the same subject code is confirmed to belong to the new course.
4. Content APIs and deep links carry `courseId`; subject-based endpoints also carry `subjectCode`. Backend authorization remains authoritative; hiding a selector is not an access check.
5. **External course identity contract:** learner-facing structured-learning URLs and the structured-lessons API use the course's stable 8-character alphanumeric `curriculum_program.public_id` (`^[A-Z]{3}[A-Z0-9]{5}# Institution → Course → Subject UI Migration Tracker

**Merged branch:** `feat/institution-course-subject-ui-migration` → `main`  
**Source of route inventory:** `docs/qa/ui-screen-inventory.csv`  
**Target contract:** all authenticated learner and staff learning interactions are scoped by the resolved institution, then the selected/assigned course, then a subject that belongs to that course. No student-facing class-root or legacy subject-track fallback is accepted. An institution label comes from the trusted tenant context; it is not a user-supplied authorization value.

## Merge status — 2026-10-19

- **Merged into `main`:** PR [#84](https://github.com/Manav326/QuantaEdge/pull/84), squash commit `d6dc124c295985875ff2798a8b3c20ce800c7136`.
- **Hierarchy retained:** Institutions → Course → Subject → published structured lesson.
- **Current verification:** post-merge CI has not yet been verified green. Check the [GitHub Actions runs](https://github.com/Manav326/QuantaEdge/actions) for the newest `QuantaEdge Build and Publish` run on `main`; pushes made while updating this tracker can cancel superseded runs.
- **Deferred by explicit instruction:** pre-merge API verification had 13 failing tests and 4 errors out of 429 tests. These test cases were not treated as a merge blocker and must be revisited after the merged code is available for runtime validation. This does **not** mean the tests passed.
- The route-by-route and runtime QA checkboxes below remain open until their actual scenarios are executed.


## Non-negotiable implementation rules

1. The institution is resolved from the authenticated session / validated institution context. It must not be inferred from a class code, client-controlled ID, or local-storage value.
2. Course options come only from the selected institution's authorized course catalogue and the learner's real entitlement/access.
3. Subject options come only from the active course's `subjects` mapping. Subject changes reset pagination/selection; course changes clear the subject unless the same subject code is confirmed to belong to the new course.
, for example a `CRS`-prefixed course ID). The numeric `curriculum_program.id` is an internal database key and must not be emitted as the new public course ID. Legacy numeric deep links remain accepted during migration; new links must use `public_id`. APIs resolve the public ID to the internal ID before running tenant, enrollment, course/subject-mapping and persistence checks.
5. Remove class-first routes, `classCode`/legacy `programCode` learning fallbacks, and `LEGACY_CLASS_ENROLLMENT` entitlement fallbacks. No unpublished/draft lesson may become visible to bypass a scope mismatch.
6. Route and component changes are not marked complete until the build passes and the QA scenario is executed on both web and Android. Static/source checks are supporting evidence, not runtime acceptance.

## Primary/focused course and structured routing contract

- The primary/focused course is a **reporting and navigation selection**, not an enrollment status. A learner can retain multiple concurrent course enrollments; changing focus must not pause, revoke, or rewrite other courses.
- `/student/courses` must read the saved primary course, render each real enrolled course, and provide an **Open structured course** action. That action sets the primary course and opens `/student/structured-lessons?courseId=<id>`.
- Subject shortcuts must preserve the exact pair `courseId + subjectCode`. The catalogue, detail, saved responses, activity responses, documents, assessments/results, progress and certificates must not fall back to a class code or a different saved course when an explicit course ID is invalid.
- API authorization checks tenant membership, assigned/released course, enrollment, mapped subject, publication state and artifact-to-course access. UI selection alone is never authorization.
- Parent reports first select the child, then select a course when the child has more than one enrolled course. The report endpoint rejects an unscoped multi-course request and returns the selected `courseId/courseName` with only that course's subject/lesson metrics.

## Current implementation slices

| Area | Current source change | Verification state |
|---|---|---|
| Shared learner scope component | `apps/web/app/student/InstitutionCourseSubjectScope.tsx` and its CSS module display the validated institution context and expose course + course-owned subjects | Merged into main; runtime QA pending |
| Structured lessons | Course + subject controls; catalogue API uses course ID and optional course-owned subject; direct lesson links preserve both | Merged into main; build/runtime verification pending |
| Learning entry route | `/student/learn` now routes to the structured-lesson catalogue rather than the old class-subject browser | Merged into main; route validation pending |
| Practice entry route | Course/subject selection; links to approved structured lessons; no class/program fallback in this entry | Merged into main; API/UX verification pending |
| Textbook library | Shared scope component; course-specific expanded-subject state; reader routes preserve course and subject; UI labels course instead of class | Merged into main; build/runtime verification pending |
| Tests/results | Shared scope component; subject selection is carried to test and results queries | Merged into main; API test/build verification pending |
| Live classes | Shared scope component; course-owned subject list and URL preserved across changes | Merged into main; build/runtime verification pending |
| Progress | Shared institution/course/subject scope; subject-specific breakdowns are filtered locally within the selected course | Merged into main; build/runtime verification pending |
| Android learning catalogue | Course-required learning flow; dynamic subject list from the selected course; no visible “return to existing lessons” switch | Implemented on branch; Android build pending |
| Android live classes | Subject choices come from current course mapping; blank selection means all subjects in that course | Implemented on branch; Android build pending |
| Student dashboard / course list | Learning links route to structured lessons with explicit course + subject; dashboard identity shows institution/course rather than class | Merged into main; build/runtime verification pending |
| API assessment results | Optional subject filter added to the course-scoped result query while keeping an overload for test/source compatibility | Merged into main; API test follow-up pending |

## Route-by-route audit checklist

The authoritative inventory is the CSV; this table is a migration worklist rather than a claim that each route has passed. Every listed route must retain loading, empty, error, authorization-denied, direct-link, course-switch, subject-switch, and tenant-isolation coverage where applicable.

### Web learner and family routes
- [ ] `/certificate/verify` — Certificate verification (`apps/web/app/certificate/verify/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/institution` — Institution entry (`apps/web/app/institution/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/login/student` — Student sign-in (`apps/web/app/login/student/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/school/[institutionCode]` — Tenant entry/invitation (`apps/web/app/school/[institutionCode]/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/parent` — Family overview/report (`apps/web/app/parent/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/parent/children` — Child management (`apps/web/app/parent/children/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/parent/courses` — Parent course purchase (`apps/web/app/parent/courses/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/parent/profile` — Parent profile (`apps/web/app/parent/profile/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student` — Student dashboard (`apps/web/app/student/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/account-lifecycle` — Account lifecycle (`apps/web/app/student/account-lifecycle/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/certificates` — Certificate centre (`apps/web/app/student/certificates/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/classes` — Classes/recordings (`apps/web/app/student/classes/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/courses` — Course catalog (`apps/web/app/student/courses/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/diagnostic` — Diagnostic (`apps/web/app/student/diagnostic/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/learn` — Learning entry (`apps/web/app/student/learn/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/marketplace` — Marketplace/checkout (`apps/web/app/student/marketplace/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/practice` — Practice entry (`apps/web/app/student/practice/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/profile` — Student profile (`apps/web/app/student/profile/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/progress` — Learning progress (`apps/web/app/student/progress/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/structured-lessons/[artifactId]` — Structured lesson (`apps/web/app/student/structured-lessons/[artifactId]/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/structured-lessons` — Structured lesson catalog (`apps/web/app/student/structured-lessons/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/tests` — Tests/assessment attempts (`apps/web/app/student/tests/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.
- [ ] `/student/textbooks` — Textbook library (`apps/web/app/student/textbooks/page.tsx`): verify trusted institution context → authorized course → mapped subject; preserve scope in URL and API; exercise no-course, no-subject, invalid subject, and switch cases.

### Android destinations
- [ ] `PARENT_SIGNUP` — Parent registration (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_RESET` — Parent credential reset (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `STUDENT_HOME` — Student home (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `ALL_COURSES` — Course catalog (`apps/android/app/src/main/java/com/quantaedge/android/ui/StudentAllCoursesScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `MARKETPLACE` — Marketplace checkout (`apps/android/app/src/main/java/com/quantaedge/android/ui/StudentMarketplaceScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_HOME` — Family overview (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_COURSES` — Parent course marketplace (`apps/android/app/src/main/java/com/quantaedge/android/ui/ParentCourseMarketplaceScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_REPORT` — Child report (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_PROFILE` — Parent profile (`apps/android/app/src/main/java/com/quantaedge/android/ui/ParentProfileScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_CHILD_CREATE` — Child creation (`apps/android/app/src/main/java/com/quantaedge/android/ui/ParentChildCreateScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_CHILD_MANAGE` — Child management (`apps/android/app/src/main/java/com/quantaedge/android/ui/ParentChildManageScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PARENT_CHILD_ARCHIVED` — Archived children (`apps/android/app/src/main/java/com/quantaedge/android/ui/ParentArchivedChildrenScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `SUBJECT_LESSONS` — Subject lessons (`apps/android/app/src/main/java/com/quantaedge/android/ui/LearningClassesScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `LESSON` — Structured lesson (`apps/android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonRenderer.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PRACTICE` — Practice (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `ASSESSMENTS` — Assessment catalog (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `ASSESSMENT_ATTEMPT` — Assessment attempt (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `ASSESSMENT_RESULTS` — Assessment results (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `ASSESSMENT_REVIEW` — Assessment review (`apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `RESOURCES` — Lesson resources (`apps/android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonResourcesPanel.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `DOCUMENT_READER` — Textbook/document reader (`apps/android/app/src/main/java/com/quantaedge/android/ui/StudentTextbookQuickLinks.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PROFILE` — Student profile (`apps/android/app/src/main/java/com/quantaedge/android/ui/StudentProfileScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `PROGRESS` — Student progress (`apps/android/app/src/main/java/com/quantaedge/android/ui/StudentProgressScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `CLASSES` — Live classes (`apps/android/app/src/main/java/com/quantaedge/android/ui/LiveBroadcastPlayerScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `CERTIFICATES` — Certificate center (`apps/android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.
- [ ] `LEADERBOARD` — Assessment leaderboard (`apps/android/app/src/main/java/com/quantaedge/android/ui/AssessmentLeaderboardScreen.kt`): use the selected tenant-bound course; subject choices must be derived from that course; exercise route/back/deep-link and course-switch behavior.

### Admin learning/catalogue routes
- [ ] `/assessments` — Assessment author/review/grade (`apps/admin/app/assessments/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/certificates` — Certificate administration (`apps/admin/app/certificates/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/classes` — Class scheduling/recording (`apps/admin/app/classes/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/learning-resources` — Learning resources (`apps/admin/app/learning-resources/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/marketplace` — Course offer management (`apps/admin/app/marketplace/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/parents` — Parent/family administration (`apps/admin/app/parents/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/resources` — Textbook/resource catalog (`apps/admin/app/resources/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/structured-lesson-preview` — Lesson preview (`apps/admin/app/structured-lesson-preview/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.
- [ ] `/students` — Student administration (`apps/admin/app/students/page.tsx`): create/assign/publish learning assets against an institution-owned or HRMS-approved course; only show subjects mapped to the selected course.

### People Operations / HRMS routes
- [ ] `/assignments` — Academic assignments (`apps/people/app/assignments/page.tsx`): show course assignment context, not class as a top-level learning product; verify cross-institution assignment approval and permission separation.
- [ ] `/institutions` — Platform institution operations (`apps/people/app/institutions/page.tsx`): show course assignment context, not class as a top-level learning product; verify cross-institution assignment approval and permission separation.
- [ ] `/my-classes` — Teacher assigned classes (`apps/people/app/my-classes/page.tsx`): show course assignment context, not class as a top-level learning product; verify cross-institution assignment approval and permission separation.

## QA scenarios required before closure

- [ ] Re-run `node --test scripts/inventory_ui_routes.test.mjs` and `node scripts/inventory_ui_routes.mjs` against the exact audited commit; reconcile wrappers and aliases.
- [ ] Add/extend source contracts to reject the legacy student lesson URL shape (`classCode` or `programCode` without a course ID) and hardcoded subject lists in learner Android UI.
- [ ] Test at least three institutions: course catalogue, assigned/released course, mapped subject, learning document, assessment, live class, progress and certificate paths. Include one course not assigned to the tenant and a forged subject code.
- [ ] Test deep links, refresh, browser Back, Android system Back, process recreation, offline storage/cache keys, language changes and course switching.
- [ ] Verify API negative cases for cross-tenant course IDs, subject IDs/codes outside course mapping, inactive memberships, unassigned courses, revoked/paused access and unpublished content.
- [ ] Confirm data returned for two courses sharing the same subject code is still isolated by `courseId`; never infer scope from `class_code` or a subject code alone.
- [ ] Review course certificates and child/parent flows for class-root subject tracks; certificate eligibility, progress, Books, assessments, live/recorded classes and family entitlement must carry the selected course and institution.
- [ ] Update `docs/qa/ui-screen-inventory.csv` status/evidence only after the relevant runtime scenario is executed, not when code merely exists.

## Admin web second-pass audit — 2026-10-19

This pass explicitly includes the admin workspace. Course scope is resolved from the active institution's assigned/released course catalogue; class codes remain descriptive metadata within a course, not the top-level authorization or content filter.

### Source-reviewed admin routes

- `/` dashboard, `/content`, `/questions`, `/assessments`, `/resources`, `/source-ingestion`, `/classes`, `/translations`, `/certificates`, and `/students` expose a course selection or an equivalent validated selected-course state and send `courseId` to their course-sensitive APIs.
- `/learning-resources` maintains a course selection for chapter/lesson/artifact targets. A second-pass defect was found in its assignment list/create/status/reorder operations: those operations previously relied on resource/assignment IDs and could list or mutate placements without checking the selected course. The API now requires the assigned course plus institution context, validates that the target chapter belongs to that course, filters assignment rows by mapped subjects, and the UI passes `courseId` on each operation.
- The legacy `PUT /api/v1/admin/lessons/{lessonId}/status` operation previously updated by lesson ID alone. It now requires `courseId`, validates the course is assigned to the authenticated institution and that the lesson is mapped to it, and delegates publish/review transitions to the tenant-aware content controller.
- The admin live-class and recording cards now type their optional course labels explicitly; CI had caught the missing TypeScript fields.
- Resource-upload class/subject defaults now follow the selected course's mapped classes/subjects instead of retaining the old Class 6 / Maths defaults when the course changes.
- User-facing admin translations now prefer **Course structure**, **Course content**, **Course chapter**, or **Textbook source** rather than the ambiguous term **Curriculum**. Internal schema/table/API identifiers are intentionally unchanged in this UI wording pass to avoid breaking contracts.

### Admin items that remain explicitly open

- The reusable learning-resource catalogue itself is currently a shared catalogue endpoint and is not filtered by `courseId`; only its placements are course-filtered. Confirm the intended ownership model for catalogue records (shared platform resource versus institution-owned resource) before claiming full tenant isolation for that library.
- The parent directory is institution-scoped and lists each parent's linked children/course names; it is an account directory, not a selected-course progress report. Any child-progress/report view must still select a course when the child has multiple active enrollments.
- CI and runtime acceptance are not complete merely because source contracts pass. Re-check the newest branch run, then execute admin course-switch, unmapped-subject, cross-institution, and direct-ID tampering scenarios before claiming runtime acceptance is complete.


## Structured-learning public course ID correction — 2026-10-19

- Learner course selectors continue to retain numeric internal IDs for local state and legacy APIs, but Learn, Practice, course-subject shortcuts, and structured-lesson detail requests now use `public_id` in the URL and structured-lessons API.
- Structured-lesson API accepts the 8-character public identifier and resolves it to `curriculum_program.id` before enforcing institution membership, active enrollment, assignment/release and mapped-subject access. Existing numeric links are kept temporarily for backward compatibility.
- This ID correction does **not** publish draft learning content. The main content inventory currently has 39 Class 6 Science topic JSONs, and the production index records zero reviewed/publishable topics. The API Docker packaging script includes only explicitly published, fully approved artifacts, so the catalogue can continue returning `[]` until the appropriate content review and publish workflow is completed. Do not relax publication gates to mask an empty catalogue.
- Build/runtime acceptance remains pending until the latest GitHub Actions run completes and a learner account verifies a URL containing the actual 8-character public ID against the authenticated catalogue/detail endpoints.
