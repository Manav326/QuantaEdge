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

## Source coverage notes

The NCERT catalogue is dynamic, so the workflow lists actual cached titles and hashes rather than hard-coding a misleading total. SCERT Bihar catalogue pages may occasionally omit details or time out; failures are logged and can be retried without losing already-pushed books. The final GitHub Actions summary lists class, publisher, title, registry ID and SHA-256 prefix for every newly cached book.
