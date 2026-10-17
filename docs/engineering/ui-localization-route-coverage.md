# UI Route Localization Coverage Matrix

**Purpose:** Track localization evidence per actual Next.js UI route. The machine-generated inventory identifies route/file paths; a recorded first pass does not mean every nested control, accessibility label, error state or dynamic status has been audited.

**Inventory evidence:** [GitHub Actions run #3025](https://github.com/Manav326/QuantaEdge/actions/runs/39001656781), commit [`36b38fa34c1b602e4f7238d43300a48023b3e011`](https://github.com/Manav326/QuantaEdge/commit/36b38fa34c1b602e4f7238d43300a48023b3e011). The [`ui-localization-route-inventory` artifact](https://github.com/Manav326/QuantaEdge/actions/runs/39001656781/artifacts/12089550843) reports **50 page routes: Web 23, Admin 17, People Operations 10; 0 route-handler files and 0 duplicate primary routes**. Artifact retention is 14 days; regenerate with `node scripts/inventory_ui_routes.mjs`.

**Status definitions:** **First pass — audit pending** means prior migration work is recorded but route acceptance remains open. **Needs audit/migration** means the tracker contains no route-specific certification evidence yet; it does not assert every screen string is English. **Localized in current slice — CI pending** means a new migration has been committed but not yet validated by its own CI run.

## Student and Parent Web

| Route | Source file | Current evidence/status | Next verification |
|---|---|---|---|
| `/` | `apps/web/app/page.tsx` | First pass — audit pending | Check labels, forms, validation and public errors. |
| `/certificate/verify` | `apps/web/app/certificate/verify/page.tsx` | First pass — audit pending | Check verification status/error variants and locale persistence. |
| `/login` | `apps/web/app/login/page.tsx` | Inline auth copy extracted — CI pending, route audit still open | Verify fields, accessibility labels and all error states in both locales. |
| `/login/student` | `apps/web/app/login/student/page.tsx` | Static inline-pair extraction — CI pending, route audit still open | Verify login validation, remaining literal JSX/accessibility text and session errors in both locales. |
| `/parent` | `apps/web/app/parent/page.tsx` | First pass — audit pending | Check family dashboard and progress labels. |
| `/parent/children` | `apps/web/app/parent/children/page.tsx` | Static inline-pair extraction — CI pending, route audit still open | Verify child creation, credential dialog, raw fallback errors and remaining literal text. |
| `/parent/profile` | `apps/web/app/parent/profile/page.tsx` | Static inline-pair extraction — CI pending, route audit still open | Verify photo/profile feedback, placeholders, residual fallback errors and language preferences. |
| `/privacy` | `apps/web/app/privacy/page.tsx` | First pass — audit pending | Verify entire legal body and language selector. |
| `/session` | `apps/web/app/session/page.tsx` | First pass — audit pending | Check session recovery and boundary messages. |
| `/student` | `apps/web/app/student/page.tsx` | First pass — audit pending | Audit dashboard widgets and empty/error states. |
| `/student/account-lifecycle` | `apps/web/app/student/account-lifecycle/page.tsx` | First pass — audit pending | Verify account-state actions and confirmations. |
| `/student/certificates` | `apps/web/app/student/certificates/page.tsx` | First pass — audit pending | Check eligibility, pending results and certificate language. |
| `/student/classes` | `apps/web/app/student/classes/page.tsx` | First pass — audit pending | Check recording list, player and empty states. |
| `/student/diagnostic` | `apps/web/app/student/diagnostic/page.tsx` | First pass — audit pending | Check each diagnostic step, errors and score screen. |
| `/student/learn` | `apps/web/app/student/learn/page.tsx` | First pass — audit pending | Check lesson blocks and UI locale vs learning language. |
| `/student/practice` | `apps/web/app/student/practice/page.tsx` | First pass — audit pending | Check question types and answer feedback. |
| `/student/profile` | `apps/web/app/student/profile/page.tsx` | First pass — audit pending | Check profile/photo-upload interactions and validation. |
| `/student/progress` | `apps/web/app/student/progress/page.tsx` | First pass — audit pending | Check score descriptions and progress status. |
| `/student/structured-lessons` | `apps/web/app/student/structured-lessons/page.tsx` | First pass — audit pending | Verify catalogue filters and failure states. |
| `/student/structured-lessons/[artifactId]` | `apps/web/app/student/structured-lessons/[artifactId]/page.tsx` | First pass — audit pending | Verify deep-link load, invalid artifact and response continuity. |
| `/student/tests` | `apps/web/app/student/tests/page.tsx` | First pass — audit pending | Check tests, submission, results and written answers. |
| `/student/textbooks` | `apps/web/app/student/textbooks/page.tsx` | First pass — audit pending | Check book/chapter lists, PDF states and controls. |
| `/terms` | `apps/web/app/terms/page.tsx` | First pass — audit pending | Verify legal body and language selector. |

## Admin

| Route | Source file | Current evidence/status | Next verification |
|---|---|---|---|
| `/` | `apps/admin/app/page.tsx` | First pass — audit pending | Review dashboard retry/error, KPI and empty states. |
| `/account-lifecycle` | `apps/admin/app/account-lifecycle/page.tsx` | First pass — audit pending | Check status actions, reason dialogs and feedback. |
| `/assessments` | `apps/admin/app/assessments/page.tsx` | Authoring, catalogue, queue and review dialog localized — CI pending | Browser-check both locales, API error details, validation, certificate records and audit-date formatting. |
| `/certificates` | `apps/admin/app/certificates/page.tsx` | First pass — build-verified, audit pending | Verify filters, revocation/audit states and localized dates in browser. |
| `/classes` | `apps/admin/app/classes/page.tsx` | Scheduling form + session/recording actions first pass — next CI pending | Verify the latest Classes localization build, both locales, session/provider status notices, recording lifecycle actions, and accessibility/empty/error states; route acceptance remains open. |
| `/content` | `apps/admin/app/content/page.tsx` | Needs audit/migration | Audit Content Studio editor, preview, visual review and validation. |
| `/employees` | `apps/admin/app/employees/page.tsx` | Needs audit/migration | Audit employee and permission-facing text. |
| `/legacy-content` | `apps/admin/app/legacy-content/page.tsx` | Needs audit/migration | Inspect retained route before deciding deprecation or migration. |
| `/login` | `apps/admin/app/login/page.tsx` | First pass — audit pending | Check authentication failures and pre-login language selector. |
| `/parents` | `apps/admin/app/parents/page.tsx` | First pass — audit pending | Check parent/child permissions, confirmations and status feedback. |
| `/questions` | `apps/admin/app/questions/page.tsx` | Localized in current slice — CI pending | Verify permission guidance, prompts, history snapshots and all visible status/error variants. |
| `/resources` | `apps/admin/app/resources/page.tsx` | Needs audit/migration | Translate PDF library forms, ingestion/preview and assignment states. |
| `/session` | `apps/admin/app/session/page.tsx` | Needs audit/migration | Audit staff session-boundary/recovery messages. |
| `/source-ingestion` | `apps/admin/app/source-ingestion/page.tsx` | Needs audit/migration | Translate ingestion, verification and failure states. |
| `/structured-lesson-preview` | `apps/admin/app/structured-lesson-preview/page.tsx` | Needs audit/migration | Check question types, preview controls and validation feedback. |
| `/students` | `apps/admin/app/students/page.tsx` | First pass — audit pending | Verify table, pagination, load errors and search. |
| `/translations` | `apps/admin/app/translations/page.tsx` | First pass — audit pending | Check filters, editor, save/review/publish statuses and dialogs. |

## People Operations

| Route | Source file | Current evidence/status | Next verification |
|---|---|---|---|
| `/` | `apps/people/app/page.tsx` | First pass — audit pending | Audit employee directory rows, forms, operations and dialogs. |
| `/access` | `apps/people/app/access/page.tsx` | First pass — audit pending | Audit credential/role dialogs, permissions and status messages. |
| `/assignments` | `apps/people/app/assignments/page.tsx` | First pass — audit pending | Verify proposal/history/workload dialogs and error feedback. |
| `/attendance` | `apps/people/app/attendance/page.tsx` | First pass — audit pending | Verify attendance correction, leave, holiday and balance workflows. |
| `/change-password` | `apps/people/app/change-password/page.tsx` | First pass — audit pending | Verify password rules, submission and server errors. |
| `/login` | `apps/people/app/login/page.tsx` | First pass — audit pending | Verify fields, validation, errors and language before login. |
| `/my-classes` | `apps/people/app/my-classes/page.tsx` | First pass — audit pending | Check join-window guidance, schedules and private-link errors. |
| `/payroll` | `apps/people/app/payroll/page.tsx` | First pass — audit pending | Verify period/snapshot/approval/reconciliation labels and permissions. |
| `/self-service` | `apps/people/app/self-service/page.tsx` | First pass — audit pending | Verify employee profile, requests and lifecycle dialogs. |
| `/workforce` | `apps/people/app/workforce/page.tsx` | First pass — audit pending | Verify recruitment, asset, performance and exit workflows. |

## Current priority

1. Use the generated locale registry as the only source for selector options; do not add per-page language options.
2. Remove inline translation pairs and direct static UI sentences route by route, beginning with pre-login/auth copy and deep Admin/People dialogs.
3. Add a blocking literal anti-regression guard only after remaining legacy raw-copy debt is migrated or explicitly tracked.
4. Verify cross-app locale persistence and language switching without changing learning language, content, test state or permissions.
5. Map the existing curriculum-translation APIs/provider/review workflow before designing any runtime translation cache. Do not introduce a duplicate AI translation service without an evidenced architecture gap.
