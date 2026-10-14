# Textbook extraction and AI Tutor grounding — durable execution tracker

This file is the durable checkpoint for the NCERT/SCERT extraction work. Continue from the first unchecked item when a conversation ends; do not restart at planning or repeat completed work. Keep implementation on `main` only (no branches or PRs), and update this tracker in the same commit as meaningful milestones.

## Goal
One reusable, provenance-preserving source of truth for whole-book PDF ingestion, page extraction, chapter mapping, Hindi/English alignment, question/lesson generation, and live AI Tutor retrieval for QuantaEdge classes 6–8 Maths and Science.

## Execution status

- [x] Add persistent per-page extracted text with source hash, PDF asset/document, class, subject, chapter, language, page number, extraction method, quality/status, and retry/error metadata (migration V95).
- [x] Add asynchronous, idempotent extraction worker: stream source PDF to a temporary file, extract embedded text, OCR low-text/scanned pages within the current 250-page OCR budget, save page-level progress, and retry failed/unresolved pages/books.
- [x] Add authorized admin endpoints to queue extraction and inspect status/progress; extraction requires CONTENT_EDIT and an approved asset with class/subject/language intake metadata.
- [x] Add source-aware search to tutor retrieval, filtered by class/subject/language and prioritizing the active chapter; preserve source title, edition, chapter, and PDF page metadata. CI/runtime verification remains pending.
- [x] Connect extracted-page retrieval to the existing tutor grounding context.
- [ ] Add focused tests for source filtering, language filtering, ranking, missing pages, and prompt provenance; run the final API/web CI; then pilot one approved Maths and one Science book with available Hindi/English editions and verify extracted page quality, chapter mapping, and live tutor citations.
- [ ] Connect extracted passages to lesson/question generation with provenance links and review gates; validate generated answers before publication.
- [ ] Align Hindi and English editions/contextual translations while preserving formulas, tables, diagrams, and scientific/mathematical terms; record translation status and source relationship.
- [ ] After the pilot is verified, scale extraction to the remaining authorized class 6–8 Maths and Science books.
- [ ] Update this tracker with commit SHAs, workflow result, and the exact next unchecked task after each work session.

## Current checkpoint (2026-10-14)

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

Latest validation workflow is running; do not claim the code is verified until its conclusion is successful. The tracker and tutor documentation are intentionally committed to the repository so work can resume at the next unchecked task. The source-ingestion admin screen now exposes the extraction action for approved complete-book assets. No book has been extracted in the live database from this implementation yet. Extraction statuses are READY, LOW_TEXT, OCR_REQUIRED, and FAILED; only READY pages can ground tutor responses. After CI passes, the next task is a real approved-book pilot and runtime SQL validation, then tests and generation/translation integration.

## Guardrails
- Existing whole-book GHCR/cache flow remains the input source; do not introduce chapter-download fallback when whole-book download is required.
- Extraction is asynchronous and never happens in a student request.
- A successful extraction is not approval to publish.
- Keep original PDFs and exact edition/language/source metadata.
- Never claim the full textbook corpus is available to the tutor until the end-to-end retrieval test demonstrates it.
