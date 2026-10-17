# Structured Lesson Rendering Audit — Class 6 Science, Chapters 1–6

**Audit date:** 2026-10-16  
**Repository baseline:** Manav326/QuantaEdge, main at 724f07501eeac2539dc89d031a9d10bd0b23e787  
**Scope:** 39 structured lesson JSON files in Chapters 1–6; production index; question-rendering registry; V3 visual-candidate catalog/workflow; current Web and Android lesson/practice renderers.  
**Change scope:** documentation only. No runtime code, lesson JSON, or publishing state is changed by this audit document.

## 1. Executive summary

The Class 6 Science draft set contains **39 lesson topics and 312 questions**. Every topic uses the same eight structured question types, one question per type. The question-rendering registry defines contracts for all eight, but it explicitly records their implementation status as **SPECIFIED_NOT_IMPLEMENTED**.

QuantaEdge already has legacy Web and Android learning/practice UI components. They consume a different, older database-backed content shape (block_type, numeric IDs, serialized content, legacy question_type values). No adapter or runtime path was found that maps the new data/structured-lessons schema into those legacy records or dispatches its rendering_contract_id values to dedicated renderers. Therefore, the prepared JSON should not be treated as rendering-ready merely because the JSON is structurally paired and the registry exists.

The V3 visual workflow prepares reviewable source-image/page candidates and attachment manifests. It does **not** create final approved student visuals for every structured lesson, implement the node/edge diagram renderer, or automatically bind a reviewed V3 candidate to each lesson/question. Candidate metadata remains non-publishable until reviewed. In the inspected Class 6 lesson set, all 39 image-observation questions and all original structured diagrams are blocked or pending visual review.

**Recommendation:** implement a schema adapter and typed rendering layer shared in behavior across Web and Android. Keep existing legacy question formats working while the new structured format is integrated additively. Do not render gated assets or enable publication until visual, language, answer, accessibility, and source-review gates pass.

## 2. Prepared content inventory

The production index reports the following prepared Class 6 Science inventory:

| Chapter | Draft topics | Practice questions |
|---:|---:|---:|
| 1 — The Wonderful World of Science | 3 | 24 |
| 2 — Diversity in the Living World | 7 | 56 |
| 3 — Mindful Eating: A Path to a Healthy Body | 8 | 64 |
| 4 — Exploring Magnets | 5 | 40 |
| 5 — Measurement of Length and Motion | 7 | 56 |
| 6 — Materials Around Us | 9 | 72 |
| **Total** | **39** | **312** |

Each topic contributes one question of every structured type. Total counts are 39 for each of the eight types. The source/production index records zero topics reviewed, zero bilingual-approved, and zero publishable. This is a draft inventory, not source-completeness approval.

### Eight structured question types

| Data enum | Contract / intended component | Current QuantaEdge UI reconciliation |
|---|---|---|
| **multiple_choice** | qtype-multiple-choice.v1 — MultipleChoiceQuestion | **Partial legacy analogue.** Web has single-choice option buttons for old MCQ records, but no structured JSON adapter, stable-ID response model, or contract-driven dispatch. Android has legacy generic practice/test question cards but no new-contract integration was found. |
| **multiple_select** | qtype-multiple-select.v1 — MultipleSelectQuestion | **Missing as a structured component.** Need an accessible checkbox group, stable set-of-option-IDs response, exact-set grading, and Web/Android parity. The current Web legacy renderer renders option lists as single-select buttons. |
| **true_false** | qtype-true-false.v1 — TrueFalseQuestion | **Partial legacy analogue only.** Web labels old TRUE_FALSE records, but the new contract uses a boolean correct_answer. No adapter from boolean data to a two-choice accessible control was found. |
| **matching** | qtype-matching.v1 — MatchingQuestion | **Partial legacy analogue only.** Web has a legacy MATCH input based on a text pattern such as 1:A, 2:B. The new contract uses stable IDs, two item lists, explicit correct pairs, and pair-wise partial credit. That contract is not wired. |
| **fill_blank** | qtype-fill-blank.v1 — FillBlankQuestion | **Missing as a dedicated component.** A generic text input exists, but not the locale-specific accepted-answer lists, prescribed Unicode/whitespace normalization, stable response model, and contract-driven grading. |
| **image_observation** | qtype-image-observation.v1 — ImageObservationQuestion | **Missing as a gated structured-question component.** Legacy lesson media can display a URL, but no renderer was found that resolves a question’s visual_asset_ref, checks approval/student-renderability, shows localized alt/caption, and then renders the declared response subtype. All 39 generated items remain blocked. |
| **short_answer** | qtype-short-answer.v1 — ShortAnswerQuestion | **Partial legacy analogue.** Web and Android have free-text answer entry, but the structured answer guide, bilingual rubric, configured scoring limits, and explicit pending-review behavior are not integrated with the new schema. Do not assume AI grading is available; the contract requires a validated grader or a pending-review workflow. |
| **scenario_based** | qtype-scenario-based.v1 — ScenarioQuestion | **Missing as a dedicated component.** Needs a separate scenario/context card, a distinct question prompt, and the explicitly declared response subtype (currently choice-based in v1). Existing UI generally treats the prompt as a single text string. |

### Existing legacy question types in the Web practice UI

The current Web PracticeClient.tsx explicitly branches on legacy values MCQ, TRUE_FALSE, NUMERICAL, ORDER, and MATCH; it also uses a multiline text-area fallback and gives LONG_ANSWER a larger text area. These are not the same wire contract as the eight new structured enums.

- MCQ, TRUE_FALSE, and MATCH are rough conceptual overlaps with structured types, not compatible serialized contracts.
- NUMERICAL, ORDER, and LONG_ANSWER are additional legacy UI modes not defined as types in the new structured-question registry.
- Do not silently delete or remap these legacy types. Keep them behind the legacy adapter, or formally add/version equivalent contracts if structured lessons need them later.

## 3. Explain block and activity component audit

The structured lessons use these block types:

| Structured block type | Content shape and required behavior | Current implementation status |
|---|---|---|
| **rich_text** | text_by_locale.en/hi with heading, paragraph list and key point. Render localized content, preserve paragraph order, support the approved text/math subset, and never execute arbitrary HTML from lesson data. | **Partial legacy analogue.** Web/Android support old EXPLANATION/PREREQUISITE content fields. They expect a different block_type + content object shape, not the new lowercase type + text_by_locale fields. Add an adapter and a structured text renderer. |
| **image** | Source-bound media block. In Chapter 1 Topic 1 it contains asset_refs[], an asset object, source book/page/hash, url, localized alt/caption, review/rights status, and an explicit editor-only render policy. | **Partial legacy analogue.** Both platforms can display URL-backed images in the old media shape. A structured asset resolver is needed; the V3 candidate metadata shape and render policy cannot be dropped when adapting. |
| **diagram** | Original diagram specification in diagram_data, including layout, nodes, edges and localized node/edge labels; also has localized alt text/caption and review state. | **Missing as a data-driven renderer.** Legacy components can show a pre-rendered image URL, but no Web/Android renderer was found for the structured node/edge model. These lesson diagrams are marked non-student-renderable. |
| **activity** | Earlier lesson version: activity kind, localized instructions, response fields, formative feedback and safety note. | **Not integrated with the new lesson source.** It is not the same shape as interactive.configuration. |
| **interactive** | Newer lesson version: activity kind, configuration, localized instructions/steps/controls, answer storage contract and safety/scope note. | **Missing as a generic configurable activity runner.** Existing GUIDED_PRACTICE/INDEPENDENT_PRACTICE blocks mostly display content; they do not execute this structured form contract. |
| **recap** | content_by_locale.en/hi, with one concise recap per topic. | **Partial legacy analogue.** Legacy Web/Android recaps usually expect SUMMARY/RECAP blocks containing points[]. Add a normalizer or renderer that handles content_by_locale as well as the old points[] schema. |

The Chapter 1 records use some older variants and extra blocks (image, activity), while Chapters 2–6 generally use two rich_text blocks, a diagram, an activity/interactive block, and recap. A discriminated schema/adapter is required; assuming every chapter has the exact same block payload would be brittle.

## 4. Visual candidates V3 — what exists and what it does not mean

The relevant workflow is **Prepare textbook visual candidates v3 (additive)**. It prepares candidate packs, candidate metadata, contact sheets, source selection/manifests, a chapter map and immutable GHCR bundles. It is an upstream source-asset preparation step, not a lesson renderer or a content approval process.

Observed Class 6 V3 catalog:
- Source: **English Curiosity only** (ncert-c6-english-fecu1), Reprint 2026–27.
- 60 selected candidates: 24 embedded images and 36 whole-page previews, across 54 distinct source PDF pages.
- Inventory summary: 2,078 asset records, 1,200 embedded-image hash groups, 272 page previews, zero missing inventory asset files.
- Every selected candidate is marked publication_allowed: false and requires human review.
- The whole-page preview has an explicit editor-only policy; it must never be shown as student content, in either language, merely because it has a URL or a translated alt description.
- The V3 catalog index currently lists 13 catalogs: one Class 6 English Science catalog and twelve Class 7/8 catalogs (six source books per class). Candidate index summary is 2,460 candidates, all review-required and non-publishable.

### Reconciliation with the structured lessons

- The 39 topic JSONs have original structured diagram drafts and 39 image_observation questions, but these are not V3 candidate assets. The structured diagrams have node/edge data; the V3 catalog has source image/page candidates. These two content types need different render paths.
- The 39 inspected topics contain a V3 candidate reference only in **Chapter 1 Topic 1**: candidate v3-page-0020-fa1415dfc5. Its image block has the matching asset_refs[] and asset.asset_id, so the reference is present in the block data. The candidate is a **whole English page preview**, has publication_allowed: false, and has an editor-only render policy. The corresponding question is correctly BLOCKED_FROM_STUDENT_RENDERING.
- No V3 candidate IDs were found linked from the other 38 topic lesson JSONs. They cannot be assumed to be using or rendering V3 assets.
- The V3 catalog index has no Class 6 Hindi Science catalog. The current structured lessons contain Hindi text, but the Class 6 V3 candidate set is not an equivalent, approved Hindi visual set.
- The Web/Android lesson renderers can display URL-backed image/media in their older block shape. They do not turn diagram_data.nodes/edges into diagrams or resolve the structured question gate based on the V3 review/rights metadata.
- A source-page/candidate image can be relevant evidence for authors without being appropriate as a student visual. For each student asset, the app needs a reviewed attachment relationship to the exact lesson/question, a confirmed source hash/book/page, allowed publication status, localized or language-independent alt/caption, and the student-renderability gate.

V3 visual category taxonomy (media types, **not question types**): PHOTO, ILLUSTRATION, DIAGRAM, CHART, TABLE, INFOGRAPHIC, SEQUENCE, TEXTBOOK_FIGURE. Web/Android currently have generic image/media display and some visual-kind labels for old URL-backed blocks. A label alone is not a renderer for structured charts, semantic tables, process sequences, or node/edge diagrams. Use specialized rendering where the underlying data is structured; use a responsive, accessible, zoomable image for reviewed image assets.

## 5. Data-quality and readiness findings

### Structural checks performed
The 39 topic files were reviewed in six batches. Each topic has eight questions and one of every registered question type. In the fields checked, rendering-contract IDs match the registry; option IDs and matching pairs reference existing IDs; localized prompts/options and short-answer guides/rubrics are populated; and image-observation items remain blocked.

### Issues to handle before enabling renderers
1. **Short-answer feedback consistency:** the ten Chapter 1–2 short_answer items use localized answer guides and rubrics but omit feedback_by_locale. The registry’s short-answer required-field list does not explicitly require that field, so this is a consistency/authoring decision rather than a proven contract-breaking error. Decide whether feedback is optional when a localized rubric/answer guide exists, or add localized feedback consistently.
2. **No Chapter 1 source-coverage ledger:** Chapters 2–6 have source-coverage.v1.json; Chapter 1 does not. Add the same coverage ledger/audit gates for consistency.
3. **Source review is still pending:** the production index shows zero reviewed, bilingual-approved, or publishable topics. The Chapter 2–6 ledgers explicitly retain rendered PDF/page, figure/table/activity and human-review tasks. Do not equate a topic inventory or generated visual with source completeness.
4. **Visual approval is a separate gate:** every diagram is currently draft/non-student-renderable, and all 39 image-observation questions are blocked. Keep those gates enforced until visual review approves the exact asset.
5. **Global language switching:** the registry requires a single global app locale to update prompts, scenarios, options, labels, answer guides, rubrics, feedback, visual alt/captions and shared controls together, while preserving selected/typed answer, submitted state, score, current question and progress. This contract is documented, not implemented for the structured data.
6. **Data loading/adapter gap:** the Web lesson and practice clients consume database-backed records with block_type, numeric IDs and JSON-string content/options. The structured JSON has its own schema/version, stable string IDs, type, locale dictionaries, diagram_data, visual_asset_ref and explicit grading data. No adapter that makes the two formats interchangeable was found.

These checks are schema-level checks, not scientific approval, translation approval, a rendered-page source audit, runtime QA, or publication approval.

## 6. Recommended target architecture

Implement an additive structured-content path; retain the existing legacy UI/API path until parity tests pass.

### A. Loader, adapter and validation
- Add a typed StructuredLesson model matching schema version quantaedge.structured-lesson.v1.
- Load the canonical JSON by class/subject/chapter/topic and validate it before it reaches a student screen.
- Normalize the two activity block variants (activity and interactive) through a versioned adapter without discarding response, feedback or safety fields.
- Keep legacy block_type and legacy question_type handling separate; do not coerce structured records into old database IDs/stringified options with lossy mappings.
- Add CI validation for schema version, stable/unique IDs, supported type/contract pairs, en/hi completeness, answer/matching references, grading shape, activity response fields, visual references and publication gates.

### B. Question renderer registry on both platforms
Implement a renderer registry matching question-rendering-registry.v1.json on Web and Android. Each renderer gets a typed payload and returns the same logical answer shape/stable IDs on both platforms.

- Multiple choice: accessible radio group/single-select; exact option-ID grading.
- Multiple select: checkboxes; unordered set of option IDs; exact-set grading.
- True/false: two clear choices; boolean grading; never rely on colour alone.
- Matching: two columns on wide screens; stacked left-item rows with an accessible selector on narrow screens; grade each pair and support partial credit.
- Fill blank: localized single-line input; apply only prescribed locale-specific normalization, without transliterating or silently translating student text.
- Image observation: resolve and validate an approved visual first, render caption/alt/zoom where appropriate, then use the declared response subtype. If the visual is not approved, do not expose the question to students.
- Short answer: multiline input, answer guide and rubric; use a validated grader if configured, otherwise save the answer and show a localized pending-review state.
- Scenario: distinct scenario card followed by the question and its declared response subtype; both switch with the app locale.

### C. Structured lesson block renderers
- RichTextBlock: localized heading, ordered paragraph list, key point and safe supported inline formatting/math.
- VisualAssetResolver + ImageBlock: resolve asset_refs/asset.asset_id and question visual_asset_ref against a reviewed catalog/attachment manifest; verify book/hash/page, status, rights and locale before display.
- StructuredDiagramRenderer: render diagram_data.layout, nodes, edges, and localized labels; include a static accessible fallback, alt text and caption. Do not require pre-rendered URLs for diagrams that are already represented as node/edge data.
- ActivityRenderer: support declared activity kind, configuration/response fields, instructions, steps, feedback, save/reset, progress persistence and safety note. Include both legacy activity and newer interactive schema versions through an explicit adapter.
- RecapRenderer: support localized recap content and legacy points[] without duplicate recap cards.
- Keep the existing media player/lightbox for approved URL-backed image/video/audio where appropriate. Do not use a full-page source preview to satisfy an image-observation question’s student visual requirement.

### D. Language, state, accessibility and responsiveness
- One global locale must control all visible question/block/visual/control/error/status text; no mixed-language or silent fallback.
- Keep answer state keyed by stable question ID and option IDs; locale changes must not reset selection, typed text, current question, submitted state, score, progress or reading position.
- Accessible semantics: labelled inputs, keyboard navigation, screen-reader labels, visible correctness text in addition to colour, alt text and caption, adequate contrast, scalable text, touch targets and responsive matching layouts.
- Large interactions must update only their owning component/state; do not reload the whole lesson for a selection change.

## 7. Definition of done for the Web/Android implementation

1. All 8 registry types render end-to-end from the actual structured JSON in Web and Android.
2. Both platforms show equivalent prompts, options, diagrams, activity steps, feedback and scoring for each shared fixture.
3. The current lesson loader handles rich_text, image, diagram, activity, interactive and recap through the structured schema; old database records continue to work through their legacy adapter.
4. Tests cover all eight question types, all six block types, legacy block/activity variants, matching partial credit, locale-specific fill-blank grading, short-answer pending review, missing-locale blocking and stable answer-state preservation during language switches.
5. Every visual_asset_ref resolves to a real, reviewed lesson asset or remains blocked. No candidate with publication_allowed: false, source-only rights, unresolved language, or editor-only page preview can render to students.
6. Every visual kind needed by approved content has a defined rendering strategy: responsive photo/illustration/diagram asset; accessible chart/graph; semantic table; infographic image with zoom/alt; sequence/process with ordered-step fallback; and quarantined unclassified textbook figures.
7. A rendered-page reconciliation and human content review is done separately from renderer QA. The topic index only moves from draft to reviewed/approved/publishable when those separate checks pass.

## 8. Repository references

- Production index: data/structured-lessons/class-6/science/production-index.v1.json
- Question contracts: data/structured-lessons/question-rendering-registry.v1.json and data/structured-lessons/question-rendering-registry.v1.md
- Lesson data: data/structured-lessons/class-6/science/chapter-01/ through chapter-06/
- Chapter coverage audits: chapter-02/source-coverage.v1.json through chapter-06/source-coverage.v1.json
- V3 workflow: .github/workflows/textbook-visual-candidates-v3.yml
- V3 catalog index: data/textbook-visual-candidates-v3/index.v1.json
- Class 6 V3 candidate catalog: data/textbook-visual-candidates-v3/catalogs/class6-en-science-9c1315ba.v3.json
- Class 6 V3 candidate pack and chapter map: data/textbook-visual-candidates-v3/class6-en-science-9c1315ba/
- Web lesson renderer: apps/web/app/student/learn/LearnClient.tsx
- Web legacy practice renderer: apps/web/app/student/practice/PracticeClient.tsx
- Android legacy lesson block renderer: apps/android/app/src/main/java/com/quantaedge/android/ui/LessonContentBlock.kt
- Android legacy learning/practice/test flows: apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt

## Audit conclusion

The content authorship format is useful and the eight question types are clearly specified. The remaining task is a real integration layer, not another question-type brainstorm: **load the structured lesson schema, resolve reviewed visual assets, render each block and question type consistently on Web and Android, preserve state across global language changes, and enforce the review gates.** Do not mark the 39 topics publishable on the basis of structural validation alone.
