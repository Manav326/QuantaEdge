# Agent 4 — Learning Platform, Content Studio, AI Pipeline, GHCR and Android Handoff

## Status summary

- **Working branch:** `agent/learning-content-ai-android`
- **Baseline audited:** `0d71946b3854a68c894001193a5174a41fa9a45b` (`main` as observed at task start)
- **Latest main integrated:** `e56620a3c59276a23dca5c2f7bbedbcf020f7da1`
- **Latest integration commit:** `09b10b71a2b35574379c497ea7cb3079f8e10c65` (second parent is the latest main above; main remains unmodified by Agent 4)
- **Repository:** [Manav326/QuantaEdge](https://github.com/Manav326/QuantaEdge)
- **Pull request:** Not created, as required.
- **Latest completed branch CI:** [Run 38341468420](https://github.com/Manav326/QuantaEdge/actions/runs/38341468420) passed all five jobs for commit `8131893ce4dc8a6f26f8ece3a6d5adece70b9ae6`; subsequent Android SDK argument alignment is being revalidated in runs 38341744121 and 38341748078.
- **Important validation caveat:** Verification was executed remotely in GitHub Actions. This environment still could not resolve GitHub for a local clone, so no local build or test command was run. The Android unit-test task was reported `FROM-CACHE`; no physical-device test was performed.

## 1. Initial audit and baseline

The repository already has the main architectural pieces; replacing them with parallel models would risk breaking the running product. The audit covered the student and administrator routes, learning and content APIs, source ingestion, extraction/generation scripts, class-wise textbook registry, GitHub Actions workflows, database migrations, and Android Gradle/module layout.

### Existing capability confirmed by source inspection

- The student web app has dedicated class, lesson, practice, progress and textbook screens. The learning API checks the authenticated student's class, supported subject, enrolment/subject authorization, and only delivers lessons and questions that are active and published.
- The Admin Content Studio and API already support class/subject/chapter/lesson discovery, editing, draft/review/published lifecycle, question review queue and question history. Source-ingestion APIs are explicitly review-oriented and do not publish source PDFs directly to students.
- The extraction flow creates page-addressable, review-only bundles. The question generator uses an OpenAI-compatible API only when both `--generate` and `--confirm-external-processing` are supplied; generated material is marked `AI_GENERATED_DRAFT` and is not auto-published.
- Textbook registry publishing already has class-specific image handling, language selection, whole-book validation, checksums, concurrent downloads, bounded retry rounds and coverage/error reporting in `scripts/textbook_registry.py` and `.github/workflows/textbook-cache.yml`.
- The Android app already uses the canonical API and has learning, practice/assessment, progress, protected resource and related student workflows. CI contains Gradle build and unit-test steps.

### Actual content baseline and limitations

The repository README documents 102 canonical chapter rows across Class 6–8 Mathematics and Science. A chapter row or lesson shell is not proof of finished teaching content. The documented clean-migration baseline has four active approved original questions (three MCQ and one True/False), all in Class 7 Mathematics. All six official textbook edition and page mappings still require editorial verification. The 2,166-question bank is a planning target, not a claim of completed or published questions.

A successful GitHub Actions run was observed for the pre-change baseline commit `0d71946b3854a68c894001193a5174a41fa9a45b` (run [38339756149](https://github.com/Manav326/QuantaEdge/actions/runs/38339756149)). This is **baseline evidence only**, not a pass for this branch. The main workflow for commit `00ed52ad75432153aefc93105cd83ae2c472843c` subsequently completed with a failure in the new Android setup job; the exact cause and corrective change are recorded below.

## 2. Changes implemented

### A. Large whole-book source PDF intake

File: `services/api/src/main/java/com/quantaedge/api/SourcePdfDownloadService.java`

- Raised the default source-PDF limit from 50 MiB to 500 MiB, matching the existing `V52__learning_pdf_assets_500mb.sql` asset-size constraint.
- Added an optional limit override using JVM property `quantaedge.source-ingestion.max-pdf-bytes` or environment variable `QUANTAEDGE_SOURCE_MAX_PDF_BYTES`.
- Keeps a hard configurable range of 1 MiB through 500 MiB and fails fast on invalid configuration.
- Uses the advertised `Content-Length` to reject oversized responses before reading and to allocate one exact-sized byte array when the length is known. This reduces the extra temporary buffer/copy cost for large whole-book PDFs.
- Rejects truncated declared-length bodies, unexpected trailing bytes and streams that exceed the limit. For responses without a length, reads incrementally with a bounded buffer.
- Updated the cached-PDF error to report the configured limit rather than the stale 50 MiB wording.

This is a bounded in-memory ingestion path, not a claim that 500 MiB is safe for every deployment's heap/database. Before production intake of large files, size the JVM/container/database memory and multipart/proxy limits, and monitor actual peak memory.

### B. PDF regression coverage added

File: `services/api/src/test/java/com/quantaedge/api/SourcePdfDownloadServiceTest.java`

Added tests for a default limit that accommodates 250 MiB books and for exact-length stream reading, truncated bodies, and bodies exceeding their advertised/allowed size. Existing cache checksum, HTTPS and local/private-host guards remain in place.

**Tests pass in branch CI:** `SourcePdfDownloadServiceTest` reported 7 tests, zero failures/errors.

### C. AI-generation recovery and bounded transient retries

File: `scripts/generate_questions_from_extraction.py`

- Retries transient provider HTTP responses (408, 425, 429, 500, 502, 503, 504) and transient URL/timeout errors for no more than five attempts per model request.
- Honors a numeric `Retry-After` value, capped at 30 seconds; otherwise uses bounded exponential backoff. Non-retryable HTTP errors, including authentication failures, stop immediately.
- Makes `--max-api-calls` a ceiling on actual HTTP attempts for the run, including retries; the remaining attempt budget is passed to each request.
- Adds opt-in `--resume`. The generator can skip existing chapter bundles only when they are valid JSON candidates with the expected draft/review-only metadata, matching source PDF SHA-256, chapter, model and curriculum, nonempty lessons/questions, supported question types and expected question count. Mismatched/incomplete candidates fail closed rather than being overwritten.
- Keeps exclusive file creation, original-source metadata, page citations, question validation, editor review and the no-auto-publish boundary.

Example invocation after reviewing the extraction bundle and provider privacy terms:

```bash
python scripts/generate_questions_from_extraction.py \
  --extraction /path/to/DRAFT_EXTRACTION_ONLY.json \
  --output-dir /path/to/private-ai-drafts \
  --generate --confirm-external-processing --resume
```

Configure `OPENAI_API_KEY`, `QUANTAEDGE_QUESTION_MODEL` and, if needed, `OPENAI_API_BASE_URL`. Generation was not run here; no live provider request was verified. `--resume` is opt-in and still requires a matching source edition/hash and editorial review before draft import or publication.

### D. AI-generation tests added

File: `tests/test_question_generation.py`

Added controlled tests for retrying a transient 429 while honoring `Retry-After`, not retrying a permanent 401 response, counting HTTP attempts, and accepting only a matching review-gated resume candidate. Tests use mocks and do not require a live AI account.

**Tests pass in branch CI:** these controlled cases run as part of the 161-test Python suite.

### E. Android APK workflow/installer alignment

Files:
- `.github/workflows/android-build.yml`
- `scripts/android-fetch-latest-apk.ps1`

The standalone Android build workflow now uploads its installable debug APK as `quantaedge-android-apk`, matching the install helper's default exact artifact name. The helper's failure text now correctly describes an installable APK rather than calling it a release APK; the existing main-branch PowerShell handling for native stderr/missing artifacts was preserved during integration.

The latest main CI added a `build-android-apk` job running `testDebugUnitTest assembleDebug` and uploading `quantaedge-android-apk`. The initial run failed before Gradle because the SDK action requested the removed legacy package `tools`. Latest main commit `e56620a3c59276a23dca5c2f7bbedbcf020f7da1` corrected the package list to `platform-tools platforms;android-36 build-tools;36.0.0`; that current-main configuration is integrated into both `.github/workflows/ci.yml` and the branch-only validation workflow. Run [38341468420](https://github.com/Manav326/QuantaEdge/actions/runs/38341468420) passed SDK setup/build with `platform-tools`; newer runs are checking the exact updated package list.

This is a debug APK, not a signed Play Store release. No Android emulator/physical device was available for an end-to-end learning-session test.

## 3. Student learning, CMS and publishing workflow

The audit confirmed existing server-side class/subject and published-content isolation and existing CMS lifecycle endpoints. Two learner/CMS rendering gaps were corrected while tracing the content flow: (1) the student lesson filtered `SUMMARY`/`RECAP` blocks before assessment but never rendered them later, hiding authored recaps; it now renders all active recap blocks after topic practice. (2) the Admin preview now keeps all non-recap blocks in authored order before the inline assessment, shows only active approved MCQs in that check (other approved question types belong to topic practice), displays the next topic-practice step, and then renders recap blocks. Both frontend builds passed in branch CI.

No new lesson, chapter, source citation, or question was fabricated to inflate coverage. Browser-level checks using real persisted content are still needed, and the admin/student block-rendering components remain separately implemented, so future edits can drift. The CMS should continue using its existing canonical lesson/question models and authorization rules.

## 4. Ingestion, chapter extraction, recovery and provenance

Existing scripts provide the intended base:
- `scripts/textbook_registry.py`: checks and verifies registry artifacts, downloads whole books, records SHA-256/availability, and uses the configured class/language-specific GHCR strategy.
- `scripts/extract_textbook_text.py`: resumes page checkpoints keyed by source hash and extractor version and records per-page/source metadata.
- `scripts/scert_extract_review.py`: creates `DRAFT_EXTRACTION_ONLY` page-addressable bundles, identifies low/no-text pages and optionally invokes OCR for review.
- `scripts/review_generated_question_bundle.py` and `scripts/import_reviewed_content.py`: preserve human-review and draft-only import boundaries.

The textbook workflow documents up to five retry rounds, safe parallel whole-book downloads, existing-artifact checks and separate availability reports. It must continue to distinguish “unavailable”, “partial/missing chapter mapping”, “downloaded”, “extracted”, “reviewed” and “published”; an image/tag name alone is not evidence that its payload is valid.

The current change does not run downloads or republish class packages. Live SCERT/NCERT retrieval, the actual contents of GHCR images, retention settings and the integrity of all class/medium artifacts remain to be checked by a credentialed CI/cache run. Do not silently fall back from a missing whole book to chapter-only files where the project's full-book rule prohibits it.

## 5. GHCR package layout and update commands

Retain the existing class-specific publisher design in `.github/workflows/textbook-cache.yml` and `scripts/textbook_registry.py`, using the prefix `ghcr.io/<owner>/quantaedge-textbooks` and class/language options rather than reviving shared one-image-per-language packaging.

The workflow can be run from **Actions → QuantaEdge Textbook Cache → Run workflow** with `language`, `class_no`, `download_artifacts`, `refresh`, `download_workers`, `push_batch_size` and `retry_rounds` set deliberately. Use the existing registry script's `publish --class-wise` workflow rather than manually overwriting package tags. Verify the emitted per-class/medium availability report and checksums. This task did not publish or verify registry images.

## 6. Android changes and validation

- Canonical Kotlin/Compose client and Gradle setup were inspected.
- Artifact naming is now aligned to `quantaedge-android-apk` in both relevant workflow paths and the install helper.
- The latest main's dedicated Android job remains part of the branch integration; a branch-only validation workflow was added so tests can run without publishing branch-built container images.
- The existing installer still checks that a successful build exists for the exact requested branch/commit, verifies that a plausible APK exists before replacing the local copy, and asks before ADB installation.
- Branch CI run [38341468420](https://github.com/Manav326/QuantaEdge/actions/runs/38341468420) completed `testDebugUnitTest assembleDebug` and uploaded `quantaedge-android-apk` (14,570,459 bytes; artifact ID `11786094554`). This is an installable debug APK, not a signed Play Store release; Gradle may restore unit-test execution from cache.

Not performed: physical-device installation, APK signing/Play Store release validation, or real student login/lesson/progress/question submission against a live backend. A passing CI compile is not a substitute for those device and backend checks.

## 7. Database, API and configuration

- No Flyway migration was added. The existing V52 asset-size constraint already permits 500 MiB assets.
- No new HTTP endpoint or second student/curriculum/progress model was introduced.
- The source-ingestion size config is `QUANTAEDGE_SOURCE_MAX_PDF_BYTES` (or JVM property `quantaedge.source-ingestion.max-pdf-bytes`); omitted means 500 MiB.
- The generator uses existing environment contracts `OPENAI_API_KEY`, `OPENAI_API_BASE_URL` and `QUANTAEDGE_QUESTION_MODEL`.
- Existing authentication, student enrollment/subject authorization, staff content permissions, audit/history and teaching-assignment contracts remain authoritative. Live class workflows should continue to use the current assignment and authorization model.

## 8. Test and verification record

| Check | Status | Notes |
|---|---|---|
| Repository/branch access | Verified | Remote working branch exists and has commits |
| Existing source contracts reviewed | Verified by inspection | CMS, published-lesson filtering, PDF/cache pipeline, AI draft workflow, Android workflow |
| Source PDF regression tests | Passed in branch CI | `SourcePdfDownloadServiceTest`: 7 tests, zero failures/errors |
| AI generation recovery tests | Passed in branch CI | Transient retry, no-retry-on-401, and resume-review-gate tests included in Python suite |
| API Maven verification | Passed | `mvn -B -ntp verify`: 195 tests, zero failures/errors; `BUILD SUCCESS` |
| Python test suite | Passed | 161 tests in 0.504 seconds on run 38341468420; passed again in newer branch validation runs |
| Student web build | Passed | `npm run build` in branch CI |
| Admin Content Studio build | Passed | `npm run build` after preview alignment changes |
| Android setup/build/unit tests | Passed in branch CI | Gradle `testDebugUnitTest assembleDebug` succeeded and a debug APK was uploaded; current-main SDK package configuration is being revalidated in run 38341744121/38341748078 |
| Baseline main workflow | Failed in Android setup | Run [38340721286](https://github.com/Manav326/QuantaEdge/actions/runs/38340721286) requested the removed `tools` SDK package. Latest main's explicit current-package correction is now integrated into the branch. |
| Real model generation | Not run | No provider credentials supplied/verified |
| GHCR read/publish | Not run | No live class/language image validation performed |
| Physical-device test | Not run | No device bridge available |
| Student/CMS browser E2E | Not run | Needs runtime with persisted data and authenticated roles |

The environment could use the connected GitHub API to inspect and write repository files, but local `git clone` failed because the runtime could not resolve `github.com`. A branch-scoped validation workflow (`.github/workflows/agent-4-validation.yml`) runs checks without publishing Docker images. Run [38341468420](https://github.com/Manav326/QuantaEdge/actions/runs/38341468420) passed all five jobs: 161 Python tests, 195 API tests with zero failures/errors, student web build, Admin Content Studio build, and Android SDK setup/Gradle build with a debug APK upload. The earlier main run [38340721286](https://github.com/Manav326/QuantaEdge/actions/runs/38340721286) failed at the obsolete `tools` SDK request; latest main's explicit current package list is integrated and being revalidated by runs 38341744121/38341748078. Live AI-provider requests, GHCR registry checks, browser E2E and physical-device use remain unverified.

## 9. Remaining gaps and blockers

1. **Curriculum and editorial completeness:** verify all six relevant official book editions and chapter/page mappings; compare each of the 102 chapter rows against authoritative SCERT/Bihar Board scope; author/review substantive teaching blocks, examples, visual content and answers. Do not count shells or target quantities as completed content.
2. **CMS preview parity:** authored sequence, inline-MCQ separation and recap visibility were aligned and both frontends build; still test real interactions and desktop/phone rendering against persisted content. Admin/student rich-block renderers remain separate and may drift.
3. **PDF operational sizing:** 500 MiB is the configured maximum, not a universal memory guarantee. Confirm heap, container, proxy, multipart and database limits. Add production telemetry around failed/oversized/truncated ingestion and test with a genuinely large full-book PDF.
4. **AI resume robustness:** the new resume validator validates source identity, chapter/model/curriculum, review guard and candidate counts, but does not replace full subject-matter re-review. Validate against interrupted real runs and malformed provider responses using the CI suite before enabling generation.
5. **Source availability and registry retention:** run the textbook workflow with registry access; inspect each class/medium image manifest, checksum, gaps report and retention configuration. Confirm that failures of individual books remain visible and do not masquerade as complete coverage.
6. **Android release/device validation:** run CI on this branch through the approved integration path, then exercise authentication, chapter navigation, lesson rendering, answer submission/progress, error/retry and protected resources on a physical device. Configure a real HTTPS API endpoint and external release signing outside source control before distributing a release build.
7. **Production readiness:** README-identified blockers still apply, including production OTP/SMS and guardian verification, legal/privacy contacts, monitoring, version history and a dedicated structured block/question editor.

## 10. Dependencies on other agents

Agents 1–3 own HRMS, payroll and academic workforce management. Agent 4 did not add employee records or a second teacher-assignment model. Continue to use shared identity/authentication, canonical class/subject identifiers and the current academic assignment authorization contract. Live-class features must reject entry when the authenticated teacher/student is not authorized under the existing assignment and class rules.

## 11. Branch, commits and remote status

- **Branch:** `agent/learning-content-ai-android`
- **Start baseline:** `0d71946b3854a68c894001193a5174a41fa9a45b`
- **Latest main merged into branch:** `e56620a3c59276a23dca5c2f7bbedbcf020f7da1`
- **Merge/integration commit:** `09b10b71a2b35574379c497ea7cb3079f8e10c65`
- **AI retry/resume implementation commit:** `94f971b526255c8e30716d341c273867721cf2f7`
- **AI regression test commit:** `1a25e1c52af380ea16e12dd1241c1dec2d0f8a05`
- **Android installer/main-sync commit:** `3c1142e93a43742140c635f1d7c85a038219643d`
- **Android CI SDK package fix:** `116e8651c0f31e4ab1b9ffa8f80c5517fb1b1916`
- **Branch validation workflow:** `.github/workflows/agent-4-validation.yml`
- **Passing full branch CI run:** [38341468420](https://github.com/Manav326/QuantaEdge/actions/runs/38341468420), source/UI commit `8131893ce4dc8a6f26f8ece3a6d5adece70b9ae6`
- **New runs for explicit Android SDK package list:** [38341744121](https://github.com/Manav326/QuantaEdge/actions/runs/38341744121) and [38341748078](https://github.com/Manav326/QuantaEdge/actions/runs/38341748078); confirm final status before treating package-list-only changes as verified.
- **Remote status:** changes have been committed to the GitHub working branch. No PR was opened and `main` was not modified by Agent 4.

The branch history is the source of truth for the exact final handoff-report commit SHA; it is committed after the integration commit listed above.
