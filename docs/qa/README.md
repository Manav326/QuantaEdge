# QuantaEdge QA & Test Environment

**Status:** Phase 1 — system and UI audit in progress  
**Scope:** Student/parent Web, Android, Educational Admin, People Operations/HRMS, shared API, persistence, authorization, and external integrations.

## Documents

- [Phase 1 — System and UI Audit](./phase-1-system-ui-audit.md): verified source baseline, application boundaries, audit findings, persona/workflow map, and exit criteria.
- [UI Screen Inventory](./ui-screen-inventory.csv): machine-readable register of web routes and Android navigation destinations.
- [Phase 1 Source Review Log](./phase-1-source-review-log.md): first-pass actions/API observations by route and Android shell mappings.
- [API, Workspace and Permission Boundary Map](./phase-1-api-permission-map.md): inspected authorization/workspace guards and sensitive controller families.
- [Data Relationship and Cleanup Map](./phase-1-data-and-cleanup-map.md): identity/tenant/course/learning/HRMS dependencies, protected baseline data and safe-uninstall requirements.
- [Workflow Scenario Register](./phase-1-workflow-scenario-register.csv): 76 stable cross-platform test scenarios, expected outcomes, negative cases, fixture dependencies and cleanup rules.
- [Existing Test Evidence](./phase-1-existing-test-evidence.md): links current API/Android test sources to scenario domains and separates tests-present from runtime-verified evidence.
- [Android Composition Map](./phase-1-android-composition-map.md): maps all 34 native routes to their actual shell/component renderers and documents BackHandler return contracts.
- [UI State Coverage Checklist](./phase-1-ui-state-coverage.csv): one row for each of the 96 web/native destination entries, with separate columns for controls, loading/empty/errors, validation, authorization, data assertions, responsiveness/accessibility and destructive actions.
- [API Route Inventory](./phase-1-api-route-inventory.csv): source-extracted register of 448 Spring request/STOMP route mappings across 51 Java source files, including path composition, method name, source line and static guard hints.
- [Role/Workspace Permission Matrix](./phase-1-role-workspace-matrix.md): source-derived Education Admin, HRMS, platform-owner, institution-admin and learner/guardian permission boundaries.
- [Runtime Execution Log](./phase-1-runtime-execution-log.csv): one execution row per scenario, ready to capture the exact commit/environment/persona/device, actual result, evidence, defect and cleanup verification.
- [Consolidated Coverage Gaps](./phase-1-coverage-gaps.md): prioritized open findings and the required closure sequence before Phase 2 test-data installation.
- [Centralized QA Test-Pack Lifecycle](./qa-test-pack-lifecycle.md): required install/uninstall state machine, immutable resource ownership manifest, production safety gates, automated acceptance matrix and implementation ledger.

## Current test-pack lifecycle work

The install/uninstall requirements and acceptance matrix are tracked in [Centralized QA Test-Pack Lifecycle](./qa-test-pack-lifecycle.md). The starter pack is being expanded to **at least three institutions per run** to exercise origin ownership, HRMS-approved cross-institution assignment, and a third institution that must remain unassigned/isolated. The feature branch is **not merged or runtime-verified**. QA-001, QA-002, QA-004, QA-005 and QA-007 remain `NOT_EXECUTED`; QA-003 (scenario runner/report coverage) and QA-006 (external-provider sandbox isolation) remain `NOT_IMPLEMENTED`. A register validator validates CSV/document consistency only; it does not prove runtime execution.

## Current audit snapshot

| Measure | Current count/status |
|---|---:|
| UI route/destination entries | 102 (32 Web, 21 Admin, 15 HRMS, 34 Android) |
| API mapped handler entries | 448 request/STOMP mapping rows across 51 source files; source extraction only |
| Screen entries linked to scenario IDs | 102; references validated against the scenario register |
| Workflow scenarios | 76 total: 74 `NOT_EXECUTED`, 2 `NOT_IMPLEMENTED` (QA scenario runner/report and external-provider safety) |
| Browser/Android runtime-verified screens | 0 fully verified; first GitHub Playwright run started the app stack but stopped at test-setup failures before route walkthroughs. This does not establish site outage |
| Per-screen state coverage | 102 rows; controls/navigation are partial source scans; state categories remain `NOT_REVIEWED` |
| Test-data ownership/uninstall implementation | QA pack/HRMS QA Center remain not certified. The feature-branch browser E2E harness started Web, Education Admin, HRMS, API, PostgreSQL and Redis on a disposable stack; setup failures still block deeper flows and all 76 workflow scenarios remain not fully verified |

Counts describe documentation inventory, not feature pass rates or functional coverage percentages.

## Evidence and status rules

| Status | Meaning |
|---|---|
| `SOURCE_INVENTORIED` | A page entry point or native navigation destination has been identified in source. |
| `SOURCE_SCAN_PARTIAL` | The route entry and/or navigation mapping has been scanned for key actions, API paths and state-handling signals, but imported components, permissions or persistence contracts remain untraced. |
| `SCENARIO_MAPPED` | The screen/API operation is linked to one or more existing scenario IDs; the mapping itself is not evidence that the scenario passed. |
| `SOURCE_REVIEWED` | Relevant source behavior, event handlers, API calls and permissions have been inspected. |
| `RUNTIME_NOT_VERIFIED` | No reproducible browser/device execution evidence is recorded for that item. |
| `AUTOMATED_VERIFIED` | An identified automated test ran against a named revision and its assertions passed. |
| `MANUAL_VERIFIED` | A named reviewer completed the steps and retained environment/device, revision, result and evidence. |
| `BLOCKED` | A reason prevents execution; this is not a pass. |
| `NOT_APPLICABLE` | A reviewer records why the particular check does not apply. |
| `NOT_REVIEWED` | A screen state/control category has not yet been investigated. |
| `IN_PROGRESS` | Source tracing or execution is underway; not a pass. |
| `VERIFIED` | Reproducible execution/assertions passed with a named revision, environment and evidence reference. |
| `BLOCKED` | A specific blocker and owner/next action are recorded; not a pass. |

A source file, successful compilation, static contract check or older tracker entry is not equivalent to an end-to-end pass. Verification must be tied to a commit SHA and execution evidence. In the per-screen checklist, `NOT_REVIEWED` means the state must still be examined; use `NOT_APPLICABLE` only after source/runtime review records a reason, never to hide an untested state.

## Register consistency guard

The CI `changes` job runs `scripts/validate_qa_registers.test.mjs` and `scripts/validate_qa_registers.mjs --root .`. These checks verify CSV structure, unique screen/API/scenario IDs, valid scenario references, one runtime-log row per scenario, and evidence metadata before a result can be marked passed. **They validate documentation integrity only**; they do not claim that a UI/API workflow executed or passed.

## Audit rules

1. Inventory every route and meaningful in-screen workflow, including tabs, dialogs, menu actions, validation, loading/empty/error/success states and destructive actions.
2. Map each behavior to its persona, permission, API contract, persisted entities, external side effects and one or more stable test-case IDs.
3. Test authorization at the API boundary; hiding a navigation link is not access control.
4. Distinguish platform-owner operations from institution administration, Educational Admin from HRMS, and adult/student/guardian lifecycle rules.
5. Test tenant isolation with multiple synthetic institutions and positive and negative access scenarios.
6. Test payment, identity, calendar/meeting, messaging and AI-provider integrations using supported sandbox/test modes or safe test doubles. A test run must never accidentally charge a real payment method, publish to real learners, or send unwanted real messages.
7. Future test-data packs must be versioned, repeatable, run-scoped, auditable and uninstallable. Cleanup must delete only resources demonstrably owned by that run; a table-wide delete by test flag is forbidden.
8. Keep findings, requirements, implementation status and verification status separate. Update this documentation when a route, permissioned workflow or material integration changes.

## Phase 1 completion gate

Phase 1 is complete only after source routes/destinations are reconciled with runtime navigation; each active screen's controls and observable states are recorded; persona, API, permission, data and provider dependencies are traced; responsive/accessibility and error-state walkthroughs have evidence; and unresolved/blocked checks are explicit. Test-pack schema and seeding implementation must follow this audit, not precede it.
