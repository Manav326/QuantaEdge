# TRE-4 Computer Science course bootstrap

## What this database migration does

Flyway migration `V168__bootstrap_quantaedge_tre4_cs_course.sql` creates the ordinary course shell in the existing QuantaEdge data model:

- **Institution:** `QUANTA-EDGE`
- **Course:** `TRE-4 · Computer Science` (stable public identity allocated by the existing `INS` prefix)
- **Subject track:** `Computer Science`
- **Chapter:** `Previous-Year Papers (PYQ)`, initially `DRAFT`
- **Institution course release:** version 1, `DRAFT`
- **Institution assignment:** `ASSIGNED` with source `ORIGIN`
- **Institution course entitlement:** `ACTIVE` for the QuantaEdge institution

The course stays within the existing `curriculum_program → curriculum_program_subject → curriculum_subject → curriculum_chapter` model. The `TRE4` row in `curriculum_class` supplies the required curricular scope; it does not create a second course or assessment engine.

## What it deliberately does not create

- It does **not** create learner `course_entitlement` rows or enroll students.
- It does **not** create a marketplace offer, price, or publish the course to students.
- It does **not** create assessment or attempt rows from extraction metadata.
- It does **not** approve source text, proposed answers, explanations, or negative-marking rules.

The table names represent different relationships: `institution_course_entitlement` grants institutional access to a course; `course_entitlement` represents an individual learner/parent's course ownership. The latter should be empty until a legitimate enrollment or entitlement grant exists.

## Local database verification

After this migration is merged, pull `main` and restart the API using the normal local development workflow so Flyway applies version 168. Then run:

```sql
SELECT p.id, p.public_id, p.code, p.display_name, p.status,
       c.code AS curriculum_scope,
       s.id AS subject_id, s.code AS subject_code,
       ch.id AS chapter_id, ch.code AS chapter_code, ch.display_name AS chapter_name,
       ch.content_status
FROM institution_tenant t
JOIN institution_course_release r ON r.source_institution_id=t.id AND r.release_version=1
JOIN institution_course_assignment a
  ON a.institution_id=t.id AND a.course_release_id=r.id AND a.status='ASSIGNED'
JOIN curriculum_program p ON p.id=r.program_id
JOIN curriculum_class c ON c.id=p.class_id
JOIN curriculum_program_subject ps ON ps.program_id=p.id AND ps.class_id=p.class_id
JOIN curriculum_subject s ON s.id=ps.subject_id AND s.class_id=ps.class_id
JOIN curriculum_chapter ch ON ch.subject_id=s.id AND ch.code='prev-years'
WHERE t.tenant_code='QUANTA-EDGE'
  AND p.display_name='TRE-4 · Computer Science';
```

Expected: one course, one Computer Science subject, one `prev-years` chapter whose status is `DRAFT`, and an assigned draft release. Check the institution-side entitlement independently:

```sql
SELECT e.status, e.valid_from, e.valid_until
FROM institution_course_entitlement e
JOIN institution_tenant t ON t.id=e.institution_id
JOIN curriculum_program p ON p.id=e.program_id
WHERE t.tenant_code='QUANTA-EDGE'
  AND p.display_name='TRE-4 · Computer Science';
```

A learner-level query against `course_entitlement` should still return zero TRE-4 rows until that learner is legitimately granted or purchases access. No student-facing test should appear until reviewed assessment packages are imported, the chapter/content release gates are satisfied, and the relevant publication/access flow is completed.

## Question-package gate

The source booklets in `data/TRE-Papres/sources/` have source inventories but still require question-level answer/key/editorial review before an assessment can be published. The supplied TRE 3.0 booklet currently reconciles to **148 total questions** (28 qualifying-language, 40 General Studies, 80 Computer Science), not 150; do not invent missing E-1/E-6 questions to reach a target count. Confirm the intended test scope against the supplied booklets before creating question snapshots.
