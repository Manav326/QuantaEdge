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

The API endpoint is protected by AuthorizationService.requirePermission(context, "CONTENT_EDIT"). The currently supported pilot script validates the signed-in staff identity through the Admin proxy and does not force X-QE-Workspace on this call, matching the historical importer request contract. Do not relax the permission check to bypass authentication.

### What about QUANTAEDGE_ADMIN_TOKEN / X-Admin-Token?

The repo history confirms the old Admin Content API initially compared X-Admin-Token with the configured APP_ADMIN_CONTENT_TOKEN (fed by QUANTAEDGE_ADMIN_TOKEN in Docker Compose). That shared-key authorization was removed in security commit 7d69a011b3, which changed content authoring to require an authenticated staff/admin identity. In the current main code, AdminContentController.authorize ignores the supplied token and checks the AuthContext permission; the newer TextbookExtractionController likewise requires an authenticated context and CONTENT_EDIT. Therefore, adding X-Admin-Token alone cannot authenticate this textbook import. Do not restore a broad shared-key bypass just to work around the 401. Use the working Education Admin session route; a future machine-token design would need a narrowly scoped endpoint and an explicit audit actor.

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
2. The script will prompt for the current access-cookie Value with hidden input. In Chrome DevTools → Application → Cookies for localhost, copy the active access cookie named QE_SESSION or QE_STAFF_SESSION. Do not use QE_REFRESH, QE_STAFF_REFRESH, or QE_HRMS_SESSION. The token is short-lived, so re-authenticate and copy it immediately before import.
3. Run the existing script:
   ~~~powershell
   .\scripts\quantaedge-class6-tutor-pilot.ps1 -Mode ImportDraft
   ~~~
4. Paste only the cookie Value at the prompt and press Enter. Do not paste a command block into the prompt. The script sends the value using the historical QE_SESSION cookie name and validates /api/v1/auth/me through http://localhost:3001, the same Admin proxy route used by the browser. It deliberately omits X-QE-Workspace for this legacy-compatible request path, then confirms that the session is an Education Admin staff identity with ADMIN or CONTENT_EDIT.
5. If session validation returns HTTP 401, **stop**. Do not loop through cookie names or repeat uploads. Record only the status/message. At the previous checkpoint the authenticated Admin proxy route worked while a direct API request with a forced workspace header returned 401. The current change routes both session verification and multipart import through the Admin proxy. If it still returns 401, collect the proxy response and inspect cookie forwarding/token freshness; never weaken API authorization.
6. If authentication is accepted, read the importer summary. A complete pilot import should report four manifests and four successes. If it reports partial success, note exact book IDs and response errors; importer keys are idempotent, but inspect counts before rerunning.

The importer now sends Cookie: QE_SESSION=<access-token> and Accept: application/json, without X-QE-Workspace. This matches the initial importer implementation (commit 999f95e1cd). Sending a staff session token under the QE_SESSION request-cookie name is acceptable to AuthService's token lookup, because the cookie name selects which token is read; the server still hashes and validates the token against auth_session and checks the staff identity/permissions. This is not a token bypass.

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
- **All books return HTTP 401:** stop after the first authentication check fails. No content data should have been accepted. Confirm Education Admin (not HRMS), use the current access cookie with the Admin proxy on port 3001, and compare the result of the proxied /api/v1/auth/me with the import route. Never share the token. The old QUANTAEDGE_ADMIN_TOKEN is not an auth fallback for this endpoint.
- **HTTP 403 / permission required:** verify /api/v1/auth/me through the Admin origin; do not elevate roles or disable the CONTENT_EDIT guard to bypass it.
- **Manifest/hash/page validation error:** return to DryRun; do not edit hashes to make an import pass.
- **LOW_TEXT pages:** keep review-gated; no blanket approval just to enable Tutor retrieval.
- **Need to resume in a new conversation:** read this file and the linked execution tracker first; run git pull --ff-only origin main; inspect the latest checkpoint; continue at the first incomplete step. Do not repeat completed dry runs or re-plan the pipeline.
