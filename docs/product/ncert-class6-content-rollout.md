# QuantaEdge Class 6 Hindi content rollout

The Class 6 book package is QuantaEdge-branded original teaching content. The internal selected chapter inventory is 10 Mathematics chapters and 12 Science chapters. A strict zero-omission source-topic crosswalk is a publication gate; see `docs/product/class6-content-completeness-policy.md`.

## Package contents

- 22 chapter-wise concept PDFs: explanations, worked examples, recap and QuantaEdge Advanced only. No questions, practice, tests or answers.
- 2 coherent complete-book PDFs: all teaching content plus the existing chapter question banks, assessment sections and answer explanations.
- 22 editable chapter authoring bundles and 22 JSON/CSV question banks.
- 266 authored topic records, 264 website micro-topic slots plus two additional topic records embedded in the chapter's twelfth lesson, and 286 question records (13 per chapter; unchanged).
- Branded with the QuantaEdge logo; no source/publisher labels or draft review watermarks are printed in learner-facing books.

## Exact local PowerShell import

1. Download `QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip` and place it in the repository root (next to `docker-compose.yml`).
2. Pull the code and apply the migration/rebuild the local services:

```powershell
cd D:\\QuantaEdge\\QuantaEdge
git pull origin main
.\\scripts\\quantaedge-local.ps1 rebuild
```

3. Sign in to the local QuantaEdge Admin portal. In your own browser's developer tools, open **Application/Storage → Cookies → localhost** and copy the value of the `QE_SESSION` cookie locally. Do not share it in chat and do not commit it.

First validate the batch without database writes:

```powershell
$env:QUANTAEDGE_API_BASE_URL = "http://localhost:8080"
$env:QUANTAEDGE_ADMIN_SESSION = "<paste your local QE_SESSION cookie value>"
python .\\scripts\\import_ncert_class6_drafts.py --package ".\\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip"
```

When the dry-run report has no errors, apply the drafts:

```powershell
python .\\scripts\\import_ncert_class6_drafts.py --package ".\\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip" --apply
```

The new Flyway migration `V48__ncert_class6_quantaedge_learning_path.sql` is on `main`. The importer attaches by class/subject/chapter code, namespaces lesson codes to avoid collision, creates lessons and question banks as DRAFT, and never publishes automatically. It reports per-chapter success/failure as JSON. Verify the output report and the 22 chapter records in Admin Content Studio before editorial review and the separate Publish action.

## Coverage and release controls

`coverage/CLASS6_TOPIC_COVERAGE_MATRIX.csv` maps each authored topic to the book sections and website lessons. `coverage/CLASS6_SOURCE_COVERAGE_CHECKLIST.csv` lists the chapter, the mapped topic titles and every source-review category to sign off. The selected source edition's headings/subheadings, definitions, examples, activities, diagrams/tables/figures and end-of-chapter learning outcomes must be explicitly cross-checked before a chapter is marked ready to publish.

**Important:** Downloading/generated PDFs does not mutate the local database. A database write is confirmed only when the authenticated `--apply` command completes and reports `APPLIED_DRAFT_ONLY` for the chapters; a successful import is still not publication.
