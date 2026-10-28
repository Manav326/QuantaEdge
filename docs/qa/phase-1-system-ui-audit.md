# Phase 1 — QuantaEdge System and UI Audit

**Repository:** [Manav326/QuantaEdge](https://github.com/Manav326/QuantaEdge)  
**Branch:** `main`  
**Source snapshot initially inventoried:** `f4f94f08f895136197fc95a48f37d19c9f16a895`  
**Live branch note:** `main` advanced during the audit due to an automated content checkpoint. Reconfirm the exact head SHA before runtime testing or claiming CI status.  
**Status:** **IN PROGRESS — source inventory baseline documented; per-control review and browser/device verification remain open.**

## 1. Purpose and audit method

This is the evidence-led baseline for the proposed centralized QuantaEdge QA & Test Data Center. Phase 1 determines what exists before deciding what to seed or how to uninstall it.

The inspected evidence includes repository metadata, current source-tree listings, the Next.js route inventory script, Android navigation contract and screen files, API controller/service names, migration names, test-file names, CI workflow steps, and selected existing UI/architecture documentation.

**Not performed in this pass:** a live browser walkthrough, physical Android device/emulator session, full local build, logged-in workflow run, production integration call, visual screenshot comparison, or exhaustive per-control endpoint/schema trace. No screen is described as runtime-verified merely because its source is present. These executions need evidence captured in subsequent audit passes.

## 2. Product and service map

| Surface | Source root | Local entry point documented in README | Main responsibility |
|---|---|---|---|
| Student/parent Web | `apps/web` | `http://localhost:3000` | Public entry, student and family accounts, courses, marketplace, learning, practice/tests, progress, books/resources, tutor/class access, profiles and certificates |
| Educational Admin | `apps/admin` | `http://localhost:3001` | Content Studio, questions, assessments, source/book ingestion, translations, classes/recordings, students/parents/staff, certificates and course marketplace |
| People Operations / HRMS | `apps/people` | `http://localhost:3002` | Platform/institution operations, workforce, access governance, attendance, leave, holidays, payroll, academic assignments and employee self-service |
| Android | `apps/android` | Device/emulator | Native Kotlin/Jetpack Compose implementation of account, learning, assessment, parent, course, media, tutor and certificate flows |
| Shared API | `services/api` | `http://localhost:8080` | Shared identity/authentication, authorization, tenant context, course entitlements, learning/content, assessment, guardian, certificates, tutor, media/messaging and HRMS APIs |
| CI, scripts and tests | `.github/workflows`, `scripts`, `tests`, API/Android test roots | GitHub Actions/local commands | Builds, static contracts, route inventory, curriculum pipelines, unit and service tests |

These are repository-documented defaults, not proof that a deployed service or local runtime is currently healthy.

## 3. Platform and tenant boundaries

The product architecture describes QuantaEdge as the platform head that manages multiple institutions and central integration credentials. The source tree includes platform institution and tenant-context APIs, institution onboarding/branding screens, institution/course lifecycle operations, scoped HRMS permissions, course entitlements and tenant-scoped learning history.

Audit these as distinct authorization boundaries:

- **Platform owner:** platform-wide institution onboarding and centrally managed integrations/credentials.
- **Institution operator/admin:** only the assigned institution's settings, staff and allowed operations.
- **Educational Admin:** content/learning administration grants; not automatic HRMS or platform-owner authority.
- **HRMS roles:** permission-scoped employee/workforce/attendance/payroll/assignment operations; designation labels must not independently grant privileges.
- **Learner and guardian:** student-specific learning and child relationship scope, consent and lifecycle constraints.
- **Tenant data:** learner, guardian, enrollment, progress, media, reports and course availability must not leak between institutions.

The code components are present; complete end-to-end enforcement and negative cross-tenant cases are not yet certified. API authorization is authoritative. UI menu visibility alone is insufficient evidence.

## 4. Source inventory baseline

The current filesystem route-source inventory inspected for the three Next.js apps contained **59 `page.*` entry files**: Web 29, Admin 19, People Operations 11. This is a source-entry count, not a count of unique user journeys or fully rendered screens. The Android navigation contract declares **34 destinations**. The route list and destinations are captured in [ui-screen-inventory.csv](./ui-screen-inventory.csv).

A previous UI localization artifact documented **50 page routes** from commit `36b38fa34c1b602e4f7238d43300a48023b3e011` (Web 23, Admin 17, People Operations 10). I inspected `scripts/inventory_ui_routes.mjs` and independently recomputed its route-normalization rules against the complete, non-truncated recursive tree at audit revision `5c4560c510c04f6c777071a887ec52cfcc711c28`: current route register targets **60 page entries / 60 primary pages / 0 route-handler files / 0 duplicate primary routes** (Web 29, Admin 19, People Operations 12), including the new `/qa` People Operations page. The 59 pre-existing page paths were reconciled in the earlier tree pass; the new `/qa` source and its state row were added on the QA feature branch. This is source/register reconciliation, **not** proof that the exact-head Node generator has run successfully.

### 4.1 Student and Parent Web

Route files identified:

- Public/entry/account: `/`, `/login`, `/login/student`, `/signup`, `/session`, `/institution`, `/school/[institutionCode]`, `/privacy`, `/terms`.
- Public certificate verification: `/certificate/verify`.
- Student/account: `/student`, `/student/profile`, `/student/account-lifecycle`.
- Course catalog/commerce: `/student/courses`, `/student/marketplace`.
- Learning/library: `/student/learn`, `/student/structured-lessons`, `/student/structured-lessons/[artifactId]`, `/student/classes`, `/student/textbooks`.
- Practice/outcomes: `/student/practice`, `/student/tests`, `/student/diagnostic`, `/student/progress`, `/student/certificates`.
- Parent/family: `/parent`, `/parent/children`, `/parent/courses`, `/parent/profile`.

Small source files exist for some learning/practice/textbook/structured-lesson routes. Their precise role (redirect, thin wrapper, or distinct page) must be confirmed before counting them as separate workflows.

### 4.2 Educational Admin

Route files identified:

`/`, `/login`, `/session`, `/account-lifecycle`, `/assessments`, `/certificates`, `/classes`, `/content`, `/employees`, `/learning-resources`, `/legacy-content`, `/marketplace`, `/parents`, `/questions`, `/resources`, `/source-ingestion`, `/structured-lesson-preview`, `/students`, and `/translations`.

The `/legacy-content` route needs an explicit disposition: active feature, compatibility wrapper/redirect, or deprecated route. It must not be accidentally omitted or duplicated in the final QA register.

### 4.3 People Operations / HRMS

Route files identified:

`/`, `/login`, `/change-password`, `/institutions`, `/access`, `/workforce`, `/attendance`, `/payroll`, `/assignments`, `/my-classes`, and `/self-service`.

These pages contain internal sections and multi-step workflows. For example, attendance includes attendance, leave requests, holiday calendar and leave balances; payroll includes compensation, periods/runs, submissions and related records; institution operations includes institution state and branding/edit flows. Each meaningful subsection and action needs its own test reference.

### 4.4 Android

The source enum `StudentNavigationContract.kt` declares these 34 destinations:

`WELCOME`, `LOGIN`, `PARENT_SIGNUP`, `PARENT_RESET`, `ACCOUNT_SIGNUP`, `STUDENT_HOME`, `ALL_COURSES`, `MARKETPLACE`, `PARENT_HOME`, `PARENT_COURSES`, `PARENT_REPORT`, `PARENT_PROFILE`, `ACCOUNT_IDENTITY`, `PARENT_CHILD_CREATE`, `PARENT_CHILD_MANAGE`, `PARENT_CHILD_ARCHIVED`, `ACCOUNT_LIFECYCLE`, `SUBJECT_LESSONS`, `LESSON`, `PRACTICE`, `DIAGNOSTIC`, `ASSESSMENTS`, `ASSESSMENT_ATTEMPT`, `ASSESSMENT_RESULTS`, `ASSESSMENT_REVIEW`, `RESOURCES`, `DOCUMENT_READER`, `PROFILE`, `PROGRESS`, `TUTOR`, `CLASSES`, `RECORDING_PLAYER`, `CERTIFICATES`, and `LEADERBOARD`.

The Compose shell sometimes owns several destinations; therefore a one-enum-to-one-source-file mapping is not assumed. Each destination must be traced through `QuantaEdgeApp.kt`, its component and navigation transitions. Device checks must include back behavior, session restoration, keyboard/IME, loading/error states, supported screen sizes, and web parity where required.


### 4.5 First source-scan pass completed (partial, not sign-off)

After the initial route inventory, the page-entry source for all 59 Web/Admin/People routes was fetched and scanned for component-level structure signals, form/action handlers, API endpoint literals, state/error signals and direct navigation. The Android `QuantaEdgeApp.kt` navigation switch was scanned to map the 34 declared destinations to shell-owned or standalone Compose renderers; key standalone identity, lifecycle, course/marketplace, guardian, certificate, profile/progress, live/recorded media, tutor and structured-lesson components were also scanned.

The concrete observations and gaps are in [phase-1-source-review-log.md](./phase-1-source-review-log.md). CSV review status now uses `SOURCE_SCAN_PARTIAL` for this level. It does **not** certify every imported component, authorization branch, API contract, database mutation or runtime state. Those remain explicit Phase 1 tasks.

## 5. Persona and permission matrix

These are required audit personas, not a statement that fixture accounts currently exist.

| Persona | Required checks |
|---|---|
| Platform owner/global administrator | Institution onboarding and platform-wide operations are available only with explicit platform-owner grant; central credentials and integrations stay under platform control. |
| Institution administrator/operator | Can act only within assigned institution; verify institution settings, branding, staff/data visibility, course configuration and edit-state affordances. |
| Educational Admin/content reviewer/editor | Content and question lifecycle, review/publish, assessments, resources, classes, learners, families, certificates and marketplace permissions are independently enforceable. |
| Adult student | Signup/sign-in, verified identity, profile, courses/access, checkout, learning, practice/tests, tutor, progress and certificates. |
| Minor student | Guardian linkage/consent, age-appropriate lifecycle and profile restrictions, course access, test/learning history and no unsupported identity changes. |
| Parent/guardian | Own identity/profile and only linked children; child create/manage/archive/restore, consent/login, course actions and reports. |
| Teacher/professor | Assigned courses/classes/resources and attendance where allowed; must not inherit CMS, payroll or platform privileges by designation. |
| HR staff/clerk/manager | Specific permissions, organization/reporting scope, staff lifecycle, attendance, leave, payroll and approval actions. |
| Unauthenticated/expired/suspended/revoked user | Protected pages, API calls and cached sessions must enforce lifecycle and authorization after logout, suspension or permission changes. |

Identity must remain consistent across connected records. Avoid testing policy transitions by merely switching a role label when the product requires a verified account, guardian relationship, fresh consent, an institution binding or a new session.

## 6. Cross-surface workflow register

Every family must become one or more stable test-case IDs with preconditions, expected UI/API/database changes, role/tenant assertions and owned cleanup records.

| Workflow family | Minimum test scope |
|---|---|
| Authentication and sessions | Google identity picker/token validation, verified Gmail claim, phone OTP in configured development mode, account setup, username/password where enabled, validation, refresh/logout/expiry, revoked/suspended sessions. |
| Identity/profile lifecycle | Unique user identifiers, trusted vs editable profile fields, photo source, profile validation, cooldowns and adult/minor account constraints. |
| Guardian/minor | Parent-child linking, age and consent checks, credential creation/reset, child limits, archive/restore with history retention, deny unrelated-child reads. |
| Tenant/institution | Onboarding, unique institution identity, status transitions, edit confirmation, brand assets/cropping/validation, tenant routing and isolation across at least two synthetic institutions. |
| Course catalog and entitlements | Separate course screen, marketplace visibility, published/unpublished offers, purchase/enrollment, owned/unowned access, grandfathered entitlement, revocation and relogin behavior. |
| Payment | Sandbox order creation, success/cancel/fail paths, server signature/webhook verification, amount/currency integrity, idempotency and retry; never charge real payment methods. |
| Learning/content | Class/course/subject selection, lesson/structured artifact navigation, draft/review/approve/publish, bilingual UI vs content language, resource linkage and forbidden unpublished access. |
| Practice/diagnostic/assessments | Question types, answer state, clear/save/previous/next, submission and durable attempts, feedback, results/review, online/offline sync as supported, leaderboard consistency. |
| Tutor | Lesson-scoped context, history, provider disabled/unavailable/retry, floating/resizable/minimizable UI, no cross-student context leakage. |
| Live/recorded learning and messaging | Scheduling/visibility, class eligibility, join/leave attendance, chat authorization/rate limit, record attachment/playback/progress, archive/remove, provider failure recovery. |
| Certificates | Eligibility, issue/reissue/revoke, revision, identity, Hindi/English, PDF/PNG, QR/public verification, ownership and revoked-download denial. |
| HRMS workforce/access | Employee lifecycle, workspace-scoped permission grants, reporting relationships, approvals, holidays/working-week rules, audit and session revocation. |
| Attendance/leave | Mark/view, correction request/review, leave requests/decisions, holiday/calendar rules, balances and immutable ledger history. |
| Payroll | Effective-dated compensation, pay-period lifecycle, calculate/recalculate/submit/approve/pay, payslip, currency/overlap validation, idempotency and audit. |
| Localization/branding/accessibility | Locale selection and persistence, labels/errors/empty states, focus semantics, keyboard navigation, reduced motion, contrast, responsive screens, tenant branding with approved QuantaEdge identity intact. |
| QA data pack | Install, rerun/idempotency, manifest, partial-failure recovery, collisions, uninstall/cleanup, no real-record mutation, no external side effects, audit log and coverage report. |

## 7. API and persistence baseline

The API source tree includes controllers/services for account identity and lifecycle; authentication/authorization; guardian access; platform/institution tenancy; course lifecycle/marketplace; curriculum and learning; assessments/practice; structured lessons; textbooks/documents/resources; tutor; messaging/live sessions; certificates; source ingestion/translations; and HRMS access, workforce, attendance, leave, holidays, assignments, approvals/reporting and payroll. The [phase-1-api-route-inventory.csv](./phase-1-api-route-inventory.csv) contains 435 static mapping rows across 48 source files; the [API permission map](./phase-1-api-permission-map.md) explains selected guards and workspace rules. Effective handler bindings, route-specific permissions, tenant/resource predicates and DB/provider side effects remain open for manual validation.

The migration folder at the inspected code snapshot included migrations through `V128__institution_isolate_learner_history.sql`. Important schema families include unified identity and course entitlements, tenant white-label/branding, class 6–8 course products/offers, institutional course releases, scoped learning history and HRMS. Before the seed-pack design, the audit must trace actual foreign keys, unique constraints, cascade/restrict behavior, external asset references and session/identity relationships in the current full schema.

### Test-data ownership search

A repository code search for `is_test_data` returned no matches in this pass. Search results for `test_run_id` included HR payroll run/version code, which is not proof of a global test-pack manifest. This is preliminary code-search evidence, not yet a schema-wide proof that no equivalent mechanism exists.

Required design principle for the later implementation: a unique installation/run ID must own a manifest of every row and asset it creates. Explicit test markers may be useful, but uninstall must use run-level ownership and resource-specific cleanup adapters. **Never delete all rows with a test flag, never delete pre-existing records reused as references, and never treat test-flagged production data as disposable by default.**

## 8. Workflow scenario mapping

A 82-case [workflow scenario register](./phase-1-workflow-scenario-register.csv) defines stable IDs and acceptance contracts for authentication, guardian/minor, tenancy, commerce, content, learning, live media, certificates, HRMS, UX/accessibility and QA-pack lifecycle. Currently, 80 scenarios are `NOT_EXECUTED`; QA-003 (scenario-runner/report coverage) and QA-006 (external-provider sandbox isolation) remain `NOT_IMPLEMENTED`. None is marked passed. All 109 UI inventory rows are `SCENARIO_MAPPED`; the 443-row API inventory includes the eight new QA test-center handlers. Links remain traceability only: source-derived mappings are not endpoint-specific coverage proof.

## 9. Per-screen state coverage checklist

A 109-row [UI State Coverage Checklist](./phase-1-ui-state-coverage.csv) now tracks the state categories separately rather than allowing one generic “screen passed” label. Each row has a stable screen key, source path, scenario IDs, state checks, runtime status, evidence URL, defect IDs and reviewer notes. Current controls/navigation status remains `SOURCE_SCAN_PARTIAL`; the other state categories remain `NOT_REVIEWED`; runtime remains `NOT_EXECUTED`. This is intentional until the control/state is reviewed or exercised. A reviewer may set `NOT_APPLICABLE` only after confirming the UI does not provide that state and recording why.

## 10. Existing automated quality evidence

Named test files found in these locations at the inspected snapshot:

| Test location | File count | Main emphasis visible from filenames/source |
|---|---:|---|
| Root `tests/` Python suite | 39 | Curriculum/textbook extraction, content imports, question generation, visual assets, pipeline contracts and data-location rules |
| API `services/api/src/test` | 66 | API/controller/service tests across auth, access control, learning, assessment, tenant, guardian, certificates, and HRMS |
| Android JVM unit tests | 15 | Domain logic and UI contracts for navigation, assessments, classes/media, lessons, tutor parsing and certificates |
| Route inventory Node test | 1 | Route inventory generator behavior |
| **Named files counted** | **121** | File count only; not test cases passed or completeness of functional coverage |

The CI workflow runs Android unit tests/build, Next.js application build jobs, machine-readable UI route inventory generation and static contracts for branding/theme/localization/structured-lesson UI. The root test suite heavily covers textbook and content pipelines. These assets provide a useful base but do not, by themselves, prove every UI action or cross-surface flow has been tested.

### CI revision integrity

The most recent successful main push run discovered in the queried history was [39137468364](https://github.com/Manav326/QuantaEdge/actions/runs/39137468364), on commit `ac37acf9b156087932f195db654e401af1a0045b`, older than the source head observed during this pass. A recent PR run [39143649342](https://github.com/Manav326/QuantaEdge/actions/runs/39143649342) failed on feature-branch SHA `a7bfa04964f47ba8241a100407fa3966fd25daa2`. Do not confuse the two results. A green run tied to the exact latest main revision has not been confirmed here; recheck before treating the baseline build as green.

No browser E2E framework was positively identified in the inspected package manifests/route scripts. That is a preliminary inventory result, not proof that no browser automation exists anywhere in the repository; conduct a full configuration/script scan before deciding the gap.

## 11. Findings and required closure evidence

| ID | Priority | Finding | Closure evidence |
|---|---|---|---|
| QA1-01 | P1 | Source-level count is now reconciled: the older 50-page artifact is from commit `36b38fa...`; current-tree route-generator logic yields 59 primary pages, 0 handlers and no duplicate primary routes. | Still run `node --test scripts/inventory_ui_routes.test.mjs` and `node scripts/inventory_ui_routes.mjs` against the exact current main SHA, retain the artifact, and reconcile the runtime navigation aliases/wrappers. |
| QA1-02 | P1 | Current-main CI health has not been confirmed on the exact latest head. | Required workflow checks for exact audited SHA, with failures investigated and linked. |
| QA1-03 | P1 | Source inventory is not yet per-control behavior documentation or browser/device certification. | Every screen has actions, expected results, validations, success/error/empty/loading states, API dependencies, permissions, responsive/a11y evidence and linked test IDs. |
| QA1-04 | P1 | No global `is_test_data` marker was found by the initial text search; a payroll-specific `test_run_id` is unrelated to proving QA-pack ownership. | Complete schema/entity/migration search and approve a safe run-scoped ownership/cleanup model. |
| QA1-05 | P1 | Cross-surface end-to-end matrix is not yet evidenced as one maintained source of truth. | Stable workflow IDs linking web, Android, API, DB and HRMS expectations with positive/negative cases. |
| QA1-06 | P1 | Platform-owner/institution-admin and education-admin/HRMS permissions are critical and not proven by navigation alone. | API and UI allow/deny tests with least-privilege roles and at least two institutions. |
| QA1-07 | P1 | External providers may cause real-world effects. | Sandbox/test-mode configuration, safe test doubles, signed webhook tests and explicit no-real-payment/no-real-message safety barriers. |
| QA1-08 | P2 | Source trace now resolves thin student routes to client components and Admin `/legacy-content` to a redirect to `/content`; component-level and runtime verification remain open. | Full action/API/permission trace for the client components plus runtime redirect/navigation evidence; no alias double-counting. |
| QA1-09 | P2 | Historical UI/UX and localization tracker statuses are tied to old source revisions; older “done” labels may not be current verification. | Revalidate against current SHA; distinguish historical source change from current browser/assistive-tech certification. |
| QA1-10 | P1 | A tenant/learner starter pack, run/resource/event/check manifest, default-off database/environment gate, verification API and HRMS QA Center are present on a feature branch; broader HRMS/payroll, live-media and provider resources are not included and runtime proof is pending. | Verify exact-head build and disposable PostgreSQL install/check/uninstall tests, including cross-run and protected-baseline preservation; expand only with verified cleanup adapters. See [lifecycle contract](./qa-test-pack-lifecycle.md). |
| QA1-11 | P1 | The 72-case workflow register now has a starter lifecycle implementation slice, but no runtime scenarios are recorded as passing. QA-003 scenario-runner/report coverage and QA-006 external-provider sandbox isolation remain unimplemented. | Execute cases on disposable migrated PostgreSQL and browser/device, attach exact SHA/results/evidence/cleanup proof, and close each case only after its assertions pass. |
| QA1-12 | P1 | The API route inventory has been source-extracted for 435 mapping annotations. Static guard hints do not prove endpoint-by-endpoint auth, tenant/resource ownership, DB effects or response states. | Review shared filters/helpers and route method body per operation; map exact permissions, DB writes, side effects and scenario IDs; run allow/deny integration tests. |
| QA1-13 | P1 | Role templates evolve across migrations (including removal of older HR/payroll/team-scope grants); nav visibility may differ from effective API permission. | Verify effective grants on a freshly migrated test database; compare UI affordances with server authorization and include at least one denied request for each sensitive role boundary. |

## 12. Phase 1 work register

### Complete at source-inventory level

- [x] Confirm repository, branch and core application/service roots.
- [x] Inventory Web/Admin/People route entry files and Android navigation destinations.
- [x] Identify existing route inventory generator and static UI contract tools.
- [x] Locate representative UI audit/design/tenant/HRMS docs, API test suites, Android unit test suite and main CI workflow.
- [x] Identify current external integration families and the high-risk tenant/role boundaries.
- [x] Scan the 59 web route-entry files for page-level handlers/direct API literals and map Android route enums through the Compose shell (partial; see source log).
- [x] Review representative schema migrations for identity, guardian, assessment, certificate, commerce, tenant/course release, learner-history and HRMS relationships; document protected baseline/cleanup findings.
- [x] Add 82 cross-platform workflow scenarios and link the screen inventory to scenario IDs; all scenarios are still unexecuted.
- [x] Add a separate 94-row UI state checklist so each screen's loading/error/validation/authorization/responsive/data/destructive states can be independently reviewed and evidenced.
- [x] Link all 109 screen entries and all 443 extracted API mapping rows to existing scenario IDs; verify no unknown or missing references.
- [x] Add a runtime execution log with one row per workflow scenario and explicit evidence/cleanup columns.
- [x] Map source-present automated test evidence to high-risk scenario groups and record the difference between test-source presence, CI execution and true E2E verification.
- [x] Source-extract 435 REST/STOMP mapping entries from 48 Java files; generated guard hints remain partial and need human review.
- [x] Document Education Admin base-role defaults, HRMS additional-role templates and distinct platform-owner/institution-admin authority.
- [x] Trace all 34 declared Android routes through the destination switch and document component branches plus the custom back/return contract (device verification remains open).
- [x] Inspect representative auth/workspace, guardian, tenant, marketplace, content, assessment, certificate and HRMS controller guards (partial; see API permission map).
- [x] Add a reusable Node validator, unit tests and CI step to check CSV structure, scenario-reference integrity, one execution-log row per scenario and required evidence fields for reported results. CI execution on the final head still must be confirmed.
- [x] Record the initial test-data marker search result and its limits.

### Still required before Phase 1 sign-off

- [x] Recompute current route normalization against the inventory-generator rules and reconcile all 59 page-file paths against the CSV; no missing or non-existent page paths were found.
- [ ] Execute the actual Node route-inventory test and generator on the exact current main SHA; retain the generated artifact and update the historical localization tracker.
- [ ] Inspect every route file and referenced component: controls, dialogs, tabs, handlers, client-side validation, loading/empty/error/success states, visible role conditions and API calls.
- [x] Trace all 34 Android destinations through Compose navigation and component composition; document route-specific renderer branches and back/return contracts. [ ] Complete device/emulator lifecycle/back testing and web parity (see Android composition map).
- [ ] Complete the route action → API method/path → authentication/permission → request/response → persistence model mapping. The 435-row source-derived API route inventory and 72-case scenario/log crosswalk exist, but its guard hints and controller-family scenario links still require endpoint-by-endpoint review.
- [ ] Complete schema-wide extraction of foreign keys, unique/check constraints, delete actions and migration history for all tables that will appear in a test pack (initial partial map: [data and cleanup map](./phase-1-data-and-cleanup-map.md)).
- [ ] Verify the source-derived [role/workspace permission matrix](./phase-1-role-workspace-matrix.md) against the effective migrated database grants and route-by-route API allow/deny behavior (not labels or nav visibility).
- [ ] Conduct browser walkthroughs at approximately 320, 375/390, 430, 768, 1280 and 1440 CSS pixels; retain screenshots and console/network findings.
- [ ] Conduct Android emulator/device walkthroughs for common and compact display sizes, including back navigation, keyboard/IME, network interruption and account/session changes.
- [ ] Exercise primary, validation, permission-denied, empty, loading, failure, retry and destructive-confirmation states.
- [ ] Record exact commit, environment, browser/device, test identity/role, actions, expected/actual result and evidence for each runtime check.
- [ ] Verify required CI checks on the exact current main SHA.
- [ ] Review and sign off the findings before test-data schema or installation functionality is designed.

## 13. Phase 1 definition of done

Phase 1 may be signed off only when:

1. Every route/destination has a final disposition: active user interface, wrapper/redirect, deprecated, or non-user-facing.
2. Every active screen's meaningful controls and states are recorded with a test ID.
3. Each test ID maps to the correct persona, permission, API dependency, persisted entity, side effect and cleanup requirement.
4. Required cross-tenant, account/session, adult/minor/guardian, course entitlement/payment, content approval, certificate and HRMS/financial boundaries include positive and negative cases.
5. Runtime evidence and automated evidence identify the exact tested revision. Blocked and untested cases remain visibly unpassed.
6. A reviewer can follow the documentation and reproduce the baseline without inventing users, credentials, records or expected outcomes.

## 14. Immediate next pass

1. Execute the route-generator test and current route artifact on the exact main SHA; also confirm the new QA-register validator tests in CI. Reconcile generated routes with the 109-row inventory (75 generated Next.js page routes plus 34 Android destinations).
2. Continue screen-specific control/API/permission mapping using the 109-row state checklist and 443-row API register, then use the 72-case execution log to drive controlled runtime walkthroughs.
3. Verify current-main CI and record its exact SHA/results.
4. Close high-priority tenant, role, test-data ownership and external-side-effect design questions; use the [consolidated coverage gap plan](./phase-1-coverage-gaps.md) as the Phase 1 gate before any central data installer is implemented.

---

**Audit integrity:** This file distinguishes source presence from source review, automated execution and manual/runtime certification. Update status only when supported by reproducible evidence.


## Route/component audit baseline update — 2026-10-28

The reconciled UI inventory and state checklist now cover **109 UI entries**: 75 generated Next.js page routes (33 Web, 25 Educational Admin and 17 People Operations/HRMS) plus 34 Android destinations. The workflow scenario register and runtime execution tracker each contain 82 rows after adding notification, institution-operations, payout-statement and course-access scenarios. Shared and route-specific component traceability is maintained in the [route/component audit register](../ui-ux-audit/route-component-audit-register.csv) and [component-instance register](../ui-ux-audit/component-instance-audit-register.csv); the [audit plan](../ui-ux-audit/comprehensive-route-component-audit-plan.md), [findings register](../ui-ux-audit/findings-register.csv), [route-specific review](../ui-ux-audit/route-specific-source-review.md) and [shared-shell review](../ui-ux-audit/shared-shell-source-review.md) define follow-up work.

These counts represent source inventory and scenario tracking, **not runtime pass results**. Browser/device walkthroughs and component-by-component responsiveness, theme, language, accessibility and workflow verification remain open until evidence is recorded against the exact tested commit.
