# Textbook visual analysis checkpoint — run 38672043506

**State:** extraction and analysis pipeline completed, but the visual results are **review-only**. They are not lesson content and must not be auto-published.

Run: https://github.com/Manav326/QuantaEdge/actions/runs/38672043506  
Job: https://github.com/Manav326/QuantaEdge/actions/runs/38672043506/job/116217186582

## Source and durable outputs

- Book ID: `ncert-c6-english-fecu1` (Class 6 English Science / Curiosity pilot).
- Source PDF SHA-256: `7e6c3a6ba4f80aa15120f446fb66614c3cec7c17462414d410d7be66c3ca8b73`.
- Local repository folder: `data/textbook-visuals/class6-curiosity-en/`.
- Main sidecars: `visual-inventory.v1.json`, `visual-analysis.v1.json`, `visual-analysis-qwen2.5vl-3b.v1.json`, and `bundle-index.v1.json`.
- Evidence: extracted image assets in `assets/` and whole-page previews `page-0001.jpg` through `page-0272.jpg`.
- Exact immutable GHCR bundle tag: `ghcr.io/manav326/quantaedge-textbook-visuals:class6-class6-curiosity-en-run-38672043506`.
- Stable per-book GHCR tag at the end of the run: `ghcr.io/manav326/quantaedge-textbook-visuals:class6-class6-curiosity-en-latest`.
- The index pins the tarball SHA-256 and explicitly says `publication_allowed: false` and `review_required: true`.

The artifacts are committed on `main`; a normal `git pull` obtains them. The workflow uses GHCR as a versioned bundle store; it does not upload a conventional GitHub Actions downloadable artifact for this run.

## What the output actually says

The full analysis JSON has **173 result records**: **145** are `DRAFT_REQUIRES_REVIEW`, and **28** are `ANALYSIS_ERROR`. The summary's `assets_processed_this_run: 10` and `assets_selected: 146` describe the last resumable batch/checkpoint view, not a claim that the full book was analyzed.

Coverage is currently concentrated on PDF pages **1–10**, with **131** result records attributed to page 10. Although previews were rendered for 272 pages, the current analysis output does not establish coverage of the chapter body. The successful classifications were 80 `page_with_multiple_visuals`, 35 `photo`, 19 `illustration`, 7 `other`, 3 `diagram`, and 1 `uncertain`. Only 39 successful records had non-empty `concepts`; several sample captions are about gradients, publication front matter, QR codes, and decorative elements rather than teachable science diagrams.

Failure breakdown:
- 18 requests exceeded Ollama's 4,096-token context limit (observed requests around 4,193–4,353 tokens).
- 8 assets timed out.
- 2 outputs used a `visual_type` outside the supported classification set.

Every result in this bundle requires human review and every result has `publication_allowed: false`. A green Action means that the job and its checkpoint/bundle steps completed; it does not mean that visual captions are factually verified or ready for learners.

## Required usage in the agreed QuantaEdge pipeline

1. **Keep raw evidence and provenance together.** Bind each accepted visual to source book ID, exact source PDF hash, PDF page, asset ID/hash/path, extracted nearby text, model/prompt version, and reviewer decision. Do not detach a caption from its source page.
2. **Improve coverage and signal before scaling.** Current results over-represent embedded image fragments from front matter. The next analyzer pass should sample across chapter pages, include page-level context, suppress duplicate/decorative fragments where appropriate, and prioritize useful diagrams, graphs, tables, maps, scientific photographs, and illustrations over logos/background pieces. Preserve the existing v2 checkpoint; do not restart/destructively overwrite it.
3. **Repair failure causes.** Reduce the amount of surrounding text sent with each image and/or explicitly configure a viable Ollama context window; add bounded retries/backoff for timeouts; normalize/validate the enumerated `visual_type`; and test each case. Prompt/model changes must use a versioned checkpoint path so the v2 results remain recoverable.
4. **Create an editor-facing visual review queue.** Show a thumbnail, source page, nearby source text, proposed caption/type/concepts, limitations, and explicit Accept / Reject / Needs correction outcomes. Accepted and rejected decisions must be persisted with reviewer and timestamp. A model caption alone is not an approval.
5. **Map reviewed visuals to curriculum.** Match page numbers and source text to the correct chapter/microtopic and existing stable course/lesson IDs. When mapping is ambiguous, keep the asset unassigned for an editor; do not guess a chapter.
6. **Import accepted records as drafts only.** Content Studio should preview the figure in the learner's lesson flow and allow publish/unpublish. Use suitable approved figures in `समझें` and, where pedagogically helpful, `Recap`. Keep practice questions separate, tied to the same chapter/microtopic, and teacher-reviewed. Use the Hindi edition's source when appropriate and retain bilingual metadata/translations without changing the original source provenance.
7. **Connect to AI Tutor only after approval.** The tutor may retrieve accepted visual captions/labels and their lesson context; it must not retrieve raw unreviewed model output as trusted facts. Keep the source page available for audit.
8. **Gate expansion on the pilot.** Validate a chapter-body sample in Class 6 English Science first, then the matching Hindi Science edition and Class 6 Maths; expand to Classes 7–8 only after mapping, visual fidelity, learner display, bilingual behavior, and review/publish/unpublish paths pass tests.

## Immediate next implementation sequence

1. Add tests and fix candidate ordering/selection so batches cover educational chapter content instead of being dominated by small front-matter image fragments.
2. Fix context-limit, timeout, and schema failures while preserving the existing v2 bundle and making the new prompt/model version resumable independently.
3. Produce a stratified review manifest for chapter-body pages and have it checked against the original page previews.
4. Only then design/implement the Content Studio draft-import contract and lesson/tutor integration. Keep all imported records unpublished until human approval.

During diagnostic runs, set `publish_stable: false` so a partial or comparison run does not move the stable GHCR tag. Do not set `restart_analysis: true` unless a separate recovery copy of the current checkpoint has been verified.
