# GitHub Actions workflow audit

**Repository:** `Manav326/QuantaEdge`  
**Audit date:** 2026-10-17  
**Scope:** All workflow files under `.github/workflows/` on `main` (24 before this prune), their declared triggers, in-repository callers/documentation, and the latest 100 repository-wide Actions runs available during the audit.

## Executive result

The repository has several different classes of workflow: mandatory CI/release automation, source-data ingestion and validation, long-running textbook content generation, and dedicated recovery entrypoints. A workflow not appearing in the latest 100 run records is **not** proof that it is obsolete: manual release, audit, and resume workflows can be intentionally quiet.

**One confirmed obsolete workflow was removed:** `.github/workflows/quantaedge-layers-6-8-benchmark-once.yml`. It was a six-hour bootstrap benchmark triggered only by changes to its own workflow file, had no manual dispatch entrypoint, and had no references outside itself. The repository has a newer evidence-gated Class 6 benchmark stage (`benchmark-class6-curiosity-after-layer5.yml`) and the general `quantaedge-layers-6-8.yml` workflow already exposes a benchmark mode. Keeping the old self-triggered bootstrap created a duplicate path that could unexpectedly consume runner time when edited.

The similarly named `quantaedge-layers-6-8-generate-once.yml` was **kept**: its successful-benchmark gate is documented in `docs/textbook-content-pipeline-eight-layers.md` and explicitly asserted by `tests/test_workflow_resume_entrypoints.py`. It is part of the evidence-gated chain, not an orphan. The current reusable `quantaedge-layers-6-8.yml` and its dedicated resume wrapper are also retained.

## Workflow-by-workflow decision

| Workflow | Decision | Purpose / evidence |
|---|---|---|
| `agent-4-validation.yml` | **Keep** | Branch-specific validation for `agent/learning-content-ai-android`; the branch exists. Runs content regression tests, API verification, frontend builds, and Android tests/APK. It overlaps with core CI in some checks but protects a separate development branch. |
| `ai-training-dataset-validation.yml` | **Keep** | Schema/quality validation on AI training dataset-related changes and manual dispatch. Different from exporting an audit sample. |
| `ai-training-offline-dataset.yml` | **Keep** | API-key-free dataset generation and committed outputs. Required for the offline data-generation path. |
| `android-build.yml` | **Keep** | Manual Android-only build fallback. Dispatch-only by design, avoiding an extra automatic APK build on every app push. |
| `android-play-store-release.yml` | **Keep** | Separate signed release-bundle workflow using protected signing secrets. Not interchangeable with the debug APK build. |
| `audit-ai-training-dataset.yml` | **Keep** | Read-only audit/sample export of existing training data. Does not generate or modify the dataset. |
| `audit-content-package-completeness.yml` | **Keep** | Read-only ZIP content/package integrity audit plus tests; distinct from runtime import and dataset checks. |
| `benchmark-class6-curiosity-after-layer5.yml` | **Keep** | Evidence-gated Class 6 benchmark; starts only after the specific Layer 1–5 recovery workflow succeeds, then verifies the real Layer 5 ledger before model startup. |
| `ci.yml` | **Keep** | Canonical main/PR CI; includes contract checks, Android APK, API verification, and container builds. Required build provenance source for the APK installer. |
| `commit-class6-extraction-artifact.yml` | **Keep** | Validates successful Class 7/8 extraction artifacts and mirrors them into the repository. This is the bridge between extraction artifacts and committed pipeline data. |
| `curriculum-pipeline-audit.yml` | **Keep** | Offline/live registry and source-cache audit, including the textbook cache workflow handoff. Read-only diagnostics are distinct from ingestion. |
| `deploy.yml` | **Keep** | Explicit manual production deployment. Do not delete based on low run frequency. |
| `quantaedge-layers-1-5-resume.yml` | **Keep** | Dedicated recovery for the Class 6 Curiosity Layer 1–5 evidence ledger. A recent failure is a reason to fix/resume it, not remove it. |
| `quantaedge-layers-6-8-benchmark-once.yml` | **Removed** | Obsolete duplicate bootstrap described above; no external references, no manual entrypoint, and a newer benchmark path exists. |
| `quantaedge-layers-6-8-generate-once.yml` | **Keep** | Bounded generation after a successful fresh benchmark; cited by the pipeline runbook and covered by resume-entrypoint tests. The latest-100 sample included a skipped run, not evidence that its gate is unnecessary. |
| `quantaedge-layers-6-8-refresh-benchmark.yml` | **Keep** | Revalidates saved benchmark samples against the current lesson contract without additional model inference. This is a different operation from running a new benchmark. |
| `quantaedge-layers-6-8.yml` | **Keep** | Main reusable/manual pipeline for benchmark, bounded generation, and resume. Recent-100 snapshot showed repeated use, including successful runs. |
| `resume-quantaedge-layers-6-8.yml` | **Keep** | Dedicated user-facing resume entrypoint that calls the reusable Layers 6–8 workflow with resume mode. |
| `resume-textbook-visual-layers-1-3.yml` | **Keep** | Dedicated resume entrypoint for saved per-book preparation checkpoints; calls the v3 visual pipeline. |
| `resume-textbook-visual-layers-4-5.yml` | **Keep** | Dedicated resume entrypoint for saved Layer 4–5 vision analysis; preserves the model and checkpoint batch settings. |
| `textbook-cache.yml` | **Keep** | Maintains the whole-book cache/registry and repair queue used by downstream textbook workflows. |
| `textbook-extraction-pilot.yml` | **Keep** | Extracts the selected Class 7/8 complete source books and publishes validated data as run artifacts for the mirror workflow. |
| `textbook-visual-analysis.yml` | **Keep** | Specialized single-book analysis with custom vision model, explicit restart, and stable-tag publishing controls. It has a dedicated execution guide and is not the same as preparing a full bilingual v3 candidate set. |
| `textbook-visual-candidates-v3.yml` | **Keep** | Main additive Layers 1–5 candidate pipeline for Classes 6–8. Implements prepare/analyze/resume modes and is the reusable target for the two visual resume entrypoints. |

## Observed run activity

This is a **snapshot of the latest 100 repository-wide run records**, not a complete historical query for every workflow. Counts can include repeated checkpoint commits and cancellations caused by new commits arriving while work is queued; absence from this sample is not a basis for deleting a manual workflow.

| Workflow | Observed in sample | Snapshot result |
|---|---:|---|
| `ci.yml` | 62 runs | 17 success, 8 failure, 35 cancelled, 2 pending/in progress |
| `textbook-visual-candidates-v3.yml` | 22 runs | 11 success, 3 failure, 7 cancelled, 1 in progress |
| `quantaedge-layers-6-8.yml` | 13 runs | 10 success, 1 failure, 2 cancelled |
| `quantaedge-layers-6-8-generate-once.yml` | 1 run | Skipped by its gate |
| `benchmark-class6-curiosity-after-layer5.yml` | 1 run | Skipped; the upstream recovery did not satisfy the success gate |
| `quantaedge-layers-1-5-resume.yml` | 1 run | Failed; workflow remains needed for recovery |

## Guardrails for future pruning

1. Prefer one reusable workflow plus thin, purpose-specific resume entrypoints; do not remove entrypoints relied upon by the UI/runbook/tests.
2. Treat a manual release, deployment, audit, or resume workflow as needed unless its behavior is replaced and references are updated.
3. Before deleting a workflow, search its filename and workflow **name** in the repository, inspect `workflow_run`/reusable-workflow callers, check script/data dependencies, and review actual run history.
4. Do not infer "unused" from cancelled/skipped recent runs alone. In this repository, checkpoint commits and workflow gates naturally produce these statuses.
5. Keep generated pipeline artifacts under the established `data/` tree and keep the Android fetcher fail-closed whenever any intervening commit changes app/build-relevant paths.

## Android APK fetcher hardening included with this audit

The APK fetcher now queries the canonical `ci.yml` workflow specifically rather than searching the latest 100 runs from every workflow. This prevents frequent content-pipeline runs from crowding successful Android build runs out of the lookup window. It still prefers exact-head successful runs and allows an older artifact only after the strict remote commit comparison confirms that every intervening file is in the approved data-only/installer allowlist.
