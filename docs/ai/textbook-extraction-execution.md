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
- [x] Run the corrected GHCR-backed Class 6 pilot for complete-book PDF extraction in Hindi and English. It extracted 4 complete books (Maths and Science in both languages), 1,252 pages total, with zero parser failures; 1,221 pages passed basic text-quality checks and 31 were flagged LOW_TEXT for review/OCR.
- [x] Add a draft-only import path for extracted GHCR page manifests, source-URL/hash validation, an explicit source-approval endpoint, and a CLI importer that defaults to dry-run.
- [x] Add focused importer tests, exact class/language/subject/scope filtering, source/PDF SHA-256 checks, and review-gated API import/approval. API verification passed on commit `a0920c60`; the final GHCR API image publish was still running at the last check.
- [ ] Import the four extracted source books into the QuantaEdge database from the Actions artifact, explicitly approve each source after reviewing its metadata, and verify live tutor retrieval/citations. The runner cannot use a developer's local admin session, so the authenticated importer must be run from the user's authorized environment.
- [ ] Connect extracted passages to lesson/question generation with provenance links and review gates; validate generated answers before publication.
- [ ] Align Hindi and English editions/contextual translations while preserving formulas, tables, diagrams, and scientific/mathematical terms; record translation status and source relationship.
- [ ] After the imported Class 6 pilot is verified end to end, scale extraction to Classes 7–8 Maths and Science books.
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
- `a5841183` — V96 GHCR source-book index, keeping original PDFs in GHCR
- `44a4b769` / `03c75dd6` — validated draft import and explicit approval API
- `999f95e1` / `a94826a2` / `d7977dd3` — safe local importer for extracted page JSONL
- `ed0023dc` / `a0920c60` — Spring Boot 4 Jackson 3 compatibility and passing API verification
- `e4b3be32` / `d095913d` / `f50f9897` — exact subject filtering and extraction/importer tests

Corrected GHCR extraction workflow succeeded at https://github.com/Manav326/QuantaEdge/actions/runs/38579506350 and produced artifact ID `11902717615` (available until 2027-01-12). The API verification job passed at commit `a0920c604086a03ead0a075ef3dde6cd58e0f880`; the aggregate workflow was still finishing the API image build at the last status check. The tracker and tutor documentation are intentionally committed to the repository so work can resume at the next unchecked task. The source-ingestion admin screen now exposes the extraction action for approved complete-book assets. The GHCR runner extracted real NCERT Class 6 books: English *Ganita Prakash* (412 pages), English *Curiosity* (272), Hindi *Ganita Prakash (Hindi)* (304), and Hindi *Jigyasa* (264), each labelled Reprint 2026–27. The 1,252 extracted page records are in the private 90-day Actions artifact, not yet imported into the live QuantaEdge database. The new import API stores page text without copying the original large PDFs into PostgreSQL. Extraction statuses are READY, LOW_TEXT, OCR_REQUIRED, and FAILED; only READY pages can ground tutor responses. After CI passes, the next task is a real approved-book pilot and runtime SQL validation, then tests and generation/translation integration.

## Guardrails
- Existing whole-book GHCR/cache flow remains the input source; do not introduce chapter-download fallback when whole-book download is required.
- Extraction is asynchronous and never happens in a student request.
- A successful extraction is not approval to publish.
- Keep original PDFs and exact edition/language/source metadata.
- Never claim the full textbook corpus is available to the tutor until the end-to-end retrieval test demonstrates it.
