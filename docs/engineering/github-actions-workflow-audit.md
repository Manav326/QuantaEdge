# GitHub Actions workflow audit and consolidation

**Repository:** `Manav326/QuantaEdge`  
**Audit date:** 2026-10-17  
**Scope:** The workflow inventory on `main`, recent execution history (latest 100 repository-wide run records), caller/test/documentation references, and duplication between the current pipeline entrypoints.

## Decision rule

The number of workflow files is not the measure of how many independent operations the system needs. The audit distinguishes:

1. **Primary content pipeline entrypoints** — at most one for Layers 1–5 and one for Layers 6–8. These expose their prepare/run/benchmark/resume modes directly.
2. **Artifact producers and handoffs** — retained only when they create/validate a distinct artifact or transfer verified output to its next stage.
3. **Product release/verification workflows** — CI, deployment and the signed Android release remain separate because they have different triggers, credentials and safety boundaries.

A thin workflow whose only job is to set a mode on an existing reusable pipeline is a duplicate UI surface, not a separate capability. A one-time workflow that is superseded by the general mode selector is not retained.

## Canonical pipeline structure

| Pipeline | Single canonical entrypoint | Supported operation(s) |
|---|---|---|
| Layers 1–5 (textbook visuals and candidates) | `.github/workflows/textbook-visual-candidates-v3.yml` | `prepare_only`, `analyze_all`, `resume_preparation`, `resume_layers_4_5`. Resume is selected from this workflow's own dispatch UI; separate resume wrappers are removed. |
| Layers 6–8 (local Ollama generation) | `.github/workflows/quantaedge-layers-6-8.yml` | `benchmark`, `run`, `resume`, `refresh_benchmark`. Refresh is now a non-inference mode in the same workflow; no separate refresh workflow. |

The independent `.github/workflows/textbook-visual-analysis.yml` is retained as a specialist **single-book visual artifact** tool with a custom model/restart/stable-GHCR-publishing contract. It is not the class-wide Layers 1–5 launcher; its bespoke operation should be migrated before removing it. It is referenced by its execution guides. The main eight-layer learning-content path remains the two entrypoints in the table above.

## Redundant workflows removed

| Removed workflow | Reason |
|---|---|
| `agent-4-validation.yml` | Branch-specific validation duplicated by canonical CI for pull requests and no longer belongs to the main-branch release path. |
| `android-build.yml` | Manual duplicate of the installable APK job in `ci.yml`; the APK fetcher relies on the verified canonical CI run. |
| `quantaedge-layers-1-5-resume.yml` | Legacy hard-coded Class 6 Curiosity recovery path. Generic Layers 1–5 now exposes fingerprinted resume modes directly. |
| `benchmark-class6-curiosity-after-layer5.yml` | One-off benchmark chained only to the retired Class 6 recovery workflow; the canonical Layers 6–8 entrypoint has benchmark mode. |
| `quantaedge-layers-6-8-generate-once.yml` | One-time generation continuation already covered by the canonical `run`/ `resume` modes with preflight and explicit review-only acknowledgement. |
| `quantaedge-layers-6-8-refresh-benchmark.yml` | Duplicate refresh-only workflow; the same no-inference operation is now `refresh_benchmark` in the canonical Layers 6–8 workflow. The report is not a push trigger, avoiding self-trigger loops. |
| `resume-textbook-visual-layers-1-3.yml` | Thin launcher over `textbook-visual-candidates-v3.yml`; use `resume_preparation` in the main dispatch UI. |
| `resume-textbook-visual-layers-4-5.yml` | Thin launcher over `textbook-visual-candidates-v3.yml`; use `resume_layers_4_5` in the main dispatch UI and choose the checkpoint batch size. |
| `resume-quantaedge-layers-6-8.yml` | Thin launcher over `quantaedge-layers-6-8.yml`; use `resume` in the main dispatch UI. |
| `.github/triggers/layers1-5-class6-curiosity.trigger` | Marker file existed solely to invoke the retired hard-coded Class 6 recovery workflow. |

## Remaining workflows and why each remains

| Workflow | Responsibility / why it is not another general Layers 1–8 pipeline |
|---|---|
| `ci.yml` | Required contract tests, API/web/Admin/People build and installable debug APK; canonical verified APK source. |
| `deploy.yml` | Explicit production deployment with protected environment and SSH credentials. |
| `android-play-store-release.yml` | Signed Play Store release bundle and signing secrets; distinct from debug APK. |
| `ai-training-dataset-validation.yml` | Validates the AI training dataset schema/contract on relevant data changes. |
| `ai-training-offline-dataset.yml` | Generates API-key-free review/draft dataset records and persists the artifact. |
| `audit-ai-training-dataset.yml` | Read-only review sample/export of the existing dataset; it does not generate or rewrite training data. |
| `audit-content-package-completeness.yml` | Read-only integrity/completeness audit for committed content ZIP artifacts. |
| `textbook-cache.yml` | Source-of-truth whole-book GHCR cache and repair queue for textbook inputs. |
| `curriculum-pipeline-audit.yml` | Audit report for textbook registry, cache/source presence and pipeline handoff integrity. |
| `textbook-extraction-pilot.yml` | Extracts and validates Class 7/8 whole-book page data into run artifacts. |
| `mirror-textbook-extraction-artifact.yml` | Validates and mirrors verified Class 7/8 extraction outputs from a source run to `data/`; the artifact handoff must not trust arbitrary workflow artifacts. |
| `textbook-visual-analysis.yml` | Specialist single-book visual-analysis artifact flow, retained pending a deliberate migration into the class-wide pipeline. |
| `textbook-visual-candidates-v3.yml` | Canonical Layers 1–5 pipeline. |
| `quantaedge-layers-6-8.yml` | Canonical Layers 6–8 pipeline. |

## Usage findings

The latest-100-run sample recorded:
- `ci.yml`: 64 observed runs — 18 successes, 9 failures, 35 cancelled and 2 pending/in progress.
- `textbook-visual-candidates-v3.yml`: 21 observed runs — 10 successes, 3 failures, 7 cancelled and 1 in progress.
- `quantaedge-layers-6-8.yml`: 13 observed runs — 10 successes, 1 failure and 2 cancelled.
- The obsolete `benchmark-class6-curiosity-after-layer5.yml` and `quantaedge-layers-6-8-generate-once.yml` each had only a skipped run in this sample. This agrees with the dependency graph: their upstream one-off chain was not the active general entrypoint.

The sample is not a full historical query. Manual deployment, release and audit workflows can be correctly quiet, so usage frequency alone was not used to delete them.

## Operational safeguards retained

- Resume modes verify book identity, source PDF hash, model/prompt/contract fingerprints and saved checkpoint status before expensive model startup.
- Generation remains review-only. A successful Actions run does not equal complete curriculum coverage or authorization to publish.
- Generated pipeline outputs remain in `data/`; workflows/scripts/docs are not alternate output storage.
- The APK downloader prefers a successful exact-head `ci.yml` run. It only accepts an older build after the compatibility checker confirms every intervening path is in the narrow approved data-only/installer allowlist; unexpected or incomplete comparisons fail closed.

## Expected operating model

For the general textbook lesson data pipeline, operators now have **two primary workflows**. Use the separate textbook cache/extraction artifact workflows only when the upstream source/page artifact itself must be created or repaired. Use artifact audit/export workflows only for their named artifact. Do not add another workflow just to expose a button for a mode already supported by a core workflow; add a mode/input to the canonical entrypoint instead.
