# Web–Android UI Parity Implementation Tracker

**Scope:** all 62 recommended decisions in the approved `QuantaEdge_Web_Android_Behaviour_Decision_Matrix.xlsx`. The chosen recommendation is accepted for every row; platform-specific native layout is retained where approved.

**Source of truth:** this tracker lives in `main` and should be updated in the same change that completes each implementation item. The Excel decision matrix remains the detailed behavior/acceptance specification. Do not mark E2E as passed from source inspection or compilation alone.

## Status rules

- **Done — code on main:** implementation or required contract is present on `main`; this does not mean every live account/device scenario has been manually verified.
- **Partial:** implementation is present but the matrix requirement still needs broader coverage.
- **Open:** no implementation found yet.
- **E2E status:** only mark passed with a recorded test account/environment, platform, steps/result and timestamp.

## Current status

| ID | Page / behavior | Implementation status | Verification status |
|---|---|---|---|
| PUB-01 | Landing / welcome | Done — code on main | CI build + real-account/browser/device E2E pending |
| PUB-02 | Landing / brand | Done — code on main | CI build + real-account/browser/device E2E pending |
| PUB-03 | Landing / language | Done — code on main | CI build + real-account/browser/device E2E pending |
| PUB-04 | Landing / legal links | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-01 | Role choice / login entry | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-02 | Student login | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-03 | Parent password login | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-04 | Parent OTP login | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-05 | Parent signup | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-06 | Forgot / reset password | Done — code on main | CI build + real-account/browser/device E2E pending |
| AUTH-07 | Session / role routing | Done — backend/workspace contract on main | CI build + real-account/browser/device E2E pending |
| NAV-01 | Student primary navigation | Done — source contract + tests on main | CI build + real-account/browser/device E2E pending |
| NAV-02 | Parent primary navigation | Done — source contract + tests on main | CI build + real-account/browser/device E2E pending |
| NAV-03 | Portrait / landscape navigation | Done — source contract + tests on main | CI build + real-account/browser/device E2E pending |
| NAV-04 | Header / profile / language | Done — shared shell on main | CI build + real-account/browser/device E2E pending |
| NAV-05 | Logout placement | Done — shared logout flow on main | CI build + real-account/browser/device E2E pending |
| DASH-01 | Student home hierarchy | Done — dashboard hierarchy on main | CI build + real-account/browser/device E2E pending |
| DASH-02 | Subject cards / chapter counts | Done — subject/resource shortcuts on main | CI build + real-account/browser/device E2E pending |
| DASH-03 | Independent course discovery / enrolment | Done — course catalogue flow on main | CI build + real-account/browser/device E2E pending |
| DASH-04 | Recommendation and next step | Done — recommendation flow on main | CI build + real-account/browser/device E2E pending |
| LEARN-01 | Subject and lesson list | Done — source on main | CI build + real-account/browser/device E2E pending |
| LEARN-02 | Lesson blocks and media | Done — shared content renderer/API flow on main | CI build + real-account/browser/device E2E pending |
| LEARN-03 | Inline lesson question and feedback | Done — answer-state policy/tests on main | CI build + real-account/browser/device E2E pending |
| LEARN-04 | AI Tutor | Done — web-aligned tutor structure, five modes, rich responses, new chat and paged history on main | CI build + real-account/browser/device E2E pending |
| LEARN-05 | Live classes | Done — class access/attendance flow on main | CI build + real-account/browser/device E2E pending |
| LEARN-06 | Recorded classes / playback | Done — protected recording player on main | CI build + real-account/browser/device E2E pending |
| PRAC-01 | Topic practice | Done — single-question practice flow on main | CI build + real-account/browser/device E2E pending |
| PRAC-02 | Practice question availability | Done — topic-scoped approved practice flow on main | CI build + real-account/browser/device E2E pending |
| PRAC-03 | Initial diagnostic | Done — diagnostic flow on main | CI build + real-account/browser/device E2E pending |
| TEST-01 | Test catalogue and filters | Done — assessment catalogue on main | CI build + real-account/browser/device E2E pending |
| TEST-02 | Active formal test screen | Done — question navigation on main | CI build + real-account/browser/device E2E pending |
| TEST-03 | Answer autosave and retry | Done — answer persistence/offline state on main | CI build + real-account/browser/device E2E pending |
| TEST-04 | Review before submitting | Done — confirmation flow on main | CI build + real-account/browser/device E2E pending |
| TEST-05 | Test submission / timed expiry | Done — deadline/submit behavior on main | CI build + real-account/browser/device E2E pending |
| TEST-06 | Awaiting review | Done — pending review state on main | CI build + real-account/browser/device E2E pending |
| TEST-07 | Released test result and answer review | Done — released attempt review on main | CI build + real-account/browser/device E2E pending |
| TEST-08 | Leaderboard | Done — leaderboard grouped with tests on main | CI build + real-account/browser/device E2E pending |
| PROG-01 | Progress and mastery dashboard | Done — server-backed progress on main | CI build + real-account/browser/device E2E pending |
| CERT-01 | Certificate centre and eligibility | Done — canonical student+attempt lookup fallback and newest certificate selection for legacy issued records; issue/reopen now routes to the actual certificate | CI build + real-account/browser/device E2E pending |
| CERT-02 | Certificate preview / PDF / PNG download | Done — web issue routes to canonical certificate viewer; Android confirmed issue opens native PDF save flow; PDF/PNG artifact access remains student-scoped | CI build + real-account/browser/device E2E pending |
| CERT-03 | QR / barcode / manual ID verification | Done — verifier + Android scanner code on main | CI build + real-account/browser/device E2E pending |
| TEXT-01 | Book/chapter discovery | Done — complete books/book parts first, expandable chapter PDFs in API order, and 10/20/30/50 pagination on main | CI build + real-account/browser/device E2E pending |
| TEXT-02 | Secure reader controls | Done — Android page jump, previous/next, zoom, fit page/width, rendered thumbnails, fullscreen and page retry on main | CI build + real-account/browser/device E2E pending |
| TEXT-03 | Offline textbook downloads | Done — Android protected offline add-on on main | CI build + real-account/browser/device E2E pending |
| CLASS-01 | Live/recorded class listing | Done — API-backed class/recording catalogue on main | CI build + real-account/browser/device E2E pending |
| CLASS-02 | Meeting join / leave and attendance | Done — server attendance join/leave on main | CI build + real-account/browser/device E2E pending |
| PARENT-01 | Family dashboard and child selection | Done — child-scoped report on main | CI build + real-account/browser/device E2E pending |
| PARENT-02 | Create child profile | Done — backend-supported child creation/consent on main | CI build + real-account/browser/device E2E pending |
| PARENT-03 | Edit child profile | Done — child profile editing on main | CI build + real-account/browser/device E2E pending |
| PARENT-04 | Child credentials and subject tracks | Done — credentials and tracks management on main | CI build + real-account/browser/device E2E pending |
| PARENT-05 | Archive / restore child | Done — explicit fresh-consent guard added in this pass | CI build + real-account/browser/device E2E pending |
| PARENT-06 | Parent profile and photo | Done — parent profile/photo flow on main | CI build + real-account/browser/device E2E pending |
| STU-01 | Student profile and photo | Done — student profile/account lifecycle on main | CI build + real-account/browser/device E2E pending |
| LANG-01 | App-interface language | Partial — Android CI now checks literal localizedUiText strings and literal EmptyState title/help pairs; full visible-string sweep remains | CI build + real-account/browser/device E2E pending |
| LANG-02 | Learning-content language | Done — independent content-language preference/request flow on main | CI build + real-account/browser/device E2E pending |
| LANG-03 | Certificate language | Done — independent certificate language on main | CI build + real-account/browser/device E2E pending |
| SESS-01 | Session timeout and resume | Done — server policy and client boundaries on main | CI build + real-account/browser/device E2E pending |
| SESS-02 | Explicit logout | Done — logout/revocation flow on main | CI build + real-account/browser/device E2E pending |
| NATIVE-01 | Offline formal assessments | Done — protected offline formal assessment on main | CI build + real-account/browser/device E2E pending |
| NATIVE-02 | Camera scanner | Done — native QR/barcode scanner code on main | CI build + real-account/browser/device E2E pending |
| NATIVE-03 | Native save/share and protected document controls | Done — native certificate save and protected documents on main | CI build + real-account/browser/device E2E pending |
| UI-01 | Loading, empty, error and success states | Partial — protected library, certificates, classes and leaderboard now show loading/error/retry/empty feedback; remaining API-backed pages need the same page-by-page audit | CI build + real-account/browser/device E2E pending |

Implementation partials still open:
- **LANG-01:** finish a screen-by-screen audit of visible web and Android UI strings. Fix untranslated hardcoded labels, errors, dialogs and loading/empty states while keeping Hindi in Devanagari-based Hinglish.
- **UI-01:** verify a consistent loading, empty, permission-denied, stale-session, recoverable-error/retry and confirmed-success state on every API-backed page. Success must follow server/file-save acknowledgement.

## Changes made in this pass

| Item | Change | Commit |
|---|---|---|
| PARENT-05 | Android archived-child restore now requires the guardian to actively tick a fresh-consent checkbox before the restore action enables. | `6d50d4b3bf712e07a4a7006edfc1a322e1b0626e` |
| PARENT-05 / LANG-01 | Added Hindi/English localization phrases for the archived-child restore explanation, explicit consent, archived list and empty state. | `fcb97bfa17cb435598edb75eb3db4f3dbaea8429` |
| PARENT-05 | Added the Compose layout import needed by the guarded consent row. | `910a00d513818dc35f538b4de9c4cf39187bb377` |
| LEARN-04 | Android TutorScreen follows the web tutor hierarchy: branded title/AI badge, independent learning-language indicator, five icon modes, chat bubbles/timestamps, rich message formatting, loading/unavailable/retry states, new chat, older history, composer and localized tutor errors. Android uses a full-screen native layout for its mobile form factor. | `9663381dcbbc211316e15facd7f548eac2d8f839`; localized errors: `1ed2d7f202439f55163a144eac12186da2b2cea3` |
| LEARN-04 | Android uses the existing tutor APIs for new-chat creation and cursor-based older-message retrieval, preserves scroll position when older messages are prepended, and reconciles slow-model network errors against persisted history for up to the web-equivalent recovery window. UI content language remains separate from app-interface language. | `637599910ce19f9ecce86c993f437dd2738f849c`; recovery: `e3a7bb2cc07d6a1fb0762fd7c768f45b9d467d22`; scroll: `ec07c2f42efec745bedb950490234551b53433b7` |
| LEARN-04 | Added a structured tutor response renderer and unit tests for headings, lists, tables, code fences, equations and inline markers. | `4655dee01ead32e5f3b18f62b42f902c67bf45df`; tests: `0d699b60cb95a4445292a85534f03508dcb99279` |
| LANG-01 | Added bilingual labels for tutor controls and connected a localization coverage check to Android CI. Runtime translation and accessibility QA remain required. | `1863a403b4c9c6f45969a3d7eef9ebe39f220010`; CI check: `8a061466a85f8e2f787e8de0ba3c7252f3424ce6` |
| LANG-01 | Extended Android localization CI to audit literal EmptyState title/detail pairs against shared Hindi/English UiPhrase mappings, in addition to localizedUiText calls. | `scripts/check-android-ui-localization.mjs` |
| UI-01 | Added explicit loading/error/retry feedback to classes, leaderboard and certificate data flows; certificate errors now include Retry and messages respect the selected app language. | `LearningClassesScreen.kt`, `AssessmentLeaderboardScreen.kt`, `StudentCertificatesScreen.kt` |
| LANG-01 | Localized the Android account-eligibility headings and humanized known backend status codes for adult verification and parent eligibility; unknown statuses remain readable instead of exposing enum underscores. | `e1b81d600b2ba776d71c8448dd1dc93f935e3fea` |
| CERT-01 / CERT-02 | Certificate picker/eligibility select one canonical record per attempt. Issue/reopen resolves legacy achievement keys, validates the returned issued ID/status, routes web to `?certificateId=...`, and opens Android native PDF save after successful issuance. | Backend: `6b89b1f1f80a7d96ba786d2637b07ef678d1c89a`; regression fixtures: `6e8fb2c2d5807701049333f8a7154c41d05e388c`; client flow: pending batch commit |
| TEXT-02 | Protected Android reader gains fullscreen, fit width, page thumbnails, forced page retry and safe cache eviction; source PDF export/share remains blocked. | `ff12d697b7d861c9559f5182fb820b03fd95c1d1`; build fix + nav contract: `abc6486e9e9c888387755b1eae420c20d0bf746f` |
| TEXT-01 | Android resource library groups complete subject books/book parts before expandable chapter PDFs; preserves API curriculum order and adds 10/20/30/50 chapter pagination and retry/empty states. | Pending batch commit |

These changes preserve the existing guardian restore endpoint and payload. The API still validates consent and controls account restoration; the client does not grant permissions or restore a profile locally.

## End-to-end regression checklist

The source matrix's 30 E2E scenarios are retained here as a durable run log. They remain not run until real browser/device/backend evidence is entered. Update this table as testing is performed.

| Test ID | Journey | Scenario | Status / evidence |
|---|---|---|---|
| E2E-01 | Public landing | Welcome, role CTAs, language and legal links | Not run in this environment — evidence required |
| E2E-02 | Student authentication | Valid/invalid student login and correct post-login screen | Not run in this environment — evidence required |
| E2E-03 | Parent authentication | Password and OTP login, role/workspace confirmation | Not run in this environment — evidence required |
| E2E-04 | Authentication | Signup/reset OTP purpose, password rules and navigation | Not run in this environment — evidence required |
| E2E-05 | Security | Direct URL access and parent/student isolation | Not run in this environment — evidence required |
| E2E-06 | Session | Refresh, back, reload, inactivity and explicit logout | Not run in this environment — evidence required |
| E2E-07 | Today | Progress, recommendations and destinations | Not run in this environment — evidence required |
| E2E-08 | Courses | Catalogue, enrolment and persistence | Not run in this environment — evidence required |
| E2E-09 | Learning | Subject → topic → lesson ordering and return path | Not run in this environment — evidence required |
| E2E-10 | Learning | Inline answers, language separation, tutor modes, rich responses, new chat and history | Not run in this environment — evidence required |
| E2E-11 | Practice | Approved topic questions, retries and summary | Not run in this environment — evidence required |
| E2E-12 | Diagnostic | Due/not-due/completed states | Not run in this environment — evidence required |
| E2E-13 | Formal tests | Timer, question navigation, rotation and restore | Not run in this environment — evidence required |
| E2E-14 | Formal tests | Submit confirmation, unanswered count, idempotency and pending state | Not run in this environment — evidence required |
| E2E-15 | Results | Released marks, feedback and explanation | Not run in this environment — evidence required |
| E2E-16 | Certificates | New/existing issue flow and canonical certificate ID | Not run in this environment — evidence required |
| E2E-17 | Certificates | PDF/PNG saves and opens correctly | Not run in this environment — evidence required |
| E2E-18 | Verification | Valid/revoked/invalid/missing ID and native scanner | Not run in this environment — evidence required |
| E2E-19 | Textbooks | Correct book/chapter/language and reader origin | Not run in this environment — evidence required |
| E2E-20 | Textbooks | Page jump, zoom/fit, thumbnails, fullscreen and retry | Not run in this environment — evidence required |
| E2E-21 | Android offline | Encrypted download/read/remove and account scoping | Not run in this environment — evidence required |
| E2E-22 | Classes | Join/leave attendance and playback resume | Not run in this environment — evidence required |
| E2E-23 | Parent | Child creation, consent, credentials and student login | Not run in this environment — evidence required |
| E2E-24 | Parent | Edit/archive/restore, fresh consent and credential reset | Not run in this environment — evidence required |
| E2E-25 | Parent | Selected-child metrics and certificate scope | Not run in this environment — evidence required |
| E2E-26 | Profile | Photo save/remove, fallback avatar and refresh persistence | Not run in this environment — evidence required |
| E2E-27 | Language | UI/content/certificate preference combinations | Not run in this environment — evidence required |
| E2E-28 | Responsive | Web mobile/desktop and Android portrait/landscape | Not run in this environment — evidence required |
| E2E-29 | Logout | Server revocation, back-navigation denial and new sign-in | Not run in this environment — evidence required |
| E2E-30 | Accessibility | Keyboard, TalkBack, touch targets, contrast and Devanagari scaling | Not run in this environment — evidence required |

## CI / manual verification boundary

The most recent green baseline before the current library hierarchy change is [run 38753976983](https://github.com/Manav326/QuantaEdge/actions/runs/38753976983) on commit `abc6486e9e9c888387755b1eae420c20d0bf746f` (success: Android build/tests, web navigation contract, API verification and image builds). The library hierarchy change below needs its own CI pass. CI cannot by itself prove real account entitlement, actual saved files, camera permission handling, device rotation, accessibility, or cross-platform data parity.

## Core constraints

- Backend remains authoritative for role/workspace, identity, access, grading, progress, enrolment, consent, attendance, test deadline, certificate status and language-specific resource availability.
- Web is the content-completeness/information-hierarchy reference; Android may use native Compose controls and approved Android-only offline/scanner/file actions.
- Student and Parent sessions stay separate. No impersonation or client-only authorization.
- App UI language, learning-content language and certificate language stay independent.
- Retain encrypted account-scoped offline storage and server-side grading; never expose protected textbook PDF export/print merely for parity.
