# Portable assessment authoring and course deployment pipeline

## Purpose

Assessment content is authored as a portable package before it is attached to a course. The portable source package must not require QuantaEdge's Class 6/7/8 curriculum shape or any other school-class model. A consuming product may present the same assessment as a previous-year-paper chapter test, a mock exam, a diagnostic, a topic-wise library or another supported experience.

For the TRE-4 work, the intended presentation is:

- Course: `TRE-4 · Computer Science`
- Chapter: `Prev years`
- Three separate full-length Computer Science practice assessments: TRE 1.0, TRE 2.0, TRE 3.0
- Each attempt, score and question response must be recorded by the existing course-bound assessment engine so course-level analytics remain coherent.

## Separation of content from presentation and access

The package contains only assessment content and source/review metadata. It does not contain `class_code`, a curriculum class ID, a tenant ID, a database course ID or a chapter ID. It must not synthesize global courses or make a test visible to every institution.

At import/deployment time, a separate validated binding must:

1. Resolve the active institution from authenticated tenant context; do not trust an institution ID supplied by the uploaded package.
2. Resolve the course's stable identity and verify the course is active and explicitly assigned/released to that institution.
3. Resolve the course's Computer Science subject track and the institution/course-owned `Prev years` chapter (or the configured presentation target for a different product).
4. Reject a missing, ambiguous, inactive, unassigned or cross-institution target. A course name alone is not an identity.
5. Create one assessment per package with `course_id` set, immutable question snapshots, stable public test IDs, audit events, and normal assessment lifecycle states.
6. Keep source package and its answers in draft/review state. Publishing remains a separate, permissioned admin action.

Course binding is deliberately not part of `quantaedge.portable-assessment-package.v1`. This is what makes the ingestion and verification stages class-independent while preserving the backend's ownership rules.

## Portable package contract

Schema: `schemas/portable-assessment-package.schema.json`

Validator: `python scripts/validate_portable_assessment_package.py <package.json>`

Optional release-preparation checks:

- `--check-complete-answers`: every item must have a proposed answer, explanation and confidence above unresolved/low.
- `--check-public-repository`: requires public-repository rights clearance, verifies the PDF path is inside the repository and validates SHA-256 if supplied.

The validator is a structural gate only; it does not independently prove question correctness, source rights, topic classification or authorization to publish. Human review and deployment checks remain separate.

## Authoring pipeline

1. **Discover and register source.** Store a source URL, issuing authority, cycle/date and access status; never label a PDF downloaded until the exact local binary exists and its checksum is calculated.
2. **Rights and fidelity review.** Record whether storing/re-distributing each exact source PDF in the public repository is cleared. Check the complete PDF's printed instructions and booklet series.
3. **Extract source map.** Keep the original question number, PDF page (1-based), printed page when different, option labels and topic tags. Preserve source wording only where reproduction is authorized; otherwise create separately authored questions based on the topic/skill and retain the original source link.
4. **Propose answers independently.** Store an answer proposal separately from the options, alongside explanation, source/key basis and confidence. Compare official keys when available. Never change source questions silently to make a selected answer seem correct.
5. **Reconcile the paper.** Verify that each Computer Science paper has exactly the expected number of unique questions. Primary topic counts must be mutually exclusive and total 80/100%; secondary tags may overlap but must not be summed as primary counts. Identify disputed/deleted items and booklet-series/translation differences.
6. **Validate package.** Run the structural validator and the full-answer check. Add automated tests for duplicate IDs, missing options, invalid answer keys, class leakage and attempted publication.
7. **Import as draft.** A course-aware importer should atomically create three `ADMIN_CREATED` assessments in the chosen course/chapter, preserve immutable snapshots and provenance, and write a single audit event for each import/action. Import must be idempotent by package ID + version/hash.
8. **QA the consumer.** Confirm the Student course chooser, chapter list, test launch, timer, save/resume, submit, result release, question/topic performance analytics and parent-facing course report all use the same course and institution scope.
9. **Human review and release.** Review answer/key/explanation and source permissions, then publish each test explicitly. Draft packages must never be made student-visible as a side effect of extraction or import.

## TRE paper source findings — 23 October 2026

The source page is `https://csestudy247.com/download-bpsc-tre-1-tre-2-tre-3-computer-science-question-paper-pdf/`. The source register and reported topic counts are in `data/TRE-Papres/`.

- The article reports 80 Computer Science questions per cycle.
- Digital Electronics and Operating Systems appear as recurring high-weight categories in its analysis.
- The article's TRE 2.0 topic counts sum to 76 (95%), despite the page claiming 80 and 100%. Recount from the complete source PDF before setting a distribution.
- The article says there is no negative marking, but the readable TRE 1.0 paper explicitly says one-fourth of the question's marks are deducted for a wrong answer. Per-cycle rules must be transcribed from each paper, not copied from the article.
- TRE 1.0 and TRE 2.0 and TRE 3.0 mirror PDF URLs are logged in `data/TRE-Papres/source-register.json`. Raw binary downloads are not present in this checkout; rights have not been confirmed for public GitHub redistribution.

## Bilingual fidelity and answer explanations

- Preserve the source English and Hindi prompts/options as independent locale values. Do not overwrite either one with a machine translation.
- Legacy Hindi-font conversion is a reconstruction aid only. Keep the raw extracted text and page reference; flag uncertain or incomplete output. Contextual reconstruction may use the paired English question, but it must not alter the intended meaning.
- Use short explanations: normally one or two direct sentences explaining why the answer is right.
- Use `solution_mode: STEPWISE` for calculation, formula substitution, algorithm tracing, or code output. Store 2–8 short ordered steps first, with localized steps for each source language where applicable. Do not jump directly to a final number or output.
- Each numerical/code explanation must be checked against the displayed source formula, symbols, values and options. A question with an ambiguous source expression, incomplete option set, or uncertain Hindi reconstruction remains blocked from release.
- The result view shows General Studies and Computer Science scored separately. A qualifying-language section is kept separate and excluded from the 120-mark scored total.
- The supplied TRE 3.0 booklet has **148 questions total**: 28 qualifying-language items, 40 General Studies and 80 Computer Science. Its pre-start instruction must state the 148-question count before creating an attempt. E-1 and E-6 are absent in this source and must not be fabricated.
- Paper-specific penalty is part of the immutable attempt snapshot. TRE 1.0 specifies a one-fourth penalty in its instructions; do not carry that value over to another cycle unless that paper itself states it.

## Current implementation boundary

This change establishes the class-neutral package contract, source tracking, preliminary article analysis, and an offline validator with tests. It does **not** claim that PDFs were downloaded, that question-level topic counts were independently recounted, that official answer keys were verified, that course TRE-4/Prev years exists in the live tenant, or that a package has been imported into the running database. The API import adapter and live consumer validation must be implemented only after the package content and source-distribution status are resolved; that adapter must use authenticated institution/course binding rather than inventing class mappings.
