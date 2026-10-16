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
