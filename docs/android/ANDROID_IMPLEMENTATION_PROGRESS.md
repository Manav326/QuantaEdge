# QuantaEdge Android implementation progress

Last updated: 2026-10-12

## Milestone 1 — Native project and secure foundations

- Added the Android Gradle project, backend API client, encrypted HTTP-only cookie persistence and protected resource cache.
- Added unit tests for practice retry and saved-ungraded-answer behaviour.
- Added a GitHub Actions workflow to compile, test and publish a debug APK artifact.
- Canonical web wordmark is copied at build time; Android backup is disabled and app windows use FLAG_SECURE.

## Milestone 2 — API-backed student and parent journeys

**Status:** Committed to main; awaiting CI build and unit-test result.

- Student and parent password sign-in through the existing auth endpoints.
- Student home, separate Maths/Science tracks, published lessons, inline question submissions and lesson-based practice with session refresh/resume.
- Diagnostic attempt load, saved answers and submit.
- Published assessment catalogue, attempt start/resume, server-saved answers, submit, and released results list.
- Protected textbook/resource catalogue, in-app page reader, encrypted page downloads that resume by skipping pages already stored, and server progress update.
- Parent dashboard, child list and server-generated child reports.
- App language toggle, sign-out, loading/error states, no fake course or question data.

## Milestone 3 — Parent onboarding and account-safe background downloads

**Status:** Added to source; build confirmation pending.

- Added parent signup and password-reset OTP flows through the existing backend.
- WorkManager downloads authorized rendered page images with network constraints, retry and per-page progress.
- Offline resource store/manifest/key is account-scoped; worker checks the active server session before downloading.
- Inline lesson answers respect the backend's saved state, and the home recommendation can open the actual recommended lesson.

## Milestone 4 — Tutor and classes

**Status:** Added to source; CI verification pending.

- Lesson screen now opens the existing contextual tutor session; conversation history, tutor modes and configured/unconfigured status use the current tutor APIs.
- Added student live/recorded class catalogue by subject, meeting hand-off with server attendance join/leave, and authenticated in-app recorded video playback.
- Recorded playback reuses the session cookie jar, handles an expired access cookie through refresh, and periodically saves playback position/watched time to the existing progress endpoint.

## Milestone 5 — Parent self-service

**Status:** Added to source; CI verification pending.

- Parent dashboard can create a child profile with explicit consent, class 6–8 selection, Maths/Science access and login credentials.
- Added account-safe credential updates and subject-track management per child.
- Added parent profile editing against the current guardian-profile endpoint.
- Child provisioning follows backend support: Hindi is the supported profile language at present; the Android UI does not offer an unsupported English child-account option.

## Milestone 6 — Student profile and progress

**Status:** Added to source; CI verification pending.

- Student can edit profile details through the existing profile endpoint.
- Language changes persist to the server-side student record as well as the app preference.
- Added a server-backed dashboard for curriculum completion, question accuracy, concept mastery, study time, and released assessment performance.

## Milestone 7 — Assessment result review

**Status:** Added to source; CI verification pending.

- Assessment result history displays each test’s latest three released attempts and best score within that same window.
- Each released attempt opens the existing server-owned attempt detail endpoint.
- The read-only review includes the learner response, released correct answer, earned marks, teacher feedback and explanation when present. Answer keys remain hidden until the server reports a released result.

## Milestone 8 — Profile photos and child archiving

**Status:** Added to source; CI verification pending.

- Student and parent profile screens can select a photo with Android's system Photo Picker—no broad file-storage permission.
- Image data is resized/re-encoded as JPEG under the existing API's 350 KB limit before it is sent to the authenticated profile-photo endpoint.
- Parent can archive a child profile after a confirmation dialog; the backend remains responsible for consent validation, session revocation and access checks.

## Verification record

- GitHub Actions is configured to compile the debug APK and run unit tests on pushes to main.
- No local Android emulator or physical-device session has been run from this environment.
- Server-side access control and grading remain authoritative. Offline formal assessments are not simulated as locally graded.
- Protected offline reader pages are encrypted in app-private storage and have no public export/share route. FLAG_SECURE and disabled backup are safeguards, not a guarantee against an external camera or compromised device.

## Next

- Review CI logs; fix any Kotlin/Gradle issues exposed by the build.
- Verify login, lesson-answer persistence, practice retries and restart/resume on the real test backend.
- Verify partial/full resource download, offline page rendering and storage handling on a real Android device.
- Expand automated UI/API contract coverage before preparing a signed release bundle.
