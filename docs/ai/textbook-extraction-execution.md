# Textbook extraction and AI Tutor grounding — durable execution tracker

This file is the durable checkpoint for the NCERT/SCERT extraction work. Continue from the first unchecked item when a conversation ends; do not restart at planning or repeat completed work. Keep implementation on `main` only (no branches or PRs), and update this tracker in the same commit as meaningful milestones.

## Goal
One reusable, provenance-preserving source of truth for whole-book PDF ingestion, page extraction, chapter mapping, Hindi/English alignment, question/lesson generation, and live AI Tutor retrieval for QuantaEdge classes 6–8 Maths and Science.

## Execution status

- [ ] Add persistent per-page extracted text with source hash, PDF asset/document, class, subject, chapter, language, page number, extraction method, quality/status, and retry/error metadata.
- [ ] Add asynchronous, idempotent extraction worker: stream source PDF to a temporary file (avoid loading 250–500 MB books wholly into heap), extract embedded text, OCR only low-text/scanned pages, save page-level progress, and safely retry failed pages/books.
- [ ] Add authorized admin endpoints to queue extraction and inspect status/progress; do not publish extracted content or generated material automatically.
- [ ] Add source-aware search over extracted pages, filtered to the current class/subject/language and prioritizing the active chapter; preserve source/page citations and avoid fabricated provenance.
- [ ] Connect the extracted-page retriever to the AI Tutor prompt alongside existing lesson/mastery/chat context; add tests for source filtering, language filtering, ranking, missing pages, and prompt provenance.
- [ ] Connect extracted passages to lesson/question generation with provenance links and review gates; validate generated answers before publication.
- [ ] Align Hindi and English editions/contextual translations while preserving formulas, tables, diagrams, and scientific/mathematical terms; record translation status and source relationship.
- [ ] Run the API/web CI and extraction tests on `main`; pilot one Maths and one Science book in both available languages, inspect OCR quality/chapter boundaries, then scale to all class 6–8 books.
- [ ] Update this tracker with commit SHAs, workflow result, and the exact next unchecked task after each work session.

## Guardrails
- Existing whole-book GHCR/cache flow remains the input source; do not introduce chapter-download fallback when whole-book download is required.
- Extraction is asynchronous and never happens in a student request.
- A successful extraction is not approval to publish.
- Keep original PDFs and exact edition/language/source metadata.
- Never claim the full textbook corpus is available to the tutor until the end-to-end retrieval test demonstrates it.
