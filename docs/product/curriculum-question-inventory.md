# QuantaEdge curriculum content and question-bank baseline

**Status:** migration-derived inventory for a clean database, not a live count from a separately running database. The report separates existing approved questions from editorial targets. Targets are not prepared questions and are never seeded as fake question rows.

## Source of truth and extraction status

The six candidate books are official Hindi-language SCERT Bihar textbook volumes for Classes 6–8 Mathematics and Science. SCERT's textbook catalogue and the Class 6–8 Maths/Science teacher handbooks are also registered as reference resources. All six book editions and all chapter/page mappings remain unverified in the current migration baseline. The chapter names/order in V6 are a curriculum outline and must not be treated as completed page-by-page extraction.

- SCERT Bihar textbook catalogue: https://scert.bihar.gov.in/textbooks
- Mathematics teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/mathematics-handbook-for-teachers-1753723347
- Science teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/science-handbook-for-teachers-class-6-8-1753723306
- Class 6 Mathematics — गणित भाग-6: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-6-1707973215
- Class 7 Mathematics — गणित भाग-7: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-7-1708060463
- Class 8 Mathematics — गणित भाग-8: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-8-1708403831
- Class 6 Science — विज्ञान भाग-1: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A5%8D%E0%A4%9E%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-1-1707973674
- Class 7 Science — विज्ञान भाग-2: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A5%8D%E0%A4%9E%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-2-1708062711
- Class 8 Science — विज्ञान भाग-3: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A5%8D%E0%A4%9E%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-3-1708404249

### Extraction and import pipeline

1. Download the exact official PDF edition and record its title/session; a URL or class/subject match alone does not prove the edition.
2. Run scripts/scert_extract_review.py with the PDF, class, subject, source URL, edition, and a reviewed JSON chapter/page map. It extracts text page-by-page with PyMuPDF; optional OCR uses Tesseract for pages with too little extractable text. It records the PDF SHA-256, page numbers, per-page text hashes, extraction method, unmapped pages, and review flags.
3. Review the extraction bundle against the PDF. Correct the chapter map and page ranges; OCR output must be checked visually because Hindi OCR can alter numerals, equations, scientific terms, and punctuation.
4. Write a separate EDITOR_REVIEWED_DRAFT authoring bundle containing original Hindi teaching explanations, worked examples, activities and questions. The importer rejects raw extraction bundles or any bundle containing extracted pages/extraction data.
5. Run scripts/import_reviewed_content.py --bundle reviewed-authoring.json for a dry run. For import, set QUANTAEDGE_ADMIN_SESSION to the value of the authenticated QE_SESSION cookie in a local shell and run with --apply; use HTTPS for remote APIs. Never commit the cookie or paste it into chat.
6. The importer creates DRAFT-only chapters/lessons/questions, leaves source mappings unverified, refuses to overwrite existing chapter codes, validates exact PDF page ranges, and stops if lesson codes collide. Review content in the authenticated Admin Content Studio, then explicitly verify sources and publish only after editorial checks. A failed multi-request import can leave draft records; inspect the CMS before retrying.

Extracted textbook text is internal review material only. Do not publish full textbook pages/scans or copied questions unless reuse rights are confirmed. Student content must be original teaching material and questions aligned to accurately cited sources.

## Current question inventory

- Seeded canonical chapter rows in V6: 102 across six class–subject tracks.
- Verified chapter-source mappings in the clean-migration baseline: 0 / 102.
- Existing active, approved original questions in the clean-migration baseline: 4 (3 MCQ and 1 True/False), all in Class 7 Mathematics.
- Planned minimum question-bank size under V27: 2,166 questions. This is an editorial target, not content already prepared or published.
- Remaining gap to the target at the current baseline: 2,162 questions.

The planned minimum per chapter is five MCQs, two True/False, three input questions, one match, one order, two case-based, three short-answer and one long-answer. Science chapters additionally require two assertion/reason questions and one diagram question. Mathematics chapters require three numerical questions; selected visual Maths chapters also require one diagram question. Map-based and source-based questions remain optional with zero minimum until they genuinely fit a chapter.

### Per class and subject

| Class | Subject | Seeded chapters | Actual active + approved questions | MCQ | True/False | Planned minimum target | Verified chapter-source mappings |
|---|---|---:|---:|---:|---:|---:|---:|
| 6 | Mathematics | 15 | 0 | 0 | 0 | 321 | 0 / 15 |
| 6 | Science | 18 | 0 | 0 | 0 | 378 | 0 / 18 |
| 7 | Mathematics | 16 | 4 | 3 | 1 | 344 | 0 / 16 |
| 7 | Science | 18 | 0 | 0 | 0 | 378 | 0 / 18 |
| 8 | Mathematics | 16 | 0 | 0 | 0 | 346 | 0 / 16 |
| 8 | Science | 19 | 0 | 0 | 0 | 399 | 0 / 19 |
| **Total** | | **102** | **4** | **3** | **1** | **2,166** | **0 / 102** |

### Planned minimum target by question type

| Question type | Planned minimum target | Current actual questions |
|---|---:|---:|
| MCQ | 510 | 3 |
| TRUE_FALSE | 204 | 1 |
| INPUT | 306 | 0 |
| MATCH | 102 | 0 |
| ORDER | 102 | 0 |
| ASSERTION_REASON | 110 | 0 |
| CASE_BASED | 204 | 0 |
| SHORT_ANSWER | 306 | 0 |
| LONG_ANSWER | 102 | 0 |
| NUMERICAL | 141 | 0 |
| DIAGRAM | 79 | 0 |
| MAP | 0 | 0 |
| SOURCE_BASED | 0 | 0 |

The curriculum-question-targets.csv file contains one row for every chapter × question type combination (1,326 rows). It records class, subject, chapter order/code/title, candidate source URL, source verification state, actual question count, planned minimum, whether that type is required for the chapter, and the remaining gap. This is the complete chapter-wise/question-type-wise planning dataset.

### Existing original questions

| Class | Subject | Chapter code | Type | Actual questions |
|---|---|---|---|---:|
| 7 | Mathematics | algebraic-expressions | MCQ | 1 |
| 7 | Mathematics | algebraic-expressions | TRUE_FALSE | 1 |
| 7 | Mathematics | simple-equations | MCQ | 2 |

The other seeded chapter rows currently have no active approved questions in the clean-migration baseline. Generic generated questions were intentionally disabled rather than presented as real curriculum content.

## Editorial and release safeguards

1. Do not mark a chapter or question source verified based only on class/subject. Verify the downloaded edition, exact chapter title, and PDF page range.
2. Keep raw extracted text separate from original authoring. The importer creates draft content only and never auto-approves or publishes questions.
3. New or edited question content returns to draft/review. The current publish validator permits only MCQ, TRUE_FALSE, INPUT, and NUMERICAL until the complete student interaction and grading path for other formats is ready.
4. Product status: class/subject routing, authenticated CMS, server-side answer grading, and persistent attempt/progress APIs are implemented in the codebase; curriculum content coverage is not launch-ready until chapter mappings and the planned original question bank are authored, reviewed, and validated.
