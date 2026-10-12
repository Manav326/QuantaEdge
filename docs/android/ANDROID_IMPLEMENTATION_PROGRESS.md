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
