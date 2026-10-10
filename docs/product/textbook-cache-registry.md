# QuantaEdge textbook cache: Hindi and English

Two GHCR images are maintained independently:

- Hindi: ghcr.io/manav326/quantaedge-textbooks-hindi:latest
- English: ghcr.io/manav326/quantaedge-textbooks-english:latest

The image index is stored at /index.json and each complete book at /books/<book_id>.pdf. NCERT individual chapter PDFs are merged sequentially into one PDF with Chapter N bookmarks so the existing QuantaEdge source-ingestion and chapter-map tools can detect page boundaries. SCERT Bihar E-resources are cached as original PDFs. Every entry records source page, publisher, class, subject, language, edition note, byte size, SHA-256 and download method.

## One-book-at-a-time behavior

scripts/textbook_registry.py discovers the official NCERT catalogue for Classes 6–12 and official SCERT Bihar E-resources, filtered to Hindi or English. It pulls the current language image, reads index.json and skips entries already stored there. For each missing book it downloads and validates one source, computes SHA-256, updates the index, creates one new OCI layer, pushes the language tag immediately, and only then begins the next book. Completed books remain available if a later source times out. A rerun resumes from the image index and does not re-download completed entries. Use --refresh only when deliberately checking for new editions.

The NCERT permission was confirmed by the repository owner; confidential licence evidence is not committed. SCERT Bihar resources retain their original official source URLs. Keep both GHCR packages private unless all book licences explicitly authorize public redistribution.

## GitHub Actions and browser download

Pushes that change the registry publisher launch the incremental cache workflow. It can also be run manually with a language selector, an optional single NCERT book code/registry ID, and a batch limit. Each PDF is pushed to GHCR as it completes. At the end, the workflow publishes two ZIP bundles as GitHub Actions artifacts for browser download. Bundles include cached PDFs and index.json and are retained for 30 days; GHCR is the durable cache.

## Commands

List the current inventory without downloading PDFs:

    python scripts/textbook_registry.py discover --language both

Pull the Hindi image locally and checksum-verify every PDF:

    python scripts/textbook_registry.py pull --image ghcr.io/manav326/quantaedge-textbooks-hindi:latest --output-dir source-pdfs/registry-cache/hindi

Pull the English image:

    python scripts/textbook_registry.py pull --image ghcr.io/manav326/quantaedge-textbooks-english:latest --output-dir source-pdfs/registry-cache/english

After pulling, use the local PDFs with QuantaEdge's existing SCERT extraction review and Admin Source ingestion flow. Use source and edition metadata from index.json rather than downloading the same book again.

## Prepare cached books for the private library

The cache sync does not upload textbook binaries to Git. Once an image is pulled, prepare a separate draft folder with checksum-verified PDFs and metadata bundles:

    python scripts/textbook_registry.py prepare-library --registry-dir source-pdfs/registry-cache/hindi --output-dir private-review/cache-library-hindi

For English:

    python scripts/textbook_registry.py prepare-library --registry-dir source-pdfs/registry-cache/english --output-dir private-review/cache-library-english

The preparer keeps the original cached books untouched. If a PDF has top-level chapter bookmarks (including the Chapter N bookmarks created for NCERT), it splits the PDF into per-chapter PDFs and creates one DRAFT_EXTRACTION_ONLY bundle per asset. Where a complete book is under the existing 50 MiB API library limit, it also creates a complete-book asset. For larger books, chapter-sized assets are created only when reliable page boundaries can be detected; the report flags books that need a reviewed page map instead of inventing one.

Register the ready bundles in the existing private library, using an authenticated staff session with CONTENT_EDIT and CONTENT_REVIEW permissions:

    python scripts/import_existing_textbook_pdfs.py --bundle-dir private-review/cache-library-hindi/bundles --pdf-dir private-review/cache-library-hindi/pdfs --api-base-url http://localhost:8080 --apply

Set QUANTAEDGE_ADMIN_SESSION in the shell that runs the importer. Repeat for the English output directory. Each imported item remains a draft/private asset; source-to-chapter mapping, approval and publishing remain explicit review steps. The complete original remains in GHCR even when it is too large for the database PDF library.

## Direct download links

- [Hindi-medium GHCR package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-hindi)
- [English-medium GHCR package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-english)

The GitHub Actions run page also exposes class-wise ZIP artifacts after successful pulls. Use the GHCR package for the durable cache; artifacts are convenience downloads with limited retention.

## Source coverage notes

The NCERT catalogue is dynamic, so the workflow lists actual cached titles and hashes rather than hard-coding a misleading total. SCERT Bihar catalogue pages may occasionally omit details or time out; failures are logged and can be retried without losing already-pushed books. The final GitHub Actions summary lists class, publisher, title, registry ID and SHA-256 prefix for every newly cached book.
