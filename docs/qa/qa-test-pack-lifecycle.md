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
- **Database safety:** before any mutation, the server confirms the current database is an approved QA/test/CI database using an explicit environment allow-list. Production profiles and production-labelled targets are always rejected. The caller cannot submit an arbitrary database, schema, SQL statement, file path or resource locator.
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

- two institutions to exercise tenant boundaries;
- platform-owner operations remain the real operator identity, not a seeded backdoor;
- institution-admin test identities for their own institution;
- adult learner, parent/guardian and minor child identities with real guardian/consent relationships;
- normal student and child login pathways with unique accounts and session isolation;
- institution membership, course release/offer/access, and shared published curriculum referenced read-only;
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
| **QA-005 Cross-run/baseline safety** | Install runs A and B, uninstall A, and prove B plus preview student, QuantaEdge tenant, pre-existing users, shared curriculum and existing business data remain unchanged. |
| **QA-006 External safety** | No real charge, payout, SMS/email, public broadcast or unowned storage/provider object; sandbox IDs are recorded and exact cleanup is verified. |
| **QA-007 Environment gate** | Operations are rejected with zero writes if flags are absent, target database is production/unknown, or profile/environment evidence conflicts. |

Add interruption tests for failure after each ownership-producing step, partial provider cleanup, missing/corrupt manifests, FK restrict blockers, and retry after process restart. Successful installation alone is not acceptance: uninstall and protected-baseline proof are mandatory.

## 7. CI and evidence rules

- API unit tests cover authorization, state machine, idempotency, environment gate, ownership-manifest validation and cleanup ordering.
- Disposable PostgreSQL integration tests apply the actual Flyway migrations and verify database constraints, transactional rollback, multiple concurrent runs and post-uninstall before/after snapshots.
- Web E2E must exercise QA Center permissions, install confirmation, persona list, scenario runner, dry-run preview, confirmation phrase/run-ID match, interrupted cleanup and report download.
- Android E2E must use the same installed synthetic personas to exercise the agreed critical learner/parent flows and compare their API-visible state.
- External adapters run in deterministic fake mode in CI; a test fails if a live credential/environment is detected.
- Each report records exact tested commit SHA, database target label, pack version/checksum, runner/browser/device, outcome, evidence links and cleanup verification.
- Do not convert a source scan, successful compile, register-validator run, or unrelated green job into a runtime pass.

## 8. Current implementation ledger

- QA route inventory, 72 workflow scenarios, 93 UI/state inventory rows, 435 source-derived API mapping rows and role/data cleanup documentation exist. Most scenarios remain unexecuted; endpoint mappings are not yet fully reviewed.
- QA-001 through QA-007 currently remain NOT_IMPLEMENTED until the end-to-end lifecycle operations and their acceptance evidence exist.
- The previous local-preview seed had a broad deletion of other LOCAL_PREVIEW students and preview login selected the first matching row. A candidate fix and regression tests are in [PR #78](https://github.com/Manav326/QuantaEdge/pull/78); its API CI job failed because the broader suite currently has institution-context/mocking failures. The candidate has not been represented as passing or merged.
- The current-main CI audit recorded a failed API run [#39151812977](https://github.com/Manav326/QuantaEdge/actions/runs/39151812977). Keep that baseline regression separate from new QA feature results.
- No production database, real payment, live message, or external provider was used in the work documented here.

## 9. Next implementation sequence

1. Fix local-preview ownership collision and establish clean focused regression evidence.
2. Add a default-off, production-rejecting QA environment gate and persistent run/resource manifest schema.
3. Implement the server-side allow-listed pack builder and dependency-aware uninstall/rollback engine; never accept SQL or paths from the browser.
4. Expose protected HRMS/platform-owner APIs and QA Center UI with dry-run confirmation and run report.
5. Add disposable-PostgreSQL lifecycle tests; expand the pack through identity, tenant, learning, and HRMS relationships only where the exact schema and cleanup adapter are verified.
6. Wire the browser/device automated flows, publish traces/reports, and close QA-001–QA-007 only against exact-SHA evidence.
