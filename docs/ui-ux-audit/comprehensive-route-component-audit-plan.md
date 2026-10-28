# Comprehensive QuantaEdge Route- and Component-Level UI/UX Audit

**Status:** IN PROGRESS — baseline reconciliation and audit controls are being established. No overall route/component sign-off exists yet.  
**Scope:** Learner/Parent Web, Educational Admin, People Operations (HRMS), and native Android.  
**Branch:** `audit/route-component-responsive-ux-2026-10` (created from `main` for this focused effort).  
**Primary rule:** improve presentation, responsiveness and accessibility without breaking or silently changing existing product behavior.

## 1. Goal and non-negotiable rules

Review every active route/destination, every rendered component within it, and every relevant state. Record evidence, findings, fixes and regressions against an exact commit. A component is not considered reviewed just because its source file exists, a shared stylesheet changed, a build passed, or another route using it looks correct.

- Preserve authentication, permissions, institution/tenant boundaries, APIs, SQL/persistence, assessment scoring, course lifecycle, payment behavior, content workflows, navigation contracts and saved user data.
- Start with read-only inspection and reproducible baseline checks. Do not make broad styling edits before understanding the rendered component tree and affected routes.
- Use the smallest targeted fix for each confirmed defect. Add a regression assertion/test where practical and revisit every affected consumer.
- Do not seed fake production data, charge real payments, publish content, or contact real learners as part of UI validation. Use safe test fixtures, sandbox providers or test doubles.
- Keep findings separate from implementation status and verification status. `NOT_REVIEWED`, `BLOCKED`, and `NOT_APPLICABLE` are never passes.
- Every pass needs the tested commit SHA, environment/device or viewport, reproducible steps, expected and actual result, and evidence reference. No blanket route-level pass if an important child component/state remains unreviewed.
- Keep the audit on the focused branch. Do not merge changes into `main` without the final review and successful applicable checks.

## 2. Baseline finding to resolve first

At the current `main` snapshot, both `docs/qa/ui-screen-inventory.csv` and `docs/qa/phase-1-ui-state-coverage.csv` contain **96 data rows**. The existing platform labels split the People Operations/HRMS entries between `people` and `people-hrms`; normalized inventory counts are currently 29 Web, 19 Admin, 14 HRMS and 34 Android destinations. Older audit documentation reports 93/94 entries and 12 HRMS destinations.

These are source-register counts, not executed screen counts. First regenerate the route inventory on this exact branch and reconcile its result with both CSVs, actual source files, Android navigation, wrappers/redirects and conditional destinations. Resolve the discrepancy explicitly; do not hide rows or make the counts match by deleting destinations.

## 3. Phases and deliverables

### Phase 0 — Baseline and inventory reconciliation
1. Record the exact `main`/branch SHA and baseline CI state.
2. Run the existing route-inventory generator and its tests; reconcile the generated Web/Admin/HRMS routes with the maintained QA register.
3. Reconcile all Android navigation destinations with shell/conditional renderers and screen files.
4. For each route, follow layout → page → imported components → nested/conditional components. Record shared component reuse and route-specific variants.
5. Review existing package scripts and available browser/emulator test support before adding dependencies.
6. Capture known pre-existing failures separately from defects introduced by this branch.

**Exit:** every known route/destination has one audit row, every inventory discrepancy has a disposition, and the current verification baseline is reproducible.

The CI `changes` job now tests `scripts/inventory_ui_component_imports.mjs` and uploads its per-route local-import graph as the `ui-component-import-inventory` artifact. Review unresolved local imports as possible alias/resolution gaps or real missing modules; do not silently discard them or treat this static graph as proof of conditional rendering.

### Phase 1 — Shared shells and component inventory
Trace the application shells, responsive navigation, headers, sidebars/drawers, language/theme controls, shared buttons/fields/selects, tabs, tables, alerts, dialogs, menus, cards, status badges, loading/empty/error views, toasts, floating tutor controls, textbook/lesson/media/assessment primitives and chart/report renderers.

Each discovered reusable or route-specific interactive component must be recorded independently and mapped to all consuming routes. Record its variants, relevant visual states, event handlers, accessibility semantics and regressions to test. A route cannot be marked component-inventory complete until the rendered component tree is traced and its leaf/interactive components are accounted for.

**Exit:** there are no unexplained/unmapped component branches on an active route; every shared component has an affected-route list.

### Phase 2 — Responsive and visual verification
Use representative checkpoints at **320, 360, 375, 390, 430, 768, 1024, 1280 and 1440 CSS pixels**, adding widths around actual CSS breakpoints. Include portrait/landscape, vertical and horizontal overflow, browser zoom/text scaling and virtual keyboard behavior where relevant. For native Android, include compact and regular phones, tablet/split-screen, orientation, system-bar/safe-area insets, IME, Back and gesture navigation.

Verify light/dark appearance where supported, English/Hindi text, long labels, mixed-script content and user-authored/dynamic text. Inspect actual foreground/background pairs, borders, focus rings, selected/disabled options and branded assets—not just token declarations.

**Exit:** each applicable viewport/theme/language/state has evidence or a named blocker, with no unexamined overflow/clipping/overlap.

### Phase 3 — Route-by-route runtime walkthrough
For every active route, exercise primary and secondary actions and trace the resulting navigation, API request, tenant/workspace context, permission, response and visible state. Cover real data, legitimately empty data, loading, server/network errors and retry, invalid input, disabled actions, success confirmation, unauthorized/forbidden states and destructive-action confirmation as applicable.

High-risk user journeys include authentication; course/marketplace and parent-child access; lesson/topic reading and practice; assessment answer/submit/result/resume; certificate verification; Content Studio editing/review/publish controls; resource/class/media flows; workforce/attendance/leave/payroll/assignment flows and downloadable reports.

**Exit:** each route has a disposition and evidence for all applicable states; known backend/provider/environment blockers are explicit and are not mislabelled as responsive defects or passes.

### Phase 4 — Accessibility and interaction
Check keyboard-only order, visible focus, dialog focus/escape behavior, labels and names for icon-only controls, focus/selected/disabled semantics, screen-reader status/error announcements, touch target and spacing, contrast, reduced motion, zoom/reflow, native IME, safe-area insets and Back behavior. Validate controls at normal, hover/pressed, focus-visible, selected, disabled, loading, success and error states where applicable.

**Exit:** critical interaction/accessibility defects are fixed or explicitly blocked with an owner and next action.

### Phase 5 — Small-batch fixes and regression
Prioritize P0 functional/accessibility/security-boundary regressions, then P1 task-blocking responsive/contrast defects, then P2 consistency and polish. Before editing a shared component, identify its consumers. Make one coherent batch at a time; add or update focused regression tests; compare before/after evidence; re-run affected app checks and existing workflow/API contracts.

Do not change business rules to achieve a visual result. Any issue that reveals a behavioral defect must be isolated, traced to root cause and handled as a deliberate separate change, not bundled blindly into CSS work.

### Phase 6 — Final regression and sign-off
Re-run route inventory validation, QA-register integrity checks, relevant unit/contract tests, app builds and the available UI automation. Repeat the manual viewport/device walkthrough for all modified components and their route consumers. Check both the exact tested commit and all applicable CI jobs.

**Exit:** zero unresolved P0/P1 issues without explicit acceptance, every row is `SIGNED_OFF`, `BLOCKED` or justified `NOT_APPLICABLE`, and evidence points to the exact final tested SHA. Overall audit cannot be called complete while any required row is `NOT_REVIEWED` or `IN_PROGRESS`.

## 4. Per-route and per-component review checklist

For each route:
- Page purpose, access persona, layout/shell and all nested imports/component branches are mapped.
- Navigation, URL/query/return behavior, loading lifecycle and error/retry paths are understood.
- Data-backed content, empty content, long/dynamic content, forms, tables and dialogs reflow correctly.
- No unexpected horizontal scroll, clipped text, overlapping controls, hidden primary action or layout jump.
- Light/dark, English/Hindi, relevant viewport/orientation, zoom/text scaling and keyboard/touch behavior are checked.
- API, workspace/tenant and permission behavior remains unchanged; actual side effects are confirmed only in safe test conditions.
- Evidence and the tested SHA are recorded.

For each component:
- Component identity/source and consuming routes are recorded; shared variants are enumerated.
- Props, state, event handlers, loading/error/empty/disabled/selected states and conditional rendering are traced.
- Responsive constraints, overflow, intrinsic sizes, sticky/fixed positioning, z-index, scrolling and viewport/inset dependencies are reviewed.
- Text/icon/background/border/focus contrast and long labels/translations are checked in supported themes.
- Keyboard/touch/accessibility behavior and regression tests are recorded.
- After a fix, every affected route and relevant state is revisited.

## 5. Severity and evidence rules

- **P0:** critical workflow is blocked, major data/permission boundary risk, or essential controls cannot be used.
- **P1:** materially broken mobile/tablet layout, invisible/unreadable essential text, clipped/overlapping controls, unusable forms/dialogs or serious accessibility barrier.
- **P2:** spacing, density, alignment, consistency, typography or minor interaction polish.
- **BLOCKED:** include the blocker, owner/next action and what evidence is still required.
- **NOT_APPLICABLE:** include a concrete reason tied to the component/platform/state.

CI success is necessary but not sufficient for visual sign-off. Source inspection proves what code exists; a screenshot proves only the captured state; a runtime pass proves only the tested route/state/viewport/revision. Never extrapolate from one sample to all routes or components.

## 6. Current status and work queue

1. **IN PROGRESS:** reconcile current-main route/screen counts; current CSV baseline is 96 rows, while older documentation is stale.
2. **NEXT:** run/validate the route generator and tests on this branch; record exact source inventory.
3. **NEXT:** finish the route-to-component trace and create independent component-instance entries with consuming routes.
4. **NEXT:** verify an appropriate repeatable browser and Android UI test path before choosing any new dependency.
5. **PENDING:** capture the existing responsive/theme baseline before applying visual fixes.
6. **PENDING:** run route walkthroughs, fix defects in small batches, and maintain regression evidence.

The route-level working register is `route-component-audit-register.csv` beside this plan. The separate `component-instance-audit-register.csv` tracks shared components and their route consumers; it is a starting inventory, not the final child-component census. The `shared-shell-source-review.md` records the initial source inspection and labels runtime checks as pending. Continue adding route-specific and conditional leaf components as the route-by-route trace discovers them. The validator enforces that each route references exactly the components that list it as a consumer. The existing QA screen/state/scenario/API/runtime registers remain the source of linked workflow and permission evidence; these records complement one another and must stay consistent.
