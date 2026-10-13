# QuantaEdge HRMS and Educational Admin UI Alignment Plan

**Repository:** `Manav326/QuantaEdge`  
**Target branch:** `main`  
**Owner:** QuantaEdge product engineering  
**Purpose:** Establish one recognizable QuantaEdge design language across Educational Admin and People Operations while preserving separate product identities, independent builds, authorization rules, and all existing workflows.

## Governing principles

1. One organization and brand; distinct product identities and workflow information architectures.
2. Establish design tokens and the shared HRMS shell before screen-by-screen restyling.
3. Do not import the entire Educational Admin global stylesheet into People Operations.
4. Keep People Operations independently buildable, packaged, and testable.
5. Navigation visibility is a usability aid only; server-side authorization remains authoritative.
6. No route, business action, workflow transition, information field, print capability, or meaningful state may be removed merely for visual simplification.
7. Implementation and verification are separate statuses. Styling is not considered verified until checked.
8. Do not report a build, workflow, browser check, or regression as green unless evidence exists.

## Baseline findings from the source audit

- The Educational Admin app has a branded workspace shell, grouped/collapsible sidebar navigation, and official QuantaEdge wordmark/icon assets.
- People Operations previously had a standalone blue theme (`#244C9A`) and a navy split-screen login; Educational Admin uses the QuantaEdge orange brand accent (`#F97316`, deep accent `#C2410C`) and dark charcoal navigation.
- People Operations repeated page navigation in each route; labels and links were not uniform, including duplicate self-service entries in the employee directory header.
- People Operations used shared CSS conventions for cards, panels, tabs, forms, tables, dialogs, feedback and pills but lacked a dedicated shared UI component directory in the current tree.
- The seven HRMS content routes had approximately 98 inline style declarations in the source snapshot. These require review; each is not automatically a defect, but one-off styling should not proliferate.
- Both applications independently build using Next.js 16.4.0 and React 19.3.0. Shared visual tokens/assets must work in both separate build contexts.
- The prior source audit did not include rendered-browser screenshots or completed end-to-end regression testing; these remain required.

## Phase 1 — Baseline and rendered UI inventory

- [ ] Confirm main revision, running/deployed build, and available sign-in/test identities for both apps.
- [ ] Exercise all existing People Operations routes and record authentication/redirect behavior.
- [ ] Capture before screenshots for desktop, tablet, and mobile widths.
- [ ] Audit the Educational Admin sidebar, workspace header, dashboard, content authoring, tables/forms, login, mobile drawer, and state variations.
- [ ] Record each HRMS route, nested tab/section, form, dialog, action, API-backed state, permissions, error/empty/loading state, responsive behavior, and printing behavior.
- [ ] Record existing console errors, failed assets/API requests, and known failures separately from redesign defects.

**Gate:** the UI and feature inventory exists; no UI mutation precedes baseline capture.

## Phase 2 — Design-system specification

- [x] Agree token names and values for brand, text, canvas/surface, border, focus, positive/warning/danger/info, typography, spacing, radii, elevation, and motion.
- [x] Reuse the official QuantaEdge brand assets, where their build packaging is confirmed.
- [x] Specify heading hierarchy, page headers, table density, fields, button hierarchy, status mapping, dialog treatment and state patterns.
- [x] Establish HRMS-scoped CSS/component conventions; do not import the full Admin stylesheet.
- [ ] Keep currency, financial data, workflow status and dangerous actions semantically clear.
- [x] Define supported viewport and accessibility review matrix.

**Gate:** token and component conventions are documented and usable by both developers and reviewers.

## Phase 3 — People Operations app shell and navigation

- [x] Add a dedicated People Operations workspace layout.
- [x] Centralize HRMS navigation, active-route behavior and labels.
- [x] Group navigation by People/Organization, Access Governance, Time/Leave, Payroll, Academic Workforce, and My Employment.
- [x] Display links based on session role/permissions without replacing API enforcement.
- [x] Remove per-page duplicate navigation headers while preserving each page's unique body and workflow.
- [x] Provide desktop collapsible sidebar and accessible mobile navigation drawer.
- [x] Standardize identity, role display and sign-out action.
- [x] Keep login/public route outside the authenticated workspace chrome.
- [x] Verify each existing route is represented in the navigation/inventory.

**Gate:** all routes load within the common shell; login remains independent and API authorization is unchanged.

## Phase 4 — Reusable HRMS component foundation

- [x] Page header / contextual action area.
- [x] Primary, secondary, neutral, destructive, loading and disabled buttons.
- [x] Metric cards, content cards, section panels, and layout primitives.
- [ ] Fields, labels, hints, inline validation and responsive form grids.
- [x] Tabs and segmented navigation.
- [ ] Tables, row action groups, overflow behavior and pagination where needed.
- [x] Semantic status badges for employment, attendance, leave, payroll/payment and assignment states.
- [x] Success/error/warning/information feedback patterns.
- [x] Empty, loading, unavailable and retry states.
- [x] Dialogs/confirmations with focus, scroll and keyboard behavior.
- [ ] Remove redundant inline layout styling where a shared variant or layout convention fits.
- [ ] Avoid forcing the same content density/layout onto every module.

**Gate:** components have defined variants and are used consistently by migrated routes.

## Phase 5 — Employee directory and organization structure

- [ ] Employee directory, search and include-inactive behavior.
- [ ] Employee summary cards and data table.
- [ ] Create/edit forms, validation, save states, and errors.
- [ ] Departments and designations, including parent/category/status controls.
- [ ] Manager, joining date, probation/exit details and status presentation.
- [ ] Employee history/audit trail.
- [ ] Permission-controlled operations and no-results states.

**Gate:** every existing employee/organization interaction passes functional regression.

## Phase 6 — Attendance, leave and academic workforce

### Attendance and leave
- [ ] Attendance listing, work-date controls, totals, marking and updates.
- [ ] Attendance correction requests, review history and reason capture.
- [ ] Leave types, requests, approval/rejection and results.
- [ ] Leave balances, transaction ledger and reversal/reason flows.
- [ ] Working-week configuration for five/six days.
- [ ] Holiday calendar, imports, provenance, and year/jurisdiction context.
- [ ] Approval policy rules and permissions.
- [ ] Preserve transactional/audit semantics and existing API contracts.

### Academic workforce
- [ ] Teaching assignments and lifecycle status.
- [ ] Academic periods and effective dates.
- [ ] Assignment replacement, cancellation, review history and reasons.
- [ ] Workload and academic reporting controls.
- [ ] My classes, upcoming/past records, meeting-provider states and private links.
- [ ] Preserve the meeting-link eligibility window and its privacy behavior.

**Gate:** all listed workflows and their permission paths pass regression.

## Phase 7 — Payroll and extended HR

### Payroll
- [ ] Payroll period lifecycle.
- [ ] Compensation and effective-dated salary components.
- [ ] Calculation/run summaries and approval actions.
- [ ] Payment status/reference display and failure handling.
- [ ] Issued payslips, snapshot details, currency/period/identifier legibility.
- [ ] Print-payslip styling and print regression.
- [ ] Preserve payroll calculation, approval, payment and idempotency semantics.

### Extended HR
- [ ] Recruitment/vacancies/candidates and hiring decisions.
- [ ] Candidate conversion to canonical employee record.
- [ ] Performance workflows.
- [ ] Asset custody.
- [ ] Exits and clearance.
- [ ] Distinct workflow states, permissions, reasons and empty/error states.

**Gate:** no financial or workforce lifecycle rule is altered by UI work; all available automated and manual checks pass.

## Phase 8 — Employee self-service, access governance and authentication

- [ ] HRMS access governance: employee login provisioning, role/permission assignment, access enable/disable, credential reset and reserved-account safeguards.
- [ ] Profile details and profile-change requests/history.
- [ ] Attendance correction request/history.
- [ ] Leave requests and outcomes.
- [ ] Payslip list, detail and print.
- [ ] Salary component disclosure as already allowed by product policy.
- [ ] Branded login and OTP request/verification states.
- [ ] Invalid/expired code, rate limit, server error, loading and retry states.
- [ ] Preserve first access, staff identity, session cookies, redirects and access rules.
- [x] Ensure People branding assets exist within the People app's Docker build context.

**Gate:** auth and self-service smoke tests pass for available test identities.

## Phase 9 — Responsive behavior, accessibility and interaction polish

- [ ] Review 1440px desktop, 1280px laptop, 768px tablet, and 390px mobile (plus additional widths when defects require).
- [ ] Check sidebar collapse and mobile drawer, focus restoration, Escape handling, and scroll locking.
- [ ] Check page headers, cards, toolbars, tabs, forms, tables and dialogs at narrow widths.
- [ ] Check long names/labels/errors, large tables, empty states and failed external integrations.
- [ ] Keyboard-only navigation and visible focus.
- [ ] Accessible names, label associations and status announcements.
- [ ] Color contrast and information not conveyed by color alone.
- [ ] Touch target sizing and readable text.
- [ ] Reduced-motion and forced-colors behavior.
- [ ] Print layout.

**Gate:** all supported workflows remain usable on target viewports and keyboard navigation.

## Phase 10 — Visual and functional regression / release

- [x] Capture final screenshots that match baseline routes/viewports/states.
- [x] Compare shared brand, type scale, spacing, page hierarchy, tables/forms, navigation and feedback patterns with Educational Admin.
- [ ] Check that HRMS has its own product identity and does not look like a direct copy or a patched assembly.
- [x] Build both `apps/admin` and `apps/people`.
- [ ] Run available lint/type checks and targeted automated tests.
- [ ] Exercise route access, CRUD, validation, permission-gated actions and feedback.
- [ ] Check browser console and failed requests on the exercised routes.
- [x] Verify logo/icon paths and independent Docker build contexts.
- [ ] Verify payslip print behavior.
- [ ] Record test evidence, known limitations and issues still blocked.
- [ ] Mark each inventory line as implemented and verified only after evidence exists.

**Release gate:** both application builds and all applicable checks are green. Any untested third-party integration or unavailable test identity is explicitly reported as unverified, not assumed successful.

## Route inventory

- [x] `/` — Employee directory and organization structure.
- [x] `/attendance` — Attendance, leave, corrections, approvals, holidays, balances.
- [x] `/payroll` — Payroll periods, compensation, components, runs/payments/payslips as exposed by the current route.
- [x] `/workforce` — Recruitment, performance, assets and exits.
- [x] `/assignments` — Teaching assignments, academic periods, reports/workload.
- [x] `/my-classes` — Upcoming/live/past teaching classes and private meeting links.
- [x] `/access` — HRMS login access, role/permission assignments and credential reset.
- [x] `/self-service` — Profile, employee requests, leave, corrections and issued payslips.
- [x] `/login` — Staff login and OTP verification.
- [x] `/change-password` — forced password-change flow; keep it outside the authenticated workspace chrome.

## Implementation status and evidence log

Maintain status per task using: **Not started / In progress / Implemented / Verified / Blocked**. Record the commit or file/route evidence for each status. A commit or a successful CSS compilation alone does not equal visual verification.

- [x] Source audit and alignment roadmap recorded.
- [ ] Rendered before-state capture.
- [x] Design tokens and shared shell — implemented on `main`; CI build evidence is recorded below.
- [x] Shared HRMS component primitives — page headers, metric cards, status badges, feedback, buttons, and empty-state component exist; adoption across every route remains in progress.
- [x] Common shell/page-header migration — all seven HRMS content routes use the shell and page-header component; detailed per-module polish and functional review remain in progress.
- [ ] Responsive/accessibility review — CSS support is implemented; rendered-browser checks remain pending.
- [ ] Green builds and regression evidence — latest completed run is recorded below; final token polish commit still needs its own CI run.

This document is the persistent checklist for continuing the work on `main`. Update it as tasks are completed; do not claim completion for unchecked verification items.


## Execution log — October 13, 2026

Implementation is proceeding directly on `main`. Each check distinguishes source implementation, CI verification, and rendered-browser QA.

- [x] Commit `ec8ca2b`: added the People Operations design stylesheet and workspace shell; packaged the official QuantaEdge wordmark and icon inside the People app.
- [x] Commit `1c2f66e`: retained the existing HRMS-specific session boundary, forced-password-change handling, refresh recovery, and HRMS workspace scope.
- [x] Commit `aba8567`: removed duplicate route headers, avoided nested `<main>` elements, and added the skip link.
- [x] Commit `b00477c`: introduced shared People UI primitives and migrated all seven original content-route page headers.
- [x] Commits `e399ee4`, `d086428`, `ba27cd8`, and `0878d62`: migrated metric cards, semantic status badges, feedback patterns, and reusable empty states across the original HRMS routes.
- [x] Commit `f2715ce`: formalized token usage for spacing, radii, controls, surfaces and focus styling.
- [x] Commit `2c57445`: introduced the shared accessible dialog for employee directory and teaching-assignment history/edit flows.
- [x] Commit `c0bc8cc`: added the static HRMS UI contract checker to the People Operations CI job. Full Actions run [38370400699](https://github.com/Manav326/QuantaEdge/actions/runs/38370400699) completed successfully: People Operations, Admin, Web and API images, API verification, content-import safeguards and Android build all passed.
- [x] Current follow-up adds `/access` to the common shell and page-header/component system; corrects navigation visibility to reflect `HR_ACCESS_MANAGE` and `TEACHING_ASSIGNMENT_VIEW`; and extends the UI contract checker to include access governance. Its CI run is pending.
- [x] Official branding assets are packaged under `apps/people/public/branding`; the People app retains `PeopleSessionBoundary` and the HRMS workspace header.
- [ ] Rendered desktop/tablet/mobile screenshots, live browser interactions, role/session workflows, and payslip-print review remain pending. These must not be inferred from image builds alone.

Run `38370400699` validates commit `c0bc8cc`. The route-inventory/permission follow-up is not considered green until its own full workflow completes.
