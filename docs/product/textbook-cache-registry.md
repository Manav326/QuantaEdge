# QuantaEdge textbook cache: Hindi and English

Two GHCR images are maintained independently:

- Hindi: ghcr.io/manav326/quantaedge-textbooks-hindi:latest
- English: ghcr.io/manav326/quantaedge-textbooks-english:latest

The image index is stored at /index.json and each complete book at /books/<book_id>.pdf. NCERT individual chapter PDFs are merged sequentially into one PDF with Chapter N bookmarks so the existing QuantaEdge source-ingestion and chapter-map tools can detect page boundaries. SCERT Bihar E-resources are cached as original PDFs. Every entry records source page, publisher, class, subject, language, edition note, byte size, SHA-256 and download method.

## Parallel batches, retries, and persistent GHCR tags

The publisher first pulls and reads the current image index from the same existing GHCR package. It skips every matching book ID already present in that image before making source requests. By default, eight whole books download concurrently; once the batch finishes, complete PDFs are checksum-recorded and pushed together as one Docker build/push. Each book is stored as a separate content-addressed layer, and subsequent pushes reuse prior layers. A later batch is not started until the previous batch's GHCR push has been attempted.

If a source book fails, its error, timestamps, attempt count and recent attempt history remain in index.json. The publisher continues through the rest of the batch/catalogue, then retries unresolved books for up to five rounds. It finishes with a report listing cached, newly pushed, unchanged and still-failed books. It has no arbitrary binary-size cap and streams binaries to disk; a complete NCERT bundle is required, with no individual-chapter download fallback. A source bundle that is incomplete is reported as a failed whole book.

Every update pushes to the same two package names and the same latest tag; no run-specific GHCR package or tag is created:
- ghcr.io/manav326/quantaedge-textbooks-hindi:latest
- ghcr.io/manav326/quantaedge-textbooks-english:latest

GHCR is the durable source of truth. GitHub Actions artifacts are only browser-download conveniences and expire after 90 days; the GHCR package remains until the package or tag is explicitly deleted. Docker pulls of the existing image and its layers are the resume/checkpoint mechanism. Use --refresh only when deliberately checking for changed official editions.

The NCERT permission was confirmed by the repository owner; confidential licence evidence is not committed. SCERT Bihar resources retain their original official source URLs. Keep both GHCR packages private unless all book licences explicitly authorize public redistribution.

## GitHub Actions and browser download

Pushes that change the registry publisher launch the incremental cache workflow. It can also be run manually with a language selector, an optional single NCERT book code/registry ID, and a batch limit. Each PDF is pushed to GHCR as it completes. At the end, the workflow publishes two ZIP bundles as GitHub Actions artifacts for browser download. Bundles include cached PDFs and index.json and are retained for 90 days; GHCR is the durable cache.

## Commands

Update both existing GHCR images in place, checking their current indexes first:

    python scripts/textbook_registry.py publish --language both --image-prefix ghcr.io/manav326/quantaedge-textbooks --download-workers 8 --push-batch-size 8 --retry-rounds 5

This command always targets the same Hindi and English packages and updates their latest tags. For a local push, log Docker in to ghcr.io first using a GitHub token with package write access. In GitHub Actions, the workflow logs in automatically and runs the same command.

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

## One-command pull and draft preparation

After the cache image contains the books you need, from PowerShell on Windows run:

    .\scripts\quantaedge-textbook-sync.ps1

Or select one language:

    .\scripts\quantaedge-textbook-sync.ps1 -Language hindi
    .\scripts\quantaedge-textbook-sync.ps1 -Language english

On Linux/macOS:

    bash scripts/quantaedge-textbook-sync.sh
    bash scripts/quantaedge-textbook-sync.sh hindi

The helper pulls from GHCR, verifies SHA-256, reuses unchanged local PDFs, and splits supported chapter-bookmarks into draft library assets. It writes a prepare-report.json per language. Importing and publication are kept separate so reviewers can verify page mappings, class/subject, chapter assignments and rights before students can see the content.

## Direct download links

- [Hindi-medium GHCR package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-hindi)
- [English-medium GHCR package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-english)

The GitHub Actions run page also exposes class-wise ZIP artifacts after successful pulls. Use the GHCR package for the durable cache; artifacts are convenience downloads with limited retention.

## Source coverage notes

The NCERT catalogue is dynamic, so the workflow lists actual cached titles and hashes rather than hard-coding a misleading total. SCERT Bihar catalogue pages may occasionally omit details or time out; failures are logged and can be retried without losing already-pushed books. The final GitHub Actions summary lists class, publisher, title, registry ID and SHA-256 prefix for every newly cached book.
