# Additive course content pipeline

This runbook uses the existing QuantaEdge textbook and content pipeline. It does not create a parallel learning, assessment, tutor, progress, or certificate system.

## Stable course identity

Use the numeric `curriculum_program.id` (`course_id`) for a course throughout manifests, import checkpoints, source books, document assignments, learner URLs, and course certificate context. Course name and code are display/catalogue metadata and may change. For source-page mappings, the textbook's printed class is recorded separately as the **source curriculum level**; it does not define which learners may enroll in the course.

## Reuse the existing whole-book cache and PDF splitter

1. Pull the four verified whole-book PDFs from the existing class/language GHCR registry and prepare the local draft library:
   ```powershell
   .\scripts\quantaedge-textbook-sync.ps1 -ClassNo 6 -Language both
   ```
2. Use the existing whole-book PDF importer to inspect the exact official PDFs, map chapter openings, split chapter PDFs, de-duplicate checksum-indexed assets, and create DRAFT document assignments scoped by stable course ID. Specify the source level explicitly:
   ```powershell
   python .\scripts\import_official_ncert_books_and_chapters.py --languages english hindi --course-id <NUMERIC_COURSE_ID> --grades 6
   ```
   Add `--apply` only after reviewing the dry-run report. The importer must remain draft-only; publication is a separate authorised review action. Use the existing resume report option if an apply is interrupted.
3. Use the checked-in whole-book page manifests and JSONL files already under `data/textbook-extractions/class-6/{english,hindi}/pages`. Bind each human-reviewed chapter map to the exact book ID and SHA-256 before producing an extraction bundle:
   ```powershell
   python .\scripts\build_extraction_bundle_from_ghcr_pages.py --manifest <BOOK.manifest.json> --chapter-map <EDITOR_REVIEWED_CHAPTER_MAP.json> --course-id <NUMERIC_COURSE_ID> --output <NEW_VERSIONED_EXTRACTION.json>
   ```
   The page map is source-bound and the extraction builder blocks mapped chapters with low-text, OCR-required, failed, or empty pages. Do not bypass those quality gates.
4. Generate question/lesson draft candidates through the existing question generator, then import through `scripts/import_site_content_draft.py` using the candidate bundle's `curriculum.course_id`. Keep model runs resumable and draft-only.
5. Review chapter/source page ranges, the Hindi and English lesson experiences, question answers/explanations, diagrams, and duplicate/coverage checks in the existing Content Studio. Publish only content that passes review. Verify the published course via the regular student lesson/practice/test/tutor/progress/certificate flows.

## Current source inventory

The four checked-in extraction manifests represent 1,252 textbook pages: English *Curiosity* (272), English *Ganita Prakash* (412), Hindi *Jigyasa* (264), and Hindi *Ganita Prakash* (304). They remain draft extraction sources. Reported low-text/review pages must be resolved per chapter before that chapter is used to generate student content. The original PDFs remain in the existing whole-book GHCR registry; the JSONL files are extracted text and are not a replacement for the original PDFs.

## Safety / backward compatibility

- An omitted course ID means the existing standard Class 6–8 content path; do not send a course code in its place.
- Course records and content must be associated via numeric course ID and distinct course-scoped subject/chapter/lesson/question IDs.
- Do not attach new course content to legacy lesson/question IDs.
- Keep all generated records in DRAFT and leave approval/publishing to the existing review flow.
- Never change source SHA-256 values to force a match. Keep the original book hash, edition, source URL, language, and page numbers in the artifact provenance.


## Source-bound chapter-map review bridge

After a successful splitter dry run, use `scripts/build_chapter_map_review_candidates.py` against the exact manifest and that run's JSON report:

```powershell
python .\scripts\build_chapter_map_review_candidates.py --manifest <BOOK.manifest.json> --split-report <COURSE_SPLIT_REPORT.json> --course-id <NUMERIC_COURSE_ID> --output <NEW_VERSIONED_MAP_CANDIDATE.json>
```

Repeat for each of the four books. The helper does not parse PDFs or infer boundaries: it copies the verified source-page ranges and course chapter IDs from the existing splitter report, and verifies the numeric course ID, book hash, and every page identity in the source JSONL. It emits `DRAFT_REQUIRES_EDITOR_REVIEW`, blank reviewer/date fields, and `student_delivery_allowed=false`. Inspect the exact original PDF for every range and title. Only after that human check, save a separate reviewed copy with `review_status=EDITOR_REVIEWED`, a real reviewer, and a `YYYY-MM-DD` review date; never edit a candidate to appear reviewed without performing that review.

Course chapter PDFs produced by the existing splitter are now stored in `private-review/ncert-book-splits/course-<NUMERIC_COURSE_ID>`. The legacy output path is unchanged, and separate course IDs have separate split directories, preventing same-named chapters from overwriting one another.


## Recommended course pipeline command

Get the course's numeric ID from the same API/database you will use for the import. The Education Admin endpoint is `GET /api/v1/admin/content/programs`; the SQL equivalent for the seeded Navodaya record is:

```sql
SELECT id, code, display_name, curriculum_version
FROM curriculum_program
WHERE code = 'NAVODAYA_BATCH' AND status = 'ACTIVE';
```

Run a no-write preflight from PowerShell at the repository root:

```powershell
.\\scripts\\quantaedge-course-textbook-pipeline.ps1 -CourseId <NUMERIC_COURSE_ID> -Mode Preflight
```

The wrapper reuses `quantaedge-textbook-sync.ps1`, the existing GHCR cache, `import_official_ncert_books_and_chapters.py`, `import_ghcr_extracted_pages.py`, and the source-bound map helper. It verifies GHCR and manifest SHA-256 values, copies source PDFs byte-for-byte into a review workspace, validates all 1,252 page records, runs the existing splitter, and creates four map candidates. It leaves API data unchanged in Preflight mode. The staff session is requested securely when it is not already present in `QUANTAEDGE_ADMIN_SESSION`.

Open the reported JSON splitter report and review the chapter source-page ranges before applying. Then use the *same reviewed report* as the apply gate:

```powershell
.\\scripts\\quantaedge-course-textbook-pipeline.ps1 -CourseId <NUMERIC_COURSE_ID> -Mode ApplyDrafts -ReviewedPreflightReport "<PATH_PRINTED_BY_PREFLIGHT>"
```

Before writing anything, the PDF importer recomputes its plan and compares course ID, source checksums, chapter IDs/codes, language, edition, and exact page ranges against the reviewed preflight report. It stops before the first API write if any of those fields changed. If content or source changed, run a new Preflight and review it again. Apply mode only creates DRAFT PDF assets/document assignments and DRAFT extracted source-book/page records; it does not approve sources, publish textbook documents, or publish lessons/questions.

The script writes course split PDFs to `private-review/ncert-book-splits/course-<NUMERIC_COURSE_ID>` and timestamped chapter-map candidates below `private-review/course-content/course-<NUMERIC_COURSE_ID>/chapter-map-candidates`. Keep those candidates separate from reviewed maps. Every chapter map must be checked against the exact PDF before it is copied and marked `EDITOR_REVIEWED`.
