# Structured Lesson UI Implementation Tracker — Class 6 Science

**Purpose:** Track implementation of the structured bilingual lesson content (Chapters 1–6) in student Web, Admin Web, and Android. This is an implementation checklist—not an approval of lesson content or visuals.

**Repository:** `Manav326/QuantaEdge`  
**Baseline main HEAD when this tracker was prepared:** `cd08d103baf1bcf0723f044eaa75da9479043af7`  
**Audit source:** [Structured Lesson Rendering Audit — Class 6 Science, Chapters 1–6](structured-lesson-rendering-audit-class6-science-chapters-1-6.md)  
**Data contract:** [Question rendering registry JSON](../../data/structured-lessons/question-rendering-registry.v1.json) and [human-readable contract](../../data/structured-lessons/question-rendering-registry.v1.md)  
**Authoring pipeline:** [Multimodal lesson authoring pipeline](../ai/multimodal-lesson-authoring-pipeline.md)

## 1. Current context — why this work is needed

The prepared Class 6 Science set has **39 topic JSON files and 312 questions**. Each topic currently has one example of each of the eight registered question types. The registry describes the expected UI; it does not implement the UI.

Existing Web and Android lesson/practice screens render the older database/API content model. Their legacy MCQ, true/false, match and free-text controls are useful reference implementations, but they are **not wired to** the new `quantaedge.structured-lesson.v1` shape, stable string IDs, `rendering_contract_id`, localized dictionaries, `diagram_data`, `visual_asset_ref`, or structured grading data.

This must be an **additive integration**:
- Keep the legacy lesson, practice and formal-test paths working.
- Do not coerce new structured records into legacy numeric IDs or stringified options if doing so loses IDs, rubric, locale, visual-gate or grading information.
- Do not create new question-type strings without versioning the registry, defining the response/grading contract, and adding tests.

### The eight registered question types

| Data type | Required component | Main behavior |
|---|---|---|
| `multiple_choice` | `MultipleChoiceQuestion` | Single selection by stable option ID; exact answer match |
| `multiple_select` | `MultipleSelectQuestion` | Checkbox set of IDs; exact-set grading |
| `true_false` | `TrueFalseQuestion` | Explicit boolean choice; no colour-only correctness cue |
| `matching` | `MatchingQuestion` | Stable left/right IDs; pair-by-pair scoring and partial credit |
| `fill_blank` | `FillBlankQuestion` | Localized input and locale-specific Unicode/whitespace normalization |
| `image_observation` | `ImageObservationQuestion` | An approved visual plus the v1 single-choice response; block while visual is unapproved |
| `short_answer` | `ShortAnswerQuestion` | Multiline response, bilingual guide/rubric, pending review if no validated grader |
| `scenario_based` | `ScenarioQuestion` | Separate scenario card and prompt; v1 single-choice response |

### Structured lesson blocks and visual categories

Lesson blocks needing a structured path: `rich_text`, `image`, `diagram`, legacy `activity`, newer `interactive`, and `recap`. Normalize the activity variants explicitly rather than dropping fields.

Visual taxonomy is separate from question types: photo, illustration, diagram, chart, table, infographic, sequence/process, and textbook figure. An image URL alone does not render structured node/edge diagrams, accessible charts, semantic tables or sequence data.

**Visual safety gate:** V3 candidates are review inputs, not approved student assets. At this audit baseline all 39 image-observation questions and the original structured diagram drafts are blocked/pending review. The existing Class 6 V3 catalog is English-only; a whole-page source preview is editor-only and must never be shown to students—even in English mode. Student display requires a reviewed link to the exact lesson/question, source/book/page/hash and rights/status metadata, locale-appropriate accessible text, and an explicit student-renderable approval.

## 2. How to maintain this tracker

Each item has two independent checkboxes:
- **Implementation on main** — check only after the code is recorded in `main`; fill in the commit SHA.
- **Acceptance QA passed** — check only after the listed acceptance criteria have been exercised on the named surface; record the test command/run or evidence link.

Do not close a task because a specification, mock, JSON record or component stub exists. Do not mark content review or publication approval done as a side effect of rendering QA. “Green” for implementation and “QA passed” are separate signals.

---

## 3. Foundation and shared contracts

### [ ] SL-01 — Add typed structured-lesson models and a canonical loader
**Surface:** shared content/API boundary; Web and Android consumers  
**Required context:** The source JSON schema is `quantaedge.structured-lesson.v1`; the production index maps class/subject/chapter/topic to files. Existing API records use different shapes.  
**Acceptance:** load a topic by stable curriculum key; validate schema version; retain stable topic/block/question/option IDs, both locales, source refs, grading, safety notes and gate fields; fail closed with a localized unavailable state for malformed or unsupported content. Legacy data continues to load unchanged.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] SL-02 — Build schema/contract validation into CI
**Surface:** shared scripts/tests/CI  
**Required context:** JSON parsing alone is insufficient. The registry requires exact contract/type mapping and valid option/matching references; visual questions have an explicit gate.  
**Acceptance:** tests cover unique IDs, supported types/contracts, required fields, English/Hindi completeness, answer references, matching pairs, grading/rubric shape, activity response fields, asset refs, visual-gate states and publication flags. Invalid records fail with a useful path/field error.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] SL-03 — Add a versioned adapter for legacy/new block schemas
**Surface:** shared content boundary; Web and Android  
**Required context:** The data includes `rich_text`, `image`, `diagram`, older `activity`, newer `interactive`, and `recap`. Legacy records use `block_type` plus a `content` payload and may use `points[]`.  
**Acceptance:** adapt each known variant without losing localized text, objective/source refs, instructions/steps, response fields, feedback, safety notes or review metadata. Unknown block variants show a controlled unavailable/editor diagnostic state, not a crash. No duplicate recap or heading is created.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] SL-04 — Implement shared stable answer/attempt state
**Surface:** Web user, Android, API/persistence as required  
**Required context:** Answers must be keyed by stable question and option IDs, not array order or locale-specific labels. Existing numeric-ID practice storage must not silently lose structured responses.  
**Acceptance:** represent selected option IDs, selected boolean, matching pairs, typed text, submission state, score and progress; persist/resume through the approved existing or new endpoint/storage path; define idempotent submit/retry behavior. Switching locales never resets answer state.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] SL-05 — Enforce the single global language contract
**Surface:** Web user, Admin preview, Android  
**Required context:** The app-level `en`/`hi` selector is the sole locale source; no per-question language selector or silent fallback is allowed.  
**Acceptance:** prompts, scenarios, options, headings, paragraphs, recap, activity steps, visual labels/captions/alt text, controls, errors, result states, answer guides and rubrics switch together. If required locale text is missing, block the item with an explicit localized/diagnostic state; do not mix languages. Preserve current question, typed/selected answers, submitted state, score, progress and reading position.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] SL-06 — Add an approval-aware visual asset resolver and hard gate
**Surface:** Web student, Admin preview/review, Android, content/API boundary  
**Required context:** V3 visual candidate packs and lesson `diagram_data` are different asset types. V3 source previews are not student visuals. A candidate having a URL does not mean it is approved.  
**Acceptance:** resolve the exact visual reference against its manifest; verify candidate ID, source book/page/hash, status/rights, locale/alt/caption and `publication_allowed`/student-renderable gate. Student mode blocks missing, unreviewed, rights-blocked, editor-only or wrong-language assets and questions. Admin may inspect blocked candidates with an unmistakable review-only label.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] SL-07 — Finalize schema decisions before implementation freeze
**Surface:** registry, validators, authoring data and renderer tests  
**Required context:** Chapter 1–2 short-answer records omit `feedback_by_locale` while supplying localized answer guides/rubrics; Chapter 1 has no source-coverage ledger; Chapter 1 uses older activity/image block variants.  
**Acceptance:** document whether short-answer feedback is optional when a localized guide/rubric exists; make validator and renderer agree. Add/record Chapter 1 coverage audit before claiming uniform coverage. Keep older variants explicitly supported or flag them for data migration—do not silently ignore them.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

---

## 4. Student Web implementation — `apps/web`

### [ ] WEB-01 — Add structured lesson loading to the student learning journey
**Required context:** Existing `LearnClient.tsx` consumes database-backed lessons and legacy blocks. Connect canonical class/subject/chapter/topic selection to the structured loader/adapter without replacing legacy routes.  
**Acceptance:** a Chapter 1–6 fixture opens the intended topic; Explain and Practice are separate; back/next/navigation and progress use stable topic IDs; a missing/invalid topic produces a recoverable state and does not refresh unrelated page components.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-02 — Implement `RichTextBlock`
**Required context:** New shape uses localized heading, ordered paragraph array and key point; legacy explanation/prerequisite blocks remain.  
**Acceptance:** correct paragraph order and spacing, supported safe inline formatting/math, responsive typography, no executable HTML from lesson data, and all text changes with the global locale.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-03 — Implement reviewed `ImageBlock` and asset resolution
**Required context:** Blocks can include `asset_refs[]`, source identifiers/hash/page, URL, localized alt/caption and editor-only/status/rights fields.  
**Acceptance:** only approved student assets display; image has meaningful alt/caption, bounded responsive sizing and zoom/open behavior when appropriate; missing/blocked assets never fall back to a page screenshot or arbitrary URL.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-04 — Implement `StructuredDiagramRenderer`
**Required context:** Original lesson visuals may be structured node/edge data with localized labels, positions/layout, alt text, caption and review status—not image URLs.  
**Acceptance:** render the schema's nodes/edges/layout; update every label on language switch; provide a clear responsive layout, keyboard/screen-reader description and accessible static fallback; keep the visual blocked until approved.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-05 — Define/render structured chart, table, infographic and sequence visuals
**Required context:** V3 media taxonomy includes chart, table, infographic and sequence in addition to photos/illustrations/diagrams. A type label alone is not a renderer.  
**Acceptance:** define the supported data shape and component/fallback for each supported visual type; tables remain semantic, charts expose a textual summary/data alternative, sequences preserve step order, infographics support zoom/alt. Unsupported types are blocked or shown as an explicit unavailable editor state.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-06 — Implement the configurable `ActivityRenderer`
**Required context:** Support both `activity` and `interactive.configuration` through SL-03; do not treat these as plain display-only blocks.  
**Acceptance:** localized instructions, steps, safe response controls, save/reset, validation/formative feedback, response persistence and safety note work. Switching locale preserves learner input. Existing guided/independent practice blocks keep working.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-07 — Implement the structured `RecapRenderer`
**Required context:** New records use `content_by_locale`; legacy recaps may use `points[]`.  
**Acceptance:** show one concise recap with correct locale and no duplicate legacy/new recap cards.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q01 — Implement `MultipleChoiceQuestion`
**Acceptance:** accessible radio group; one stable option ID; exact-ID grading; selected/disabled/submitted/correct/incorrect states and localized feedback.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q02 — Implement `MultipleSelectQuestion`
**Acceptance:** accessible checkbox group; unordered selected-ID set; exact-set grading; do not treat it as single-select or infer partial credit.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q03 — Implement `TrueFalseQuestion`
**Acceptance:** two equally clear localized choices tied to boolean/null state; correct/incorrect feedback is readable without colour alone.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q04 — Implement `MatchingQuestion`
**Acceptance:** labelled columns on wide screens; accessible stacked rows/selectors on narrow screens; response stores stable ID pairs; each pair can score independently with partial credit. Drag-and-drop must not be the only interaction.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q05 — Implement `FillBlankQuestion`
**Acceptance:** localized text input and submit control; normalize English and Hindi according to the registry only; never transliterate or translate the learner's response silently.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q06 — Implement gated `ImageObservationQuestion`
**Acceptance:** question is not in the student question flow until an approved `visual_asset_ref` resolves; then render accessible visual/caption/alt and v1 single-choice controls. If gate fails, block the question cleanly without crashing the lesson.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q07 — Implement `ShortAnswerQuestion`
**Acceptance:** multiline response, localized answer guide/rubric and correct max score. With no validated grader, save the answer and show pending review—never invent a score. Continue to handle optional feedback consistently with SL-07.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-Q08 — Implement `ScenarioQuestion`
**Acceptance:** separate scenario/context card, separate prompt and v1 single-choice controls; both fields and options switch language together; content stays within the topic's chapter scope.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] WEB-08 — Implement shared answer feedback and question-state UI
**Required context:** Need explicit unanswered, selected, submitted-correct, submitted-incorrect, partly correct, pending review, unavailable/blocked states. Legacy `AnswerFeedback` can be reused only through a typed adapter.  
**Acceptance:** visible correctness text plus icons/colour, per-question progress, disabled states, localized helper/error messages, and no duplicate submit/score behavior. Selecting one answer updates only the owning question/state—not the whole page or unrelated lesson sections.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

---

## 5. Admin Web implementation — `apps/admin`

### [ ] ADM-01 — Add a structured-content browser/inspector
**Required context:** Current admin content/questions screens use the legacy database model. Administrators need to select class → subject → chapter → topic from the production index and inspect the actual structured JSON.  
**Acceptance:** show stable IDs, status, locale completeness, objective/block/question counts, source refs and contract IDs; do not confuse generated, reviewed, approved or publishable states. Existing content and questions pages remain usable.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] ADM-02 — Build an admin preview using the same Web renderer
**Required context:** Student and admin previews must not drift into two different rendering implementations. The admin needs to preview EN and HI and compare the exact content students would see once gates pass.  
**Acceptance:** reuses WEB block/question components and the same global-locale contract; identifies preview-only mode; preserves admin preview position/answers during locale switching; clearly labels draft and blocked content.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] ADM-03 — Add visual-candidate attachment and review panel
**Required context:** V3 candidate packs, source-image/page manifests and lesson `diagram_data` are distinct. An editor-only page preview may help review but is never a student asset.  
**Acceptance:** show candidate type, book/edition/page/hash, EN/HI availability, rights/review status, alt/caption, publication/student-rendering gate, and exact lesson/question attachment. Missing or ambiguous links are flagged; attaching a candidate does not approve it automatically.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] ADM-04 — Show source coverage and validation blockers
**Required context:** Chapters 2–6 have source-coverage ledgers; Chapter 1 currently lacks one. Source completeness and human review are separate from structural JSON validity.  
**Acceptance:** show source refs/page mapping status, missing ledger, visual/question gates, locale gaps, unsupported contracts, and pending rendered-page/figure/table/activity reconciliation. Do not provide a misleading green “complete” status if required review is pending.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] ADM-05 — Connect approval/publishing controls to authorized audited state
**Required context:** UI-only toggles are not authoritative. Use the existing authorized API and audit history where supported; add backend contracts only where the required state transition does not exist.  
**Acceptance:** role/permission checks, actor/time/reason history and explicit review transitions; publication is refused if source, bilingual, scientific or visual gates fail; no admin action silently makes editor-only source pages student-renderable.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

---

## 6. Android implementation — `apps/android`

### [ ] AND-01 — Add typed Kotlin models and structured lesson loader/adapter
**Required context:** Android uses legacy Compose content shapes in `LessonContentBlock.kt` and legacy learning/practice flows in `QuantaEdgeApp.kt`. Reuse neither as a lossy parser for the new JSON.  
**Acceptance:** load the same canonical fixture data as Web; use stable string IDs, locale dictionaries, renderer contract IDs, scoring and visual gate state; explicitly adapt legacy/new activity and recap variants; malformed records fail safely.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-02 — Implement structured block Composables
**Required context:** Need rich text, approved image asset, structured node/edge diagram, activity/interactive runner and recap—not just the current legacy URL-media renderer.  
**Acceptance:** all supported blocks render in reading order; diagrams expose localized labels and accessible descriptions; visual assets respect review/rights/locale gates; activities preserve responses and show safety notes; old blocks continue to work.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q01 — Implement `MultipleChoiceQuestion`
**Acceptance:** single stable option ID, accessible selection semantics, registry grading and localized result states.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q02 — Implement `MultipleSelectQuestion`
**Acceptance:** multi-checkbox selection, stable unordered ID set and exact-set grading; no single-select behavior.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q03 — Implement `TrueFalseQuestion`
**Acceptance:** explicit localized boolean controls and state; text/icon feedback complements colour.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q04 — Implement `MatchingQuestion`
**Acceptance:** responsive Compose layout with accessible selectors; stable ID pairs and pair-wise partial-credit scoring; no drag-only UI.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q05 — Implement `FillBlankQuestion`
**Acceptance:** localized text field/IME actions; registry-compliant English/Hindi normalization; saved typed response survives locale changes and navigation/resume.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q06 — Implement gated `ImageObservationQuestion`
**Acceptance:** verify and resolve the visual before showing the question; show caption/alt/zoom where appropriate; do not show unreviewed candidate/page screenshots; safely mark unavailable when gate fails.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q07 — Implement `ShortAnswerQuestion`
**Acceptance:** multiline response, localized guide/rubric/max score, and pending-review behavior if a validated grader is not configured. No fabricated automated score.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-Q08 — Implement `ScenarioQuestion`
**Acceptance:** separate localized scenario card, prompt and v1 response controls; screen reader and focus order remain logical.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-03 — Preserve state and parity during global language switches
**Required context:** Keep language selection aligned with the Web global `en`/`hi` selector. Android-specific offline behavior remains compatible with the same stable response IDs.  
**Acceptance:** test EN → HI → EN while unanswered, selected, typed, submitted, correct/incorrect, and pending-review; preserve question index, answer, score, progress and scroll/reading position. No mixed-language screen or silent fallback.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] AND-04 — Add responsive, accessible and offline-aware visual handling
**Required context:** Android portrait/landscape, text scaling, screen-reader semantics, touch targets and existing protected resource/cache rules must be respected.  
**Acceptance:** matching and diagrams remain usable in portrait/landscape; visual alternatives are available; approved assets resolve through the intended protected/offline path where supported; no student screen exposes review-only candidate assets.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

---

## 7. Cross-platform QA and release gates

### [ ] QA-01 — Create shared golden fixtures for every registered type and block
**Acceptance:** fixtures include all eight questions, rich text, image, structured diagram, both activity versions and recap; expected prompts/options/answers/scores match across Web and Android.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] QA-02 — Test grading, validation and answer normalization
**Acceptance:** unit tests cover exact MCQ/multi-select/boolean, matching partial credit, fill-blank English/Hindi normalization, malformed IDs, short-answer pending review, and stable response serialization.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] QA-03 — Test end-to-end EN/HI state preservation
**Acceptance:** on Web and Android, switching language does not reset current question, selected/typed response, submitted state, score, completion progress, activity input or reading position.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] QA-04 — Test visual gates and rights/locale failure paths
**Acceptance:** missing, pending, editor-only, wrong-locale or `publication_allowed:false` assets block student rendering; an approved linked asset renders with correct alt/caption; admin preview clearly differentiates review-only assets.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] QA-05 — Run legacy regression tests
**Acceptance:** current database lesson/practice/formal-test paths and old values (including `MCQ`, `TRUE_FALSE`, `NUMERICAL`, `ORDER`, `MATCH`, and long-answer/free-text modes) still behave as before. New enum names do not silently replace legacy values.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] QA-06 — Accessibility, layout and interaction performance
**Acceptance:** keyboard/focus/IME/touch/screen-reader checks pass, larger text and narrow screens work, correctness does not rely on colour alone, and selecting an answer updates only its owning component rather than refreshing unrelated lesson UI.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

### [ ] QA-07 — Keep content approval separate from renderer readiness
**Acceptance:** do not mark topics reviewed/approved/publishable until rendered official-page reconciliation, science review, Hindi semantic review, and exact visual approval are completed independently. Rendering tests passing is not content approval.  
- [ ] Implementation on main — commit SHA: `—`  
- [ ] Acceptance QA passed — evidence: `—`

## 8. Completion rule

A workstream is ready to close only when its relevant implementation and acceptance-QA checkboxes are checked with evidence. The eight question components, structured blocks, asset resolver and language/state contract must work in **both Web student and Android** before the structured lesson set is treated as integrated. Admin must use the same rendering primitives for preview and enforce review gates.

**Current status at tracker creation:** all implementation and QA items are unchecked. The registry and lessons are specifications/data; no structured renderer is assumed implemented by the existence of those files.
