# Admin source PDF ingestion, splitting and review

QuantaEdge now exposes a reusable admin flow at Admin → Source ingestion. It reads existing source metadata from content_source, existing source-to-chapter mappings from chapter_source, and the PDF bytes/checksums already in learning_pdf_asset. The migration backfills source-to-PDF links where checksums or source references match.

## Workflow

1. Choose a registered content_source record or register a new source for any active class/subject.
2. The importer checks the source link, checksum and existing PDF asset before downloading. It accepts a public HTTPS catalogue page or direct PDF URL, finds a PDF link on simple HTML pages, validates the PDF signature, and stores new book PDFs as REVIEW assets. Download work is handled asynchronously and job state is persisted.
3. The importer detects top-level PDF bookmarks or conservative Chapter/अध्याय/पाठ headings. Detected page ranges are suggestions only. An admin maps titles to curriculum chapters and corrects page ranges in the review UI.
4. Each selected range is split into a chapter PDF. Chapter assets remain in REVIEW and are not listed by the reusable Textbook library API. Reviewers preview the original or split pages and approve/reject each mapping.
5. Final approval marks the book and approved split assets as approved, upserts chapter_source coverage/provenance, and creates SUBJECT_BOOK / CHAPTER_PDF learning_document rows in DRAFT. A publisher must still publish them separately for student visibility.
6. Rejection keeps pending assets out of the reusable library. Checksums prevent duplicate binary storage. Existing approved assets and already published resources are not demoted by a new ingestion attempt.

## Operational notes

- Source download URLs must be HTTPS, resolve to public IP addresses, and may redirect only through a validated public HTTPS URL. Files are limited to 50 MB and 2,000 pages; password-protected PDFs are rejected.
- PDF book pages are private and rendered into PNG only for authorized content reviewers. The original PDF bytes are not exposed to student endpoints.
- The admin UI uses the existing curriculum API for the available class/subject/chapter list, so the workflow is not hardcoded to the initial Mathematics/Science examples.
- The service can discover direct PDF links from ordinary anchor links ending in .pdf. For JavaScript-only catalogue pages, login-gated sources, or unusual download forms, paste the direct official PDF URL.
- The PDF byte schema checked into the repository uses learning_pdf_asset. If a live installation has an additional legacy binary table not represented by these migrations, its rows still need a schema-specific adapter; the migration safely backfills links using the current source checksums/references.
- No production migration or live database ingestion is run by this commit. Back up production database/media first, deploy after CI, and run through the admin review flow on a small source before bulk processing.


## Duplicate and retry behavior

- Clicking start again while the same source/subject already has a download running for the last 15 minutes returns that existing job instead of starting another fetch.
- Invalid HTTPS source URLs are rejected before existing source metadata is edited or a new source record is registered.
- When an administrator intentionally retries a previously rejected PDF, its checksum-matched asset returns to REVIEW. Existing APPROVED assets are not demoted by retries.
- Rejection targets the asset IDs attached to that specific job rather than matching a broad source-reference prefix.


## End-to-end admin-to-student visibility

- Admin / Source ingestion is the source discovery and review queue. It displays existing content_source/chapter_source relationships, detects or reuses PDF assets, previews downloaded books and split chapters, and requires an explicit approve/reject decision for each chapter candidate.
- Final ingestion approval creates a complete-book resource plus only approved chapter resources as DRAFT assignments. Rejected chapter candidates are not attached to student resources. The complete book's asset is protected from being marked rejected just because a chapter split shares its checksum.
- Admin / Textbook library is the publishing surface. Editors can upload/attach resources; staff with CONTENT_PUBLISH can view the resource queue, preview the actual assigned pages, publish approved assets, and archive already-published entries. Chapter PDFs require an active curriculum chapter; authored/published lesson text is independent of textbook-source availability.
- Student / Textbooks at /student/textbooks shows complete-book cards and chapter PDF cards grouped by the subject name returned by the curriculum database. Subject filters and groups are dynamic, not limited to Mathematics and Science.
- Students see only documents whose assignment status is PUBLISHED, whose PDF asset is APPROVED, and whose class/subject is active and matches the student's class plus active subject enrollment. Chapter PDFs also require an active curriculum chapter. Complete books and chapter PDFs open in the protected page-image reader; page progress is saved per student.
- The student navigation and account menu both link to Textbooks. A newly approved ingestion is intentionally not immediately visible to students: publishers must preview and publish its book/chapter assignments first.
- Preview routes are staff-permission protected and return rendered PNG pages only. Student routes enforce enrollment on catalog, document-detail, progress and each page request; they do not send the original PDF bytes.
