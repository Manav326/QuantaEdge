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

The Gradle build copies the canonical approved wordmark from apps/web/public/branding into Android generated resources. Protected textbook pages are encrypted at rest with Android Keystore and stored only in app-private internal storage. Screens use FLAG_SECURE and app backup is disabled.

Client-side anti-capture controls reduce casual copying but cannot prevent another device from photographing the screen. Do not publish a release until endpoint configuration, signing, real-device flows, and privacy/legal review are complete.
