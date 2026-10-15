# QuantaEdge Web ↔ Android UI Parity Tracker

## Why we use this Excel workbook

The Excel workbook is the durable decision and progress ledger for QuantaEdge's user-facing web ↔ Android UI parity effort. It keeps one row per page/behaviour, captures the agreed target behaviour, records whether implementation is on `main`, and provides a separate end-to-end QA checklist. This avoids repeating the same audits in later sessions, losing the reasoning behind decisions, or confusing “code recorded on main” with “verified working in a real user journey.”

## Scope

The matrix covers the public landing page; login, signup, password reset and session/logout; student learning, AI tutor, practice, diagnostics, formal tests, results and certificates; textbook/document access; parent/child management and reports; and certificate verification/scanning. Admin and HRMS are explicitly outside this user-facing web-vs-Android matrix.

## Status rules

- **Green / implemented on `main`:** implementation is recorded in the main branch. It does not certify complete end-to-end behaviour.
- **QA passed:** only mark a case passed after actually exercising the specified real-account/browser/device behaviour and recording evidence.
- **CI green:** build/test evidence is useful but is not a substitute for checking account entitlements, saved files, scanner permissions, responsive/orientation behaviour, accessibility, or cross-platform data parity.

## Baseline recorded on 2026-10-15

- **62/62** page/behaviour decisions are recorded as implemented on `main` in the workbook.
- **0/30** E2E cases are marked passed; all remain pending actual verification evidence.

These counts describe the recorded tracker state, not a claim that full parity has been independently verified.

## How to resume in a later session

1. Open [the workbook](QuantaEdge_UI_Parity_Progress_2026-10-15.xlsx) and this note before changing UI parity code.
2. Start at the first incomplete row in **QA Checklist** for verification work. For further implementation, first identify a concrete uncovered discrepancy instead of redoing completed decisions.
3. Implement on `main` under the existing project constraint to prefer **additive changes only** and avoid unrelated refactors.
4. Record implementation status, commit SHA and CI run after the change reaches `main`. Mark a QA case passed only when its real verification succeeds.
5. Save and commit the updated workbook and keep this note/implementation plan aligned.

## Canonical files

- Workbook: `docs/plans/QuantaEdge_UI_Parity_Progress_2026-10-15.xlsx`
- Implementation and detailed QA plan: `docs/plans/web-android-ui-parity-implementation.md`
- Branch: `main`

