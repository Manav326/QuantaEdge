# Structured bilingual lesson drafts

This folder stores AI-authored learning drafts separately from source-text extraction and application implementation.

## Start here

- [Production index](class-6/science/production-index.v1.json) — chapter/topic inventory, per-type totals, validation and resume state.
- [Reusable textbook content-generation prompt](../../docs/ai/reusable-textbook-content-generation-prompt.md) — source-first, bilingual lesson generation instructions for new class/subject batches.
- [Chapter 3 source-coverage ledger](class-6/science/chapter-03/source-coverage.v1.json) — numbered sections, source pages, figures/tables/activities, special material, summary, keywords, 13 review questions and six Learning further tasks.
- [Chapter 4 source-coverage ledger](class-6/science/chapter-04/source-coverage.v1.json) — five numbered lesson sections, EN/HI source page map, 18 numbered figures, 3 tables, 7 activities, storage/caution guidance, 11 review questions and 5 Learning further tasks.
- [Question-rendering registry — JSON](question-rendering-registry.v1.json) and [human-readable guide](question-rendering-registry.v1.md) — data contracts for the registered question types.
- Chapters 1–6 are committed as draft lesson data; source reconciliation, bilingual review, science review and visual approval remain pending. A commit does not mean the lessons are approved or publishable.

## Non-negotiable production rules

1. **Source-first lesson boundaries.** Derive lesson counts from the actual numbered textbook sections and subsections; do not preselect the count. Use each chapter coverage ledger to track figures, tables, activities, special boxes, summary, keywords, review questions and Learning further tasks.
2. **No omissions or cross-chapter teaching.** Account for the source scope or explicitly classify notes/front/back matter. A passing mention of another chapter must not become a separate lesson on it.
3. **Explain + Practice.** Every lesson has structured explanation and purposeful practice. Choose question types from the supported registry according to the concept and objective; no type is a quota and equal type counts are not required.
4. **English/Hindi parity.** Keep objective/block/visual/activity/question IDs, answer keys, grading and intended difficulty aligned. Localize all learner-visible fields, including diagram labels/alt text, instructions, options, feedback, rubrics and controls.
5. **One global live language selector.** The app language controls all learner-visible Web and Android content. Switching must preserve question index, selected/typed answers, score, progress and reading position. No mixed-language screen or silent locale fallback; missing fields block approval.
6. **Document every renderer.** Every question has `type` and `rendering_contract_id` from the registry. The registry specifies UI contracts but does not mean those components are implemented.
7. **Visual gates.** Render/review diagrams for factual accuracy, locale parity, legibility, contrast, alt text and accessibility. Image-observation questions remain blocked until the visual is approved.
8. **Draft vs approval.** Structural JSON validation is not proof of source completeness, translation correctness, scientific accuracy, human approval or publishability.
9. **Batch staging; no topic commits.** During authoring create Git blobs/tree objects only—do not commit or move branch refs. Update issue #56 once after each complete chapter batch, not after every topic. Commit the full batch only when explicitly requested.
10. **Resumable metadata.** Keep the updated index, README and source coverage ledgers in the same staged tree. Record the full tree SHA and next action in issue #56 at each chapter checkpoint.

## Current draft inventory

| Chapter | Topics | Questions | State |
|---|---:|---:|---|
| 1 — The Wonderful World of Science | 3 | 24 | Earlier-committed drafts; review pending |
| 2 — Diversity in the Living World | 7 | 56 | Committed in batch; coverage ledger included; review pending |
| 3 — Mindful Eating: A Path to a Healthy Body | 8 | 64 | Committed in batch; coverage ledger included |
| 4 — Exploring Magnets | 5 | 40 | Committed in batch; coverage ledger included |
| 5 — Measurement of Length and Motion | 7 | 56 | Committed in batch; coverage ledger included |
| 6 — Materials Around Us | 9 | 72 | Committed in batch; coverage ledger included |
| **Total prepared** | **39** | **312** | **Draft only; not approved or publishable** |

The committed historical inventory contains **39 questions of each** registered type because the earlier prompt forced one of each per topic. This describes existing draft data—not a target distribution for future generation. New lessons must choose question types contextually and map every source-inventory item to lesson content or an explicit disposition. All image-observation questions and draft diagrams for Chapters 2–6 remain blocked pending visual/accessibility approval.

## Resume procedure

Read the production index and GitHub issue [#56](https://github.com/Manav326/QuantaEdge/issues/56) first. Continue from the recorded staged tree/blob SHAs; do not regenerate existing drafts. Complete rendered-source, bilingual, visual and scientific reviews. Map the next chapter's numbered headings before choosing its lesson count. Do not commit unless explicitly requested to finalize.


## Chapter 5 checkpoint (draft; review pending)

- `class-6/science/chapter-05/`: 7 bilingual draft lessons (sections 5.1–5.7), 56 questions (7 per registered type), and a source-coverage ledger.
- All image-observation questions and draft visuals are blocked until rendered and accessibility-reviewed.
- English/Hindi PDF page boundaries, complete source coverage, semantic parity and science review remain pending. Runtime Web/Android renderer contracts and global language switching are specifications, not implementations.
- Resume details and staged tree SHA are recorded in [Issue #56](https://github.com/Manav326/QuantaEdge/issues/56).


## Chapter 6 checkpoint (draft; review pending)

- `class-6/science/chapter-06/`: 9 bilingual lessons derived from headings 6.1, 6.2, 6.3.1–6.3.6 and 6.4; 72 practice questions (9 per registered type), plus a source-coverage ledger.
- The ledger inventories figures 6.1–6.8, tables 6.1–6.5, activities 6.1–6.8, both `Let us play` tasks, 10 end questions and four `Learning further` tasks. Exact page boundaries, figure/layout mapping, Hindi semantic parity and science review remain pending.
- Commit status is recorded in [Issue #56](https://github.com/Manav326/QuantaEdge/issues/56). Committed draft data does not mean approved or publishable.


## Item-level source coverage contract

New coverage ledgers must provide `source_inventory.items[]` and `coverage_map[]`. Every source item ID appears exactly once in the map; coverage references resolve to real lesson block, activity or question IDs. A source item may be taught, handled by an activity, assessed in practice, intentionally kept as reference-only, excluded with a reason, or explicitly blocked pending source review. An unaccounted item is a validation failure. Existing ledgers without these fields remain historical audit records and are not coverage-validator passes.


## Canonical application and database key contract

The only canonical application artifact format is one UTF-8 JSON object per topic using schema_version and artifact_type equal to quantaedge.structured-lesson.v1. Keep extra model, alignment, source, provenance, scoring-guide and review metadata: additional keys are allowed. Do not omit or rename the keys that the current API/client contract requires.

Canonical content path:
data/structured-lessons/class-{class}/{subject}/chapter-{NN}/topic-{NN}-{slug}.v1.json

For the current Class 6 Science batch, the production index is:
data/structured-lessons/class-6/science/production-index.v1.json

The index is the authoring/resume/path ledger. The API Docker build packages data/structured-lessons into its JAR resources and loads topic JSON from the class/subject/chapter directories. A generation manifest, SFT/RAG file, benchmark response or candidate under data/textbook-content-pipeline is not itself a learner lesson.

Required application-routing keys include curriculum.class (integer), curriculum.subject (subject code), chapter.chapter_number, chapter.title_by_locale, topic.topic_number, topic.topic_id, topic.title_by_locale and artifact_id. The required content keys include learning_objectives, source_refs, explain.blocks, practice.questions and workflow.publication_allowed. The selected question must carry its exact rendering_contract_id. The validator permits additional keys and does not require a fixed question-type distribution.

Database mapping is explicit:
- artifact_id maps to artifact_id in the student_structured_question_response and student_structured_activity_response tables.
- The API computes artifact_sha256 from the exact canonical JSON bytes. It is a content-version identity, not the source textbook's workflow.source_pdf_sha256.
- practice.questions[].id maps to question_id in student_structured_question_response.
- explain.blocks[].id maps to block_id in student_structured_activity_response.
- student_id is resolved from the authenticated student's context and is never generated in lesson JSON.
- response is the student's response JSONB. Correct-answer IDs, accepted answers, answer guides and grading rubrics remain server-side.

The DB columns constrain artifact_id to varchar(120), question_id to varchar(180) and block_id to varchar(180). Stable IDs must not depend on translated labels or array position. Changing the bytes of an already-published artifact intentionally changes its artifact hash so earlier answers are not silently attributed to a different content version.

Generation output is staged first. After confirming the canonical chapter mapping, preview registration with:
python scripts/register_structured_lesson_drafts.py --candidate-dir <run-output>/lessons --index data/structured-lessons/class-6/science/production-index.v1.json --chapter-number 7 --confirm-chapter-mapping

Review every destination and key. Then add --apply to write canonical draft files and update the index. Registration preserves additional metadata and stable artifact/question/block IDs, verifies the source-book ID and whole-book PDF SHA-256, checks the runtime schema, refuses overwrites and keeps publication disabled.

Before merging content, run:
python scripts/validate_structured_lesson_artifacts.py --repo-root . --index data/structured-lessons/class-6/science/production-index.v1.json

Validation checks the canonical files against the production index, current runtime fields, the renderer registry and DB identifier limits. It does not approve factual accuracy, source completeness, bilingual semantic parity, visual assets or publication. The existing 39-topic / 312-question batch remains draft-only.
