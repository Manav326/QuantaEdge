# QuantaEdge textbook cache: Hindi and English

Two GHCR images are maintained independently:

- Hindi: ghcr.io/manav326/quantaedge-textbooks-hindi:latest
- English: ghcr.io/manav326/quantaedge-textbooks-english:latest

The image index is stored at /index.json and each complete book at /books/<book_id>.pdf. The publisher only accepts a complete NCERT book bundle; it validates all expected members and merges the bundle contents into one whole-book PDF with chapter bookmarks. It never attempts individual chapter URL downloads as a fallback. SCERT Bihar E-resources are cached as original PDFs. Every entry records source page, publisher, class, subject, language, edition note, byte size, SHA-256 and download method.

## Parallel batches, retries, and persistent GHCR tags

The publisher first pulls and reads the current image index from the same existing GHCR package. It skips every matching book ID already present in that image before making source requests. By default, eight whole books download concurrently; once the batch finishes, complete PDFs are checksum-recorded and pushed together as one Docker build/push. Each book is stored as a separate content-addressed layer, and subsequent pushes reuse prior layers. A later batch is not started until the previous batch's GHCR push has been attempted.

If a source book fails, its error, timestamps, attempt count and recent attempt history remain in index.json. The publisher continues through the rest of the batch/catalogue, then retries unresolved books for up to five rounds. It finishes with a report listing cached, newly pushed, unchanged and still-failed books. It has no arbitrary binary-size cap and streams binaries to disk; a complete NCERT bundle is required, with no individual-chapter download fallback. A source bundle that is incomplete is reported as a failed whole book.

Every update targets fourteen fixed packages—Classes 6–12, each in Hindi and English—and updates the same `latest` tag. No run-specific package is created:

| Class | Hindi GHCR package | English GHCR package |
|---:|---|---|
| 6 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-6-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-6-english) |
| 7 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-7-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-7-english) |
| 8 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-8-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-8-english) |
| 9 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-9-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-9-english) |
| 10 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-10-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-10-english) |
| 11 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-11-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-11-english) |
| 12 | [Hindi package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-12-hindi) | [English package](https://github.com/Manav326/QuantaEdge/pkgs/container/quantaedge-textbooks-class-12-english) |

The on-disk mirrors use `source-pdfs/registry-cache/class-N/{hindi|english}`; each API volume path corresponds to exactly one package, and every index is class-scoped.

GHCR is the durable source of truth. GitHub Actions artifacts are only browser-download conveniences and expire after 90 days; the GHCR package remains until the package or tag is explicitly deleted. Docker pulls of the existing image and its layers are the resume/checkpoint mechanism. Use --refresh only when deliberately checking for changed official editions.

The NCERT permission was confirmed by the repository owner; confidential licence evidence is not committed. SCERT Bihar resources retain their original official source URLs. Keep both GHCR packages private unless all book licences explicitly authorize public redistribution.

## GitHub Actions and browser download

Pushes to the publisher start the resumable class-wise cache workflow. A manual run can select a medium, a single class (or all Classes 6–12), and whether to produce direct downloadable ZIPs. The optional ZIP artifact contains one whole-book archive per selected class/medium and lasts 90 days; the fourteen GHCR `latest` tags remain the durable source.

## End-to-end QuantaEdge source ingestion

1. Sync the two GHCR images to the API host through `scripts/quantaedge-deploy.sh` or run `scripts/quantaedge-textbook-sync.ps1` locally.
2. In Admin → **Source ingestion & chapter review**, select the class and subject, then use **Use a persistent complete textbook → Review complete cached book**. This starts an ingestion job against the cache book ID and expected SHA-256; it does not revisit the official website.
3. For books under 50 MiB, the API can keep a deduplicated whole-book asset in PostgreSQL. For bigger complete books, the source remains on the read-only GHCR mount; admin preview and chapter splitting read from disk.
4. Review the original page preview and detected outline. Match every chapter to the correct active curriculum chapter and exact one-based page range, then create chapter PDFs. Each chapter PDF must fit the existing 50 MiB library limit.
5. Verify the printed edition/reprint year or academic session in the private preview. Edit the title, official source URL and edition/session in Step 01 and click **Save verified source metadata** in the review job before approval. The backend rejects placeholder editions at final approval.
6. Approve the reviewed chapter decisions. Approved chapter files are added to the private PDF library as **DRAFT** learning documents. The full original for a larger book remains in GHCR.
6. Open Admin → **Textbook library**, check source URL/title/edition and page mapping, and publish each resource deliberately. Only published documents are exposed through the authenticated student textbook reader.

The upstream source needs to provide a complete NCERT book bundle. A missing, corrupt, or incomplete complete-book bundle is recorded as a failed whole book and retried; the downloader never falls back to fetching individual chapter URLs. The index and source PDF are verified when a cached ingestion job starts. Chapter boundaries, subject placement and publishing still require reviewer decisions.

## Commands

Update both existing GHCR images in place, checking their current indexes first:

    python scripts/textbook_registry.py publish --class-wise --language both --image-prefix ghcr.io/manav326/quantaedge-textbooks --download-workers 8 --push-batch-size 8 --retry-rounds 5

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
