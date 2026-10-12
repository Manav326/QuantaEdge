# HRMS Integration Status

This file is the shared handoff board for the Core HR, Attendance & Leave, Academic Workforce, and Payroll/ESS workstreams. Update it after each meaningful implementation commit. A listed commit means the commit was observed on GitHub; it does not by itself mean tests passed.

## Current baseline

Last main commit audited before this status update: `59b9a704a268ab7d22d1b68a71920410f928902d` — `docs: require shared contracts and handoffs for all agents`.

Observed recent main history also includes:
- `37023a8ca469b776f0ac3ed65a6c111255e61f3a` — test empty leave ledger migration
- `70b9a44b22caa3ff7628659c36cb19630a6d432d` — leave balance permissions and transaction validation tests
- `acd24c218629d85be3c31afce6afd7477bb31cdf` — serialize leave balance reductions
- `883e46aa432ef5210d5b4584d86586f053ec9c32` — permission-checked leave balance ledger
- `585cd4240500e230e863921fbb50e4bd58a4b8b3` — auditable leave balance ledger
- `a1f9d42c28a87f985296c6f2cebfeae350f78c8d` — prevent reporting-manager hierarchy cycles
- `d44b28fd8472f9414bcb79ded342941dbb560f78` — test reporting hierarchy cycles

## Workstream handoffs

### Agent 1 — Core HR
- Existing API base: `/api/v1/hr`.
- Observed endpoints include organization and work-schedule reads/updates, employment-type/department/designation reads and creation, employee directory/detail/history, employee create/update, and employment-status update.
- Permission names observed in the core controller: `HR_EMPLOYEE_VIEW` and `HR_EMPLOYEE_MANAGE`.
- Manager references are checked for same-organization active status and recursive reporting-cycle prevention.
- Employee history is exposed at `GET /api/v1/hr/employees/{id}/history`.
- Latest Core HR commit observed before this document: `d44b28fd8472f9414bcb79ded342941dbb560f78`.
- Additional commits in this coordination pass:
  - `ac6f22d92c0a091dd7a52efd2e9c975904b0993f` — validate email syntax and E.164 phone format on create/update.
  - `a1e3ecc9b2af684393728d49b289f5fad8eb1aed` — add invalid email/phone regression tests.
- Current input validation rejects invalid email and non-E.164 phone values before database access during employee creation.
- CI run for the contact-validation test commit `a1e3ecc9b2af684393728d49b289f5fad8eb1aed`: [QuantaEdge Build and Publish #1631](https://github.com/Manav326/QuantaEdge/actions/runs/38294894090) completed successfully. Observed successful jobs included API verification, API image, admin image, web image, People Operations image, and content import safeguards.
- The later docs-only run for the current main status commit was queued/in progress at the last check; it is not being represented as passed.
- Next checks: lifecycle transition consistency, department hierarchy integrity, full build/test verification, and inspect latest CI result.

### Agent 2 — Attendance & Leave
- Recent observed work includes an auditable leave-balance ledger, permission-checked ledger endpoint, serialized balance reductions, ledger migration test, and rejection of manual leave consumption before database access.
- Latest observed commit at baseline: `c610d34bf8b3ed120627a4e6921727b306df185d`.
- Required handoff: document exact leave/attendance endpoint paths, permission names, employee/org foreign keys, migration versions, and test/build evidence here after audit.

### Agent 3 — Academic Workforce
- Status: awaiting a verified handoff from the agent's latest main audit.
- Required handoff: exact assignment schema/migrations, effective-date semantics, approval/replacement lifecycle, API paths, permissions, and tests.

### Agent 4 — Payroll & Employee Self-Service
- Status: awaiting a verified handoff from the agent's latest main audit.
- Required handoff: salary component and payroll-period schema, calculation/approval states, payslip/reconciliation APIs, ESS authorization model, migration versions, and tests.

## Integration blockers / unverified items

- Repository-wide contributor/agent instructions: [AGENTS.md](../../AGENTS.md).
- Shared coordination protocol: [agent-coordination.md](agent-coordination.md).
- Verified shared identity/API contract: [shared-contracts.md](shared-contracts.md).
- The latest observed commit history does not establish that the full API build or all HRMS tests passed.
- CI status and workflow results must be checked for the current main SHA; if no result is returned, report it as unobserved rather than green.
- Exact attendance, academic assignment, and payroll/ESS contracts must be copied from source after auditing their current files, not inferred from commit titles.
- Cross-module integration is not complete until the three specialist handoffs are recorded and the combined build/tests are verified.

## Update format

For each new entry, record:
1. Date (UTC), module and commit SHA
2. Exact files/migrations changed
3. Contract impact and affected consumers
4. Exact tests/build commands and observed results
5. CI check links/results or “not observed”
6. Open blockers and next owner/action
