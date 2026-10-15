# QuantaEdge visual-content pipeline — persistent handoff

**Purpose:** Continue this work in a future chat without restarting discovery or repeating decisions.  
**Status:** Planning/audit only. This document adds a roadmap; it does not mean the review UI or import pipeline exists.  
**Last researched:** 2026-10-15  
**Repository:** \`Manav326/QuantaEdge\`, work directly on \`main\` unless explicitly instructed otherwise.

## Start here in the next chat

1. Read this document first.
2. Re-check current \`main\` HEAD and relevant GitHub Actions status before implementation; another commit may have landed since this document was written.
3. Read the linked current-state audit, existing analyzer workflow/script, Content Studio, learner renderer and Admin API before touching code.
4. Continue from **Phase 1 below**, not from zero.
5. Keep changes additive: do not replace existing extraction, chapter content, lesson blocks, Content Studio flows, current v2 checkpoint, or approved/published content. Add versioned outputs, new endpoints/tables and new UI. Commit directly to \`main\` as the project owner prefers, and check CI after each logical stage.

## Product outcome

Turn official, checksum-verified NCERT textbook visuals into reusable course media that can be placed contextually in QuantaEdge lessons. It is not enough to generate image captions.

For each accepted visual, the system must retain the actual media plus source provenance; explain the figure in context; propose a real class/subject/chapter/microtopic/lesson mapping; recommend its placement; let an authorised human review/correct/reject it in Admin; preview it inside the learner lesson; and only expose it to learners when the associated content passes the existing publication checks.

Target placements:
- **\`समझें\`**: inline with/next to the explanation where it teaches the concept.
- **Worked example**: adjacent to the example when it explains the problem or steps; preserve the example.
- **\`Recap\`**: inside the actual recap experience, not merely at the end of the general block sequence.

## Fixed decisions and guardrails

- No ChatGPT image-creation credit is required for the default path. Prefer exact source extraction, careful image processing and programmatic SVG/vector recreation where appropriate.
- Image-generation models are optional, not a pipeline dependency. Do not use generated imagery to replace scientific photographs, precise specimen images or source detail that matters. Never trust image-model-rendered text for labels.
- Preserve source page, exact source PDF SHA-256, edition/language, source book ID, asset/crop hash and coordinates, nearby page text/caption, processing/model/prompt version and reviewer decision.
- Preserve the original crop alongside any enhanced/recreated derivative. Record which processing changed it. Do not add unsupported scientific labels, arrows or relationships.
- Model captions are suggestions, not ground truth. Map only to existing catalog/course/lesson IDs. If mapping is ambiguous, leave unassigned.
- All new assets start as candidates/drafts; no automatic student publication and no automatic trusted AI Tutor grounding from unreviewed data.
- Keep practice questions separate. This work is for textbook visuals, not generating assessment questions.
- New lesson insertion must be non-destructive. Never replace a lesson's current blocks to import a visual. Use a new safe append/insert service/endpoint with transaction, ordering, audit trail and tests.
- Follow existing permissions/roles and central QuantaEdge branding. Enforce authentication and review/publish permission server-side.
- The owner prefers artefacts committed to GitHub so \`git pull\` updates local work. Continue durable/versioned output in Git and GHCR, but avoid committing redundant gigantic derivatives blindly; document what resides in Git versus the versioned bundle and provide integrity hashes/restore tooling.

## Current pilot and evidence

- Workflow run: https://github.com/Manav326/QuantaEdge/actions/runs/38672043506
- Job: https://github.com/Manav326/QuantaEdge/actions/runs/38672043506/job/116217186582
- Book ID: \`ncert-c6-english-fecu1\` — Class 6 English Science / Curiosity.
- Source PDF SHA-256: \`7e6c3a6ba4f80aa15120f446fb66614c3cec7c17462414d410d7be66c3ca8b73\`
- Sidecars in \`data/textbook-visuals/class6-curiosity-en/\`: \`visual-inventory.v1.json\`, \`visual-analysis.v1.json\`, \`visual-analysis-qwen2.5vl-3b.v1.json\`, \`bundle-index.v1.json\`.
- Whole-page previews were produced for pages 1–272. Versioned GHCR package: \`ghcr.io/manav326/quantaedge-textbook-visuals:class6-class6-curiosity-en-run-38672043506\`; stable tag then used: \`class6-class6-curiosity-en-latest\`.
- Index says \`publication_allowed: false\` and \`review_required: true\`.
- The run audit is committed at [docs/ai/visual-analysis-run-38672043506-review.md](visual-analysis-run-38672043506-review.md).
- Analysis contains 173 records: 145 \`DRAFT_REQUIRES_REVIEW\`, 28 \`ANALYSIS_ERROR\`; 18 context-limit errors, 8 timeouts, 2 unsupported \`visual_type\` responses. The 173 records are concentrated on PDF pages 1–10; 131 records are page 10. The records include front matter/decorative fragments and repeated content. Only 39 successful records have non-empty concepts. It does not demonstrate full useful figure coverage of the chapter body.
- Important: a green Actions result is successful execution/packaging, not proof of semantic quality or curriculum readiness. Do not tell the owner these assets are lesson-ready.

## Existing application support found

- Workflow: \`.github/workflows/textbook-visual-analysis.yml\`.
- Extractor: \`scripts/extract_visual_asset_inventory.py\`.
- Ollama analyser: \`scripts/analyze_visual_inventory_with_ollama.py\`.
- Admin navigation: \`apps/admin/app/components/admin-navigation.ts\`; curriculum tools include Content Studio, Textbook library and Source ingestion, but no visual-review queue was found.
- Content Studio: \`apps/admin/app/content/page.tsx\`. It supports \`IMAGE\` and \`DIAGRAM\` blocks, media URL/caption/alt/description, block ordering, learner preview and existing review/publish controls.
- Student renderer: \`apps/web/app/student/learn/LearnClient.tsx\`. It renders active \`IMAGE\`/\`DIAGRAM\` blocks by URL in block order. The dedicated recap is rendered separately after practice; placing an image in recap needs an intentional extension to recap data/rendering.
- Content APIs: \`services/api/src/main/java/com/quantaedge/api/AdminContentController.java\` includes block save/publish workflows and existing lesson-block types. Current Content Studio lesson save transmits the lesson's block list; do not use a careless read-modify-write import that could overwrite simultaneous edits.
- Text extraction is a separate, review-gated system: \`TextbookExtractionController.java\`, \`TextbookExtractionService.java\`, migrations including \`V96__ghcr_textbook_source_book_index.sql\`. These do not currently import visual-analysis records into lessons.
- No visual-specific review controller/table/media import path was found in this audit. Re-check the current tree before implementation.

## GitHub-hosted runner: yes, use it

Use the existing GitHub-hosted Actions runner as the repeatable extraction/processing worker. The runner is ephemeral, so nothing important may exist only on its disk. At the start of each run, verify the official whole-book source against the existing manifest/checksum; at the end and at checkpoints, persist versioned outputs to the repository and GHCR as appropriate. Preserve the project's \`git pull\` workflow for review manifests and deliverables. Never move the stable GHCR tag to a partial diagnostic result: set \`publish_stable: false\` for comparisons and partial runs.

The runner can do without image-generation credit:
- Download/checksum-verify PDF and its existing page-text JSONL/manifest.
- Extract embedded image objects and exact page-coordinate crops with PyMuPDF; also retain page previews for vector art.
- Render vector/page crops at a readable resolution when there is no embedded image.
- Create deterministic, repeatable crop/clean-up variants (rotation, trim, safe contrast/levels); keep original and derivative separately.
- De-duplicate repeated binary content while retaining every source-page occurrence/location.
- Generate original + derivative previews, contact sheets and a review manifest.
- Recreate suitable structured diagrams as real SVG/XML using templates/code; labels remain editable text. Use only source-verified labels/arrows/relationships.
- Run optional local vision-model comparisons in small, stratified batches. Check actual runner RAM/CPU/GPU, Ollama version, model download size and context window at runtime; do not assume GPU availability or that the larger model fits. Model is a helper, not the selector of record.
- Validate metadata/schema, hashes, asset dimensions, duplicate rate, page coverage and output package.
- Commit deterministic manifests/compact review artefacts to \`main\`; publish an immutable GHCR bundle with the full assets and checksums; move the stable tag only after a full validated run, not an experiment.

The runner does not run this chat's model internally. My contextual judgement can be used in this conversation when representative images/page context are actually surfaced or attached. For automated unattended runs, use deterministic checks and/or explicitly configured models; do not pretend this assistant silently runs inside the Action. External vision APIs must be opt-in, with credentials managed as GitHub secrets, clear cost/privacy choice and no silent fallback.

## Actionable implementation plan

### Phase 0 — preflight and protect current state

- [ ] Re-check current \`main\` HEAD and CI status. Check whether newer work or migrations landed after this plan.
- [ ] Verify the existing v2 inventory, analysis checkpoint and bundle checksums; keep a recovery copy/reference before any new analysis.
- [ ] Review the inventory JSON schema and candidate ordering in the existing scripts to diagnose why pages 1–10 dominate and why repeated crop hashes occur. Do not infer the root cause from summary alone.
- [ ] Confirm runner resource limits and Ollama version/model compatibility in a diagnostic step.
- [ ] No edits to v2 files; all fixes/version changes get separate v3 schema/output/checkpoint names.

### Phase 1 — candidate selection and image preparation (first coding stage)

- [ ] Add regression tests for candidate eligibility/order, page coverage, repeated binary hashes with multiple occurrences, page-preview fallback, context-limit failures and output provenance.
- [ ] Change candidate discovery/ranking so selection is stratified across actual chapter-body pages and sections, not just inventory order. Ensure page-level previews are considered when required for vector art; do not let hundreds of tiny embedded fragments crowd out diagrams/photos that matter.
- [ ] Retain page occurrence bounding boxes and nearby text for each candidate. De-duplicate bytes for storage if useful but preserve all page/occurrence links.
- [ ] Create candidate tiers: meaningful illustration/photo/diagram/chart/table/graph/map/equation; mixed page crop needing segmentation; likely decorative/logo/background; uncertain. Exclude or deprioritise decoration but retain a traceable reason.
- [ ] Provide deterministic crop export, image dimensions, sha256, page number, coordinates, original preview and source-text sidecar. Avoid destructive editing and retain original pixels.
- [ ] Produce a contact sheet and a small, stratified review pack (chapter-body pages across sections; include different visual types). Inspect it before scaling.
- [ ] Keep analysis checkpoint v2 unchanged; generate v3 files in a separate versioned folder/checkpoint.

### Phase 2 — optional model comparison (after better selection)

- [ ] Benchmark current \`qwen2.5vl:3b\` against a compatible newer model such as \`qwen3-vl:4b\` only if supported by the current Ollama version and runner resources. Pin exact model tags; don't use a floating tag.
- [ ] Give each model the same stratified representative crops plus only relevant nearby textbook text/caption/heading. Configure a tested context window and conservative image resolution; bound timeout/retries and validate the output enum/schema.
- [ ] Compare against the human-reviewed sample for visual-type correctness, hallucinated labels/relationships, useful-concept identification and correct curriculum mapping. Report results before picking a default.
- [ ] Optional external vision API comparison is opt-in only. No ChatGPT image-generation credits are part of the default workflow; no network API secret means no API call.
- [ ] Model produces suggestions/metadata only. It must never auto-approve or publish.

### Phase 3 — metadata and candidate pack

- [ ] Define a versioned \`visual-candidate\` schema with stable candidate ID; source book/hash/edition/language; all source page occurrences; crop hash/path/coords; original and processed asset references; nearby text/caption/heading; proposed type/summary/concepts/alt text; model/prompt/version and limitations; suggested course mapping and placement; and status.
- [ ] Make a representative Class 6 English Science chapter-body batch accessible for human inspection; correct wrong captions/mappings, then use those decisions as evaluation examples.
- [ ] Ensure an SVG recreation records its source candidate and a change/specification note. Validate SVG safety (no scripts/external references), shape/text bounds, font fallback and rendering in a browser.
- [ ] Keep original and new derivative linked; don't overwrite source evidence.

### Phase 4 — Admin review workflow (additive)

- [ ] Add a new admin review screen/navigation entry for textbook visuals; leave existing Content Studio, Textbook library, Source ingestion and review flows intact.
- [ ] Add the next unused database migration after inspecting the live repository migration list. Use new visual-candidate/review data tables rather than repurposing text extraction tables. Store state, reviewer ID/time, notes/edits, mapping, source references, asset hashes, processing versions and audit details.
- [ ] Add a dedicated secured API/controller for candidate list/detail, image/page preview, accept, reject, correction and mapping. Enforce current content view/review/edit/publish permissions server-side and record audit actions.
- [ ] Expose side-by-side source/candidate preview, page/text evidence, proposed caption and concept, course/chapter/lesson selectors populated from real catalogue data, placement selector (\`समझें\` / example-adjacent / recap), correction notes, duplicate warning and status history.
- [ ] Do not show a plain “Accept = publish” control. Acceptance approves the asset candidate; lesson inclusion is a separate draft-import step.

### Phase 5 — reusable media and non-destructive lesson import

- [ ] Add a private media registration/serving contract for reviewed assets. Do not use an untrusted public URL or depend on a GitHub web URL for production media rendering. Enforce auth for review media and the correct published-content access rules for student media.
- [ ] Store/import high-quality original crop or validated derivative; expose stable media ID and application-controlled URL. Keep full-resolution bundle/checksums in GHCR and the project's Git-backed artefact policy; choose committed derivatives carefully to avoid needless repo bloat.
- [ ] Add a new transactional append/insert API that reads current blocks and inserts only the selected visual without deleting/replacing existing blocks. Use optimistic content revision/version checks, sequence renumbering, duplicate checks and audit logs. A conflict should fail safely and ask the editor to reload.
- [ ] Let the reviewer choose location in teaching sequence. Add recap-specific visual content/rendering to the dedicated recap model so visuals really appear inside Recap.
- [ ] Open the existing Content Studio learner preview using the imported draft so the owner can see its real intended appearance. Do not auto-publish lesson or chapter.
- [ ] Keep language/edition provenance accurate. Captions and accessibility text can have reviewed Hindi/English variants, but don't change original visual labels as part of translation.

### Phase 6 — AI Tutor and validation

- [ ] Only expose accepted/reviewed visual descriptions and labels to tutor retrieval after they are linked to a published lesson/content scope. Preserve source page links for audit.
- [ ] Add automated tests for source checksum mismatches, bad/corrupt images, missing media, unsafe SVG, duplicates, ambiguous mapping, role/permission failures, optimistic concurrency, non-destructive insertion, preview parity, recap display, publish/unpublish and tutor retrieval gates.
- [ ] Validate the complete pilot end-to-end: extraction → source context → review/correction → accepted mapping → draft import → Content Studio preview → lesson review/publish → student display → tutor retrieval.
- [ ] After English Science passes, repeat against Hindi Science and Class 6 Maths. Expand to Classes 7–8 only when source fidelity, useful figure coverage, mapping accuracy, bilingual metadata and complete UI flow pass.
- [ ] Run relevant tests/build/Actions; inspect failures and reach green required workflows before considering the phase complete.

## Definition of done for one image

A visual is not “ready” just because a PNG/SVG file exists. It is ready for draft import only when:
1. It is a real, viewable asset and source checksum/page/crop provenance are verified.
2. It is instructional rather than decorative (or a reviewer has explicitly justified why it belongs).
3. Caption, labels, concepts and alt text match the source and nearby textbook context.
4. Existing course/chapter/microtopic/lesson mapping is verified or deliberately left unassigned.
5. Reviewer decision and edits are recorded.
6. The asset has a stable application media ID/URL and can be previewed in the student renderer.
7. Import is non-destructive and remains unpublished until the existing review/publication rules pass.

## Immediate next task

**Start Phase 0, then Phase 1 only.** Re-read the latest files and branch status; diagnose candidate ordering/repeated source content with tests; preserve v2; create a v3 diagnostic sample and inspect the evidence pack. Do not start the Admin UI or import implementation until the representative chapter-body sample proves the extraction/crop/metadata format is useful.

## Files to start from

- [Current run review](visual-analysis-run-38672043506-review.md)
- [Visual analysis execution guide](visual-analysis-execution.md)
- [Visual inventory execution guide](visual-inventory-execution.md)
- [Workflow](../../.github/workflows/textbook-visual-analysis.yml)
- [Inventory extractor](../../scripts/extract_visual_asset_inventory.py)
- [Ollama analyser](../../scripts/analyze_visual_inventory_with_ollama.py)
- [Admin navigation](../../apps/admin/app/components/admin-navigation.ts)
- [Content Studio](../../apps/admin/app/content/page.tsx)
- [Student lesson renderer](../../apps/web/app/student/learn/LearnClient.tsx)
- [Admin lesson API](../../services/api/src/main/java/com/quantaedge/api/AdminContentController.java)

**Do not restart discovery from scratch. Use this handoff and the current repository as the source of truth.**