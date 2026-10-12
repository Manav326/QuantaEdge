# QuantaEdge Android

Native Kotlin + Jetpack Compose client for the existing QuantaEdge API. It reuses published curriculum, lesson/question records, attempts, assessments and the protected learning-document service; it does not seed demo learning data.

## Local build

Requirements: Android SDK (API 35), JDK 17, Gradle 8.11.1, and Android platform/build tools.

From the repository root:

~~~powershell
gradle -p apps/android assembleDebug
gradle -p apps/android testDebugUnitTest
~~~

The default debug API URL is http://10.0.2.2:8080/, which reaches a backend exposed on host port 8080 from the Android emulator. For a physical test device, pass a reachable development-server URL:

~~~powershell
gradle -p apps/android assembleDebug -PquantaedgeApiBaseUrl=http://192.168.1.20:8080/
~~~

Use cleartext HTTP only on a trusted development network. A release build requires a real HTTPS endpoint:

~~~powershell
gradle -p apps/android verifyReleaseApiConfiguration -PquantaedgeApiBaseUrl=https://your-real-api-host/
gradle -p apps/android assembleRelease -PquantaedgeApiBaseUrl=https://your-real-api-host/
~~~

Do not use an invented host. Release signing must be configured outside this repository; never commit signing keys or passwords.

The Gradle build copies the canonical approved wordmark from apps/web/public/branding into Android generated resources. The native AI tutor follows the backend feature flag and persists its conversation through the existing tutor API. Live-class join/leave updates server-side attendance, and recorded classes play inside a protected Media3 player using the authenticated cookie jar and periodically save viewing progress. Protected textbook pages are encrypted at rest with Android Keystore and stored only in app-private internal storage, namespaced per authenticated account. Unique WorkManager jobs download authorized rendered page images when connected, skip cached pages and retry transient server/network failures. Screens use FLAG_SECURE and app backup is disabled.

Client-side anti-capture controls reduce casual copying but cannot prevent another device from photographing the screen. Do not publish a release until endpoint configuration, signing, real-device flows, and privacy/legal review are complete.
## Web parity implemented in the Android student app

The student home includes a certificate centre and a Top 20 test leaderboard. The certificate centre uses the same authenticated backend lifecycle as the web app:

- Lists only certificates belonging to the signed-in student.
- Requests subject-completion or released-assessment-pass certificates using the canonical `/api/v1/students/me/certificates/issue` endpoint; eligibility and recipient details are derived and validated by the server.
- Supports the Classic, Modern, and Academic templates and Hindi/English issuance.
- Downloads the original PDF or PNG through the ownership-checked API and asks the student to choose a save location using Android's document picker. Revoked certificates are not offered for download.

The Top 20 screen loads the published Maths and Science tests and calls each selected test's server-authoritative `/api/v1/learning/assessments/{id}/leaderboard` endpoint. It shows participant count, the student's standing when available, ranked students, scores, time, and server-provided ranking criteria. It does not calculate rankings from local or unsynchronized offline attempts.

Before release, verify both features against a real student account and the deployed backend, including an ineligible certificate request, a revoked certificate, PDF/PNG saving, an empty leaderboard, and a student outside the Top 20. The GitHub Android workflow assembles a debug APK and runs unit tests on pushes to `main`; a successful source build does not replace physical-device testing.
