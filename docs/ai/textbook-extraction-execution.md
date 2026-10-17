# Textbook extraction and AI Tutor grounding — durable execution tracker

This file is the durable checkpoint for the NCERT/SCERT extraction work. Continue from the first unchecked item when a conversation ends; do not restart at planning or repeat completed work. Keep implementation on `main` only (no branches or PRs), and update this tracker in the same commit as meaningful milestones.

**Operator runbook:** Read [Class 6 GHCR textbook extraction pilot](../runbooks/class6-ghcr-textbook-pilot.md) before repeating local import steps. It records commands, environment, source manifest totals, portal/cookie separation, failure history, safety gates, and the exact next action.

## Goal
One reusable, provenance-preserving source of truth for whole-book PDF ingestion, page extraction, visual asset extraction, chapter mapping, Hindi/English alignment, multimodal lesson/question generation, and live AI Tutor retrieval for QuantaEdge classes 6–8 Maths and Science.

## Execution status

- [x] Add persistent per-page extracted text with source hash, PDF asset/document, class, subject, chapter, language, page number, extraction method, quality/status, and retry/error metadata (migration V95).
- [x] Add asynchronous, idempotent extraction worker: stream source PDF to a temporary file, extract embedded text, OCR low-text/scanned pages within the current 250-page OCR budget, save page-level progress, and retry failed/unresolved pages/books.
- [x] Add authorized admin endpoints to queue extraction and inspect status/progress; extraction requires CONTENT_EDIT and an approved asset with class/subject/language intake metadata.
- [x] Add source-aware search to tutor retrieval, filtered by class/subject/language and prioritizing the active chapter; preserve source title, edition, chapter, and PDF page metadata. CI/runtime verification remains pending.
- [x] Connect extracted-page retrieval to the existing tutor grounding context.
- [x] Run the corrected GHCR-backed Class 6 pilot for complete-book PDF extraction in Hindi and English. It extracted 4 complete books (Maths and Science in both languages), 1,252 pages total, with zero parser failures; 1,221 pages passed basic text-quality checks and 31 were flagged LOW_TEXT for review/OCR.
- [x] Add a draft-only import path for extracted GHCR page manifests, source-URL/hash validation, an explicit source-approval endpoint, and a CLI importer that defaults to dry-run.
- [x] Add focused importer tests, exact class/language/subject/scope filtering, source/PDF SHA-256 checks, and review-gated API import/approval. API verification passed on commit `a0920c60`; the final GHCR API image publish was still running at the last check.
- [ ] Complete the local Class 6 source-page import and tutor retrieval/citation verification. The four committed manifests and page JSONL files were independently audited on 2026-10-14: SHA-256 values and contiguous page numbering matched (1,252 pages; 1,221 TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW and 31 LOW_TEXT_REVIEW; no integrity issues). On 2026-10-15, the local dry run passed but draft import returned HTTP 401 (0 accepted books). Browser auth through the Education Admin proxy reports ADMIN/staffId 1/EDUCATION_ADMIN/CONTENT_EDIT, while direct API requests with a forced workspace header returned 401. Historical importer commit 999f95e1cd used QE_SESSION and no X-QE-Workspace; the current fix restores that request shape and sends both session verification and multipart import through the Admin proxy on port 3001. Check newest CI and perform one runtime attempt; **stop on 401**. The legacy QUANTAEDGE_ADMIN_TOKEN/X-Admin-Token was deliberately removed as an authorization bypass by security commit 7d69a011b3 and is not a substitute for staff identity on this endpoint. After successful DRAFT import, verify 4 source books and 1,252 pages read-only before any separate source approval.
- [x] Add a draft-only bridge from committed GHCR page manifests/JSONL into the existing question-generation schema. It requires a source-bound editor-reviewed chapter map using canonical curriculum codes, re-verifies full-book page continuity and metadata hashes, and blocks whole chapters containing LOW_TEXT/OCR_REQUIRED/empty pages while allowing clean chapters through.
- [x] Enable existing question generation against an in-house Ollama/OpenAI-compatible endpoint on exact localhost/loopback without a hosted API key or external-processing confirmation; hosted endpoints still require HTTPS, an explicit consent flag, and a key. All generated bundles remain drafts.
- [x] Document the multimodal extraction and lesson-authoring contract in [multimodal-lesson-authoring-pipeline.md](multimodal-lesson-authoring-pipeline.md): page/crop visual inventory, provenance and rights gates, typed lesson blocks, trusted interactive registry, accessible fallbacks, and staged acceptance criteria. This is a design contract; implementation is not yet complete.
- [x] Record the additive-only compatibility contract: preserve existing extraction/import formats, lesson records/rendering, Content Studio workflow, questions/tests and Tutor routes; new visual fields and blocks remain optional and legacy text-only paths must pass regression tests.
- [ ] Phase A — visual inventory: extract embedded images and render page/crop previews; record asset hashes, page coordinates, captions/context, confidence, rights/provenance and review state; test against the four-book Class 6 pilot without breaking text import.
- [ ] Phase B — typed lesson blocks: define and validate a versioned lesson document schema; add draft import/preview/render support for text, equations, examples, images/diagrams, tables/charts, checks and recap while keeping existing lessons backward-compatible.
- [ ] Phase C — trusted interactive component library: start with deterministic Maths manipulatives (number line/place value, fractions, geometry, simple graph explorer), then a reviewed Science animation. Never execute AI-generated JavaScript/HTML.
- [ ] Phase D — AI-assisted multimodal authoring: generate lesson blueprints and original visual/animation briefs from mapped, quality-approved sources; produce draft-only assets/blocks; run structural, factual, bilingual, accessibility and rights checks before editor approval.
- [ ] Run the local Class 6 loop end to end: import/approve verified source records, create reviewed chapter maps, generate candidates using Ollama, promote reviewed content, import it as draft into Content Studio, and verify Tutor retrieval/citations.
- [ ] Align Hindi and English editions/contextual translations while preserving formulas, tables, diagrams, visual labels and scientific/mathematical terms; record translation status and source relationship.
- [ ] After the Class 6 pipeline and multimodal student experience are verified end to end, scale extraction/authoring to Classes 7–8 Maths and Science books.
- [ ] Update this tracker with commit SHAs, workflow result, and the exact next unchecked task after each work session.

## Current checkpoint (2026-10-15)

The multimodal implementation requirements are now durable in the repository. The implementation is explicitly **additive-only**: preserve current behavior and contracts, add optional capabilities behind safe compatibility paths, and prove legacy regression tests. Start with **Phase A: visual inventory**, not animation generation. Text extraction, source-page hashes and review-gated question-generation infrastructure exist; image/crop extraction, typed multimodal lesson rendering, interactive blocks, and their editor approval workflow remain unimplemented. Do not describe QuantaEdge as having automatic multimodal lesson generation until the phase acceptance criteria pass.

## Existing implementation checkpoint (2026-10-14)

Implementation commits on `main`:
- `43f7b8fc` — durable tracker (this file)
- `536c4434` — V95 extracted-page and extraction-job schema
- `172577fc` — first extraction worker implementation
- `5599e12c` — admin queue/status API
- `f3d7fc00` — tutor retrieval integration
- `14e97c65` — asynchronous dispatch, retry, and duplicate-worker safeguards
- `3cd5812b` — chunked PDF byte streaming for large books
- `255d3a92` — page readiness states and partial-extraction reporting
- `43762520` — readable tutor source/page context
- `1b3c78e9` — tutor textbook retrieval tests
- `4ca8521a` / `3216290d` — support whole-book ingestion assets and preserve chapter ranges
- `bd917fad` — admin UI controls to queue extraction and inspect progress
- `f1cad587` / `a5dab569` — input validation and accurate source-approval wording
- `a5841183` — V96 GHCR source-book index, keeping original PDFs in GHCR
- `44a4b769` / `03c75dd6` — validated draft import and explicit approval API
- `999f95e1` / `a94826a2` / `d7977dd3` — safe local importer for extracted page JSONL
- `ed0023dc` / `a0920c60` — Spring Boot 4 Jackson 3 compatibility and passing API verification
- `e4b3be32` / `d095913d` / `f50f9897` — exact subject filtering and extraction/importer tests
- `98796de9` — multimodal lesson-authoring pipeline design contract
- `ecc773ce` — mandatory additive/backward-compatible rollout guardrails

Corrected GHCR extraction workflow succeeded at https://github.com/Manav326/QuantaEdge/actions/runs/38579506350 and produced artifact ID `11902717615` (available until 2027-01-12). The API verification job passed at commit `a0920c604086a03ead0a075ef3dde6cd58e0f880`; the aggregate workflow was still finishing the API image build at the last status check. The tracker and tutor documentation are intentionally committed to the repository so work can resume at the next unchecked task. The source-ingestion admin screen now exposes the extraction action for approved complete-book assets. The GHCR runner extracted real NCERT Class 6 books: English *Ganita Prakash* (412 pages), English *Curiosity* (272), Hindi *Ganita Prakash (Hindi)* (304), and Hindi *Jigyasa* (264), each labelled Reprint 2026–27. The 1,252 extracted page records are in the private 90-day Actions artifact, not yet imported into the live QuantaEdge database. The new import API stores page text without copying the original large PDFs into PostgreSQL. Extraction statuses are READY, LOW_TEXT, OCR_REQUIRED, and FAILED; only READY pages can ground tutor responses. After CI passes, the next task is a real approved-book pilot and runtime SQL validation, then tests and generation/translation integration.

## Repository artifact mirror

A GitHub Actions workflow (`.github/workflows/mirror-textbook-extraction-artifact.yml`) mirrors validated Class 7 or Class 8 extraction artifacts into the matching `data/textbook-extractions/class-{class}/` folder on `main`, so a normal `git pull` retrieves the page JSONL and manifests without a manual artifact download. The mirror contains extracted text and reports only; original PDFs remain in GHCR. These files are extraction-only and are not approved or published lesson content.

## GHCR extraction to question-generation bridge

Run `scripts/build_extraction_bundle_from_ghcr_pages.py` to convert one committed manifest and sibling page JSONL into the existing `DRAFT_EXTRACTION_ONLY` bundle schema. The command requires a human-reviewed chapter-map JSON bound to that exact source `book_id` and SHA-256. Chapter codes must exist in the canonical Class 6–8 curriculum target matrix. Start from `docs/ai/ghcr-extraction-chapter-map-template.json`; leave it in draft status until the source book's contents and PDF page ranges have actually been checked. Chapters touching low-text, OCR-required, failed, or empty pages are reported and skipped. The adapter does not publish content.

For local generation, set `QUANTAEDGE_QUESTION_API_BASE_URL=http://localhost:11434/v1` and `QUANTAEDGE_QUESTION_MODEL` to the model tag already pulled into Ollama, then pass `--generate` to `scripts/generate_questions_from_extraction.py`. Exact loopback endpoints do not require `OPENAI_API_KEY` or `--confirm-external-processing`; a hosted endpoint still requires HTTPS, explicit confirmation, and a key. Candidate lessons/questions remain drafts and continue through the existing human review and Content Studio gates.

## Immediate Class 6 tutor pilot command

Use the end-to-end operator instructions in [the Class 6 GHCR textbook pilot runbook](../runbooks/class6-ghcr-textbook-pilot.md). The PowerShell pilot defaults to a no-write dry run. Draft import prompts for an access cookie and performs session/workspace/permission checks before upload. The current live-import attempt is blocked by a direct API 401 even though the Education Admin proxy session is authenticated; see the runbook's checkpoint and troubleshooting section. Do not run `ImportAndApprove` until the draft import succeeds and the four source records, checksums, page totals, and review flags have been verified. These artifacts are not lesson/question content and never imply publication.

## Guardrails
- Existing whole-book GHCR/cache flow remains the input source; do not introduce chapter-download fallback when whole-book download is required.
- Extraction is asynchronous and never happens in a student request.
- A successful extraction is not approval to publish.
- Keep original PDFs and exact edition/language/source metadata.
- Never claim the full textbook corpus is available to the tutor until the end-to-end retrieval test demonstrates it.

## Optional visual inventory: first implementation stage

The additive-only Phase A tool is now implemented at `scripts/extract_visual_asset_inventory.py` (commit `bf1f84db895818888b206141d5b8e34383a7825f`). It reads the existing whole-book PDF and source manifest, verifies the PDF SHA-256 and page count, optionally reads existing page JSONL context without changing it, extracts embedded images, renders per-page JPEG previews (including evidence for vector diagrams), and writes a separate `quantaedge.visual-inventory.v1` sidecar. It does not alter the text extraction bundle/import contract or publish assets. Every item remains `CANDIDATE` / `SOURCE_REFERENCE_ONLY`; VLM analysis is still `NOT_ANALYZED`.

Regression tests are in `tests/test_extract_visual_asset_inventory.py`. The existing content-import workflow's Python unittest discovery covers this file and installs PyMuPDF. Before running the tool on the four-book pilot, test it on one exact original PDF + matching manifest in a local authenticated environment. Keep the derived media output private/local; do not commit page previews or extracted images to Git by default.

Next Phase A tasks:
- Run the tool on one Class 6 Maths book and inspect embedded images plus page previews, especially vector diagrams and low-text pages.
- Confirm output size/runtime and review the generated manifest for correct book/page/hash/coordinate provenance.
- The optional VLM stage is now implemented at `scripts/analyze_visual_inventory_with_ollama.py` (commit `3679a87d3f426c144a52758ad6c2fffdd97f07c3`). It uses a user-selected, already-installed local Ollama vision model, accepts only loopback HTTP, validates asset hashes, writes a separate `quantaedge.visual-analysis.v1` sidecar, and leaves source inventory/text/import data untouched. Results remain drafts and never publish automatically. Run the new tests and a five-asset local evaluation before selecting a default model; no model is assumed installed and no model pull is triggered by the script.
- Only after these checks consider a multi-book run and later draft-block integration.



## Optional local VLM evaluation stage

- Script: `scripts/analyze_visual_inventory_with_ollama.py`; guide: `docs/ai/visual-analysis-execution.md`.
- Regression tests: `tests/test_analyze_visual_inventory_with_ollama.py`; CI's existing unittest discovery includes it.
- The script sends an image plus page-text context to the local Ollama `/api/chat` endpoint. It records summary, type, visible text, concepts, context relation and limitations with source page/hash/model provenance.
- **Not yet validated on a real book or a real installed vision model.** User/local step: create an inventory for one book, run `ollama list`, select a vision-capable installed model tag, and run a five-asset sample. Review captions/labels before increasing sample size or setting any default.
- After local evaluation, decide whether to add OCR/figure cropping and then a reviewed visual-to-lesson adapter. Do not connect this draft sidecar to student delivery yet.


## GitHub-hosted visual pipeline checkpoint (2026-10-15)

- [x] Add per-asset resumable vision analysis. The analyzer atomically checkpoints every completed asset; successful entries are reused only when source asset SHA-256, model tag and prompt version match. Failed assets are retried. Subsequent runs process the next pending assets rather than redoing completed work.
- [x] Add workflow .github/workflows/textbook-visual-analysis.yml to derive the class-wise GHCR image, fetch the whole-book PDF by book ID, and locate the matching committed manifest/page JSONL by book ID and SHA-256, build the additive visual inventory, run Ollama on the GitHub runner, execute focused tests, publish a run-versioned and stable per-book GHCR bundle, and mirror generated binaries, previews, inventory, checkpoint and index to main.
- [x] Store a stable bundle index in data/textbook-visuals/<book_slug>/bundle-index.v1.json; it records source PDF hash, model/prompt version, GHCR package/tags and bundle SHA-256.
- [ ] Run the workflow on one exact Class 6 source image, inspect visual accuracy and runtime, and confirm GHCR publication plus the repository checkpoint commit. The workflow defaults to the existing Class 6 English Curiosity book and automatically resolves its class-wise GHCR image and source paths.
- [ ] Review the sample captions and visible text, then run the next batch using the same book slug/model. Only after review should visual results be adapted into Content Studio lesson drafts.

Implementation commits:
- 448ba2fa — atomic per-asset checkpoints and resume support.
- 587d3ea9 — resume regression tests.
- e64e2d4e — GitHub-hosted visual pipeline workflow.
- deaa053a — resumed runs continue to the next unprocessed assets.
- 32a50abd — execution guide for resumable analysis.


- [x] Add a restore script, scripts/pull_textbook_visual_bundle.ps1, that reads the committed bundle index, pulls the exact versioned GHCR package, verifies SHA-256, and restores the complete asset bundle after a repository pull.
- [ ] Confirm the first workflow run on the actual existing GHCR source-book image; no real-book vision results or successful workflow run are claimed until that run completes.


- [x] Strengthen runner resumability: process batches of 10, commit all generated inventory/analysis/assets/previews to main after each batch, and mirror the analysis checkpoint to a stable GHCR checkpoint tag. Same-run failures do not starve later assets; the next workflow run retries failed assets.
- [ ] Validate the new workflow end-to-end. GitHub connector status output has not yet confirmed a successful Actions run for these commits.

## Class 7/8 source preparation and authoring handoff (2026-10-16)

The two class workflows deliberately accept **only Class 7 or Class 8**. Each selected class is handled in both English and Hindi; the required source set is one complete Science book and exactly two Maths books explicitly identifiable as Part 1 and Part 2 in each language. If the GHCR index has missing, duplicate, unlabelled or ambiguous sources, selection fails instead of guessing.

### Required sequence

1. Run [QuantaEdge GHCR Textbook Extraction Pilot](https://github.com/Manav326/QuantaEdge/actions/workflows/textbook-extraction-pilot.yml) and select Class 7 or 8. It extracts only matching Science/Maths subject-book pages in both languages and validates source hashes, full page continuity, the exact source counts and the Maths part labels.
2. Only after that run succeeds, run [Mirror verified textbook extraction artifact to main](https://github.com/Manav326/QuantaEdge/actions/workflows/commit-class6-extraction-artifact.yml) with the same class and successful pilot run ID. This records the selected Class 7/8 extraction JSONL/manifests on `main`; original PDFs remain in GHCR.
3. Run [Prepare textbook visual candidates v3 (additive)](https://github.com/Manav326/QuantaEdge/actions/workflows/textbook-visual-candidates-v3.yml) and select the same Class 7 or 8. It resolves the six expected sources automatically, verifies each selected GHCR PDF hash against its committed extraction manifest/page JSONL, and builds a separate review-only visual candidate pack per source book. The full visual assets are stored under immutable per-run GHCR tags; compact JSON, contact sheets, bundle indexes and the admin review catalog are checkpointed on `main`.
4. Review source identity, page coverage, figures/tables, scientific meaning, Hindi/English semantic parity, visual labels, readability, rights/provenance and accessibility. Candidate generation is not lesson generation, approval or publication.
5. Then author the chapter-specific bilingual lesson JSON using [the reusable textbook content-generation prompt](reusable-textbook-content-generation-prompt.md). Read the current production index and [Issue #56](https://github.com/Manav326/QuantaEdge/issues/56) before starting; never assume a previous chat's context or reuse stale source IDs.

### Canonical data and gates

- Extraction output: `data/textbook-extractions/class-N/{english,hindi}/pages/*.jsonl` plus sibling `*.manifest.json`.
- Visual candidate metadata: `data/textbook-visual-candidates-v3/<book_slug>/`.
- Admin review catalog: `data/textbook-visual-candidates-v3/catalogs/<book_slug>.v3.json`; index: `data/textbook-visual-candidates-v3/index.v1.json`.
- Visual bundles are versioned in GHCR and must be restored/verified by the recorded bundle SHA-256.
- All extracts and visual candidates remain draft/review-only. The pipeline never marks content publishable.
- Web/Android question renderers and the single state-preserving global language selector are separate runtime implementation tasks; source preparation does not implement them.

### Reusable class content contract

- Use the actual numbered headings/subsections in both language editions to choose lesson count; never choose a fixed number of topics before reviewing the chapter structure.
- Maintain a bilingual source-coverage ledger that itemizes every section/subsection, figure/panel, table/chart, activity/experiment, worked example, sidebar/case/safety note, keyword, summary point, chapter-end question and “Learning further” task. Give every item a stable ID and map it to a concrete lesson block/activity/question ID, or state a reasoned explicit disposition. Counts alone do not prove item-level coverage. Run `scripts/validate_structured_lesson_coverage.py`; rendered-source and human review remain separate gates.
- Each lesson JSON must use the current structured lesson schema and have separate Explain and Practice sections; stable locale-neutral IDs; non-empty English and Hindi text for learner-visible fields; and a context-appropriate set of questions. The eight structured types are a supported catalogue, not quotas: never require one of each type or equal per-type counts.
- Questions must use the current renderer contract IDs, localized options/feedback/rubrics and validated answer references. AI-authored questions are supplementary, not verbatim NCERT questions.
- A generated diagram or visual observation is non-renderable until visual, source, semantic bilingual, scientific and accessibility review passes. Keep the publication flag false until human approval.
- Create all topic JSONs and the coverage ledger as one chapter batch. Do not commit topic-by-topic. Update Issue #56 once per completed chapter with the exact tree/blob pointers, test results and next action. Commit only when explicitly requested.

### Current verification status

The updated workflow code and focused tests are committed together as an implementation change, but the Class 7/8 batch is **not confirmed end-to-end until both workflows are run against the actual GHCR indexes and the source artifacts are reviewed**. If the GHCR source list differs from the required 1 Science + 2 clearly labelled Maths parts per language, first resolve the source selection rather than weakening the validation.


### Contextual question-type selection

For structured lessons, the model may select from the eight registered structured question types (and only additional contracts explicitly supported for the target surface). The list is capability metadata, not a required distribution. The validator checks each question against its selected contract but does not require all types to appear. A generated question must be relevant to a learning objective and source evidence; an activity or figure can be covered by teaching content without being forced into a question. The legacy question-bank generator's explicit approved-count target mode is a separate replenishment operation and must not be confused with lesson authoring.

Run the source-item coverage validator on a draft coverage ledger:

```bash
python scripts/validate_structured_lesson_coverage.py \
  --ledger data/structured-lessons/class-6/science/chapter-03/source-coverage.v1.json \
  --repo-root . --allow-pending-review
```

Use `--allow-pending-review` only when creating a draft checkpoint and report every pending item. Run again without that flag after source reconciliation; only the strict run can pass without source-review blockers. Existing coverage ledgers written before the item-level `source_inventory`/`coverage_map` contract will be reported as incomplete until they are reviewed and normalized. Do not treat a missing map as an empty map or silently mark it complete.


## Exact runtime/DB handoff — validate before generation batches

A lesson candidate must be a full quantaedge.structured-lesson.v1 object, not merely the model response, source extract, benchmark record or SFT example. Preserve all extra metadata but include the runtime-required names exactly: curriculum.class (integer), curriculum.subject (code matching the curriculum-subject enrollment key), chapter.chapter_number/title_by_locale, topic.topic_number/topic_id/title_by_locale, artifact_id, source_refs, learning_objectives, explain.blocks, practice.questions and publication workflow state.

The content DB does not store lesson definitions. It stores student response rows using artifact_id, artifact_sha256 (hash of exact canonical JSON bytes), question_id (question id), block_id (activity block id), response JSONB and the authenticated student_id. The textbook PDF hash is provenance only and must never be substituted for artifact_sha256. IDs must fit artifact_id varchar(120), question_id varchar(180) and block_id varchar(180).

After source-bound generation, map the output to its real canonical NCERT chapter, then dry-run:
python scripts/register_structured_lesson_drafts.py --candidate-dir <run-output>/lessons --index data/structured-lessons/class-6/science/production-index.v1.json --chapter-number 7 --confirm-chapter-mapping

Only after checking the displayed file names, stable IDs, exact source book/hash and question contracts should --apply be added. The importer preserves richer keys, normalizes runtime routing keys, appends new paths to the production index, and keeps drafts unpublished. Finish with:
python scripts/validate_structured_lesson_artifacts.py --repo-root . --index data/structured-lessons/class-6/science/production-index.v1.json

The strict validator is wired into CI. Existing 39 topics and 312 questions are included in the same index check, but structural success does not clear pending rendered-source, science, bilingual, activity-safety or visual reviews.


**Storage invariant:** generated textbook pipeline data committed to Git stays under `data/`. The Admin Content Studio reads review-only candidate catalogs from the data directory; it does not need the same generated JSON copied under an application `public/` folder.
