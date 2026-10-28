# Class 6–8 bilingual textbook pipeline operations

## The one operator entrypoint

Use Actions → QuantaEdge Textbook Content Pipeline → Run workflow. This is the sole textbook workflow file and operator entrypoint. Cache, extraction, mirror, candidate preparation, Layers 6–8 and audit are separate jobs within this one file; no other textbook workflow file or automatic trigger remains. The default task is progress_report; it performs validation/reporting, not visual inference or lesson generation.

Choose one task deliberately:

| Task | What it does | What it does not do |
|---|---|---|
| progress_report | Builds a timestamped report by class, pair, and language | Does not download models or process content |
| refresh_source_cache | Updates the source-book registry/cache for the requested class/language | Does not run Layers 1–8 |
| repair_source_cache_queue | Processes only the explicitly bounded approved cache repair queue | Does not silently expand to every book |
| extract_and_mirror | Extracts verified whole-book text for the explicitly selected classes, then validates and mirrors that exact dispatch's artifacts | Does not generate lessons or publish content |
| prepare_layers_1_5 | Prepares/verifies Layers 1–3 by the exact bilingual pair; runs Layer 4–5 inference only if analysis_mode explicitly requests it | Does not launch unselected pairs |
| repair_layers_4_5 | Selects only pairs that fail the strict Layer 5 completion contract and resumes matching source/model/prompt checkpoints | Does not rerun pairs already proven complete |
| rebuild_corrections | Rebuilds only effective analysis/coverage outputs affected by accepted correction overlays in the selected scope | Does not rerun vision inference or alter Layer 4 raw output |
| layers_6_8 | Runs a selected benchmark, bounded draft-generation batch, exact-checkpoint resume, or benchmark refresh for one source book | Does not auto-select a production model, publish lessons, or approve content |
| audit_contracts | Runs offline source/contract checks; pulls live GHCR registries only when that input is enabled | A successful offline audit is not proof that all live books are complete |

A task is considered started only when an operator dispatches it. Workflow completion never dispatches more work. Failed work writes a blocker/status handoff; an operator examines the reason and selects the next task.

## Work units and dependency order

The eight scheduler/reporting units are stable bilingual pairs (each pair means one English and one Hindi source edition): Class 6 Science, Class 6 Maths, Class 7 Science, Class 7 Maths Parts I and II, Class 8 Science, and Class 8 Maths Parts I and II. A pair is the queue/report unit, not a combined source-book record.

0. **Stage 0 — source extraction:** verify whole-book manifest, edition, class, language, PDF SHA-256, sibling page JSONL, row identity and ordered 1-based source pages.
1. **Layer 1 — visual inventory:** extract stable visual IDs, page locations and hashes from the exact source PDF.
2. **Layer 2 — candidate preparation:** produce a review-only candidate pack whose rows resolve to inventory assets/pages/hashes.
3. **Layer 3 — catalogue/attachment handoff:** produce the candidate catalogue and bundle/index manifests. These are evidence packages, not approval.
4. **Layer 4 — raw visual analysis:** analyze eligible assets against the exact source, model and prompt version; each eligible asset has exactly one source-matched result or a recorded failure. Checkpoints preserve valid results.
5. **Layer 5 — effective analysis and coverage ledger:** apply only accepted correction overlays, reconcile source pages and visual IDs/hashes, and retain all unresolved/error/orphan evidence. A Layer 5 ledger cannot be COMPLETE while a required page/asset/text item remains unresolved.
6. **Layer 6 — curriculum/concept mapping:** requires a source-matched Layer 5 handoff and produces page-backed chapter/topic/microtopic mapping. Unresolved mappings stay visible.
7. **Layer 7 — structured lesson/question draft generation:** consumes validated mapping and source evidence, emitting renderer-contract-validated drafts with stable IDs and page provenance.
8. **Layer 8 — tutor retrieval/training material:** uses source-linked, quality-gated retrieval documents and training examples with split isolation. Ineligible evidence is not promoted into retrieval/training.

Every downstream step must validate its required inputs before expensive processing and validate its output before the handoff is marked complete. Missing input, schema mismatch, invalid hash, orphan reference, or incompatible class/language/book identity is a blocker, not a warning that can be ignored. Lessons and AI-tutor material remain review-only until separately approved.

## Immutable source identity and common handoff contract

The source identity for a work item is the pair ID plus both registered book IDs/slugs and each edition's whole-book PDF SHA-256. A latest image tag is a resolution hint, not a permanent source identity; each extraction artifact pins a digest/hash and downstream artifacts carry that exact value.

Each current.v1.json handoff is a versioned quantaedge.textbook-pipeline-handoff.v1 envelope, with an append-only sibling event log and a per-run snapshot. It carries:

- **Identity:** run/handoff ID, pair ID, class, subject/book label, source IDs, language, slugs, and source PDF hashes.
- **Compatibility:** stage/layer, input/output schema versions, and consumer contract version.
- **Lineage:** producer Git SHA, workflow/run URL and attempt, branch, parent handoff IDs, and input/output artifact paths with SHA-256, byte size, schema version and row count when available.
- **UTC timing:** created_at, started_at, last_progress_at, checkpoint_at, completed_at, last_error_at, retry_after, and updated_at, formatted as RFC 3339 UTC timestamps ending in Z.
- **Work/resource accounting:** attempts, completed batches, expected/processed/failed/unresolved counts, budget, model/prompt, and measured model startup/run durations where available.
- **Validation/decision:** schema, identity, hash and reference validity; exact blockers; status, reason code, retryability, next action, review requirement and the publication-disabled flag.

created_at is immutable for the saved stage history. last_progress_at changes only when durable work/counters change; an invocation or no-op status update is not progress. checkpoint_at marks each durable savepoint, including a budgeted CHECKPOINTED state. completed_at is set only for an actually contract-validated COMPLETE stage; NEEDS_REVIEW is a saved review state, not completion. Never overwrite an earlier timestamp with a later poll just because the workflow ran again.

The generated progress report has one timing row for every source book and each stage 0–8. It includes actual elapsed time, attempt and cumulative durations, expected total time, remaining-time estimate and the last progress timestamp. Layer 4 separately reports summed model-seconds and a remaining model-time estimate after at least five measured asset durations; model-seconds are not wall-clock time. Layers 6–8 share segment-generation calls, so their shared runtime is explicitly marked and must not be summed three times. Expected/remaining time is calibrated from the median of at least two completed measured runs for that layer and source class of work. When older artifacts have no timer or the sample size is too small, the report says Not recorded / estimate withheld; it never guesses elapsed time from file modification dates.

## Status and retry policy

- QUEUED: selected but not processing.
- RUNNING: the explicitly requested task has passed preflight and is working.
- CHECKPOINTED: a validated durable savepoint exists but the stage is not complete.
- COMPLETE: the output and all required cross-artifact checks passed.
- RETRYABLE_FAILED: the failure is known to be transient and a retry is appropriate after the recorded cooldown.
- BLOCKED: schema/type/identity/hash/path/page-map conflict, corrupted or stale checkpoint, missing prerequisite, unresolved required reference, or write/rebase conflict. Fix the cause before running more model inference.
- NEEDS_REVIEW: output exists, but a human must inspect/approve it.

The workflow does not automatically retry or infer the next action. Network/service interruption can be retried manually after cooldown, reusing the same source/model/prompt checkpoint. A model-budget exit is a CHECKPOINTED savepoint; dispatch repair_layers_4_5 or an exact Layers 6–8 resume only after confirming the source/model/prompt and current progress report. Missing extraction data is repaired at Stage 0, not retried as inference. Schema mismatch, stale hashes, orphan references and write conflicts are blocked pending correction and must not trigger blind inference reruns.

## Resource, concurrency and writer rules

- The central workflow serializes resource-heavy work for the selected branch; pair matrix fan-out has max-parallel: 1. Do not increase this until timing data proves safe throughput and output commits are demonstrably conflict-free.
- This avoids downloading/starting multiple vision models for the same period and avoids pair workers racing to write common catalogue/index files.
- Workers checkpoint to the caller's selected branch. They do not git pull --rebase origin main or push content checkpoints to a hard-coded main ref.
- Only the central workflow exists and is operator-dispatchable. Its job conditions select exactly one requested task. The former five-minute resume coordinator, standalone duplicate visual-analysis workflow, and the separate reusable cache/extraction/mirror/candidate/Layers 6–8/audit workflow files are removed.
- A GitHub Actions result of success is not itself content completion. The artifact contract and the saved handoff must verify it.
- No automated publication, source-edition substitution, cross-institution approval, or promotion from review-only draft state is performed by this pipeline.

## Progress reporting

The report includes an as_of UTC timestamp, overall/class/pair status, separate English/Hindi edition progress, eligible/analyzed/failed/remaining counts, completion percentage based on actual eligible work, handoff layer/status, workflow run ID, last progress/checkpoint/error clocks, retry-after value, blockers and the next action where present. The authoritative details are in each pair's current.v1.json and events.v1.jsonl under data/textbook-content-pipeline/handoffs/class-{N}/{pair_id}/.

A pair is complete only if both source editions independently pass the complete Layer 4–5 crosswalk: exact source hash and schemas; all required eligible assets have a single hash-matched raw result; effective analysis points to the current raw file/hash and retains the review-only status; the coverage ledger resolves every source page/eligible visual; zero missing/failed/orphan/unresolved required records; and the pipeline index points to the canonical artifacts with Layer 5 marked COMPLETE.
