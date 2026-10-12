# QuantaEdge UI/UX Audit — Initial Remediation Record

**Repository:** [Manav326/QuantaEdge](https://github.com/Manav326/QuantaEdge)  
**Branch:** `main`  
**Audit started:** 2026-10-13  
**Scope:** Static repository/route inventory and targeted accessibility/responsive remediation. This is not a claim that every route has been browser-tested.

## Repository and route inventory

The current `main` contains three web applications plus the Android client:

- **Student/parent web — `apps/web`**: landing page, login/session, student home, learning, practice, progress, textbooks, tests, live classes, certificates, profile, parent reports/children/profile, certificate verification, privacy and terms.
- **Admin — `apps/admin`**: overview, login/session, Content Studio and legacy content, questions, assessments, classes, students, parents, employees, resources, source ingestion, translations and certificates.
- **People Operations — `apps/people`**: overview/employee management, attendance and leave, login.
- **Android — `apps/android`**: native student learning, assessments, profile, progress, certificates, tutor and recorded/live learning UI.

The route inventory above is derived from the current Git tree. It does not imply every backend-dependent workflow was exercised.

## Findings and priority

| Priority | Area | Finding | Remediation/status |
|---|---|---|---|
| P1 | People Operations accessibility | The People Operations global stylesheet did not define a consistent `:focus-visible` indicator, unlike the larger web/admin stylesheets. Keyboard users could lose track of focus. | **Fixed on main:** add a high-visibility focus outline, including a forced-colors fallback. |
| P1 | People Operations mobile usability | The employee search toolbar input used a fixed 220px minimum width; at narrow viewports this can compete with adjacent controls. | **Fixed on main:** allow the input to take a full row on mobile and let action buttons wrap. |
| P1 | People Operations touch usability | Shared buttons and form controls did not have a consistent minimum interaction height. | **Fixed on main:** establish a 44px minimum target for common controls. |
| P2 | People Operations tablet layout | Three-column metric cards remained in use until the mobile breakpoint, which can make intermediate-width layouts cramped. | **Fixed on main:** use a two-column card layout from 761px through 1024px. |
| P2 | People Operations motion/accessibility | The stylesheet did not honor `prefers-reduced-motion`. | **Fixed on main:** disable smooth scrolling and minimize animation/transition duration when reduced motion is requested. |
| P2 | People Operations narrow screens | Tabs could compete for width and modal/action layouts could become crowded on narrow screens. | **Fixed on main:** enable horizontal tab scrolling, wrapping actions, and compact modal/page padding. |
| P2 | Cross-application visual consistency | Student/admin styles already have larger, separately evolved style systems; People Operations had a much smaller, separate stylesheet. Shared interaction baselines need to be compared during browser QA. | **Partially addressed:** People Operations now has an accessibility/responsive baseline. Full visual comparison remains outstanding. |

## Change committed

- `apps/people/app/globals.css` — added visible keyboard focus, 44px common controls, disabled-state affordance, tablet card breakpoint, narrow-screen toolbar/tab/modal adjustments, reduced-motion support, and forced-colors focus styling.
- Commit: `5f695e363192acf222c0c89f3f0a05163eb5eb6f`

## Verification and limitations

- Confirmed repository default branch is `main` and read the current tree, route files, key layouts, and application stylesheets from GitHub.
- Reviewed recent GitHub Actions jobs. At the initial baseline, the preceding run `38296695995` had an API verification failure while its web/admin/People image builds and content safeguards passed. The newer run `38296952627` subsequently reported API verification, web/admin/People image builds, and content safeguards as successful; its API image build was still running when first inspected.
- This environment could not resolve `github.com` from the local shell, so a local checkout/build and browser screenshot run were not available. No local lint, typecheck, end-to-end, or viewport screenshot result is claimed here.
- The new CSS has been committed directly to `main`; GitHub Actions should be checked after this commit before considering it verified.
- The full audit remains in progress. Student learning, admin/Content Studio, and People Operations routes still need live browser walkthroughs at mobile/tablet/desktop sizes, including empty/error/loading states, permissions, keyboard navigation, and real API-backed mutations.

## Next audit pass

1. Re-check the GitHub Actions run triggered by the remediation commit.
2. Run the applications in a browser and inspect the route inventory at approximately 320/375/390/430/768/1280/1440px.
3. Exercise student login → class/subject → lesson → practice/test → progress; admin content edit → preview → publish controls; and People employee/attendance/leave workflows with appropriate permissions.
4. Fix confirmed P0/P1 regressions first, add focused regression tests, and update this record with exact verification evidence.


## Follow-up accessibility remediation

Two additional UI-only changes were committed directly to `main` after the initial audit record:

- `apps/people/app/page.tsx` — the Employees / Departments / Designations view switcher now exposes its group label and selected state with `role="group"` and `aria-pressed`, and explicitly uses non-submit buttons.
- `apps/people/app/attendance/page.tsx` — the Attendance / Leave requests / Holiday calendar / Leave balances switcher now exposes the selected state and group label, and explicitly uses non-submit buttons.

Commits:
- `de50d24fea8ed75b8951143eebb39c0ac856ea28`
- `393c47e26f3aa4ff9f708797c113cc9e4f17aab7`

These are source-level accessibility corrections. They still require browser/keyboard verification; no manual assistive-technology test is claimed.


## Second remediation pass — student and admin interaction accessibility

Additional source-level fixes were committed to `main`:

- `apps/web/app/globals.css` — added a consistent visible keyboard-focus ring for links, buttons, form fields, and other keyboard-focusable elements, with a forced-colors fallback. Commit: `5417c482d4e09c17f2427554df1dadc5e8657439`.
- `apps/admin/app/globals.css` — added the corresponding high-visibility focus ring for admin and Content Studio workflows, including forced-colors support. Commit: `3cebce21acf81a8869d1dbc600dd466cbadc21bc`.
- `apps/web/app/student/page.tsx` — student profile loading is now announced as a polite status and the blocking student-load error is announced as an alert. This changes accessibility semantics only; it does not alter the API flow or error recovery. Commit: `3f5bac9bfe9e7f709937db5140fa3f4bb5d2515d`.

### Current verification status

- A preceding GitHub Actions run for commit `f03e99ff8937bd629060ace7faec907c37ab92cd` completed API verification, web/admin/People image builds, and content-import safeguards successfully; its API image build was still in progress at the time checked.
- Earlier UI-remediation run `38297929776` completed successfully for all its listed jobs, including API verification and web/admin/People image builds.
- The newest student accessibility commit triggered run `38299084573`; it was queued when last checked. Several runs triggered by rapid consecutive direct commits were cancelled by GitHub Actions concurrency, so the newest commit still needs a final CI result.
- Browser screenshots, viewport-by-viewport inspection, and manual keyboard/screen-reader verification remain outstanding. These commits must be treated as code-level remediation, not proof of complete WCAG conformance.

Next, inspect the live Content Studio and student workflows in a browser-capable environment, and only then make additional targeted visual changes. Avoid rapid unrelated commits that cancel the latest validation run.


## Third remediation pass — payroll navigation semantics

- `apps/people/app/payroll/page.tsx` — the Payroll periods / Compensation / Salary components selector now has a programmatic group label, exposes its selected state through `aria-pressed`, and explicitly marks the Refresh action and selectors as non-submit buttons. Existing permission checks and business actions are unchanged.
- Commit: `d4c313497fbad07c3af8b39f8041fbb4c509572e`.

This is a source-level correction. Keyboard navigation and assistive-technology behavior still need browser verification.


## Fourth remediation pass — student lesson navigation and Content Studio status

- `apps/web/app/student/learn/LearnClient.tsx`: explicitly mark Previous lesson and Next lesson navigation controls as `type="button"`, avoiding accidental form-submit behavior if the surrounding layout is extended with a form later.
- `apps/admin/app/content/page.tsx`: expose the Content Studio connection/loading label as a polite live status, hide the decorative status dot from assistive technology, and give the icon-only refresh control an explicit accessible name and button type.
- Existing lesson navigation, content API calls, permissions, and refresh behavior are unchanged.
- These are source-level improvements; visual layout, keyboard traversal, and screen-reader announcements still require browser verification.
