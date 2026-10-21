# Student Web Navigation Refactor — Implementation Plan

Status: In progress
Scope: student-facing web navigation in `apps/web`; parent workspace and backend behavior must remain unchanged.
Target branch: `feature/student-web-navigation-groups`
Target base: `main`

## Target information architecture

1. **Today** — `/student`, dashboard and recommendations.
2. **Learn** — `/student/learn` (Lessons & topics) and `/student/classes` (Live & recorded classes).
3. **Practice & Tests** — `/student/practice` (Practice), `/student/tests` (Tests & results), and `/student/diagnostic` (Initial diagnostic).
4. **Progress & Certificates** — `/student/progress` (My progress) and `/student/certificates` (Certificates).
5. **Textbook Library** — `/student/textbooks`.

Student Profile & Settings remains reachable from the top-right student account menu. Language switching stays in the top bar. Logout remains a separate action, not a sixth primary category. Public `/certificate/verify` stays linked from certificate records and is not part of the student navbar.

## Guardrails

- Do not rename/delete routes, remove functionality, or change APIs/data models as part of this navigation refactor.
- Preserve query parameters, deep links, active-route semantics, browser history and textbook return-to-origin flows.
- Keep formal assessments distinct from practice; do not imply ordinary practice qualifies for admin-issued certificates.
- Preserve class join/access checks, attendance, recording progress, test attempts/results/leaderboards, certificate eligibility/issue/download/verification, and profile/photo updates.
- Keep parent navigation and role boundaries unchanged even though `WorkspaceLayout.tsx` is shared.
- Keep labels usable in English and Hinglish; support keyboard and assistive technologies.
- Five is the maximum number of primary student destinations. Account/security controls are separate utilities.

## Actionable checklist

### Phase 1 — Baseline and inventory
- [ ] Record the base commit and current CI status.
- [ ] Inventory student routes, navbar/account/contextual links, query parameters and return paths.
- [ ] Review current package scripts and existing CI coverage.
- [ ] Identify shared layout behavior that must remain unchanged for parents.

### Phase 2 — Navigation rules
- [ ] Define group labels, icons, children and exact route ownership.
- [ ] Define bilingual labels and compact responsive labels.
- [ ] Define active states, auto-expansion and manual collapse rules.
- [ ] Keep diagnostic entry conditional on need and reachable within Practice & Tests.
- [ ] Keep certificates discoverable and public verification contextual.

### Phase 3 — Shared configuration
- [ ] Replace the flat student menu configuration with a typed five-group model.
- [ ] Use a single source of truth for desktop/mobile student navigation.
- [ ] Preserve parent menu configuration and behavior.
- [ ] Separate group expansion state from sidebar collapse state.
- [ ] Open the active group when entering a nested route, while letting users collapse it manually.
- [ ] Preserve direct links and refresh/deep-link behavior.

### Phase 4 — Desktop UX
- [ ] Render five primary categories, with nested links for grouped categories.
- [ ] Implement keyboard-accessible expand/collapse controls, active parent styles and child `aria-current`.
- [ ] Preserve collapsed sidebar icons/tooltips and stored sidebar preference.
- [ ] Keep account identity, language switching, safe-learning footer and Logout accessible.

### Phase 5 — Mobile UX
- [ ] Show five primary student destinations on the mobile bottom bar.
- [ ] Provide an unclipped submenu/sheet for the grouped destinations.
- [ ] Keep Logout separately accessible and keep Profile under the account menu.
- [ ] Check safe areas, tap targets, keyboard behavior, long Hinglish text, portrait/landscape and tablet widths.

### Phase 6 — Preserve full journeys
- [ ] Today → recommendation → subject/lesson or initial diagnostic.
- [ ] Subject → chapter → lesson → MCQ → tutor/next lesson/topic practice.
- [ ] Live class access-check → join/leave/attendance; recording → player → saved position.
- [ ] Practice → answer feedback/retry; formal test → submit → results → question review/leaderboard/certificate when eligible.
- [ ] Progress → certificate eligibility/issue → view/download PDF/PNG → public verification.
- [ ] Textbook filters → book/chapter → PDF reader controls → return to correct origin.
- [ ] Profile/photo edit → save → refreshed account identity.
- [ ] Session expiry/logout redirects and parent workspace regression checks.

### Phase 7 — Localization, accessibility and polish
- [ ] Validate English/Hinglish labels and Devanagari rendering.
- [ ] Add/check accessible names, `aria-expanded`, `aria-controls`, `aria-current`, focus visibility and semantic buttons/links.
- [ ] Unify spacing, icons, hover/focus/active states and responsive styling.
- [ ] Verify loading, empty, error and unauthorized states.

### Phase 8 — Automated and browser validation
- [ ] Run web lint/type checking and production build where supported by the repository.
- [ ] Add focused automated tests for route-to-group mapping and navigation behavior where test infrastructure allows.
- [ ] Run current CI on the pull request and resolve introduced failures.
- [ ] Execute end-to-end checks for all journeys above in desktop/mobile and English/Hinglish where browser tooling is available.
- [ ] Confirm parent navigation and authentication boundaries are unchanged.

### Phase 9 — Review, merge and post-merge verification
- [ ] Review the diff for out-of-scope backend/API/parent changes.
- [ ] Re-run required checks on the final candidate commit.
- [ ] Verify every original student destination remains reachable.
- [ ] Merge into `main` only after checks pass and merge is safe.
- [ ] Verify merged commit and post-merge CI; record any external deployment check separately.

## Definition of done

- Five primary student navigation categories; no feature or route removed.
- Desktop/mobile, collapsed navigation and English/Hinglish states work.
- All listed student journeys pass.
- Parent workspace and session protections have no regressions.
- Required CI/build checks are green.
- Changes are merged into `main`, with merge commit and check links recorded.
