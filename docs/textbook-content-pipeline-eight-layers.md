# QuantaEdge eight-layer textbook content pipeline

## Layer contracts

Every run is bound to a stable book ID and the SHA-256 of the exact complete-book PDF edition. A checkpoint from another edition is never silently reused. All generated assets are experimental review-only candidates; no stage publishes to student delivery.

1. Source visual inventory: every original PDF page, embedded-image occurrence and page preview with hashes and source-page linkage.
2. Exhaustive visual candidate preparation: eligible assets are represented; batch size is a checkpoint interval rather than a total candidate cap.
3. Review catalog and attachment manifests: source lineage and immutable bundle references.
4. Visual AI analysis: local Ollama processes embedded-image and page-preview rows.
5. Evidence/correction ledger: each page, text record, visual row, analysis and accepted correction is linked. Human corrections are sidecars; raw Layer 4 results remain unchanged.
6. Curriculum/concept map: the Layers 6–8 script maps every bounded segment to chapter/topic/microtopic/concept candidates, bilingual terms, objectives and source pages. Uncertain chapter IDs remain unresolved, not guessed.
7. Premium course generation: the same generation result produces a structured bilingual lesson candidate with explanation, worked example, recap, two bilingual MCQs, objective references and page-level source links. Existing V3 image candidates are attached only when book ID, whole-book PDF hash, original source page and content-addressed display hash match. These images are unapproved review suggestions: rights remain SOURCE_REFERENCE_ONLY, publication_allowed is false, and student_renderable is false. They are visible only within the draft/admin review flow; a human must confirm relevance and rights before any approval.
8. AI tutor datasets: a deterministic page-level NCERT retrieval corpus is separate from conversational fine-tuning/SFT examples. SFT rows are deduplicated by stable IDs and grouped into non-overlapping train/validation/test splits. Neither dataset is automatically approved, fine-tuned or served.

## Native Ollama and model benchmark

The script scripts/quantaedge_layers_6_8.py uses Ollama's native /api/chat endpoint and JSON mode. The existing scripts/ai_training_dataset.py generator also defaults to native loopback Ollama. Local generation does not require an OpenAI API key. An API key is required only when an operator explicitly chooses the external OpenAI-compatible provider; that branch requires HTTPS and explicit confirmation of external processing.

The manual workflow .github/workflows/quantaedge-layers-6-8.yml defaults to benchmark-only. Benchmark output is bound to the current prompt/lesson-contract version (including the visual-candidate attachment contract); a stale report is rejected for generation. It compares qwen2.5:1.5b and qwen2.5:3b on the same three Class 6 NCERT Science (Curiosity) segments with the same prompt. Its committed report records JSON/schema validity, structured-lesson validity, page-citation validity and CPU runner latency. It intentionally leaves production_model null and production_model_selection PENDING_HUMAN_REVIEW; it never automatically selects a production model. A small sample is a screening benchmark, not proof of educational quality.

## Resource budget, persistence and resumability

- Do not run all 18 Class 6–8 subject/language whole books in one hosted runner job. Dispatch one exact source book ID per run.
- GitHub-hosted runners are CPU-only for this design; assume no GPU and bounded RAM, disk and runtime. The job has a 360-minute hard ceiling, supports at most 20 model calls per generation run and defaults to six. A lesson receives at most two hash-verified image-candidate links from segment source pages; absence of candidates does not invent a placeholder image. Benchmark runs test at most three excerpts per model and pull only the configured candidate models.
- Hosted runners are ephemeral: model weights may need to be downloaded again on every workflow invocation. Durable state is the source-bound Git checkpoint committed to main, not the runner's Ollama volume.
- Context is bounded. Oversized page text is split into lossless fragments, and the segment builder checks that no source characters were dropped.
- JSON/JSONL writes use temporary files and atomic replacement. Successful segments are checkpointed after a small batch; resume skips successful records. Failed output is retryable. A PDF, extraction JSONL, Layer 5 ledger, model or prompt fingerprint mismatch fails closed rather than silently mixing editions.
- Retrieval output has one row per original PDF page, including explicit non-retrievable rows for pages with no extractable text. SFT output is separate from retrieval text, and source groups do not cross train/validation/test splits.
- Budget exhaustion is resumable IN_PROGRESS, not false completion. Even after machine checks pass, outputs stay DRAFT_REQUIRES_HUMAN_REVIEW and publication_allowed remains false.

## Execution order

1. Run Layers 1–5 and verify the source's Layer 5 coverage ledger is COMPLETE for the exact whole-book PDF hash.
2. Dispatch the Layers 6–8 workflow in benchmark mode and review the committed comparison and sample output quality.
3. Decide the production model separately after checking Hindi fluency, factual/math correctness, answer keys, source linkage and practical latency. Run mode requires a successful benchmark sample and a separate explicit experimental-draft acknowledgement.
4. Use one book ID per run and repeat the same inputs until the per-book manifest has no pending segments. Review the map, lessons and both datasets; do not publish or fine-tune drafts.

## Correction overlay

The visual correction overlay remains at data/textbook-content-pipeline/class-N/books/BOOK_SLUG/visual-analysis-corrections.v1.json using schema quantaedge.visual-analysis-corrections.v1. Accepted entries require exact PDF/asset hashes, reviewer, reason and supported patch. Edition or asset mismatches fail closed, and raw visual analysis is not mutated.

## Direct local commands

From a clean clone on the machine that already runs Ollama, first ensure the source's Layer 5 coverage ledger is complete and the Ollama model is installed. The endpoint must be loopback (the script will refuse remote endpoints).

Benchmark sample candidates:
`python scripts/quantaedge_layers_6_8.py benchmark --repo-root . --source-book-id ncert-c6-english-fecu1 --endpoint http://127.0.0.1:11434 --models qwen2.5:1.5b,qwen2.5:3b --sample-segments 3 --output data/textbook-content-pipeline/model-benchmarks/class-6-ncert-curiosity.v1.json`

Resume a small review-only generation batch:
`python scripts/quantaedge_layers_6_8.py run --repo-root . --class-no 6 --source-book-id ncert-c6-english-fecu1 --endpoint http://127.0.0.1:11434 --model qwen2.5:1.5b --max-model-calls 6 --checkpoint-every 2 --allow-experimental-model --commit-checkpoints`

The same command can be repeated to resume. The model must first have a contract-valid sample in the committed benchmark report. Pick matching class and exact source-book ID for other tracks; do not reuse the Class 6 Science book ID for Class 7/8.

The existing general-purpose SFT generator is also Ollama-first:
`python scripts/ai_training_dataset.py generate --input-dir private-review --output data/ai-training/generated/drafts.jsonl --provider ollama --endpoint http://127.0.0.1:11434 --model qwen2.5:1.5b --target 25000 --batch-size 5 --max-api-calls 100 --generate`
