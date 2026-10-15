# Class 6 GHCR textbook extraction pilot — operator runbook

**Last updated:** 2026-10-15  
**Repository:** Manav326/QuantaEdge  
**Local checkout used in this run:** D:\QuantaEdge\QuantaEdge  
**Execution tracker:** [Textbook extraction and AI Tutor grounding](../ai/textbook-extraction-execution.md)

Use this runbook when resuming the Class 6 complete-book extraction/import pilot. Start here instead of rediscovering the same environment, authentication, and import steps. Keep code changes on main only; no branches or PRs for this workflow.

## 1. Current checkpoint (read before running commands)

As of the last verified local checks on 2026-10-15:

- Local containers were running: API http://localhost:8080, student web http://localhost:3000, Education Admin http://localhost:3001, HRMS/People http://localhost:3002; PostgreSQL and Redis were healthy.
- API health returned UP. The database had Flyway schema version 96.
- Python 3.12.10 is installed at D:\Tools\Python312\python.exe. Windows User PATH was updated so python works directly. Do not reinstall Python or use the Microsoft Store alias.
- The four complete-book manifests and sibling page JSONL files are committed under data/textbook-extractions/class-6/. The dry run verified them successfully.
- An initial draft-import attempt reached the API but got HTTP 401 for all four books: succeeded=0. No import was accepted. Do not interpret the source pipeline as complete.
- Browser authentication through http://localhost:3001/api/v1/auth/me returned authenticated=true, role ADMIN, staff ID 1, workspace EDUCATION_ADMIN, and CONTENT_EDIT. A direct browser fetch to http://localhost:8080/api/v1/auth/me returned 401. This is an unresolved cookie transport/session path mismatch, not a missing permission.
- Cookie-name compatibility code and its focused tests are on main at commit c9135d4ee3c19695713e068bc4057bed02d545fb; its GitHub Actions run [38668563104](https://github.com/Manav326/QuantaEdge/actions/runs/38668563104) completed successfully. This verifies the code/test workflow, **not** that the local user's cookie has been accepted at runtime. The proxy/direct-API 401 mismatch remains unresolved until a real draft-import attempt proves otherwise. Documentation commits trigger newer workflows; always check the newest main run.

**Safety rule:** Do not keep retrying the import after a 401. Stop, record the response, and investigate authentication routing. No secrets or access/refresh token values belong in chat, logs, screenshots, issue comments, or this file.

## 2. Admin portal identity — do not mix these up

| Portal | Local URL | Sign-in flow | Session cookie family | Allowed here? |
| --- | --- | --- | --- | --- |
| Education Admin / staff content console | http://localhost:3001 | Staff mobile + OTP | QE_STAFF_SESSION; legacy QE_SESSION may also exist | **Yes**, requires CONTENT_EDIT (the Education Admin ADMIN role has this permission) |
| HRMS / People Operations | http://localhost:3002 | Username + password | QE_HRMS_SESSION | **No** — HRMS is a separate security workspace |

Refresh cookies (QE_STAFF_REFRESH, QE_REFRESH, QE_HRMS_REFRESH) are not access cookies and must never be passed as the session token.

The importer uses workspace header X-QE-Workspace: EDUCATION_ADMIN. The API endpoint is protected by AuthorizationService.requirePermission(context, "CONTENT_EDIT"). Do not relax this check to bypass authentication.

## 3. Source books included in this pilot

The source PDFs remain in GHCR; the committed artifacts below contain extracted page text and manifests.

| Book ID | Title | Subject | Language | Edition | Pages | Pages flagged LOW_TEXT |
| --- | --- | --- | --- | --- | ---: | ---: |
| ncert-c6-english-fegp1 | Ganita Prakash | Mathematics | English | Reprint 2026-27 | 412 | 12 |
| ncert-c6-english-fecu1 | Curiosity | Science | English | Reprint 2026-27 | 272 | 7 |
| ncert-c6-hindi-fhgp1 | Ganita Prakash (Hindi) | Mathematics | Hindi | Reprint 2026-27 | 304 | 11 |
| ncert-c6-hindi-fhcu1 | Jigyasa | Science | Hindi | Reprint 2026-27 | 264 | 1 |
| **Total** | | | | | **1,252** | **31** |

Every manifest is whole-book scoped and marked DRAFT_EXTRACTION_ONLY_REQUIRES_REVIEW. Low-text pages must remain review-gated and must not be treated as ready Tutor evidence. The page count or successful extraction alone does not approve a source or create a lesson.

## 4. Startup and source-control checks

Open PowerShell in the checkout:

~~~powershell
Set-Location D:\QuantaEdge\QuantaEdge
git pull --ff-only origin main
git status --short --branch
git rev-parse --short HEAD
~~~

If the branch is not main or there are unexplained local changes, stop and reconcile before proceeding. Do not reset the checkout or alter database volumes.

Check the running stack without changing it:

~~~powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-WebRequest http://localhost:3001 -UseBasicParsing | Select-Object StatusCode
.\scripts\quantaedge-local.ps1 ps
~~~

Expected: API health UP, Admin page HTTP 200, and existing services running. If containers are stopped, use the documented local launcher; do not use reset, clean-build, database reseeding, or volume deletion for this task. The current importer uses the standard Python library only.

## 5. Focused tests and safe dry run

First run the importer unit tests:

~~~powershell
python --version
python -m unittest discover -s tests -p "test_import_ghcr_extracted_pages.py" -v
~~~

Expected: all focused tests pass. They validate complete-book manifest checks, named multipart fields, and cookie/workspace headers. If a test fails, do not proceed to the live import.

Then run the no-write source validation:

~~~powershell
.\scripts\quantaedge-class6-tutor-pilot.ps1 -Mode DryRun
~~~

Expected summary: exactly four expected Class 6 books, 1,252 pages, and 31 pages flagged for low text. DryRun does not authenticate or call the write API. Stop if a book, page count, checksum, URL, edition, or quality count differs unexpectedly.

## 6. Draft-only import procedure

Only continue after the focused tests pass and the current GitHub Actions run has been checked.

1. In Chrome, sign in to Education Admin at http://localhost:3001 with the staff mobile + OTP flow. Do not sign in through HRMS for this task.
2. Use Chrome DevTools → Application → Cookies for localhost to inspect the access cookies. Copy only the current access-cookie Value—prefer the active QE_STAFF_SESSION; if the active portal only has the legacy QE_SESSION, use that. Do not use a refresh cookie. The access token is short-lived, so re-authenticate and copy it immediately before importing.
3. Run the existing script:
   ~~~powershell
   .\scripts\quantaedge-class6-tutor-pilot.ps1 -Mode ImportDraft
   ~~~
4. At the script's hidden prompt, paste that access-cookie value and press Enter. Do not paste an entire command block into the prompt. The script should validate session/workspace/permission before invoking the importer.
5. If session validation returns HTTP 401, **stop**. Do not loop through cookie names or repeat all four uploads. Record only the status/message. Recheck the direct-vs-proxy mismatch: the authenticated Admin route http://localhost:3001/api/v1/auth/me previously returned authenticated JSON while direct API http://localhost:8080/api/v1/auth/me returned 401. Diagnose the proxy/header/session path; never weaken API authorization.
6. If authentication is accepted, read the importer summary. A complete pilot import should report four manifests and four successes. If it reports partial success, note the exact book IDs and response errors; the importer uses idempotent source keys, but still inspect existing counts before rerunning.

The script and importer send X-QE-Workspace: EDUCATION_ADMIN. The current compatibility attempt sends the provided access value under both QE_STAFF_SESSION and QE_SESSION; this is a diagnostic compatibility measure, **not proof** that an arbitrary cookie value is valid.

## 7. After a successful draft import: verify, do not approve

Run read-only SQL using the established local PostgreSQL connection. Do not modify tables manually.

~~~sql
SELECT registry_book_id, status, total_pages, imported_pages
FROM textbook_source_book
WHERE registry_book_id IN (
  'ncert-c6-english-fegp1',
  'ncert-c6-english-fecu1',
  'ncert-c6-hindi-fhgp1',
  'ncert-c6-hindi-fhcu1'
)
ORDER BY registry_book_id;

SELECT b.registry_book_id,
       COUNT(p.id) AS stored_pages,
       COUNT(*) FILTER (WHERE p.quality_status = 'LOW_TEXT') AS low_text_pages
FROM textbook_source_book b
JOIN textbook_extracted_page p ON p.source_book_id = b.id
WHERE b.registry_book_id IN (
  'ncert-c6-english-fegp1',
  'ncert-c6-english-fecu1',
  'ncert-c6-hindi-fhgp1',
  'ncert-c6-hindi-fhcu1'
)
GROUP BY b.registry_book_id
ORDER BY b.registry_book_id;
~~~

Expected, if all four imports completed: four DRAFT source-book rows, page totals of 412/272/304/264 and 1,252 rows in aggregate, including 31 LOW_TEXT records. If column/status details differ in a future schema, inspect migration V96 and the API response rather than guessing or changing data.

**Do not run -Mode ImportAndApprove automatically after import.** Source approval is a separate audited action requiring an explicit review of URLs, edition, hashes, page completeness and quality flags. Approval is not publication: do not generate/publish lessons or questions merely because page import succeeded.

## 8. Baseline database state before any successful page import

A read-only snapshot previously showed:

- Flyway schema: version 96.
- textbook_source_book: 0 rows.
- textbook_extracted_page: 0 rows.
- textbook_extraction_job: 0 rows.
- learning_book_intake: 0 rows.
- Existing content: 992 lessons and 4,570 questions.
- Existing records included 166 learning_document rows and 166 learning_pdf_asset rows.

These are historical baseline values captured before the rejected 401 attempts, not a live statement. Always re-query before assuming counts. Preserve existing lessons, questions, student progress and the PostgreSQL volume.

The four rejected API requests returned HTTP 401 and succeeded=0; the request was rejected in authentication before the textbook controller accepted any book. The goal is to keep it that way until authentication is fixed.

## 9. Next workflow after import

1. Finish and verify the draft-only page import.
2. Review low-text pages and ensure their quality status keeps them excluded from Tutor retrieval.
3. Create a human-reviewed chapter map bound to the exact book_id and SHA-256, starting from docs/ai/ghcr-extraction-chapter-map-template.json.
4. Use scripts/build_extraction_bundle_from_ghcr_pages.py only after source review; it skips chapters touching low-text/OCR-required/failed/empty pages.
5. Generate candidates using the local Ollama-compatible endpoint with scripts/generate_questions_from_extraction.py. All outputs stay drafts.
6. Continue through existing Content Studio review and publishing gates, then test Tutor retrieval and source/page citations end to end.
7. Update the execution tracker with the exact next task, commit SHA and CI result.

## 10. Troubleshooting shorthand

- **python opens Microsoft Store:** Python 3.12.10 is at D:\Tools\Python312\python.exe; Windows User PATH has been configured. Verify with Get-Command python and python --version; do not reinstall just for the alias.
- **All books return HTTP 401:** stop after the first authenticated request proves rejected. No content data should have been accepted. Confirm Education Admin (not HRMS), check the current access cookie, then diagnose the proxy/API route discrepancy. Never share the token.
- **HTTP 403 / permission required:** verify /api/v1/auth/me through the Admin origin; do not elevate roles or disable the CONTENT_EDIT guard to bypass it.
- **Manifest/hash/page validation error:** return to DryRun; do not edit hashes to make an import pass.
- **LOW_TEXT pages:** keep review-gated; no blanket approval just to enable Tutor retrieval.
- **Need to resume in a new conversation:** read this file and the linked execution tracker first; run git pull --ff-only origin main; inspect the latest checkpoint; continue at the first incomplete step. Do not repeat completed dry runs or re-plan the pipeline.
