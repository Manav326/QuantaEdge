# Structured Lesson UI Implementation Tracker

**Purpose:** Track the Web student, Web admin, and Android implementation required to render the new structured bilingual lesson content correctly. Use the checkboxes as the source of truth: mark an item `[x]` only after its acceptance checks pass on the relevant platform(s).

**Audit baseline:** `787d3dbadffc5a6d4da6c6ca786776e0aaf978dc`  
**Scope:** Class 6 Science structured lesson drafts, Chapters 1–6; registry contracts; existing lesson and practice UI.  
**Status vocabulary:** `NOT STARTED` = checkbox unchecked; `IN PROGRESS` = add a short note beside the item; `DONE` = checkbox checked and acceptance criteria verified.  
**Important:** A renderer contract in JSON or an existing generic input is not proof that the new structured type is integrated.

## 1. Audit findings — current state

### Eight registered structured question types

The registry in [`data/structured-lessons/question-rendering-registry.v1.json`](../../data/structured-lessons/question-rendering-registry.v1.json) explicitly marks all eight canonical types as **`SPECIFIED_NOT_IMPLEMENTED`**.

| Canonical type | Contract component | Current related UI found in repository | Required conclusion |
|---|---|---|---|
| `multiple_choice` | `MultipleChoiceQuestion` | Web already has option-button / MCQ rendering for its current question model. Android already has assessment UI. | **Partial / not wired to structured schema.** Integrate via canonical adapter and verify stable option IDs and scoring. |
| `multiple_select` | `MultipleSelectQuestion` | No verified structured multi-select contract in the existing practice path. | **Missing as a structured question type.** Add checkbox multi-select and exact-set grading. |
| `true_false` | `TrueFalseQuestion` | The existing `TRUE_FALSE` question model is recognised in Web question labels; Android already has an assessment model. | **Partial / not wired to structured schema.** Use a boolean response with locale-independent grading. |
| `matching` | `MatchingQuestion` | Web already accepts typed pair text for its current `MATCH` question model. Android localization includes a JSON-map help phrase. | **Partial, not equivalent to contract.** Implement a structured, keyboard/touch-accessible pair selector with partial credit. |
| `fill_blank` | `FillBlankQuestion` | A generic text input already exists for non-choice questions. | **Partial input only.** Add locale-specific accepted answers and the specified safe normalization policy. |
| `image_observation` | `ImageObservationQuestion` | Existing lesson pages can render image/diagram media and zoom/alt-text metadata. | **Partial media support, missing question integration and approval gate.** The image must be approved and student-renderable before the question is shown. |
| `short_answer` | `ShortAnswerQuestion` | Web already supports textarea for `SHORT_ANSWER`; Android already has answer and assessment flows. | **Partial input only.** Add bilingual answer guide/rubric, score range, and pending-review behavior if no validated grader exists. |
| `scenario_based` | `ScenarioQuestion` | Existing question prompts can contain prose, but a dedicated scenario/context renderer for the structured schema has not been verified. | **Missing as a structured type.** Render localized scenario context plus its declared response control. |

**Existing question models and the new structured schema are different data contracts.** Current Web data paths also use identifiers such as `MCQ`, `TRUE_FALSE`, `NUMERICAL`, `ORDER`, `MATCH`, `SHORT_ANSWER`, and `LONG_ANSWER`. The implementation must explicitly map these to canonical types where semantics match, or retain them as documented extensions. Do not silently reinterpret them or change scoring. Ordering and numerical input may remain additional supported types, versioned/documented separately from this eight-type registry.

### Lesson/content blocks and visuals

| Existing capability observed | Gap for the new structured data |
|---|---|
| Web `LearnClient.tsx` and Android `LessonContentBlock.kt` render existing explanation/prerequisite, worked example, guided/independent practice, hint/challenge, image/diagram/animation, video/audio, AI help and recap/summary blocks. | New JSON uses `explain.blocks[].type` values such as `rich_text`, `diagram`, `interactive`, and `recap`, with nested bilingual values. It does not directly match the existing `block_type` + stringified `content` data contract. |
| QuantaEdge already renders image/diagram/media blocks with captions, alt text, zoom/lightbox, source attribution and visual-kind labels such as diagram, chart, table, infographic and sequence. | The new lesson diagrams contain structured `diagram_data.nodes`; many have no image URL. Kind labels alone do not render node/edge relationships, process flows, tables or charts. |
| Admin Content Studio supports existing lesson-block authoring, student-style previews and a source-bound visual-candidate browser. | Admin needs a way to inspect and validate the structured lesson JSON itself, display canonical question-type and locale coverage, surface unsupported blocks, and enforce the visual-approval gate. Existing generic block preview is not a substitute. |
| A Web language provider and Android app language state already exist. | New data must use one selected learning locale consistently across question, visual labels/alt text, feedback, activity controls and status messages. Switching locale must preserve the learner state defined by the registry. |
| Mathematical-text display helpers exist (`MathText` on Web and `LearningMathText` on Android). | Keep them reusable in structured rich text and questions; add test coverage for structured text/inline formula content rather than assuming compatibility. |

## 2. Implementation tracker

Mark each task complete only after the acceptance checks pass. Keep Web and Android parity explicit; a component implemented on one surface does not finish the cross-platform task.

### A. Shared contract, data loading and safety gates — P0

- [ ] **SL-01 — Canonical structured-lesson adapter** *(Shared data contract; Web + Android consumers)*  
  **Context:** Generated lesson JSON has a different contract from the existing API's `blocks[]` / database `question_type` payloads.  
  **Implement:** Define typed models and one explicit versioned adapter for `chapter`, `topic`, `learning_objectives`, `source_refs`, `explain.blocks`, `practice.questions`, rubrics, bilingual labels and visuals. Map `rich_text → explanation`, `diagram → structured visual`, `interactive → activity`, `recap → recap`; do not flatten away fields.  
  **Accept when:** Valid Chapters 1–6 JSON can be parsed; missing required fields, unknown types and unsupported schema versions return explicit validation errors; no content is silently dropped.

- [ ] **SL-02 — Canonical question-type registry wired into runtime** *(Web + Android)*  
  **Context:** The registry is a specification only; its eight types all currently say `SPECIFIED_NOT_IMPLEMENTED`.  
  **Implement:** Type-safe dispatch on canonical `question.type`; validate `rendering_contract_id` against the registry before rendering. Keep existing question identifiers/extensions in a separately documented mapping.  
  **Accept when:** Each canonical type routes to exactly one renderer; a missing or mismatched contract fails closed with a developer-visible diagnostic and learner-safe unavailable state.

- [ ] **SL-03 — One locale source, state-preserving language switch** *(Web + Android)*  
  **Context:** Existing language selectors/providers exist, but structured data has additional localized fields beyond those exposed by the existing APIs.  
  **Implement:** Read one global learning locale (`en` or `hi`) for every structured learner-facing field; update displayed text immediately without resetting responses or changing grading IDs. Never fallback silently to the other locale.  
  **Accept when:** Switching language preserves active question ID/index, selections, boolean, matching pairs, typed response, submission state, score, completion progress and lesson reading position on both platforms. Any missing translation blocks approval/rendering and is logged clearly.

- [ ] **SL-04 — Validate structured content before preview/publish** *(Shared validator; Admin uses it)*  
  **Implement:** Validate unique stable IDs; required `en` and `hi` fields; objective references; correct option/pair references; type/contract match; answer/rubric shape; source references; activity configuration; visual alt text and approval state.  
  **Accept when:** Unit tests cover malformed JSON and every known failure category; admin can see actionable field-level errors; student endpoints cannot leak invalid or unapproved records.

- [ ] **SL-05 — Keep response, grading and presentation separate** *(Web + Android + API integration)*  
  **Implement:** Store responses by stable question/option IDs, booleans, pairs or typed text—not localized labels. Store scoring/status separately from localized feedback. Respect each question's declared grading contract.  
  **Accept when:** English and Hindi produce identical correctness and score for the same stable response; locale changes never rewrite answers or scoring metadata.

### B. Question UI components — P0/P1

- [ ] **SL-06 — Multiple-choice renderer** *(Web student + Android)*  
  **Implement:** Accessible single-select radio/choice group; localized prompt/options/instructions; selected/submitted/correct/incorrect states; explicit submit behavior per contract.  
  **Accept when:** Exactly one stable option ID can be selected; invalid answer IDs are rejected; results include localized feedback; keyboard, touch and screen readers can operate it.

- [ ] **SL-07 — Multiple-select renderer** *(Web student + Android)*  
  **Implement:** Accessible checkbox group with multiple selections and set-based grading.  
  **Accept when:** Zero, one and multiple choices are representable; only the exact declared answer-ID set receives credit under the current contract; selection persists through language change.

- [ ] **SL-08 — True/false renderer** *(Web student + Android)*  
  **Implement:** Two equally prominent choices bound to a boolean value; localized labels.  
  **Accept when:** `false` is not confused with missing/unanswered; correct grading is boolean equality; no colour-only success/error signal.

- [ ] **SL-09 — Matching renderer** *(Web student + Android)*  
  **Implement:** Two labelled columns. Each left item gets an accessible select/list choice for a right item; drag-and-drop may be optional but never the only input.  
  **Accept when:** Stable pairs serialize as `left_id/right_id`; all references validate; pairwise scoring and partial credit work; the layout remains usable on narrow screens.

- [ ] **SL-10 — Fill-in-the-blank renderer and grader** *(Web student + Android; API/domain tests)*  
  **Implement:** Localized text input; per-locale accepted answer lists; Unicode NFC, whitespace trimming/collapse, and English case-insensitive matching. Hindi grading must not strip meaningful matras, transliterate, or silently translate.  
  **Accept when:** Accepted/rejected examples pass for both scripts; empty input is distinct from an incorrect answer; active locale does not change the expected answer set unexpectedly.

- [ ] **SL-11 — Image-observation question renderer** *(Web student + Android)*  
  **Implement:** Approved image/diagram panel, caption, bilingual alt text, optional zoom, then the explicitly declared answer control. Apply the visual gate before rendering the question.  
  **Accept when:** Missing, draft, rejected, rights-uncleared where relevant, or non-student-renderable assets cannot appear to learners; approved assets are accessible and associated with the exact topic/question.

- [ ] **SL-12 — Short-answer renderer and review lifecycle** *(Web student + Android + review/API integration)*  
  **Implement:** Accessible multiline text area, localized guidance, key points/sample answer and explicit rubric/max score. If no validated grader is configured, save the response and display localized pending-review state; do not invent automatic grading.  
  **Accept when:** Text survives navigation and language changes; rubric scoring supports the declared range; pending responses are not falsely marked correct/incorrect; reviewer identity/outcome can be displayed when available.

- [ ] **SL-13 — Scenario-based renderer** *(Web student + Android)*  
  **Implement:** Show the localized scenario/context as a distinct readable block, then render the response control declared by the question contract (currently choice-based for authored records).  
  **Accept when:** Scenario and prompt both switch language; option IDs and grading remain stable; context is not lost on narrow screens or when the question is revisited.

- [ ] **SL-14 — Explicit handling for additional numerical, ordering and long-answer question types** *(Web + Android)*  
  **Context:** Existing data includes `NUMERICAL`, `ORDER`, `SHORT_ANSWER` and `LONG_ANSWER`, which are not all in the current eight-type registry.  
  **Implement:** Document whether each type maps to a canonical type or remains an extension; preserve existing behavior while sharing components where semantics truly match.  
  **Accept when:** A regression matrix covers existing question records and canonical structured JSON; no supported type is silently rendered as an unrelated input or graded by a changed rule.

### C. Teaching blocks, visuals and activities — P0/P1

- [ ] **SL-15 — Structured explanation / rich-text block** *(Web student + Android)*  
  **Implement:** Render localized heading, ordered paragraphs, key point and inline maths/text safely from the new `rich_text` schema; do not evaluate authored HTML or scripts.  
  **Accept when:** Paragraph order, emphasis where supported and inline maths are correct; both locales display without leaking the opposite language; malformed markup is safely handled.

- [ ] **SL-16 — Structured diagram renderer** *(Web student + Android)*  
  **Context:** Generated lessons contain `diagram_data.nodes` and visual briefs; many do not yet have a rendered asset URL. Existing media components primarily display a supplied image/video URL.  
  **Implement:** Render structured nodes/relations or a reviewed generated asset. Support process/sequence, comparison, labels and arrows as represented by the schema; include responsive layout and a meaningful text alternative.  
  **Accept when:** All diagram data fields have a defined rendering path; no empty placeholder is passed off as a finished diagram; labels remain legible in Hindi and English; screen readers receive a useful description.

- [ ] **SL-17 — Visual-kind renderer coverage** *(Web student + Android; Admin preview)*  
  **Implement:** Explicitly support approved `PHOTO/ILLUSTRATION/DIAGRAM/CHART/TABLE/INFOGRAPHIC/SEQUENCE` visuals. Use a native structured rendering path for data-backed charts/tables/sequences when an image is not supplied.  
  **Accept when:** Each kind has at least one representative fixture on all three surfaces; image-only fallback does not hide structured data; caption, alt text, source/rights metadata and locale are handled consistently.

- [ ] **SL-18 — Interactive activity block** *(Web student + Android)*  
  **Context:** Generated lessons use `type: interactive` with `activity_kind`, configuration, steps and response storage rules. The existing `GUIDED_PRACTICE` or `INDEPENDENT_PRACTICE` text blocks do not by themselves implement these activity semantics.  
  **Implement:** Add an activity renderer/registry and a safe generic fallback only for explicitly supported activity kinds. Start with the activities in Chapters 1–6; do not show inert controls that appear to work but do not save results.  
  **Accept when:** Inputs, steps, reset/save actions and result state function end-to-end; learner response persists as declared and survives language changes; unavailable activity kinds fail visibly rather than silently disappearing.

- [ ] **SL-19 — Worked example, guided practice, hint and recap parity** *(Web student + Android)*  
  **Implement:** Make new-schema equivalents render consistently with existing content blocks; localize headings and body/key point data; keep recap after practice according to lesson order.  
  **Accept when:** Same lesson fixtures display equivalent content and order on Web and Android; no duplicate recap or lost key point occurs.

- [ ] **SL-20 — Visual approval and student-renderability gate** *(Admin + API + Web student + Android)*  
  **Implement:** Treat source-derived visual candidates, generated structured diagrams and reviewed student assets as distinct states. An image-observation question stays blocked until its asset is approved, topic-matched, alt-text-complete and student-renderable.  
  **Accept when:** Admin can see/block/publish the gate state; both student platforms and API enforce it even if a crafted URL or direct question ID is requested.

### D. Admin workflow, preview and governance — P1

- [ ] **SL-21 — Structured lesson inspector in Admin** *(Web admin)*  
  **Implement:** Add a read-only/controlled editor view for chapter/topic metadata, bilingual objectives, source references, ordered Explain blocks, questions, correct-answer IDs, rubric and visual gates from the structured schema. Keep the existing Content Studio workflow intact.  
  **Accept when:** Admin can open any Chapter 1–6 structured draft, inspect all fields and see validation errors without hand-editing raw JSON or changing production content accidentally.

- [ ] **SL-22 — Student preview using the same renderers** *(Web admin → Web student renderer; mirror on Android test fixtures)*  
  **Implement:** Use shared Web renderer components in preview, and use the same fixtures/contracts in Android. Include a locale toggle and gated assets.  
  **Accept when:** Preview matches learner rendering for layout, localization, response control and feedback; draft/review-only visual questions are visibly blocked.

- [ ] **SL-23 — Admin validation and approval checklist** *(Web admin)*  
  **Implement:** Show locale completeness, unsupported question/block types, bad answer references, objective/source-reference errors, visual gate state and renderer support state. Separate structure-valid, source-reviewed, bilingual-reviewed, science-reviewed and publishable statuses.  
  **Accept when:** Structural pass alone never labels content approved/publishable; each blocker names the field and remediation.

- [ ] **SL-24 — Source traceability for text and visuals** *(Web admin + lesson detail)*  
  **Implement:** Display source book/edition/page, candidate ID, visual review state and attribution/rights metadata where applicable. Keep original generated visuals distinct from NCERT source-page previews.  
  **Accept when:** Every source-bound visual can be traced to its manifest entry; a source candidate for another class/subject/book cannot attach; missing source details are explicit.

### E. Cross-platform quality and regression tests — P0

- [ ] **SL-25 — Shared fixtures and contract tests** *(Web + Android + validators)*  
  **Implement:** Create a fixture for every canonical question type, every structured block type, both locales, pending-review short answers, invalid references and each visual gate state.  
  **Accept when:** Test suite proves correct dispatch, response serialization, grading contract, missing-locale failure, visual blocking and accessibility labels.

- [ ] **SL-26 — End-to-end Web/Android parity test** *(Web student + Admin preview + Android)*  
  **Implement:** Run the same lesson/question fixtures through each platform.  
  **Accept when:** Active question, answer IDs, submitted state, score, progress, localized feedback, lesson block order and visual eligibility agree; differences are documented only where platform capabilities genuinely differ.

- [ ] **SL-27 — Keyboard, screen-reader, touch and responsive QA** *(Web student + Admin preview + Android)*  
  **Implement:** Check focus order and visible focus, labels, announcement of answer result, non-colour status, large text, narrow screens and Android touch sizes. Matching must not depend on dragging.  
  **Accept when:** All eight question types and visual/activity blocks pass manual and automated accessibility checks on supported screen sizes.

- [ ] **SL-28 — Performance and learner-state preservation** *(Web student + Android)*  
  **Implement:** Keep response/session state by stable question ID; avoid reloading unrelated lesson components on a locale change; load heavy visuals lazily and limit animation/media work.  
  **Accept when:** Switching language does not restart the page/session, erase input, reset scoring or move reading position; repeated renders do not duplicate network submissions.

## 3. Definition of Done

A task can be marked complete only when the relevant code is committed and the evidence below exists.

- [ ] Runtime uses the structured schema and typed registry—not hand-coded per-chapter exceptions.
- [ ] Each canonical question type and each structured block has a working Web and Android renderer or an explicitly documented supported fallback.
- [ ] Admin can inspect, preview and validate the same content and knows why an asset/question is blocked.
- [ ] Locale switching preserves response/session state and never silently falls back to untranslated text.
- [ ] Automated contract tests and cross-platform acceptance tests pass.
- [ ] Draft, reviewed, approved and publishable are distinct statuses; unresolved source/translation/science/visual gates block publication.
- [ ] This tracker is updated with the implementation commit/PR and test result for every completed task.

## 4. Suggested implementation order

1. **SL-01–SL-05:** data adapter, validation, registry dispatch, locale and grading/state foundations.
2. **SL-06–SL-14:** canonical question components and explicit mapping for existing question models.
3. **SL-15–SL-20:** rich content, structured visuals, activities and visual safety gate.
4. **SL-21–SL-24:** Admin inspector, preview and governance.
5. **SL-25–SL-28:** shared tests, parity, accessibility and performance; then mark tasks done from evidence.

**Do not start with styling alone.** First connect the structured schema to the actual runtime, then implement each component against its declared contract. This avoids polished controls that cannot save, grade, localize or preserve a learner's answer.

## 5. Active resume checkpoint — 2026-10-16

**Status: in progress; no SL task is marked complete by this checkpoint.**

- Verified `main` baseline: `54474d101b842dae89b1b2b6f1ca12c534871975`; its latest CI run succeeded. This does not validate staged UI source.
- The existing Web renderer/Admin preview files remain in staged tree objects, not on `main`.
- Added the additive native Compose renderer `apps/android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonRenderer.kt`, blob `d82693d5793a9c4f56c46fc5305823dd99bb22d8`.
- The renderer covers bilingual lesson metadata, rich text/recap, structured diagram data, canonical question controls, stable-ID response payloads, activity callbacks, and fail-closed visual/publication gates. It deliberately does not expose answer keys or claim persistence/grading success.
- **Not yet wired or build-tested.** The existing API lesson DTO returns legacy `blocks` and database-backed `questions`. The current answer endpoint accepts numeric database question IDs and evaluates stored database answer payloads; structured artifact IDs cannot be passed through unchanged. Preserve `strip_answer_keys(...)` in the public lesson path.
- Next: design and implement a backward-compatible server-side structured artifact adapter and response persistence contract with publication/visual checks and answer-key isolation. Only then wire the renderer into the student route and verify web/admin/Android builds and regression tests.
- All UI tasks remain unchecked until acceptance evidence is available. No commit or branch-ref move was made for this staged checkpoint.

## 6. Staged code review — 2026-10-16

A focused review of the staged Web/Admin/Android source found and fixed these UI issues before CI:
- Activity blocks no longer claim a response was saved when the screen has no persistence callback. The save control is disabled and the missing adapter is shown clearly.
- Structured diagram edges now show localized endpoint labels from the node IDs instead of displaying raw IDs or inventing arrows between sequential nodes.
- Web/Admin accept both explicit diagram review states already accepted by the delivery helper: `APPROVED` and `APPROVED_FOR_STUDENT_RENDERING`.
- Android structured lesson rendering now requires both workflow status `PUBLISHED` and `publication_allowed=true`; learner-mode data containing author-only answer keys is blocked.
- Android image-observation questions resolve `visual_asset_ref` against lesson blocks, require the approval gate, render the associated image/diagram, use the declared option-based control, and serialize the selected stable option ID.
- Android question submission is disabled until the response is complete; activity instruction/step data supports string and structured localized shapes.

**Still not complete:** no structured artifact API adapter or answer persistence/grading endpoint has been added; Android renderer is not wired to an API payload; Web learner submit adapter is absent. These staged components intentionally fail closed rather than using the legacy database question IDs incorrectly.

**Do not mark tasks SL-01–SL-28 complete yet.** No local build could be run in this session; the next verification is the existing GitHub Actions build matrix after the staged UI changes are committed on `main`.

## 7. UI interaction hardening — 2026-10-16

- Updated the shared Web/Admin structured question experience to guard the submit action while a response is in flight.
- The question submit control is disabled during submission and shows a localized progress label (English/Hindi); response editing is also paused until the request resolves.
- The guard is released in `finally`, including on endpoint errors, so the learner can retry after a failed request. This prevents accidental duplicate requests from fast repeated clicks without falsely claiming the response was saved.
- Web learner and Admin preview share the same renderer files by Git blob, so this interaction stays visually and behaviorally aligned.
- **Acceptance remains in progress:** this is a narrow UI hardening fix, not evidence of end-to-end structured artifact API/persistence. No SL task is marked complete. Verify the repository's CI for this commit.
