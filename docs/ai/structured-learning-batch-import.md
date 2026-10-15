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

---

## Repository and GHCR handoff checkpoint — 2026-10-15

This section is the resume point for the next QuantaEdge coding chat. It records what was actually verified from GitHub during the resource audit. It does not replace a fresh check of `main`, Actions, the current GHCR tags, or the live API before a write.

### GitHub branch and Actions

- The branch is `main` only. Continue additively; do not create another branch, reset history, remove existing files, or alter unrelated user commits.
- At the beginning of this resource audit, `main` was at `72577e9a8ac4d6b5018b8608273f7aadaf6f283d`. It advanced during the audit to `a53323a583f6a585e4740bc266e01bd9fe01bb9f` due to ongoing changes. Pull/re-check the current head before using any line numbers, hashes, or statuses recorded here.
- The completed aggregate build at [run 38742972709](https://github.com/Manav326/QuantaEdge/actions/runs/38742972709), commit `72577e9`, was green across all 8 jobs: Detect affected components, Content import safeguards, Android APK, API verification, and Admin/People Operations/Web/API image builds.
- At the end of this audit, the newer aggregate CI run [38745726895](https://github.com/Manav326/QuantaEdge/actions/runs/38745726895) for commit `a53323a` was still in progress. Do not call the *current* head all-green until that run has completed successfully.
- The reusable source-cache workflow is `.github/workflows/textbook-cache.yml`. Related workflows are `.github/workflows/textbook-extraction-pilot.yml`, `.github/workflows/commit-class6-extraction-artifact.yml`, `.github/workflows/textbook-visual-analysis.yml`, and `.github/workflows/textbook-visual-candidates-v3.yml`.

Verified pipeline runs:
- [Class 6 GHCR extraction pilot — run 38579506350](https://github.com/Manav326/QuantaEdge/actions/runs/38579506350): succeeded; uploaded extraction artifact `11902717615`, retained until 2027-01-12.
- [Resumable visual extraction and analysis — run 38672043506](https://github.com/Manav326/QuantaEdge/actions/runs/38672043506): succeeded; actual Class 6 English Science book pulled from GHCR, full visual bundle published and checkpointed.
- [Visual candidate pack v3 — run 38688536449](https://github.com/Manav326/QuantaEdge/actions/runs/38688536449): succeeded; GHCR pull, checksum binding, tests, immutable candidate package and GitHub checkpoint all succeeded.
- [Curriculum pipeline audit — run 38676978691](https://github.com/Manav326/QuantaEdge/actions/runs/38676978691): offline validation succeeded; the live-registry job was skipped. This is not proof that every class/medium registry image is currently available.

### Real Class 6 source resources already committed under `data/`

The committed source page records are **extraction/review artifacts, not finished lessons or approved Tutor evidence**. Original complete-book PDFs remain in GHCR.

| Subject | Language | Book ID | Pages | Text extracted / needs content review | Low-text pages | Manifest |
|---|---|---|---:|---:|---:|---|
| Mathematics | English | `ncert-c6-english-fegp1` — Ganita Prakash | 412 | 400 | 12 | [manifest](../../data/textbook-extractions/class-6/english/pages/07e70f879355ef91fadba2c375928c807649b5018fbec16ea6a0cf1aa6b63d33.manifest.json) |
| Science | English | `ncert-c6-english-fecu1` — Curiosity | 272 | 265 | 7 | [manifest](../../data/textbook-extractions/class-6/english/pages/7e6c3a6ba4f80aa15120f446fb66614c3cec7c17462414d410d7be66c3ca8b73.manifest.json) |
| Mathematics | Hindi | `ncert-c6-hindi-fhgp1` — Ganita Prakash (Hindi) | 304 | 293 | 11 | [manifest](../../data/textbook-extractions/class-6/hindi/pages/f815150b18a0124fbe0742cd3896ef802b1ad1760586b3614d68b75a8dc5d38f.manifest.json) |
| Science | Hindi | `ncert-c6-hindi-fhcu1` — Jigyasa | 264 | 263 | 1 | [manifest](../../data/textbook-extractions/class-6/hindi/pages/af6c738eed25d9565403eb42350b98f793630500c871e8c719c25261b995afec.manifest.json) |
| **Total** | | | **1,252** | **1,221** | **31** | |

All four manifests are marked `DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW` and `published=false`. The matching per-book page JSONL files are beside their manifests. The summary reports are `data/textbook-extractions/class-6/{english,hindi}/extraction-report.json`. Do not skip/relabel low-text pages as ready. The extraction Action's success is about the extraction job, not content approval or student publication.

### GHCR verification and visual assets

**Whole-book source used in the visual pilot**
- Source image: `ghcr.io/manav326/quantaedge-textbooks-class-6-english:latest`.
- Exact source book: `ncert-c6-english-fecu1` (Class 6 English Science, *Curiosity*, Reprint 2026–27).
- Original PDF SHA-256: `7e6c3a6ba4f80aa15120f446fb66614c3cec7c17462414d410d7be66c3ca8b73`.
- The v3 candidate Action pulled the GHCR image successfully and verified its index hash against the committed source manifest before extracting candidates.

**Analysis bundle**
- GitHub index: [data/textbook-visuals/class6-curiosity-en/bundle-index.v1.json](../../data/textbook-visuals/class6-curiosity-en/bundle-index.v1.json).
- Version tag from run 38672043506: `class6-class6-curiosity-en-run-38672043506`.
- Stable GHCR tag: `ghcr.io/manav326/quantaedge-textbook-visuals:class6-class6-curiosity-en-latest`.
- Bundle TAR SHA-256 recorded in the index: `6584aa817e1bbc1fa12b426e1dcfca845fb44d913706caa213b83421fda3d1af`.
- The bundle index reports 146 selected assets, 145 analysis records, and 28 failed/error statuses. Treat analysis as **incomplete** until that counter mismatch and failures are reviewed. The index and results both set `publication_allowed=false`; results require human review.

**Visual candidate v3 package**
- GitHub pack: [data/textbook-visual-candidates-v3/class6-curiosity-en/](../../data/textbook-visual-candidates-v3/class6-curiosity-en/).
- Package: `ghcr.io/manav326/quantaedge-textbook-visuals:candidates-v3-class6-class6-curiosity-en-run-38688536449`.
- Candidate tar SHA-256: `4b03642acde232c968999d90320ace616f8b5f51d286ac0cb1ed961364e7465d`.
- 60 heuristic candidates across 54 distinct source pages (24 embedded-image candidates and 36 page-preview candidates). The method is heuristic, not semantic validation. The chapter map is `TOC_DERIVED_NEEDS_HUMAN_REVIEW`; `semantic_classification_performed=false`; `publication_allowed=false`.
- Review the contact sheet/candidates and source-page context before deciding what to attach to original teaching material. Do not automatically copy these source images to `apps/web/public/content/visual-assets` or public raw GitHub URLs; repository visibility is public, and candidate assets are not publication-cleared.

### Other existing data and archives — inspect before generating anything new

- `data/ai-training/datasets/manifest.json` describes 25,000 records generated by `deterministic_original_templates_v1`, status `SYNTHETIC_DRAFT_REQUIRES_TEACHER_REVIEW`. It explicitly says these are not LLM-generated, not official textbook questions, and not source-page-grounded. Do not treat this dataset as the Navodaya authored lesson bundle or as teacher-approved training data.
- `docs/content-pilot-input/` contains `maths-chapter-7.bundle.json` and `science-chapter-1.bundle.json` plus page JSONL/manifests. These are Class 6 Hindi NCERT source-extraction pilots (`DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW`), not the structured teaching/assessment bundle expected by this importer.
- Two ZIP files exist at the repository root: `QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip` (13,932,775 bytes) and `quantaedge-class6-hindi-pilot-input.zip` (3,913,343 bytes). They were **not inspected internally during this GitHub API audit** because the connector does not return binary ZIP blobs as UTF-8. In the next session, inspect each ZIP's member listing and bundle schema from a normal local checkout before concluding that no reusable authored bundle exists. Do not assume either ZIP is Navodaya-targeted or ready to import until inspected.
- The prior audit found no path explicitly named `navodaya` or `jnvst`. The Navodaya draft importer and tests are present at [`scripts/import_structured_learning_batch.py`](../../scripts/import_structured_learning_batch.py) and [`tests/test_structured_learning_batch_import.py`](../../tests/test_structured_learning_batch_import.py).

### Current course and safe next operation

- Migration `V97__additive_course_scoped_tracks.sql` seeds `NAVODAYA_BATCH` as an independent Class 6 course and creates **Maths and Science tracks only**. That setup does not prove that lesson or question content has been populated. Confirm intended course scope before importing; do not silently treat general NCERT lessons as the full JNVST exam syllabus or create extra subject tracks without a deliberate plan.
- The live API import in the older operator runbook had previously hit HTTP 401. The user subsequently stated the issue was resolved, but this repository/Actions inspection did **not** observe a successful post-resolution live write or verify database row counts. Freshly authenticate, execute the importer's no-write dry run, then use live read-only preflight. If the current authenticated request returns 401, stop and diagnose the session/proxy rather than weakening authorization or looping writes.
- First inspect the two root ZIPs. If one contains valid authoring bundles, run `scripts/import_structured_learning_batch.py --manifest <manifest>` without `--apply` and inspect the validation output. Only proceed to `--apply` after the exact target program, class, subject, language, bundle schema, provenance, lesson codes and asset rights are verified.
- Imports are sequential, not one batch transaction. If one fails mid-run, completed records may remain as drafts; inspect Content Studio/API results before retrying. Never blindly replay a partial batch.
- Do **not** invoke `scripts/publish_imported_ncert_hindi_packages_once.py` for this task: it is explicitly a one-time bulk release that bypasses the ordinary per-lesson/question review workflow.
- After any successful draft import, verify returned chapter/lesson/question IDs and read-only API/database counts. Leave all items unpublished. Human review of lesson accuracy, answer keys, bilingual text, visual provenance and use rights is a separate gate.

---

## Verified ZIP audit and local draft insertion — 2026-10-15

This section supersedes the earlier note that the two root ZIPs had not been inspected internally. The read-only auditor, tests and workflow are now committed additively on main:
- Auditor: scripts/audit_content_package_completeness.py
- Tests: tests/test_audit_content_package_completeness.py
- Workflow: .github/workflows/audit-content-package-completeness.yml
- Successful audit run: https://github.com/Manav326/QuantaEdge/actions/runs/38748782851
- Audit artifact ID: 11980740093 (machine-readable JSON report; 30-day retention).

### Exact findings

**QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip**
- ZIP CRC/integrity: PASS; 144 archive members; 24,226,844 uncompressed bytes.
- SHA-256: 49c0b21b6acc1693e5a5e059a2de4925b973b0bb7677526778e0d79631ecf3a7.
- 22/22 authoring bundles pass the existing validate_site_bundle validator. Scope: Class 6, Hindi, Maths + Science; 10 Maths chapters and 12 Science chapters.
- Totals: 286 lessons, 286 questions, 1,652 teaching blocks, zero visual/media blocks. Every bundle meets the legacy package convention of 13 lessons and 13 questions with one question per lesson.
- Duplicate chapter/target lesson codes: none.
- Editorial warning: the prompt “पृथ्वी का प्राकृतिक उपग्रह कौन है?” occurs twice in the Beyond Earth chapter (question IDs beyond-earth-test-input-01 and beyond-earth-test-summary-mcq). Review whether this is intentional before publication.
- Verdict: structurally suitable for draft import, not teacher-approved/publish-ready, and not a visual-rich package. It is NCERT Class 6 Hindi Maths/Science content, not proof of full Navodaya/JNVST coverage (for example, no separate Mental Ability or Language track is included).

**quantaedge-class6-hindi-pilot-input.zip**
- ZIP integrity: PASS; 9 members; 4,380,442 uncompressed bytes.
- SHA-256: 959d7c774bdaf807e8d21e0ad26e182ffc4baaff2af8a6bc4efc67812801c94e.
- Contains original chapter PDFs, bundle metadata, extracted-text JSONL and manifests for Maths Chapter 7 and Science Chapter 1. It contains zero content/bundles authoring JSONs.
- Verdict: do not pass this ZIP to the lesson importer. Use the source-page review/import workflow if those source records are needed.

The audit Action passed all five auditor tests. This proves archive readability and structural compliance only; it does not prove answer correctness, media rights, JNVST syllabus coverage, that the local database is migrated, or that data has been imported.

### Local procedure: audit and dry run first

Open PowerShell in the repository checkout. Pull main and inspect the working tree; do not reset local changes or delete database volumes:

~~~powershell
Set-Location D:\QuantaEdge\QuantaEdge
git pull --ff-only origin main
git status --short --branch
python --version
Invoke-RestMethod http://localhost:8080/actuator/health
~~~

Run the same read-only audit locally:

~~~powershell
python scripts/audit_content_package_completeness.py --package .\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip --package .\quantaedge-class6-hindi-pilot-input.zip --output (Join-Path $env:TEMP 'qe-content-package-completeness.json')
~~~

Then use the existing authoring importer with no --apply. This is a no-write dry run and does not require a staff session:

~~~powershell
python scripts/import_ncert_class6_drafts.py --package .\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip --program-code NAVODAYA_BATCH
~~~

Review the full JSON output. Expected baseline: mode DRY_RUN, selected_chapters=22, failed=0, with 10 Maths and 12 Science bundles. Each bundle should report 13 lessons and 13 questions. If any bundle fails, or course scope is unclear, stop. Never use scripts/publish_imported_ncert_hindi_packages_once.py as an import shortcut; it bypasses normal lesson/question review.

### Read-only local course/auth preflight before the first write

Sign in to Education Admin at http://localhost:3001 as staff with content-edit permission. In PowerShell, enter the active QE_STAFF_SESSION and its matching QE_STAFF_REFRESH at secure prompts. Do not place cookie values directly in command history, files, chat or the audit output:

~~~powershell
function Set-EnvFromSecurePrompt {
  param([string]$Name, [string]$Prompt)
  $secure = Read-Host -Prompt $Prompt -AsSecureString
  $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try {
    [Environment]::SetEnvironmentVariable($Name, [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr), "Process")
  } finally {
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
  }
}
Set-EnvFromSecurePrompt "QUANTAEDGE_ADMIN_SESSION" "Active QE_STAFF_SESSION cookie value"
Set-EnvFromSecurePrompt "QE_STAFF_REFRESH" "Matching QE_STAFF_REFRESH cookie value"

$headers = @{
  Cookie = "QE_STAFF_SESSION=$env:QUANTAEDGE_ADMIN_SESSION"
  "X-QE-Workspace" = "STAFF"
  Accept = "application/json"
}
$programs = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/admin/content/programs" -Headers $headers
$target = @($programs | Where-Object { $_.code -eq "NAVODAYA_BATCH" -and $_.curriculum_class_code -eq "6" })
if ($target.Count -ne 1) { throw "Expected exactly one active Class 6 NAVODAYA_BATCH course; found $($target.Count). Stop without importing." }

foreach ($subject in @("maths", "science")) {
  $url = "http://localhost:8080/api/v1/admin/content?classCode=6&subjectCode=$subject&status=ALL&programCode=NAVODAYA_BATCH"
  $rows = @(Invoke-RestMethod -Uri $url -Headers $headers)
  if (@($rows | Where-Object { $_.subject_code -eq $subject }).Count -eq 0) {
    throw "Target subject track $subject was not found for NAVODAYA_BATCH. Stop without importing."
  }
  Write-Host "$subject preflight rows: $($rows.Count)"
}
~~~

These are authenticated GET requests only. If either request returns 401/403, stop and fix staff-session routing; do not start a batch. The existing importer client uses QE_STAFF_SESSION, X-QE-Workspace: STAFF, and can refresh once using the paired refresh cookie.

### Apply as drafts only

Only after the dry run and read-only preflight both pass, run:

~~~powershell
$reportPath = Join-Path $env:TEMP 'qe-navodaya-class6-hindi-import.json'
$result = python scripts/import_ncert_class6_drafts.py --package .\QuantaEdge_Class6_QuantaEdge_Premium_Hindi_Package.zip --program-code NAVODAYA_BATCH --api-url http://localhost:8080 --apply
$exitCode = $LASTEXITCODE
$result | Out-File -FilePath $reportPath -Encoding utf8
$result
if ($exitCode -ne 0) { throw "Import reported a failure. Inspect $reportPath and Content Studio before retrying; do not replay the batch blindly." }
~~~

The importer namespaces lesson codes by chapter, attaches to matching existing chapters when appropriate, skips a chapter only if every intended lesson code already exists, and refuses partial lesson-code collisions. The import is sequential, not one transaction: a later failure can leave earlier records as drafts. If any FAILED_SAFELY result appears, inspect the report and Content Studio before retrying.

Expected on an empty target: 22 selected bundles, no failures, and publish_action_taken=false. Existing fully populated chapters may be skipped. Compare pre-import and post-import counts rather than assuming all 286 lessons were newly created.

### Read-only verification after apply

Repeat the two GET /api/v1/admin/content queries above and compare them with pre-import counts. If no equivalent draft content existed beforehand, the expected addition is 10 Maths chapters / 130 lessons / 130 questions and 12 Science chapters / 156 lessons / 156 questions (286 lessons and 286 questions combined). Confirm lesson status DRAFT and chapter status DRAFT; nothing should be published. The block_count total for these lessons should be 1,652. Resolve count mismatches against the import JSON and Content Studio; do not hand-edit database tables.

Known gaps remain: this package has no visual blocks, one repeated prompt to review, Hindi only, and no evidence of full JNVST syllabus coverage. Draft insertion can stage the content for review, but do not treat it as complete student-ready multimodal material until those gaps are addressed.
