# NCERT Class 6 Hindi content rollout

**Source of truth:** NCERT Class VI Hindi editions, गणित प्रकाश (Mathematics) and जिज्ञासा (Science). The content pack contains 10 Maths chapters and 12 Science chapters. Every authored bundle must remain a draft through import; Admin Content Studio is the publication gate.

## Package contents

- 22 chapter-wise concept PDFs: no practice prompts, MCQs, tests, or answer keys.
- 2 complete-book PDFs: concept material plus practice/test questions and answer explanations.
- 22 authoring JSON bundles and 22 question-bank JSON files, with editable DOCX, CSV question banks, and CONTENT_QA_REPORT.json.
- Fractions in Maths are rendered with numerator above denominator. Learner-facing PDFs omit review-watermark wording.

## Draft import

The batch importer is committed at **scripts/import_ncert_class6_drafts.py**. It uses the existing **scripts/import_site_content_draft.py** validation and Admin API, namespaces lesson codes by chapter, validates each bundle, and writes DRAFT records only.

Dry-run first:

```bash
python scripts/import_ncert_class6_drafts.py --package /path/to/QuantaEdge_Class6_NCERT_Hindi_Complete_Content_Package.zip
```

On a machine with a reachable QuantaEdge Admin API, after signing into Admin, expose the QE_SESSION cookie value as QUANTAEDGE_ADMIN_SESSION and set QUANTAEDGE_API_BASE_URL to the intended API origin. Do not commit or paste session cookies into source control.

Apply the draft import:

```bash
python scripts/import_ncert_class6_drafts.py --package /path/to/QuantaEdge_Class6_NCERT_Hindi_Complete_Content_Package.zip --apply
```

The importer does not publish anything. It skips chapters whose lesson codes are already present, stops safely on partial collisions, and outputs a JSON report. Review each chapter, each answer key, Hindi wording, and student/PDF behavior in Admin Content Studio before an authorized administrator publishes.

## Explicit status boundary

Generating the downloadable content pack does not change a running database. A live write is confirmed only when the importer is run with a valid Admin session against the intended API and the returned report says APPLIED_DRAFT_ONLY for each chapter.
