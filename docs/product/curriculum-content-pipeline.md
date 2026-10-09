# Curriculum source, extraction, authoring and import pipeline

## Source-of-truth policy

Use official SCERT Bihar resources to identify the correct book, language, edition/session and publication details. The currently registered source references are candidates, not proof that a specific edition or page range has been verified.

- SCERT Bihar textbook catalogue: https://scert.bihar.gov.in/textbooks
- Mathematics teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/mathematics-handbook-for-teachers-1753723347
- Science teacher handbook, Classes 6–8: https://scert.bihar.gov.in/eresources/science-handbook-for-teachers-class-6-8-1753723306
- Class 6 Mathematics — गणित भाग-6: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-6-1707973215
- Class 7 Mathematics — गणित भाग-7: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-7-1708060463
- Class 8 Mathematics — गणित भाग-8: https://scert.bihar.gov.in/eresources/%E0%A4%97%E0%A4%A3%E0%A4%BF%E0%A4%A4-%E0%A4%AD%E0%A4%BE%E0%A4%97-8-1708403831
- Class 6 Science — विज्ञान भाग-1: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A5%8D%E0%A4%9E%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-1-1707973674
- Class 7 Science — विज्ञान भाग-2: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-2-1708062711
- Class 8 Science — विज्ञान भाग-3: https://scert.bihar.gov.in/eresources/%E0%A4%B5%E0%A4%BF%E0%A4%9C%E0%A4%BE%E0%A4%A8-%E0%A4%AD%E0%A4%BE%E0%A4%97-3-1708404249

## Stage 1 — extract and review source pages

1. Download the official PDF manually and keep the original file unchanged.
2. Create a chapter map JSON with chapter code/title and 1-based PDF page_start/page_end. Overlapping ranges, duplicate codes and out-of-range pages are rejected.
3. Install PyMuPDF with python -m pip install pymupdf. Run scripts/scert_extract_review.py with PDF path, class code, subject code, source title/URL, actual edition/session, reviewed chapter map and output path.
4. The extractor stores each page's extracted text, PDF page number, per-page text hash, extraction method, PDF SHA-256, OCR page list, unmapped pages and human-review flags. Use --ocr only for scanned/low-text pages; Tesseract plus Hindi/English language data is required.
5. Inspect OCR text against the PDF visually. OCR can misread Hindi characters, numerals, equations, units, scientific terms and punctuation. Correct the chapter map and edition metadata before authoring.

Example extraction command (replace all paths and metadata with values verified from the downloaded source):

```bash
python -m pip install pymupdf
python scripts/scert_extract_review.py \
  --pdf ./source-pdfs/ganit-bhag-6.pdf \
  --class-code 6 \
  --subject-code maths \
  --source-title "गणित भाग-6" \
  --source-url "https://scert.bihar.gov.in/textbooks" \
  --edition "actual edition/session from the PDF" \
  --chapter-map ./source-maps/class-6-maths.json \
  --output ./private-review/class-6-maths-extraction.json
```

## Stage 2 — optional AI-assisted question generation

The extractor intentionally does not call an AI model. Use this separate script to turn the extraction review bundle into original question/lesson candidates:

- Script: `scripts/generate_questions_from_extraction.py`
- Input: only a `DRAFT_EXTRACTION_ONLY` bundle made by the extractor.
- Output: one `<chapter-code>.ai-generated-draft.json` per selected chapter, marked `AI_GENERATED_DRAFT`.
- Default question count: 12 candidates per chapter. The default type mix is curriculum-aware: Maths uses 6 MCQ, 2 True/False, 2 text-input and 2 numerical questions per 12; Science uses 7 MCQ, 2 True/False and 3 text-input questions per 12. These are generated candidates, not approved content.
- Model provider: any configured OpenAI-compatible chat-completions endpoint. Set `OPENAI_API_KEY`, `OPENAI_API_BASE_URL` and `QUANTAEDGE_QUESTION_MODEL` explicitly. The question-generation model is deliberately configured separately from the AI tutor.
- Budget controls: `--chapter-code`, `--max-chapters`, `--questions-per-chapter`, `--max-context-chars` and `--max-api-calls`. Long chapters are split into bounded excerpts; when there are more excerpts than requested questions, the excerpts are sampled across the chapter. Inspect the generated question-level page references to see the actual coverage.
- Quality gates: exact question counts/type quotas, supported grading types, answer-key/option consistency, numeric answer types, page references, prompt uniqueness and difficulty values are validated locally. A validation failure stops the chapter and does not write its candidate bundle. This is a structural check, not proof that the model's answers are factually correct.

First run an offline plan. It does not make any network calls:

```bash
python scripts/generate_questions_from_extraction.py \
  --extraction ./private-review/class-6-maths-extraction.json \
  --output-dir ./private-review/ai-candidates/class-6-maths \
  --chapter-code knowing-numbers
```

For actual generation, first configure a model that is enabled on your endpoint. Then explicitly confirm that extracted page text may be sent to that model provider:

```bash
export OPENAI_API_KEY='<provider API key>'
export OPENAI_API_BASE_URL='https://api.openai.com'
export QUANTAEDGE_QUESTION_MODEL='<model available to this endpoint>'
python scripts/generate_questions_from_extraction.py \
  --extraction ./private-review/class-6-maths-extraction.json \
  --output-dir ./private-review/ai-candidates/class-6-maths \
  --chapter-code knowing-numbers \
  --questions-per-chapter 12 \
  --max-api-calls 12 \
  --generate --confirm-external-processing
```

The script sends the selected extracted text—not the PDF file itself—to the configured provider. Review provider privacy/retention terms and content-sharing permissions before confirming. Use a private, ignored directory for extraction bundles and candidates; do not commit textbook text, generated candidate bundles or API credentials.

AI output remains `AI_GENERATED_DRAFT` and is deliberately rejected by the content importer. To promote it for editorial import, use the interactive human gate:

```bash
python scripts/review_generated_question_bundle.py \
  --bundle ./private-review/ai-candidates/class-6-maths/knowing-numbers.ai-generated-draft.json \
  --output ./private-review/reviewed/knowing-numbers.reviewed.json \
  --reviewer 'Responsible curriculum editor'
```

The reviewer must type `YES` for all five checks. This records the named reviewer and date but does **not** approve individual questions or publish anything. Do not attest that content is original or that reuse rights are reviewed unless those points have genuinely been checked.

When the reviewed chapter code matches the existing canonical class/subject chapter, preview the import and explicitly attach new lesson drafts to that existing chapter rather than creating a duplicate chapter:

```bash
export QUANTAEDGE_API_BASE_URL='http://localhost:8080'
export QUANTAEDGE_ADMIN_SESSION='<QE_SESSION cookie value>'
python scripts/import_reviewed_content.py \
  --bundle ./private-review/reviewed/knowing-numbers.reviewed.json \
  --attach-to-existing-chapter
python scripts/import_reviewed_content.py \
  --bundle ./private-review/reviewed/knowing-numbers.reviewed.json \
  --attach-to-existing-chapter --apply
```

The first importer command is a dry run. The second writes draft lessons/questions only, under an authenticated admin session. A chapter-code mismatch will not be silently auto-mapped; check the Admin Content Studio and correct the chapter map rather than creating a duplicate canonical chapter. Source alignment remains unverified, questions remain `DRAFT`, and publication still requires editor approval and the existing curriculum/source validation. For a genuinely new chapter, omit `--attach-to-existing-chapter` only after confirming that the code does not duplicate an existing canonical chapter.

## Stage 3 — review and author the learning material

The extraction JSON is a review artifact only. It is not a lesson, question bank, or student-delivery payload. Do not copy full textbook text, scans, or textbook questions into student content unless the relevant reuse rights are confirmed.

Create a separate editor-reviewed authoring bundle with schema_version 1 and bundle_status EDITOR_REVIEWED_DRAFT. It must include:

- curriculum: Bihar Board, class_code 6/7/8, subject_code maths/science and language.
- source: title, canonical HTTPS URL, actual edition/session, PDF SHA-256, PDF page count and publication year when known.
- editorial_declaration: original_content=true, rights_reviewed=true, reviewer and reviewed_at.
- chapter: unique lowercase hyphenated code, display_name, description, sort_order and exact PDF page range.
- lessons: unique codes, title, summary, estimated_minutes, sort_order, exact PDF page range, authored blocks and questions.
- blocks: a supported block_type plus an authored JSON content object.
- questions: question_type, prompt, difficulty, sort_order, original explanation, answer_payload and options where relevant.

Question authoring rules: MCQ/TRUE_FALSE require 2–10 unique options and exactly one correct option. INPUT requires a TEXT answer key; NUMERICAL requires a NUMERIC answer key. Other formats can remain drafts, but cannot be published until their complete student interaction and grading path is supported.

## Stage 4 — dry-run and draft-only import

scripts/import_reviewed_content.py validates the reviewed authoring bundle and creates content through the authenticated admin API. It rejects raw extraction bundles, placeholder editions, non-HTTPS remote sources, missing source hashes, invalid/out-of-range page ranges, lesson page ranges outside the chapter, duplicate lesson codes, malformed question keys and unsupported block/question types.

Without --apply, the importer only validates and prints the draft import plan:

```bash
python scripts/import_reviewed_content.py --bundle ./private-review/class-6-maths-reviewed.json
```

For an intentional import, use the authenticated admin session cookie in a local shell. Do not commit it, put it in the authoring bundle, or share it in chat:

```bash
export QUANTAEDGE_API_BASE_URL=http://localhost:8080
export QUANTAEDGE_ADMIN_SESSION='<QE_SESSION cookie value>'
python scripts/import_reviewed_content.py --bundle ./private-review/class-6-maths-reviewed.json --apply
```

Use HTTPS for non-local API URLs. The importer creates DRAFT chapters, DRAFT lessons and DRAFT questions only; it leaves source verification false, never overwrites existing chapters, and stops on code collisions. The API uses an authenticated administrator session, not the old shared X-Admin-Token or demo-mode bypass. Since the API writes are separate requests, an API failure can leave partial draft records; inspect the CMS before retrying.

## Stage 5 — editorial review and publication

1. Open Admin Content Studio using an authenticated administrator account.
2. Confirm class, subject, chapter, official book edition, chapter title and exact PDF page mapping.
3. Review the full teaching sequence: prerequisite, original explanation, worked example, guided practice, independent practice, assessment and recap where pedagogically appropriate.
4. Check every answer key, explanation, question type, age appropriateness, language, visual, units and scientific/mathematical correctness. Mark questions approved only after review.
5. Verify the lesson's source title, HTTPS URL, edition/session and page range in the CMS. A chapter/lesson cannot be newly published without the source verification and supported assessment checks.
6. Publish the chapter, then its reviewed lessons. Verify the student view and attempt/progress persistence for the correct class/subject.

## Known status and limits

- The canonical V6 outline contains 102 chapter rows across six class–subject tracks. Their official source editions/page mappings have not yet been verified in the clean-migration baseline.
- The clean-migration baseline contains four active approved original questions (three MCQs and one True/False), all in Class 7 Mathematics. Generic generated questions were disabled rather than passed off as real content.
- V27 defines a minimum planning target of 2,166 original questions. This is a target, not content already authored or published. See docs/product/curriculum-question-inventory.md and docs/product/curriculum-question-targets.csv for the per-track and chapter × question-type inventory.
- Version history and a dedicated structured question/block editor are not yet implemented; the current CMS uses validated JSON editors for lesson blocks and question metadata.
- Content is not launch-ready until source mapping, original lesson authoring, assessment coverage, review and focused student acceptance checks are complete.
