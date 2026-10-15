# Navodaya structured-content batch import

## Purpose and compatibility

This is an additive batch orchestrator for the existing Content Studio draft importer. It does not modify the lesson/question API, database schema, existing importer, existing renderer or publication workflow. The accepted input files use the already-supported **quantaedge.site-content-authoring-draft.v1** format; the manifest groups those files for a preflighted sequential import.

The importer is deliberately draft-only. It does not approve questions, assert that source rights are cleared, approve visual assets or publish a chapter/lesson.

## 1. Prepare the input

Create a private working folder outside the public web assets, for example:

~~~text
navodaya-batch/
  batch-manifest.json
  drafts/
    maths-fractions-en.json
    maths-fractions-hi.json
~~~

Each draft file must already satisfy the current draft bundle contract used by **scripts/import_site_content_draft.py**. Every bundle must:

- set **bundle_status=AI_AUTHORED_DRAFT**, **publish_status=DRAFT**, **auto_publish=false**, and **editorial_state=PENDING_HUMAN_REVIEW**;
- set **curriculum.program_code=NAVODAYA_BATCH** and a **source_class_code** matching the manifest (defaults to Class 6);
- use one currently active subject track in that batch, the correct language, a chapter code, at least one lesson and at least one supported online question per lesson;
- keep source alignment and rights claims unverified until an editor checks them.

Start with the manifest example in **structured-learning-batch-manifest.example.json**. Paths are relative to the manifest folder. The optional SHA-256 on each manifest entry pins the exact JSON file; when present, the tool rejects any mismatch. Use unique lesson codes throughout a chapter's entire batch import to avoid duplicate records.

The migration on main currently seeds the Navodaya program with Maths and Science subject tracks. A bundle for another subject is blocked in the read-only live preflight until that course-scoped subject track is deliberately provisioned. This script will not create subjects or silently remap other subjects to Maths/Science.

## 2. Visual blocks

The existing Content Studio and learner web/Android renderer support media blocks. For **IMAGE**, **DIAGRAM** and **ANIMATION** blocks, include an HTTPS **url**, meaningful **title** and **alt** plus **title_hi** and **alt_hi**. Supply description/caption text in both languages if supplying either language's version.

For a source-derived visual, preserve the asset's **source_candidate_id**, **source_book_id**, **source_pdf_sha256**, **source_asset_sha256** and 1-based **source_pdf_page_number**. Keep it explicitly **visual_review_status=NEEDS_REVIEW**, **rights_status=SOURCE_REFERENCE_ONLY**, and **publication_allowed=false**. A draft import must not impersonate the content-reviewer approval step.

Use a private, approved, or rights-cleared HTTPS media location as appropriate. This importer intentionally refuses unreviewed source-candidate URLs served from public raw GitHub paths; a private source PDF or figure must not become public merely because its lesson is a draft. The tool records references to assets; it does not upload local files or create a media-storage endpoint.

## 3. Dry run first

From the repository root, run:

~~~powershell
python scripts/import_structured_learning_batch.py --manifest .\navodaya-batch\batch-manifest.json
~~~

The command validates every bundle before any database request. It reports total bundles, lessons, questions and visual blocks, and flags visuals that still need review. It performs no writes.

## 4. Live preflight and draft import

After the dry-run report is clean, use a currently authenticated staff session with the required content-edit permissions. Set the staff session cookie in the environment—not on the command line—and point the importer at the same API environment used for Content Studio:

~~~powershell
$env:QUANTAEDGE_ADMIN_SESSION = "<authenticated staff QE session cookie>"
python scripts/import_structured_learning_batch.py --manifest .\navodaya-batch\batch-manifest.json --api-url http://localhost:8080 --apply
~~~

For a remote API, use its HTTPS origin instead of localhost. Do not paste session credentials into the manifest, logs, repository, or chat.

Before the first write, **--apply** performs a read-only preflight: the Navodaya course must exist exactly once, every referenced subject track must exist, existing chapter codes must be unambiguous, and no lesson code may collide. New chapters are created as drafts; later bundles for that chapter attach to the same chapter. Bundles are then imported sequentially through the existing importer. No auto-approval or publishing is attempted.

A batch spanning multiple bundles is not one database transaction. If an API/network failure happens mid-run, the completed records remain drafts. The command stops and reports the partial point; inspect Content Studio and the returned IDs before retrying. Do not blindly restart a partially applied batch.

## 5. Acceptance after import

In Content Studio, verify chapter/lesson placement, the exact asset meaning and page provenance, captions and bilingual alternative text, mobile preview, all answer keys and question explanations. Confirm that source visual rights are cleared by the authorised reviewer before publication. Then separately exercise student render → answer → grading → saved progress and Tutor retrieval from approved lesson material. Certificate eligibility remains controlled by the existing subject-completion/formal-assessment rules.

Run the import safeguards tests with:

~~~powershell
python -m unittest discover -s tests -p "test_*.py" -v
~~~

## Current boundary

This tool imports existing structured draft bundles; it does not invent lesson material or convert arbitrary schemas automatically. No Navodaya lesson content is bundled with this tool. The actual prepared batch must be supplied in the stated existing draft format before live records can be inserted.
