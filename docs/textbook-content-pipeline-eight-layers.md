# QuantaEdge eight-layer textbook content pipeline

Each class run is isolated by stable book ID and exact whole-book PDF SHA-256 edition. No automatic publishing; every generated output remains review-only.

1. **Source visual inventory** — every PDF page, embedded-image occurrence, and rendered page preview with hashes and source-page linkage.
2. **Exhaustive V3 candidate preparation** — every eligible item is represented. Candidate packs are review views, not a filter; no total candidate cap.
3. **Review catalog and attachment manifests** — admin catalog, source lineage, and immutable bundle references.
4. **Visual AI analysis** — Ollama analyzes all embedded-image and page-preview rows. A per-invocation batch size is only a checkpoint interval. Checkpoints include model/prompt and asset hashes.
5. **Evidence and correction ledger** — every page, text record, visual row, analysis result and accepted correction is linked. Corrections create a separate effective sidecar and rebuild Layer 5 without rerunning Layer 4.
6. **Curriculum/concept map** — chapter/section/topic/microtopic/concept map, prerequisites, bilingual alignment, advanced concepts and gap reporting. NOT_STARTED until implemented.
7. **Premium course generation** — grounded bilingual explanations, worked examples, activities, relevant visuals, recap and questions; completeness validation before draft import. NOT_STARTED until implemented.
8. **AI tutor datasets** — a source-grounded retrieval corpus and a separate conversational fine-tuning corpus with provenance, deduplication and train/validation/test separation. NOT_STARTED until implemented.

## Correction overlay

Path: data/textbook-content-pipeline/class-N/books/BOOK_SLUG/visual-analysis-corrections.v1.json

Schema: quantaedge.visual-analysis-corrections.v1. Top-level source_pdf_sha256 and corrections list. Each correction has asset_id, asset_sha256, status (PROPOSED/ACCEPTED/REJECTED). ACCEPTED also requires reviewer, reason and a supported analysis_patch. Edition or asset hash mismatch is a hard error. Raw Layer 4 output is never modified.

## Current execution scope

The class-wide workflow now defaults to Layers 1–5. Layers 6–8 must be implemented as additional stages that consume these versioned contracts; their status is explicitly NOT_STARTED, not complete. Layer 5 remains INCOMPLETE whenever source text or any eligible visual analysis is missing or failed.