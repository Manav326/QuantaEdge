# Question Rendering Registry — v1

**Status: SPECIFIED, NOT IMPLEMENTED.** This is the shared content-to-UI contract for the student web and Android apps. A JSON lesson is not enough by itself: every `question.type` below requires a known renderer component, a response format, grading behavior, translations, accessibility behavior and QA coverage.

**Machine-readable contract:** [`question-rendering-registry.v1.json`](question-rendering-registry.v1.json)  
**Active production ledger:** [Class 6 Science production index](class-6/science/production-index.v1.json)  
**First lesson using these types:** [Chapter 1, Topic 1 — Science Begins with Curiosity](class-6/science/chapter-01/topic-01-science-begins-with-curiosity.v1.json)

## Non-negotiable runtime rules

- The global app language setting (`en` or `hi`) controls all learner-visible content. There is no separate question-language selector.
- Switching language updates prompts, scenarios, options, labels, hints, feedback, correct-answer explanations, rubrics, helper/error text and shared controls together.
- A switch must preserve the active question, selected answers, typed response, submitted state, score and practice progress.
- The answer model uses stable IDs or typed values, **not translated labels**. Translation must never change the correct answer or scoring.
- No mixed-language screen and no silent fallback to the other language are permitted. Missing locale content blocks approval/publication.
- Generated lesson data must not run arbitrary HTML, JavaScript or other executable markup.
- A referenced image/diagram must be approved for student rendering and appropriate for the selected language. A review-only English textbook page is not a learner visual in Hindi mode.
- The registry describes expected behavior. The UI component code and runtime language switching remain separate implementation tasks.

## Shared UI architecture

### `PracticeSession`

Own the question sequence and the answer map keyed by question ID. Store current question, selected/typed responses, submission/result state, score and progress. A language change may change only the displayed strings; it must not reset session state.

### `PracticeQuestionRenderer`

Dispatch by the exact `question.type` value. The renderer should validate the required fields, pass the global locale and response state to the matching component, and fail closed on unknown or invalid types. Do not infer the type from the wording.

Common component inputs:

`question`, `locale`, `response`, `submitted`, `result`, `disabled`, `onResponseChange`, `onSubmit`.

Common visual states: unanswered, answered, correct, incorrect, partly correct, pending review and unavailable. Render result text accessibly; do not rely on red/green alone.

## Types currently generated

The first topic deliberately contains one example of each of these eight types. The generated data exists, but the components are not yet implemented and the topic is not approved/publishable.

| Type value in JSON | Required UI component | Response stored | Default grading |
|---|---|---|---|
| `multiple_choice` | `MultipleChoiceQuestion` | One selected option ID | Exact ID match; 1 or 0 |
| `multiple_select` | `MultipleSelectQuestion` | Set of selected option IDs | Exact set match; 1 or 0 |
| `true_false` | `TrueFalseQuestion` | Boolean or null | Boolean equality; 1 or 0 |
| `matching` | `MatchingQuestion` | Left-ID/right-ID pairs | Score each correct pair; partial credit |
| `fill_blank` | `FillBlankQuestion` | Text string | Match the active locale's accepted answers after documented normalization |
| `image_observation` | `ImageObservationQuestion` | In current v1, one selected option ID | Uses declared response subtype; referenced visual must be student-renderable |
| `short_answer` | `ShortAnswerQuestion` | Text string | Explicit rubric; pending-review state if no validated grader exists |
| `scenario_based` | `ScenarioQuestion` | In current v1, one selected option ID | Scenario card + separate prompt, using declared choice-based grading |

The canonical component contract IDs, field requirements, localized control labels, grading rules and validation checks are in the JSON registry. Do not create new type strings without adding a registry entry and renderer acceptance tests.

## Component specifications

### 1. Multiple choice — `multiple_choice`

**UI:** question prompt followed by an accessible radio group with one selection allowed.  
**Data:** bilingual prompt/options/feedback, stable option IDs, and exactly one `correct_answer_ids` entry.  
**Response:** `selected_option_id`.  
**Grading:** exact option-ID match; one point when correct, zero otherwise.  
**Validation:** at least two unique options; correct ID exists; every visible option and feedback string exists in both locales.

### 2. Multiple select — `multiple_select`

**UI:** question prompt followed by checkboxes; learners can select more than one option.  
**Data:** bilingual prompt/options/feedback and one or more correct option IDs.  
**Response:** unordered set of selected option IDs.  
**Grading:** v1 requires an exact set match. No partial credit is implied unless the item explicitly uses a later versioned grading rule.  
**Validation:** at least two options; at least one correct ID; all IDs exist; all labels are bilingual.

### 3. True/false — `true_false`

**UI:** prompt/statement and two equally usable choice controls. Use radio controls or accessible choice buttons; never show correctness using colour alone.  
**Data:** bilingual prompt/feedback and a boolean `correct_answer`.  
**Response:** selected boolean or null.  
**Grading:** exact boolean equality.  
**Localized control labels:** EN `True / False`; HI `सही / गलत`.

### 4. Matching — `matching`

**UI:** two clearly named columns. Each left item is paired using an accessible select/list control. On narrow screens, stack one left item and its match control per row. Drag-and-drop may be an enhancement but is never the only way to complete the task.  
**Data:** bilingual prompt, bilingual left/right labels, stable IDs and `correct_pairs`.  
**Response:** a list of `{ left_id, right_id }` pairs.  
**Grading:** each correct pair earns an equal share by default, so partial credit is possible.  
**Validation:** every left item has one expected pair unless explicitly stated otherwise; all IDs and labels are valid and localized.

### 5. Fill in the blank — `fill_blank`

**UI:** sentence/prompt with a single-line accessible text input, bilingual placeholder, and a visible check-answer control.  
**Data:** bilingual prompt, `accepted_answers.en[]`, `accepted_answers.hi[]`, and bilingual feedback.  
**Response:** text.  
**Grading:** compare only against the active locale's accepted answers after normalization. For English, v1 permits Unicode NFC, trimming/collapsing whitespace and case-insensitive comparison. For Hindi, v1 permits Unicode NFC and whitespace normalization. Do not transliterate, translate silently, remove meaningful matras/diacritics or replace semantic judgement with a brittle exact sample sentence.  
**Validation:** each locale has valid accepted answers in its expected script.

### 6. Image observation — `image_observation`

**UI:** an approved locale-appropriate image/diagram, useful alt text and caption, then the declared response control. In this first registry version, the response control is a single-select group.  
**Data:** stable visual asset ID, bilingual prompt/alt text/caption, bilingual options/correct answer/feedback.  
**Response/grading:** same rules as its explicit response subtype.  
**Strict visual gate:** missing, unapproved, rights-blocked, non-student-renderable or wrong-language visuals block that question from learner rendering and publication. Do not show a full-page English source preview as an image question in Hindi mode.

**Current lesson blocker:** question `c6-sci-ch01-t01-q06` references V3 candidate `v3-page-0020-fa1415dfc5`, an English full-page preview kept for review. That source preview is explicitly not student-renderable. The question has content but is blocked until an appropriate bilingual/language-independent visual is reviewed and selected.

### 7. Short answer — `short_answer`

**UI:** prompt and multiline accessible text area; preserve the typed response when switching languages. Show the relevant rubric/sample answer after submit according to the lesson's review policy.  
**Data:** bilingual prompt, bilingual key points/sample answer, positive `max_score` and bilingual rubric.  
**Response:** text.  
**Grading:** explicit rubric only. Do not assume an AI grader exists. If a validated grader is not configured, keep the submission and show the localized pending-review state; do not invent a score.  
**Validation:** rubric and answer guide exist in both languages, and the rubric is consistent with `max_score`.

### 8. Scenario question — `scenario_based`

**UI:** a distinct scenario/context card, followed by a separate prompt and the response control. Both the scenario and prompt use the active global locale. For v1, the supported subtype is single-choice.  
**Data:** `scenario_by_locale`, `prompt_by_locale`, choice options, correct option ID and bilingual feedback.  
**Response/grading:** stable selected option ID; same grading as multiple choice.  
**Validation:** scenario and prompt must be separate bilingual fields, and the scenario must stay inside the current topic's chapter scope.

## QA and acceptance checklist

For every type, test:

- [ ] Locale data exists and is non-empty for all student-visible strings in EN and HI.
- [ ] The same question/option IDs, expected answer, score rule and intended difficulty work in both locales.
- [ ] Switching EN → HI → EN preserves question index, selected/typed response, submitted state, score and progress.
- [ ] Unanswered, valid, invalid, submitted-correct, submitted-incorrect and revisit states work as applicable.
- [ ] Keyboard focus and touch interaction work; controls have localized accessible names.
- [ ] Layout remains usable on narrow screens and in larger text sizes.
- [ ] Result and feedback are understandable without colour alone.
- [ ] Missing translation, invalid answer key, unsupported type or non-renderable visual is blocked without crashing unrelated questions.
- [ ] Existing practice/test routes remain unchanged; this contract is adopted additively.

## Current generated inventory — Chapter 1

The current production set has three draft topics, with eight question types used once per topic. That makes **24 draft questions total**:

| Question type | Renderer component | Topic 1 | Topic 2 | Topic 3 | Chapter 1 total |
|---|---|---:|---:|---:|---:|
| `multiple_choice` | `MultipleChoiceQuestion` | 1 | 1 | 1 | 3 |
| `multiple_select` | `MultipleSelectQuestion` | 1 | 1 | 1 | 3 |
| `true_false` | `TrueFalseQuestion` | 1 | 1 | 1 | 3 |
| `matching` | `MatchingQuestion` | 1 | 1 | 1 | 3 |
| `fill_blank` | `FillBlankQuestion` | 1 | 1 | 1 | 3 |
| `image_observation` | `ImageObservationQuestion` | 1 | 1 | 1 | 3 |
| `short_answer` | `ShortAnswerQuestion` | 1 | 1 | 1 | 3 |
| `scenario_based` | `ScenarioQuestion` | 1 | 1 | 1 | 3 |
| **Total** | — | **8** | **8** | **8** | **24** |

Topic artifacts:

- [Topic 1 — Science Begins with Curiosity](class-6/science/chapter-01/topic-01-science-begins-with-curiosity.v1.json)
- [Topic 2 — The Scientific Method](class-6/science/chapter-01/topic-02-the-scientific-method.v1.json)
- [Topic 3 — Curiosity, Collaboration and New Questions](class-6/science/chapter-01/topic-03-curiosity-collaboration-and-new-questions.v1.json)

Question IDs follow this naming pattern: `c6-sci-ch01-t01-q01` through `c6-sci-ch01-t03-q08`. Every question has a `rendering_contract_id` linking it to its component contract. The production index is the canonical live count and should be updated after each new lesson commit.

**Current state:** all 24 items are draft content. The three image-observation questions are blocked from student rendering until the referenced visual is approved and rendered as a locale-appropriate student visual. No question UI component or global language-switch runtime is implemented yet.

## Resume instructions

1. Read this file and the linked JSON registry before generating or implementing a new question type.
2. When a new type is introduced, add the type to the JSON registry and this human-readable document, define its exact data fields and response/grading/accessibility behavior, and add validation/test cases.
3. Add the type/component contract ID to every question in the lesson JSON and list its counts and IDs in the production index.
4. After authoring each topic, commit the lesson JSON and immediately update the production index.
5. Do not mark registry implementation as complete until the actual web/Android component and QA tests exist. Current state is **specification only**.
