# Import root-level textbook PDFs

This workflow adds the English NCERT whole-book PDFs and chapter ranges to the existing
QuantaEdge private PDF library. It does **not** put source textbook PDFs into Git history,
and it does not publish student-facing resources automatically.

## Put the source PDFs in the project root

Use descriptive filenames so the importer can identify the class, subject and medium:

- `Class_6_Science_English.pdf`
- `Class_6_Mathematics_English.pdf`

Common NCERT download filenames that carry the class/book code (for example
`ncert-c6-english-fecu1.pdf`) are supported too. Filenames without a language marker
use the CLI's selected language, which defaults to English. Avoid keeping two different
PDFs for the same class/subject/medium in the root at the same time; the importer blocks
ambiguous duplicates.

Root textbook PDFs are ignored by `.gitignore`; do not force-add them to the repository.
The importer uploads them to the authenticated private PDF library instead.

## Dry run first

From the repository root:

```powershell
$env:QUANTAEDGE_ADMIN_SESSION = "<fresh QE_SESSION cookie value>"
python scripts/import_root_textbook_pdfs.py --root . --language english --class-no 6
```

The command checks each PDF, checks the official Class 6 curriculum chapter IDs, detects
chapter boundaries from PDF bookmarks or verified title pages, and writes
`root-textbook-pdf-import-report.json`. It is a dry run unless `--apply` is specified.

If a PDF lacks one or more verified chapter boundaries, the default chapter-wise workflow
stops before uploading anything. For an explicitly book-only import, use `--book-only`.

## Apply after checking the report

```powershell
python scripts/import_root_textbook_pdfs.py --root . --language english --class-no 6 --apply
```

Each source PDF is uploaded once. The complete-book assignment references all pages; each
chapter assignment uses its own page range into the same source asset. Assets are
deduplicated by SHA-256, and chapter IDs are resolved by exact canonical code. Re-running
the same import skips exact existing assignments. A different PDF already occupying the
same language/class/subject/book or chapter slot causes a safe preflight stop; archive or
review the existing assignment before replacing it.

## Review and publish

All new assignments are created as `DRAFT`. In Admin → Textbook library, check the PDF,
title, language, page count and chapter range, then publish the complete book and chapter
assignments after review. Only published, approved assets are visible to eligible students.

The student textbook library and reader follow the global language selector. English
selection requests only English assignments. When a learner switches language while a PDF
is open, QuantaEdge switches to the matching published resource; if no equivalent exists,
it returns to the language-filtered library with a clear notice.

## Current chapter range support

Exact chapter-title maps are included for Class 6 NCERT Science (*Curiosity*) and
Mathematics (*Ganita Prakash*). The same full-book-only workflow can be used for other
classes, but chapter-wise imports for a new edition need a reviewed chapter title map
before they can be split. Do not guess page ranges or chapter IDs.
