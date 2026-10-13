# Web–Android Navigation and Experience Unification — Actionable Plan

**Status:** Planning baseline created; implementation not started  
**Baseline branch:** \`main\`  
**Baseline commit reviewed:** \`4e63e3ce8a4be6c7fc16b585252f54f8869c704f\`  
**Plan branch:** \`plan/web-android-navigation-unification\`  
**Scope:** Student/user web and Android app, with parent navigation alignment and shared-flow parity.  
**Non-goal:** Do not redesign or remove Android-only offline assessment, protected offline document/PDF, scanner, native-save, or platform security functionality.

## Product decisions that must not drift

1. **Use the five student groups already present in web, in the same order and with the same route ownership.** Do not create a new taxonomy.
2. **Android portrait:** five primary destinations at the bottom.
3. **Android landscape:** the same five destinations in a left navigation rail.
4. **Use device orientation, not a 600dp width threshold, to select bottom versus left navigation.** Rotation must not reset the current workflow or its state.
5. **Exactly five primary student navigation items.** Profile, language switching and Logout are utilities, not sixth/seventh navigation destinations. Do not add separate primary icons for Tests, Results, Certificates, Leaderboard, Profile, Classes or Tutor.
6. **Keep the five-group web layout as the source reference.** Make any small web corrections needed for parity—for example, mobile Logout must remain accessible but must not become an extra primary navigation item.
7. **Only primary groups receive primary navigation icons.** Nested routes should be presented as simple, readable text actions or restrained subitems, not a second icon-heavy navigation layer.
8. **Parent navigation remains role-specific.** Align to the three existing web destinations (Family Report, Children’s Profiles, Parent Profile); do not force the student five-group model on parents.
9. **App language, Learning Content Language and Certificate Language are separate preferences.** Hindi/English coverage is non-negotiable across both platforms.
10. Preserve existing route names, API contracts, ownership checks, server-side assessment/certificate rules, records and valid deep links unless a separately reviewed change is required.

## Canonical student navigation contract

| Order | Group ID | Bilingual meaning | Canonical routes / destinations |
|---:|---|---|---|
| 1 | \`today\` | Today / आज की योजना | \`/student\`; recommendations and continue-learning entry points |
| 2 | \`learn\` | Learn / पढ़ाई | \`/student/learn\` (lessons/topics), \`/student/classes\` (live/recorded classes); contextual tutor access; online learning resources |
| 3 | \`practice\` | Practice & Tests / अभ्यास और टेस्ट | \`/student/practice\`, \`/student/tests\`; \`/student/diagnostic\` only when due, while preserving valid direct entry paths |
| 4 | \`progress\` | Progress & Certificates / प्रगति और प्रमाणपत्र | \`/student/progress\`, \`/student/certificates\`; certificate status and relevant result-linked actions |
| 5 | \`library\` | Textbook Library / किताबें | \`/student/textbooks\`; book/chapter discovery and reader/library actions |

- The Top-20 leaderboard is reached from the Tests & Results experience; it is not a sixth primary destination.
- Certificate actions remain discoverable from qualifying results and from the certificate centre. A result-linked action must preserve the result/attempt identity.
- The public \`/certificate/verify\` page stays a contextual/public web destination, not a student navigation item.
- Student Profile & Settings is opened from the account/avatar menu.
- App language remains available in the persistent top bar; Learning Content Language is configured explicitly in settings.
- Logout remains easy to find in the account menu/utilities area. It does not consume a primary navigation slot.
- The desktop web sidebar may retain its utility footer; its five primary groups remain the same.
- Existing role restrictions and current conditional availability rules (such as the initial diagnostic being offered only when due) must be preserved.

## Phase 0 — Capture baseline and establish safety checks

- [ ] Record the exact base SHA and current CI/workflow status before code changes.
- [ ] Run or inspect the existing web navigation contract check and identify any baseline failure separately from introduced failures.
- [ ] Record current web package scripts, Android Gradle commands and available test commands.
- [ ] Make a route inventory for all student and parent screens, account actions, query parameters, deep links and return paths.
- [ ] Record Android-only feature entry points and their current acceptance tests.
- [ ] Confirm no change is needed to admin, HRMS or unrelated backend features.
- [ ] Create a feature branch from the latest \`main\`; never implement this redesign directly on \`main\`.

**Exit gate:** the baseline and known pre-existing failures are documented; every existing route/action has an owner.

## Phase 1 — Complete the parity matrix before changing navigation

- [ ] Compare every student web route with its Android \`AppRoute\` or screen implementation.
- [ ] For each capability record: web entry point, Android entry point, API/action, expected status/state, role, content-language behaviour and verification test.
- [ ] Explicitly mark these as Android-only and protected from removal: encrypted offline assessment attempts, background attempt synchronization, protected offline PDF/document page cache, Android document save flow, camera QR/barcode scanning and native security protections.
- [ ] Identify web-only/public capabilities that do not need forced native equivalents, including the landing/legal pages and public certificate-verification route.
- [ ] Identify functional gaps separately from presentation/discoverability differences; do not assume a route missing from primary navigation is missing functionality.
- [ ] Confirm common assessment, result and certificate eligibility logic remains server-authoritative.
- [ ] Add acceptance criteria for every parity gap before implementing its fix.

**Exit gate:** no unclassified feature remains; every intentional platform exception is documented.

## Phase 2 — Add navigation contract checks

- [ ] Define stable group IDs \`today\`, \`learn\`, \`practice\`, \`progress\`, \`library\` in the Android navigation model.
- [ ] Keep the web and Android labels localized but use identical group identities, order and route ownership.
- [ ] Add a focused automated contract check that asserts exactly five student groups in web and Android, in the agreed order.
- [ ] Test group membership for all canonical student routes, including classes, diagnostics, certificates, textbooks and results.
- [ ] Assert Profile, Logout and Language are not student primary destinations.
- [ ] Assert leaderboard remains nested in the Tests & Results journey and does not create a sixth item.
- [ ] Keep a route-to-group mapping that can be tested independently of Compose rendering.
- [ ] Fail validation when a new route is added without a documented group/exception.

**Exit gate:** the navigation contract detects both excess primary items and lost/unmapped routes.

## Phase 3 — Refactor Android primary navigation

- [ ] Replace the Android Home/Learn/Tests/Results/Profile student item list with the exact five canonical groups.
- [ ] Make Today navigate to student home/recommendations.
- [ ] Make Learn expose Lessons & Topics and Live & Recorded Classes; retain contextual tutor actions inside learning.
- [ ] Make Practice & Tests expose Practice, Tests & Results and Diagnostic only when it is currently due; preserve valid direct/deep-linked diagnostic handling.
- [ ] Make Progress & Certificates expose progress, mastery and certificates.
- [ ] Make Textbook Library open book/resource discovery and preserve document-reader flows.
- [ ] Place the leaderboard inside the Tests & Results destination/flow with its existing API and ranking criteria.
- [ ] Remove separate home-header certificate and leaderboard shortcut rows when their functionality is accessible from the agreed destination. Keep contextual result-linked certificate actions.
- [ ] Keep Profile & Settings accessible from the account/avatar menu, not the primary navigation.
- [ ] Move Logout to an account menu or dedicated account utility action, not a sixth primary item.
- [ ] Preserve every existing \`AppRoute\`; do not delete a screen just because its navigation entry moves.
- [ ] Implement one tested route-to-group function, so detail routes highlight the correct group and tapping a group opens its expected destination.
- [ ] Ensure group switches clear stale presentation errors only where safe; do not clear answers, attempts, reader position or other work state.

**Exit gate:** exactly five primary items exist; every existing student feature remains reachable and correctly grouped.

## Phase 4 — Implement orientation-responsive Android layout

- [ ] Read the active Android configuration orientation (portrait/landscape) instead of using \`screenWidthDp >= 600\` as the navigation selector.
- [ ] In portrait, render exactly five items in a bottom navigation bar, including on large portrait phones/tablets.
- [ ] In landscape, render the same five items in a left navigation rail, including on narrower landscape devices.
- [ ] Treat undefined/transitioning orientation safely and avoid a blank or duplicated navigation bar during configuration changes.
- [ ] Keep the same order, group labels, icons, active state and route-selection behaviour in both layouts.
- [ ] When a group contains child routes, open an accessible, appropriately placed submenu/sheet; keep nested actions text-first with no competing row of feature icons.
- [ ] Place Logout, Profile and app-language controls outside the five-item primary bar/rail.
- [ ] Preserve safe-area insets, keyboard/IME handling, scrolling, readable Hindi labels and touch-target dimensions.
- [ ] Ensure screen rotation does not recreate or discard an active test, unsaved answer, lesson state, current book/page, tutor conversation or recording position.
- [ ] Ensure selecting the active group can return to its root/remembered subsection consistently rather than always opening an unrelated screen.
- [ ] Add UI/manual test cases for rotation while on Home, nested lesson, Practice, an in-progress assessment, Results, Textbook Library/reader and a recording player.

**Exit gate:** portrait always uses bottom navigation; landscape always uses left navigation; the active workflow survives rotation.

## Phase 5 — Bring the web navigation fully into the same contract

- [ ] Keep the five existing web groups and their canonical order; do not regress the recent web grouping work.
- [ ] Keep mobile web's primary bottom navigation to exactly those five destinations. Remove the separate Logout item from the primary bar and expose Logout through the account menu/utility surface.
- [ ] Keep desktop Logout accessible in the sidebar utility area if desired, without treating it as a primary destination.
- [ ] Reduce excessive nested navigation icons: primary group icons remain; nested links use text-first presentation or restrained indicators.
- [ ] Ensure mobile group sheets/submenus never obscure the selected action, stay within safe areas, and close predictably after navigation, Escape or backdrop dismissal.
- [ ] Check that deep links auto-open the correct group and that active child links remain obvious.
- [ ] Keep Profile, Language and Logout accessible without introducing a sixth student destination.
- [ ] Preserve the parent menu's existing three routes and role-specific content.

**Exit gate:** web and Android have the same five student groups and the mobile navigation no longer presents Logout as an extra primary icon.

## Phase 6 — Align the shared feature journeys

Implement and verify in this order:

### 6.1 Learning and lessons
- [ ] Align subject → chapter/topic → lesson → content blocks → lesson questions → next step.
- [ ] Preserve resume/return-to-origin behaviour and query parameters.
- [ ] Keep tutor access in lesson context and align available tutor modes/messages/errors.
- [ ] Align online textbook/resource entry points without removing Android's protected offline extras.

### 6.2 Practice and diagnostic
- [ ] Align practice start, topic identity, saved answers, graded/ungraded feedback, retry rules and completion state.
- [ ] Align the one-time diagnostic entry/availability logic and its recommended next step.
- [ ] Keep practice separate from formal assessments; never imply ordinary practice qualifies for an admin-issued certificate.

### 6.3 Formal tests and results
- [ ] Align available tests, subject filtering, pagination, page-size choices where supported, attempt status and empty/loading/error states.
- [ ] Align timer display, answer autosave, submission, awaiting-review state, released result and answer-review presentation.
- [ ] Preserve Android offline-attempt creation, encryption, queued synchronization and server grading as Android-only extensions.
- [ ] Keep the leaderboard available within Tests & Results and retain server-authoritative ranking/score/time criteria.

### 6.4 Progress and certificates
- [ ] Align progress metrics, mastery definitions, subject completion and concepts needing support.
- [ ] Align certificate centre, eligibility reasons, issue/retrieve flows, template choice, language choice and PDF/PNG actions.
- [ ] Use the same backend authority for issuance and revoked/issued status on both platforms.
- [ ] Ensure certificates are visible from both the certificate centre and the relevant qualifying result where appropriate.
- [ ] Preserve Android's scanning and native save/chooser experience.

### 6.5 Textbook Library and classes
- [ ] Align book/resource discovery, class/subject filters, language availability, reader entry and return behaviour.
- [ ] Show a clear localized explanation when the chosen-language PDF is unavailable; never silently label a fallback as the requested language.
- [ ] Align live/recorded tabs, subject filters, session status, join access checks, leave/attendance confirmation and recording availability.
- [ ] Align playback resume position and watched/completed state.
- [ ] Preserve protected, encrypted offline document downloads and local-copy management on Android only.

### 6.6 Student/parent account flows
- [ ] Align student profile fields, photo upload/remove, save/cancel and settings.
- [ ] Align parent family report, child selection, child profile creation/management, student credential setup/reset and subject access.
- [ ] Keep parent report access read-only; only the child account can learn or take assessments.
- [ ] Align the parent's three navigation destinations while applying responsive navigation placement consistently.
- [ ] Preserve authentication, logout and session-expiry boundaries.

**Exit gate:** each shared journey has the same expected sequence, labels, state meanings and business rules, even if native presentation details differ.

## Phase 7 — Make language support complete and independent

- [ ] Define and document three separate preferences: App Language (\`hi\`/\`en\`), Learning Content Language (\`hi\`/\`en\`) and Certificate Language (\`hi\`/\`en\`).
- [ ] Review existing persistence/API fields before changing them; use an additive migration/API change if separation requires it. Do not silently reuse a field with a different meaning.
- [ ] Ensure app language changes update the interface immediately without resetting active navigation, lesson, attempt, response, page or playback state.
- [ ] Persist and restore app-language preference consistently on web and Android.
- [ ] Persist and restore learning-content preference independently; ensure every applicable lesson, question, practice, assessment, class, tutor and document request uses it.
- [ ] Ensure certificate issuance uses the explicit certificate language and does not change either other preference.
- [ ] Complete missing web localization in Tests & Results, Certificates, Classes and Textbook Library, including dynamic/API errors and notices.
- [ ] Replace Android hardcoded/Hindi-only strings, empty states, validation, status messages and inaccessible labels with proper bilingual resources.
- [ ] Avoid fragile phrase matching as the long-term localization interface; prefer stable translation keys/resources for all touched screens.
- [ ] Check translated lesson blocks, questions/options/explanations and tutor content where translations/assets exist.
- [ ] Communicate missing/stale translations and missing language-specific PDFs clearly without mixing languages invisibly.
- [ ] Add string/resource coverage checks so new visible copy cannot bypass localization unnoticed.
- [ ] Verify Hindi Devanagari rendering and English text sizing, labels, buttons, dialog copy, screen-reader labels and long text wrapping.

**Mandatory four-combination matrix:**
- [ ] Hindi app + Hindi learning content.
- [ ] Hindi app + English learning content.
- [ ] English app + Hindi learning content.
- [ ] English app + English learning content.

**Exit gate:** the UI language, learning language and certificate language remain independently controlled and consistent on both platforms.

## Phase 8 — Align shared visual components and responsive presentation

- [ ] Document the common QuantaEdge design tokens: brand colours, typography, spacing, radii, borders, shadows and status colours.
- [ ] Match primary group names, icons, active states and short labels across the web and Android.
- [ ] Use only five prominent icons for student primary navigation; use readable labels for nested functions.
- [ ] Standardise screen headers, descriptions, primary/secondary/destructive buttons, fields, tabs, chips, progress indicators and status badges.
- [ ] Standardise loading skeletons, empty states, error messages, retries, confirmations and success feedback.
- [ ] Ensure each feature screen has a clear title, current context and one sensible primary action.
- [ ] Remove duplicated/competing shortcut cards only after ensuring the functionality has another obvious entry point.
- [ ] Verify desktop web, compact mobile web, Android portrait, Android landscape and tablet layouts.
- [ ] Test larger text settings, Hindi wrapping, touch targets, keyboard/focus behaviour, TalkBack/accessibility semantics and contrast.
- [ ] Keep native-only controls where they serve platform-specific features; match their intent and status, not necessarily their exact component.

**Exit gate:** both clients share the same visual hierarchy and interaction principles without sacrificing responsive or native usability.

## Phase 9 — Automated and manual regression validation

### Navigation contract
- [ ] Web automated test asserts exactly five ordered student groups and complete route mapping.
- [ ] Android unit test asserts the same five group IDs/order and route-to-group behaviour.
- [ ] Test nested route active states, conditional diagnostic visibility and leaderboard/certificate placement.
- [ ] Test that Logout/Profile/App Language are accessible but do not appear as extra primary items.

### Responsive behaviour
- [ ] Portrait phone: five bottom items, readable Hindi/English labels, no sixth Logout item.
- [ ] Large portrait device: still bottom navigation.
- [ ] Landscape phone: five left-rail items, content remains usable at narrower width.
- [ ] Landscape tablet: left navigation rail and expanded content.
- [ ] Rotate during a lesson, practice answer, in-progress assessment, textbook read, tutor conversation and video playback; verify workflow state is retained.
- [ ] Verify back behaviour, deep links, refresh, account menu, logout and session expiry.

### End-to-end feature regression
- [ ] Today → recommendation → lesson/diagnostic.
- [ ] Learn → lesson → question → practice/tutor → next lesson.
- [ ] Learn → live session → join/leave → attendance.
- [ ] Learn → recording → play/resume → saved playback progress.
- [ ] Practice → answer feedback → retry/completion.
- [ ] Tests → start/save/submit → awaiting review/released result → answer review.
- [ ] Tests & Results → leaderboard → ranking criteria and current-student status.
- [ ] Released eligible assessment → issue/view/download certificate; confirm ineligible/revoked cases.
- [ ] Progress → mastery → relevant next lesson/practice.
- [ ] Textbook Library → filter/select → read page → return; verify unavailable language copy.
- [ ] Parent → select child → read-only report → child credentials/subjects/profile.
- [ ] Android protected offline download, airplane-mode read, offline assessment queue, reconnect/sync, local-copy deletion and scanner verification.
- [ ] Run the four language combinations across core journeys.
- [ ] Run API tests, web contract/lint/type/build checks, Android unit tests and Android debug build. Record baseline failures separately; resolve introduced failures.
- [ ] Review authorization/security-sensitive diffs, dependency/migration changes and generated content before merge.

## Phase 10 — Review, merge and post-merge verification

- [ ] Review all changed files against the agreed scope; reject unrelated admin, HRMS or backend changes.
- [ ] Confirm Android-only offline/PDF/scanner/security functionality remains intact.
- [ ] Confirm all shared routes remain reachable and no current records/data are invalidated.
- [ ] Re-run required CI/build/tests on the final candidate commit.
- [ ] Open a pull request against \`main\` with the parity matrix and test evidence.
- [ ] Merge only after all required checks are green and the final diff is reviewed.
- [ ] Verify the merge commit and post-merge CI on \`main\`.
- [ ] Record any deployment/device checks not available in CI as outstanding; do not claim unexecuted checks passed.

## Definition of done

- Exactly the existing five student groups are used in web and Android, in the same order.
- Android portrait always displays bottom navigation; Android landscape always displays the five-group rail on the left.
- No sixth student primary navigation item is added for Profile, Logout, Results, Certificates, Leaderboard, Classes or Tutor.
- Parent navigation continues to reflect its three existing web destinations.
- All shared functionality has equivalent discoverability, state meanings and business rules.
- App language, learning-content language and certificate language work independently in Hindi and English.
- Android-only offline assessment/PDF/scanner/security capabilities remain operational.
- Required tests/builds are green and the changes are merged into \`main\`.
