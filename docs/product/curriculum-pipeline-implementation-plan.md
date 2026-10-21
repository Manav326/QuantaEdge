# QuantaEdge textbook-to-bilingual-assessment implementation plan

**Saved on main:** 13 October 2026. A registry tag, chapter row, model request or JSON candidate does not by itself mean content is processed or student-ready.

## Scope and safeguards
- Classes 6–8 Mathematics and Science, Hindi and English independently.
- Complete books use class/language-specific GHCR images.
- The 2,166-question count in curriculum-question-targets.csv is a target, not authored/published content.
- Extracted source pages are internal review material; student lessons must be original and source-linked.
- AI output stays draft-only until human source, answer, language, suitability and rights review, CMS approval and explicit publication.
- Keep changes on main. Do not seed fake curriculum data. No database schema change without a demonstrated requirement.

## Ordered delivery checklist

### A. Whole-book registry
- [ ] Audit all six Classes 6–8 Hindi/English GHCR images.
- [ ] Validate index schema, safe PDF path, signature, SHA-256, byte size and actual page count.
- [ ] Separate complete, provisional, partial and unavailable books.
- [ ] Verify source, edition/session, class, subject and language; report missing images/books/tracks.
- [ ] Never substitute chapter-level downloads for whole-book requirements.

### B. Extraction and chapter distribution
- [ ] Produce page-addressable bundles per source edition, language, class and subject.
- [ ] Check page counts, duplicate/missing pages, blank text and OCR review flags.
- [ ] Validate unique chapter codes and non-overlapping 1-based page ranges.
- [ ] Account for every page as chapter content or reviewed front/back matter.
- [ ] Reconcile all 102 canonical chapters separately per language, preserving page citations.

### C. Original teaching materials
- [ ] Every chapter needs explanation, worked example, guided practice, independent practice and recap.
- [ ] Add useful diagrams, tables, graphs and activities; verify actual rendering.
- [ ] Verify learning objectives against the correct source, not just text/page presence.
- [ ] Review Hindi numerals/equations/units/Science terms and English suitability separately.
- [ ] Question-only candidates do not count as completed lessons.

### D. Questions and review
- [ ] Generate original questions in both languages with source-page citations.
- [ ] Cover target question types by class/subject/chapter with curriculum-question-targets.csv.
- [ ] Validate option shape, answer keys, numeric types, difficulty, duplicates, citations and language.
- [ ] Generate MATCH, ORDER, ASSERTION_REASON, CASE_BASED, SHORT_ANSWER, LONG_ANSWER, DIAGRAM, MAP and SOURCE_BASED as review-only drafts.
- [ ] Keep unsupported formats unpublished until interaction, scoring/rubrics and grading UI exist and pass tests.
- [ ] A named editor independently checks answers, provenance, OCR-sensitive details, originality and rights.

### E. Import and CMS
- [ ] Dry-run authoring bundles; verify track/chapter/source metadata and prevent collisions.
- [ ] Keep Hindi/English identities separate unless persisted data and renderer support bilingual variants.
- [ ] Compare CMS previews with student sections (समझें, अब खुद करें, Recap).
- [ ] Test editing, draft/review/publish/unpublish and language switching.

### F. Student acceptance
- [ ] For supported types: render → answer → submit → grade → feedback → saved attempt/progress.
- [ ] Unsupported assessment types remain drafts.
- [ ] Verify empty states, errors, reload/resume, scoping and mobile layouts.
- [ ] Test both languages independently against the real API/database.
- [ ] Resolve P0 source/coverage defects and human-review gates before declaring green.

## Execution order
1. Audit actual GHCR images and whole-book integrity.
2. Audit page extraction and chapter distribution for each language.
3. Generate complete teaching sections and question candidates by format.
4. Human review, draft-only import, CMS approval and publication.
5. Test student journeys and grading independently in Hindi and English.
6. Re-run audits, retain reports and resolve residual blockers.

## AI question generation
The generator will support the full planned question schema for candidate authoring. It can use QUANTAEDGE_QUESTION_MODEL (falling back to APP_AI_TUTOR_MODEL), QUANTAEDGE_QUESTION_API_BASE_URL (falling back to OPENAI_API_BASE_URL) and OPENAI_API_KEY. A local OpenAI-compatible Ollama endpoint can be selected explicitly. Actual model requests still require explicit --generate and --confirm-external-processing flags; model output is not factual verification.

## Audit tooling
scripts/audit_curriculum_pipeline.py is read-only and checks real pulled GHCR indexes/PDFs, extraction bundles, generated/reviewed drafts, target counts and optionally the live CMS list API. Tests cover hashes, unsafe paths, partial books, absent indexes, page/range defects and planned-versus-baseline counts. The manual GHCR workflow emits a strict asset report.