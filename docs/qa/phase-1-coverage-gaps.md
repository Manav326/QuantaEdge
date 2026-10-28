# Phase 1 — Consolidated Coverage Gaps and Closure Plan

**Status:** Open findings; the presence of a row in a register is not test execution.  
**Baseline artifacts:** [system audit](./phase-1-system-ui-audit.md), [UI route inventory](./ui-screen-inventory.csv), [per-screen state checklist](./phase-1-ui-state-coverage.csv), [workflow scenario register](./phase-1-workflow-scenario-register.csv), [API endpoint register](./phase-1-api-endpoint-register.csv), [role/workspace matrix](./phase-1-role-workspace-matrix.md), [test evidence map](./phase-1-existing-test-evidence.md), [data/cleanup map](./phase-1-data-and-cleanup-map.md), and [Android composition map](./phase-1-android-composition-map.md).

## 1. High-priority findings

| Finding | Severity | Source evidence / impact | Closure acceptance |
|---|---|---|---|
| GAP-01 — UI execution coverage absent | P1 | The current UI inventory and state checklist each contain 96 entries (29 Web, 19 Admin, 14 HRMS and 34 Android after normalizing the `people` and `people-hrms` platform labels); older documentation reported 94. Their status remains source-level only, and generated-route reconciliation is still required. No browser or Android device walkthrough evidence was captured in this pass. | Every active screen has a test environment, role fixture, primary-control walkthrough, loading/empty/error/retry/permission-denied state review, evidence URL and exact tested SHA. Redirect/wrapper/deprecated entries have a documented disposition. |
| GAP-02 — Browser/native UI automation not identified | P1 | Repository search did not identify Playwright, Cypress, Puppeteer, WebdriverIO, Compose UI test APIs, AndroidJUnit4, Maestro or Appium. Existing CI runs JVM Android unit tests and build, not a complete connected-device Compose suite. This is search evidence, not proof that no external test harness exists. | Select and add a supported browser E2E harness across student/parent, Admin and People Operations; add Android emulator/Compose UI test support for critical native flows; publish trace/screenshot/log artifacts from CI. |
| GAP-03 — API endpoint-to-UI contracts still need manual validation | P1 | `phase-1-api-endpoint-register.csv` now lists 443 mappings, including the eight new QA test-center endpoints; mappings and guard hints are still source-derived. Scenario links remain candidates and helper-built client URLs, filters and SQL effects need verification. | Confirm all mappings against Spring runtime/OpenAPI or a reliable source parser; inspect controller + AuthFilter + tenant context + called services/SQL; replace candidate labels only after endpoint-specific review; link positive/negative automated assertions. |
| GAP-04 — Per-screen state categories unreviewed | P1 | The 94-row state checklist deliberately marks loading/empty/error, validation, authorization, DB assertions, responsive/accessibility and destructive-confirmation categories `NOT_REVIEWED`. | Review each screen/component and mark each category verified, blocked or justified `NOT_APPLICABLE`, with case IDs and evidence. No global “screen passed” shortcut. |
| GAP-05 — QA lifecycle is implemented only as a starter slice and is not runtime-certified | P1 | The feature branch adds a run/resource/event/check manifest schema, default-off environment and QA-database gate, typed tenant/learner pack, manifest-driven uninstall, verification checks and an HRMS QA Center. The full HRMS/payroll/media/provider pack and exact-head runtime proof are still missing. | Run exact-head CI and disposable PostgreSQL acceptance tests for install rollback, retries, verification, run-A/run-B isolation, interrupted cleanup and baseline preservation. Continue the schema-wide constraints/write-path review before adding more resource builders. QA-003 and QA-006 remain unimplemented. |
| GAP-06 — Local preview isolation fix is on the candidate feature branch only | P1 | Current main still contains a broad `LOCAL_PREVIEW` deletion query and first-match preview login. The centralized QA feature branch removes the broad delete, pins login to the canonical preview UUID, and adds regression tests; it is not yet merged/runtime-verified. | Verify focused preview regression tests and the complete API suite; keep QA personas on the separate run marker, never on `LOCAL_PREVIEW`. |
| GAP-07 — Complete relational delete order not yet proven | P1 | Representative migrations show protected payment/entitlement/assessment/certificate/attendance/leave/ledger/audit relationships with `ON DELETE RESTRICT`, composite tenant keys and business uniqueness. The current data/cleanup map is partial, not a full schema dump. | Generate complete table/FK/unique/check/delete-action catalog; test the installer and cleanup with the real migrated schema; preserve immutable audit/ledger history or use explicit compensating entries. FK violations must stop and report cleanup, never force cascade. |
| GAP-08 — External side-effect inventory incomplete | P1 | OAuth, Razorpay, Meet/calendar, live media, storage, tutoring provider and realtime messaging have behavior outside SQL. Source tests cover individual primitives but no whole-pack resource lifecycle was executed. | Each integration has an explicit sandbox/fake adapter and capability-tested cleanup operation; capture exact provider/file IDs and prove no live charge, real learner notification or unowned object is created. |
| GAP-09 — Cross-workspace and cross-tenant E2E not run | P1 | API tests exist for selected workspace/tenant boundaries; role grants are workspace-scoped and institution-aware history uses tenant-scoped uniqueness. UI navigation cannot prove backend enforcement. | Exercise at least two institutions and all applicable workspace sessions. Test same identity where supported, distinct children/guardians, revoked grants, other-tenant IDs, hidden courses, reports, certificates, uploads and media endpoints. |
| GAP-10 — API route-to-table and side-effect graph incomplete | P1 | Endpoint mapping now covers mapping annotations, but no complete write-to-table, object-store, audit, async-job and external-resource graph exists for every endpoint. | Every mutating endpoint identifies affected entities, transaction boundary, uniqueness/idempotency, status transition, audit events, storage/provider keys, retry semantics and run-scoped cleanup rule. |
| GAP-11 — Browser/device parity incomplete | P1 | Android shell has 34 destinations, including conditional renderers for lesson, subject catalogue and live classes; the native JVM navigation contract does not execute the screens. | Execute identical synthetic persona/data scenarios across web and Android for courses/marketplace, Books, lessons, practice, parent reports/profile, certificates, live/recorded classes and errors. Compare API state plus observed UX. |
| GAP-12 — Accessibility/responsive behavior not executed | P2 | Static source scanning does not validate focus order, keyboard behavior, touch targets, reflow, screen-reader labels, Android IME/back gestures, orientation or zoom. | Record manual or automated results at compact phone, regular phone, tablet/landscape and desktop sizes, including keyboard-only where relevant, zoom/text size, dialogs and long/empty/error content. |

## 2. Source-present tests worth retaining

Existing source tests already cover substantial API logic. Examples:
- `AuthFilterTest`, `AuthServiceTest`, `AccountLifecycleControllerTest`: workspace separation, parent/child identity, session lifecycle and account transition constraints.
- `InstitutionTenantContextServiceTest`, `CourseMarketplaceTenantScopeTest`: tenant selection and exact-tenant published offer query behavior.
- `CourseMarketplaceControllerTest`: checkout signature and idempotency primitives.
- `AdminContentControllerTest`, `StructuredLessonArtifactServiceTest`: review/publish gates and safe learner payloads.
- `AssessmentLifecycleIntegrationTest`, `LearningControllerTest`: durable attempts, server-side grading and entitlement denial.
- `LearningClassControllerTest`, `LearningMessagingRulesTest`: join-window, private media and messaging rules.
- `CertificateControllerEligibilityTest`, `CertificateRendererTest`: eligibility and artifact behavior.
- `HrAttendanceLeaveControllerTest`, `HrPayrollControllerTest`: HRMS permission-before-write and selected approval/ledger rules.

These are not a substitute for running the tests on the exact current head, and their individual assertions may use mocks. Refer to [existing test evidence](./phase-1-existing-test-evidence.md) before converting a source-present case to verified.

## 3. Closure order — do not skip ahead

1. **Finish source reconciliation:** re-run the web route generator on the current SHA; classify wrappers/redirects; verify 34 Android routes against the shell and test dynamic renderer branches; validate the 443 mapping entries against actual handler bindings.
2. **Finish action/data trace:** for each screen's actionable controls, record request, auth/session workspace, tenant selector, required permission, response/error states, persisted entities, external effects and relevant scenario IDs.
3. **Establish executable verification:** select browser/native automation, safe role/tenant fixtures, cleanup/reset and trace/screenshot upload. Prove existing baseline tests pass before introducing data-pack test fixtures.
4. **Validate authorization and tenancy:** execute allow/deny tests for every sensitive family, cross-tenant and cross-workspace; record API and UI results independently.
5. **Complete schema/storage inventory:** extract all schema constraints/migration ownership; inspect every durable private asset and provider integration. Approve safe ownership and dependency graph.
6. **Review and sign off Phase 1:** each inventory entry has a disposition; each active-screen state is reviewed; critical scenario tests have evidence; blockers are tracked and no untested case is represented as passed.
7. **Starter lifecycle slice is underway:** the run manifest, default-off safety gate, tenant/learner install, invariant checks, uninstall plan and cleanup engine are on the feature branch. Do not expand to the full pack or merge until disposable PostgreSQL acceptance proves rollback, same-key retry/concurrency behavior, run-A/run-B isolation, safe uninstall and protected-baseline preservation. QA-003 scenario execution/report coverage and QA-006 provider sandbox isolation remain unimplemented.

## 4. Full-pack acceptance criteria (beyond the starter implementation)

A future QA Center is acceptable only if:
- It refuses install/uninstall outside an explicitly approved isolated test environment.
- It uses unique run IDs and avoids existing user/tenant/course/username/public-ID collisions.
- Test users are true synthetic identities with actual guardian, institution, permission and entitlement relationships.
- It includes successful and failing flows, wrong/expired values, permission denials, duplicate/replay requests, lifecycle transitions, empty/error states and cross-client parity.
- It records expected versus actual UI/API/DB outcomes for each case and preserves logs/evidence.
- It tracks every owned SQL row, external-provider object and storage key; uninstall is scoped strictly to that manifest.
- It will not delete the QuantaEdge platform tenant, local-preview baseline, shared curriculum, real customers, external provider objects it did not create, or audit/ledger history needed for integrity.
- Interrupted install/uninstall is retryable and idempotent; unresolved FK/provider cleanup blockers stop the operation and produce an actionable report.
- It proves with test assertions that unrelated data, other test runs and the preview baseline remain unchanged.

---

**Current decision:** The documentation foundation is substantial enough to organize the next audit runs; it is not evidence that Phase 1 is signed off. The installer and connected runtime test suite must remain out of scope until the open P1 findings have either been closed or explicitly accepted with a documented fallback.
