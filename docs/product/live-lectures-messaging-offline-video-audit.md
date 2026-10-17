# Live lectures, messaging, private replay and offline video — source audit

**Audit date:** 2026-10-16  
**Audited branch:** `main`  
**Checkpoint audited:** `df5e8d5cefc0b88e136a4516cf119cc24504a79d`, plus the three subsequent content-only commits through `19ec0e7a76a3b1f740bf0cbefa1d4057c5da457e`.  
**CI evidence:** [Build and Publish run 2862](https://github.com/Manav326/QuantaEdge/actions/runs/38933136844) passed all eight jobs on the checkpoint commit. Current `main` is three commits ahead, all modifying pipeline data under `data/`; the successful checkpoint is an ancestor of current `main`.

This document records source-confirmed contracts and gaps before new schema work. It is not a claim that the feature is complete or deployed.

## Current contract map

| Area | Existing source of truth | Confirmed behavior | Gap to close |
|---|---|---|---|
| Scheduled/live sessions | V43 `learning_class_session`; `LearningClassController`; V79 managed Meet fields | Session has subject/chapter, teaching assignment, start/end, status, provider, private join URL and managed Google Meet operation metadata. Admin has list/create/status/schedule/teacher-reassignment/Meet-retry/private-link endpoints. | V43 provider constraint is `CUSTOM`, `GOOGLE_MEET`, `ZOOM`, `MICROSOFT_TEAMS`, `OTHER`; there is no first-class YouTube Live provider or separate public broadcast reference. |
| Student schedule visibility | `GET /api/v1/learning/live-classes` | Enrollment, active student, approved effective teaching assignment, employee eligibility and published chapter are checked. The list and count query both now filter future `SCHEDULED` sessions to `starts_at <= now() + interval '24 hours'`. | Keep the listing visibility rule separate from join authorization. Add actual database-backed boundary tests if the current test harness supports PostgreSQL. |
| Join and attendance | `POST /api/v1/learning/live-classes/{id}/join`, `/leave` | Server checks enrollment, active session state, assignment/employment, chapter publication and the separate 15-minute early-join limit. Attendance clocks are persisted. Google Meet requires verified provider creation. | A YouTube broadcast needs a provider-specific join/player contract; it must not be forced through the private Meet URL field. Do not widen the 15-minute join gate. |
| Recorded-class catalogue | V43 `recorded_class`; admin/student methods in `LearningClassController` | One row per uploaded recording; optional session/chapter relation; status lifecycle `DRAFT/PUBLISHED/ARCHIVED`; MP4/WebM/QuickTime; size up to 500 MiB; SHA-256 and optional duration. | No lesson/microtopic link, download permission, generic resource type, provider reference, upload idempotency key or resumable upload state. |
| Recording upload | `POST /api/v1/admin/recorded-classes`; `PrivateMediaStorageService` | Single multipart request. Storage generates a UUID key, streams to a private `.part`, hashes SHA-256, checks basic media signature and size, then atomically moves where supported. Controller removes the just-written file if the DB insert/audit transaction throws. | A client retry after an uncertain response can create a duplicate row/file. No resumable upload session/chunk contract or persisted processing state. Duration is optional metadata, not derived/verified by the storage service. |
| Private media deployment | `docker-compose.prod.yml`; `APP_MEDIA_STORAGE_DIR=/var/lib/quantaedge/media` | API mounts named `quantaedge_media` volume; PostgreSQL and Redis are existing services. Recording bytes are on disk, not in the database. | Repository documentation requires backup/restore of the media volume, but this audit did not find an automated media backup/restore job or script. Persistent volume is not proof of backup or restore readiness. Verify the actual deployment and restore procedure before production claims. |
| Playback | `GET /api/v1/learning/recorded-classes/{id}/stream`; web HTML video; Android Media3 | Server checks active student, class/subject enrollment, published recording and active/published chapter. Stream supports byte ranges and restrictive no-store/nosniff/noindex headers. Web and Android save playback progress. Android uses authenticated OkHttp-backed Media3 source. | Android playback is online-only; no video download/offline playback control is present in `LearningClassesScreen.kt` / `RecordedPlayerScreen.kt`. No separate authorized download policy/endpoint. |
| Offline resources | V41/V47/V52 and `LearningDocumentController`; Android `ProtectedResourceStore` and `ProtectedResourceDownloadWorker` | Existing textbook/PDF system stores PDF bytes in PostgreSQL `bytea` and serves authorized rendered pages; Android encrypts page images in app-private storage and has a retrying WorkManager worker. | The Android worker is page-image-specific (not video). Reuse its owner isolation, encrypted-storage and worker patterns only where appropriate; do not treat page download endpoints as video support. Keep new video bytes out of PostgreSQL. |
| Existing resource models | V3 `lesson` / `lesson_block`; V16 `content_asset`; V41 `learning_document`; V51 translations | `lesson` is chapter-scoped; `lesson_block` is ordered JSONB and can reference one `content_asset`. `content_asset` supports IMAGE/DIAGRAM/VIDEO/AUDIO and a URL/status/provenance. `learning_document` supports subject/chapter PDF assignments and publication. | No single provider-neutral multi-resource assignment/catalogue was found that supports multiple videos/files per lesson/chapter with download policy, lifecycle, audit, provider reference and private storage metadata. Existing `learning_document` publication uniqueness and rendered-page delivery are not a generic file library. Do not overload lesson JSONB or create another video-only subsystem. |
| Admin/web clients | `apps/admin/app/classes/page.tsx`; `apps/web/app/student/classes/ClassesClient.tsx` | Admin can schedule sessions, manage verified Meet setup, upload a recording as draft, and publish/archive. Student web lists sessions, calls the authorized join endpoint, embeds private recorded stream and saves progress. | Admin upload has no resume/retry identity or multi-resource attachment UI. Student web does not yet render a QuantaEdge-owned YouTube live player, native class chat, or download policy. |
| Android parity | `LearningClassesScreen.kt`; `LiveBroadcastPlayerScreen.kt`; `RecordedPlayerScreen.kt`; `ApiClient` | Native schedule, validated YouTube in-app player and authenticated online Media3 replay are present in source. | Offline video lifecycle and native classroom/private messaging remain incomplete. Real device join/reopen/leave, restart and account-switch checks remain pending. |
| Native messaging | Repository code search for `@EnableWebSocket`, `WebSocketMessageBrokerConfigurer`, `SimpMessagingTemplate`, `RedisMessageListenerContainer`, `SseEmitter`, and likely message/conversation table names | No native chat transport or durable message/conversation table was identified in the inspected source. Redis already exists in production Compose. | Need durable PostgreSQL message/conversation membership and authorization first, then real-time delivery/reconnect. Do not add Redis Pub/Sub/broker unless the deployment topology or measured fan-out requires it. |
| Backup/retention | Compose media volume plus architecture runbook | Volume is persistent across API container recreation; no age-based deletion was found in the reviewed recording status path. | Retention/archive/removal policy needs explicit auditable behavior. A restore test must restore DB metadata and media files together and verify hashes. |

## Contract decisions for implementation

1. **Preserve existing IDs and lifecycle.** A resource catalogue must use the canonical `curriculum_subject`, `curriculum_chapter`, and `lesson.id` keys. Any topic/microtopic reference must be reconciled to the actual canonical schema before adding an FK; do not assume a generated JSON topic ID is a database ID.
2. **Do not store video bytes in PostgreSQL or Git.** Keep media in private persistent storage; keep metadata, upload identity, authorization and message history in PostgreSQL. Existing PDF `bytea` storage is legacy behavior and is not a precedent for video.
3. **Model YouTube as a broadcast provider, not a private meeting URL.** Store only the required public broadcast/video reference separately from private provider join URLs. A public broadcast embed is not authorization to join an interactive Meet session. Never send Google secrets or private meeting links in catalogue responses.
4. **Keep replay as QuantaEdge-owned playback.** For recordings, teacher captures an authorized original (preferably simultaneously with the broadcast) and uploads it to private storage. Do not unofficially download YouTube recordings. Publishing a recording requires storage verification and an explicit publication action.
5. **Use an idempotent upload protocol before retry UI.** The server needs a stable upload/request identity and conflict rules, checksum verification, safe cleanup/reconciliation of partial/orphaned files, and explicit status before clients retry. A unique content hash alone is not sufficient because the same source may legitimately be attached to different sessions.
6. **Separate resource assignment from source-specific delivery.** Existing PDFs, uploaded recordings, reusable content assets, and approved external providers have different storage/auth contracts. The schema design must retain source identity and support ordered multi-resource attachments without making a public URL an access-control mechanism.
7. **Offline video must be account-scoped and app-private.** The Android design needs resumable/cancellable downloads, integrity checks, encrypted/private storage, account switch/logout behavior, offline playback after process/device restart, deletion of local copies, and no deletion of the server original.
8. **Messaging must be durable and scoped.** PostgreSQL stores conversations, participants, messages, read/unread state and moderation/audit. Server verifies classroom membership and teacher/student relationships on every read/write. Reconnect retrieves missed messages; client-provided sender IDs are never trusted.
9. **No age-based media deletion.** Archival/removal is explicit, permission-controlled and audited. A recording is retained indefinitely by default.
10. **Do not claim operational safety from Compose alone.** Confirm production mount, free space/quotas, backup schedule, off-host copy and an actual restore drill.

## Required tests carried forward

- PostgreSQL integration tests for visibility immediately before, at and after the 24-hour boundary; 15-minute join boundary; cancelled/completed sessions; and count/list parity.
- Provider tests for YouTube public broadcast references versus private Meet URLs, and proof that private join links/secrets never appear in student catalogue output.
- Multi-resource ordering, multiple resource types per lesson/chapter, scope mismatch rejection, draft/publish/archive history and authorized reads.
- Upload type/size/checksum validation, duplicate retry after lost response, interrupted transfer, partial/orphan cleanup, and publish only after verified storage.
- Playback/download authorization for enrolled and out-of-scope students, byte ranges, path traversal and local download policy.
- Android offline download/playback after process restart and device restart, owner isolation across account switch/logout, checksum failure retry, cancellation and local deletion.
- Public/private chat isolation, membership changes, duplicate-send idempotency, reconnect catch-up, pagination/order, unread state, moderation/rate limits and audit.
- 100–500 broadcast viewer behavior (provider embed, not 500 WebRTC participants); separately load-test native chat fan-out.
- Backup/restore of PostgreSQL metadata plus private media and SHA-256 verification. Record cost per lecture and media growth.

## Exact resume point

1. This source audit is now recorded; issue #58 should be updated with the findings.
2. Reconcile the generic resource catalogue with existing `learning_document`, `content_asset`, `recorded_class`, `lesson`, `lesson_block`, and the actual topic/microtopic schema. Only then write a forward-only migration.
3. Implement provider-neutral resource metadata/assignments and YouTube broadcast references without breaking the existing Meet lifecycle.
4. Design upload idempotency and download policy before adding Android video download UI.
5. Implement durable native messaging only after confirming its membership/permission model against existing authorization helpers.
6. Update the implementation plan journal at each milestone; keep all code changes on `main`, generated pipeline data only under `data/`, and wait for green CI before claiming a checkpoint passed.


## Follow-up discovery — structured lesson/topic IDs (2026-10-16)

The structured lesson runtime confirms that the topic/microtopic content is **not stored as rows in the legacy `lesson` table**. The API loads approved JSON artifacts from packaged `data/structured-lessons` files. Each artifact has a stable string `artifact_id`; its `topic.topic_id` is also a stable string inside the JSON. The database response tables V106/V107 key learner responses by `artifact_id` plus the exact artifact SHA-256, not by `lesson.id` or a numeric topic ID.

Implications for resource linkage:

- A resource attachment to a legacy lesson may use the real `lesson.id` FK.
- A resource attachment to a structured lesson must use the stable `artifact_id` (and may record the corresponding topic ID as descriptive/index metadata); do not invent a numeric topic FK.
- The API must validate a structured artifact through `StructuredLessonArtifactService.requirePublished(artifactId)` and verify that its class, subject and chapter match the selected canonical curriculum target. Draft/unreviewed artifacts must not become student-visible merely because a resource row is published.
- The attachment contract should allow a chapter-level resource with neither lesson target, a legacy lesson target, or a structured artifact target, with mutually exclusive target checks and canonical subject/chapter validation.
- The canonical resource record should not duplicate lesson JSON or modify the artifact's publication status. Preserve the exact artifact SHA/version semantics used by learner responses.

This closes the main topic-ID ambiguity from the first audit pass and is required before writing the resource migration.


---

## Current implementation verification — 2026-10-17

**Repository snapshot audited:** main at 36b38fa34c1b602e4f7238d43300a48023b3e011, immediately before this status-document update.  
**Feature code checkpoint validated:** 5c4dc9c69bf0770517c7e2c33dc17e0f412b4eea.  
**Build evidence:** [QuantaEdge Build and Publish run #3024](https://github.com/Manav326/QuantaEdge/actions/runs/39000812717) — completed successfully; all eight jobs passed, including API verification, Android unit tests/debug APK build, admin/web/API/People Operations image builds, and content safeguards. The three commits from that feature checkpoint to the audited repository snapshot touched the CI workflow, route-inventory scripts/tests, translation tracker and four files under data/; no application feature source changed. Run #3024 validates the feature code checkpoint, not the later workflow/script changes, production deployment, live account/device behavior, load, or backup restoration.

### Updated status — what exists

| Capability | Status from source review | Boundary / limitation |
|---|---|---|
| PDF textbook library | Implemented: private PDF asset library, SHA-256 deduplication, source/review metadata, page-count validation, Admin upload ceiling 500 MiB (standard staff 50 MiB), reuse of existing source assets. | LearningDocumentController.uploadPdf reads the whole PDF into a JVM byte array before validation and DB insert; the 500 MiB ceiling can create substantial heap pressure under concurrent uploads. PDF bytes are stored in PostgreSQL bytea by this legacy textbook path. |
| PDF assignments | Implemented for whole subject books and chapter PDFs, Hindi/English editions, page ranges and textbook parts, draft/publish/archive, publication-slot rules, source/edition fields, staff audit. | Not a provider-neutral lesson resource catalogue. Attachments are not assignable to a particular legacy lesson or published structured artifact, and the manager only treats textbook/chapter PDFs as its resource types. |
| PDF student access | Implemented: API exposes authorized rendered PNG pages rather than raw PDF bytes; verifies active learner, subject enrolment, applicable class/program, publication, asset approval and chapter availability; records page progress. | Browser PDF download/print controls are intentionally absent. Offline textbook support caches encrypted rendered page images, not the original PDF file. |
| Android offline PDF pages | Implemented in code: account-namespaced app-private encrypted page storage and a network-constrained WorkManager worker with retry/progress and valid-page reuse. | CI build is green, but real-device restart, account-switch/logout cleanup, full-document integrity and low-storage recovery still need explicit end-to-end tests. |
| Live schedule / provider | Implemented in the API: 24-hour schedule visibility and separate join authorization window; YouTube Live is modeled separately from private meeting URLs in V108; Google Meet provider lifecycle is retained. Student web embeds the YouTube broadcast after the authorized join call. | Android's joinLiveClass still only opens joinUrl; it does not consume youtubeVideoId or render/open the YouTube broadcast correctly. Provider parity is incomplete. |
| Recording upload | Implemented: MP4/WebM/QuickTime, up to 500 MiB, streamed to private disk using a temporary file, SHA-256, basic signature checks, and atomic move where supported. Admin can create draft recordings and publish/archive them. | It is one multipart request, not a resumable/chunked upload. A retry with the same key still has to upload and hash the entire file before the existing row can be returned. No upload-session/checkpoint or explicit processing-state model exists. |
| Recording retry idempotency | Implemented in V109/controller/admin UI. The Idempotency-Key is scoped to the creator; the request fingerprint includes normalized metadata and file digest; identical retries return the existing recording and key reuse with different content conflicts. Concurrent reuse relies on the unique index and conflict-safe insert. The feature checkpoint passes CI run #3024. | LearningClassControllerTest has no dedicated assertions for first upload, identical retry, conflicting-key reuse, concurrent retry or cleanup. Green CI proves compilation/current test suite and image builds, not those new edge cases specifically. |
| Online replay | Implemented in student web and Android with authenticated private byte-range streaming, playback position/watched-time progress, seek/resume and restrictive response headers; student access is rechecked server-side. | No explicit per-recording download permission or distinct authorized download endpoint; web player disables download controls. Android playback is online-only. |
| Media retention/operations | Media files use the configured private media directory and persistent Compose volume; no age-based automatic deletion was identified in the reviewed path. Archive is a metadata status transition. | A persistent volume is not a backup. The reviewed repo paths do not prove automated backup, cross-restoration of database metadata plus media files, or a successful restore drill. |

### Remaining work — not complete

1. **Provider-neutral resource catalogue (issue #59):** one catalog/assignment contract for videos, PDFs, worksheets, slides, audio, images and external references; multiple ordered resources at chapter, legacy lesson, or published structured-artifact scope; language, lifecycle/version/audit, checksums/provider references, access policy and per-resource download permission. Keep video bytes out of PostgreSQL and generated pipeline files outside data/ uncommitted.
2. **Authorized offline video (issue #61):** server-controlled download permission and endpoint, resumable/cancellable transfer, checksum validation, private account-scoped Android storage, offline Media3 playback, resume after process/device restart, deletion/storage accounting, account-switch/logout handling and partial-file cleanup.
3. **Student/Android provider parity (issue #63):** implement Android YouTube Live handling instead of treating an authorized broadcast with no joinUrl as an unavailable meeting; provide the shared resource catalogue/lesson attachment presentation on web and Android.
4. **Upload robustness:** retain idempotency, but add dedicated regression and concurrency tests plus abandoned temporary/orphan reconciliation. Decide whether resumable upload sessions are required for 500 MiB files; current endpoint cannot resume after a broken transfer.
5. **PDF upload capacity:** evaluate streaming/staged PDF ingestion or a verified heap/concurrency guard so a 500 MiB file is not duplicated in heap under concurrent uploads. Any change must preserve current PDF validation, checksum deduplication and publication/access contracts.
6. **Verification and operations (issue #64):** real-account browser/device E2E; authorization and timing boundaries; upload retry/partial-file tests; offline video tests; measured 100–500-viewer broadcast behavior and separately measured chat load; media+database backup/restore drill; storage/cost and playback-quality measurements. CI green alone does not satisfy these criteria.
7. **Durable native messaging (issue #62):** still absent from the inspected implementation and is outside the PDF/video-specific completion criteria, but remains part of epic #65.

### Current completion decision

**Not complete end to end.** Existing textbook PDF management and online recorded-video playback are functioning code paths; the YouTube web broadcast path and idempotent recording upload have passed the current feature-code CI checkpoint. The common resource catalogue, Android YouTube parity, authorized offline video, resumable transfer, dedicated idempotency tests, backup/restore evidence and full operational tests remain open. Tracking stays in epic [#65](https://github.com/Manav326/QuantaEdge/issues/65) and child issues [#59](https://github.com/Manav326/QuantaEdge/issues/59), [#61](https://github.com/Manav326/QuantaEdge/issues/61), [#63](https://github.com/Manav326/QuantaEdge/issues/63), and [#64](https://github.com/Manav326/QuantaEdge/issues/64).


## Admin-controlled join window — 2026-10-17

- V110 adds a persisted per-session `join_window_minutes` setting; 15 minutes remains the default for existing and new sessions.
- Admins can configure 15 minutes, longer windows up to 24 hours, or Off (0). Off disables only the early-join time check; enrollment/assignment eligibility, session status, chapter publication and end-time authorization continue to be enforced server-side.
- The admin can set this at session creation and change it on scheduled-session cards. Server-side student join, assigned-teacher meeting-link, and teacher joinable-catalogue logic read the persisted value; the 24-hour schedule-visibility limit remains separate and unchanged.
- Regression tests cover the disabled gate, custom-window denial, and invalid value rejection. CI evidence is pending for this checkpoint.


### Recording retry edge-case tests and teacher-link boundary — 2026-10-17

The follow-up source checkpoint adds tests for same-key/same-fingerprint retry and same-key/different-content conflict with duplicate-file cleanup. It also keeps the teacher private-link listing restricted to sessions that actually have a private join URL; public YouTube broadcast metadata is not a substitute for that link. This follow-up is pending publication and CI validation after the join-window run.


## Provider-neutral catalogue backend checkpoint — 2026-10-17

A new V111 catalogue backend is present in code pending CI verification. It adds a common resource record that references existing recordings, published textbook documents and content assets without copying their binaries; new generic files use the existing private media volume under `learning-resources/`. Ordered assignments can target a chapter, canonical legacy lesson, or published structured artifact. Stable artifact IDs are mapped explicitly to canonical chapters and checked against the runtime artifact before learner delivery.

The backend includes resource/assignment lifecycle, source checks, per-resource download policy, private byte-range streaming/download and learner media progress. It is not the completed cross-platform feature yet: Admin catalogue UI, student lesson presentation, Android resource view/offline video, real-account device verification, backup/restore and load tests remain required. Treat V111 as implemented-but-unverified until CI completes.


## Cross-surface parity update — 2026-10-17

- Android's YouTube join flow consumes the protected API response's `youtubeVideoId`, validates the identifier, renders the privacy-enhanced embed in a restricted WebView, supports reopening a joined broadcast and keeps leave/attendance separate from the player.
- Admin's schedule parser fix is on main. An Android video-ID unit test and API join regression are added, with cross-surface contract assertions in the CI source-contract script.
- Evidence boundary: these are source/unit/build checks. A real signed-in browser session and physical Android device playback against a controlled live broadcast have not been executed by this change. Offline video, resource assignment UI, native messaging and media backup/restore remain outstanding.


## Join-window parity update — 2026-10-17

- Replaced fixed Admin dropdown choices with any whole-number minute input plus an explicit “Allow joining anytime” toggle for both creation and scheduled-session edits. The toggle stores 0, which removes only the early-join check; enrollment, eligible approved assignment, session status/provider validity and end time remain enforced by the server. The scheduled-session visibility window is still 24 hours.
- Student web now computes its join state from the API's persisted `join_window_minutes` rather than hardcoding 15 minutes. Android uses the same value to gate joining/reopening and displays a pre-start hint. Both clients refresh their local clock while the screen is visible; API authorization remains authoritative.
- Validation must be repeated on the exact resulting main commit. These checks do not replace controlled signed-in browser and physical Android device E2E.
