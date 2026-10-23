# BPSC TRE Computer Science previous-year paper workspace

This folder is the durable home for the TRE 1.0, TRE 2.0 and TRE 3.0 source register, paper analysis and reviewed portable assessment packages for the QuantaEdge **TRE-4 · Computer Science** course.

## Planned course presentation

- Course: **TRE-4 · Computer Science**
- Chapter: **Prev years**
- Assessments: one independent practice test for each of TRE 1.0, TRE 2.0 and TRE 3.0
- Each attempt must use QuantaEdge's existing course-bound assessment engine so its scores, timing, attempts and question analytics contribute to the selected course and assessment.
- The source package must not encode a school class (6/7/8 or otherwise). Course/institution association belongs to a separate, validated import/deployment step.

## Current source status

- `source-register.json` records the source article and three readable paper-mirror URLs.
- `paper-analysis.json` records the article's published topic counts and flags discrepancies that must be checked against the PDFs.
- The PDF contents can be inspected from the linked mirrors, but the original binary files were **not downloaded into this checkout**. This runtime could read the indexed PDF pages but could not fetch the raw bytes. Do not mark the papers as downloaded or commit a placeholder in place of a PDF.
- Before storing source PDFs in this public repository, verify the reproduction/repository-distribution rights for each exact file. Publicly accessible does not by itself establish permission to rehost. Until that is established, keep verified source links and metadata here; add PDF binaries only after rights review.

## Review and release safety

All imported question/answer packages must remain `DRAFT`, `PENDING_HUMAN_REVIEW`, and `student_delivery_allowed: false` until a named reviewer checks the original question, options, proposed answer, explanation, source location, official key where available, and topic classification. AI-assisted answer explanations are proposals, not an official answer key.

Use the portable schema and validator documented in `docs/product/portable-assessment-pipeline.md`. No package file by itself creates a course, a chapter, an assessment database row, or student-visible content.
