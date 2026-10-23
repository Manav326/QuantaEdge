# QuantaEdge textbook pipeline: resume and recovery runbook

## Purpose

A rerun is not automatically a resume. A resume must reuse verified completed work, skip expensive stages that do not need to run, restore durable checkpoints when the runner is new, and fail before expensive work if an existing checkpoint conflicts with the requested inputs.

All generated pipeline data and savepoints belong under `data/`. Application code, workflow definitions, and documentation must not become alternate locations for generated data. Candidate and generated lesson artifacts remain review-only; publication is never enabled by a resume action.

## Automatic continuation for Classes 6–8 Layer 4–5

The joint recovery push marker `[resume-visual-layers-4-5-classes-6-8]` starts all three class jobs in parallel and uses `resume_layers_4_5`, the existing `qwen2.5vl:3b` Layer 4 checkpoint identity, and five-asset durable savepoints.

The separate `Resume Class 6–8 Layer 4–5 checkpoints` coordinator checks every five minutes. It dispatches a resume for each class that lacks a durable completion marker only when no Layer 4–5 workflow is queued or running. This covers the finite GitHub-hosted job duration: after a runner is cancelled by the time limit, the coordinator dispatches the remaining classes from their saved checkpoints rather than rebuilding candidate packs.

A class completion marker is written only after every selected whole-book Layer 5 ledger in that class reports `COMPLETE`. The coordinator turns off the active state once all three markers exist. This automation does **not** weaken the Layer 5 gate, turn `INCOMPLETE` into `COMPLETE`, or bypass source/PDF/hash checks; unverified checkpoints still require diagnosis rather than blind re-analysis. The continuation control and completion markers remain under `data/`.

## One entrypoint per pipeline

The general Layers 1–8 content pipeline uses two main workflow entrypoints. Choose the operation in the workflow's **Run workflow** form; separate resume-only wrappers have been retired.

| Layers | Actions entry | Mode |
|---|---|---|
| 1–3 preparation | [Prepare textbook visual candidates v3](https://github.com/Manav326/QuantaEdge/actions/workflows/textbook-visual-candidates-v3.yml) | `resume_preparation` |
| 4–5 visual analysis | [Prepare textbook visual candidates v3](https://github.com/Manav326/QuantaEdge/actions/workflows/textbook-visual-candidates-v3.yml) | `resume_layers_4_5` |
| 1–5 full preparation and analysis | [Prepare textbook visual candidates v3](https://github.com/Manav326/QuantaEdge/actions/workflows/textbook-visual-candidates-v3.yml) | `analyze_all` |
| 6–8 generation | [QuantaEdge Layers 6–8](https://github.com/Manav326/QuantaEdge/actions/workflows/quantaedge-layers-6-8.yml) | `benchmark`, `run`, `resume`, or `refresh_benchmark` |

For Layer 4–5 recovery, select the exact previous class, source edition, and vision model. The default checkpoint batch size is `5`; choose `1` when the smallest possible uncommitted work window matters more than savepoint overhead. For Layers 6–8, keep the class, exact `source_book_id`, model, benchmark report and checkpoint settings identical to the interrupted run. The core workflow's preflight requires a matching saved checkpoint for `resume` and refuses an incompatible or missing checkpoint before model download.

Do not use GitHub's **Re-run all jobs** action as a substitute for selecting the application's resume mode. A resume is an explicit data operation with source/model/contract validation, not a rerun of the previous runner.

## Workflow UI: Layers 1–5 visual pipeline

Open **Actions → Prepare textbook visual candidates v3 (additive) → Run workflow**.

Select the class and the exact vision model used by the checkpoint. Set **analysis_mode** to one of:

- `prepare_only`: restore or build the per-book candidate bundles and persist metadata/catalogs; stop before vision analysis.
- `analyze_all`: ensure each book has a matching candidate bundle, then run Layers 4–5. It naturally restores matching deterministic bundles, so it does not mean “force rebuild everything.”
- `resume_preparation`: resume a cancellation during Layers 1–3. The workflow fingerprints the current source PDF, extraction manifest, page JSONL, generated inventory and preparation scripts. It attempts to restore each book's deterministic GHCR artifact; if it cannot find a matching artifact, it rebuilds and pushes only that book before moving on. A completed book is saved in GHCR before the final Git catalog commit, so interruption between books does not discard its preparation work.
- `resume_layers_4_5`: skip candidate-pack generation, attachment-manifest rebuild, candidate GHCR publication and catalog rebuild. Verify the same sources, restore Layer 1 inventory and Layer 4 analysis checkpoints, process only missing/error/stale-context assets, then regenerate Layer 5 from the raw analysis and current corrections.

Per-book preparation bundles have deterministic tags derived from the whole-book source hash, extraction inputs, current Layer 1 inventory, relevant script hashes, Python/PyMuPDF versions, crop/preview settings and candidate schema. Each archive contains a manifest with file hashes. A bundle is only reused if the fingerprint, source identity, candidate schema, every bundled file hash, candidate count and all candidate asset paths verify. A registry 404 means the matching artifact is absent and that book may be rebuilt; a corrupt bundle, hash mismatch or non-404 registry error fails closed rather than pretending a resume worked.

The resume action is intentionally conservative:
- It validates the selected whole-book PDF SHA-256 against the committed extraction manifest before analysis.
- A Git checkpoint is reused only when schema, source book, exact source PDF hash, model and prompt version match.
- If a Git checkpoint is absent, the workflow checks the per-book GHCR checkpoint. A valid checkpoint is restored before model startup.
- If no checkpoint exists for a book that has never started, the book may start from zero; other books still reuse their own checkpoints.
- If a checkpoint exists but its source/model/prompt fingerprint conflicts, or GHCR cannot be checked safely, preflight fails before the vision model is downloaded. Do not change the source/model inputs to force a resume.
- Layer 4 is checkpointed in bounded batches; Layer 5 coverage/effective-analysis ledgers are regenerated from the raw checkpoint. Human review remains required.

The temporary visual inventory must be reconstructed on a fresh runner because the image files used for inference are not all stored in Git. This is not the same as rebuilding the candidate packs. The resume mode skips candidate-pack creation and publishing, but still verifies and regenerates the Layer 1 analysis inventory required by Layers 4–5.

## Layer 4–5 performance findings and guardrails (17 October 2026)

A review of the Class 7 and Class 8 Actions logs found that visual requests were processed sequentially by `qwen2.5vl:3b` on the standard four-core GitHub-hosted runner, with no GPU passed to the Ollama container. The recorded runner RAM was around 92–93% in the inspected batches, and completed image requests typically took about 82–110 seconds each. This inference cost, rather than workflow startup alone, is the main runtime driver.

The inspected English Science inventories contained 7,488 visual occurrence rows for Class 7 and 2,291 for Class 8. At roughly 90 seconds per request, those books alone would represent approximately 187 hours and 57 hours of sequential inference respectively, before other language/subject books, retries, and savepoint overhead. These estimates are rate-based, not completion predictions.

Each durable batch checkpoint also regenerates the Layer 5 effective-analysis/coverage ledger, uploads the Layer 4 checkpoint to GHCR, and commits the data under `data/` to `main`. Using batch size `1` repeated that full checkpoint sequence after every image. The workflow now defaults to batches of `5` (still explicitly selectable as `1` or `2`), reducing that savepoint overhead; at most four not-yet-durably-saved successful requests may need to be repeated if a runner is interrupted mid-batch. Matching committed/GHCR checkpoints still resume by source, model, prompt and asset fingerprints.

The Class 7 and Class 8 matrix jobs use separate, class-scoped data paths and the checkpoint writer rebases and retries a concurrent push. The matrix limit is therefore raised to two so the classes can use separate runners at the same time instead of making Class 8 wait for the entire Class 7 run. The analyser's progress summary now counts only successful rows whose asset hash, source, model, prompt and page-text-context hash match the current inventory; stale-context results do not masquerade as newly completed work. All generated analyses remain review-only with publication disabled.

## Workflow UI: Layers 6–8 local Ollama pipeline

Open **Actions → QuantaEdge Layers 6–8 - local Ollama pipeline → Run workflow**.

- Use `benchmark` to benchmark models on the fixed Class 6 NCERT Science comparison set.
- Use `run` to start bounded draft generation for the selected class and whole-book source.
- Use `resume` to explicitly continue the same book/model from its committed generation checkpoints.

For `run` and `resume`, keep the class, exact `source_book_id`, generation model, and benchmark report the same as the prior attempt. Enable the experimental-model approval input as required. The script checks a fingerprint covering the source PDF/edition and extracted text, visual-candidate evidence, benchmark report, model, prompt version, lesson contract and segmenting configuration. Completed matching segments are reused; only pending or failed segments are attempted within the new `max_model_calls` budget. A fingerprint mismatch stops safely and preserves the prior checkpoint rather than silently overwriting it.

The workflow concurrency group treats `run` and `resume` as the same generation lane for a given class/source. This prevents an ordinary run and an explicit resume from racing over the same checkpoint. Benchmarking is a separate lane.

## Generation preflight update

Both `run` and `resume` validate the source and checkpoint fingerprint before Ollama or model downloads. `resume` requires an exact saved checkpoint match; `run` may initialize a new checkpoint only when none exists, and rejects a conflicting checkpoint early. If the checkpoint is already terminal, the workflow reconciles outputs without downloading Ollama or making model calls. Draft approval is checked before inference startup.

## Legacy Layer 4 checkpoint reconciliation

When an older Layer 4 checkpoint lacks `page_text_context_sha256` and its matching inventory is not present in Git, the runner may restore that inventory from GHCR. Before backfilling legacy hashes, the pipeline must verify the restore marker against the prior pipeline index (book ID, PDF hash, registry package, immutable tag and tarball SHA-256). Only that exact restored inventory may be used to decide which successful analyses are reusable. If the binding is absent or conflicts, the pipeline fails closed instead of marking old results reusable or needlessly rerunning all visuals.

## Recovery matrix

| Event | Correct action |
|---|---|
| Runner cancelled during Layers 1–3 | Choose `resume_preparation`; matching books are restored from immutable GHCR bundles and only missing/unmatched books are rebuilt. |
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

## Resume recovery: Classes 6–8

Use the existing **Prepare textbook visual candidates v3 (additive)** workflow. In Actions, choose **Run workflow**, select the class, and set `analysis_mode=resume_layers_4_5` to continue saved visual analysis without rebuilding candidate packs. The core workflow supports single-class recovery and the combined Class 7–8 path; no extra workflow file is needed.

Resume preflight uses the shared `missing_artifact(message, expected_reference)` classifier. It recognizes explicit registry absence responses, including the exact ORAS/GHCR `failed to resolve <tag>: <package>:<tag>: not found` response, and does not mistake arbitrary proxy, upstream, or authentication errors for an absent checkpoint. Missing checkpoints are treated as not started only when no Layer 4 work is recorded; an existing partial checkpoint is never silently regenerated.

### Coordinator regression-test correction (18 October 2026)

The per-class continuation markers are generated from the fixed class set `(6, 7, 8)` using the same `class-{grade}.v1.json` naming pattern. The regression test checks that loop and naming pattern rather than requiring three duplicated string literals. Layer 4–5 remains resumable from the existing source-bound Git/GHCR checkpoints; this test adjustment does not alter any completion gate.

### Class 6 checkpoint restore fix (18 October 2026)

The Class 6 resume run exposed a format mismatch in the Layer 1 inventory restore step: current preparation bundles store files under a book-specific `slug-layer1/` directory and include `layer1-bundle-manifest.v1.json`, while the consumer assumed `inventory/` at archive root. The restore step now accepts the two known layouts, uses the canonical path-safe extractor for wrapped archives, checks the immutable GHCR tag against the embedded preparation fingerprint, validates the embedded per-file manifest against the current source manifest and page JSONL, and restores only verified inventory files. Unknown/mixed layouts, source/context mismatches, path traversal, or file-hash mismatches still fail safely.


## Bounded Layer 4–5 runs and emergency recovery

Each candidate-worker invocation processes at most 8 productive five-asset analyzer batches before it safely yields; checking already-complete books does not consume this budget. The final processed batch and Layer 5 ledger are committed before the yield; the successful yield is not recorded as class completion. The workflow-run coordinator continues from those checkpoints. The analyzer now normalizes legacy per-asset source metadata to the verified whole-book identity, and a no-progress guard stops a worker if it analyzes the same previously successful asset IDs without increasing the durable analyzed/failed counts.

When a stale all-class push run must be stopped before single-class recovery, pair the joint resume marker `[resume-visual-layers-4-5-classes-6-8]` with `[cancel-layer45-matrix]` in a commit that changes a file covered by the candidate workflow's push paths. That run is intentionally skipped after it replaces the in-flight all-class run. Then use a separate class marker for each class that needs to resume: `[resume-visual-layers-4-5-class-6]`, `[resume-visual-layers-4-5-class-7]`, or `[resume-visual-layers-4-5-class-8]`. These single-class push markers replace older in-flight runners for that same class. All paths still use existing source-bound Git/GHCR checkpoints; no checkpoint is discarded or rebuilt merely to resume.


## Per-class resume markers

Use exactly one class marker per push commit, and ensure the commit changes a file covered by this workflow's push paths. These markers start `resume_layers_4_5` on the existing durable source-bound checkpoints; class resume does not authorize publication.


### Class 8 resume lane verification

For Class 8 recovery, the push commit must include `[resume-visual-layers-4-5-class-8]`. The workflow's concurrency expression checks push markers before the manual `class_no` default so this push replaces only the Class 8 runner; the existing book/PDF/model fingerprints and saved checkpoints remain authoritative.

### Class 7 resume lane verification

A Class 7 retry after the workflow regression assertion update must carry `[resume-visual-layers-4-5-class-7]` so the candidate workflow selects only Class 7 and reuses the matching source-bound checkpoint.

### Class 7 bounded checkpoint resume

The corrected Layer 4–5 workflow limits each run to eight five-asset analysis batches (40 assets max per run) with a five-minute per-request timeout. Resume Class 7 from its matching durable checkpoint and do not mark the class complete on a safe yield.


### Class 8 bounded checkpoint resume

The corrected Layer 4–5 workflow limits each run to eight five-asset analysis batches (40 assets max per run) with a five-minute per-request timeout. Resume Class 8 from its matching durable checkpoint and do not mark the class complete on a safe yield.


### Class 6 productive-batch resume

Restart this class from its matching source-bound Git/GHCR checkpoint using the corrected budget rule: only productive five-asset analyzer batches count toward the eight-batch run limit. Already-complete books are checked without consuming the budget. [resume-visual-layers-4-5-class-6]


### Class 7 productive-batch resume

Restart this class from its matching source-bound Git/GHCR checkpoint using the corrected budget rule: only productive five-asset analyzer batches count toward the eight-batch run limit. Already-complete books are checked without consuming the budget. [resume-visual-layers-4-5-class-7]


### Class 6 productive-batch resume

Restart Class 6 from its matching source-bound Git/GHCR checkpoint using the corrected budget rule: only productive five-asset analyzer batches count toward the eight-batch run limit. Already-complete books are checked without consuming the budget. [resume-visual-layers-4-5-class-6]
