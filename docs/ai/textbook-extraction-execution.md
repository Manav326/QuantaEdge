# Textbook extraction and AI Tutor grounding — durable execution tracker

This file is the durable checkpoint for the NCERT/SCERT extraction work. Continue from the first unchecked item when a conversation ends; do not restart at planning or repeat completed work. Keep implementation on `main` only (no branches or PRs), and update this tracker in the same commit as meaningful milestones.

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
- [ ] Run `scripts/quantaedge-class6-tutor-pilot.ps1` from the user's authorized local environment: dry-run first, then import as draft, and approve only after inspecting the source summary. Verify live tutor retrieval/citations. The four committed manifests and page JSONL files were independently audited on 2026-10-14: SHA-256 values and contiguous page numbering matched (1,252 pages; 1,221 TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW and 31 LOW_TEXT_REVIEW; no integrity issues). The API import/approval requires a local admin session and cannot be executed by a CI runner.
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

A GitHub Actions workflow (`.github/workflows/commit-class6-extraction-artifact.yml`) mirrors the verified Class 6 extraction artifact into `data/textbook-extractions/class-6/` on `main`, so a normal `git pull` retrieves the page JSONL and manifests without a manual artifact download. The mirror contains extracted text and reports only; original PDFs remain in GHCR. These files are extraction-only and are not approved or published lesson content.

## GHCR extraction to question-generation bridge

Run `scripts/build_extraction_bundle_from_ghcr_pages.py` to convert one committed manifest and sibling page JSONL into the existing `DRAFT_EXTRACTION_ONLY` bundle schema. The command requires a human-reviewed chapter-map JSON bound to that exact source `book_id` and SHA-256. Chapter codes must exist in the canonical Class 6–8 curriculum target matrix. Start from `docs/ai/ghcr-extraction-chapter-map-template.json`; leave it in draft status until the source book's contents and PDF page ranges have actually been checked. Chapters touching low-text, OCR-required, failed, or empty pages are reported and skipped. The adapter does not publish content.

For local generation, set `QUANTAEDGE_QUESTION_API_BASE_URL=http://localhost:11434/v1` and `QUANTAEDGE_QUESTION_MODEL` to the model tag already pulled into Ollama, then pass `--generate` to `scripts/generate_questions_from_extraction.py`. Exact loopback endpoints do not require `OPENAI_API_KEY` or `--confirm-external-processing`; a hosted endpoint still requires HTTPS, explicit confirmation, and a key. Candidate lessons/questions remain drafts and continue through the existing human review and Content Studio gates.

## Immediate Class 6 tutor pilot command

`powershell -ExecutionPolicy Bypass -File .\\scripts\\quantaedge-class6-tutor-pilot.ps1` runs the safe dry-run by default. `-Mode ImportDraft` sends the four validated book manifests and page files to the local admin API as draft source records. Set `QUANTAEDGE_ADMIN_SESSION` to the authenticated `QE_SESSION` cookie value in the user's terminal first; never paste it into chat. After reviewing the displayed official URLs, editions, page counts and SHA prefixes, `-Mode ImportAndApprove` requires the exact confirmation `APPROVE CLASS 6 SOURCES` before it imports and approves those four source records. The script uses artifacts already committed to `main`; it does not download books, overwrite local caches, import lessons, or publish student content. `LOW_TEXT` pages remain excluded from Tutor retrieval.

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
- [x] Add workflow .github/workflows/textbook-visual-analysis.yml to fetch the original PDF and matching manifest/page JSONL from a specified existing GHCR source-book image, build the additive visual inventory, run Ollama on the GitHub runner, execute focused tests, publish a run-versioned and stable per-book GHCR bundle, and commit checkpoint/index JSON to main.
- [x] Store a stable bundle index in data/textbook-visuals/<book_slug>/bundle-index.v1.json; it records source PDF hash, model/prompt version, GHCR package/tags and bundle SHA-256.
- [ ] Run the workflow on one exact Class 6 source image, inspect visual accuracy and runtime, and confirm GHCR publication plus the repository checkpoint commit. The source image name and the internal PDF/manifest paths must match the existing GHCR package; do not guess them.
- [ ] Review the sample captions and visible text, then run the next batch using the same book slug/model. Only after review should visual results be adapted into Content Studio lesson drafts.

Implementation commits:
- 448ba2fa — atomic per-asset checkpoints and resume support.
- 587d3ea9 — resume regression tests.
- e64e2d4e — GitHub-hosted visual pipeline workflow.
- deaa053a — resumed runs continue to the next unprocessed assets.
- 32a50abd — execution guide for resumable analysis.


- [x] Add a restore script, scripts/pull_textbook_visual_bundle.ps1, that reads the committed bundle index, pulls the exact versioned GHCR package, verifies SHA-256, and restores the complete asset bundle after a repository pull.
- [ ] Confirm the first workflow run on the actual existing GHCR source-book image; no real-book vision results or successful workflow run are claimed until that run completes.
