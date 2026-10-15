# QuantaEdge Android

Native Kotlin + Jetpack Compose client for the existing QuantaEdge API. It reuses published curriculum, lesson/question records, attempts, assessments and the protected learning-document service; it does not seed demo learning data.

## Local build

Requirements: Android SDK (API 35), JDK 17, Gradle 8.11.1, and Android platform/build tools.

From the repository root:

~~~powershell
gradle -p apps/android assembleDebug
gradle -p apps/android testDebugUnitTest
~~~

The default debug API URL for a physical Android device on the same Wi-Fi is http://192.168.31.47:8080/, matching the QuantaEdge test PC LAN address configured in mPay. The backend must be running on the PC and reachable over port 8080. For an Android emulator, override this with the emulator host alias:

~~~powershell
gradle -p apps/android assembleDebug -PquantaedgeApiBaseUrl=http://10.0.2.2:8080/
~~~

To use a different PC/LAN address, override the URL with `-PquantaedgeApiBaseUrl=http://YOUR-PC-LAN-IP:8080/` or `QUANTAEDGE_API_BASE_URL`.

Use cleartext HTTP only on a trusted development network. A release build requires a real HTTPS endpoint:

~~~powershell
gradle -p apps/android verifyReleaseApiConfiguration -PquantaedgeApiBaseUrl=https://your-real-api-host/
gradle -p apps/android assembleRelease -PquantaedgeApiBaseUrl=https://your-real-api-host/
~~~

Do not use an invented host. Release signing must be configured outside this repository; never commit signing keys or passwords.

The Gradle build validates and copies the canonical approved Q-mark icon from apps/web/public/branding into Android generated resources. The same Q mark is used for the native adaptive launcher icon; the wordmark is not used as an app icon. The Android brand component renders the unchanged QuantaEdge name in the approved navy/orange lockup with the unchanged English tagline, matching the web component; it does not load the incomplete legacy quantaedge-wordmark.png asset. The native AI tutor follows the backend feature flag and persists its conversation through the existing tutor API. Live-class join/leave updates server-side attendance, and recorded classes play inside a protected Media3 player using the authenticated cookie jar and periodically save viewing progress. Protected textbook pages are encrypted at rest with Android Keystore and stored only in app-private internal storage, namespaced per authenticated account. Unique WorkManager jobs download authorized rendered page images when connected, skip cached pages and retry transient server/network failures. Screens use FLAG_SECURE. Android Auto Backup is allow-listed to the active learner session recovery file only; protected textbook pages, offline assessments, profile caches, and all other app data remain excluded. Reinstall can restore a session only when Android backup/restore is enabled and the backend session is still valid (maximum 30 days from sign-in). Explicit logout revokes the backend session and clears the recovery snapshot. If Android does not restore app data, the user must sign in again.

Client-side anti-capture controls reduce casual copying but cannot prevent another device from photographing the screen. Do not publish a release until endpoint configuration, signing, real-device flows, and privacy/legal review are complete.
## Web parity implemented in the Android student app

The student home includes a certificate centre and a Top 20 test leaderboard. The certificate centre uses the same authenticated backend lifecycle as the web app:

- Lists only certificates belonging to the signed-in student.
- Requests subject-completion or released-assessment-pass certificates using the canonical `/api/v1/students/me/certificates/issue` endpoint; eligibility and recipient details are derived and validated by the server.
- Supports the Classic, Modern, and Academic templates and Hindi/English issuance.
- Downloads the original PDF or PNG through the ownership-checked API and asks the student to choose a save location using Android's document picker. Revoked certificates are not offered for download.

The Top 20 screen loads the published Maths and Science tests and calls each selected test's server-authoritative `/api/v1/learning/assessments/{id}/leaderboard` endpoint. It shows participant count, the student's standing when available, ranked students, scores, time, and server-provided ranking criteria. It does not calculate rankings from local or unsynchronized offline attempts.

Before release, verify both features against a real student account and the deployed backend, including an ineligible certificate request, a revoked certificate, PDF/PNG saving, an empty leaderboard, and a student outside the Top 20. The GitHub Android workflow assembles a debug APK and runs unit tests on pushes to `main`; a successful source build does not replace physical-device testing.


## Google Play Store release

The app targets API 36 and its release build uses R8 code minification plus resource shrinking. Release builds intentionally fail unless the API URL is HTTPS and upload signing is configured.

Run **Android Play Store release bundle** from GitHub Actions `workflow_dispatch`. Before running it, configure these repository Actions secrets: `ANDROID_KEYSTORE_BASE64` (base64-encoded upload keystore), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Never commit signing keys or passwords. Enter a version code higher than the highest code already uploaded to Play Console, a version name, and the deployed HTTPS API base URL. The workflow runs unit tests and attaches the signed `app-release.aab`, R8 mapping file, and `play-store-icon-512.png` to its artifact.

Before submission, install and exercise the release build on physical devices; verify student/parent authentication, certificates, PDF viewing/saving, barcode scanning, offline assessments and protected resources against production. Complete Play Console's store listing, screenshots/feature graphic, public privacy-policy URL, Data safety form, content rating, target-audience declarations and app-access instructions. A successful source build alone does not constitute Play approval.
