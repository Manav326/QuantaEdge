# HRMS Agent Coordination Protocol

## Purpose

This document is the persistent coordination point for all HRMS implementation work in QuantaEdge. Separate ChatGPT conversations do not share live context; agents must communicate through version-controlled contracts, focused commits, and explicit handoffs.

## Repository rules

- Work against the latest `main` only. Do not create branches or pull requests.
- Before editing, inspect the current `main` commit and the exact files/migrations being changed.
- Fetch the latest file version immediately before a write. Never replace a file using a stale copy.
- Make small, focused commits. Do not write the same file concurrently.
- Do not add synthetic employee/student/business records to production code or migrations.
- Do not create a database migration version until checking the latest migration version and reserving ownership in the integration status.
- If a commit depends on another module's unfinished contract, document the dependency and stop rather than guessing.
- A successful commit is not a successful build. Report CI/test state only when actually observed.

## Module ownership

| Agent | Primary ownership | Must coordinate with |
|---|---|---|
| Agent 1 — Core HR | Employee identity and lifecycle, organization, departments, designations, manager relationships, employment history, core HR permissions | All agents; owns the authoritative employee identity contract |
| Agent 2 — Attendance & Leave | Attendance events/corrections, leave lifecycle, working schedules, holiday calendars, leave balances and ledger | Core HR for employee/org identity and permissions |
| Agent 3 — Academic Workforce | Effective-dated teacher/professor assignments, class/subject/academic-period links, replacement history and approval | Core HR for employee identity; academic models and live-class consumers |
| Agent 4 — Payroll & Employee Self-Service | Salary components, payroll periods, calculations/approvals, payslips, reconciliation and employee self-service | Core HR for employee identity/lifecycle; Attendance for approved attendance/leave inputs |

## Required workflow

1. **Audit:** read the latest main commit, this protocol, integration status, relevant controllers/services/tests, and current migrations.
2. **Contract first:** document any new shared API, schema, status enum, permission, or migration dependency before implementing consumers.
3. **Implement:** keep changes within owned files where possible; coordinate shared files explicitly.
4. **Verify:** run the narrowest relevant tests and the module build when available. Inspect CI for the resulting commit.
5. **Handoff:** update `integration-status.md` with commit SHA, changed files, contract changes, verification actually run, blockers, and next dependent action.
6. **Integrate:** the lead checks compatibility across modules and schedules follow-up work. Do not claim cross-module integration merely because individual commits exist.

## Handoff template

- Module / owner:
- Base main SHA:
- Resulting commit SHA:
- Files changed:
- API/schema/permission contracts changed:
- Dependencies and consumers:
- Tests/build run (include exact command and result):
- CI result (link/check name, or “not observed”):
- Known gaps/blockers:
- Next action and responsible module:

## Shared contract change rules

- Core HR owns the canonical employee ID and employee lifecycle fields. Other modules reference the stable employee ID; they must not create a second employee identity.
- Organization-scoped records must validate that referenced employees/departments/designations belong to the same organization where applicable.
- Cross-module writes require explicit authorization, transaction boundaries, and auditable actor/reason fields where the operation is consequential.
- Migrations are append-only. Never edit an already-applied migration to change its meaning.
- API consumers must use authenticated, permission-checked server endpoints; hiding a UI control is not authorization.
- Keep People Operations as its own portal. Do not move its functionality into the general admin portal without an explicit product decision.

## Lead integration checklist

- [ ] Latest main and shared docs reviewed
- [ ] No conflicting migration versions
- [ ] Employee/org identifiers and lifecycle semantics align
- [ ] Permission names and authorization boundaries align
- [ ] API request/response contracts align with web clients
- [ ] Auditing and transaction semantics reviewed
- [ ] Relevant tests and full build results recorded
- [ ] Integration status updated with evidence
