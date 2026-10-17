# Centralized Translation Implementation Tracker

**Purpose:** Resumable implementation record for centralized QuantaEdge web UI localization and context-aware learning-content translation. Update this file on every meaningful implementation milestone. Continue from the latest main revision and this tracker; do not restart completed work or create another branch.

**Last reviewed:** 2026-10-17. Current reviewed `main` baseline: `780adccfd3b8f98c36c84776d0f6fb5a32dc1641`. [Baseline commit](https://github.com/Manav326/QuantaEdge/commit/780adccfd3b8f98c36c84776d0f6fb5a32dc1641).

**Overall status: IN PROGRESS — NOT PRODUCTION-COMPLETE.** A shared static UI catalog, shared translation APIs/hooks, cross-app language preference bridge, and extensive first-wave route migrations are implemented. The complete admin/People page-body migration, comprehensive route coverage proof, and a central server-side AI translation service for arbitrary runtime dynamic content are not yet complete. Do not describe the entire site as fully translated until the remaining checklists and CI gates are green.

## Current baseline and evidence

- Canonical English catalog: `localization/catalogs/english.json`.
- Canonical Hinglish catalog: `localization/catalogs/hinglish.json`.
- Shared runtime and synchronization contract: `localization/runtime.ts`, `scripts/sync_ui_localization.py`.
- Generated copies are stored under each independent Next.js Docker context: `apps/{web,admin,people}/app/i18n/catalogs/{english,hinglish}.json` and app-local `catalog.ts` files.
- At the current baseline (`780adccfd3b8f98c36c84776d0f6fb5a32dc1641`), the catalogs have **1,578 keys each**, identical key sets, no empty string values, and all six generated JSON copies match the canonical catalogs. This was verified against repository files during the 2026-10-17 session; it is a source-level check, not a successful build claim. Re-run `python3 scripts/sync_ui_localization.py --check` after any change; never hand-edit only one generated copy.
- CI invokes the synchronization/parity validator in `.github/workflows/ci.yml`. The check validates catalog parity, placeholder sets, and generated copies.
- Static interface locale and learning-content locale are separate preferences. The web app keeps the existing `qe-learning-language` and `qe-certificate-language` behavior. The shared UI choice uses `qe-locale`, with `english` and `hinglish` as normalized cross-app values and backward compatibility for the older `hindi` value.
- Hinglish means Hindi in Devanagari with commonly used English/Roman-script terms retained; it does not mean phonetic Hindi written in Roman letters.

## Architecture implemented

- Central key/value resources for `english` and `hinglish`, with a runtime API (`translateUi`, `normalizeUiLocale`, `hasUiTranslation`) and typed message-key support.
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
| People Operations page bodies | Not complete | Shell/navigation/selector migrated; individual pages still need centralized interface strings. |
| Dynamic server/API/database content | Not complete as a generalized runtime service | QuantaEdge already has contextual, source-versioned, review-gated curriculum translation tooling documented below. The new UI catalog does not translate arbitrary database/API text, and no frontend should call an AI provider directly. |
| Coverage inventory and anti-regression guard | In progress | CI verifies catalog and app-copy parity. A complete page/component string-coverage check and exception register remain to be built. |
| Production build validation | Pending after last fixes | See CI state below; do not mark green until the follow-up run on the latest commit has finished successfully. |

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
- [ ] Confirm the GitHub Actions build on the fix commit is green: web, admin, People, API, content safeguards, Android, and localization parity. Latest link: [QuantaEdge Actions](https://github.com/Manav326/QuantaEdge/actions).

### P1 — Complete route and static-string coverage

- [ ] Build a complete machine-readable route inventory from the Next.js app trees for web, admin and People Operations.
- [x] Migrate admin login and overview/dashboard first pass, plus the Language Review workflow page. Validate all with CI before considering these routes complete.
- [x] Migrate first pass of Student directory, Account Lifecycle and Parent directory copy into the central catalog; verify these routes in browser after CI is green.
- [ ] Migrate remaining admin page body text: Content Studio, questions, resources, source ingestion, assessments/tests/grading, live classes, staff, certificates, structured lesson preview, session and all dialogs/forms/tables.
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

- [ ] CI check fails for catalog key/placeholder drift, missing generated copies, and newly introduced hardcoded interface literals in covered routes (with documented exceptions).
- [ ] Add unit/integration tests for `translateUi`, fallback, interpolation, cookie/storage synchronization and locale persistence.
- [ ] Verify deep links, reload, tab changes and authenticated routes in all three apps with English and Hinglish selected.
- [ ] Verify UI language changes do not silently change learning language, assessment state, question ID, selected answer, result, source data or permissions.
- [ ] Build an automated coverage report with route/component, string-source type, catalog status, dynamic-content strategy, and tracked exception.
- [ ] Complete browser/device accessibility and language-switch acceptance checks before claiming completion.

## CI history and current validation status

Recent runs exposed integration issues during the active migration. The earlier failures included duplicate `UiText` imports, a stale admin navigation property access after navigation items became key-based, and a People UI contract asserting the old inline label rather than the new catalog key; those were fixed in commit `07e72efdd2c99524b985b6a6886504bb36cc9498`. Run #2962 then surfaced four TutorDock TypeScript errors from incorrectly invoking the standalone translator as a property of `languageRef.current` / `current`; this was fixed in `fd5f1f725c6a25e875966e355edd7fdd29339298`. The follow-up run #2967 for admin login at `7cd22a47a9457738a7c7dc8672f8010e81c89220` was still running when the next changes were made. The current Language Review page is at `d188ac4fc09383b24514e45d1bf2ed26f5ab155a`; its build run #2970 was queued at last check. Catalog validation during the current session shows **1,578 keys per locale**, identical key sets, zero blank messages, and all six generated catalog copies byte-identical. Latest known full CI run is [#2981 on `780adccfd3`](https://github.com/Manav326/QuantaEdge/actions/runs/38993842189), which was still `in_progress` at the latest status check; only the initial `Checkout` steps for the first jobs had been observed, with no failure reported yet. Treat it as **pending**, not green, until all required jobs finish successfully. This is a source-level parity check; the GitHub workflow must still pass before P0 is complete. Do not interpret cancelled superseded runs as successful validation.

## Latest session update — 2026-10-17

- Checked the repository head before documenting progress: `780adccfd3b8f98c36c84776d0f6fb5a32dc1641` (`refactor(i18n): centralize admin parent directory copy`). Work remains on `main`; no new localization branch was created.
- Verified canonical catalogs: **1,578 English keys and 1,578 Hinglish keys**, matching key sets, no blank values. All six app-local JSON copies are byte-for-byte equal to their corresponding canonical file.
- The web UI language, Admin selector, and People Operations selector use the shared normalized `qe-locale` cookie/storage convention. Student learning-content and certificate preferences remain separate.
- Latest Actions run: [#2981](https://github.com/Manav326/QuantaEdge/actions/runs/38993842189) for `780adccfd3b8f98c36c84776d0f6fb5a32dc1641` was `in_progress` when checked. Do not claim the full build is green until all required jobs finish successfully.
- Coverage implemented to date includes shared navigation/shell labels, public landing/login, parent portal, broad student dashboard/lesson/practice/test/textbook/profile/progress/certificate/class/account-lifecycle messages, shared account/session components, AI Tutor controls, and first-pass Admin dashboard/Language Review/student/parent-directory migrations. These are meaningful milestones, not proof every route is complete.
- Next: confirm the current Actions result; then continue route-by-route Admin and People Operations page-body migration. Audit raw UI literals and the dynamic-text translation service boundary before claiming end-to-end coverage.

## Resume instructions

On the next session: read this tracker first, inspect the latest main SHA and newest Actions run, finish the P0 CI verification, then proceed to P1 route-by-route coverage. Work directly on main only. Update this checklist after each meaningful change and record the relevant commit/CI run. Do not implement a second AI translation service until the existing learning-content translator and API contracts have been inspected.




