# Multimodal lesson authoring pipeline (Classes 6–8)

## Decision

QuantaEdge must not treat a textbook as plain text. The source-ingestion pipeline should preserve page-level text **and** visual evidence, then create a typed, reviewable lesson plan that can contain prose, equations, tables, diagrams, original illustrations, animations and interactive learning activities. Extraction records what the source contains; authoring decides what the student should experience. These are separate stages.

This document is an implementation contract, not a claim that multimodal authoring is already complete.

## Target flow

```text
Official whole-book PDF + curriculum map
  -> page text + page images + embedded figures/tables + source coordinates/hashes
  -> page/chapter understanding and visual inventory (AI suggestions, confidence, provenance)
  -> lesson blueprint (learning objective -> explanation -> worked example -> activity -> check -> recap)
  -> draft assets and typed lesson blocks
  -> automated validation + editor preview/review
  -> explicit approval and publish
  -> responsive student renderer + accessible non-animated fallback
  -> tutor retrieval over approved text, captions, labels and activity explanations
```

## 1. Extraction output: text and visual evidence

Keep the original PDF immutable in the existing whole-book GHCR registry. For every source page, preserve the existing source-book ID, PDF SHA-256, edition, language, page number, extraction method and quality status. Extend the extraction bundle with a versioned `visual_assets[]` inventory. Each candidate asset should contain:

- stable asset ID; source book ID and SHA-256; 1-based PDF page; page text hash;
- asset kind: `embedded_image`, `page_crop`, `table_candidate`, `chart_candidate`, `diagram_candidate`, `photo_candidate`, or `illustration_candidate`;
- source bounding box in page coordinates, original/crop checksum, image dimensions and media type;
- nearby heading/caption/text, extraction method, OCR/vision notes and confidence;
- rights/provenance status: `SOURCE_REFERENCE_ONLY`, `LICENSE_CONFIRMED`, or `GENERATED_ORIGINAL`;
- review status: `CANDIDATE`, `NEEDS_REVIEW`, `APPROVED`, or `REJECTED`.

Extract embedded PDF images when available, and render pages/crops for vector diagrams or figures that are not standalone image objects. Tables and graphs need a structured representation as well as a preview image when possible; preserve cell text, axes, units, legends, labels and ordering. Never silently drop a page because its text layer is empty: mark it for OCR/visual review. Keep source page numbering distinct from printed page labels.

AI vision may suggest an asset type, caption, relevant concept and likely chapter, but must not silently invent labels or scientific facts. Low-confidence, equation-heavy, chart-heavy or OCR-failed assets must be flagged for human review. Extraction must remain asynchronous and idempotent, just like text extraction.

## 2. Generate a lesson blueprint, not a wall of text

For each canonical curriculum chapter, create a structured blueprint tied to learning objectives and source-page ranges. The blueprint identifies *where* a visual or activity improves understanding and *why*. Example block sequence:

1. `concept_intro` — concise explanation and prerequisite.
2. `illustration` or `diagram` — visual model with labels and alt text.
3. `worked_example` — numbered steps, equations and optional synchronized highlighting.
4. `interactive_activity` — manipulate a number line, fraction model, angle, graph, food chain or experiment variables.
5. `knowledge_check` — one question with immediate explanatory feedback.
6. `recap` — short summary and key terms.

Not every lesson needs every block. Do not add decorative animation just to make a lesson look rich; use the simplest representation that teaches the concept accurately. The blueprint must distinguish a source-derived reference asset from a newly generated original asset.

## 3. Versioned, typed content-block contract

Use a versioned JSON content document (for example, `quantaedge.lesson.v1`) with stable lesson/block IDs and typed blocks, not arbitrary HTML/JS. Each block includes:

- `id`, `type`, `schema_version`, `order`;
- `content_by_locale` for `hi` and `en`, with paired stable IDs and translation status;
- `learning_objective_ids`, class/subject/chapter, difficulty where applicable;
- `source_refs[]`: source-book ID, source SHA-256, PDF page, and optional bounding box;
- `asset_refs[]`: approved asset IDs only for publishable blocks;
- `review_status`, reviewer, review timestamp and revision/version.

Initial supported block types should be:

- `rich_text`: headings, paragraphs, lists, glossary terms;
- `equation`: structured math expression plus readable fallback;
- `worked_example`: steps, intermediate states, answer and explanation;
- `image`: image URL/asset ID, caption, attribution and required alt text;
- `diagram`: structured labels/connectors/legend plus static fallback;
- `table` and `chart`: data/labels/units plus accessible text summary;
- `animation`: approved, sandboxed Lottie/SVG sequence or timeline with pause/replay and static fallback;
- `interactive`: allowlisted activity kind plus validated configuration and deterministic local logic;
- `knowledge_check`: assessment item reference, feedback and answer explanation;
- `recap`: key takeaways and vocabulary.

Do not execute model-generated JavaScript, HTML, remote embeds or arbitrary SVG scripts. Interactive blocks use a registry of trusted components with strict schemas, input bounds and deterministic grading. Store asset binaries in the approved asset/object store or a versioned media package; store metadata and references in the lesson document/database. Do not put large image/video bytes in PostgreSQL lesson JSON.

## 4. How assets are produced

Use this priority order:

1. **Reuse for analysis:** source page/figure is retained as private reference evidence, with exact provenance.
2. **Reuse for publishing only when permitted:** publish the source figure only after its licence/rights and attribution are confirmed.
3. **Create an original visual:** generate a new diagram/cartoon/illustration from a reviewed brief; avoid tracing copyrighted artwork or copying its distinctive style. Validate all labels and subject facts.
4. **Create a deterministic interactive model:** use hand-built, tested components for number lines, place value, fractions, geometry, simple graphs, classification and science simulations.
5. **Animation:** prefer a short, purposeful, pauseable sequence (e.g. water cycle stages, particle model, fraction equivalence) generated from a structured storyboard. Every animation must have a static image and text explanation fallback.

An AI-generated asset is a draft, not automatically approved. The asset record must store its generation prompt/brief, model/version, output hash, source references used for factual grounding, language-independent labels where possible, and reviewer decision.

## 5. Review and publication gates

An editor should be able to preview the exact student experience in Content Studio, including Hindi/English toggle, mobile layout, captions, alt text, activity controls, reduced-motion fallback and source-page links.

Automated checks must reject publication if:
- a referenced asset is missing, unapproved, or has a checksum mismatch;
- an image lacks meaningful alt text or a chart/table lacks a text equivalent;
- an interactive config fails its allowlisted schema or bounds;
- a block references the wrong class/subject/chapter/language;
- Hindi/English block pairs are missing or marked incorrectly;
- an equation, chart, unit, label or answer has a failed validation;
- source provenance/rights status is absent where required;
- an animation has no pause/replay and static fallback.

Keep separate statuses for extraction, asset review, lesson review and publication. Approving a source book must never approve its figures, generated assets, lessons, questions or animations automatically.

## 6. Tutor integration

The Tutor should retrieve approved lesson blocks and their explanatory text, glossary, diagram labels, chart/table summaries, activity rules and captions alongside the existing approved page-level source passages. Include source title, edition, chapter and page citations in the retrieved context. The Tutor may describe an approved visual or guide a student through an approved activity; it should not claim to have inspected an asset unless its textual description or structured representation was actually retrieved.

Do not fine-tune a model merely to add each new figure. Use retrieval from approved lesson/source records. For visual questions based on a student's uploaded image, handle that as a separate consented input capability; it is not implied by textbook extraction.

## 7. Delivery phases and acceptance criteria

### Phase A — visual inventory (first)
- Add versioned visual-asset metadata to the extraction bundle.
- Extract embedded figures and render page/crop previews with page/hash/bounding-box provenance.
- Produce a reviewable asset manifest; flag low-quality/OCR/complex diagrams.
- Test against the current four-book Class 6 pilot and ensure existing text extraction/import remains backward-compatible.

**Pass:** every page has a visual scan status, assets can be traced to the exact PDF hash/page, and no source image is published automatically.

### Phase B — structured lesson blocks
- Define and validate the `quantaedge.lesson.v1` schema.
- Add draft authoring/import support for typed blocks and asset references.
- Render a student lesson using text, equation, image/diagram, table/chart and recap blocks.
- Keep existing lessons working through a backwards-compatible adapter.

**Pass:** one lesson renders correctly on mobile and desktop, in both languages, with source citations and accessibility fallbacks.

### Phase C — interactive component library
- Implement a small trusted registry, starting with number line/place value, fraction model, geometry manipulator and simple graph explorer.
- Add deterministic tests for configurations, keyboard/touch input, correctness and fallback behavior.
- Add one science sequence animation only after its static storyboard and review flow work.

**Pass:** each activity works without network access, has predictable feedback, and can be used by keyboard/touch or replaced with a meaningful static explanation.

### Phase D — AI-assisted visual authoring
- Generate lesson blueprints and original visual briefs from mapped, quality-approved chapter content.
- Generate assets/activities as drafts, never as executable model output.
- Run structural, factual, bilingual, provenance, accessibility and rights checks before editor review.

**Pass:** a reviewer can approve/reject/edit each block and asset individually, then publish the lesson atomically.

### Phase E — Class 6 pilot, then scale
Start with one Maths concept where a visual changes understanding (number line/place value or fractions), then one Science concept (e.g. water cycle or states of matter). Verify the entire pipeline in Content Studio and the student app before expanding to all Class 6 chapters, then Classes 7–8.

## Current status

The repository already has page-level text extraction, source hashes, quality flags, GHCR manifests and a review-gated question-generation bridge. The above visual-asset inventory, typed lesson-block authoring, asset approval UI and interactive renderer are **planned requirements, not completed features**. Do not describe QuantaEdge as having automatic multimodal lesson generation until the Phase A–D acceptance criteria pass.
