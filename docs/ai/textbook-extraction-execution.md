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
- [x] Run a GHCR-backed Class 6 pilot for complete-book PDF extraction in Hindi and English; preserve page text and source metadata as review-only JSONL manifests. The first run extracted 1,480 pages with no parser failures, but its broad subject filter included Social Science in the Hindi result; exact subject matching was fixed and a corrected rerun is required.
- [x] Add a draft-only import path for extracted GHCR page manifests, source-URL/hash validation, an explicit source-approval endpoint, and a CLI importer that defaults to dry-run.
- [ ] Run focused API tests for GHCR import/source filtering, complete the corrected Maths/Science-only pilot, import those page records into the QuantaEdge database, approve the sources, and verify live tutor citations.
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
- `999f95e1` / `a94826a2` — safe local importer for extracted page JSONL
- `e4b3be32` / `d095913d` / `f50f9897` — exact subject filtering and extraction/importer tests

The initial GHCR pilot workflow succeeded at https://github.com/Manav326/QuantaEdge/actions/runs/38578838985 and produced artifact https://github.com/Manav326/QuantaEdge/actions/runs/38578838985. The corrected exact-subject run and the latest API build still need to finish; do not claim the new V96 import API is verified until CI succeeds. The tracker and tutor documentation are intentionally committed to the repository so work can resume at the next unchecked task. The source-ingestion admin screen now exposes the extraction action for approved complete-book assets. The GHCR runner has extracted real Class 6 whole-book PDFs; extracted pages are currently in a private 90-day Actions artifact, not yet imported into the live QuantaEdge database. The new import API stores these pages without copying large original PDFs into PostgreSQL. Extraction statuses are READY, LOW_TEXT, OCR_REQUIRED, and FAILED; only READY pages can ground tutor responses. After CI passes, the next task is a real approved-book pilot and runtime SQL validation, then tests and generation/translation integration.

## Guardrails
- Existing whole-book GHCR/cache flow remains the input source; do not introduce chapter-download fallback when whole-book download is required.
- Extraction is asynchronous and never happens in a student request.
- A successful extraction is not approval to publish.
- Keep original PDFs and exact edition/language/source metadata.
- Never claim the full textbook corpus is available to the tutor until the end-to-end retrieval test demonstrates it.
