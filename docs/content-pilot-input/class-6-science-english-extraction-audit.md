# Class 6 Science (English) — NCERT extraction completeness audit

**Status: IN PROGRESS — NOT APPROVED AS COMPLETE.**  
**Scope:** one book only: NCERT *Curiosity*, Textbook of Science for Grade 6, English, Reprint 2026–27.  
**Repository:** `Manav326/QuantaEdge`, branch `main`.  
**Source record:** `data/textbook-extractions/class-6/english/pages/7e6c3a6ba4f80aa15120f446fb66614c3cec7c17462414d410d7be66c3ca8b73.manifest.json`.  
**Official NCERT reference:** https://ncert.nic.in/textbook.php?fecu1=0-12

## Naming clarification — must be preserved in every future chat

“Navodaya” is a project/content label chosen only to make this Class 6 material distinct. It does **not** mean the Navodaya entrance examination, and this content is **not** Navodaya entrance-exam preparation. Treat it as ordinary Class 6 curriculum content. Do not infer an exam-preparation scope from the name.

## What the committed extraction proves

- The manifest identifies the source as NCERT *Curiosity*, Class 6 Science, English, Reprint 2026–27.
- The source PDF SHA-256 recorded in the manifest is `7e6c3a6ba4f80aa15120f446fb66614c3cec7c17462414d410d7be66c3ca8b73`; the manifest records the same hash for the original book and extracted asset.
- The extraction manifest reports **272/272 PDF pages processed**, 265 pages with `TEXT_EXTRACTED_REQUIRES_CONTENT_REVIEW`, and **7 pages marked `LOW_TEXT_REVIEW`**. Those seven are PDF pages **10, 18, 78, 140, 160, 200, and 224**; their extracted text is only the recurring “Reprint 2026-27” or “Notes / Reprint 2026-27” footer. These may be intentional notes/blank pages, not missing lesson text, but must be checked against rendered source pages and classified explicitly.
- The extraction report says 684 pages were processed across two English books (Mathematics and Science). Do not mistake that combined report for the Science-only count.
- The JSONL is a whole-book, page-addressable text extraction. Every sampled Science record has `chapter_title: null`; chapter-level text bundles are not yet produced by this artifact.
- Page coverage and matching hashes establish file identity and page processing, **not** that every printed word, equation, table, diagram label, activity, or visual has been captured accurately.
- Visual content is not represented adequately by plain extracted text alone. Some sampled pages show multi-column/figure text interleaving, broken reading order, and image/diagram material mixed into prose. This needs page-render comparison.
- The JSONL uses `source_book_page_number` as a PDF-page index (for example, PDF page 260 contains printed textbook pages 242–243). Do not treat this field as the printed page number without correcting/renaming its semantics.

## NCERT chapter checklist

The official NCERT contents list contains these 12 chapters. The list below is the audit ledger, not a declaration that the extraction is complete.

| # | NCERT chapter | Text/page extraction | Chapter-boundary mapping | Text + visual comparison against source |
|---:|---|---|---|---|
| 1 | The Wonderful World of Science | Whole-book pages processed; review required | Pending | Pending |
| 2 | Diversity in the Living World | Whole-book pages processed; review required | Pending | Pending |
| 3 | Mindful Eating: A Path to a Healthy Body | Whole-book pages processed; review required | Pending | Pending |
| 4 | Exploring Magnets | Whole-book pages processed; review required | Pending | Pending |
| 5 | Measurement of Length and Motion | Whole-book pages processed; review required | Pending | Pending |
| 6 | Materials Around Us | Whole-book pages processed; review required | Pending | Pending |
| 7 | Temperature and its Measurement | Whole-book pages processed; review required | Pending | Pending |
| 8 | A Journey through States of Water | Whole-book pages processed; review required | Pending | Pending |
| 9 | Methods of Separation in Everyday Life | Whole-book pages processed; review required | Pending | Pending |
| 10 | Living Creatures: Exploring their Characteristics | Whole-book pages processed; review required | Pending | Pending |
| 11 | Nature’s Treasures | Whole-book pages processed; review required | Pending | Pending |
| 12 | Beyond Earth | Whole-book pages processed; review required | Pending | Pending |

## Initial findings / defects to resolve

1. **Chapter-level completeness has not yet been established.** The committed artifact contains one page stream for the whole book, not independently bounded chapter extracts. A 272-page count alone cannot prove every chapter is present or correctly split.
2. **Seven pages are explicitly low-text review cases:** PDF pages 10, 18, 78, 140, 160, 200, and 224. The extracted text is only a reprint footer or notes-page footer. Verify the rendered source pages and classify each as intentional notes/blank/front matter or an extraction defect; do not count them as missing content without checking.
3. **Reading order is suspect on image/table-heavy pages.** The sampled page records around the Solar System section interleave columns, callouts, figure captions, and body text. Do not use these raw strings directly as teaching content.
4. **Text extraction cannot preserve all visuals.** Create a per-page visual inventory of figures, diagrams, tables, activity steps, labels, captions, and any content conveyed only by an image. Confirm every item remains available in the source page or is faithfully represented in the derived material.
5. **Page-number semantics are ambiguous/misleading.** Rename the current field to `pdf_page_number` or store separate `pdf_page_number` and `printed_page_number`; derive printed page numbers from source evidence rather than assuming PDF index equals printed pagination.
6. **The source PDF itself must be rendered and compared.** The manifest's hash match does not replace a visual/text comparison with the official NCERT PDF for the same edition.
7. **No content may be marked approved/published from this audit alone.** Extraction artifacts remain draft-only until chapter mapping, source reconciliation, visual QA, and human review pass.

## Required acceptance procedure

For each of the 12 chapters:

- Record NCERT chapter title, exact start/end PDF pages, printed-page range (including front/back matter treatment), and source URL/edition/hash.
- Confirm every source PDF page is accounted for exactly once as chapter content or explicitly classified front/back matter; no gaps, overlaps, duplicates, or silent page drops.
- Compare page text with rendered source pages. Check headings/subheadings, paragraphs, lists, equations, units, tables, activity instructions, question prompts, captions, footnotes, and page-to-page continuations.
- Mark OCR/text-order failures and low-text pages; inspect them visually and correct/annotate rather than silently accepting.
- Inventory diagrams/illustrations/graphs/tables and their labels/captions separately. Plain text extraction is not a substitute for visuals.
- Record each issue with chapter, PDF page, printed page (if available), issue type, evidence, resolution, and reviewer/status.
- Only mark a chapter **PASS** when all pages and visual elements have been reconciled. Otherwise use **PARTIAL** or **BLOCKED** with specific missing items.
- After all chapters pass, generate chapter-wise JSONL/manifests and a machine-readable audit summary. Keep generated lessons/questions as separate draft artifacts.

## Current verdict

**NOT COMPLETE / NOT SAFE TO CLAIM COMPLETE.** The available evidence supports that the 272-page source PDF was processed page-by-page, but it does not yet establish complete chapter-wise extraction or faithful preservation of all NCERT text and visuals. Continue this audit for this one book before starting another book.

## Resume instructions for the next chat

1. Read this file first.
2. Keep scope fixed to Class 6 Science, English, NCERT *Curiosity*, Reprint 2026–27.
3. Read the manifest and JSONL cited above; enumerate the seven `LOW_TEXT_REVIEW` pages and verify their rendered source pages.
4. Use the official NCERT reference above and the same edition/hash. Do not compare against an older edition.
5. Finish chapter boundary mapping and per-chapter text + visual reconciliation for all 12 chapters. Update this ledger with evidence and status; do not mark PASS based only on page counts.
6. Preserve the “Navodaya” naming clarification above.
