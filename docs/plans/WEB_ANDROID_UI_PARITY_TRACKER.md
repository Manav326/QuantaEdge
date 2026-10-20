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

## 2026-10-20 — No-enrolment dashboard and learning states

- **Implementation recorded on `main`:** First-time student login remains on the Dashboard instead of being routed directly to Marketplace.
- The Dashboard now presents a concise, localized enrolment message and an explicit **Explore Marketplace** action; navigation occurs only when that action is selected.
- Learn, Practice, Progress and Books render localized empty states when the authoritative course request completes successfully with no enrolled/paused course. Course/subject filters are omitted in this state.
- API/loading failures remain errors/loading states; they are not treated as an empty enrolment catalogue. When a course is enrolled, the existing course-scoped content and filters remain in place.
- Web messages use the English/Hinglish catalogs; Android messages use the selected app language and Material theme semantic colors.
- Added source-contract assertions in `apps/web/scripts/check-student-navigation.mjs` for the cross-platform empty states. Main application changes include `f482a00e2749512340fa6e23be10889a4e33a160`; contract coverage is recorded in `41677758e1147873d8be034a1bb7bc42725b0746`.
- **Verification status:** Implementation is recorded on `main`; automated build/contract results and real-account end-to-end QA are pending. This is not marked as end-to-end passed.


## Student empty-state coverage — 2026-10-20

The agreed pattern is a shared responsive, accessible student empty-state component with a contextual line icon, heading, explanation and relevant next step. Scope includes Dashboard, My Courses, Marketplace, Structured Lessons and nested lesson detail, Practice, Tests & Results, Diagnostic, Live & Recorded Classes, Progress, Certificates, and Books/Chapters.

Marketplace has a distinct no-published-offers fallback with retry and a link to My Courses. Loading and genuine request failures remain separate; empty catalogue copy must not imply an API failure is an empty result. Direct certificate lookup links remain usable when the student has no current enrolment. Empty content *within an enrolled course* (no published tests, released results, scheduled class, recording, PDF or issued certificate) stays contextual to that content.

Implementation on main does not mean browser/device E2E is passed. Verify mobile layout, keyboard focus, action destinations, truly empty responses, API errors, and both interface languages before marking parity QA green.


### Route coverage added on 2026-10-20

The shared web fallback now covers Live/Recorded Classes, Diagnostic, Tests & Results, Certificates and nested Structured Lesson detail in addition to Dashboard, My Courses, Marketplace, Learn, Practice, Progress and Books. Route-level gates require the course scope to finish loading and only accept an empty/explicit no-enrolment result; unrelated course-scope failures retain their error UI. Certificate deep links bypass the no-enrolment guard. The navigation contract asserts all of these coverage points and the Marketplace's distinct no-offers retry state.

### Android route and empty-data safety coverage — 2026-10-20

Android now uses a shared Compose empty-state card for Dashboard, My Courses, Marketplace, lessons, practice/tests, results, leaderboard, progress, library resources, live/recorded classes and certificates. Contextual next actions send a student to Marketplace; Marketplace with zero offers also offers refresh and My Courses. Course-list errors retain Retry and are not converted into “no courses” results. Class, assessment/result and certificate routes retain their own empty states for a valid enrolled course with no scheduled sessions, no published tests/results, or no issued certificates.

Nested lesson/detail guards follow course scope; an in-progress assessment route is intentionally not replaced so saved work can be recovered. Direct certificate verification links remain accessible on web. Reader/deep-link access is checked against course enrolment before rendering course content. Web content lists for classes, tests/results, certificates and textbooks were also tightened so an API error cannot simultaneously claim that valid content is absent.

Android's no-enrolment Certificates state also retains a **Scan certificate** action; the empty-state guard yields to scanner mode and scanned verification results, including invalid-code feedback, instead of hiding them behind the course prompt.

The source contract now checks these web and Android states and the central English/Hinglish copy. In-course empty catalogues now also use the shared premium card with contextual copy: no published structured lessons, no scheduled live classes, no recordings, no published tests/results, no PDFs, and no issued certificates. The available action is contextual (refresh, review lessons, or open Tests & Results), not a generic enrolment prompt. The checks do not replace Android build/device tests or web browser E2E; keep QA status pending until the current main workflow is green and the main empty, error and enrolled-but-no-content cases are exercised.


## Android no-course loading guard — 2026-10-20

Reviewed Android student route fallbacks in Dashboard, My Courses, Marketplace, Structured Lessons and nested lessons, Practice, Diagnostic, Tests and Results, live/recorded classes, Progress, Certificates and Books/Chapters. The existing Android code uses the shared Compose `StudentEnrollmentEmptyState` component for the confirmed no-enrolment case. Marketplace already has a separate empty-offers fallback with Refresh and My Courses actions; load failures remain their own state. Certificate scanning/verification remains available without course enrolment.

Tightened initial-load handling: an empty in-memory course list no longer qualifies as a confirmed no-enrolment state. The predicate now requires a successful catalogue fetch, no catalogue-load failure and no enrolled/paused course. Dashboard and My Courses show a loading state until the catalogue resolves. Added a JVM unit test for loaded, failed and entitled cases. Source/unit-test evidence is not device E2E certification.
