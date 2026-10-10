# Draft textbook text-library extraction

This command extracts a searchable, page-referenced text layer from the prepared PDF assets. It is intentionally separate from PDF ingestion and publishing: extracted text is **not** authored lesson content, is **not** imported into student lessons, and remains draft material until it has been reviewed through the existing QuantaEdge admin workflow.

## Run locally

PowerShell example for the current Class 6 Hindi Mathematics and Science drafts:

```powershell
$Python = "D:\Tools\Python312\python.exe"
$Drafts = "D:\QuantaEdge\textbook-drafts\class-6\hindi-maths-science"

& $Python .\scripts\extract_textbook_text.py `
  --bundle-dir "$Drafts\bundles" `
  --pdf-dir "$Drafts\pdfs" `
  --output-dir "$Drafts\text-library"

if ($LASTEXITCODE -ne 0) { throw "Text extraction failed; inspect extraction-report.json." }
```

Install PyMuPDF in that Python environment if necessary: `python -m pip install pymupdf`.

## Outputs and safety properties

- `text-library/pages/<asset-pdf-sha256>.jsonl`: one JSON record per page, including extracted text, asset page number, original-book page number, book/class/subject metadata, source checksums, character counts, and a conservative review status.
- `text-library/pages/<asset-pdf-sha256>.manifest.json`: per-asset completion and status counts.
- `text-library/extraction-report.json`: run totals and failures.
- The input PDF must match the bundle's SHA-256 and page count before extraction begins.
- Existing valid page records are reused on reruns; each new page is flushed to disk as a checkpoint. A truncated/invalid line from an interrupted write is ignored and the JSONL is normalized before continuing.
- Use `--book-id ncert-c6-hindi-fhgp1` or `--book-id ncert-c6-hindi-fhcu1` to process one book. Repeat `--book-id` to include multiple exact IDs. Use `--force` only when a deliberate full re-extraction is needed.
- Source PDFs and source registry files are read-only. The command does not call the QuantaEdge API, upload files, create lessons, approve assets, or publish anything.
- Pages with no text, unusually little text, or no Devanagari characters are flagged for review. A page with extractable text is still marked `TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW`; that status does not certify reading order, mathematical notation, tables, diagrams, or pedagogical correctness.
- This is a text-layer extraction, not OCR. Image-only content is not reconstructed. Reviewers must compare text with the page image and use the existing Source ingestion/Textbook library approval and publication process.

The complete Science source PDF exceeds the current 50 MiB learning-PDF library upload limit. Its chapter assets can be reviewed through the existing flow; this script does not weaken that limit or upload the oversized complete book.
