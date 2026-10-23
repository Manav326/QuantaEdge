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

## Status rule

A source change or green compilation is not proof of a working user journey. Mark end-to-end items complete only after the named flow is exercised in a real browser/device against an approved test environment and the result is recorded.


## Account deletion request lifecycle and linked-child guardrails

- Web and Android provide an identity-verified, reasoned deletion-request intake with status and cancellation; submission is not completion.
- Requests record a mandatory reason category, optional bounded explanation, request initiator and the child's public identity where a guardian requests child deletion.
- A parent account is blocked server-side while any active parent-managed child remains linked. A checkbox or URL parameter cannot bypass the guard.
- A verified adult child must complete the existing independent-learner conversion path. A child with unknown/pending adult status requires staff age-status review before guardian-requested deletion can be submitted.
- Staff queue and read-only dependency assessment surface the request and associated FK dependencies. Full retention-aware erasure remains out of scope until the approved retention decisions and audited fulfillment workflow are implemented.
