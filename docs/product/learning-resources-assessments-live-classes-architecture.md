# Learning resources, assessment records, grading, and live classes

## Goal

Add secure chapter/book resources, durable test attempts and results, teacher review for non-auto-graded answers, and scheduled/live/recorded classes to QuantaEdge without changing the existing class/subject access rules or exposing private source files.

## Existing architecture to preserve

- Student web and admin consoles are separate Next.js applications (apps/web, apps/admin).
- The Spring Boot API (services/api) is the authority for identity, permissions, content eligibility, marking, and access decisions.
- PostgreSQL and Flyway own durable records. Redis remains available for transient/session-oriented needs.
- Students have class identity plus explicit active subject enrollments (student_track_enrollment).
- Staff access is represented by staff_account and staff_permission_grant; AuthorizationService is the single permission-checking entry point.
- StaffAuditService writes staff-level audit events.
- The API is built as a single container and deployed through the existing main-branch CI/GHCR workflow.

## Target architecture

### 1. Textbook library (subject book + chapter PDF)

- A learning_document record identifies the logical resource, its scope (SUBJECT_BOOK or CHAPTER_PDF), curriculum subject/chapter, title, current version, upload metadata, page count, checksum, lifecycle state, and uploader.
- Each upload version gets a separate immutable version record. Replacements create a new version; older versions can be retained for traceability and rolled back by an authorized editor.
- Original PDFs and derived page images are stored outside the database in a private persistent storage directory mounted into the API container. The database stores opaque storage keys, not user-supplied paths or public URLs.
- Upload validation checks the PDF signature, size/page limits, rendering success, safe file naming, checksum, and ownership of the selected subject/chapter.
- PDF pages are server-rendered to image tiles for the view-only student viewer. Student endpoints return only the requested page after authorization; they never serve the original PDF or a filesystem path. Student pages use Cache-Control: no-store and restrictive response headers.
- Subject-book access is controlled by active student subject enrollment. Chapter PDF access additionally verifies that the chapter belongs to the student's class and an enrolled subject, and follows the same active/published curriculum availability rules.
- The viewer supports page navigation, thumbnails, zoom, fit-to-width, fit-to-page, fullscreen, page count, progress/resume, and keyboard navigation, with a mobile-friendly layout. The viewer omits built-in download/print controls. This deters casual copying but is not absolute DRM: an authorized viewer can still capture screenshots or save delivered page images.
- Admin upload/manage UI uses the existing staff authentication and explicit content permissions. Never put a PDF into the public Next.js assets directory or make an object-storage bucket public.

### 2. Assessment/test attempts and durable results

- An assessment defines a published test, scope, duration, availability, attempt limits, and a stable set/order of eligible questions with per-question maximum marks.
- A student start action creates a durable student_assessment_attempt, snapshots the assessment/questions used for that attempt, and writes each response to student_assessment_answer as it is saved. Refreshing or reconnecting reopens the same in-progress attempt; it must not silently create a new result.
- Submission freezes the response set, records timestamps, computes objective marks server-side, and routes the attempt to AWAITING_REVIEW if any answer requires manual grading.
- Each answer has a durable status (AUTO_GRADED, PENDING_REVIEW, GRADED) and optional mark/feedback fields. Students cannot alter answers after submission.
- Final scores are stored, not recomputed from today's question content. Results are released to students only when all manual grading is done and an authorized grader publishes the result.

### 3. Teacher review and test-level audit

- The admin has a dedicated assessment-review queue for submitted attempts with pending manual answers. It supports search/filter by test, student, subject/class and status.
- Each test can have assigned staff graders. An administrator can grade any attempt; otherwise only an active staff member assigned to that assessment and granted the grading permission may do so.
- Per-answer marks/comments are saved as assessment data for feedback and result display.
- The test audit trail is intentionally test-level only: who assigned, submitted, graded/released the test, when it occurred, the status transition, and an optional overall note. It does not create a separate audit event for every question. No answer text or question-by-question marking is copied into the audit log.
- Grading and publishing are transactional; the audit row and grade changes commit together.

### 4. Live classes and recorded classes

- A learning_class_session represents a scheduled/live/completed/cancelled class tied to a class/subject and optionally a chapter/topic, with start/end times, host staff, provider metadata, and a private join destination.
- Student access is derived from active class/subject enrollment and the session's availability window. Join endpoints return meeting details only after authorization; the join URL is never exposed in public curriculum/catalog responses.
- A recorded_class points to private stored media or an approved video-provider identifier. Direct public file URLs are not used for protected recordings. Access is checked on each playback/segment request or against short-lived, scoped playback credentials where a streaming provider supports them.
- Attendance/join events and recorded playback progress are persisted so students can resume; staff can inspect attendance and content access separately.
- Existing lessons remain the canonical teaching content; classes link back to the related subject/chapter/topic rather than duplicating that content.

## Security, privacy, and reliability rules

- Backend authorization is authoritative. Hiding a button in React is not access control.
- Enforce active student, class, enrollment, chapter status, staff permissions and grader assignment server-side on every read/write.
- Keep raw PDFs, recordings, answer keys, correct-answer snapshots, and meeting join URLs out of public static folders and public API responses.
- Use server-generated storage keys, path normalization, upload limits, explicit MIME/signature checking, and safe failure cleanup of partially written files.
- Use private/no-store response headers for protected content and results; avoid shared CDN caching for authenticated content.
- Persist timestamps and scores in PostgreSQL transactions. Preserve submitted snapshots/results when course content changes.
- Do not log answer contents, document bytes, or conferencing tokens in audit logs.
- Add migrations forward-only; do not edit released migrations.
- Retain an audit history for test-level actions, not per-question audit noise.
- Record schema/storage compatibility and backup/restore requirements in deployment docs.

## Delivery sequence and acceptance gates

### Phase 0 — architecture and constraints
- This document is the reference architecture.
- Confirm which existing roles/permissions, schemas, storage and routes can be reused before implementing features.

### Phase 1 — protected PDF resources
1. Add versioned metadata and page tables in a new Flyway migration.
2. Add private storage and safe PDF validation/rasterization, with configurable limits and persistent Docker volume.
3. Add permission-checked admin upload/list/retire APIs for subject books and chapter PDFs.
4. Add student resource catalog and protected page endpoints scoped to active enrollments.
5. Add admin resource management UI and a polished responsive student page viewer.
6. Add tests for upload validation, page authorization, document scope and no-original-PDF delivery.
7. CI gate: API tests, admin build, web build, container builds all green.

### Phase 2 — durable tests and results
1. Add assessment definition, question snapshot, attempt, answer and test-level audit tables.
2. Add permissioned assessment authoring/publication, student start/resume/save/submit and result APIs.
3. Add server-side auto-marking and manual-review routing.
4. Add student test/result UI and admin/grader review UI with assigned-staff enforcement.
5. Add regression tests for snapshots, repeat submissions, manual review, grade persistence and test-level audit privacy.
6. CI gate: all jobs green; never clear or recalculate a submitted result on content edits.

### Phase 3 — live and recorded classes
1. Add scheduled session/recording metadata, staff host/grader assignments, attendance and playback progress tables.
2. Add staff create/update APIs and student catalog/join/playback APIs with access windows and enrollment checks.
3. Add admin schedule/recording UI and student schedule/playback UI.
4. Integrate a meeting/video provider only behind a provider interface; do not store provider secrets in browser code.
5. Add tests for out-of-scope students, access windows, attendance/progress persistence and private playback.
6. CI gate: all jobs green.

### Phase 4 — operational readiness
- Verify persistent volume mount/backup/restore, migration compatibility, storage quotas, retention, monitoring, and a deployment smoke test.
- Only then deploy the migrations and service images to production.
- Upload the actual supplied book/chapter PDFs and configure any conferencing/video provider credentials after the management UI and storage are available.

## Definition of done

- Every feature is committed directly to main as requested; no task-specific feature branch is created.
- The latest commit has green API verification, content-import safeguards, web/admin builds, and container build jobs.
- PDFs and recorded media are private, survive container recreation through persistent storage, and are never exposed as public static URLs.
- Book/chapter access is enrollment-aware.
- Submitted tests, responses, review state, results and release timestamps persist through refresh, question edits and redeploys.
- Only authorized admin/assigned staff can grade; students see published results and feedback.
- Test audit shows the test-level actor/action/time, not per-question audit records.
- Live class joining, recording access, attendance, and resume progress are persisted and authorization-checked.
- No production migration/deployment is claimed unless it was actually executed and verified.
