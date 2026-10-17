# Centralized Translation Implementation Tracker

**Purpose:** Resumable implementation record for centralized QuantaEdge web UI localization and context-aware learning-content translation. Update this file on every meaningful implementation milestone. Continue from the latest main revision and this tracker; do not restart completed work or create another branch.

**Baseline reviewed before the route-inventory continuation:** [`8ba4f10c6f60c05da77719039d3a7700822fefae`](https://github.com/Manav326/QuantaEdge/commit/8ba4f10c6f60c05da77719039d3a7700822fefae). Latest successful full localization-code build remains [Actions #3021](https://github.com/Manav326/QuantaEdge/actions/runs/38996750050) for commit `66a2c3d0b0a9b9685210b199b536cbdb145f30f3`; it validates that older localization-code commit, not the new inventory changes.

**Overall status: IN PROGRESS — NOT PRODUCTION-COMPLETE.** A shared static UI catalog, shared translation APIs/hooks, cross-app language preference bridge, and extensive first-wave route migrations are implemented. The complete admin/People page-body migration, comprehensive route coverage proof, and a central server-side AI translation service for arbitrary runtime dynamic content are not yet complete. Do not describe the entire site as fully translated until the remaining checklists and CI gates are green.

## Current baseline and evidence

- Canonical English catalog: `localization/catalogs/english.json`.
- Canonical Hinglish catalog: `localization/catalogs/hinglish.json`.
- Shared runtime and synchronization contract: `localization/runtime.ts`, `scripts/sync_ui_localization.py`.
- Generated copies are stored under each independent Next.js Docker context: `apps/{web,admin,people}/app/i18n/catalogs/{english,hinglish}.json` and app-local `catalog.ts` files.
- At current `main` head `7c57daccaa2a4f6c6cf8e2cf76cc3b5a6d13d458`, the canonical catalogs have **2,217 keys each**, identical key sets, 0 blank messages, and 0 interpolation-placeholder mismatches. All six generated JSON copies are byte-for-byte equal to the canonical files. This direct file verification is a source-level check; the latest full build of the localization code is [Actions run #3021](https://github.com/Manav326/QuantaEdge/actions/runs/38996750050), which passed all required jobs on commit `66a2c3d0b0a9b9685210b199b536cbdb145f30f3`. Newer main commits are content-pipeline checkpoints. Re-run `python3 scripts/sync_ui_localization.py --check` after catalog changes; never hand-edit only one generated copy.
- CI invokes the synchronization/parity validator in `.github/workflows/ci.yml`. The check validates catalog parity, placeholder sets, and generated copies.
- Static interface locale and learning-content locale are separate preferences. The web app keeps the existing `qe-learning-language` and `qe-certificate-language` behavior. The shared UI choice uses `qe-locale`, with `english` and `hinglish` as normalized cross-app values and backward compatibility for the older `hindi` value.
- Hinglish means Hindi in Devanagari with commonly used English/Roman-script terms retained; it does not mean phonetic Hindi written in Roman letters.

## Architecture implemented

- Central key/value resources driven by `localization/locales.json` and a generated registry. `translateUi`, locale normalization, aliases, display names, HTML language tags, fallback and selector choices are registry-backed. Adding a catalog plus one metadata entry generates all three app copies without page-level edits.
- Web exposes `useUiTranslation` and `UiText` through the existing `LanguageProvider`; it keeps the distinct learning-language and certificate-language controls.
- Admin uses the existing `AdminWorkspaceContext` for catalog translation and shares the locale through the same cookie/local-storage contract.
- People Operations is wrapped in `UiLocaleProvider`, has a header language selector, and reads/writes the shared locale cookie.
- Navigation labels for student/parent, admin, and People Operations are key-based rather than storing parallel English/Hinglish labels in every navigation definition.
- Source content, question IDs, lesson IDs, grading, authentication, role permissions, and route ownership are not supposed to change as part of UI-string extraction.

## Work implemented on main

| Area | Current state | Evidence / notes |
|---|---|---|
| Central English/Hinglish UI catalogs | Done for foundation; expanding | 1,578 matched keys as of `780adccfd3`; run the sync validator after every catalog update. |
| Shared runtime, key fallback and interpolation | Implemented | `localization/runtime.ts`, copied into app-local `catalog.ts`. Placeholder and empty-key handling received follow-up fixes. |
| Preference bridge | Implemented for shell-level selector | Shared `qe-locale` cookie/storage contract across web/admin/People; validate same-domain deployment and cross-app refresh behavior in browser tests. |
| Shared shell/navigation labels | Partially migrated | Student/parent and admin navigation use catalog keys. People navigation labels use catalog messages and have a language selector. Some account/session labels still need coverage. |
| Public landing and authentication | First migration pass done | Landing, parent login and student login JSX pairs moved into the catalog. Audit dynamic errors and every remaining input/accessibility string. |
| Parent web portal | First migration pass done | Family report, children management, profile pages migrated. Re-audit all raw strings and interpolated messages. |
| Student web portal | Broad first pass done | Dashboard, learn, practice, tests, textbooks, lesson catalogue/detail, diagnostic, profile, progress, certificates, classes, account lifecycle and shared menus have been migrated in multiple commits. Do not interpret this as all student routes/strings being complete. |
| Shared learning components | Partial migration done | TutorDock, pagination/textbook reader, certificate verification, structured lesson/question UI labels have had centralization work. Structured lesson content locale remains distinct from UI locale. |
| Admin page bodies | Partially migrated | Admin overview/dashboard labels, KPI definitions, review/source/asset empty states, status labels, shell/sidebar, and login page text are now catalog-backed. The remaining page bodies—content Studio, questions, resources, source ingestion, language review, assessments, tests/grading, live classes, staff, students, parents, account lifecycle, certificates, structured preview and dialogs/forms/tables—still require a route-by-route audit. |
| People Operations page bodies | Partially migrated — substantial first pass | Centralized first passes for public sign-in/password setup, employee directory, access governance, academic assignments/classes, payroll, attendance/leave, workforce lifecycle, and employee self-service. Remaining work includes employee/access deep dialogs, field-level copy, a complete route/string audit, and build/browser acceptance per route. |
| Dynamic server/API/database content | Not complete as a generalized runtime service | QuantaEdge already has contextual, source-versioned, review-gated curriculum translation tooling documented below. The new UI catalog does not translate arbitrary database/API text, and no frontend should call an AI provider directly. |
| Coverage inventory and anti-regression guard | In progress | CI verifies catalog and app-copy parity. A complete page/component string-coverage check and exception register remain to be built. |
| Production build validation | Latest localization-code build passed; head caveat noted | [Run #3021](https://github.com/Manav326/QuantaEdge/actions/runs/38996750050) for `66a2c3d0b0a9` passed change detection, Android, API verification, web/admin/People images, content safeguards, and API image build. Current `main` is newer due to textbook-content checkpoint commits; no localization files changed after that successful full build in the reviewed commit history. Re-run a full build if additional app code changes are made. |

### Representative implementation commits

- [Central bilingual UI catalog foundation](https://github.com/Manav326/QuantaEdge/commit/e02ccb27cda3e02cecab7ec6a669d182e7ac49b8).
- [Shared web/admin/People locale hooks and preference bridge](https://github.com/Manav326/QuantaEdge/commit/557cbbdd9229331c66595c08e055b2fe0528dba7).
- [Web student/parent navigation refactor](https://github.com/Manav326/QuantaEdge/commit/65b31977c30dfeb0354fe922594d2092bf7a8205).
- [Admin navigation refactor](https://github.com/Manav326/QuantaEdge/commit/823f716d1518376c15b100d4b79d114490eb95f1).
- [Shared language selectors across workspaces](https://github.com/Manav326/QuantaEdge/commit/d86c4c566d52a76ca79360671383d599c3133d8c).
- [Landing and login catalog migration](https://github.com/Manav326/QuantaEdge/commit/d85ebaec780b36c7b4b4567b1bd83bd5aefb5789).
- [Parent portal catalog migration](https://github.com/Manav326/QuantaEdge/commit/4d96cddad93772dc74bfaa29adbf5dc070832b48).
- [Student dashboard/learn/practice migration](https://github.com/Manav326/QuantaEdge/commit/33331ac1b22fca005212f69f416f6e26b4aa46b8).
- [Tests, textbook, and lesson-list migration](https://github.com/Manav326/QuantaEdge/commit/2b5eefaf9c6734b051f6786d1d7f7a09a6aa753a).
- [TutorDock controls/status migration](https://github.com/Manav326/QuantaEdge/commit/7b86a825a26bd35cfa775da1e5df0b1d8f4ddc10).
- [Student learning and assessment message migration](https://github.com/Manav326/QuantaEdge/commit/8987718f97487dab4c3faaae76d66da88772a2ac).
- [Latest known navigation build-contract fixes](https://github.com/Manav326/QuantaEdge/commit/07e72efdd2c99524b985b6a6886504bb36cc9498).
- [TutorDock translator call-site TypeScript fix](https://github.com/Manav326/QuantaEdge/commit/fd5f1f725c6a25e875966e355edd7fdd29339298).
- [Admin sidebar shell-label migration](https://github.com/Manav326/QuantaEdge/commit/bd7010ef8453dbb3348115c86fa9aea883b7e802).
- [Admin dashboard localization pass](https://github.com/Manav326/QuantaEdge/commit/3b82f858487f4a50d1984ef4acb0f908bda61ef1).
- [Admin login localization and pre-login language selector](https://github.com/Manav326/QuantaEdge/commit/7cd22a47a9457738a7c7dc8672f8010e81c89220).
- [Admin Language Review page centralized catalog migration](https://github.com/Manav326/QuantaEdge/commit/b48733d0ac4380ce16f753672be48bb5145c9b17).
- [Language Review JSX/status and catalog follow-up fixes](https://github.com/Manav326/QuantaEdge/commit/d188ac4fc09383b24514e45d1bf2ed26f5ab155a).
- [Student directory and Account Lifecycle catalog migration](https://github.com/Manav326/QuantaEdge/commit/8f50b81dca5d37f5cb0dec7584c0f0f7bcbefd6b).
- [Parent directory first-pass catalog migration](https://github.com/Manav326/QuantaEdge/commit/780adccfd3b8f98c36c84776d0f6fb5a32dc1641).

The catalog currently contains many stable, generated `legacy.web.*` keys from safely extracting existing English/Hinglish pairs. This is a transitional naming scheme, not the desired final authoring standard. Add semantic namespaces for new strings and gradually rename legacy keys with tests; do not change both content and identifiers blindly in bulk.

## AI-powered contextual learning-content translation — existing system to reuse

The database-backed learning translation system is separate from the UI catalog and is already documented in [Bilingual content and curriculum architecture](../bilingual-content-architecture.md) and [Offline English translation import](../offline-english-translation-import.md).

Preserve these rules when implementing the next phase:

1. Stable curriculum/lesson/block/question/option/assessment identity remains language-independent.
2. A translation is valid only for its source revision/fingerprint, target language and compatible context. Old revisions remain history and must not be served for changed source content.
3. Context includes class/grade, subject, chapter, lesson/topic, content/entity type, surrounding passage and glossary. The translation must preserve math/science meaning, equations, units, question/option linkage, stable IDs and answer/grading logic.
4. AI provider access is server-side only. Do not expose keys or invoke providers from React page components.
5. Reuse the existing contextual translator, source-hash/version model, audit reports, retry behavior and review queue where applicable. AI-generated output remains DRAFT/IN_REVIEW until staff approves and publishes it. Do not create a duplicate translation table/service without an architecture gap documented first.
6. For runtime dynamic text not attached to a translatable curriculum entity, design a dedicated server-side translation service/cache only after mapping the existing API/database contracts. Cache by content hash, target locale, context/glossary version and model/prompt version. Do not call AI on every render; deduplicate requests, bound retries/cost and make failures observable.
7. Translate structured content by field and entity type, not by flattening arbitrary JSON, markup, tables, math, media, or assessment objects into plain strings. Preserve rich formatting and escape model output safely.

## Remaining implementation checklist

### P0 — Restore and verify CI after localization changes

- [x] Inspect failures from the last full build and identify the specific regression causes.
- [x] Remove duplicate `UiText` import in the structured-lessons catalogue client.
- [x] Replace stale `ADMIN_OVERVIEW_ITEM.english` access with the catalog-backed label function.
- [x] Update the People UI contract to assert the new centralized access-governance key while preserving permission-gating checks.
- [x] Confirm the Admin Classes syntax-fix commit’s full GitHub Actions build is green: all eight required jobs passed on [`dd95053`](https://github.com/Manav326/QuantaEdge/commit/dd95053e85d57b82b6ff1d3b29ca2581c79016ed), [run #3048](https://github.com/Manav326/QuantaEdge/actions/runs/39006837008). The subsequent Classes session/recording localization batch still needs its own CI run.

### P1 — Complete route and static-string coverage

- [x] Generate and validate a machine-readable route inventory in CI for web, admin and People Operations; [run #3025](https://github.com/Manav326/QuantaEdge/actions/runs/39001656781) reported 50 page routes (Web 23, Admin 17, People 10), 0 route handlers and 0 duplicate primary routes.
- [x] Add the route-by-route localization coverage matrix at `docs/engineering/ui-localization-route-coverage.md`; first-pass migration is not route certification.
- [ ] Complete the route-by-route raw-string, dialog, accessibility and browser-acceptance audit against that matrix.
- [x] Migrate admin login and overview/dashboard first pass, plus the Language Review workflow page. Validate all with CI before considering these routes complete.
- [x] Migrate first pass of Student directory, Account Lifecycle and Parent directory copy into the central catalog; verify these routes in browser after CI is green.
- [ ] Migrate remaining admin page body text: Content Studio, resources, source ingestion, deeper assessment grading dialogs/feedback and audit states, live classes, staff/employees, structured lesson preview, session and remaining dialogs/forms/tables. Questions, Certificates and the primary Assessments authoring/queue surfaces have first migration passes; route-level/browser acceptance remains open.
- [ ] Migrate all People Operations page bodies: login/change-password, employee directory, workforce, access, attendance/leave, payroll, teaching assignments/classes, and employee self-service.
- [ ] Audit remaining student and public routes, including profile/settings, class recording detail, all test states, error/empty/loading states, legal/metadata and account menus.
- [ ] Replace page-level `tx('Hinglish', 'English')` helpers, inline language ternaries, duplicate local dictionaries and hardcoded accessibility text with typed catalog keys; leave content-derived values/data untouched unless their formatting needs locale handling.
- [ ] Replace transitional `legacy.web.*` keys with semantic feature namespaces incrementally, preserving key compatibility or using a deliberate migration map.
- [ ] Ensure each required message has both locales and matching interpolation placeholders; document intentional untranslated names, code, IDs, URLs and user-generated content as exceptions.

### P2 — Central AI translation service for dynamic content

- [ ] Map existing translation entities, API endpoints, source fingerprint/version logic, database schema, AI provider routing, job/retry pattern, and review workflow before adding service code.
- [ ] Write the decision record for how the existing contextual translator is called from API/jobs, and whether a new runtime-cache table is actually required.
- [ ] Implement one server-side translation boundary for eligible dynamic content, with typed input/output, context/glossary, schema validation, source hash, locale, prompt/model version, bounded retries, timeout and observability.
- [ ] Reuse existing stored reviewed translations when appropriate; never serve stale-source or unreviewed educational translations to learners where approval is required.
- [ ] Add cache/deduplication, invalidation on source/context change, cost/rate limits, authorization and private-content isolation.
- [ ] Pass the user's selected locale and learning language separately: UI locale for controls, learning language for lessons/questions/AI tutor answers.
- [ ] Test provider failure, unknown locale, missing content, concurrent requests, stale source, glossary/formatting correctness, retry recovery, and human review gating.

### P3 — Acceptance and observability

- [x] CI fails for catalog key/placeholder drift, missing locale metadata/catalog pairs, alias collisions, placeholder drift, stale generated registry and out-of-sync app copies.
- [ ] CI check fails for direct static UI sentences in all covered routes (with documented exceptions); migrate existing raw-copy debt before enabling the repository-wide blocking mode.
- [ ] Add unit/integration tests for `translateUi`, fallback, interpolation, cookie/storage synchronization and locale persistence.
- [ ] Verify deep links, reload, tab changes and authenticated routes in all three apps with English and Hinglish selected.
- [ ] Verify UI language changes do not silently change learning language, assessment state, question ID, selected answer, result, source data or permissions.
- [ ] Build an automated coverage report with route/component, string-source type, catalog status, dynamic-content strategy, and tracked exception.
- [ ] Complete browser/device accessibility and language-switch acceptance checks before claiming completion.

## CI history and current validation status

Historical migration failures included duplicate `UiText` imports, stale admin navigation properties after navigation became key-based, outdated UI-contract assertions, TutorDock TypeScript errors, and malformed Admin retry-button JSX. These were corrected in subsequent commits. After the Admin Certificates, Questions and Assessments first-pass batches, the canonical catalogs contain **2,404 keys per locale**, with matching key sets and interpolation placeholders; CI parity checking confirms all six generated copies match the canonical files. The latest full localization-code validation is [Actions run #3021](https://github.com/Manav326/QuantaEdge/actions/runs/38996750050) for `66a2c3d0b0a9`; all eight jobs completed successfully: change detection, Android APK, API verification, web image, admin image, People image, content safeguards, and API image. Current `main` is newer due to content-pipeline checkpoint commits, so this is not a statement that every newer repository commit was rebuilt. Do not mark the full product production-complete: runtime dynamic-content translation, route-by-route coverage and browser acceptance remain outstanding.

## Latest session update — 2026-10-17

- Checked the repository head before documenting progress: `780adccfd3b8f98c36c84776d0f6fb5a32dc1641` (`refactor(i18n): centralize admin parent directory copy`). Work remains on `main`; no new localization branch was created.
- Verified canonical catalogs: **1,578 English keys and 1,578 Hinglish keys**, matching key sets, no blank values. All six app-local JSON copies are byte-for-byte equal to their corresponding canonical file.
- The web UI language, Admin selector, and People Operations selector use the shared normalized `qe-locale` cookie/storage convention. Student learning-content and certificate preferences remain separate.
- Latest Actions run: [#2981](https://github.com/Manav326/QuantaEdge/actions/runs/38993842189) for `780adccfd3b8f98c36c84776d0f6fb5a32dc1641` was `in_progress` when checked. Do not claim the full build is green until all required jobs finish successfully.
- Coverage implemented to date includes shared navigation/shell labels, public landing/login, parent portal, broad student dashboard/lesson/practice/test/textbook/profile/progress/certificate/class/account-lifecycle messages, shared account/session components, AI Tutor controls, and first-pass Admin dashboard/Language Review/student/parent-directory migrations. These are meaningful milestones, not proof every route is complete.
- Next: confirm the current Actions result; then continue route-by-route Admin and People Operations page-body migration. Audit raw UI literals and the dynamic-text translation service boundary before claiming end-to-end coverage.

## Resume instructions

On the next session: read this tracker first, inspect the latest main SHA and newest Actions run, finish the P0 CI verification, then proceed to P1 route-by-route coverage. Work directly on main only. Update this checklist after each meaningful change and record the relevant commit/CI run. Do not implement a second AI translation service until the existing learning-content translator and API contracts have been inspected.






## Latest session update — 2026-10-17 (after Admin build failure investigation)

- Resumed from the latest repository state instead of relying on the earlier chat checkpoint. Latest `main` baseline at this note: [ `0cbc2624be85` ](https://github.com/Manav326/QuantaEdge/commit/0cbc2624be85bcfb61d90da9a629097c606405b5). No new branch was created.
- Confirmed catalog integrity directly against the files on this `main`: **1,578 English keys, 1,578 Hinglish keys, identical key sets, 0 blank messages in either locale**. All six generated JSON copies across web/admin/People exactly match the two canonical catalog files.
- Inspected [run #2981](https://github.com/Manav326/QuantaEdge/actions/runs/38993842189). Android, API verification, content safeguards, web image and People image passed, but **Admin image build failed** due a JSX parse error in the dashboard retry button at `apps/admin/app/page.tsx:69`.
- Fixed the exact defect in [ `0cbc2624be85` ](https://github.com/Manav326/QuantaEdge/commit/0cbc2624be85bcfb61d90da9a629097c606405b5) by properly terminating the opening `<button>` tag before its localized label. This changes no dashboard data, access, or action behavior.
- Triggered follow-up [run #2992](https://github.com/Manav326/QuantaEdge/actions/runs/38994356579) for docs/localization progress on top of the Admin syntax fix. It remains `pending` in the Actions queue; the prior [run #2989](https://github.com/Manav326/QuantaEdge/actions/runs/38994235490) still had API verification running even though its Admin image job failed. Do **not** claim green until a non-cancelled run validates the fix and all required jobs finish successfully.
- Latest implementation scope already represented in this tracker includes centralized static interface keys and hooks, shared locale preference, shell/nav copy, landing/login, parent portal, broad student learning/practice/tests/textbooks/profile/progress/certificates/classes, TutorDock, student account lifecycle, legal/session messages, and early Admin dashboard/Language Review/student/parent directory work. Remaining high-priority scope remains the rest of Admin page bodies, all People page bodies, full route/string coverage guard, locale persistence/browser tests, and a deliberate design using the existing source-versioned review-gated translator for dynamic learning content—not an unreviewed duplicate AI service.
- Next: inspect #2991's result; fix any further errors it reports; then continue the Admin and People route-by-route migration. Update this tracker after each meaningful batch and record the exact commit and CI result.


## Latest continuation checkpoint — 2026-10-17

- Re-read the current branch before proceeding. Latest `main` head is [`980a7414883a5b3e63b5ba3b526ddc46425030f8`](https://github.com/Manav326/QuantaEdge/commit/980a7414883a5b3e63b5ba3b526ddc46425030f8) (`checkpoint(content): Layer 4-5 class7-en-science-93a8f46e`). The immediately preceding localization progress note is [`54d0f002a081`](https://github.com/Manav326/QuantaEdge/commit/54d0f002a0817806bfa0cb9a82eaf26cd3f9df95). The latest head is an Actions-generated content checkpoint after that note; treat source-localization evidence from the preceding note as still relevant, but re-check current files before each next patch.
- Independently validated the current canonical JSON files: **1,578 English keys and 1,578 Hinglish keys**, identical key sets, no empty messages, and no interpolation-placeholder mismatches. All six generated catalog JSON copies (English/Hinglish in web/admin/People) are byte-for-byte equal to the canonical catalogs. These are repository-file checks, not a substitute for the CI build.
- The shared sync validator is wired into `.github/workflows/ci.yml` as “Verify centralized UI localization catalogs and app copies”.
- CI is **not yet confirmed green**. [Run #2992](https://github.com/Manav326/QuantaEdge/actions/runs/38994356579) is still pending with no jobs started when inspected. [Run #2989](https://github.com/Manav326/QuantaEdge/actions/runs/38994235490) passed change detection, Android build, content safeguards, and web/People image builds, but its Admin image build failed; API verification was still in progress. The Admin JSX fix is in [`0cbc2624be85`](https://github.com/Manav326/QuantaEdge/commit/0cbc2624be85bcfb61d90da9a629097c606405b5), so a fresh non-cancelled full build must verify it.
- Current areas already migrated across this implementation include shared static catalogs/runtime; language selector/preferences in the three web apps; key-based student/parent/Admin/People navigation; public landing/auth; parent and broad student learning/testing/library/progress/profile/certificate/class flows; shared account/session components; AI Tutor controls; and first-pass Admin dashboard/Language Review/Student directory/Parent directory/Account Lifecycle labels.
- Still not complete: remaining Admin page-body strings, all People Operations page-body strings, a route-by-route coverage report and hardcoded-string guard, browser-level cross-app locale persistence tests, and the generalized server-side translation/cache boundary for runtime data not covered by the existing review-gated curriculum translation system.
- Next action: inspect the current Actions queue and continue one route group at a time. Keep the UI locale distinct from student learning language. Do not mark route bodies complete based only on the presence of a provider or shared catalog.


## Latest continuation — People Operations authentication and employee directory

- Added 29 semantic `people.auth.*` messages for HRMS sign-in and first-time password setup. Those pages now use `useUiTranslation` for headings, descriptions, labels, submit states and local validation/fallback messages; a shared `PeopleLanguageSwitcher` is available on both public auth screens before sign-in.
- Migrated the first employee-directory slice to semantic `people.directory.*` catalog keys: page header, metric descriptions, search controls, section tabs, common table headers, empty/history states and operation fallback messages. Employee identities, numbers, departments, API-returned records and permission checks remain dynamic data/logic.
- This patch adds 43 additional directory keys to the catalog and refreshes the six app-local JSON copies from canonical `localization/catalogs`. The synchronization script remains the source-of-truth check for future edits.
- CI must be checked on the newest commit; current branch verification was not yet all-green at the last check. Do not claim production readiness until a fresh non-cancelled run passes the localization parity check and all required builds.
- Still outstanding for People Operations: the rest of employee creation/edit dialog fields, access/role assignments, attendance/leave, payroll, workforce lifecycle, teaching assignments/classes, self-service, and deep dialogs/error/empty/loading states. This directory pass is not full route completion.


## Latest continuation — HRMS access governance localization

- Added 52 semantic `people.access.*` messages for role names/descriptions, access overview metrics, filters, credential setup/reset, permission errors, role-save/access-toggle feedback, and status text. The access-management page now uses the shared `useUiTranslation` hook for those surfaces and no longer keeps role labels/descriptions as a local English dictionary.
- Role codes, permission keys, reserved-account checks, API access enforcement, session revocation behavior and credential rules are unchanged. The initial-password minimum remains 12 characters; the client message is catalog-backed, while explicit server response messages remain visible so operational errors are not hidden.
- The canonical catalogs will contain 1702 keys per locale once this change is committed. The commit also refreshes all six app-local JSON copies and updates this tracker.
- Still outstanding in People Operations: deep copy in access dialogs, attendance/leave, payroll, workforce lifecycle, academic assignments/classes and self-service. The access route is a first migration pass, not full route certification.


## Latest continuation — Admin locale-formatting CI correction

- At reviewed `main` baseline `6ba9aebc2d4be30035062eb54bdf94d6a01271a2`, the shared catalogs contain 1,702 English keys and 1,702 Hinglish keys; the directory/access commits refresh all six app-local catalog copies in sync.
- Inspected [full build run #3005](https://github.com/Manav326/QuantaEdge/actions/runs/38994942266). Android, content safeguards, and the People image passed; the Admin image failed TypeScript at `apps/admin/app/translations/page.tsx:339` with `Cannot find name 'locale'`. Web image and API verification had not finished when inspected.
- Root cause: Language Review adopted `useUiTranslation()` for messages, but number formatting still referenced `locale` without reading it. This patch obtains `locale` from the existing `AdminWorkspaceContext`, preserving the shared source of truth and localized number formatting.
- A fresh non-cancelled full build must verify the correction. CI was not all green at the time of diagnosis; do not mark P0 complete until the Admin image and every required job pass.
- People Operations now has first migration passes for public sign-in/password setup, employee directory and access governance. Remaining People routes/forms and the wider Admin route audit remain in progress.


## Latest continuation — People teaching-classes page

- Added 30 semantic `people.teachingClasses.*` messages and moved the page’s header, schedule summary, loading/empty/error states, meeting-link actions and past-class table labels into the central catalog.
- The page formats scheduled dates using the selected UI locale (`en-IN` vs `hi-IN`) while keeping the `Asia/Kolkata` time zone, eligible assignment filtering, join-window check, private meeting-link API and copy/open actions unchanged.
- The catalog plus all six app-local copies are refreshed in this same commit; the tracker remains the resumable record. Verify the newest CI run before treating this route as build-certified.


## Latest continuation — People academic assignments

- Added 124 semantic `people.assignments.*` messages and migrated the academic-assignment screen’s period lifecycle, proposal form, approval/cancellation actions, history modal, coverage report and workload-policy interface. The page now uses the shared People translation hook rather than embedding these labels and workflow messages in JSX.
- Permission gates, independently approved assignment workflow, effective dates, period lifecycle, assignment/history IDs, private class-link integration behavior and workload warning calculations are unchanged. Backend-originated error text remains visible where it carries actionable server detail.
- All six app-local catalog JSON copies are refreshed in this commit. The central catalog has 1856 keys per locale after this batch. Confirm CI and `scripts/sync_ui_localization.py --check` before marking the route certified.
- Remaining People Operations routes: attendance/leave, payroll, workforce lifecycle, self-service, plus remaining field-level/access-dialog strings in employee directory/access. This migration is not a claim of total site coverage.


## Latest continuation — People Payroll localization

- Added 41 semantic `people.payroll.*` messages and moved Payroll’s permission/fallback notices, page header/tabs, salary-component information, effective-dated compensation, payroll-period controls, snapshot review, independent approval and payment-reconciliation interface into the shared catalog.
- This pass changes display copy only: salary values/currencies, effective dates, immutable snapshot data, approval gates, run statuses, provider references and payment reconciliation calls remain unchanged. Raw server-originated error details remain visible when the API supplies them.
- The canonical catalogs and all six app-local JSON copies are synchronized in this commit. CI is the authority for type/build validation; do not call the overall build green until a fresh non-cancelled run passes.
- Remaining People Operations routes include attendance/leave, workforce lifecycle and self-service; deep field-level copy in access and employee forms also remains to be reviewed.


## Latest continuation — People Attendance & Leave localization

- Added 168 semantic `people.attendance.*` messages for attendance recording/corrections, leave workflows, balance ledger/reversals, lifecycle approval rules, working-week configuration and government-sourced holiday calendar management. Major section labels, controls, validation/fallback notices and empty states are catalog-backed.
- Permission checks, no-self-approval rules, immutable correction history, leave-balance ledger behavior, working-day calculations and official holiday source-attestation rules remain unchanged. Dynamic employee records, balances and API statuses remain data-driven.
- All six generated app-local catalogs are updated from canonical `localization/catalogs` in this commit; inspect the newest CI run before marking route certification complete.


## CI follow-up — teaching-class interpolation type fix

- Build run [#3017](https://github.com/Manav326/QuantaEdge/actions/runs/38995880441) passed Android, Admin, API, web and content safeguards, but the People Operations image failed TypeScript at `app/my-classes/page.tsx(23,606)` and `(23,615)`: the `countSummary` translator received `LiveClass[]` for `{upcoming}`/`{past}` where `string | number` is required.
- Fixed the call to pass `upcoming.length` and `past.length`. This only affects summary formatting; the underlying filtered class arrays and eligibility/join rules are unchanged.
- The Attendance & Leave commit also refreshes catalog copies to 2,065 entries per locale, and a new full run must still validate that change. Do not mark the build green until the fix’s own non-cancelled run passes all required jobs.


## Latest continuation — central localization status refreshed (2026-10-17)

- Reviewed the current repository head: [`7c57daccaa2a4f6c6cf8e2cf76cc3b5a6d13d458`](https://github.com/Manav326/QuantaEdge/commit/7c57daccaa2a4f6c6cf8e2cf76cc3b5a6d13d458). The newer commits after the last localization change are textbook visual/content checkpoint commits; do not attribute those commits to localization work.
- Independently counted the canonical message dictionaries on current `main`: **2,217 English keys and 2,217 Hinglish keys**. The sets match exactly; both have zero blank entries and no interpolation-placeholder mismatches. All six app-generated catalog copies are byte-for-byte identical to the canonical files.
- Latest full CI run for the localization-code state is [#3021](https://github.com/Manav326/QuantaEdge/actions/runs/38996750050), on [commit `66a2c3d0b0a9b9685210b199b536cbdb145f30f3`](https://github.com/Manav326/QuantaEdge/commit/66a2c3d0b0a9b9685210b199b536cbdb145f30f3). All eight required jobs passed: change detection, Android APK, API verification, web image, admin image, People Operations image, content safeguards, and API image. This confirms the localization changes up to that commit; do not imply later content-pipeline checkpoint commits were rebuilt at the exact same SHA.
- The People Operations first-pass coverage now includes directory, access governance, academic assignments/classes, payroll, attendance/leave, workforce lifecycle and employee self-service, in addition to sign-in/password setup. Remaining route/dialog field-level copy still needs a coverage audit and acceptance pass.
- Current implementation is **not complete end-to-end**. The centralized UI catalog and cross-app locale preference bridge exist, but an automated hardcoded-string coverage inventory, all remaining admin/People/student deep-route strings, browser-level locale-persistence tests, and the dedicated server-side AI translation/cache boundary for eligible dynamic content remain open. Reuse the existing versioned, context-aware, review-gated curriculum translator rather than introducing a duplicate service.
- Resume from current `main` and this tracker. Next work: inventory remaining app routes/components, fix any CI regression immediately, continue safe message extraction, and update this tracker and catalog parity proof after each batch.


## Latest continuation — machine-readable UI route inventory (2026-10-17)

- Baseline reviewed before this continuation: [`8ba4f10c6f60c05da77719039d3a7700822fefae`](https://github.com/Manav326/QuantaEdge/commit/8ba4f10c6f60c05da77719039d3a7700822fefae). This was a content-pipeline checkpoint; no existing UI localization implementation was overwritten.
- Added `scripts/inventory_ui_routes.mjs` to enumerate Next.js App Router `page.*` screens and `route.*` handlers under `apps/web/app`, `apps/admin/app`, and `apps/people/app`. The inventory preserves route-group and parallel-slot metadata, dynamic route segments, intercepting-route metadata and route-file paths; it skips Next.js private folders.
- Added `scripts/inventory_ui_routes.test.mjs`. Local validation passed **9/9 tests**, including a temporary fixture-tree scan spanning all three application roots, route groups, dynamic routes, parallel slots, intercepting routes, private folders and route handlers.
- Updated `.github/workflows/ci.yml` to run those tests, generate JSON at `$RUNNER_TEMP/ui-localization-route-inventory.json`, and upload a `ui-localization-route-inventory` artifact for 14 days on each CI run.
- The report explicitly says inventory is not translation certification. It also records primary-route duplicates for follow-up rather than automatically labelling any page as translated.
- CI run [#3025](https://github.com/Manav326/QuantaEdge/actions/runs/39001656781) completed successfully: 9/9 route-inventory tests passed, the report generated 50 page routes and its artifact uploaded. Next implementation step is to use that inventory to audit route-by-route message coverage; continue reusing the existing translation runtime and do not introduce a duplicate AI translation service.

## Latest continuation — Admin Certificates first-pass localization (2026-10-17)

- Added the semantic `admin.certificates.*` namespace and migrated the Admin `/certificates` page headings, filters, table labels, empty/loading states, action labels, revocation validation/success feedback, audit states and pagination. Dynamic API error messages, certificate/student identifiers, audit details, authorization redirects, filter values, endpoint contracts, and unknown server status values remain data-driven and unchanged.
- Certificate issue timestamps and audit-event times now use the shared Admin UI locale (`en-IN` for English and `hi-IN` for Hinglish). The certificate-language filter remains independent of UI and learning-content language.
- Added `scripts/check-admin-certificates-i18n.mjs` to check central-hook use, presence of every used semantic key in both catalogs, matching placeholders and removal of representative embedded English UI literals. CI invokes this guard in the localization validation job.
- Added `docs/engineering/ui-localization-route-coverage.md` from the CI inventory. It records all 50 actual UI routes and their evidence level; first-pass migration is not route certification.
- This commit adds 57 semantic messages. The build and route guard results must be read from the Actions run triggered by this commit; the previously successful localization-code build does not validate these new changes.

## Latest continuation — Admin Questions review queue localization (2026-10-17)

- Confirmed the preceding Admin Certificates slice passed [full build run #3027](https://github.com/Manav326/QuantaEdge/actions/runs/39002075318) on commit [`f5ab6244ab323f3ff94a5494e266620ee4840038`](https://github.com/Manav326/QuantaEdge/commit/f5ab6244ab323f3ff94a5494e266620ee4840038): all eight required jobs passed (change detection, Android APK, API verification, web/admin/People image builds, content safeguards and API image).
- Added 87 semantic `admin.questions.*` messages and migrated the Admin `/questions` page review dashboard, status labels, filters, search, permission guidance, review prompts, empty/loading states, review actions, notices, audit snapshots and pagination to the central catalogs.
- Canonical English and Hinglish catalogs now contain **2,361 keys each**. All six generated catalog JSON copies were refreshed from those canonical files, and the central synchronization check remains the source-of-truth parity gate.
- Added `scripts/check-admin-questions-i18n.mjs` and wired it into the existing CI localization job. This checks the route's central hook, each used key in both locales, placeholder parity and removal of representative hardcoded interface copy.
- Review permissions, lesson-state prerequisites, status enums submitted to the API, endpoints, question/source content, reviewer-entered notes and audit history structures are preserved. UI number/date display follows the Admin locale; server-provided diagnostic details continue to be surfaced when present.
- The full build triggered by this Questions commit must pass before this slice is considered build-verified. Continue route-by-route acceptance and the remaining Admin migration; do not call the product fully localized.

## Latest continuation — Admin Assessments authoring and queue first pass (2026-10-17)

- Added 43 semantic `admin.assessments.*` messages and migrated the primary Admin `/assessments` authoring and teacher-review-queue headings, curriculum/approved-question-bank guidance, test-detail labels, catalogue guidance, queue descriptions, loading/empty states and relevant input labels to the shared UI translation hook.
- Synced the canonical English/Hinglish catalogs and all six application catalog copies; the canonical count is **2,404 keys per locale**. Placeholder sets were checked for all existing and newly added `admin.assessments.*` entries, and the route uses only catalog-backed keys from this namespace.
- Added `scripts/check-admin-assessments-i18n.mjs` to CI to enforce central-hook usage, presence of used keys in both locales, interpolation parity and removal of representative embedded strings.
- This is a **first pass**, not full certification. Remaining in the same route are error/fallback messages, action confirmations, dynamic status labels and the detailed manual-grading/certificate audit dialog. Existing API calls, frozen attempt snapshots, status payloads, grader authorization, marks bounds, offline-delivery policy and result-release rules were not changed.
- Baseline full build [run #3038](https://github.com/Manav326/QuantaEdge/actions/runs/39002946679) passed on the preceding `main` commit `b580cbecd10210dff0816fd6be190eb00bd68372`. The new Assessment migration requires its own non-cancelled run before this slice is marked build-verified.


## Latest continuation — Admin Assessments grading and certificate review dialog (2026-10-17)

- Extended the deeper `/assessments` migration into action validation/errors, create/status/offline-policy/grader feedback, dynamic test status labels, manual grading, saved-answer/reference-answer labels, certificate lookup/download states, audit text and modal actions.
- Added 82 semantic `admin.assessments.*` messages. Canonical catalogs now contain **2486 keys per locale** with identical key sets and matching interpolation placeholders; the six app-local JSON copies are synchronized from the canonical catalog bytes.
- Submission and audit timestamps now format with the selected Admin UI locale. API routes, server status payloads, grader permissions, answer data, mark validation, certificate eligibility and result-release decisions remain unchanged.
- The CI route guard now recognizes both `t(...)` and `tRef.current(...)`, and adds regression checks for review-dialog text.
- Source and catalog validation passed before commit. This slice awaits its own CI run; browser acceptance for both locales remains open. Remaining work on this route includes some list-row metadata/actions and queue/filter labels.


## Latest continuation — Admin Assessments catalogue and review-queue labels (2026-10-17)

- Completed the next /assessments UI-copy pass for authoring tabs, curriculum selector options, approved-question selection summary, catalogue filters and metadata, offline-delivery labels, grader-assignment controls, queue filters/search, submitted-attempt summaries, and review/open-record actions.
- Added 44 semantic admin.assessments messages. Canonical catalogs now have **2530 keys per locale**, with identical key sets and matching interpolation placeholders; all six app-local JSON copies are synchronized from the canonical sources.
- Backend APIs, status payloads, grader permissions, attempt snapshots, mark validation, certificate rules, and result-release decisions remain unchanged.
- Expanded the CI literal guard to cover representative tabs, filters, grader assignment, queue search, and review-dialog strings. The current code commit's build and browser acceptance remain pending.


## Build blocker repair — Learning resource API compilation (2026-10-17)

- CI run [#3042](https://github.com/Manav326/QuantaEdge/actions/runs/39005066760) exposed a Java compile failure in `LearningResourceController.java`, inherited from the provider-neutral learning-resource catalogue changes; the failure was unrelated to the UI-localization commits.
- Corrected three INSERT...RETURNING id calls to use `JdbcTemplate.queryForObject` (the scalar `Long` result is required rather than the `List` returned by `query`) and gave the recording/document subject and chapter pattern variables distinct names.
- Request bodies, SQL, authorization and scope validation rules were otherwise left unchanged. This is a compile-fix only; the next full workflow must verify the fix before build status is marked green.


## Admin Classes — scheduling-form first pass (2026-10-17)

- Began `/classes` localization by centralizing the page/hero, class and subject filters, status filter, live-session tabs, scheduling-form labels and guidance, and key create-session validation/error fallbacks.
- Added 60 semantic `admin.classes.*` messages. Canonical catalogs contain **2590 keys each** with matching key sets and interpolation placeholders; six application catalog copies are set to the canonical catalog bytes.
- Added `scripts/check-admin-classes-i18n.mjs` and wired it into CI. This is explicitly a first pass; session-card actions/statuses, provider-operation notices, recording-management details and browser acceptance remain open.

## Build blocker repair — Admin Classes syntax regression (2026-10-17)

- Full Actions run [#3048](https://github.com/Manav326/QuantaEdge/actions/runs/39006837008) passed all eight required jobs on commit [`dd95053`](https://github.com/Manav326/QuantaEdge/commit/dd95053e85d57b82b6ff1d3b29ca2581c79016ed): localization/route guards, Android APK, Admin image, Web image, People Operations image, content import safeguards, API verification, and API image.
- The Admin image failure on the previous Classes form commit was an unterminated string caused by one extra quote in the secure-meeting validation fallback. The fix changes only TSX syntax and leaves validation/API behavior unchanged.
- The next Classes localization batch below has not yet been build-certified; its own CI result must pass before the route is treated as verified.

## Latest continuation — Admin Classes session operations and recording lifecycle (2026-10-17)

- Extended `/classes` central-catalog coverage from the scheduling form to join-window controls, provider-operation and private-link notices, session status/actions, teacher replacement/rescheduling, recording upload guidance, empty/loading states, and publish/archive/restore actions.
- Added **118 semantic `admin.classes.*` messages**. Canonical English and Hinglish catalogs now have **2,708 keys each** with matching key sets and interpolation placeholders; all six generated app catalog copies reuse the canonical bytes.
- Known session, recording and Google integration statuses now display localized labels while preserving the underlying API status codes. Date/time display uses the selected UI locale (`en-IN` or `hi-IN`) with `Asia/Kolkata`; business date parsing and API payloads are unchanged.
- Existing endpoint calls, authorization, assignment eligibility, meeting-link privacy, upload idempotency, recording state transitions, raw server diagnostics, user-generated titles/descriptions and identifiers remain data-driven and unchanged.
- Expanded `scripts/check-admin-classes-i18n.mjs` to check both catalog key/placeholder coverage and representative session/recording hardcoded UI literals. Pre-push validation confirmed all **170 unique directly referenced page translation keys** resolve in both catalogs, with zero catalog placeholder mismatches and zero checked literal remnants.
- Updated `docs/engineering/ui-localization-route-coverage.md`. This remains a first migration pass, not full route acceptance: browser/device checks and remaining accessibility/deep-state audits are still open. The new batch awaits its own CI run.


## Non-negotiable requirement added — catalog-only UI language and static copy

- Required outcome: no page component may own a user-visible static sentence, inline language pair such as `tx('Hinglish','English')`, locale-specific ternary, duplicated page dictionary, or hardcoded accessibility/helper sentence. Every static label, action, notice, form hint, validation message, dialog, empty/loading/error state and accessibility name must resolve from central semantic keys.
- Adding another UI language must not require edits to page components, feature components, or route files. A locale catalog and one entry in `localization/locales.json` define its registered ID, display/native name, HTML language tag, legacy aliases and fallback. The sync script generates the static import registry and keeps web/admin/People Operations copies in sync.
- Legacy stored values `hindi` and `hi` continue resolving to Hinglish to avoid changing existing user preferences. Future Pure Hindi must use a distinct registered ID (recommended `pure-hindi`) so this compatibility alias cannot silently switch existing users' language.
- Language selectors display available language names only; no visible pre-login explanatory phrase such as “Interface language” or long helper description. Crisp notices are the default; actual curriculum text, user-generated data, identifiers and meaningful server diagnostics are not rewritten as short UI copy.
- Completion is not inferred from the registry alone. Route-by-route literal migration, short-copy review, a blocking static-copy guard, catalog checks, CI builds and real-browser acceptance remain separate tracker items.

## Latest implementation checkpoint — locale registry and selector foundation

- Inspected `main` before patch: `066727ad8f091aa278d1f30f166d221c3e7fd5f1`. The preceding Admin Classes localization validation is green on [Actions run #3051](https://github.com/Manav326/QuantaEdge/actions/runs/39007559294) (eight required jobs succeeded for `53d01006ce13`).
- Introduced registry-driven locale metadata, generated TypeScript imports, canonical locale normalization, legacy aliases, display/native labels, HTML language tags and catalog fallback. The current registered locales remain English and Hinglish; no incomplete Pure Hindi catalog is being advertised.
- Replaced hardcoded two-option language lists in Student/Parent Web, Admin and People Operations selectors with the shared generated options. Removed visible explanatory/help copy from those selectors while keeping accessible names. Existing learning-language and certificate-language settings remain independent.
- Updated this tracker and route matrix to distinguish registry readiness from still-incomplete inline-copy and route acceptance work.
- This patch is not marked CI-verified until its own new Actions run completes successfully.


## Latest implementation checkpoint — Parent profile/child management and Student login copy extraction

- Added central semantic keys for Parent child creation, profile updates, credential saving, course/track actions, restore/archive confirmations, profile-photo feedback and Student login validation/placeholders.
- Migrated the inline two-language `tx()` helpers and calls from `/parent/children`, `/parent/profile` and `/login/student` to `useUiTranslation()`. The long mixed-language child-creation sentence is now two short clauses. Parameterized messages preserve student/course names.
- Fixed the Parent profile’s legacy `setLocale('hindi')` call to the registered `hinglish` ID while retaining old stored `hindi`/ `hi` aliases in the provider/registry. Learning-language preference remains separate from UI language.
- Added `scripts/check-web-static-copy.mjs` and wired it into CI to prevent inline `tx()` regressions on these three migrated routes and to verify the new semantic keys in both catalogs. This guard is intentionally scoped; it is not evidence that every web route is clean yet.
- The changed routes remain **first-pass, audit pending** for residual literal JSX labels, native/browser messages, accessibility text, and server-error fallback strings. This migration is not complete until its own CI build passes and the remaining route audit is green.


## Type-check follow-up — translation hook call shape

- Corrected migrated components to bind `const t = useUiTranslation()`; this hook returns the translator callback directly (it does not return an object with a `t` property). The route-copy extraction is still pending the current full Actions result.


## Parent login route — centralized static copy

- Migrated `/login` Parent authentication, registration, OTP/reset validation, in-progress/success notices, action labels and field placeholders into semantic catalog keys; removed the local `tx(Hinglish, English)` helper.
- Client-owned malformed-response text now uses a catalog key with a matching `{status}` placeholder; API-supplied diagnostic messages remain dynamic and are not rewritten.
- Updated the shared web `useUiTranslation` hook to pass the registered locale ID unchanged into the runtime, instead of coercing every non-English locale to Hinglish. Added a guard against reintroducing that forced mapping on guarded auth routes.
- Route matrix: Parent login has first-pass catalog extraction; complete literal JSX/accessibility audit and browser acceptance remain open. Registry/future-locale extension is not finished until remaining locale-specific branches and types are addressed.


## Locale pass-through and TutorDock static-error extraction — 2026-10-17

- Removed forced English/Hinglish mapping from Student progress, diagnostic, profile, classes, practice, certificates, structured lessons, textbook library, lesson visuals, test runner/results, shared workspace navigation/pagination and AI Tutor message UI. Shared translation calls now pass the registered locale ID directly to the central runtime.
- TutorDock error helpers now accept the registered locale and resolve fallback messages through central catalog keys; its remaining English/Hinglish error pairs were removed with concise copy. Added nine paired `tutor.error.*` entries to the canonical catalogs and all six generated copies.
- Generalized Admin Classes and Assessments date labels and People class times to use the locale registry’s language tag. Admin navigation’s locale type now reuses the central `UiLocale` type. Learning language and lesson/test data remain independent of UI locale.
- Extended the static-copy guard to scan all Web, Admin and People Operations TypeScript/JavaScript source for the known forced English/Hinglish translation mapping and validate the new TutorDock keys.
- This is a locale-extensibility pass—not the final raw-string migration. Static sentences, labels and some locale-specific branches remain across route components; route acceptance and browser checks remain open. CI must pass on this commit before this batch is build-certified.


## Shared account-menu copy extraction — 2026-10-17

- Replaced hardcoded Web landing/Parent/Student account-menu accessibility names and logout text with shared semantic catalog keys. Parent/student menu labels retain the profile name through the `{name}` placeholder.
- Added nine matching English/Hinglish messages and synchronized catalog content for all three independent Next.js app builds. Catalog key and placeholder checks remain part of CI.
- Pagination and remaining workspace labels are the next shared-component slice. This does not certify all route copy or remove remaining static sentences elsewhere.


## Shared account-menu and pagination copy extraction — 2026-10-17

- Centralized shared Web account-menu accessibility names and logout labels. Parent/student menu triggers preserve the profile name through the `{name}` placeholder.
- Centralized Admin and People Operations pagination copy: range counts, no-item states, page count, rows-per-page, previous/next actions and accessibility labels.
- Removed hardcoded Admin/People workspace navigation accessibility labels. Extended CI to check these raw shared strings do not return and to validate the catalog keys/placeholders in both languages.
- Other route-level static sentences remain open. This batch does not certify that all page bodies are free of embedded copy.


## Student Learn coverage labels and Admin structured-preview copy — 2026-10-17

- Replaced the remaining `tx(Hinglish, English)` helper in Student Learn for missing lesson-block/question counts with interpolated catalog messages; removed a redundant language-pair wrapper around question-type labels.
- Migrated Admin structured lesson preview fallback feedback, publication gate warnings, question progress/instructions, question navigation and validation errors to the central UI translator. The UI now follows the Admin UI locale; the lesson's selected content locale still controls lesson content, accepted answers and feedback from the lesson data.
- Simplified long static preview instructions while preserving the publication gate and the warning that author-only grading keys must be sanitized before student display.
- Added guard coverage for these two inline-pair helper patterns plus 20 new paired catalog keys. Route audits and full browser acceptance remain open pending CI and manual testing.


## Pre-login language and authentication copy — Admin and People Operations

- Admin login helper sentences now use central catalog keys with concise wording. Client-owned malformed API response messages are catalog-backed with interpolated HTTP status.
- Admin's pre-login language selector now renders options from the central registry and shows language names only—no visible caption or fixed English/Hinglish list.
- People Operations login's username placeholder is catalog-backed. Password-only HRMS authentication and OTP-based educational staff authentication remain unchanged.
- Added CI regressions for removed literal helper sentences, fixed Admin language options and the People login placeholder; new catalog keys and placeholder parity are checked in both locales.
- Both routes remain first-pass: inspect remaining embedded copy, responsive/browser behavior and all error states before marking route audit complete.


## Admin Employees/Governance — first-pass catalog extraction

- Extracted static UI copy for staff account creation, permission editing, role choices, suspend/reactivate flows, staff and audit tabs, loading/empty/error states, security guidance, search fields and audit event details into semantic catalog keys.
- Known role codes now map to translated catalog labels. Permission names/descriptions and API-supplied error details remain dynamic records instead of duplicating source data as UI copy.
- Request failures use a catalog fallback with an HTTP status placeholder; audit dates follow the registered UI locale tag.
- Added CI raw-copy regression checks for representative prior literals and paired catalog messages across generated Web/Admin/People copies.
- Route remains first-pass until current CI passes and browser checks cover staff creation, role permissions, suspend/reactivate and read-only audit access.


## Admin Content Studio — lifecycle feedback, first batch

- Centralized confirmation, saved-preview version, draft creation, curriculum/textbook verification, chapter/micro-topic publication and review-submission feedback.
- Revision numbers and content type use placeholders; dynamic API diagnostics remain preserved.
- Added paired catalog keys and CI regression checks. More feedback, validation prompts, block/question editing and preview labels remain open.
