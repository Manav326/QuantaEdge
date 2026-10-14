# Optional visual inventory from the existing textbook pipeline

This is the first code stage of the multimodal plan. It is an **optional sidecar**:
it does not modify the existing text JSONL, source manifest, question-generation
bundle, database, student lesson renderer, Content Studio, or Tutor API.

## What it does

- Verifies the original PDF SHA-256 and page count against the existing source manifest.
- Reads existing page JSONL context when available without changing that file.
- Extracts embedded PDF images with PyMuPDF and records image checksums, dimensions,
  media types, page numbers, occurrence bounding boxes, nearby text, and source provenance.
- Renders a JPEG preview of every PDF page by default. This preserves visual evidence
  for vector drawings/diagrams that are not stored as embedded images.
- Writes a versioned `quantaedge.visual-inventory.v1` JSON sidecar and asset files to a
  separate output directory. Every asset remains `CANDIDATE`, `SOURCE_REFERENCE_ONLY`,
  and not publishable. Vision understanding is explicitly `NOT_ANALYZED`.

It does **not** yet use a vision-language model to caption/classify assets, segment
individual vector diagrams, or create interactive activities. Those are later stages.
A full-page preview is not falsely labelled as a separately extracted diagram.

## Run on one book first

Use the original PDF downloaded from the existing whole-book GHCR source and its
matching committed manifest. Do not substitute a different edition.

```powershell
python -m pip install "PyMuPDF>=1.24,<2"
python scripts/extract_visual_asset_inventory.py `
  --pdf "D:\QuantaEdge\private-source\class-6-curiosity.pdf" `
  --manifest "data\textbook-extractions\class-6\english\pages\ncert-c6-english-curiosity.manifest.json" `
  --output-dir "D:\QuantaEdge\visual-inventory\class-6-curiosity"
```

Replace the illustrative local paths with the exact PDF and manifest paths on your
machine. The manifest must match the PDF hash and page count. The command refuses to
overwrite an existing inventory unless `--overwrite` is passed. Use `--no-page-previews`
only when you deliberately want embedded images alone; vector diagrams will then not
have page-preview evidence.

The output directory contains `visual-inventory.v1.json`, page preview JPEGs, and an
`assets/` folder for extracted embedded images. Keep these outputs local/private until
the asset storage and rights-review workflow is decided; do not commit large derived
media to the source-code repository by default.

## Test

```powershell
python -m unittest tests.test_extract_visual_asset_inventory -v
```

The tests use a tiny synthetic PDF and check hash binding, per-page scan records,
page previews, use of existing text context, and that legacy JSONL remains unchanged.
