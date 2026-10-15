# Structured bilingual lesson drafts

This folder stores AI-authored learning drafts separately from source-text extraction and application implementation.

## Start here

- [Production index](class-6/science/production-index.v1.json) — chapter/topic inventory, per-type totals, validation and resume state.
- [Chapter 3 source-coverage ledger](class-6/science/chapter-03/source-coverage.v1.json) — numbered sections, source pages, figures/tables/activities, special material, summary, keywords, 13 review questions and six Learning further tasks.
- [Chapter 4 source-coverage ledger](class-6/science/chapter-04/source-coverage.v1.json) — five numbered lesson sections, EN/HI source page map, 18 numbered figures, 3 tables, 7 activities, storage/caution guidance, 11 review questions and 5 Learning further tasks.
- [Question-rendering registry — JSON](question-rendering-registry.v1.json) and [human-readable guide](question-rendering-registry.v1.md) — data contracts for the registered question types.
- Chapter 1 has earlier-committed drafts. Chapters 2–4 are staged as uncommitted Git objects, not on a branch.

## Non-negotiable production rules

1. **Source-first lesson boundaries.** Derive lesson counts from the actual numbered textbook sections and subsections; do not preselect the count. Use each chapter coverage ledger to track figures, tables, activities, special boxes, summary, keywords, review questions and Learning further tasks.
2. **No omissions or cross-chapter teaching.** Account for the source scope or explicitly classify notes/front/back matter. A passing mention of another chapter must not become a separate lesson on it.
3. **Explain + Practice.** Every lesson has a structured explanation and distinct practice using multiple question types.
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

The prepared inventory contains **39 questions of each** registered type: multiple choice, multiple select, true/false, matching, fill in the blank, image observation, short answer and scenario-based. All image-observation questions and draft diagrams for Chapters 2–6 remain blocked pending visual/accessibility approval.

## Resume procedure

Read the production index and GitHub issue [#56](https://github.com/Manav326/QuantaEdge) first. Continue from the recorded staged tree/blob SHAs; do not regenerate existing drafts. Complete rendered-source, bilingual, visual and scientific reviews. Map the next chapter's numbered headings before choosing its lesson count. Do not commit unless explicitly requested to finalize.


## Chapter 5 checkpoint (draft; review pending)

- `class-6/science/chapter-05/`: 7 bilingual draft lessons (sections 5.1–5.7), 56 questions (7 per registered type), and a source-coverage ledger.
- All image-observation questions and draft visuals are blocked until rendered and accessibility-reviewed.
- English/Hindi PDF page boundaries, complete source coverage, semantic parity and science review remain pending. Runtime Web/Android renderer contracts and global language switching are specifications, not implementations.
- Resume details and staged tree SHA are recorded in [Issue #56](https://github.com/Manav326/QuantaEdge/issues/56).


## Chapter 6 checkpoint (draft; review pending)

- `class-6/science/chapter-06/`: 9 bilingual lessons derived from headings 6.1, 6.2, 6.3.1–6.3.6 and 6.4; 72 practice questions (9 per registered type), plus a source-coverage ledger.
- The ledger inventories figures 6.1–6.8, tables 6.1–6.5, activities 6.1–6.8, both `Let us play` tasks, 10 end questions and four `Learning further` tasks. Exact page boundaries, figure/layout mapping, Hindi semantic parity and science review remain pending.
- Commit status is recorded in [Issue #56](https://github.com/Manav326/QuantaEdge/issues/56). Committed draft data does not mean approved or publishable.
