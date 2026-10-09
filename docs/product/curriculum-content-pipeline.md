# Curriculum content pipeline (Classes 6–8, Bihar Board)

## Source-of-truth policy

Use the Bihar SCERT textbook catalogue to confirm the applicable book, language, edition/session, and publication details before authoring a lesson:

- SCERT Bihar textbook catalogue: https://scert.bihar.gov.in/textbooks
- Mathematics teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/mathematics-handbook-for-teachers-1753723347
- Science teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/science-handbook-for-teachers-class-6-8-1753723306
- गणित भाग-6: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-6-1707973215
- विज्ञान भाग-2, Class 7: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-2-1708062711
- गणित भाग-8: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-8-1708403831

The catalogue is the discovery point for all six class/subject combinations. Do not assume the edition or chapter order from a previous session; record the actual book used for each import.

## Repeatable extraction and review

1. Download the official PDF manually from its canonical SCERT page and retain the original file unchanged.
2. Create a reviewed chapter/page map. Page numbers are 1-based PDF page numbers, not necessarily the printed page numbers.
3. Run `scripts/scert_extract_review.py` to extract page-addressable text and calculate the source PDF SHA-256. Use `--ocr` only when scanned pages need OCR and Tesseract with the Hindi/English language packs is installed.
4. Review extraction errors, OCR output, chapter boundaries, edition, and source metadata manually.
5. Write original QuantaEdge explanations, worked examples, diagrams, and questions that teach the mapped concepts. Record source title, edition, chapter, and page references accurately.
6. Create draft chapters and lessons through the admin content library. Add blocks and practice questions, review them, then publish the chapter followed by its lessons.
7. Verify the published class/subject track from the student side.

Example:

```bash
python -m pip install pymupdf
python scripts/scert_extract_review.py \
  --pdf ./source-pdfs/ganit-bhag-6.pdf \
  --class-code 6 \
  --subject-code maths \
  --source-title "गणित भाग-6" \
  --source-url "https://scert.bihar.gov.in/textbooks" \
  --edition "verify from downloaded book" \
  --chapter-map ./source-maps/class-6-maths.json \
  --output ./private-review/class-6-maths-extraction.json
```

A chapter map has this shape:

```json
{
  "chapters": [
    {
      "code": "example-chapter-code",
      "title": "Reviewed chapter title",
      "page_start": 3,
      "page_end": 17
    }
  ]
}
```

Replace the example metadata and page range with values verified against the actual PDF. Overlapping page ranges are rejected.

## Safety and current limitations

- Extraction bundles are marked `DRAFT_EXTRACTION_ONLY`; the tool never publishes to the student API.
- Extracted textbook text is internal source-review material. Do not publish copied textbook text, page images, or questions without the required rights. Student-facing explanations and questions should be original and reviewed.
- OCR output and chapter mapping require human review; OCR is not proof of correctness.
- The extraction tool is not yet an automatic PDF-to-CMS importer. An extracted bundle must not be treated as completed curriculum.
- The current publication validator supports MCQ and true/false grading. Input/free-text questions remain blocked from publication until server-side grading and answer-key storage are implemented.
- Admin editing currently saves content JSON directly. Version history and a dedicated structured question/block editor remain launch-hardening work.
