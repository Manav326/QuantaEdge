# Structured bilingual lesson drafts

This folder stores authored lesson data separately from raw textbook extraction and from application implementation.

## Start here

- [Class 6 Science production index](class-6/science/production-index.v1.json) — resumable status, completed/pending topics, committed artifact paths and per-chapter/per-type question counts.
- [Question-rendering registry — JSON contract](question-rendering-registry.v1.json) — machine-readable UI component, data fields, response shapes, grading and localization rules.
- [Question-rendering registry — human-readable guide](question-rendering-registry.v1.md) — implementation and QA guide for maintainers.
- [Class 6 Science · Chapter 1 · Topic 1](class-6/science/chapter-01/topic-01-science-begins-with-curiosity.v1.json) — first bilingual lesson draft.
- [Class 6 Science · Chapter 1 · Topic 2](class-6/science/chapter-01/topic-02-the-scientific-method.v1.json) — second bilingual lesson draft.

## Non-negotiable production rules

1. **Chapter-specific only.** Each lesson belongs to exactly one chapter/topic. No concept teaching, examples, activities, visuals or practice items from a different chapter. Do not expand a brief mention into a mini-lesson on another chapter.
2. **Explain + Practice.** Every topic has a structured explanation experience and a separate practice section.
3. **English/Hindi parity.** Author both locales together from the same learning objectives. Keep objective, block, asset and question IDs, correct-answer IDs, scoring, difficulty and learning demand aligned. Translate the complete learning experience, not only the headings.
4. **App-wide live language setting.** One global app language selector controls all learner-visible content throughout web and Android. The learner can switch at any point. Explanations, activity instructions, diagram labels, image captions/alt text, every question and option, hints, answers, feedback, rubrics, glossary, recap, shared controls, status messages and errors must all have English and Hindi values.
5. **Preserve state on switch.** Changing language must update the visible experience together without losing the current question, selected/typed answers, score, progress or reading position. No mixed-language screen and no silent fallback to the other locale are allowed. Missing locale values block approval/publication.
6. **Question type must map to a UI component.** Every question includes `type` and `rendering_contract_id` from the question-rendering registry. The registry defines component name, data schema, response, grading, localization, accessibility and validation. Do not invent a new type without adding its specification and QA plan. **Registry and UI implementations are currently specifications only.**
7. **Generated vs source text.** Generated teaching explanations, original diagrams, activities and practice items are authored learning aids. Do not present them as verbatim NCERT text or NCERT-provided assessment items.
8. **Visual provenance.** Retain stable candidate IDs, source book/page/hash and review state. V3 candidate assets are review-only until checked for content relevance, source fidelity, accessibility, rights and approval. An English full-page preview is a review reference, not a student visual for Hindi mode. Use an appropriate bilingual/original visual for student rendering.
9. **Status separation.** Extraction completeness, chapter mapping, asset review, source reconciliation, bilingual parity, lesson review, runtime language behavior and publication are separate statuses. Generated does not mean complete, approved or publishable.
10. **Commit/resume every topic.** On `main`, commit one topic JSON, immediately update the production index, and record exact completed work, per-topic question-type counts, cumulative chapter totals, blockers and next action. Do not create an extra branch for this content-authoring stream.
11. **Content only for now.** These data artifacts do not mean web/Android UI behavior has been implemented. Do not modify runtime code or existing lesson records as part of content authoring.

## Current question inventory

Chapter 1 currently has **16 draft practice questions** across 2 generated topics: **2 multiple-choice, 2 multiple-select, 2 true/false, 2 matching, 2 fill-in-the-blank, 2 image-observation, 2 short-answer and 2 scenario-based**. The index is the canonical rolling count and is updated after each topic commit.

## Expected lesson data

A topic artifact should include source book and edition identity, exact chapter/topic scope, included/excluded concepts, learning objectives, typed bilingual Explain blocks, relevant source/generated visual data, activities where appropriate, a distinct varied Practice set, answer keys/feedback/rubrics in both languages, registry links for every question, provenance, validation status, known limitations and next steps.

## Resume procedure

Read the production index first. Open the listed active topic rather than regenerating it from scratch. Read the question-rendering registry before adding question types. Resolve pending source and visual review items, run locale completeness/parity checks, then author the next chapter-specific topic. Update the lesson artifact and production index on `main` at each topic boundary.
