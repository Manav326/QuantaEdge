# QuantaEdge QA & Test Environment

**Status:** Phase 1 — system and UI audit in progress  
**Scope:** Student/parent Web, Android, Educational Admin, People Operations/HRMS, shared API, persistence, authorization, and external integrations.

## Documents

- [Phase 1 — System and UI Audit](./phase-1-system-ui-audit.md): verified source baseline, application boundaries, audit findings, persona/workflow map, and exit criteria.
- [UI Screen Inventory](./ui-screen-inventory.csv): machine-readable register of web routes and Android navigation destinations.
- [Phase 1 Source Review Log](./phase-1-source-review-log.md): first-pass actions/API observations by route and Android shell mappings.
- [API, Workspace and Permission Boundary Map](./phase-1-api-permission-map.md): inspected authorization/workspace guards and sensitive controller families.
- [Data Relationship and Cleanup Map](./phase-1-data-and-cleanup-map.md): identity/tenant/course/learning/HRMS dependencies, protected baseline data and safe-uninstall requirements.
- [Workflow Scenario Register](./phase-1-workflow-scenario-register.csv): 70 stable cross-platform test scenarios, expected outcomes, negative cases, fixture dependencies and cleanup rules.
- [Existing Test Evidence](./phase-1-existing-test-evidence.md): links current API/Android test sources to scenario domains and separates tests-present from runtime-verified evidence.

## Evidence and status rules

| Status | Meaning |
|---|---|
| `SOURCE_INVENTORIED` | A page entry point or native navigation destination has been identified in source. |
| `SOURCE_SCAN_PARTIAL` | The route entry and/or navigation mapping has been scanned for key actions, API paths and state-handling signals, but imported components, permissions or persistence contracts remain untraced. |
| `SOURCE_REVIEWED` | Relevant source behavior, event handlers, API calls and permissions have been inspected. |
| `RUNTIME_NOT_VERIFIED` | No reproducible browser/device execution evidence is recorded for that item. |
| `AUTOMATED_VERIFIED` | An identified automated test ran against a named revision and its assertions passed. |
| `MANUAL_VERIFIED` | A named reviewer completed the steps and retained environment/device, revision, result and evidence. |
| `BLOCKED` | A reason prevents execution; this is not a pass. |
| `NOT_APPLICABLE` | A reviewer records why the particular check does not apply. |

A source file, successful compilation, static contract check or older tracker entry is not equivalent to an end-to-end pass. Verification must be tied to a commit SHA and execution evidence.

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
