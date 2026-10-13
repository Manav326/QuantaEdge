# Web–Android Route Parity Matrix

**Baseline:** QuantaEdge `main` at `54f99cf12a2aa95bfd891e25488a3e66bd19498c` after PR #52. This is a route ownership and preservation inventory, not a claim that native/web rendering is pixel-identical.

## Student navigation ownership

| Web destination | Canonical group | Android routes/screens | Important flow and preservation rule | Verification |
|---|---|---|---|---|
| `/student` | Today (`today`) | `STUDENT_HOME` | Recommendation dashboard; any due one-time diagnostic remains accessible from its due/recommendation flow. | Android contract test: Today maps to `today`; web contract checks `/student`. |
| `/student/learn` | Learn (`learn`) | `SUBJECT_LESSONS`, `LESSON` | Subject → topic/lesson → learning blocks/questions; preserve lesson return route and in-progress answer state. | Android route-owner unit tests; web route contract. |
| Lesson-context tutor | Learn (`learn`) | `TUTOR` | Tutor remains inside lesson context; a missing tutor configuration is a visible availability state, not a different primary destination. | Android route-owner unit tests; manual bilingual tutor journey still required. |
| `/student/classes` | Learn (`learn`) | `CLASSES`, `RECORDING_PLAYER` | Live-class access is server-checked; preserve attendance, join/leave, playback resume and completion state. | Android route-owner unit tests; web build; session end-to-end/manual checks remain part of regression. |
| `/student/practice` | Practice & Tests (`practice`) | `PRACTICE` | Practice stays separate from a formal admin-created assessment and is not certificate-eligible by implication. | Android route-owner unit tests; web contract. |
| `/student/tests` | Practice & Tests (`practice`) | `ASSESSMENTS`, `ASSESSMENT_ATTEMPT`, `ASSESSMENT_RESULTS`, `ASSESSMENT_REVIEW` | Preserve server grading, answer save/submission, timer, released-vs-pending status and result review. Offline Android attempt creation/sync remains an extension. | Android route-owner unit tests; workflow CI; detailed end-to-end state cases remain in the manual matrix. |
| `/student/diagnostic` | Practice & Tests (`practice`) | `DIAGNOSTIC` | Primary group link is surfaced only when recommendation says it is due; direct/deep-linked route remains available. | Web checker asserts group ownership; Android route-owner tests; conditional visibility should be manually tested. |
| Leaderboard | Practice & Tests (`practice`) | `LEADERBOARD` | Top-20 remains nested within the formal tests/results journey; existing server score/time ranking remains authoritative. | Android route-owner test; scoring/ranking E2E still needs a live-data/manual check. |
| `/student/progress` | Progress & Certificates (`progress`) | `PROGRESS` | Progress and mastery use current backend records; avoid computing a divergent second score on the client. | Android route-owner test; business-logic regression remains server-owned. |
| `/student/certificates` | Progress & Certificates (`progress`) | `CERTIFICATES` | Certificate centre, eligibility status, existing/revoked certificate handling, PDF/PNG, independent certificate language and native verification remain intact. | Android route-owner test; CI compiles/test-runs native scanner code; issuance scenario requires eligible account E2E. |
| `/student/textbooks` | Textbook Library (`library`) | `RESOURCES`, `DOCUMENT_READER` | Book → chapter → secured page reader; requested language must match the chosen document language or give an explicit localized unavailable message. | Android route-owner test; protected file paths/cache must remain Android-only extras. |
| `/student/profile` | Account utility (not primary nav) | `PROFILE` | Student profile/settings; return selection remembers the prior student group. | Android route-owner test; web checker asserts profile is absent from primary nav. |

## Parent and authentication ownership

| Web destination | Parent group | Android route/screen | Rule |
|---|---|---|---|
| `/parent` | Family report (`family`) | `PARENT_HOME`, `PARENT_REPORT` | Parent can view children’s reports but must not impersonate the student or submit child assessments. The selected family/children subsection remains remembered. |
| `/parent/children` | Children’s profiles (`children`) | `PARENT_CHILD_CREATE`, `PARENT_CHILD_MANAGE` | Parent-owned credential creation/reset and subject access remain parent-only. |
| `/parent/profile` | Parent profile (`profile`) | `PARENT_PROFILE` | Parent identity/profile settings stay out of student destinations. |
| Login/signup/reset | Not a primary group | `LOGIN`, `PARENT_SIGNUP`, `PARENT_RESET` | These are explicit navigation exceptions; unauthenticated users do not see authenticated navigation. |

## Platform-specific features that must not be removed

- Android offline assessments remain protected by `OfflineAssessmentStore`, with `OfflineAssessmentSyncWorker` responsible for sync; regrouping never replaces server grading or relaxes encrypted offline state controls.
- Android protected offline resources/documents remain backed by `ProtectedResourceStore` and `ProtectedResourceDownloadWorker`; online textbook grouping must not replace secured page rendering or local resource management.
- Android certificate QR/barcode scanning and native save/share/chooser behaviour remain native-only capabilities.
- Public web/legal/landing/certificate-verification routes do not need artificial student navigation entries on Android. Admin and People Operations remain outside this cross-client student/parent navigation contract.

## Language acceptance matrix

For every applicable screen/request, execute all four independent preference combinations. App UI controls must follow App Language; lesson/questions/practice/textbook request language follows Learning Content Language; certificate generation/preview follows Certificate Language.

| App UI | Learning content | Certificate | Expected |
|---|---|---|---|
| Hindi | Hindi | Hindi | Hindi shell; Hindi content if published; Hindi certificate |
| Hindi | English | Hindi | Hindi shell; English content if published; Hindi certificate |
| English | Hindi | English | English shell; Hindi content if published; English certificate |
| English | English | Hindi | English shell; English content if published; Hindi certificate |

Missing translated content or missing language-specific PDFs must show an explicit localized message; a fallback must never be silently presented as the requested language.

## Device/orientation regression matrix

| Device state | Expected primary navigation | State to preserve |
|---|---|---|
| Portrait phone | Five-item bottom bar | Current route and nested destination |
| Large portrait phone/tablet | Five-item bottom bar (orientation takes precedence over width) | Current route and scroll position |
| Landscape phone | Left navigation rail | Current lesson/answer/attempt/reader/player state |
| Landscape tablet | Left navigation rail | Current lesson/answer/attempt/reader/player state |
| Rotation during active test or reader/player | Placement changes; destination and saved in-memory work remain | Selected answers, timer/attempt, page, tutor context, attendance/playback position |

**Remaining manual QA:** the repository CI can compile, run unit tests and verify source contracts, but it cannot prove device rotation, TalkBack/keyboard usability, authenticated certificate issuance, or real live-class/recording behaviour. Execute those against test accounts/devices before declaring every manual regression row complete.
