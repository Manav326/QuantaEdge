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
