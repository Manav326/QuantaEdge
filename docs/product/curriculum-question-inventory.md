# QuantaEdge curriculum content and question-bank baseline

**Status:** migration-derived seed inventory for a clean database, not a claim about any separately existing database. Update this report after editorial work is saved and reviewed. The current inventory is deliberately split between questions that exist and planned minimum targets.

## Source of truth and extraction status

The six candidate sources are official Hindi-language SCERT Bihar class/subject textbook PDFs. Each source entry is currently recorded as `UNVERIFIED`; every chapter-source link is `NEEDS_REVIEW`. The chapter names/orders in the seed migrations are a curriculum outline, not verified page-by-page extraction from these PDFs.

The repository's `scripts/scert_extract_review.py` can extract page text from a downloaded PDF, optionally OCR low-text pages, calculate a PDF SHA-256, preserve 1-based PDF page references, and produce a draft review JSON. **It does not import to PostgreSQL or publish to students.** There is no completed automatic PDF-to-CMS importer in the current code. The admin must verify the downloaded edition and map each chapter/page range, then author original explanations/examples/questions and review them before publication.

## Current question inventory

- Seeded canonical chapter rows in V6: **102** across six class-subject tracks.
- Source-mapped/verified chapters in the current migration baseline: **0 / 102**.
- Active, approved, real question rows in the clean-migration baseline: **4**. Three are MCQ and one is TRUE_FALSE; all are original QuantaEdge items, not claimed copies of textbook questions.
- Required minimum assessment target under V26: **997 questions** across the current chapter rows. This is a target only; it is not a count of prepared questions.
- Optional formats with a minimum target of one but not currently required in every chapter: MAP and SOURCE_BASED. For diagrams the migration marks Science chapters and selected diagram-friendly Maths chapters as required.

### Per class and subject

| Class | Subject | Seeded chapters | Actual active + approved questions | MCQ | True/False | Required minimum target | Verified chapter-source mappings |
|---|---|---:|---:|---:|---:|---:|---:|
| 6 | Mathematics | 15 | 0 | 0 | 0 | 141 | 0 / 15 |
| 6 | Science | 18 | 0 | 0 | 0 | 180 | 0 / 18 |
| 7 | Mathematics | 16 | 4 | 3 | 1 | 152 | 0 / 16 |
| 7 | Science | 18 | 0 | 0 | 0 | 180 | 0 / 18 |
| 8 | Mathematics | 16 | 0 | 0 | 0 | 154 | 0 / 16 |
| 8 | Science | 19 | 0 | 0 | 0 | 190 | 0 / 19 |
| **Total** | | **102** | **4** | **102 actual target formats counted separately** | | **997** | **0 / 102** |

### Required target breakdown by question type

| Question type | Required minimum targets | Current actual questions |
|---|---:|---:|
| MCQ | 102 | 3 |
| TRUE_FALSE | 102 | 1 |
| INPUT | 102 | 0 |
| MATCH | 102 | 0 |
| ORDER | 102 | 0 |
| ASSERTION_REASON | 55 | 0 |
| CASE_BASED | 102 | 0 |
| SHORT_ANSWER | 102 | 0 |
| LONG_ANSWER | 102 | 0 |
| NUMERICAL | 47 | 0 |
| DIAGRAM | 79 | 0 |
| MAP | 0 | 0 |
| SOURCE_BASED | 0 | 0 |

The `curriculum-question-targets.csv` file has one row for every **chapter × question type** combination (1,326 rows): actual count, minimum target, whether the type is mandatory for that chapter, and the target gap. `required_in_current_target_plan=false` does not mean the format can never be added; it means the present plan does not require it in every chapter.

## Existing original questions

| Class | Subject | Seed chapter code | Type | Actual questions |
|---|---|---|---|---:|
| 7 | Mathematics | `algebraic-expressions` | MCQ | 1 |
| 7 | Mathematics | `algebraic-expressions` | TRUE_FALSE | 1 |
| 7 | Mathematics | `simple-equations` | MCQ | 2 |

No verified questions are currently present in the other 100 seeded chapter rows after the generic template questions are disabled. The 997 required items are a **minimum editorial plan** (not a production promise or generated data).

## Editorial/source safeguards

1. Never mark a chapter or question source verified merely because the class/subject matches the book. Verify the exact edition, book title, chapter and PDF/printed page range.
2. Keep full textbook pages, scans and copied questions out of student delivery unless the relevant reuse rights are confirmed. Write original teaching explanations and assessment questions and retain precise source/page references.
3. New or edited questions start in DRAFT/review and content changes require a new review. Current student publishing validation supports MCQ, TRUE_FALSE, INPUT and NUMERICAL only; other question formats remain authorable as drafts but must not be published until their student interaction/grading workflow is complete.

