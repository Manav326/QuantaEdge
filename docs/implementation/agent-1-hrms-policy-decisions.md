# Agent 1 — HRMS Policy Decisions and Safety Gaps

Status: implementation contract for `agent/hrms-core-attendance-leave`

This document records the product-owner decisions received on 2026-10-13. It is not evidence that every API/database enforcement point has been implemented or tested.

## Approved decisions

1. **Working week:** six working days per week by default; an organization may configure five. Store the organization-level setting as the authoritative value. Do not infer weekends from a browser locale.
2. **Attendance correction:** support both an employee/authorized correction request with approval and a direct correction by an explicitly authorized HR operator. Every correction must preserve the prior approved value and record actor, timestamp, reason, and approval/reversal linkage. The permission to mark attendance is not automatically permission to correct historical attendance.
3. **Leave types:** configurable paid and unpaid types initially. Eligibility, whether a balance is required, whether negative balance is allowed, and approval requirements must be explicit policy attributes; do not silently treat all leave types alike.
4. **Leave accounting:** balances are derived from an append-only transactional ledger. Grants, accruals, usage, reversals, and adjustments require effective date, actor, reason, and source/reference where applicable. Corrections are compensating transactions, not updates/deletes of posted transactions. A balance cache, if used, is rebuildable and not authoritative.
5. **Holidays:** provide an India-oriented baseline calendar with organization-level overrides. The baseline must identify its jurisdiction and source, and organization overrides must be explicit. National, state, local, and optional holidays must not be conflated.
6. **Lifecycle approvals:** configure approval requirements by transition and permission. Enforce the transition graph and approver authorization on the API, not just in the UI. Self-approval is prohibited for a request/transition raised by the same actor unless a future explicitly approved policy says otherwise.
7. **Calendar scope:** organization-wide by default. No department-specific or employee-specific calendar overrides are in scope for this iteration.
8. **Manager access:** only reporting employees; a manager is not automatically an HR administrator. Require a verified reporting relationship and explicit permission for each read/write capability.
9. **History:** preserve approved history. Corrections and reversals are audited events linked to the original record; approved records and posted ledger transactions are not silently overwritten or deleted.
10. **Delivery:** implement the in-scope employee lifecycle, organization/calendar, attendance, leave, approvals, ledger, permission and audit foundations together, with tests and an explicit integration handoff.

## Safety constraints

- Employee employment status, department, designation, manager relation, and academic assignment never grant platform administrator, CMS, or unrelated permissions.
- Validate that an employee, manager, leave request, attendance record, leave type, calendar and organization belong to the same authorized organization.
- Never accept actor identity, approver identity, organization scope, or final balance from client-supplied fields; derive identity and permissions from the authenticated API session and calculate balances server-side.
- Reject invalid dates, reversed ranges, overlapping requests, duplicate effective records, invalid status transitions, and unauthorized cross-organization access.
- Use organization-local calendar dates for attendance/leave. Store instants with timezone offsets where applicable; do not convert a date-only leave request into a UTC instant.
- No fabricated employee, attendance, leave, balance, or holiday records may be seeded in production.
- Keep separated employee records and their audit history; deactivation is not hard deletion.

## Gaps requiring backend proof before claiming completion

The current People Operations attendance screen already calls attendance, leave request/decision, work-schedule, holiday, and leave-ledger endpoints. The visible attendance form sends a `PUT` to the employee/date resource and states that it updates the existing record. That contract alone does **not** prove an immutable correction trail or approval workflow for historical corrections. The API/database must be inspected and tests must prove the preservation behavior before this is considered safe.

The UI previously fell back to five working days when the organization response omitted the setting. The UI fallback is now six, but the authoritative organization creation/default path and existing organizations must also be migrated/normalized to six days. A frontend default alone is insufficient.

The calendar is organization-managed and does not seed dates automatically. A permission-controlled import accepts bounded entries from an HTTPS government domain and stores source type, URL, jurisdiction, year, verification timestamp and verifying staff member. This is a recorded staff attestation, not automatic validation of every date. Import the central/state/district calendar that actually applies to the organization; manual entries remain explicit organization overrides.

The visible balance screen records `GRANT`, `ACCRUAL`, and `ADJUSTMENT` transactions and reads a ledger. The screen does not establish whether posted transactions can be updated/deleted, whether leave consumption/reversal is atomic with request approval, or whether actor/reason/source fields are enforced server-side. These are mandatory backend checks.

Transition-specific approval configuration, employee correction requests, and manager reporting-tree scope have been added to API code and migrations. Manager scope activates only when HR_MANAGER_TEAM_VIEW is explicitly granted, and must be combined with the relevant HR view/manage permission. Integration tests must still prove list, detail, update, decision and balance isolation, as well as lifecycle-rule enforcement and ledger reversal semantics.

## Acceptance evidence required

- Database migrations are forward-only, compatible with the existing migration history, and tested on a clean database and an upgraded database.
- API integration tests prove permissions for allowed/denied roles, cross-organization isolation, manager reporting scope, self-approval rejection, lifecycle transition rules, attendance correction audit preservation, leave overlap/counting rules, and ledger posting/reversal invariants.
- Unit tests cover organization-local date and configurable working-day calculations, including six-day default and five-day override.
- India holiday baseline tests verify provenance and override precedence; no unverified dates are shipped as official.
- People app typecheck/build and API test/build results are recorded from actual runs. No test status is inferred from code inspection.
