# Shared Shells and Responsive Foundations — Source Review Log

**Status:** SOURCE REVIEW IN PROGRESS — no browser/emulator visual verdict is claimed.  
**Review base:** `main` commit `ba837e2560b05c15e26a215e2e24c9781caefc14` (latest baseline when this clean audit branch was cut).  
**Audit branch:** `audit/route-component-responsive-ux-clean-2026-10`.  
**Scope of this pass:** shared root layouts, workspace navigation shells, global/responsive styles, locale/theme providers, and floating tutor overlays. This is a static-source pass to establish what to verify at runtime; it is not yet a complete route or component audit.

## 1. Source evidence and observations

### Learner/Parent Web

Files:
- [Root layout](../../apps/web/app/layout.tsx)
- [Workspace shell](../../apps/web/app/components/WorkspaceLayout.tsx)
- [Global stylesheet](../../apps/web/app/globals.css)
- [Locale provider](../../apps/web/app/components/LanguageProvider.tsx)
- [Appearance provider](../../apps/web/app/components/ColorThemeProvider.tsx)
- [Tenant branding provider](../../apps/web/app/components/TenantBrandingProvider.tsx)
- [Tutor dock](../../apps/web/app/student/learn/TutorDock.tsx)

Source observations:
- The root layout composes the notification host, locale provider, session boundary, workspace shell, tenant branding and appearance providers.
- The workspace layout renders a desktop sidebar and sticky top bar; its CSS switches at approximately 900 CSS px to a fixed horizontal mobile navigation. The mobile navigation uses horizontal scrolling and CSS padding that references `env(safe-area-inset-bottom)`.
- Student mobile navigation has nested submenu sections and a close backdrop; parent and student navigation content is role-dependent.
- The Tutor dock code observes resize events, reads the workspace top bar and visible mobile-nav geometry to clamp the movable launcher, and resets/resizes its floating frame. The CSS gives the open dock a full-viewport mobile mode at approximately 700 CSS px, using `100dvh` and safe-area padding.

Measured source footprint: `globals.css` is approximately 295 KB and contains 89 `@media` blocks; breakpoints present include 850, 700, 680, 650, 640, 620, 600, 560, 540, 430, 420, 390 CSS px, as well as others. The same workspace selectors appear in more than one CSS cluster. This establishes a cascade-complexity risk that needs computed-style and screenshot review; it does **not** by itself prove a layout defect.

Runtime checks still required:
- Compact and regular phones at/around 390, 430, 700 and 900 px; landscape and keyboard-open states.
- Fixed navigation vs content bottom padding, safe-area inset, visible labels, horizontal scrolling, page focus/scroll restore and browser zoom.
- Parent vs student shell, public routes without authenticated shell, long Hindi labels, disabled/loading controls, account menus, theme selector and nested mobile submenu.
- Tutor launcher and open dock: drag/resize, minimized/restored, scroll, window resize/orientation, header/nav collision, focus/escape/close behavior and full-screen viewport on small devices.
- The Web Tutor dock is consumed by `/student/learn` and `/student/structured-lessons/[artifactId]`; shared behavior and route-specific context both need separate cases.

### Educational Admin

Files:
- [Root layout](../../apps/admin/app/layout.tsx)
- [Workspace shell](../../apps/admin/app/components/AdminWorkspaceLayout.tsx)
- [Sidebar](../../apps/admin/app/components/AdminSidebar.tsx)
- [Global stylesheet](../../apps/admin/app/globals.css)
- [Semantic theme stylesheet](../../apps/admin/app/admin-theme.css)
- [Theme provider](../../apps/admin/app/components/AdminThemeProvider.tsx)

Source observations:
- The shell has a desktop sidebar, mobile quick navigation, and a separate mobile “more” dialog for the additional destinations.
- Source handlers close the menu on Escape and route changes, trap Tab/Shift+Tab inside the dialog, lock body scrolling while open, and restore focus to the triggering control after close.
- Language and appearance controls are in the shared workspace header. Public/auth routes have separate behavior and must be checked without assuming authenticated shell styling.
- The base stylesheet is approximately 143 KB with 52 `@media` blocks; the semantic theme stylesheet is approximately 117 KB with 16 `@media` blocks. These styles contain overlapping breakpoint families (including 900/650 plus additional route-specific breakpoints) and theme overrides. Static rule presence cannot reveal the final cascade at all route/component combinations.

Runtime checks still required:
- More-menu open/close, focus restoration and focus trap with keyboard and touch; short-height landscape; dialog overflow and body scroll restoration after route change.
- Quick-nav label wrapping/selected state and width constraints; dense Content Studio/assessment forms, question editor/toolbars, tables and sticky action bars.
- Language selector option readability in light/dark modes, translated text expansion, focus ring, disabled/loading/error states and route-specific theme exceptions.
- Login/public routes in both themes at phone/tablet/desktop sizes; ensure existing authentication and return-path behavior remains unchanged.

### People Operations / HRMS

Files:
- [Root layout](../../apps/people/app/layout.tsx)
- [Workspace shell](../../apps/people/app/components/PeopleWorkspaceLayout.tsx)
- [Design-system stylesheet](../../apps/people/app/people-design-system.css)
- [Appearance stylesheet](../../apps/people/app/people-appearance.css)
- [Locale provider](../../apps/people/app/i18n/UiLocaleProvider.tsx)
- [Appearance provider](../../apps/people/app/components/PeopleAppearanceProvider.tsx)

Source observations:
- The workspace uses a sticky desktop sidebar and switches to a mobile drawer at approximately 900 CSS px.
- Source handlers lock body scrolling while the drawer is open, support Escape and Tab focus cycling, focus the first control on open and restore focus to the menu trigger on close.
- The design-system stylesheet is approximately 51 KB with 15 `@media` blocks, including 1100/900/600 px families. The appearance stylesheet is approximately 98 KB with 11 `@media` blocks, including 1024/760/420 px rules and print/theme overrides.
- The appearance stylesheet includes broad page-slot selectors that infer grid/header/table-like structures from class-name patterns. This can be useful for legacy pages, but must be validated against each page's CSS modules and special output surfaces. Invoice and authored-notification rendering must retain their own intentionally controlled styling.

Runtime checks still required:
- Drawer open/close, focus/keyboard/touch, body scroll restoration, and drawer content scrolling on short-height landscape.
- Payroll/pay-slip/invoice and notification-template surfaces under dark mode and print; ensure normal HRMS theme rules do not damage deliberately authored/print content.
- Long employee/course/department names, translated labels, table column behavior, filters, dialogs, forms and action wrapping at 320–430 px, tablet and desktop.
- Attendance, leave, payroll and assignment pages must be exercised using realistic, synthetic records and permission-appropriate roles. A responsive screenshot alone must not be considered a pass for permission-scoped UI behavior.

### Android

Files:
- [Route shell](../../apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt)
- [Navigation contract](../../apps/android/app/src/main/java/com/quantaedge/android/ui/StudentNavigationContract.kt)
- [Tutor dock](../../apps/android/app/src/main/java/com/quantaedge/android/ui/FloatingTutorDock.kt)
- [Existing screen composition map](../qa/phase-1-android-composition-map.md)

Source observations:
- The app routes through a central Compose switch with conditional renderers for lesson/subject catalogue variants, and has a role/orientation-aware navigation policy plus custom BackHandler route-return rules.
- The tutor dock uses `BoxWithConstraints` and available width/height to limit its movable/resizeable window; code comments specifically call out landscape, split-screen and IME reductions. Source inspection did not include emulator or device execution.
- The shell file is large (approximately 539 KB), so source presence of an AppRoute case cannot prove a complete destination, overlay and return-flow review. Use the composition map as a starting index, then inspect the renderer branch and child components.

Runtime checks still required:
- Every one of the 34 declared destinations, including both conditional renderers for subject lessons and lesson content.
- Compact/regular phone, tablet/split-screen, portrait/landscape, font/display scaling, system bars and navigation insets, keyboard/IME, Back/gesture, loading/error/empty states and touch targets.
- Lesson/tutor open/minimized/restore/close, drag/resize bounds and context retention after navigation; make sure the overlay neither hides primary controls nor becomes unreachable behind system UI.
- Compare equivalent API-backed Web and Android journeys without relaxing the same institution, role, course entitlement or saved-attempt rules.

## 1.1 Exact-head source inventory evidence

[CI run #7083](https://github.com/Manav326/QuantaEdge/actions/runs/40643885831) for commit `e31a26d76b05293a40adb4c72f583e223b4a2c3e` completed the route/source parity and component-source graph steps successfully. It found 75 generated page routes, 245 unique resolved source/asset paths, 166 unique UI-module candidates, 52 stylesheet modules, and zero unresolved local import references. The generated `ui-component-import-inventory` artifact is the source-trace starting point for the next phase.

The result confirms local import resolution, not actual rendered-tree completeness: components hidden behind runtime data, computed imports, permission conditions, feature flags or lazy state transitions still need explicit inspection. No browser screenshot/device result is implied. Web/Admin/HRMS/API image builds were skipped in this audit-only run.

## 2. Cross-app risk list (not confirmed defects)

| ID | Observation | Required verification before action |
|---|---|---|
| SHELL-01 | Shared Web CSS has many media blocks and repeated workspace selectors. | Inspect computed styles and screenshots at each relevant breakpoint before consolidating/overriding any rule. |
| SHELL-02 | Admin base and theme stylesheets both define responsive layout and semantic theme behavior. | Check theme + viewport + route combinations, especially editor/table/overlay surfaces; don't “fix” a single sample with a broad selector. |
| SHELL-03 | HRMS appearance rules use broad page-slot class-name matches and have print/document exclusions. | Exercise each HRMS route and print/document mode; narrow any future correction to verified affected selectors only. |
| SHELL-04 | Mobile shell dialogs implement focus/scroll behavior in source. | Use keyboard and touch to verify open/close focus, Escape, scroll lock restoration and short-height devices. |
| SHELL-05 | Floating tutor overlays derive bounds dynamically. | Test mobile/desktop, window/rotation/IME changes, minimized/restored and long sessions; do not infer collision-free behavior from formulas alone. |
| SHELL-06 | Android has 34 destination declarations and conditional renderer branches. | Verify composition map to runtime routes and test each distinct renderer path; no global pass shortcut. |

## 3. No changes made from this source pass

This source review records existing behavior and tests to run. No CSS, React/Compose runtime logic, API behavior, authorization, tenant policy or data model was changed based on these observations. Any remediation must first be tied to a reproducible failure, then implemented in a small batch with a regression test and retest of all affected routes/components.

**Status rule:** the source rows marked `IN_PROGRESS` indicate inspection/trace has started, not that responsiveness, theme, accessibility or runtime behavior passed. Browser/emulator evidence and an exact tested commit are still required.


## Reconciliation note — clean audit branch

The former draft branch accumulated unrelated textbook-pipeline data deletions and is preserved separately; this audit-only branch is cut from current `main`. Source review entries remain source-level observations only. The current CSS/layout source files for the inspected shells were compared against `main` and have matching blob SHAs for the inspected files; runtime visual verification remains pending.
