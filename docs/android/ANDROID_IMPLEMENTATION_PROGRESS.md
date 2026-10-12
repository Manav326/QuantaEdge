# QuantaEdge Android implementation progress

Last updated: 2026-10-12

## Milestone 1 — Native project and secure foundations

**Status:** Source committed; Android workflow will provide build/test confirmation.

- Added an independent Gradle Android application under apps/android with Kotlin, Jetpack Compose, Material 3 and Java 17.
- Added an emulator-configurable API endpoint, debug-only cleartext allowance and a release HTTPS endpoint validation task.
- Added an OkHttp client for the existing QuantaEdge backend.
- Added Android Keystore AES-GCM persistence for backend HttpOnly auth cookies and app-private protected resource pages.
- Added unit tests for practice retry and saved-ungraded-answer behaviour.
- Added a GitHub Actions workflow to compile, run unit tests and upload an installable debug APK.
- Reuses the canonical web wordmark by build-time copy; Android does not carry a duplicate logo binary.
- Protected windows use FLAG_SECURE and Android backup is disabled.

## Verification record

- Local emulator/device verification has not yet been performed.
- CI result must be checked on the latest main commit before calling the build green.
- A successful compile cannot validate the deployed API, available content inventory, device-specific offline behaviour or release signing.

## Next milestone

Implement real Compose flows for sign-in, student/parent home, Maths and Science tracks, lesson detail, practice persistence, diagnostic, formal assessment attempts/results, and the protected learning-resource reader/download manager. Run the workflow and address every build/test failure before reporting the app as installable.
