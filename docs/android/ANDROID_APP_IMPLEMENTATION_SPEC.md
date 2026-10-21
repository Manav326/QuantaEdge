# QuantaEdge Native Android App — Implementation Specification

**Status:** Source-of-truth specification; implementation not yet started.
**Target repository:** `Manav326/QuantaEdge`
**Target branch:** `main`
**Product:** QuantaEdge — Smarter Decisions. Greater Growth.

This document preserves the native Android implementation requirements in GitHub so the project does not depend on chat history or temporary workspace files. Update implementation status here or in a linked, committed progress log whenever material work is completed.

## 1. Product objective

Build a production-minded native Android application for QuantaEdge students and parents, centred initially on Bihar Board Classes 6–8, Mathematics and Science, in Hindi and English. Reuse the existing QuantaEdge API, curriculum, question bank, authentication, publishing rules and learning records. Do not create a parallel content system or hardcode sample student/course/question data.

The existing web application and backend remain the product source of truth. Audit current endpoints, request/response contracts, authorization and migrations before building each feature. Where a capability is not supported by the backend, document it as an explicit gap rather than fabricating data or silently inventing a new API.

## 2. Repository and delivery constraints

- Add native Android source under `apps/android/`; do not replace or regress `apps/web`, `apps/admin`, or `services/api`.
- Work directly on `main`, consistent with the repository owner's explicit workflow preference. Do not create feature branches or pull requests.
- Commit all materially completed source, configuration, migrations (only when required), tests, content schemas/manifests, scripts, product decisions and documentation to GitHub as progress is made.
- Keep working state recoverable: never rely solely on the current chat, a temporary container, IDE cache, local Downloads folder, or an uncommitted workspace.
- Store generated JSON/content bundles and translation artifacts in version control when their licence and size permit. Record their source, language, status, checksum, schema/version and review state.
- Do not commit passwords, API keys, signing keys, keystores, access tokens, personal student data, production database dumps, session cookies, or other secrets. Use ignored local configuration and documented examples with placeholders.
- Do not commit textbooks or other copyrighted/licensed binaries unless the actual licence expressly permits redistribution. Keep source files in approved storage and commit safe manifests, extraction metadata, scripts, checksums and original/editor-reviewed derivative content where allowed.
- Put installable APKs and release bundles in GitHub Releases or a release workflow, not in ordinary source history unless there is a specific reason. Commit build workflows and instructions so artifacts can be recreated.
- Do not declare the app finished solely because Gradle builds. Record what built, what tests ran, what passed/failed, and which device/backend checks remain.
- Avoid destructive storage cleanup until relevant folders, generated content, translation bundles, logs and backups have been inventoried and anything valuable has a verified durable copy.

## 3. Required technical approach

Use Kotlin, Jetpack Compose, Material 3, a single-activity architecture, typed navigation, lifecycle-aware state, coroutines/Flow, a clear MVVM or similarly testable presentation/domain/data separation, repository-based API access, dependency injection, Room for structured local state, DataStore for non-secret preferences, Android Keystore-backed encryption for protected local content, and WorkManager for reliable background downloads/sync. Use maintained libraries compatible with the repository's chosen Android SDK/build tooling. Keep dependencies minimal and pinned/reproducible.

Centralize:
- API base URL and environment configuration;
- authentication/session and refresh/expiry behaviour (matching the real backend);
- typed DTOs and API error handling;
- Hindi/English formatting and language selection;
- loading, empty, offline, stale, retryable-error and permission-denied states;
- download manifests, local encryption, sync queue and conflict/idempotency behaviour;
- analytics/diagnostics with privacy-aware redaction.

Never place grading secrets or privileged admin credentials in the Android client. Server-side authorization is mandatory even if the UI hides unavailable features.

## 4. Branding and interface quality

Follow the brand guidelines in `docs/branding.md`, reuse approved logo assets, typography, palette and tagline, and retain the clean, compact, premium visual language of the web product. Build native responsive layouts with accessible text sizes, touch targets, readable mathematics, screen-reader semantics, proper keyboard/insets behaviour and clear focus/error states. Use loading skeletons only where useful; avoid fake charts, fake progress, arbitrary empty cards, or fabricated content. Support Hindi text shaping and mathematical notation, including fractions read and displayed as “1 over 4” where that matches the approved learning-content convention.

## 5. User and learning functionality

### Identity and access
- Implement the actual supported student/parent sign-in, registration, OTP verification and session lifecycle after inspecting current auth APIs.
- Respect parent-child relationships, account suspension, student profile state, class assignment, subscribed/enrolled subjects and server-enforced access rules.
- Provide safe logout, expired-session handling, account/profile basics and appropriate consent/privacy text for minors.
- Do not add permissions that the app does not need. Never log OTPs, passwords, access tokens or protected content.

### Student home and navigation
- Show authenticated learner identity and the actual assigned class.
- Present Maths and Science as distinct subject tracks, with chapter/lesson progress and recommended next learning based on API data.
- Preserve the established class → subject → chapter → lesson hierarchy. Keep language selection clear and context relevant.
- Show meaningful no-content or not-yet-published states instead of fabricated chapters/questions.

### Lesson player
- Consume published/eligible lessons using the live API. Preserve server-defined block order and support the response block types actually returned by the backend.
- Support the teaching flow used by QuantaEdge, including prerequisite/context, explanation (`समझें`), examples, guided and independent work, challenge/hint content, supported media, recap and approved inline questions.
- Render text, maths, lists, tables and supported media correctly. Show AI tutor help only when configured/enabled and authorized.
- Save supported lesson activity and answers through existing endpoints; surface a durable saved/failed/retry state.
- Correctly distinguish a prompt/exercise embedded in a lesson from a persisted question attempt; never claim an answer is saved when it is not.

### Interactive practice
- Start sessions through the backend's lesson-specific practice API and render its selected question snapshot in returned order.
- Support the question types present in backend contracts/content, including MCQ, true/false, numerical and text responses, plus matching, ordering, assertion/reason, case/source-based, diagram/map and other types only when the API and authored data define them.
- Submit answers to the server; honour grading results, ungraded/manual-review states, attempts, retries and backend restrictions.
- Fix the known state edge case: an answer already saved but not yet graded must not remain apparently resubmittable if the server will reject a duplicate submission. Represent `correct = null` as “submitted / awaiting evaluation” when that is the server meaning, not as “not answered.”
- Resume the same session and question order after navigation, app restart or reconnect. Never silently start a duplicate session or discard an answer.
- Provide accessible feedback and explanations only according to server policy, and update completion/progress from server responses.

### Diagnostic, assessments and tests
- Implement the supported initial diagnostic flow, assignment and recommendations from live endpoints.
- Support available chapter/topic/practice tests and formal assessments, including instructions, question navigation, timer rules, answer persistence, submission confirmation, result and review availability.
- Resume only when the server says the attempt can be resumed; restore attempt ID, server timing/deadline and saved answers accurately.
- Results should identify the assessment clearly, present supported score/accuracy and question-level feedback, and honour manual evaluation/release policy. Any “latest three”/best-of-recent-attempt rule must match existing product/API semantics, not be reimplemented inconsistently in the app.
- Formal-test packages must not contain answer keys, grading code/secrets or unreleased feedback. Server remains the grading authority unless a deliberate, documented assessment design says otherwise.

### Progress, mastery and recommendations
- Present only real progress data returned by the backend.
- Keep lesson completion, practice attempts/accuracy and mastery conceptually distinct. Do not derive client-side metrics that conflict with server definitions.
- Explain incomplete or unavailable metrics honestly and refresh after sync where needed.

### Textbook/resources and downloads
- List only resources the current account may access and the backend/rights policy permits to download.
- Allow permitted books or chapter resources to be downloaded, paused/retried where feasible, resumed after interruption, verified by checksum and managed from a clear downloads library.
- Store protected resources in app-private internal storage, encrypted with keys protected by Android Keystore. Do not save protected copies into the public Downloads folder, expose them to other apps, share/export/print them, or back them up to consumer cloud storage.
- Provide an in-app reader/viewer with progress, page navigation and zoom where appropriate. Support offline use only while the server/licence policy permits it; show the actual local/remote availability.
- If download is prohibited, expired or revoked, follow the policy and do not keep presenting the resource as available. Keep textbook source PDFs out of Git unless redistribution is explicitly permitted.

### Downloadable tests and protected offline assessments
- Do not treat an assessment like a normal PDF or ordinary downloadable question document.
- Support offline assessment packages only where the backend's explicit policy permits them. Require a signed/versioned manifest describing assessment ID, allowed use, content version, expiry, attempt limit and device/user binding as appropriate.
- Package only assessment material the learner is allowed to see; omit correct answers, scoring secrets and unreleased explanations.
- Encrypt package data at rest and keep it app-private. Apply protected-window controls such as Android `FLAG_SECURE` on protected screens, restrict export/share/print and add a non-invasive user/session watermark when policy requires.
- Save offline responses as an encrypted local attempt queue with stable IDs and idempotent sync. Make it clear that a response is saved locally but not yet synced, and avoid showing an authoritative score until the server has accepted and graded it.
- Use online-only mode for high-stakes/timed assessments when offline operation cannot meet the integrity requirements. Expiry, clock manipulation, reinstalls, rooted devices and sync conflicts must fail safely rather than silently granting extra attempts.
- Be candid in product/security documentation: no client-only measure can make displayed material impossible to photograph or capture with a separate device.

### AI tutor and media/live classes
- Use current server-supported AI tutor routes and feature flags. If credentials or the feature are not configured, show a clear unavailable state; never invent a working tutor.
- Send only the minimum learning context needed, and never submit unrelated local documents or private downloads automatically.
- Implement scheduled/live classes, recordings, attendance, playback resume or related media flows only to the extent that current backend functionality supports them. Document gaps rather than mocking functionality.

### Parent experience
- Implement parent dashboard, child selection/management and learning summaries supported by current APIs.
- Respect guardian links and authorization; never expose another family's children, responses or activity.
- Give accurate progress context without overstating mastery or generating fake reports.

### Profile, settings and help
- Support profile information, language preference, session/logout controls, permitted notification preferences, downloads/storage management and privacy/help content as available.
- Provide clear app version, connectivity, sync/download status and recoverable error messages.

## 6. Offline, storage and privacy model

- Separate server-sourced, local-only and synced states visibly.
- Use an outbox with stable operation IDs, retry/backoff and idempotent server calls where supported. Never pretend a failed server write succeeded.
- Persist a session or practice draft locally only when policy allows; encrypt sensitive local records. Clear user-bound cached data when required on logout/account switch.
- Avoid backups of protected/offline assessment packages and protected resource files.
- Do not upload local files, downloaded books, screenshots or assessment packages to analytics or AI systems.
- Consider minors and India privacy obligations in data minimization, consent, retention and deletion; legal review is required for final policy claims.
- Request only essential Android permissions and state why they are needed.
- Security controls reduce casual copying; they are not a guarantee against external cameras, compromised devices or every operating-system capture path.

## 7. Testing requirements

Build a real test suite, not only screenshots. Include unit tests for state/business rules, repository/API contract tests, serialization/error tests, local database/encryption/sync tests, Compose UI tests for critical screens and automated end-to-end journeys using controlled test accounts/backend fixtures where available.

Minimum end-to-end journeys:
1. Sign in → load actual student home → switch language/class/subject within allowed scope.
2. Open lesson → render ordered blocks → answer an inline question → navigate away/back → verify saved state.
3. Start practice → answer/submit → handle correct, incorrect and ungraded/manual-review states → leave/relaunch → resume same session and question snapshot.
4. Lose connectivity before/after submit → confirm truthful save/sync status → reconnect → verify idempotent synchronization.
5. Download an entitled resource → interrupt/resume → open offline in app → verify it is not present in public Downloads or an export/share picker.
6. Start a policy-eligible offline assessment → persist encrypted answer → reconnect → sync once → verify server grading and attempt policy.
7. Verify forbidden access (wrong class, unsubscribed subject, suspended account, another family) is denied by the server and presented cleanly by the app.
8. Open progress/results → verify they agree with API values and policy.

Record device/emulator, Android version, backend environment, test commands and result. Distinguish tests run locally from tests that require the owner's own device, credentials or deployed backend.

## 8. Build and delivery

- Add reproducible Gradle configuration, dependency/version management, app icons/branding, network security config, manifest, safe debug/release configuration and documented build/run instructions.
- Provide a debug APK for installation/testing; prepare a release AAB configuration when signing/distribution details are available. Signing credentials remain outside GitHub.
- Add a GitHub Actions workflow for compile, static checks and automated tests that can run without production secrets.
- Deliver a feature matrix mapping each requested capability to screens, API routes, persistence/sync model and test coverage.
- Track API/backend gaps and deferred features explicitly. Do not silently fake unfinished capabilities.
- After every substantial milestone, commit the work and update this specification or a committed progress log. At handoff, include commit SHA(s), build artifacts/links, exact test outcomes, known limits and the next concrete verification action.

## 9. Definition of done

The app is ready for user testing only when it builds reproducibly, core flows use real APIs, state survives navigation/restart and relevant offline transitions, protected downloads remain app-private, answer/result semantics match the backend, and the automated critical journeys pass. Production release additionally requires real-device verification, release signing, privacy/legal review and owner approval.
