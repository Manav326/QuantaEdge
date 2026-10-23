# Course-centred platform model — audit and additive rollout

**Audit date:** 23 October 2026  
**Main baseline reviewed:** `0efb0b91d02f7cf57d233345ab6762f5423a0a04`  
**Change policy:** additive migrations and compatibility-preserving API changes first; mark superseded routes and components deprecated; remove only after usage has been measured and cutover is verified.

## Non-negotiable rules

1. HRMS is the control plane. Every learner-facing Marketplace offer—also the offer in the source institution's own Marketplace—must pass HRMS quality review, source-institution release approval, destination assignment where applicable, the HRMS Marketplace launch stepper, and HRMS offer approval/publication. No client- or institution-admin route may publish an offer.
2. Every Education Admin staff member acts within one HRMS-configured institution. Their permissions are effective only inside that institution. The server—not browser state, a URL, a query string or `X-QE-Institution-Code` alone—must establish the staff-to-institution binding. HRMS platform-owner workflows are separate and may operate across institutions.
3. A course's content may be edited only by its source institution. A destination institution can see and use its assigned shared release, but cannot modify the source content. A source edit creates a new version and does not mutate a release already approved/published for learners.
4. Course creation and assessment creation use the same authoritative server-side model and validators whether the user fills the UI or uploads JSON. Import creates drafts only; a package can never set HRMS approval, publication, institution assignments or revenue policy.
5. Historical records must remain intact. Add replacement APIs, attribution columns, compatibility behavior and deprecation notices before removing old code. Do not silently migrate legacy published offers into current approval state.

## Course identity and vocabulary

- **Course type** is product configuration, not tenant ownership. Initial types: `K12`, `COMPETITIVE`, and `ASSESSMENT`; additional types should be registry/configuration driven rather than hardcoded in every UI.
- **SOURCED** and **SHARED** describe the course relationship from the current institution's perspective, not intrinsic course types. Derive `SOURCED` when the active institution equals the release's `source_institution_id`; derive `SHARED` when the institution has an HRMS-created assignment to another institution's release. Do not store a second mutable flag that can contradict the foreign keys.
- Keep the immutable course identity separate from versioned content/releases; tenant-specific Marketplace offers and terms are distinct from the course and from its release.
- The course should be the navigation and reporting root: identity/type/source; releases and actors; assignments and Marketplace offers per institution; effective revenue-share/tax terms; registrations/enrolments, progress, assessment attempts/results, and certificate status.

## Course-type content arrangements

- **K12:** `course → subjects → chapters → lessons/teaching blocks`. Resources may optionally attach to the course, subject, chapter, lesson or supported block.
- **COMPETITIVE:** configurable tracks/sections/topics and reusable question banks or assessments; a school class is not mandatory.
- **ASSESSMENT:** `course → test packs → tests/assessments → ordered questions`. A pack may have any defined number and supported types of tests. Each test can declare timer, attempts, scoring/negative marking, open-book resource policy, auto/manual evaluation, answer explanations, result release, diagnostics and certificate eligibility.
- A generic resource may be a PDF, video lecture, worksheet, slides, audio, external reference or a defined collection such as a question bank. Attachment scope and access policy must be explicit and versioned.

## Confirmed current foundations

- `institution_course_release`, `institution_course_assignment`, and `institution_marketplace_offer` already model releases, destination assignments and tenant-specific offers.
- `CourseMarketplaceLaunchController` provides an HRMS-only six-step Marketplace launch workflow and checks quality, source approval, current fingerprints, content, payment configuration and commercial readiness.
- The database contains course-aware assessment/attempt/question snapshots, public test IDs, certificate primitives, revenue-share/invoice tables, and a portable assessment-package JSON Schema/validator.
- Generic `learning_resource` records support multiple media types, and `learning_resource_assignment` currently attaches resources only to chapters, lessons and structured artifacts, all with a required chapter.
- Course class identity is already nullable in the curriculum schema, so independent competitive/assessment courses have a foundation.

## Confirmed gaps from source inspection

### P0 — Publication authority
- Institution-admin routes under `/api/v1/institution/course-offers/{offerId}/...` include submit, approve and publish operations over the same tenant offer table. Direct publication is a second path outside HRMS.
- The legacy Education Admin Marketplace uses `/api/v1/marketplace/admin/courses` and `course_marketplace_offer`, while the current learner catalogue reads `institution_marketplace_offer`. Keeping both editable is confusing and cannot be allowed to imply approval.
- The current learner Marketplace checks offer status `PUBLISHED`, assignment status, and tenant/course rows, but does not itself require a current `SHAREABLE` release, a current fingerprint, or an explicit HRMS publisher attribution.
- V125 intentionally retained some historic QuantaEdge `PUBLISHED` offers with `legacy_migration=true`; historical status is not proof that the new approval steps were completed.

### P0 — Institution binding and content ownership
- `AdminWorkspaceLayout.tsx` currently renders an institution dropdown, remembers the choice in localStorage, and loads `/api/v1/admin/content/institutions`.
- `AuthFilter` resolves Education Admin tenant context from the selector header (or deployment default/fixed mode). `AdminContentController` checks that the course is assigned to the resolved institution, but assignment alone does not prove that the institution owns the source release.
- In the reviewed main source, a single HRMS-managed employee-to-institution binding was not connected to this Education Admin request path. If a separate HRMS configuration is intended to provide it, the handoff/lookup is missing from this path and must be verified before relying on it.

### P1 — Versions and fingerprinting
- V125 states that a release is metadata around a shared mutable curriculum graph, not a frozen content snapshot.
- `course_content_fingerprint()` includes program/subject/chapter/lesson data and assessment content for assessment-only courses, but does not cover every content dependency (for example ordinary lesson blocks and generic resource assignments). Learner catalogue checks do not enforce the fingerprint after publication.
- Editing a shared graph could therefore change an already reviewed version. A new edit must produce a new draft version; published content must remain stable for existing learners/attempts.

### P1 — Generic course/content authoring
- Institution course creation is in `InstitutionCourseLifecycleController.createCourse`; it requires a `classCode` and 1–30 subjects. Content Studio currently creates chapters and lessons rather than the full course.
- `curriculum_program_delivery_mode` currently models the special `ASSESSMENT_ONLY` mode. It is not a general type registry defining each type's allowed hierarchy, required fields, and renderer.
- No complete Course Content Studio page was found for displaying the physical database/API schema or importing a complete course JSON object.
- The portable assessment package schema and `scripts/validate_portable_assessment_package.py` provide a reusable foundation for 100-question JSON packs, but validation/package preparation is not yet a unified Admin Web create/import → preview → atomic draft persistence path.
- Resource associations cannot currently attach a resource to the course, subject or assessment/test; an assignment requires a chapter. Open-book tests need explicit, attempt-scoped resource permissions.
- Course creator/reviewer/publisher attribution exists in lifecycle events and selected assessment records, but should be standardised per version and surfaced as one history.
- Existing QA route inventory marks key course lifecycle/Marketplace routes as `NOT_EXECUTED`. Source tests are not proof of a green current build or end-to-end behavior; the latest commit query returned no status records, so CI is unknown rather than passing.

## Additive implementation sequence

1. **Cutover guardrails:** add explicit HRMS approval/publisher attribution to institution offers; backfill only from matching HRMS lifecycle events; make offer discovery, free enrolment, checkout and payment verification fail closed unless HRMS published the currently shareable, current-fingerprint release. Mark the second publisher and legacy editor obsolete while preserving their paths.
2. **Trusted tenant/source permissions:** connect HRMS's authoritative institution assignment to the Education Admin session/request; remove the dropdown and localStorage tenant switch; derive `SOURCED`/`SHARED` from release ownership and institution assignment; enforce source-only editing across curriculum, questions, assessments and resources.
3. **Version integrity:** persist immutable/versioned course content and resource associations; new edits create a new release draft; include every material dependency in the canonical fingerprint and validate it consistently across launch, catalog, access, assessment launch and checkout.
4. **Schema-driven authoring:** add a versioned course-type/schema registry and a Course Studio entry within existing Content Studio. Generate UI sections from the selected type. Support optional resource links at each allowed scope and separate source/share status panels.
5. **Portable JSON import:** define a complete course-package Schema contract and assessment-package contract. Validate locally and server-side, show a preview and row-level errors, issue all database IDs server-side, and persist the package atomically in draft state. Enforce package/version/hash idempotency and never allow JSON to choose tenant/source identities or publish state.
6. **Course-centred operations/reporting:** per version and destination, show assignment/offer readiness, price/revenue share, registration count, progress, attempts/results, diagnostics and certificates. Keep older results linked to the content snapshot used for that attempt.
7. **Evidence and cleanup:** prove with source institution + two destinations + unrelated institution, unauthorized staff and authorized staff with scoped permissions, and tests for every old route. Record exact SHA/CI/E2E evidence. Remove deprecated components only in a later cleanup after callers and historical data are reconciled.

## Proposed JSON authoring experience

The schema page must document physical table/column names and types separately from JSON field names, required/optional rules, IDs/unique keys, foreign-key relationships, status transitions, review requirements, supported resource scopes and troubleshooting steps. It must be generated from or checked against the authoritative schema/contract so the documentation cannot drift.

A test-package question should be expressible as stable package ID + version, ordered question ID/type/prompt, option key/label/order, correct answer, explanation/marking rubric, marks, difficulty/tags and provenance/review metadata. The server stores immutable question snapshots per published assessment; public student responses never expose answer keys before allowed result release.

For large imports, validation must report every error at a JSON path (e.g. `$.tests[0].questions[27].options[1].key`) and a row count before any writes. A failed import has zero partial records. Publishing stays in the normal permissioned review/HRMS workflow.


## Obsolete paths — retained until post-cutover cleanup

The following paths are explicitly **obsolete and must not be reactivated**. They remain in the codebase until the HRMS-governed replacement is proven; remove them in a dedicated cleanup PR only after route inventory and telemetry show no active callers.

- Legacy Education Admin global Marketplace offer editor and its old `/api/v1/marketplace/admin/courses` API.
- Institution-side offer configuration, submission, approval and publishing endpoints under `/api/v1/institution/course-assignments/*/offers` and `/api/v1/institution/course-offers/*`.
- Legacy institution selector / client-controlled tenant switching; the authoritative institution is the server-resolved HRMS staff binding.
- Any former global-admin permission bypass. Tests must use explicit workspace-scoped grants rather than weakening authorization.

No historical offers, audit events, purchases, release records or course data should be deleted as part of this marking step.


## Course Studio JSON packages — v1 implementation boundary

The canonical reference is `schemas/course-package.schema.json`. Its v1 importer is for **K12 nested curriculum only** and imports `subjects → chapters → lessons → teaching blocks`. Use `POST /api/v1/admin/content/course-packages/validate` to validate without writes, then `POST /api/v1/admin/content/course-packages/import` to import. Both require the caller's HRMS-resolved Education Admin institution context and `CONTENT_CREATE`; import also requires `CONTENT_EDIT`.

Imports are a single transaction and keyed by institution + package ID + package version. Replaying the same package/version/content returns the original course/release; reusing that package/version for changed content is rejected. The server generates course and child database IDs. Imported chapters, lessons and blocks remain drafts; source mappings remain unverified; no student delivery or Marketplace publication is enabled by import. HRMS's existing release-quality and Marketplace workflow remains the only distribution path.

**Explicitly not implemented by this v1 importer:** the separate Competitive sections/topics/question-bank hierarchy and standalone Assessment test-pack hierarchy. Those course types are rejected by this endpoint with a clear validation error instead of being forced into K12 subject/chapter tables. Their registry entries are not evidence that the course hierarchy importer exists.


## HRMS source-rights decision path

Imported package rights are reviewed in the existing HRMS institution Marketplace launch stepper. A decision requires reviewer notes and, for either clearance state, an HTTPS evidence URL. HRMS stores the reviewer identity, timestamp, notes, evidence and lifecycle audit event. The decision follows the stable source-course identity across later draft versions; review alone does not publish the course. `DO_NOT_REDISTRIBUTE` remains a blocking state.
