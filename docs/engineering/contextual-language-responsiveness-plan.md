# Contextual Language Responsiveness — Implementation Plan

**Status:** In progress  
**Branch:** `feature/contextual-language-responsiveness`  
**Scope:** Learner/parent Web, Institution Admin, People Operations (HRMS), Android, and database-backed learning content.

## Product rule

Translations must express the intended meaning in the screen's context, not translate isolated words. Copy must be crisp, simple, and immediately clear. Prefer one short sentence; avoid redundant helper text. Do not shorten legal, safety, assessment, payment, or consent text if doing so changes meaning.

Hinglish is Hindi in Devanagari with familiar English terms retained naturally. Do not use phonetic Roman Hindi. English and Hinglish are the supported UI locales for this implementation. Keep locale architecture registry-driven so Pure Hindi can be added deliberately later; never advertise an unavailable locale.

## Architecture boundaries

- **Interface locale:** navigation, labels, forms, notices, errors, accessibility text and workflow statuses.
- **Learning-content locale:** lessons, blocks, questions, options, explanations, activities, feedback and media descriptions.
- **Certificate locale:** certificate rendering/metadata.
- Changing one preference must not unexpectedly overwrite another.
- Keep IDs, answer keys, grading, permissions, progress, payments and source content independent of translated labels.
- Use the existing centralized Web/Admin/People catalogs and synchronization tooling as the browser source of truth. Avoid new handwritten duplicate catalogs.
- Keep the content translator server-side and its review/approval/publication workflow intact. Do not translate on every render or silently serve stale/other-language content.

## Phases and acceptance gates

### P0 — Locale contract and preference consistency
- [x] Register only supported UI locales and keep selectors aligned with runtime capabilities.
- [x] Align Android's account interface-language options and save behavior with the app's active UI locale.
- [ ] Document preference precedence and persistence for browser apps, Android, login/logout and offline startup.
- [ ] Verify changing UI locale never changes the learning-content or certificate locale.
- **Accept:** selected, saved and displayed locale agree across app navigation and reload; unsupported choices cannot be saved or offered.

### P0 — Complete visible-copy inventory
- [ ] Inventory every page, nested route, shared component, dialog, toast, validation message, error, loading/empty/success state and accessibility label.
- [ ] Track each surface as not reviewed, migrated, contextual-reviewed, runtime-tested or accepted.
- [ ] Include all Android Compose destinations and common shell components.
- **Accept:** 100% of inventoried surfaces have evidence and no route is certified by heading-only inspection.

### P0 — Single translation path
- [ ] Replace remaining inline locale pairs, duplicated dictionaries and hardcoded binary-locale helpers progressively with typed catalog keys.
- [ ] Keep the shared registry/metadata, catalogs and generated app copies synchronized.
- [ ] Replace legacy-generated key names with semantic names in small, tested batches.
- [ ] Connect Android to a single generated/validated locale source or a documented compatibility layer; do not add a second manually maintained catalog.
- **Accept:** catalog drift, missing keys and invalid locale IDs fail CI; a new locale cannot silently render as Hinglish.

### P1 — Contextual, concise writing
- [ ] Review existing copy in context, starting with authentication, course access, payments/refunds, permissions, assessments and publishing.
- [ ] Apply a domain glossary and audience-specific terminology to learner/parent, Admin and HRMS flows.
- [ ] Prefer action labels of 1–3 words and helper text of about 8–10 words or fewer when clarity permits.
- [ ] Localize a complete message, not independently translated fragments; preserve placeholders and grammatical agreement.
- [ ] Human-review ambiguous, educational, legal, security, payment and consent copy.
- **Accept:** English and Hinglish preserve the same intent, next action and constraints; no awkward word-for-word translation in the accepted sample set.

### P1 — Dynamic and learning content
- [ ] Map server/API error codes to known localized messages; keep detailed diagnostics in logs.
- [ ] Localize dynamic statuses, counts, dates, notification text and fallback states.
- [ ] Audit learning-content translation coverage per active course/class/subject, entity type and locale.
- [ ] Block or clearly report missing/stale translations instead of silently mixing languages.
- [ ] Keep translation approval/publishing separate from draft generation.
- **Accept:** all required published content has a current approved translation; business logic and answer grading do not change with locale.

### P1 — State-preserving runtime and device tests
- [ ] Verify locale persistence across navigation, refresh, login/logout, session recovery and offline startup.
- [ ] Switching language preserves typed input, selected/matched answers, active question, submission state, score, progress and reading position.
- [ ] Test browser desktop/mobile plus Android portrait/landscape, including Devanagari text, math, tables, dialogs and accessibility labels.
- **Accept:** critical workflows pass both locales without clipping, language leakage or locale-induced functional regressions.

### P1 — CI and release evidence
- [ ] Extend localization checks to visible literals, catalog coverage, placeholder parity, semantic-key usage and unresolved exceptions.
- [ ] Run route-level tests for loading, empty, error, dialog and success states.
- [ ] Add Android tests for preference application/persistence and language-switch state preservation.
- [ ] Record CI and browser/device results against the route matrix.
- **Accept:** zero missing translations in critical workflows, 100% catalog/placeholder parity, zero critical state/grading regressions, and zero unresolved route acceptance checks before claiming production completion.

## Priority implementation order

1. Fix supported-locale and Android account-setting/runtime mismatch.
2. Add regression tests for preference save/apply behavior.
3. Update route inventory and localization guardrails.
4. Migrate high-risk and high-traffic Web/Admin/People surfaces away from inline translation.
5. Unify Android translation resources and cover every destination.
6. Complete contextual copy review, dynamic messages and learning-content audit.
7. Run end-to-end bilingual/responsive acceptance and update the tracker with evidence.

## Implementation checkpoint — 2026-10-27

Implemented on this branch:
- Restricted Android account interface-language choices to English and Hinglish; legacy unsupported profile values display as Hinglish instead of an unselected option.
- Saving the account interface preference now updates Android's active UI locale and persists it in app preferences after the API accepts the change.
- The account profile API now accepts only registered UI locale IDs (`english`, `hinglish`). Added API and Android locale-resolution regression tests.
- Added a report-only UI copy-quality audit. It lists long messages for contextual review, validates locale key/placeholder consistency, and is attached as a CI artifact.
- Shortened and contextualized 18 messages across learner, Admin and HRMS screens in English and Hinglish. Updated the canonical catalogs and all generated Web/Admin/People copies together.

**Validation still required:** Android/API tests and full CI have not yet been confirmed on this branch. Full route coverage, browser/device acceptance, the complete Android/catalog integration, dynamic-message coverage, and the broader contextual copy review remain open.

## Existing authoritative records

- `docs/engineering/centralized-translation-implementation-tracker.md`
- `docs/engineering/ui-localization-route-coverage.md`
- `docs/engineering/structured-lesson-renderer-implementation-tracker.md`
- `docs/bilingual-content-architecture.md`

The current catalog's key/placeholder parity is necessary, but not sufficient: route-level and runtime acceptance remains required.
