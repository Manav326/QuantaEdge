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
- The exact source PDFs supplied in `data/TRE-4_PYQ.zip` are stored under `sources/` on this draft branch and are hashed by the inspection workflow. The source question inventory is an audit artifact, not an approved exam.
- Source-count reconciliation: TRE 1.0 has 120 questions (40 GS + 80 CS); TRE 2.0 and TRE 3.0 each have 150 (30 qualifying-language + 40 GS + 80 CS). TRE 3.0 preserves language IDs E-1 through E-8 and H-9 through H-30.
- Keep question wording, Hindi reconstruction, answer keys, explanations and rights review as separate release gates. A green extraction workflow does not mean the paper is ready for students.

## Review and release safety

All imported question/answer packages must remain `DRAFT`, `PENDING_HUMAN_REVIEW`, and `student_delivery_allowed: false` until a named reviewer checks the original question, options, proposed answer, explanation, source location, official key where available, and topic classification. AI-assisted answer explanations are proposals, not an official answer key.

Use the portable schema and validator documented in `docs/product/portable-assessment-pipeline.md`. No package file by itself creates a course, a chapter, an assessment database row, or student-visible content.
