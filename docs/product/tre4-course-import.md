# TRE-4 course import and HRMS release

## Target catalog record

- Source institution: QuantaEdge (`QUANTA-EDGE`)
- Course: `TRE-4 · Computer Science`
- Subject: `Computer Science`
- Chapter: `Prev years`
- Paper assessments: TRE 1.0, TRE 2.0 and TRE 3.0
- Delivery mode: `ASSESSMENT_ONLY`

The legacy curriculum schema requires every course product to reference a curriculum class. The importer uses an internal `TRE4` compatibility scope labelled **Competitive Exam Preparation**; it is not a school grade. The public course hierarchy remains Institution → Course → Subject.

## Inspect and import

Install the importer dependencies:

```powershell
python -m pip install "psycopg[binary]>=3.2,<4" pymupdf
```

From the repository root, first run the dry-run (no database writes):

```powershell
python scripts/import_tre4_course.py --archive data/TRE-4_PYQ.zip
```

When the three expected booklet counts and source hashes reconcile, set the local API PostgreSQL URL in the current PowerShell session and apply:

```powershell
$env:TRE4_DATABASE_URL = "postgresql://quantaedge:YOUR_LOCAL_DB_PASSWORD@localhost:5432/quantaedge"
python scripts/import_tre4_course.py --archive data/TRE-4_PYQ.zip --apply --submit-for-review
```

The importer also accepts the existing `SPRING_DATASOURCE_URL` (including the `jdbc:postgresql://` prefix). Never commit the password or put it in this document. The DB must have Flyway V170 applied (including V168/V169); the importer refuses to proceed unless the assessment-only readiness gate expects 150 TRE 3.0 questions.

## What the script writes

The import is one PostgreSQL transaction. It idempotently creates/reuses the QuantaEdge-owned course, maps the Computer Science subject, creates a draft `Prev years` chapter, and inserts three `DRAFT` assessments with immutable snapshots of every extracted question. Source package IDs and hashes are stable, and repeat runs must match the existing package hash and question count; the importer refuses to overwrite a changed or reviewed package. It creates audit rows for inserted assessments and course lifecycle events.

- TRE 1.0: 120 questions; paper-specific 0.25 negative-marking fraction.
- TRE 2.0: 150 questions, including the qualifying language section.
- TRE 3.0: 150 questions (30 qualifying-language + 40 General Studies + 80 Computer Science), with the language IDs E-1 through E-8 and H-9 through H-30 preserved.
- General Studies and Computer Science count toward the scored total; qualifying-language questions are stored but excluded from that aggregate score.
- Shared passages/stimuli remain on each child question snapshot with a shared stable group ID.
- No answer key or explanation is guessed. Answer payloads are `UNRESOLVED`, review status is `PENDING_HUMAN_REVIEW`, and assessment status remains `DRAFT`.

## HRMS lifecycle

`--submit-for-review` submits a structurally complete assessment-only release into `IN_QUALITY_REVIEW`. It does **not** approve, make it shareable, or publish any assessment. HRMS must approve the release, and a QuantaEdge institution administrator must then approve the origin release. Only then does the release appear in HRMS's shareable-course assignment catalog. This retains the current two-party approval policy.

V168 establishes assessment-only readiness and V170 aligns its TRE 3.0 count with the extracted 150-question source inventory. Exactly the three expected source-paper packages, correct question counts, valid hashes, and the correct Prev years/Computer Science mapping must be present. The canonical fingerprint includes the assessment rows and question snapshots for assessment-only courses; ordinary lesson courses retain the previous fingerprint inputs so existing releases are not invalidated.

Even after assignment to another institution, imported tests remain draft and invisible to students. The student-facing publish transition rejects portable paper assessments while a question's human-review status is pending or its answer key is unresolved. Review source wording, Hindi glyph fidelity, question/options, topic metadata, official key (where available), and explanations, then import/approve a reviewed version through the supported editorial flow before student delivery. This initial importer is for creating the course and queuing it for institutional assignment, not for certifying exam content.

## Validation

```powershell
python -m unittest discover -s tests -p test_import_tre4_course.py
```

This test suite is offline and does not connect to PostgreSQL. The dry-run additionally verifies source PDF hashes and exact extracted question counts. After import, check the API/HRMS course-release review queue, confirm the course has one subject and the three draft paper assessments, then complete the quality-review and origin-approval steps.
