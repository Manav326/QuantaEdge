# QuantaEdge Live Lectures, Native Messaging, and Offline Video — Locked Implementation Plan

Status: **Scope locked; implementation not yet complete.** This document is the implementation contract. Start with a source-level audit and reuse the existing live-class, recorded-class, private-media, identity, permission, audit, and Android playback foundations. Do not create a parallel subsystem or patch symptoms.

## Locked product decisions

1. **Cost is the top priority.** Prefer existing infrastructure and free tiers where they genuinely satisfy requirements. Do not introduce paid providers, extra brokers, or microservices without evidence that they are needed.
2. **Large live lectures:** use YouTube Live as the initial low-cost broadcast delivery path for 100–500 concurrent viewers, embedded in QuantaEdge where supported. QuantaEdge's own classroom chat appears alongside it.
3. **Small interactive sessions:** Google Meet may be used for sessions that fit the account's actual participant/duration limits. Launch the supported Meet URL from QuantaEdge; do not promise a fully embedded Meet UI. Verify account capability and access controls. A hidden Join button is not sufficient security if a meeting URL leaks.
4. **Own the replay:** after a lecture, a recording obtained from an authorized source workflow is ingested into QuantaEdge-controlled private media storage and played in the QuantaEdge player. Do not use unauthorized YouTube downloaders or treat YouTube's archive as our retained source copy.
5. **Cost-first recording workflow:** audit whether the teacher broadcast workflow can record the original source locally (for example, OBS records while broadcasting to YouTube) and upload that authorized recording to the existing private-media service. Provide resumable/retryable upload and a visible failure state. If the source workflow cannot reliably do this, compare supported authorized alternatives before choosing paid streaming storage.
6. **Downloads/offline:** provide an authorized Download for offline learning option. Web can download a permitted original file; Android stores permitted downloads in app-private storage and supports offline playback, progress, cancellation, deletion and storage management. This is not absolute DRM. Do not copy media out of YouTube; downloads are from QuantaEdge's authorized stored source.
7. **Resources:** support multiple resource items per chapter and/or canonical lesson/micro-topic, including recorded videos, external live/video links, PDFs, worksheets, slides, audio and other supported resource types. Do not add a single video field to a lesson.
8. **Scheduling:** scheduled sessions become visible to eligible students exactly within the 24-hour pre-start window. Joining uses a separate server-authoritative join window and authorization. Do not expose private meeting URLs in public catalog payloads.
9. **Messaging:** build native QuantaEdge public classroom chat and private student-to-teacher conversations, independent of YouTube/Meet. Use the existing Spring Boot API/PostgreSQL and existing Redis only if the audit demonstrates a need; WebSocket delivery must have durable history and reconnect recovery.
10. **Retention:** recordings remain until an authorized archival/removal action. No automatic deletion based on age. Preserve audit history for archive/removal and handle storage-cost alerts. Any archive tier must be explicitly tested for restore time and cost.
11. **Cross-client parity:** admin, student web and Android use consistent contracts and authorization. Keep existing lesson/subject enrollment and staff permission rules.
12. **Repository discipline:** code, migrations, tests and implementation docs are committed directly to GitHub `main` as requested; no feature branch or PR unless the user explicitly changes this. Runtime videos, recordings, message data, generated pipeline data and secrets must not be committed. Pipeline-generated data remains confined to the designated `data/` directory. Require relevant GitHub Actions checks green before declaring completion or deploying.

## Existing foundations to audit before changing

- `services/api/src/main/resources/db/migration/V43__live_and_recorded_classes.sql`
- `services/api/src/main/java/com/quantaedge/api/LearningClassController.java`
- `PrivateMediaStorageService`, `recorded_class`, `learning_class_session`, attendance/progress persistence and authorization/audit services
- `apps/web/app/student/classes/ClassesClient.tsx`, `apps/admin/app/classes/page.tsx`
- Android live/recorded classes, playback, download/storage, API/authentication, and navigation implementations
- Existing WebSocket/Redis facilities, upload limits, media volume, backup/restore and deployment configuration
- Existing CI workflows and current `main` head before each write

The current architecture document records media under `APP_MEDIA_STORAGE_DIR` (default `/var/lib/quantaedge/media`) on the persistent `quantaedge_media` volume. Verify current code and deployment rather than assuming the documented state is fully deployed. Do not store media bytes in PostgreSQL unless a pre-existing requirement specifically needs it; keep media on private persistent storage and metadata in PostgreSQL.

## Work breakdown and acceptance gates

### Task 1 — Baseline audit and contracts
- Trace the current session-to-recording flow and every input/output from admin, API, DB, storage, web and Android.
- Identify existing endpoints, migrations, auth checks, provider metadata and playback/download behavior.
- Document exact gaps and reuse plan; avoid duplicate tables/endpoints and do not edit released migrations.
- Acceptance: an audit matrix names current implementation, required behavior, exact code/schema locations, migration/API compatibility, and tests to add.

### Task 2 — Multi-resource lesson/chapter catalogue
- Define provider-neutral resource metadata, type, stable ID, chapter/lesson linkage, order, language, status, storage/provider reference, file metadata/checksum, download policy, publication, creator and audit fields.
- Reconcile the model with existing lesson/topic/chapter IDs and database keys before migration.
- Support multiple videos and other resources per lesson/chapter, drafts, publish/unpublish, archive and replacement without losing history.
- Acceptance: multiple resources can be attached and ordered; invalid scope and unauthorized writes are rejected; existing records remain readable.

### Task 3 — Cost-first live scheduling and authorized recording ingestion
- Implement/verify the 24-hour student visibility rule server-side; preserve independent join window, enrollment checks and cancelled-state behavior.
- Add/verify YouTube Live broadcast references and a supported Google Meet link workflow for eligible small interactive sessions; secrets and private join URLs remain server-side.
- Design source recording + upload so the original is captured through an authorized workflow (preferred: simultaneous teacher-side recording while broadcasting), then uploaded to private QuantaEdge storage.
- Upload must support progress, interruption/retry, idempotency, checksum, type/size validation, duration/processing state and clear admin failures.
- Link recording to the original session and relevant lesson/chapter; only publish when storage validation succeeds.
- Acceptance: no unauthorized join, no public raw storage URL, no duplicate recording after retries, no silent missing recording, and a verified end-to-end scheduled-to-replay flow.

### Task 4 — QuantaEdge-owned video playback and downloads
- Use the existing private-media storage abstraction/volume where sound; do not buy a managed service before a cost/performance comparison shows need.
- Implement/verify the QuantaEdge player on web and Android, adaptive/range playback where supported, seek/resume, duration/progress and low-bandwidth behavior.
- Add download policy and server authorization. Web download is available only when permitted. Android supports app-private downloads, offline playback, resumable download, cancellation, deletion and storage visibility.
- Protect against path traversal, unrestricted permanent URLs, unauthorized download and duplicate files; validate checksums and clean up partial uploads/downloads safely.
- Acceptance: authorized student streams/downloads; unauthorized student is denied; downloaded Android lesson plays offline after process/device restart; removing a download frees storage without deleting the retained server original.

### Task 5 — Native messaging and classroom moderation
- Build public class conversations and private student-to-teacher conversations in QuantaEdge; do not use YouTube/Meet chat as the system of record.
- Durable messages, conversation membership, stable ordering, pagination, unread/read state, delivery acknowledgement, retry deduplication, reconnect catch-up and notification hooks.
- WebSocket real-time delivery backed by the existing API/auth system and PostgreSQL. Add Redis/broker only if required by measured multi-instance needs.
- Moderation: teacher/moderator delete/hide, mute, report, rate limits/slow mode, pinned announcements/questions and audit trail.
- Student-to-student private messaging is disabled by default. Parent messaging is out of scope for the first release unless separately approved.
- Acceptance: tests prove private/public isolation, class membership enforcement, no impersonation, reconnect recovery, duplicate-submit idempotency, moderation and audit behavior.

### Task 6 — Admin, web and Android parity
- Admin can schedule/manage sessions, attach/reorder/publish resources, see recording/upload/processing failures, set download permission, and moderate classroom chat.
- Student web and Android show upcoming sessions, embedded YouTube live player, Meet handoff where applicable, native chat, recorded resources, player progress and download/offline states.
- Use shared API contracts and consistent empty/loading/error states; do not put provider secrets or unrestricted media URLs in clients.
- Acceptance: supported workflows pass on both clients and UI uses existing QuantaEdge branding/navigation conventions.

### Task 7 — Security, load, cost and operational validation
- Automated tests: 24-hour boundary, join windows, enrollment, cancellation, public/private chat isolation, resource scope, upload validation, storage authorization, download authorization, retry/idempotency, archive/removal audit and Android offline playback.
- Load-test expected 100–500 concurrent broadcast viewers via provider/representative playback testing; test native chat fanout separately with expected concurrency. Do not confuse broadcast viewers with 500 two-way WebRTC participants.
- Measure startup time, buffering, playback failures, upload/processing time, chat delivery latency, storage growth and monthly cost per lecture.
- Verify backups/restores for PostgreSQL metadata and private media volume; confirm no automatic expiry or age-based deletion.
- Acceptance: relevant API tests, web/admin builds, Android CI/build and container/CI jobs are green. Test results and exact commit/run IDs are recorded. No production migration/deployment is claimed unless actually executed and verified.

## Definition of done

- All seven tasks are completed and linked to this plan.
- Schedule -> 24-hour visibility -> authorized live access -> native chat -> source recording upload -> validation -> resource publication -> online playback -> authorized download/offline playback works end to end.
- Multiple resource types and multiple recordings can be linked to the same lesson/chapter.
- Recording retention is permanent by default with authorized, auditable archive/removal only.
- Web and Android parity and security tests pass; 100–500 broadcast behavior and chat load are measured.
- Changes are on `main`, with required CI checks green; no media, secrets, or runtime data are committed.


---

## Implementation journal — 2026-10-16 (checkpoint 1)

This journal is intentionally part of the repository contract. Update it in the same commit as meaningful implementation milestones so a fresh session can resume without relying on chat history.

### Repository baseline

- Branch: `main`
- Baseline commit when this checkpoint began: `c02c8c7d19511d3ca1e6cd0a5f2f1bb7a2d387a8`
- Plan file: `docs/product/live-lectures-messaging-offline-video-implementation-plan.md`
- Work tracking: epic #65; tasks #58–#64.
- Baseline CI run for the plan commit: [run 38931943122](https://github.com/Manav326/QuantaEdge/actions/runs/38931943122). At the time checked it was still `in_progress`; do not treat it as green until rechecked.

### Audit findings confirmed from current `main`

| Area | Existing contract | Confirmed gap / risk | Implementation consequence |
|---|---|---|---|
| Live session schema | V43 `learning_class_session` supports `CUSTOM`, `GOOGLE_MEET`, `ZOOM`, `MICROSOFT_TEAMS`, `OTHER`; has chapter and subject FKs, times/status, private join URL. | No YouTube Live provider value or explicit public broadcast reference contract in V43. | Add a forward-only migration; do not edit V43. Model public broadcast reference separately from private meeting URL; never expose private join URL in listing responses. |
| Schedule visibility | `GET /api/v1/learning/live-classes` returns sessions whose end is within the last 60 days, with ordering/pagination and enrollment, approved teaching-assignment, employee-status and chapter-publication checks. | Query does not restrict scheduled sessions to the 24 hours before `starts_at`; it may expose future sessions much earlier. Its total-count query must use the same visibility predicate. | Introduce one consistent server-side visibility rule in item and count queries; add tests for just before/at/after the 24-hour boundary and already-started/live/completed sessions. |
| Join authorization | `POST /api/v1/learning/live-classes/{sessionId}/join` checks student active status, class/subject enrollment, approved teacher assignment, teacher employment state, chapter publication, session status and private URL. It rejects starts more than 15 minutes away and ended sessions. | Existing 15-minute early join window is a separate rule from 24-hour listing visibility and must not be accidentally widened. Confirm provider-specific behavior for YouTube broadcast versus Meet. | Keep listing visibility and join eligibility independent; never rely on UI hiding alone. Test exact join boundaries and cancellation. |
| Recording schema | V43 `recorded_class` supports one MP4/WebM/QuickTime recording row, max 524,288,000 bytes, SHA-256, draft/published/archived status, optional chapter and session links. | It is a recording-specific table, not a generic multi-resource catalogue; only three video MIME types and a hard 500 MiB limit. Lesson/micro-topic linkage and download policy are not represented here. | Add a provider-neutral resource model after verifying canonical lesson/topic IDs and existing resource tables. Preserve old recordings through compatible reads/migration/backfill; do not create a parallel video-only lesson field. Revisit upload size only with a storage/hosting cost and abuse-control rationale. |
| Private media | `PrivateMediaStorageService` writes UUID-named files beneath `app.media.storage-dir` (default `/var/lib/quantaedge/media`), hashes content, validates basic MP4/WebM/MOV signatures, uses temporary `.part` files and atomic move where supported. It rejects files over 500 MiB. | This service accepts a multipart upload as one request; resumable upload, explicit upload idempotency, processing state and retry reconciliation are not established by this class. The default path alone does not prove production volume/backup configuration. | Reuse the storage abstraction; audit deployment/volume/backup next. Design upload session/chunk/checksum/idempotency before adding client-side retries. Never store media bytes in PostgreSQL or commit media to Git. |
| Student replay authorization | `readableRecording` only returns a `PUBLISHED` recording when student is active, belongs to the matching class, and has active enrollment for the subject; chapter must be active/published. Streaming supports byte ranges and uses no-store/nosniff/noindex headers. Existing test covers out-of-scope denial and malformed range rejection. | No evidence yet of a permitted-download flag or distinct download authorization/attachment behavior. Session-to-recording relation is optional. | Extend authorization contract before download endpoints/UI. Add tests for stream and download authorization, private URL leakage, checksum/partial transfer behavior. |
| Web | Student `ClassesClient.tsx` uses API-backed class listing, authorized join endpoint and recording playback/progress. Admin has a class-management page. | Need a complete pass through resource presentation, provider rendering, recording upload/status errors, and chat UX before modifying UI. | Build on existing pages and shared API contracts rather than a parallel route/system. |
| Android | Existing Android implementation includes API-backed live/recorded class catalogue, authenticated in-app Media3 replay, session-cookie refresh, and playback progress. Existing docs confirm protected offline textbook assets, but that does not establish offline video downloads. | Offline video storage/download/resume/delete is not yet confirmed; public YouTube embedding and native chat parity remain to be audited. | Reuse the existing player/auth/navigation architecture; keep video bytes in app-private storage and verify offline playback after process/device restart. |
| Messaging | Repository searches for `@MessageMapping` and `WebSocketMessageBrokerConfigurer` returned no matches; no direct evidence yet of an existing Spring WebSocket chat implementation. | Native classroom/private messaging has not been located in the inspected code. | Search the full repository for alternate WebSocket/SSE/notification infrastructure before deciding transport. Start with PostgreSQL durable message history and authorization; add broker only if actual deployment scale requires it. |

### Current audit test evidence

- `services/api/src/test/java/com/quantaedge/api/LearningClassControllerTest.java` exists and covers: early join rejection (15-minute gate), insecure URL rejection, class-management permission denial, attendance clock closure, unverified Meet link redaction, out-of-scope recording denial, and malformed byte-range rejection.
- These are source-level test cases, not evidence that tests were executed during this checkpoint.
- `V43__live_and_recorded_classes.sql` is already a migration. Never change it; introduce a new forward migration and keep schema changes compatible with existing data.
- The baseline plan CI run was still in progress at last check. Recheck current `main` and CI before the next code write and after each implementation commit.

### Next actions (resume here)

1. Finish the contract audit before schema changes:
   - inspect all V43 controller methods for upload, publish/archive, student list/stream/progress;
   - inspect all migrations and existing content-resource/lesson/topic schemas to find the canonical FK targets;
   - inspect admin/student web API contracts and components end-to-end;
   - inspect Android player/data/repository implementation and search for any existing video download manager;
   - inspect Docker/production deployment and backup configuration for the private media volume;
   - search repository for WebSocket, SSE, notifications and any existing message tables.
2. Add the detailed audit matrix and exact tests to issue #58; close it only when every contract is traced.
3. Implement in small, direct-to-`main` commits in dependency order: multi-resource schema/API; 24-hour visibility/provider contract; reliable authorized recording ingestion; download policy and web/Android offline video; durable native messaging; admin/web/Android parity; security/load/cost/backup validation.
4. Update this journal after every material change with commit SHA, changed files, migration IDs, test commands/results, workflow run links, unresolved risks and the exact next step. Never claim green CI or completed E2E without evidence.


## Implementation journal — 2026-10-16 (checkpoint 2: first code change)

### Delivered to `main`

- Commit `dcd41f5f41fd687aff971451fd779864571ed3c7`: `LearningClassController.studentLiveClasses` now filters future `SCHEDULED` sessions to `starts_at <= now() + interval '24 hours'`.
- The same predicate is applied to the catalogue item query and the total-count query, so pagination metadata cannot count hidden far-future scheduled sessions.
- Commit `92f950c0c1f625a9565e9624e038b4eb7e5e4003`: added `scheduledClassesUseTheSameTwentyFourHourVisibilityRuleForItemsAndCount` to `LearningClassControllerTest`. It verifies the rule is present in both SQL paths.
- This change deliberately leaves the separate 15-minute early join authorization unchanged. Existing join endpoint checks remain authoritative.
- CI for the latest commit: [run 38933086666](https://github.com/Manav326/QuantaEdge/actions/runs/38933086666), status was `pending` when checked. The code and test have been committed, but test execution and CI success are **not yet confirmed**.

### Additional schema discovery

- V3 defines canonical `lesson` and `lesson_block`; lessons are chapter-scoped and blocks are ordered JSONB with a block type. V30 expands lesson block types to include `VIDEO` and `AUDIO`.
- Lesson blocks are part of lesson content rendering, but they are not a provider-neutral resource catalogue with per-resource lifecycle, external provider references, checksums, download permission and archive audit. Do not overload the JSONB lesson block contract to solve the full catalogue requirement.
- V79 already handles managed Google Meet lifecycle through provider operation state and verified meeting creation. Reuse `GoogleMeetCalendarService`; do not replace or bypass its retry/error-state model.
- Android `RecordedPlayerScreen.kt` currently points Media3 to the authenticated `/api/v1/learning/recorded-classes/{id}/stream` endpoint and saves progress. `LearningClassesScreen.kt` exposes playback but no confirmed video download action. The existing `ProtectedResourceDownloadWorker` is for protected learning resources and must be inspected before reuse; do not assume it handles video.
- No existing WebSocket/SSE/message implementation has been identified yet by repository searches. Continue a broader path/code search before concluding it does not exist.

### Remaining validation for the first code change

- Wait for and inspect CI run `38933086666`; if it fails, fix the root cause and record the follow-up commit/run.
- The new unit test checks SQL contract shape, not a real PostgreSQL time-boundary behavior. Add an integration-level boundary test if the existing test harness supports PostgreSQL migrations.
- Confirm whether all status transitions that remain visible are intentional; the current predicate only restricts rows whose status is `SCHEDULED`, preserving existing live/history visibility.

### Exact resume point

1. Check run `38933086666` and current `main` head.
2. Continue audit: identify all existing content-resource tables/APIs and lesson/micro-topic keys; inspect complete admin upload/publish/archive endpoints; trace Android resource download worker and storage; inspect Compose/deployment/backup media-volume config; search the repository for any alternative messaging transport.
3. Then implement the multi-resource model only after those FK and API contracts are reconciled. Keep this journal current at every milestone.


## Implementation journal — 2026-10-16 (checkpoint 3: source audit and CI confirmation)

### Audit artifacts and repository state

- Added `docs/product/live-lectures-messaging-offline-video-audit.md` in commit `da9df4339a3c5eb51bb8d94c2181eb312d281ac9`.
- The audit traces current API endpoints, migration/schema contracts, production Compose volume, admin/student web clients, Android playback/download worker, resource models, and messaging search results.
- At audit start, `main` was `19ec0e7a76a3b1f740bf0cbefa1d4057c5da457e`. This is three content-pipeline commits ahead of `df5e8d5cefc0b88e136a4516cf119cc24504a79d`; the three commits only changed files under `data/`. Pipeline data stays confined to `data/` as required.
- Confirmed CI run [#2862](https://github.com/Manav326/QuantaEdge/actions/runs/38933136844) on `df5e8d5cefc0b88e136a4516cf119cc24504a79d` completed successfully across all eight jobs: Android APK, affected-component detection, API verification, content safeguards, admin image, People Operations image, web image, and API image. The successful commit is an ancestor of current `main`; do not infer that later data commits constitute a separate feature-code build.
- This is source-level auditing plus CI evidence; no production deployment, PostgreSQL integration boundary test, load test, or backup restore drill has been run by this checkpoint.

### Confirmed root gaps

1. YouTube Live is not a first-class provider in V43's provider constraint. Existing live-session creation and join assume a private `private_join_url`; the broadcast player/reference must be modeled separately, while preserving the existing Google Meet lifecycle in V79.
2. `recorded_class` supports private local media, but only one multipart upload request (500 MiB max), no upload idempotency/resume state, no download-permission field and no lesson/microtopic FK. Existing byte-range stream authorization is a sound base.
3. Production Compose mounts the named `quantaedge_media` volume at `/var/lib/quantaedge/media`; this proves persistence across container recreation, not backup readiness. No automated media backup/restore job or script was found in the repository paths searched. Confirm the real deployment and restore process before release.
4. The generic `content_asset` model is used by lesson blocks but only contains a URL/status/provenance and does not provide the full access-controlled multi-resource assignment/lifecycle/download contract. `learning_document` is chapter/subject PDF-oriented and returns authorized rendered pages; it is not a generic resource catalogue.
5. Android `RecordedPlayerScreen` uses authenticated online Media3 playback; `ProtectedResourceDownloadWorker` downloads encrypted rendered page images, not video. Video offline support must be implemented as a distinct media transfer/storage path that reuses account isolation and safe worker patterns without misusing the page endpoint.
6. Searches for `@EnableWebSocket`, `WebSocketMessageBrokerConfigurer`, `SimpMessagingTemplate`, `RedisMessageListenerContainer`, `SseEmitter`, and likely conversation/message table names found no native chat transport or durable message model in inspected source. Redis is already deployed, but no broker should be added until measured multi-instance requirements justify it.
7. Recording upload already removes the file if the DB insert/audit transaction throws. It does not protect against a client retry after a lost success response, which can create a duplicate recording. A stable request identity is required; SHA-256 alone cannot be the idempotency key because the same bytes may legitimately be reused.

### Exact next step

Reconcile canonical subject/chapter/lesson/topic identifiers and source-specific lifecycle for `learning_document`, `content_asset`, and `recorded_class`; then design a forward-only migration for provider-neutral multi-resource assignments and YouTube broadcast metadata. Do not modify released migrations or add a video-only duplicate catalogue. Implement upload idempotency and authorized offline download policy before the corresponding retry/download UI. Add issue #58 audit findings and update this journal after each milestone.


### Structured lesson identifier reconciliation (follow-up audit)

- The current structured lesson runtime loads approved JSON artifacts from packaged `data/structured-lessons`; topic/microtopic definitions are not rows in the legacy `lesson` table.
- Stable structured identity is `artifact_id` (string), with `topic.topic_id` as a stable string in the JSON. V106/V107 learner-response rows key on `artifact_id` plus exact artifact SHA-256.
- Resource links to legacy lessons may use `lesson.id`; links to structured lessons must use the stable `artifact_id`, validate through `StructuredLessonArtifactService.requirePublished`, and verify class/subject/chapter scope. Do not invent numeric topic FKs or publish a resource against a draft artifact.
- This resolves the topic-key ambiguity. Next implementation step remains the forward-only resource model that can attach at chapter scope, legacy lesson scope, or structured artifact scope without changing the existing structured artifact/version contract.


### Implementation journal — 2026-10-16 (checkpoint 4: YouTube Live broadcast path)

- Added forward-only migration V108 to introduce `YOUTUBE_LIVE` as a distinct provider with a validated 11-character YouTube video ID and a database invariant that public broadcast sessions never carry a private meeting URL. Existing providers and Google Meet's managed lifecycle remain unchanged.
- Extended the live-class API to accept only HTTPS YouTube watch/live/embed/short URLs on approved YouTube hosts, extract the video ID, store it separately, and return the ID only in access-controlled class catalogue/join payloads. The 15-minute join gate and 24-hour schedule visibility remain intact; YouTube attendance is recorded through QuantaEdge.
- Updated the admin class form with a distinct public broadcast URL field and the student web client to render a privacy-enhanced YouTube embed after the server authorizes joining. This is a broadcast embed, not a private YouTube download or a replacement for uploading an authorized recording into private storage.
- Added a controller regression test rejecting a broadcast URL on an untrusted host before database access.
- Files changed in this checkpoint: V108 migration, `LearningClassController.java`, `LearningClassControllerTest.java`, admin classes page, student classes client, and this journal. No generated pipeline data or media files were added.
- **Validation status:** this commit has not yet passed CI. Verify migration/API compilation and both frontend builds; fix failures before claiming the milestone green.
- Next: validate V108 against the migration test harness, then implement recording upload idempotency and provider-neutral resource assignments before Android offline video downloads. Native messaging remains a separate durable PostgreSQL-backed workstream.


### YouTube provider contract correction

- Tightened the V108 database check to require `youtube_video_id IS NOT NULL` explicitly. PostgreSQL CHECK constraints accept NULL expressions, so the explicit predicate is necessary to prevent a YOUTUBE_LIVE row without a video ID.
- Kept the teacher's private `meeting-link` endpoint private-link-only. YouTube broadcasts use the separate student broadcast/player field and must not be returned as a meeting URL by that endpoint.


### CI-driven correction — student broadcast render guard

- The first frontend build reported TypeScript errors because the newly added broadcast iframe read `activeJoined` without guarding the optional result of the session lookup. Changed the condition to require `activeJoined` before reading its provider/title. This is a genuine nullability issue, not a suppression or cast. Re-run the web build and the full workflow; do not mark the feature green until the current `main` SHA passes.


### CI-driven root-cause correction — migration and card action

- Run #2878 exposed two independent defects: V108 had a malformed SQL regular-expression literal and stray duplicated SQL appended after the metadata upsert; the web build also found the card action dereferenced `activeJoined` when the card's session variable was the correct scoped value.
- Rewrote V108 as a complete clean forward migration. The constraint now validates ID length and allowed characters without a dollar-anchored expression, requires non-null ID, and enforces no private meeting URL for YouTube broadcasts.
- Corrected the class-card action to use its own `session.provider`, not optional `activeJoined.provider`. This addresses the TypeScript failure at its source rather than masking the type checker.
- Re-run full API migration tests and web build on the resulting commit; do not proceed on the assumption these fixes pass.


### Schema invariant check — YouTube versus private join URLs

- The original V43 schema requires `private_join_url NOT NULL`. V108 now explicitly drops that column-level requirement because YouTube broadcasts do not have a private meeting URL, while its replacement provider-specific CHECK requires a non-null private URL for every non-YouTube provider. This preserves the prior invariant for Meet/custom/other sessions instead of accidentally making their private URL optional.
- This correction is part of the unvalidated V108 migration before the feature is released; do not rewrite a migration after it has been deployed. Re-run migration/API tests before proceeding.


### Implementation journal — 2026-10-17 (checkpoint 5: idempotent recording upload)

- Added a forward-only V109 migration for nullable idempotency keys and SHA-256 request fingerprints on recorded recordings. Existing rows and older clients remain compatible; a partial unique index scopes keys to the uploading staff account.
- Recording uploads accept an `Idempotency-Key` header. When a retry uses the same key and identical normalized metadata plus file digest, the API returns the existing recording instead of creating another row. Reusing a key for different content returns a conflict. Concurrent identical requests use a database uniqueness constraint and conflict-safe insert rather than relying only on a race-prone preflight query.
- The API continues to stream media to private disk storage; the request fingerprint uses the computed file SHA-256 and metadata rather than loading video bytes into memory. Duplicate temporary media is deleted before returning the existing row.
- The admin upload form now sends a stable UUID key and retains it after a failed request so retrying the same form is idempotent; successful upload or choosing a different file resets the key.
- **Validation status:** awaiting the full GitHub Actions run on the resulting commit. Do not treat this milestone as complete until API compilation, migration verification, admin build, and the full workflow pass.
- Next: build the provider-neutral resource catalogue and assignment schema, then implement Android app-private offline video downloads and durable PostgreSQL-backed messaging. Preserve main-only commits and do not add generated content outside `data/`.


### Implementation journal — 2026-10-17 (checkpoint 6: resource-management status audit)

- Rechecked source contracts and main at 36b38fa34c1b602e4f7238d43300a48023b3e011 immediately before this documentation update. The feature code checkpoint 5c4dc9c69bf0770517c7e2c33dc17e0f412b4eea is validated by [Build and Publish run #3024](https://github.com/Manav326/QuantaEdge/actions/runs/39000812717): all eight jobs passed (API verification, Android tests/build, admin/web/API/People Operations image builds and content safeguards). The three later commits changed CI workflow/route-inventory validation, the translation tracker and four files under data/; no application feature source changed after the tested feature checkpoint. Run #3024 does not verify the later CI workflow/script changes.
- This supersedes the earlier point-in-time statement in checkpoint 5 that CI was still pending. V109 idempotency code is CI-green, but no direct test currently exercises first upload, same-key identical retry, changed-content conflict, concurrent retry or cleanup; add those tests before treating idempotency behavior as fully verified.
- The existing PDF implementation is substantially present: private deduplicated PDF library; chapter and complete-book assignment; Hindi/English and book-part handling; page-range metadata; draft/publish/archive; authorized rendered page delivery; reader progress; Android encrypted app-private rendered-page cache and retrying WorkManager worker. This is a textbook/PDF-specific system, not the new unified multi-resource catalogue. PDF bytes are currently stored as PostgreSQL bytea, and LearningDocumentController.uploadPdf calls MultipartFile.getBytes(); the 500 MiB ceiling therefore has heap/concurrency implications that need a deliberate solution.
- Video source audit confirms: private online playback and progress already work in web/Android; V108 separates public YouTube broadcast ID from private meeting URL and web renders the broadcast after join authorization; V109 adds idempotency for whole-file upload retries. Android join still only launches joinUrl and does not handle youtubeVideoId. No resource-level download flag, separate download endpoint, chunk/resume upload session, or Android offline video player/download path was found.
- The common provider-neutral resource catalogue is not yet implemented: current PDF assignments support subject-book/chapter-PDF scopes; recordings optionally reference a session/chapter; generic content_asset remains URL/status/provenance and single lesson-block attachment, not the full multi-resource access-controlled contract.
- No automated media-volume backup/restore job or successful restore drill has been evidenced. A named persistent volume must not be reported as backed up. No 100–500-viewer operational load test or real-account/device end-to-end proof has been found in this audit.
- Current completion decision: **not complete end to end**. Keep epic #65 and issues #59, #61, #63, #64 open. The refreshed source audit is in docs/product/live-lectures-messaging-offline-video-audit.md; it supersedes older baseline gap descriptions where the October 17 checkpoint records a newer implementation state.
- Next implementation order: (1) add specific V109 idempotency regression/concurrency tests; (2) implement one provider-neutral resource catalogue/assignment model using canonical chapter, legacy lesson.id, and validated published structured artifact_id identities; (3) add download policy + Android offline video transfer/player; (4) close Android YouTube provider gap; (5) implement media backup/restore and operational tests. Keep changes on main, leave generated pipeline outputs only under data/, and do not close the epic until all acceptance criteria are evidenced.


### Implementation journal — 2026-10-17 (checkpoint 7: admin-controlled join window)

- Added V110 `join_window_minutes` to each live session, default 15 for existing and new sessions. Server validation accepts 0–1440 minutes; 0 disables only the pre-start gate, and does not bypass enrollment, approved teaching assignment, eligible employee/student state, session status, or end-time checks.
- Added the setting to session creation and a `CLASS_MANAGE`-protected update endpoint for scheduled sessions. All server join decisions (student join and assigned teacher meeting-link access) now use the persisted setting; teacher's joinable catalogue predicate uses the same rule rather than a hard-coded 15 minutes. The 24-hour student schedule visibility rule is unchanged.
- Added admin form options (off, 15m, 30m, 1h, 2h, 6h, 24h) and a per-scheduled-session control so an Admin can revise the setting after creation. The UI explicitly warns that Off permits an otherwise authorized participant to join as soon as the session is scheduled, while the session is not ended.
- Added controller tests for disabled early gate, configured 30-minute gate, and rejecting settings outside the supported range.
- **Validation status:** these changes are committed directly to main and the new CI run is authoritative; do not treat this milestone as validated until API verification, migration tests, admin build and full workflow succeed.
- Next in the locked plan: add direct V109 idempotency retry/conflict regression tests, then implement the provider-neutral resource catalogue and ordered assignments, then protected Android offline video downloads/player and Android YouTube Live parity. Keep the docs journal synchronized as each milestone lands.


### Implementation journal — 2026-10-17 (checkpoint 8: recording idempotency regression coverage)

- Added direct controller regression tests for an identical upload retry (returns the existing draft and removes duplicate bytes) and idempotency-key reuse for different content (409 conflict and cleanup).
- During this follow-up review, restored the teacher `joinable` query's private-URL requirement. The admin-configurable join window changes only when the server allows an assigned teacher to retrieve a private meeting link; a public YouTube broadcast is not a private teacher meeting link and must not appear as joinable through that endpoint.
- The tests and query correction are prepared as the next source checkpoint but are not yet on `main`; the live join-window validation run must finish first. Once published, the full Actions run is the acceptance evidence for this checkpoint.
- Next implementation: provider-neutral resource catalogue, ordered scope assignments across chapters/legacy lessons/published structured artifacts, then authorized Android offline media.


### Implementation journal — 2026-10-17 (checkpoint 9: provider-neutral resource catalogue backend)

- Added forward-only V111 schema for reusable resources, private file metadata, source references to existing recordings/textbook documents/content assets, explicit reviewed mappings from structured artifact IDs to canonical chapter IDs, ordered assignments, lifecycle/audit fields, per-resource download policy and learner media progress.
- Added private streamed file storage under the existing media volume's `learning-resources/` subdirectory. Uploads use a streamed checksum pass, 500 MiB ceiling, file-signature allowlist and temporary-file/atomic-move handling. New video/worksheet/slide/audio/image bytes do not enter PostgreSQL.
- Added API routes for linking existing assets or external HTTPS resources, private uploads, resource publication/archive and download policy, target selection, ordered chapter/legacy lesson/structured artifact assignments, learner resource lists, authenticated byte-range streaming/download and media progress.
- Existing recorded-class, learning-document and content-asset bytes are referenced by database ID rather than copied. The existing textbook reader remains responsible for protected learning-document pages.
- Structured resource assignments require an actually published artifact. The first assignment creates an explicit mapping from its stable artifact_id to the chosen canonical chapter; subsequent assignments must respect that mapping, and class/subject/chapter metadata is checked again when learners access it.
- Validation status: the new API/schema/test checkpoint is on main but not validated yet. The Admin catalogue page and student web/Android lesson surfaces still need to be implemented; offline Android video transfer and backup/restore/load tests remain open.


### Implementation journal — 2026-10-17 (checkpoint 10: cross-surface YouTube Live parity)

- Android now consumes the `youtubeVideoId` returned by the authorized join API, validates its shape, displays the privacy-enhanced YouTube embed in QuantaEdge, offers reopen for joined sessions and preserves a separate leave/attendance action. Meet and other private HTTPS providers retain the existing external handoff.
- Added an Android video-ID validation unit test, an API regression proving that YouTube join returns a video ID without a private meeting URL while persisting attendance, and CI source-contract checks for Admin/student-web/API/Android wiring.
- These are **source-contract, unit and build checks**, not proof that a real student completed a broadcast on web and a physical device. A controlled live broadcast/test account is required for runtime E2E.
- Remaining completion gates: latest-main CI pass; runtime browser/device join/reopen/leave; V111 resource catalogue UI across Admin/student web/Android; Android app-private offline recordings across restart/account switch; durable classroom/private messaging; media backup/restore, retention and load evidence.


### Implementation journal — 2026-10-17 (checkpoint 11: custom join-window and anytime control)

- Replaced fixed Admin dropdown choices with a numeric input accepting any whole number from 1 to 1440 minutes and an explicit “Allow joining anytime” checkbox. The same interaction is available when scheduling a class and when editing a scheduled session. The anytime choice is represented to the API as 0; while off, the selected minute count is saved.
- Fixed student-side parity at the same time: student web and Android now use the persisted `join_window_minutes` value returned by the session API instead of hardcoding 15 minutes in web or relying only on an API rejection in Android. Both preserve the server's authorization as the definitive gate. Android shows a pre-start hint and refreshes its local clock while the class screen is visible.
- Added regression coverage for the Android minute boundary, zero/anytime semantics, ended/completed sessions and malformed times; CI source-contract assertions check Admin schedule/edit, web, Android and API wiring. Updated the UI catalogues in all workspaces.
- **Validation status:** pending the full build on this commit. This is not evidence of real-account/device E2E; controlled browser and Android playback must be tested separately.
