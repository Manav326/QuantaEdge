# Course catalogue and content-scope correction

## Product rules

- Classes 6, 7 and 8 are direct learner courses in the course/batch model, not school-class enrolment records.
- Numeric `curriculum_program.id` remains the canonical course identity. New public IDs use the registered `CRS` prefix and the existing five-character suffix format.
- Existing subject, chapter, lesson, question, document, progress, assessment and attempt identities are preserved. The migration re-scopes existing canonical subject rows in place.
- Web and Android All Courses list every active non-`GENERAL` course. Learners can open lesson links only after the course is activated for their account.
- Marketplace lists active courses whether or not an offer exists. Checkout and free-enrolment stay disabled until an authorized administrator publishes an offer and a price; an absent offer is never treated as free.
- Every existing learner whose class code is 6–8 is backfilled to the matching course, even if older code never wrote a GENERAL enrollment row. New learner profiles and class promotions also receive the matching course through a database trigger. If a different independent course is active, the class course is paused rather than silently stealing focus.
- Existing course purchases, unified course entitlements and institutional course grants remain recognized access sources.

## Migration

Flyway migration `V122__class_courses_and_content_scope.sql` registers the `CRS` prefix, creates a course for each of class codes 6, 7 and 8, re-scopes their current canonical subjects without creating duplicate content, adds course-to-subject links without deleting the historic GENERAL links, moves textbook source-book scope and backfills learner access.

The migration preserves the legacy class-code entitlement through course enrollment, including for new profiles with class codes 6–8. It does not publish purchase offers or invent prices. Review Class 6/7/8 in Admin → Marketplace and publish the agreed offer/price before enabling a separate purchase flow.

## Verification checklist

1. Apply migrations to a disposable database snapshot first. Compare course, subject, chapter, lesson, question, document, progress and assessment-reference counts before/after.
2. Verify a legacy Class 6/7/8 learner sees the matching course and can open published lessons, questions, practice, tests and approved PDF assignments using its numeric course ID.
3. Verify a learner without a matching active course cannot open course-scoped lesson, question, practice or document IDs, including direct API requests with no courseId.
4. Verify Marketplace shows “Coming soon” for courses without published offers and disables checkout/free join. In staging, publish a free offer and a paid offer and verify free enrolment plus server-side Razorpay order/payment verification.
5. Verify learners with valid institutional course grants can activate that course without consumer checkout.
6. Run API tests, the web navigation contract, Android unit tests and full CI. CI cannot prove the production database or authenticated staging behavior; perform real learner/parent staging smoke tests before rollout.
