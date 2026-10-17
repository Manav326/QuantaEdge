# QuantaEdge Web UI/UX Design System and Rollout Plan

**Status:** implementation approved by the user on 2026-10-17; proceed through the documented route-by-route rollout on the main branch.  
**Scope:** All QuantaEdge web interfaces: learner/public web, Admin/Content Studio, and People Operations (HRMS).  
**Branch policy:** Work on `main` only unless the user explicitly changes this instruction.  
**Primary rule:** Improve presentation and interaction quality without changing business logic, authorization, API contracts, persistence semantics, curriculum behaviour, or existing workflows.

## 1. Product direction

Use a **premium hybrid** visual language:
- Dark, brand-led hero areas and selected navigation surfaces.
- Light, low-distraction working surfaces for lessons, practice, parent tools, tables, forms, and operational workflows.
- Consistent typography, spacing, borders, radii, focus styles, component states, and motion across all three applications.
- A single QuantaEdge identity. Do not independently restyle or translate the logo, wordmark, tagline, or canonical brand colours per application or language.

This is a restrained, polished education product—not a neon/glassmorphism theme. Effects should improve hierarchy and perceived quality, not compete with content. **Keep the existing orange-led QuantaEdge palette unchanged.** Do not introduce a blue-led palette or alter canonical logo/wordmark/tagline colours. Layout, spacing, typography, navigation, card/form structure and interaction polish are approved for implementation under this plan, preserving current brand colours and product workflows.

## 2. Canonical brand tokens

The existing approved brand system in `docs/branding.md` remains authoritative. This design plan does not change the orange-led identity.

| Token | Value | Use |
|---|---|---|
| Canonical brand navy | `#152038` | Existing wordmark and identity elements |
| Orange | `#F97316` | Existing orange-led brand accent; preserve current usage and hierarchy |
| Deep orange | `#C2410C` | Existing approved brand context; preserve current usage |
| Soft orange | `#FFF0E5` | Existing approved brand context; preserve current usage |
| White | `#FFFFFF` | Cards and reading surfaces |
| Light canvas | `#F5F7FC` | Main light work area |
| Neutral border | `#E5EAF3` | Dividers, outlines, card boundaries |
| Primary text | `#172033` | Body and heading text on light surfaces |
| Muted text | `#667085` | Secondary metadata, after contrast verification |
| Success / warning / error | Existing semantic tokens first | Status and validation meaning, never decorative branding |

No new colour palette is approved. Existing CSS/design tokens and the orange-led identity are the source of truth; do not add blue palette tokens or shift primary actions from orange to blue. Reconcile any proposed neutral/semantic token adjustments against the actual code and contrast-test them. Keep logo, wordmark and tagline assets unchanged. Layout/placement changes may now be implemented within this approved plan; record unverified visual results separately from source implementation and CI.

## 3. Visual and interaction foundations

### Surfaces and depth
- Public landing pages may retain their existing brand colours. Any proposed hero, navigation, CTA, card or section placement changes must be shown for user approval first; do not introduce a blue glow or blue primary action as part of this redesign.
- The hero is followed by light sections; working screens should not become dark solely for visual novelty.
- Prefer surface contrast, thin borders, and low-elevation shadows over heavy drop shadows.
- Glass effects, blur, gradients, and decorative illustrations are optional and rare; avoid them behind dense text and data tables.
- Use one consistent radii scale (proposed: 8 / 12 / 16 / 20 px) and elevation scale, after auditing existing components.

### Typography and reading
- Establish a documented type scale and line-height scale instead of local arbitrary values.
- Use a font stack with reliable Latin and Devanagari support; check actual bundled fonts and licensing before adding assets.
- Preserve legibility for Hindi and English, mathematical notation, fractions, equations, tables, and question types.
- Lesson, textbook, assessment, and AI Tutor reading surfaces prioritise line length, contrast, and spacing over decorative effects.

### Layout and spacing
- Adopt a consistent spacing scale (proposed base: 4 px; common steps 4, 8, 12, 16, 24, 32, 48).
- Standardise content max-widths, page gutters, card padding, form rhythm, and table density by use case.
- Admin and HRMS may be information-dense but must preserve hierarchy and comfortable touch targets.
- Avoid layout shifts when data loads; reserve space for asynchronous content.

### Component states
All shared interactive components should define normal, hover, pressed, focus-visible, selected, disabled, loading, success, and error states as applicable. Focus indicators must be visible and must not rely on colour alone. Destructive actions require clear wording and appropriate confirmation.

### Motion and perceived performance
- Use short transitions for state changes (starting guideline: 150–220 ms); avoid gratuitous movement.
- Respect `prefers-reduced-motion`.
- Use skeletons only where the loading layout is known; otherwise use a stable, accessible progress indicator.
- Give immediate honest feedback for actions. Optimistic UI is allowed only when rollback/error handling is correct; never show a save/publish success before the server confirms it.
- Keep animation and blur inexpensive on low-powered phones.

## 4. Surface-specific application

### Public landing and authentication
- Brand-led dark hero with approved QuantaEdge mark/wordmark/tagline.
- Clear page hierarchy, one primary action, and readable supporting actions.
- Light content sections, accessible forms, useful validation, and responsive navigation.
- Do not change authentication, OTP, account, or routing behaviour as part of visual work.

### Student learning, lessons, practice, progress
- Light reading-first surfaces; minimal distraction.
- Clear lesson stages and actions, consistent question controls across MCQ, multi-select, numerical, short answer, and long answer.
- Keep correct/incorrect/error feedback semantically clear and accessible.
- Preserve current content, scoring, attempt persistence, lesson progression, and class/subject entitlements.
- Ensure small-screen layouts use intentional stacking/two-row layouts where needed rather than shrinking controls until unusable.

### Parent experience
- Reuse the student-facing visual language and canonical brand.
- Clear child/subject context, understandable progress summaries, and legible reports.
- Preserve guardian access restrictions and existing account/child management workflows.

### Admin and Content Studio
- Compact professional workspace, consistent navigation and active states, clear page titles, filters, tables, and status coding.
- Keep draft/review/publish/unpublish states distinct and never imply a content action succeeded before API confirmation.
- Preview should continue to represent the student experience accurately.
- Maintain existing role and permission checks; a design change must never grant privileges.

### People Operations (HRMS)
- Share tokens, typography, controls, responsive principles, and brand lockup with the other apps.
- Keep operational screens efficient and data-dense where useful.
- Preserve employee/manager scoping, HR permissions, attendance/payroll/leave audit requirements, and separation from CMS/admin access.

## 5. Centralisation and architecture rules

1. `docs/branding.md` remains the source of truth for the existing orange-led brand identity and canonical assets.
2. Inspect the current shared branding implementation and app-level styles before changing them. Reuse the central brand component; do not create parallel logo or wordmark components.
3. Create or extend shared design tokens and reusable primitives only where the repository's app/build architecture supports it. If applications have separate bundles, keep token definitions synchronised through a documented shared source or parity check rather than pretending runtime sharing exists.
4. Avoid broad global CSS selectors that accidentally restyle third-party widgets, equations, charts, editor content, or embedded media.
5. Do not add a dependency merely to implement basic tokens, shadows, transitions, or layout.
6. Do not mix this work with API/database migrations, data imports, authentication changes, or content generation.
7. Respect the repository data policy: this design document is durable documentation; generated content, test data, screenshots with real user information, caches, logs, and intermediate reports must not be committed outside the explicitly permitted `data/` locations/policies.

## 6. Implementation phases and exit criteria

### Phase 0 — Baseline inventory (read-only)
- Map routes and layouts across `apps/web`, `apps/admin`, and `apps/people`.
- Find current tokens, fonts, shared controls, brand component usage, and duplicate/contradictory CSS.
- Record representative desktop, tablet, and mobile routes; note loading, empty, error, permission-denied, and success states.
- Capture current build/test commands and existing failures before editing.

**Exit:** inventory identifies the canonical implementation points and regression-sensitive routes. No production behaviour changed.

### Phase 1 — Token and brand foundation
- Reconcile proposed neutral/surface tokens with existing CSS.
- Establish documented colour, type, spacing, radius, elevation, focus, and motion tokens.
- Confirm all apps use the approved mark and the unchanged `QuantaEdge` name/tagline; check light and dark contrast.
- Add token parity checks if the applications have separate stylesheets/bundles.

**Exit:** tokens are consistent across apps, brand assets remain unchanged, contrast and responsive smoke tests pass.

### Phase 2 — Shared primitives and application shells
- Standardise buttons, links, fields, selects, checkboxes/radios, badges, cards, alerts, dialogs, tabs, navigation, tables, skeletons, and empty/error states.
- Standardise responsive navigation and shell spacing.
- Preserve app-specific density and capabilities while sharing visual rules.

**Exit:** component states are documented and representative screens use the same interaction patterns.

### Phase 3 — Route-by-route rollout
Apply changes in this order:
1. Public landing and authentication.
2. Student dashboard, lesson, practice, progress, and parent routes.
3. Admin shell and Content Studio.
4. People Operations shell and workflows.

For every route, check real data, empty data, loading, server errors, permission boundaries, and mobile layout. Do not replace missing production data with invented sample data.

**Exit:** all in-scope routes have been reviewed; no route is left on an accidental mixed theme.

### Phase 4 — Accessibility, responsive and performance pass
- Keyboard-only navigation and visible focus.
- WCAG AA contrast checks for text and controls.
- Screen-reader labels, heading order, form errors, status announcements, and dialog focus behaviour.
- Reduced-motion support and touch target review.
- Small-device layout checks, overflow checks, image/font loading, and layout-shift checks.

**Exit:** no known critical accessibility or responsive regressions remain; exceptions are documented.

### Phase 5 — Verification and delivery
- Run existing lint, type-check, unit, integration, and production build workflows for affected applications.
- Test API-backed save, publish, unpublish, login, practice submission, and permission-denied paths without changing their contracts.
- Review screenshots for representative desktop/tablet/mobile routes and compare all three apps.
- Check Git diff for unintended changes, generated data, secrets, dependency churn, and business-logic edits.
- Commit to `main` only according to the user's repository policy and wait for applicable GitHub Actions to finish. Report actual results; never label unrun visual checks as passed.

**Exit:** relevant CI checks are green; remaining failures and unverified browser/visual checks are explicitly listed.

## 7. Non-negotiable acceptance criteria

- One QuantaEdge brand identity; no translated/transliterated company name or tagline.
- Dark hero + light working surfaces consistently applied.
- No fake data or production-state placeholders presented as real.
- No business logic, API contract, database, scoring, curriculum, authentication, or authorization changes hidden inside visual refactoring.
- All question types and student/parent/admin/HRMS workflows remain usable.
- Keyboard focus, contrast, reduced motion, responsive layouts, and loading/error states are addressed.
- No new untracked/generated data artifacts are committed outside the repository's data policy.
- Main branch and GitHub Actions status are reported truthfully.

## 8. Progress log

- **2026-10-16:** User clarified ththe existing orange-led QuantaEdge palette must remain unchanged. The user approved implementation of the planned presentation and placement changes on 2026-10-17. Source implementation, automated verification, and rendered-browser verification remain separate gates.
- **Execution:** shared responsive polish and the cross-app brand contract are implemented on `main;` continue route-by-route functional and rendered UI verification without altering business behavior.


## Execution record — October 17, 2026

The user explicitly directed implementation of the planned UI/UX work end to end. This authorizes the layout and placement changes previously held for approval, subject to the non-negotiable requirement to preserve the existing orange-led brand palette and all business behavior.

- Learner/public web: scoped inline-validation, overflow resilience, and narrow-screen scroll improvements for existing learner, practice, progress and parent surfaces.
- Educational Admin/Content Studio: scoped invalid-control presentation, responsive action clusters, data-row hover/focus states, and visual-source/provenance overflow handling.
- People Operations: orange-led alignment and shared form/table/pagination responsive polish, retaining its separate product shell.
- Cross-app automation: a lightweight, dependency-free contract verifies official brand assets/lockup, palette parity, responsive breakpoints and accessibility baseline, then runs in CI.
- Verification gate remains evidence-based: builds and structural contracts are not the same as browser screenshots, screen-reader testing, or end-to-end HR workflow testing. Mark those only after they are actually exercised.


**Mobile brand consistency — October 17, 2026:** Admin and People Operations mobile workspace headers now render the shared `QuantaEdgeBrand` component instead of repeating the organization name as disconnected text. The cross-app contract guards these entry points.
