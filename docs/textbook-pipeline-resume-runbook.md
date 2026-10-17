# QuantaEdge textbook pipeline: resume and recovery runbook

## Purpose

A rerun is not automatically a resume. A resume must reuse verified completed work, skip expensive stages that do not need to run, restore durable checkpoints when the runner is new, and fail before expensive work if an existing checkpoint conflicts with the requested inputs.

All generated pipeline data and savepoints belong under `data/`. Application code, workflow definitions, and documentation must not become alternate locations for generated data. Candidate and generated lesson artifacts remain review-only; publication is never enabled by a resume action.

## Workflow UI: Layers 1–5 visual pipeline

Open **Actions → Prepare textbook visual candidates v3 (additive) → Run workflow**.

Select the class and the exact vision model used by the checkpoint. Set **analysis_mode** to one of:

- `prepare_only`: prepare and persist candidate packs/catalogs; do not run vision analysis.
- `analyze_all`: prepare candidate packs/catalogs, then run Layers 4–5. Use for a new source set or when candidate packs must genuinely be rebuilt.
- `resume_layers_4_5`: skip candidate-pack generation, attachment-manifest rebuild, candidate GHCR publication and catalog rebuild. Resolve and verify the same whole-book sources, rebuild only the temporary visual inventory required by Layer 4, preflight the per-book/model checkpoints, restore valid checkpoints from Git/GHCR, and continue Layers 4–5.

The resume action is intentionally conservative:
- It validates the selected whole-book PDF SHA-256 against the committed extraction manifest before analysis.
- A Git checkpoint is reused only when schema, source book, exact source PDF hash, model and prompt version match.
- If a Git checkpoint is absent, the workflow checks the per-book GHCR checkpoint. A valid checkpoint is restored before model startup.
- If no checkpoint exists for a book that has never started, the book may start from zero; other books still reuse their own checkpoints.
- If a checkpoint exists but its source/model/prompt fingerprint conflicts, or GHCR cannot be checked safely, preflight fails before the vision model is downloaded. Do not change the source/model inputs to force a resume.
- Layer 4 is checkpointed in bounded batches; Layer 5 coverage/effective-analysis ledgers are regenerated from the raw checkpoint. Human review remains required.

The temporary visual inventory must be reconstructed on a fresh runner because the image files used for inference are not all stored in Git. This is not the same as rebuilding the candidate packs. The resume mode skips candidate-pack creation and publishing, but still verifies and regenerates the Layer 1 analysis inventory required by Layers 4–5.

## Workflow UI: Layers 6–8 local Ollama pipeline

Open **Actions → QuantaEdge Layers 6–8 - local Ollama pipeline → Run workflow**.

- Use `benchmark` to benchmark models on the fixed Class 6 NCERT Science comparison set.
- Use `run` to start bounded draft generation for the selected class and whole-book source.
- Use `resume` to explicitly continue the same book/model from its committed generation checkpoints.

For `run` and `resume`, keep the class, exact `source_book_id`, generation model, and benchmark report the same as the prior attempt. Enable the experimental-model approval input as required. The script checks a fingerprint covering the source PDF/edition and extracted text, visual-candidate evidence, benchmark report, model, prompt version, lesson contract and segmenting configuration. Completed matching segments are reused; only pending or failed segments are attempted within the new `max_model_calls` budget. A fingerprint mismatch stops safely and preserves the prior checkpoint rather than silently overwriting it.

The workflow concurrency group treats `run` and `resume` as the same generation lane for a given class/source. This prevents an ordinary run and an explicit resume from racing over the same checkpoint. Benchmarking is a separate lane.

## Recovery matrix

| Event | Correct action |
|---|---|
| Runner cancelled during Layers 1–3 | Use `analyze_all` only if candidate packs are missing, invalid or intentionally being rebuilt. Otherwise use `resume_layers_4_5`. |
| Runner cancelled during Layer 4 | Use `resume_layers_4_5` with the same class, source set and vision model. Completed asset results should be skipped; failed or missing assets are retried according to the analyzer's checkpoint rules. |
| Runner cancelled during Layer 5 | Use `resume_layers_4_5`; Layer 5 is derived from the raw Layer 4 analysis and corrections, so it can be rebuilt without repeating successful vision calls. |
| Some books never started | Resume may start only those books without a checkpoint while reusing checkpoints for already-started books. |
| Source PDF hash or extraction edition changed | Do not force-resume. Rebuild the affected upstream extraction/inventory and invalidate only downstream artifacts bound to the old source. |
| Vision model or prompt version changed | Use a new checkpoint identity or a deliberate full analysis run; never treat another model's outputs as matching. |
| Layers 6–8 runner cancelled or model-call budget exhausted | Choose `resume`, preserve all matching inputs, and grant a new bounded model-call budget. |
| Benchmark or lesson contract changed | Re-run the appropriate benchmark/validation stage; generation checkpoints with a different fingerprint must not be silently reused. |
| A checkpoint is corrupt or has conflicting identity | Stop before expensive model startup, preserve the old file for diagnosis, and repair/restore from a verified checkpoint or explicitly start a new fingerprinted run. |
| Final output is incomplete | Keep it marked `IN_PROGRESS` or `INCOMPLETE`; never treat a successful workflow exit alone as content completeness. |

## Required checkpoint semantics

Every resumable stage must:
1. define a stable identity (class, whole-book ID, source SHA-256, language/subject/edition as applicable);
2. fingerprint every setting that can change its output (model, prompt, schema/contract, upstream artifact hashes and relevant configuration);
3. write atomic checkpoints after bounded work units and commit durable checkpoints only under `data/`;
4. verify and reuse successful units before any expensive compute;
5. retry only missing/failed units when the fingerprint still matches;
6. reject stale/corrupt checkpoints without deleting the previous valid copy;
7. expose explicit `REUSED`, `RESTORED`, `REBUILT`, `RESUMED`, and `NOT_STARTED` decisions in logs and summaries;
8. distinguish workflow status from data completeness, and keep all outputs review-only.

## Operating rule

Before cancelling or rerunning an Actions job, check its last completed step and the latest `data/` savepoint on `main`. Prefer the explicit resume mode whenever inputs are unchanged. Use full preparation only when upstream artifacts are missing, invalid, or intentionally changed.