# Centralized QA Test-Pack Lifecycle

**Owner goal:** install and uninstall realistic, interconnected synthetic test data through a controlled QA Center, with reusable automated tests covering Web, Android, Education Admin, HRMS, API authorization, database relationships and side-effect isolation.

**Status as of 2026-10-18:** implementation in progress. A design entry or passing source validator is not evidence that the full installer/uninstaller or UI workflows have passed.

## 1. Required user experience

The platform-owner-only QA Center will live in the People Operations / HRMS workspace. It will let an authorized operator:

1. inspect whether the current deployment is explicitly approved for QA-data operations;
2. view versioned test packs and their persona/relationship coverage;
3. install a pack into a new, uniquely identified run;
4. inspect generated identities and linked records without exposing secrets;
5. run selected automated scenarios against those identities;
6. preview uninstall impact before confirming the exact run ID;
7. uninstall one run, retry cleanup after interruption, and download the result report.

The UI must explain blocked/unknown states and partial cleanup. It must never make a production environment appear safe merely because the operator has a platform-owner permission.

## 2. Non-negotiable safety boundaries

- **Default off:** QA write operations require explicit feature enablement and explicit environment approval. Missing, malformed or conflicting configuration fails closed.
- **Database safety:** before any mutation, the server confirms the current database name has an approved QA/test/CI suffix and exactly matches the server-owned `APP_QA_TARGET_DATABASE`; the caller cannot select or override the database. `APP_QA_ENABLED`, `APP_QA_ENVIRONMENT_APPROVED`, `APP_QA_ENVIRONMENT_LABEL`, `APP_QA_TARGET_DATABASE` and `APP_QA_FIXTURE_PASSWORD` must all be configured consistently. Production/live profiles and production-labelled targets are rejected. The caller cannot submit an arbitrary database, schema, SQL statement, file path or resource locator.
- **Permission:** API authorization requires the existing QE_PLATFORM_OWNER capability in the HRMS workspace. UI visibility is not authorization.
- **Run ownership:** every install has a random immutable run UUID, pack key/version, actor, target environment, source revision, start/end times, state, counts and a checksum over its manifest.
- **Exact manifest:** each created SQL row, identity, uploaded file and external provider object is registered under the run using its actual primary/public ID or exact provider/storage key. Manifest ownership is authoritative; a generic test flag is not sufficient for deletion.
- **No broad cleanup:** never delete by LOCAL_PREVIEW, is_test_data, ID range, age, wildcard path or whole-table truncation. Never delete shared curriculum/reference data, the canonical preview student, the QUANTA-EDGE institution, unrelated accounts, other QA runs, or records the run merely referenced.
- **Dependency-safe cleanup:** stop scheduled test work and revoke run-owned sessions first; clean external resources via registered resource adapters; remove child/transactional rows before parent identities; stop and report any ambiguous owner or FK restriction.
- **Audit integrity:** keep the run and cleanup manifest/report after uninstall. Financial and immutable audit/ledger records must be retained or reversed using the owning business process rather than hard-deleted.
- **External side effects:** fake providers by default. A real provider is allowed only through a named sandbox adapter that proves its mode and supports exact-resource cleanup. If a provider has no safe cleanup contract, the relevant scenario cannot create that resource.
- **No secret disclosure:** generated credentials can be returned only in the one-time install response to the authorized operator if specifically required; never store plaintext passwords/tokens in the manifest, logs or downloadable reports. Prefer fixed synthetic credentials supplied only to isolated CI fixtures and store password hashes in application tables.

## 3. Run state machine

Allowed forward transitions:

PREPARING → INSTALLING → INSTALLED

A failure during installation transitions to INSTALL_FAILED only after the operation records what committed and what rollback/compensation remains. Retrying the same idempotency key must resume/reconcile the same run, not create duplicate users.

Uninstall uses:

INSTALLED → UNINSTALLING → UNINSTALLED

On blocker/error, the run becomes UNINSTALL_BLOCKED with exact unresolved resources and retry guidance. Repeating uninstall is idempotent. A run is never labelled UNINSTALLED while owned resources remain pending/failed.

Concurrent installs with the same idempotency key must serialize. Concurrent install/uninstall of one run must be rejected or serialized. State mutation, manifest writes and database row creation need an atomic transaction or explicitly journaled compensation boundary.

## 4. Manifest contract

Each run records at least:

- run_id, pack_key, pack_version, immutable pack-definition checksum;
- creator staff ID, approved environment label and exact database target ID/label;
- QuantaEdge commit SHA and test-runner version;
- state, timestamps, idempotency key, retry count and summary error;
- counts planned/created/verified/pending/cleaned/failed;
- resource entries: resource type, owning table/kind, actual primary/public ID or exact file/provider ID, creation status, dependency keys/order, cleanup adapter/version and before/after result;
- protected-baseline fingerprints captured before the run and rechecked after uninstall;
- scenario execution results, evidence references, redacted logs, and external-side-effect verification;
- a final cleanup report, including retained immutable records and every unresolved blocker.

Do not store arbitrary executable SQL in the pack definition. A versioned allow-listed pack uses typed fixture builders and named cleanup adapters.

## 5. Pack coverage

The first full cross-platform pack must use synthetic, linked personas rather than unrelated rows:

- **at least three test institutions in every QA run**, in addition to the protected QuantaEdge institution. QuantaEdge remains the source owner of Classes 6, 7 and 8; the QA pack creates run-owned release revisions from those existing courses, HRMS explicitly assigns Class 7 to Institutions A and B and Class 6 to Institution C, and C must not inherit Class 7 without its own assignment;
- platform-owner operations remain the real operator identity, not a seeded backdoor;
- institution-admin test identities for their own institution;
- adult learner, parent/guardian and minor child identities with real guardian/consent relationships;
- normal student and child login pathways with unique accounts and session isolation;
- all login identities and learner profiles belong to their institution; every release has a real source institution, every assignment has an owning destination institution, and every learner enrolment matches the learner’s institution membership plus an explicit active course assignment. The existing QuantaEdge course masters and shared curriculum are preserved. Run-owned synthetic Class 6/7 lessons/questions/options are individually manifested and removed by exact ID; none is seeded into product migrations.
- varied learning progress, practice answers, assessment attempts/results and certificates created through supported domain flows;
- teacher/HR employee records, assignments, attendance, leave and payroll in a synthetic organization, with no real payout or statutory side effect;
- live/recorded class references, messaging, resource and file fixtures using fake/sandbox services;
- invalid/expired/revoked states and deliberately different child/tenant data to make cross-account leaks observable.

Synthetic identities need unique run namespacing, while business keys/public IDs follow the real domain validators. Shared content is borrowed read-only unless a test explicitly creates a namespaced draft through a supported API.

## 6. Automated acceptance matrix

The existing workflow register IDs remain the primary acceptance contract:

| Scenario | Must prove |
|---|---|
| **QA-001 Install** | Successful pack install; exact manifest matches created records; relationships can be queried; setup is repeatable. |
| **QA-002 Idempotency** | Same key/retry/concurrent request cannot create duplicates; each distinct run stays isolated. |
| **QA-003 Inspect/report** | Authorized operator sees personas, status and coverage; ordinary users cannot access the QA Center or another run. |
| **QA-004 Uninstall** | Preview is accurate; cleanup follows dependency order; only the selected run's owned resources are removed/reversed. |
| **QA-005 Cross-run/baseline safety** | Install runs A and B, each with three test institutions; prove QuantaEdge-owned Class 7 is assigned to A/B by HRMS, Class 6 to C, and Class 7 is denied in C. Uninstall run A while proving run B, the real QuantaEdge tenant and Classes 6/7/8, existing users, shared curriculum and business data remain unchanged. |
| **QA-006 External safety** | No real charge, payout, SMS/email, public broadcast or unowned storage/provider object; sandbox IDs are recorded and exact cleanup is verified. |
| **QA-007 Environment gate** | Operations are rejected with zero writes if flags are absent, target database is production/unknown, or profile/environment evidence conflicts. |

Add interruption tests for failure after each ownership-producing step, partial provider cleanup, missing/corrupt manifests, FK restrict blockers, and retry after process restart. Successful installation alone is not acceptance: uninstall and protected-baseline proof are mandatory.

## 7. CI and evidence rules

- API unit tests cover authorization, state machine, idempotency, environment gate, ownership-manifest validation and cleanup ordering.
- `QaTestPackLifecycleIntegrationTest` targets the dedicated `quantaedge_qa` database and applies actual Flyway migrations. Each isolated run creates three QA institutions under HRMS, institution-admin fixtures for A/B/C plus a run-owned QuantaEdge course-owner admin, a run-owned HRMS/platform assignment actor and a separate content reviewer. Login identities and student profiles have active institution memberships. QuantaEdge remains the source owner of Classes 6/7/8; the run adds exactly two disposable release revisions and HRMS assigns Class 7 to A/B and Class 6 to C. Institution C must not inherit Class 7. Each learner enrollment must match that institution’s explicit course assignment; test progress/answers use run-owned synthetic Class 6/7 lessons/questions/options. The integration test checks idempotent replay, these ownership/denial boundaries, uninstalls run A while proving run B and its course fixtures remain, checks protected-baseline fingerprints, and then cleans run B. CI provisions this database and opts into the test. Runtime status is `PASS` only after exact-head CI succeeds.
- Web E2E must exercise QA Center permissions, install confirmation, persona list, scenario runner, dry-run preview, confirmation phrase/run-ID match, interrupted cleanup and report download.
- Android E2E must use the same installed synthetic personas to exercise the agreed critical learner/parent flows and compare their API-visible state.
- External adapters run in deterministic fake mode in CI; a test fails if a live credential/environment is detected.
- Each report records exact tested commit SHA, database target label, pack version/checksum, runner/browser/device, outcome, evidence links and cleanup verification.
- Do not convert a source scan, successful compile, register-validator run, or unrelated green job into a runtime pass.

## 8. Current implementation ledger

- QA route inventory, 72 workflow scenarios, 94 UI/state inventory rows, 443 source-derived API mapping rows and role/data cleanup documentation exist. Endpoints remain source-derived and not fully reviewed.
- The feature branch now implements a self-contained three-institution starter pack, run/resource/event/check manifest, institution-linked admins/users/students, run-owned release revisions sourced from QuantaEdge-owned Classes 6/7, HRMS-controlled cross-institution assignments, tenant-scoped enrolments, exact run ownership for synthetic Class 6/7 lesson/question/options, a default-off isolated-database gate, relationship/boundary checks, manifest-based uninstall, and an HRMS QA Center UI/API. The disposable PostgreSQL acceptance test and CI provisioning still require a passing exact-head run. Full HRMS/payroll/media/provider scenarios and browser/device scenario execution remain future work.
- QA-001, QA-002, QA-004, QA-005 and QA-007 have starter-slice code but remain NOT_EXECUTED; QA-003 scenario runner/coverage report and QA-006 external-provider sandbox isolation remain NOT_IMPLEMENTED.
- The local-preview isolation fix with regression tests is included on this feature branch. Draft PR #78 still exists separately and had a failing API run because of broader institution-context/mocking failures; do not describe either candidate as merged or passing without exact-head CI evidence.
- The current-main API run [#39151812977](https://github.com/Manav326/QuantaEdge/actions/runs/39151812977) failed before this feature branch. Keep that baseline regression separate from the new branch's results.
- No production database, real payment, live message, or external provider was used in the work documented here.

## 9. Next implementation sequence

1. Verify preview isolation, server-configured database targeting, the manifest/check schema, and the dedicated PostgreSQL lifecycle test through exact-head CI; fix the pre-existing institution-context API test failures rather than masking them.
2. Prove starter-pack install rollback, same-key retry/concurrency, data invariant checks and manifest reconciliation against a disposable migrated PostgreSQL database.
3. Install run A and run B, uninstall A, and prove B and protected baseline rows remain unchanged; test interrupted cleanup and retry.
4. Implement QA-003 scenario execution/reporting and QA-006 fake/sandbox provider adapters before claiming complete lifecycle coverage.
5. Expand typed builders into HRMS/payroll, commerce, certificates, live classes/media and storage only after the relevant schema and external cleanup semantics are proven.
6. Add Web E2E and Android emulator flows, publish exact-SHA reports and only mark each scenario complete when its install/verify/uninstall and preservation assertions pass.
