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
