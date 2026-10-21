# Class 6–8 course visibility audit and implementation actions

**Audit date:** 2026-10-18  
**Repository:** `Manav326/QuantaEdge`  
**Audited branch/head:** `main` at `9fd95ff328b415741229efa58a54dd35690588f4`  
**Related locked scope:** [Issue #66 — Course-aware student experience, marketplace and web/Android parity](https://github.com/Manav326/QuantaEdge/issues/66)

## Finding

Class 6, 7 and 8 exist as curriculum classes, but that does not currently make them discoverable course products in the course marketplace.

The source-level path is:

1. `V2__curriculum_foundation.sql` creates `curriculum_class` records for codes `6`, `7` and `8`, together with legacy class-scoped subjects.
2. `V51__bilingual_content_and_program_foundation.sql` creates an active `GENERAL` program per class and attaches the legacy subjects to those `GENERAL` rows. Student course-listing code explicitly excludes `GENERAL`.
3. `V97__additive_course_scoped_tracks.sql` creates one separate, non-`GENERAL` course product: `NAVODAYA_BATCH`. It does not create independently selectable Class 6, Class 7 or Class 8 course products.
4. `V113__course_marketplace_razorpay.sql` deliberately creates no offer rows or prices. Its comment specifies that administrators must explicitly publish each offer.
5. The Admin Marketplace endpoint `GET /api/v1/marketplace/admin/courses` lists active non-`GENERAL` `curriculum_program` records and left-joins their offers. Consequently, the class curriculum records are not rows it can configure as purchasable products.
6. Student Marketplace `GET /api/v1/marketplace/courses` inner-joins `course_marketplace_offer` and returns only `PUBLISHED` offers. A class cannot appear there merely because textbooks, subjects, chapters or lessons exist.
7. `GET /api/v1/students/me/programs` also excludes `GENERAL` and returns actual active course products. The `/student/courses` UI then deliberately shows only enrolled/paused courses. Therefore **All Courses is not meant to be a list of unpurchased offerings**; Marketplace is the discovery surface. A student must have an enrollment, verified purchase, published-free-course enrollment or authorized institution/course entitlement before a course belongs in All Courses.

This is a product-catalogue/data-model gap, not evidence that React simply forgot to render three existing program rows. The source audit cannot establish the live production database's applied migrations or live offer rows; confirm those during integration validation.

## Actionable implementation plan

- [ ] **C1 — Define the product identities.** Add independently addressable Class 6, Class 7 and Class 8 course products without reusing or renaming the existing `GENERAL` rows and without conflicting with `NAVODAYA_BATCH`. Use an additive Flyway migration; do not edit released migrations.
- [ ] **C2 — Connect real curriculum content.** Map the products to the correct mathematics/science (and other intended) course tracks. Inspect the complete course-aware lesson, assessment, tutor, resource/PDF, progress and certificate paths before choosing whether to migrate/copy tracks or add a supported content mapping. Preserve real source provenance and publication/review gates; do not expose draft content or fabricate lessons/questions. Course-specific progress must not accidentally mix with another course or the legacy class path.
- [ ] **C3 — Surface products to authorized Admin.** Ensure the new active product records appear under Admin → Course Marketplace so an authorized administrator can set the intended title, description and INR price and choose DRAFT/PUBLISHED/ARCHIVED. Add regression coverage proving each product is configurable.
- [ ] **C4 — Publish intentionally.** Do **not** guess a price or mark every class free automatically. No student Marketplace offer should be considered purchasable until an authorized administrator explicitly sets the correct price and publishes the offer. After that action, the current published-offer API should expose it.
- [ ] **C5 — Preserve course ownership semantics.** Keep unpurchased courses in Marketplace and owned/enrolled courses in All Courses. A successful paid checkout must verify server-side before ownership appears; a published free offer may use the existing explicit free-enrollment flow; institution access must still require an active membership and course entitlement. Do not silently auto-enrol all students into all three classes.
- [ ] **C6 — Keep Web and Android aligned.** Confirm that web and Android Marketplace display published Class 6/7/8 offers from the same API and that owned-course lists contain them only after the authoritative access check. Use the same course IDs for navigation, assessments, PDF/lesson contexts and progress.
- [ ] **C7 — Acceptance tests.** On clean and upgraded databases, assert the three products exist, have the correct subject/chapters mapping and appear in Admin Marketplace. Verify draft/archived offers are not purchasable; published offers display the correct price; an unentitled learner cannot enter the course; verified purchase/free enrollment/institution authorization updates All Courses; and class-specific progress, assessments, certificates, PDFs and structured lessons stay scoped correctly. Run API tests, Web/Android builds and exact-main GitHub Actions before checking these tasks complete.

## Related logo-onboarding audit

The current HRMS upload path is PNG/JPEG only, capped at 2 MiB, with server-side decoded-image checks; PDF logos are not accepted and uploaded images are not normalized into component-specific renditions. School logo upload/removal and versioned tenant-specific serving already exist. Before implementation, inspect the actual logo component's responsive CSS geometry and then add:

- [ ] Accept school-provided PNG/JPEG and PDF, safely rasterizing the first relevant PDF page server-side with strict file/page/pixel/time/memory limits. Do not trust filename or MIME, and do not serve the uploaded PDF as the active logo.
- [ ] Provide a preview/approval tool with transparent contain-fit, safe padding/crop, and distinct logo versus square app-icon outputs derived from the real component slots. Never stretch a wide logo into a square.
- [ ] Store the source separately from generated renditions; persist hashes, dimensions, asset role and branding version; publish only the selected, validated rendition for that institution.
- [ ] Test malformed/oversized PDFs, malformed images, conversion limits, transparency, light/dark backgrounds, logo replacement/removal, stale cache URLs, and cross-tenant isolation. Keep the current audit trail and dynamic versioned asset delivery.

## Implementation progress — 2026-10-18

- [x] **C1 — Product identities (source committed; CI pending):** additive migration V122 creates active `CLASS_6`, `CLASS_7`, and `CLASS_8` product identities with registered stable public IDs. It does not rename `GENERAL`, alter `NAVODAYA_BATCH`, seed offers/prices, or enroll learners.
- [ ] **C2 — Explicit curriculum mapping (implementation slice landed; acceptance open):** each class product maps only to active, same-class legacy subject rows. This reuses class-scoped chapter, lesson and source IDs rather than copying content or provenance. Key course catalogue, enrolment, marketplace subject summaries, student lesson filtering, progress metrics, structured lesson access, assessment filtering and PDF catalogue scope honour this mapping in this source change. Complete full endpoint/security coverage including certificates and real browser/device validation before checking this task off.
- [x] **C3 — Admin configuration inventory (source committed; CI pending):** active product records are returned by the existing Admin Marketplace list endpoint, where a staff administrator can supply price/title/description and deliberately choose DRAFT/PUBLISHED/ARCHIVED.
- [ ] **C4 — Student marketplace publication:** intentionally still requires an administrator to set the actual INR price and publish the offer. The migration does not make courses free or silently public.
- [ ] **C5–C7 — Access/tenant/ownership, full web/Android parity and complete integration acceptance:** still open until current-main Actions and mapped class-course browser/API/Android tests prove publication, checkout/free enrollment, tenant entitlement, lesson/PDF/assessment/certificate and progress boundaries.

### Current exact-main verification

The previous Actions test failure was corrected in commit [6c86243](https://github.com/Manav326/QuantaEdge/commit/6c86243ade08d6a3b2f632d2f06100a1b3233a01), and run [#3772](https://github.com/Manav326/QuantaEdge/actions/runs/39095464683) passed all eight jobs. The Class 6–8 catalogue implementation landed in commit [d5bd7da](https://github.com/Manav326/QuantaEdge/commit/d5bd7da6b914be026398e26acf3bb6fa538f6fcf). Its Actions run [#3774](https://github.com/Manav326/QuantaEdge/actions/runs/39096985979) has passed Android APK build, affected-component detection, Admin image, web image, People Operations image, content-import safeguards and **API verification (including the new migration-backed smoke test)**. **Build API image is still in progress** at this checkpoint; do not call the entire run green until the workflow reports its final conclusion.

## Status

**Audit complete; first catalogue implementation slice added, with end-to-end gates still open.** No price, enrollment, paid entitlement, free access or public offer status is invented. The student Marketplace continues to list only explicitly published offers; All Courses continues to list courses the learner actually owns/enrolled in.
