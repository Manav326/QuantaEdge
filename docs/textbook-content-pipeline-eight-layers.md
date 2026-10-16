# QuantaEdge eight-layer textbook content pipeline

## Layer contracts

Every run is bound to a stable book ID and the SHA-256 of the exact complete-book PDF edition. A checkpoint from another edition is never silently reused. All generated assets are experimental review-only candidates; no stage publishes to student delivery.

1. Source visual inventory: every original PDF page, embedded-image occurrence and page preview with hashes and source-page linkage.
2. Exhaustive visual candidate preparation: eligible assets are represented; batch size is a checkpoint interval rather than a total candidate cap.
3. Review catalog and attachment manifests: source lineage and immutable bundle references.
4. Visual AI analysis: local Ollama processes embedded-image and page-preview rows with an explicit 8,192-token context, 1,024-token output ceiling and 360-second request timeout. Failed outputs remain resumable; an unsupported model visual label is conservatively mapped to `uncertain` with a human-review limitation rather than silently accepted.
5. Evidence/correction ledger: each page, text record, visual row, analysis and accepted correction is linked. Human corrections are sidecars; raw Layer 4 results remain unchanged.
6. Curriculum/concept map: the Layers 6–8 script maps every bounded segment to chapter/topic/microtopic/concept candidates, bilingual terms, objectives and source pages. Uncertain chapter IDs remain unresolved, not guessed.
7. Premium lesson generation: the same result produces a structured bilingual lesson candidate with explanation, a supported worked example when source permits, recap, paired objectives and 1–6 context-appropriate practice questions. The eight registered question types are a catalogue, not a required distribution: no one-of-each rule, no equal type counts and no padding. Questions choose their renderer contract from their actual response shape and link to exact supporting pages. Existing V3 image candidates are attached only when book ID, whole-book PDF hash, original source page and content-addressed display hash match. Image-observation questions must cite an allow-listed candidate attached to the same segment; they remain blocked until that exact visual is approved. Candidate images remain SOURCE_REFERENCE_ONLY, publication_allowed is false, and student_renderable is false.
8. AI tutor datasets: a deterministic page-level NCERT retrieval corpus is separate from conversational fine-tuning/SFT examples. SFT rows are deduplicated by stable IDs and grouped into non-overlapping train/validation/test splits. Neither dataset is automatically approved, fine-tuned or served.

## Native Ollama and model benchmark

The script scripts/quantaedge_layers_6_8.py uses Ollama's native /api/chat endpoint and JSON mode. The existing scripts/ai_training_dataset.py generator also defaults to native loopback Ollama. Local generation does not require an OpenAI API key. An API key is required only when an operator explicitly chooses the external OpenAI-compatible provider; that branch requires HTTPS and explicit confirmation of external processing.

The manual workflow .github/workflows/quantaedge-layers-6-8.yml defaults to benchmark-only. Benchmark output is bound to the current prompt, lesson-renderer contract and visual-candidate fingerprint. If the renderer schema changes without changing the prompt, `refresh-benchmark` revalidates saved raw outputs and rebuilds lesson previews with zero model calls; altered sample hashes or mismatched source segments are rejected. It compares qwen2.5:1.5b and qwen2.5:3b on the same three Class 6 NCERT Science (Curiosity) segments with the same prompt. Its committed report records JSON/schema validity, structured-lesson validity, page-citation validity and CPU runner latency. It intentionally leaves production_model null and production_model_selection PENDING_HUMAN_REVIEW; it never automatically selects a production model. A small sample is a screening benchmark, not proof of educational quality.

## Resource budget, persistence and resumability

- Do not run all 18 Class 6–8 subject/language whole books in one hosted runner job. Dispatch one exact source book ID per run.
- GitHub-hosted runners are CPU-only for this design; assume no GPU and bounded RAM, disk and runtime. The job has a 360-minute hard ceiling, supports at most 20 model calls per generation run and defaults to six. A lesson receives at most two hash-verified image-candidate links from segment source pages; absence of candidates does not invent a placeholder image. Benchmark runs test at most three excerpts per model and pull only the configured candidate models.
- Hosted runners are ephemeral: model weights may need to be downloaded again on every workflow invocation. Durable state is the source-bound Git checkpoint committed to main, not the runner's Ollama volume.
- Context and generation are bounded. Page text is split into lossless fragments, the segment builder checks that no source characters were dropped, and each Ollama call caps generation at 3,072 output tokens with an 8,192-token context ceiling. Benchmark excerpts are at most two pages / 5,000 characters; each report sample is capped at 100 KB JSON.
- JSON/JSONL writes use temporary files and atomic replacement. Successful segments are checkpointed after a small batch; resume skips successful records. Failed output is retryable. A PDF, extraction JSONL, Layer 5 ledger, model, benchmark report, segment/context budget or generation-configuration fingerprint mismatch fails closed rather than silently mixing incompatible work.
- Retrieval output has one row per original PDF page, including explicit non-retrievable rows for pages with no extractable text. Empty-text pages remain in `unresolved_text_pages` and cannot be counted as mapped even when they share a generation segment with readable pages. SFT output is separate from retrieval text, and source groups do not cross train/validation/test splits.
- Budget exhaustion is resumable IN_PROGRESS, not false completion. Even after machine checks pass, outputs stay DRAFT_REQUIRES_HUMAN_REVIEW and publication_allowed remains false.

## Recovering the existing Class 6 Curiosity Layer 5 checkpoint

The one-time workflow `.github/workflows/quantaedge-layers-1-5-resume.yml` is deliberately triggered by a separate marker-file commit under `.github/triggers/`. It retries the existing failed vision checkpoint using local `qwen2.5vl:3b` with an 8,192-token context, commits every small batch to `main`, and rebuilds the effective analysis plus actual Layer 5 ledger for the exact Curiosity PDF hash. It never writes `COMPLETE` itself: the ledger builder computes completeness from every page and eligible visual, and the workflow fails if any gap remains. A failed/incomplete run leaves its raw checkpoint and ledger committed for inspection/retry.

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

## First bounded Class 6 generation run

The guarded workflow `.github/workflows/quantaedge-layers-6-8-generate-once.yml` is ready but deliberately not triggered by its own introduction. Only after the fresh benchmark contains a contract-valid `qwen2.5:1.5b` sample and the real Class 6 Curiosity Layer 5 ledger is `COMPLETE`, add or update `.github/triggers/generate-class6-curiosity.trigger` on `main` to execute the initial six-call experimental run. Preflight checks run before model download; each two-call batch is checkpointed and committed. The workflow cannot publish lessons or select the production model, and reports `IN_PROGRESS` when it has more segments to resume.


### Context-driven practice and source coverage

Layers 6–8 no longer requires two MCQs per segment. The prompt permits 1–6 distinct questions chosen from the structured renderer catalogue according to the source, objective and learner task. The schema validator rejects unsupported types, invalid type-specific payloads, out-of-segment page citations and image-observation questions without an exact allow-listed embedded-image candidate. Changing the prompt/contract version invalidates older benchmark reports and checkpoints; run a fresh benchmark before model generation.

This per-segment generator is not a substitute for the structured chapter-authoring coverage ledger. For complete lessons, every inventoried section/subsection, figure/panel, table, activity, example, special note, keyword, summary item, end question and Learning further task must be mapped to a concrete lesson block/activity/question or an explicit review/exclusion disposition. Use `scripts/validate_structured_lesson_coverage.py`; count-only inventories cannot pass as item-level coverage. The question-type counts in reports are descriptive only.


## Canonical output path, application schema and DB response keys

Layers 6–8 first writes generated lesson candidates under its run output folder. These candidates are not discovered as lessons by the runtime API. The canonical application format is one UTF-8 JSON file per topic with schema_version and artifact_type equal to quantaedge.structured-lesson.v1, saved under data/structured-lessons/class-{class}/{subject}/chapter-{NN}/topic-{NN}-{slug}.v1.json and registered in the matching production-index.v1.json.

Keep extra candidate metadata, but ensure the exact current keys exist: curriculum.class, curriculum.subject, chapter.chapter_number/title_by_locale, topic.topic_number/topic_id/title_by_locale, artifact_id, learning_objectives, source_refs, explain.blocks, practice.questions and workflow publication/review state. The API validates the chosen renderer contract and packages canonical JSON into the server JAR.

The DB does not persist the full lesson JSON. It keys question answers with artifact_id + artifact_sha256 + question_id; it keys activity responses with artifact_id + artifact_sha256 + block_id. artifact_sha256 is computed by the API over the exact final canonical JSON bytes; workflow.source_pdf_sha256 is a separate textbook-provenance hash. student_id comes from the authenticated request, never from model generation. Stable artifact IDs must fit varchar(120); question and block IDs must fit varchar(180).

After a human confirms the source/canonical chapter mapping, dry-run registration with scripts/register_structured_lesson_drafts.py and the target production index. The importer validates the precise source-book ID and whole-book PDF hash, preserves rich metadata and stable IDs, refuses overwrites, updates the index and never publishes. CI now validates the complete canonical index and all 39 existing topics against the application and DB-facing contracts.
