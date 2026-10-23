# Public Learning Experience Implementation Tracker

## Product positioning

QuantaEdge is a quality-first learning platform for different ages, subjects, skills, and goals. Parents may monitor linked children's progress and learn independently through courses for their own interests, skills, and goals. School curriculum or NEP 2020 alignment must only be described for the specific school courses where it has been designed and reviewed for applicability. Public wording must not expose internal platform architecture; institution-branded journeys treat the institution as the authority presented to that audience.

## Current implementation slice — User Web

- [x] Replace the school-only general homepage message with a broad learning proposition and sections for learning structure, diagnostics/practice, topic-scoped AI support, progress/certificates, teacher review, supported offline learning, school learners, parents learning independently, and adult/skills learners.
- [x] Scope curriculum and NEP 2020 messaging to applicable school courses only.
- [x] Keep the institute-branded learning entry distinct from the general QuantaEdge public homepage.
- [x] Use shared theme and language controls on the public homepage, authentication routes, and institute-branded entry states.
- [x] Introduce a shared responsive two-panel authentication presentation for account sign-in, signup, and young-learner sign-in.
- [x] Keep the current form submission, verification, session validation, and redirect handlers unchanged while reworking presentation.
- [x] Add English/Hinglish copy to the centralized catalogs and keep the app catalogs synchronized with the added keys.
- [x] Update public document metadata to describe the broader learning offer.
- [x] CI run #39888675780 passed the current source contracts, catalog parity, content safeguards, Web/Admin/HRMS image builds, and Android APK build. Browser/device interaction checks remain open below.
- [ ] Test in a browser at desktop, tablet, and mobile sizes; light, dark, and system modes; English and Hinglish; direct routes and browser back navigation; and institute-branded entry.
- [ ] Review keyboard operation, focus order, contrast, reduced motion, small-screen overflow, and readable form validation/error states.

## Follow-through required before production

- [ ] Ensure the public feature descriptions correspond to enabled, tested product capabilities and do not imply offline or AI capabilities for unsupported courses.
- [ ] Audit and implement end-to-end account deletion from profile/settings in User Web and Android, including confirmation, identity verification, session revocation, linked family records, deletion status, and legal retention exceptions.
- [ ] Parent deletion must explicitly resolve each linked child: include that child in the deletion request or complete a verified guardian transfer first. Never leave child login dependent on a deleted guardian without a supported recovery path.
- [ ] Review purchases, invoices, certificates, assessment records, security/audit evidence, and statutory retention with the responsible legal/business owner. Delete personal data when required; retain only specifically justified records, limit access, and disclose the retention clearly.
- [ ] Audit Android resource downloads and offline tests on supported content; verify interrupted downloads, app restart, encrypted queued answers, idempotent result sync, server-side grading, and clear sync states before treating offline learning as release-ready.
- [x] Admin and HRMS pre-login theme/language controls are present in source; each retains concise role-specific messaging.
- [x] Android signed-out account routes expose the existing persistent theme choices and retain the existing language controls.
- [ ] Exercise Android theme/language persistence on-device across welcome, login, signup and password recovery.
- [ ] Verify applicable current Google Play, privacy, account-deletion, institutional, and payment-provider requirements against official rules and the real deployed product. Do not mark compliance complete from copy or links alone.
- [ ] Verify public contact/operator disclosures, privacy and terms, support path, course availability claims, certificate wording, and any applicable refund/cancellation disclosures.
- [ ] Keep this tracker and the UI parity ledger updated with source implementation and end-to-end evidence recorded separately.

## Account-deletion implementation progress — request intake only

- [x] Add an authenticated request/status/cancellation API with typed confirmation, server-derived identity, duplicate-open-request protection, an audit event, and a staff review queue.
- [x] Add the external User Web request route and link it from Privacy; expose the same request panel in parent/independent-learner profile settings. English/Hinglish strings are centralized.
- [x] Add the Android in-app request/status/cancel controls to account identity settings, using the existing authenticated API client and centralized Android phrase registry.
- [x] Require parents to explicitly include every currently linked child in the request; store a per-child public-ID snapshot and do not silently orphan child access.
- [x] Keep the request visibly distinct from completed deletion; provide cancellation while the request is still open.
- [ ] Implement actual retention-aware erase/anonymize fulfillment, revocation of all account and learner sessions at finalization, processor-side deletion, and an approved status-completion path. Current request intake must not be treated as completed deletion.
- [ ] Complete the full migrated-schema FK/PII inventory and legal/business retention decision. The confirmed dependencies and blockers are documented in `docs/architecture/account-deletion-dependency-map.md`.
- [ ] Add a verified step-up identity check for the request/fulfillment process (not just an active session and typed confirmation); define OTP/password/Google re-auth behavior without weakening existing login verification.
- [ ] Build and device-test the Android in-app request path, including API error states, child confirmation, cancellation and restored sessions. The source controls are wired, but physical-device acceptance has not been run.
- [ ] Add end-to-end staff operations and verify parent-only, multiple-child, shared-guardian, purchase, invoice, certificate and cancellation scenarios in a real migrated test environment.

## Status rule

A source change or green compilation is not proof of a working user journey. Mark end-to-end items complete only after the named flow is exercised in a real browser/device against an approved test environment and the result is recorded.
