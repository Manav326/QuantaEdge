# QuantaEdge textbook pipeline: resume and recovery runbook

## Purpose

A rerun is not automatically a resume. A resume must reuse verified completed work, skip expensive stages that do not need to run, restore durable checkpoints when the runner is new, and fail before expensive work if an existing checkpoint conflicts with the requested inputs.

All generated pipeline data and savepoints belong under `data/`. Application code, workflow definitions, and documentation must not become alternate locations for generated data. Candidate and generated lesson artifacts remain review-only; publication is never enabled by a resume action.

## Dedicated resume buttons in Actions

For recovery from a phone or desktop, use the dedicated **Resume** workflow in the repository's Actions list rather than launching the broad preparation workflow and relying on its default mode:

| Layers | Actions entry | Locked execution mode |
|---|---|---|
| 1–3 | [Resume textbook preparation (Layers 1–3)](https://github.com/Manav326/QuantaEdge/actions/workflows/resume-textbook-visual-layers-1-3.yml) | `resume_preparation` |
| 4–5 | [Resume textbook visual pipeline (Layers 4–5)](https://github.com/Manav326/QuantaEdge/actions/workflows/resume-textbook-visual-layers-4-5.yml) | `resume_layers_4_5` |
| 6–8 | [Resume QuantaEdge Layers 6–8](https://github.com/Manav326/QuantaEdge/actions/workflows/resume-quantaedge-layers-6-8.yml) | `resume` |

These are small UI launchers over the same core workflows—not duplicate pipeline implementations. Each locks the operation to the relevant resume mode. The Layer 4–5 launcher defaults to one asset per checkpoint and skips candidate-pack/catalog rebuilding. The Layers 6–8 launcher requires the exact source book and model plus an explicit experimental-draft acknowledgement; its core preflight refuses to start if no matching checkpoint exists. Match the prior run's class, book/edition, and model. Do not use GitHub's **Re-run all jobs** action as a substitute for selecting the application's resume mode.


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