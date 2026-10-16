# Structured Lesson UI Implementation Tracker

**Purpose:** Resumable engineering tracker for rendering the structured Class 6 Science lesson JSONs in Web Student, Web Admin, and Android. This is the source of truth for completion status and acceptance evidence.

**Current baseline inspected:** `main` at `54474d101b842dae89b1b2b6f1ca12c534871975` (tree `c678ee4aba83e1777696adf2d9d32d96afd6451d`), checked on 2026-10-16.
**Related contracts/data:** [Question rendering registry](../../data/structured-lessons/question-rendering-registry.v1.json), [structured lesson README](../../data/structured-lessons/README.md), [production index](../../data/structured-lessons/class-6/science/production-index.v1.json), and [resume Issue #56](https://github.com/Manav326/QuantaEdge/issues/56).
**Status semantics:** `[x]` = implemented and acceptance checks evidenced; `[~]` = currently being implemented; `[ ]` = not complete. A file existing is not proof of runtime integration. Never mark a task done from code inspection alone when the listed build/test/manual checks have not passed.

## Existing code paths verified

- Web student lesson: `apps/web/app/student/learn/LearnClient.tsx`. It renders the legacy database block schema (e.g. `EXPLANATION`, `IMAGE`, `DIAGRAM`, `WORKED_EXAMPLE`, practice hints, media, recap).
- Web student practice: `apps/web/app/student/practice/PracticeClient.tsx`. It is built around the legacy API question schema (`question_type`, serialized `options`, server answer endpoint), including existing legacy option, ordering/matching and text-entry flows. This is not yet the structured JSON renderer for all eight new question contracts.
- Web admin authoring/preview: `apps/admin/app/content/page.tsx`. It has legacy lesson-block editor/preview and visual-candidate governance, but no demonstrated end-to-end structured JSON preview using the exact learner renderer.
- Android lesson blocks: `apps/android/app/src/main/java/com/quantaedge/android/ui/LessonContentBlock.kt`. It renders the existing database `block_type` / JSON string shape.
- Android lesson/question flow: `apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt` (lesson screen and existing `QuestionCard`), plus `apps/android/app/src/main/java/com/quantaedge/android/ui/AndroidLocalization.kt` (shared UI language context).
- Registry: `data/structured-lessons/question-rendering-registry.v1.json`. Its status is `SPECIFIED_NOT_IMPLEMENTED`; it defines the structured question contracts but does not prove the new renderers exist.
- Structured content lives under `data/structured-lessons/class-6/science/chapter-01` through `chapter-06`. Its shape differs from the legacy API/database shape. UI components must not assume a JSON file automatically flows into current API endpoints.

## Data contract to implement

### Question types (eight, one canonical renderer per `type`)

| ID | JSON type | Required UI behavior | Current state |
|---|---|---|---|
| UI-Q01 | `multiple_choice` | Single-select accessible radio/choice group; stable option IDs; submitted correctness/feedback | [ ] Structured renderer not verified |
| UI-Q02 | `multiple_select` | Multi-select checkbox group; preserve selected ID set; exact-set grading contract | [ ] Structured renderer not verified |
| UI-Q03 | `true_false` | Accessible True/False choices; do not rely on colour alone | [ ] Structured renderer not verified |
| UI-Q04 | `matching` | Each left item selects one right item; narrow-screen stacked layout; partial credit displayed when returned | [ ] Structured renderer not verified |
| UI-Q05 | `fill_blank` | Localized input and validation; locale-specific accepted answers; Unicode-safe normalization | [ ] Structured renderer not verified |
| UI-Q06 | `image_observation` | Approved image/diagram + caption/alt text + declared answer control; hard-block non-approved/non-renderable visuals | [ ] Structured renderer not verified |
| UI-Q07 | `short_answer` | Multiline response, rubric/pending-review state; never invent automatic grading without a configured, validated grader | [ ] Structured renderer not verified |
| UI-Q08 | `scenario_based` | Separate scenario/context and question; render the declared response subtype; all context localized | [ ] Structured renderer not verified |

### Explain/lesson content

Handle the structured schema (not only legacy database block names):
- `rich_text`: localized heading, paragraphs, callout/key point; safe plain text/limited sanitized formatting, never execute generated HTML or script.
- `image`: localized source/alt/caption, source attribution and review/provenance state. Source-page screenshots marked editor-only or `student_renderable:false` must never appear in student UI.
- `diagram`: render approved `diagram_data` (nodes/edges/layout) as a responsive, language-switchable visual; preserve alt/caption. Unsupported or unreviewed visual data gets a clear blocked/needs-review state, not a broken image.
- `interactive`: localized activity instructions, steps, controls and safe response entry; persist neutral response data across locale changes.
- `recap`: render localized recap/key points.
- Preserve authored block order, stable IDs, objective links and source references. Unknown block types fail closed with telemetry/admin diagnostic, not raw JSON on the student screen.

## Implementation checklist

### Foundation and data plumbing
- [ ] **UI-001 — Shared typed contract/adapter.** Define TypeScript/Kotlin models or validated adapters for lesson, locale map, explain blocks, questions, visuals and answer/rubric state. Keep structured data separate from legacy API records; document the mapping explicitly.
  - Acceptance: malformed JSON, missing required locales, unknown types, duplicate IDs, invalid answer IDs/pairs and broken asset refs return actionable validation errors; valid Ch1/2/6 fixtures parse on both clients.
- [ ] **UI-002 — Source and renderability gates.** Centralize checks for workflow approval, `publication_allowed`, `student_renderable`, `render_status` and visual localization. Do not show source-page previews or any blocked draft to students.
  - Acceptance: blocked/unapproved visual is not rendered and cannot be bypassed by Web, Admin preview mode, or Android.
- [ ] **UI-003 — Runtime delivery path.** Decide and implement how approved structured JSON reaches each client (admin preview from a selected/imported artifact; learner view via authenticated API/published version). Do not assume repository files are implicitly served by the existing relational learning endpoints.
  - Acceptance: versioned artifact loads with error/loading/empty states; no unauthenticated private content route; legacy lesson routes remain unchanged.

### Web student
- [ ] **UI-101 — Structured Explain renderer.** Implement each supported block kind in `apps/web`; responsive, accessible, en/hi localized. Render diagrams using node/edge/layout data rather than treating data objects as URLs.
  - Acceptance: all block types have previews; correct block order and locale; unknown type is safely blocked; no console errors.
- [ ] **UI-102 — Eight question components + dispatcher.** Implement the eight registry types behind a typed `PracticeQuestionRenderer`. Reuse existing QuantaEdge visual tokens/feedback styles where suitable; no new parallel practice page unless routing requires it.
  - Acceptance: fixture renders every type, validates inputs, and sends neutral IDs/boolean/text/pairs to the answer/grading interface.
- [ ] **UI-103 — Practice-session state.** Store response/result by stable question ID; preserve current question, selected options, matching pairs, typed text, submitted state, score and progress across English/Hindi changes and question revisits.
  - Acceptance: switch language before and after submission on each type; answers/progress remain unchanged.
- [ ] **UI-104 — Visual and activity controls.** Add zoom/lightbox where appropriate; alt text and captions; diagram scaling; activity input/reset/save. Ensure keyboard/touch support and visible focus.
  - Acceptance: keyboard-only, screen-reader labels and mobile width test pass; visuals are not clipped.

### Web admin
- [ ] **UI-201 — Structured lesson preview.** Preview the exact structured lesson schema using the same rendering logic as Web Student where practical; support en/hi toggle and block/question navigation.
  - Acceptance: admin preview matches learner appearance for the same approved fixture; no student-publish behavior is triggered by previewing.
- [ ] **UI-202 — Validation and status reporting.** Show missing locale/content fields, bad references/contracts, renderer support, visual review status and publication blockers with actionable messages.
  - Acceptance: one deliberately broken fixture produces the relevant error per field; preview cannot mark content approved/published.
- [ ] **UI-203 — Visual QA workflow.** Show candidate/source provenance, generated visual draft vs approved asset, alt/caption and localization completeness. Connect the v3 candidate governance rather than bypassing it.
  - Acceptance: source preview is editor-only; only reviewed language-appropriate visuals pass the student renderability gate.

### Android
- [ ] **UI-301 — Structured lesson block renderer.** Extend/reuse Compose renderers without breaking legacy `LessonContentBlock`; support localized rich text, image, diagram nodes/edges, activity and recap with responsive scrolling.
  - Acceptance: Ch1/2/6 fixtures display; legacy DB lesson display stays unchanged; blocked assets never render.
- [ ] **UI-302 — Eight question components + dispatcher.** Implement single-select, multi-select, true/false, matching, fill blank, image observation, short answer and scenario-based controls in Compose.
  - Acceptance: each type follows the registry contract, works with keyboard/accessibility services and touch, handles narrow/landscape layouts, and does not rely on colour alone.
- [ ] **UI-303 — State and language parity.** Use the existing global Android language source; store neutral answer state by stable question ID. Switching language only changes labels/content, not learner input or score.
  - Acceptance: type-by-type language-switch test plus navigating away/back restores active response/session.
- [ ] **UI-304 — Visuals and interactive activities.** Render native/vector diagram data, approved asset links, zoom/captions/alt descriptions and localized activity steps. Handle offline/unavailable assets with a clear placeholder.
  - Acceptance: no malformed-image crash; TalkBack labels present; content scales in portrait/landscape.

### Cross-platform QA / release
- [ ] **UI-401 — Contract fixture and regression tests.** Keep shared JSON fixtures for all eight question types and all structured block kinds; test good/bad locale/ID/visual cases.
  - Acceptance: automated Web unit/build tests and Android unit/build tests pass.
- [ ] **UI-402 — Cross-platform parity matrix.** Test the same lesson/question fixture on Web Student, Web Admin preview and Android: content, options, correctness, partial credit, pending review, localization and progress.
  - Acceptance: all cells have evidence; any intentional platform-specific behavior is explicitly documented.
- [ ] **UI-403 — Performance and lifecycle.** Avoid remounting the whole lesson when one answer changes; key state by stable IDs; lazy-render long lesson/question lists and avoid repeated artifact fetches.
  - Acceptance: answer interaction updates only the necessary component; route/language changes do not reset session; no unbounded refetch loop.
- [ ] **UI-404 — Publish gate.** Content remains non-publishable until source completeness, scientific review, bilingual semantic review, visual accessibility and UI render tests pass.
  - Acceptance: attempting to publish a draft with any required blocker is rejected with explicit reasons.
- [ ] **UI-405 — Final regression and resume update.** Run Web/Admin checks, Android `assembleDebug` and `testDebugUnitTest`, record outputs/limitations, update this file per completed task and append the final commit SHA to Issue #56.
  - Acceptance: tested commit is identifiable; completed task IDs have evidence; remaining items state a concrete next action.

## Required test matrix

For every question type on both student surfaces:
1. Render correct locale with all content/options and proper response input.
2. Select/type/submit; verify server or configured local grader response and correctness/pending status.
3. Switch language before submission and after feedback; current question, answer, score and progress remain stable.
4. Revisit the question and confirm state restoration.
5. Test missing locale, missing option, invalid correct-answer reference and unapproved visual.
6. Keyboard/TalkBack, touch targets, focus announcement, contrast without colour-only status, portrait/small-screen and landscape checks.

Test lesson fixtures: at minimum Class 6 Science Ch1 (rich text plus source/diagram/interactive blocks), Ch2 (classification/observation), and Ch6 (material comparisons/solubility/mass/volume). Include any approved visual; also include a blocked visual.

## Resume protocol (mandatory)

1. Open [Issue #56](https://github.com/Manav326/QuantaEdge/issues/56).
2. Read the latest **Structured Lesson UI Implementation** checkpoint, not older checkpoint sections.
3. Fetch its exact staged tree SHA and this file's recorded blob SHA; compare with the latest `main` head before editing.
4. Inspect all staged paths and keep existing user work. Do not overwrite files from a stale base. Do not create another branch.
5. Continue at the **first unchecked UI task ID**. After each meaningful vertical slice, update the staged tree and Issue #56 with tree/blob pointers, test evidence, and exact next action. Do not say a task is complete unless its acceptance checks were actually run.
6. Commit to `main` only once the agreed slice is complete and validated; record commit SHA and CI/build status. Never turn a source/data review blocker into a publish approval.

**User resume phrase:** `Resume QuantaEdge structured lesson UI implementation from Issue #56. Fetch the latest checkpoint tree and tracker, preserve all staged work, and continue with the first incomplete UI task ID.`

## Checkpoint on 2026-10-16

- Inspected main `54474d101b842dae89b1b2b6f1ca12c534871975` / tree `c678ee4aba83e1777696adf2d9d32d96afd6451d`.
- Confirmed the registry defines eight question types but is marked `SPECIFIED_NOT_IMPLEMENTED`.
- Confirmed current Web/Android renderers use a legacy database schema; they are integration points, not proof the new structured schema is supported.
- A specific uncommitted UI tree containing an Android Search component was **not identifiable in the visible main tree or the checked relevant branch trees**. Do not claim those files are preserved unless a matching tree/blob pointer is recovered from GitHub. Existing main files listed above are intact.
- UI implementation status at the initial checkpoint: foundation and new structured renderers were not yet implemented. The checklist above is the authoritative task list.


## Recovery investigation addendum (2026-10-16, after user correction)

- Rechecked the exact checkpoint tree above and inspected the complete current `main` tree plus relevant existing branches: `agent/learning-content-ai-android`, `android/play-store-size-and-compose-perf`, `feat/android-ui-localization-responsive-parity`, `feat/android-ui-localization-responsive-parity-v2`, `feat/complete-learning-content`, `feat/curriculum-learning-engine`, `feat/web-android-navigation-unification`, `plan/web-android-navigation-unification`, `feat/market-ready-mvp`, and `feat/student-textbook-reading-experience`.
- No file whose path contains `search` was found in current main or the trees checked. No structured question renderer components were found in those refs, beyond legacy `LessonContentBlock.kt` and `PracticeClient.tsx`/`LearnClient.tsx`.
- Therefore the previously reported uncommitted Search/renderer work has **not been recovered**. It may have existed only in a staged Git tree whose SHA is not recorded in available issue history/current context; GitHub cannot enumerate unreferenced tree objects by filename. Do not state that the original component bytes are retained, and do not overwrite any original tree should the exact SHA later surface.
- The staged tracker tree is still only the tracker document change. No application code was added by creating the tracker checkpoint. This distinction must remain explicit.
- Next step: proceed with additive implementation from the first task, UI-001. Create exact blobs and a complete tree checkpoint after each vertical slice, and append those tree/blob SHAs to Issue #56 immediately. Do not rely on ephemeral context as the only record.


## Implementation slice 1 — Web structured lesson rendering skeleton (source added; tests pending)

- **Tree:** `29ec49f55135c7df39437d3e2d9cd043fe0ab8c6`
- **Tree URL:** https://api.github.com/repos/Manav326/QuantaEdge/git/trees/29ec49f55135c7df39437d3e2d9cd043fe0ab8c6?recursive=1
- **Status:** files created as staged Git objects only; not committed; build/tests not run.
- `apps/web/app/student/structured-lessons/contract.ts` — `42e6c6015609084fadba0bd52948994e1f69bff3`: typed locale-neutral structured lesson/question contract, validator for the eight question types, stable IDs, localized required fields, answer-option/pair references, and strict visual gating helpers.
- `apps/web/app/student/structured-lessons/StructuredLessonContent.tsx` — `21073bd279a6f8c8370258373c773b34b25c403c`: localized Explain/block renderer for rich text, image, diagram, activity/interactive and recap; admin preview can inspect drafts while student mode hides visuals not explicitly approved.
- `apps/web/app/student/structured-lessons/StructuredQuestionRenderer.tsx` — `8f0a2df8c70cd86aa3a7d6e376eb55d64157a10b`: renderer dispatcher for MC, multi-select, true/false, matching, fill blank, image observation, short answer and scenario; response values use stable IDs/boolean/text/pairs.
- `apps/web/app/student/structured-lessons/StructuredLessonExperience.tsx` — `e123a1fba59dbe69bce1cc8dabe5d182d26a62df`: one-question-at-a-time flow with parent-owned response/result state keyed by stable question ID; state stays mounted across locale prop changes; admin preview uses explicit grading only for objective types and leaves short answers pending review.
- **Not complete yet:** no CSS/premium layout, no admin page wiring, no actual API/source delivery adapter, no Android Compose renderer, no automated build/test evidence. Student mode deliberately does not invent a local score when no submit adapter is passed.
- **Next action:** add contract fixtures and tests/validation; fix any schema incompatibilities against Chapter 1, Chapter 2 and Chapter 6 sample artifacts. Then implement the Web Admin JSON preview using its own app build context and the same contract. Follow with Android components and real delivery/grading integration.


## Implementation slice 2 — Web Admin preview + learner adapter (source staged; tests pending)

- **Current complete staged tree:** `0736320036d6f9d657f65cc295e9f26f379c5411`
- Browse: https://api.github.com/repos/Manav326/QuantaEdge/git/trees/0736320036d6f9d657f65cc295e9f26f379c5411?recursive=1
- New Admin preview route `apps/admin/app/structured-lesson-preview/page.tsx` — `e3300d7a88f760e9f99311264b209e8c97c14fda`. Accepts a JSON file or pasted JSON, runs structural validation, reports errors, toggles EN/HI and renders draft review content. Preview activity responses remain local; no upload, approve or publish action is offered.
- Admin renderer/contract copies are co-located under `apps/admin/app/structured-lesson-preview/` for the current isolated Docker build contexts. Their blob SHAs are identical to the Web counterparts where the source code is intentionally identical.
- `apps/admin/app/content/page.tsx` — `a1aa5fc5c31c0769bdc21111fe9395204473f20d`: adds a direct Content Studio link to Structured JSON Preview.
- `apps/web/app/student/learn/LearnClient.tsx` — `6f036bdf5c58c8a0e05bac66d500f0794e96270f`: additive optional `structured_lesson` rendering path; existing legacy API lesson path is retained unchanged.
- Web validator now understands bilingual activity instruction arrays, verifies question-to-visual references and provides a server-side sanitization helper. Student UI fail-closes drafts and payloads with answer keys.
- **Important boundary:** current API responses do not yet include a published structured artifact, and no API endpoint exists for grading the stable string question IDs. The learner component accepts a host submit callback and reports a clear error rather than fabricating saved scores when no callback is wired. Server artifact delivery and grading remain UI-003/backend integration work.
- **No build/test result claimed.** The remote Git object service provided no local repo/build environment during this chat; CI/manual validation is still required. Task IDs UI-001/UI-101/UI-102/UI-201 are in progress, not marked complete.
- **Next action:** implement the native Android structured lesson/explain/question Compose components, including the same visual and answer-key gates. Then add or adapt tests and update the complete tree checkpoint.


## Resume checkpoint — server-side structured artifact adapter (2026-10-16; CI acceptance pending)

- Rechecked main while resuming. The prior Web/Admin/Android renderer slice is already committed at 7f28d280f88923cfe78fbaeab9a299fde5e307bd; this slice is additive and does not replace those components or change authored lesson JSON files.
- Added StructuredLessonArtifactService.java: server-side artifact discovery from packaged classpath JSON, duplicate artifact-ID fail-closed behavior, schema checks, explicit publication/review gates, approved-visual checks, and a sanitized learner payload that removes grading keys and author-only feedback.
- Added StructuredLessonController.java under /api/v1/learning/structured-lessons: student-authenticated, locale-aware artifact delivery and a stable-string-question-ID response endpoint. Student track access is checked before delivery or submission. Legacy numeric question endpoints remain unchanged.
- Added Flyway migration V106__structured_lesson_responses.sql for per-student, artifact-versioned, stable-ID response persistence, attempt counts and objective/pending-review result states.
- Added StructuredLessonArtifactServiceTest.java for draft/pending-gate denial, answer-key sanitization, server-side grading and response validation.
- Corrected API Docker build context in services/api/Dockerfile, docker-compose.yml and .github/workflows/ci.yml so repository-root data/structured-lessons is packaged as server-side API resources.
- Security/content gate: current generated artifacts are still drafts with publication_allowed=false and pending source/bilingual/visual reviews. The new learner endpoint must not deliver them; previewing or rendering a draft does not approve it.
- Validation status: tests are authored but have not yet been run in this session. No UI task is marked complete from source inspection alone. Await the GitHub API test/build workflow results for the adapter slice.
- Next action after CI: fix any build/test regressions found by Actions, then wire the new published-artifact endpoint into the Web learner route and Android lesson route with state restoration by stable question ID. Keep the existing legacy lesson route intact and maintain Web/Admin/Android renderer parity.


## CI feedback — adapter compile correction (2026-10-16)

- Build run 2753 reached API verification and identified one compile-time API mismatch: Jackson 3 JsonNode does not provide fieldNames().
- Replaced that call with JsonNode.properties(), already used in this service to recursively strip answer keys. This is a targeted API-compatibility fix; the API tests/build must rerun before the adapter slice is accepted.
- Run: https://github.com/Manav326/QuantaEdge/actions/runs/38874455123


## CI feedback — Spring bean constructor selection (2026-10-16)

- The Jackson 3 compile error was corrected. The next API verification run compiled and the four new StructuredLessonArtifactService tests passed, but Spring application-context tests reported that the service bean had no default constructor.
- Cause: test-only/package-private constructors mean Spring needs an explicit production constructor selection in this Spring Boot configuration.
- Fix: annotate the public ObjectMapper-only constructor with @Autowired. The existing fixture constructors remain package-private for tests.
- Follow-up: rerun API verification and keep the next application/client slice blocked until bean startup and Flyway migrations pass.


## Learner API + Web client wiring (2026-10-16; CI acceptance pending)

- Added published-only structured lesson catalogue discovery; only students with an active class/subject enrollment receive matching published entries.
- Added separate activity-response persistence plus saved-response retrieval for stable-ID lesson state; legacy numeric lesson/question APIs remain unchanged.
- Web experience now hydrates restored question/activity responses, uses bilingual server feedback, and shows activity save success only after the server confirms the save.
- Added a Web structured-lessons catalogue/detail route and a navigation link in the existing Learn group.
- Validation pending: API verification and Web image build must pass before marking this slice complete.


## Android structured lesson API wiring (2026-10-16; CI acceptance pending)

- Reuses the existing Learn route and existing Compose StructuredLessonRenderer; no second navigation group or unrelated app route was added.
- Adds an entry from the existing lesson list into the published-only structured lesson catalogue, with a clear empty state while the generated topics remain unapproved.
- Loads lesson JSON and saved state from the same endpoints as Web. Question and activity responses use stable IDs and server persistence.
- Corrects Android answer bodies to the new API's typed contract: string option IDs; Boolean true/false; string array for multi-select; matching object; raw text string for text answers.
- Restores saved answer/activity state, renders server statuses/scores and language-specific feedback, and presents one practice question at a time.
- Existing legacy numeric lesson route remains intact. Validation is pending the commit's Android APK, API verification, Web image build and migration checks. Do not mark implementation green until the run completes.


## Android missing-import fix committed — acceptance still pending (2026-10-16)

- On main commit 70d243a4cdce6135d1bb51accf23d3870544c536, added the missing androidx.compose.material3.OutlinedButton import reported by Android Kotlin compilation in run #2764.
- The failing compile diagnostic was: StructuredLessonRenderer.kt:104 unresolved reference OutlinedButton; the related Composable-context diagnostic was downstream of that unresolved symbol.
- New validation run: https://github.com/Manav326/QuantaEdge/actions/runs/38878396073 (run #2766). Its status was pending at the last check.
- Do not mark UI-Q items green yet: latest green evidence covers API verification, Web/Admin/People builds and content import safeguards, not the repaired Android APK build or full manual Web/Admin/Android parity matrix.
- Remaining acceptance: confirm run #2766 Android and API image succeed; exercise approved Class 6 Science Ch1/Ch2/Ch6 fixtures across learner Web, Admin preview and Android; test all question types, language switching, saved state and blocked-visual/publication gates. Current generated lesson artifacts remain drafts by design.
