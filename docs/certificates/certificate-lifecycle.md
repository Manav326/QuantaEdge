# QuantaEdge certificates

The end-to-end audit and repair checklist is maintained in [certificate-lifecycle-audit-workflow.md](./certificate-lifecycle-audit-workflow.md). The student test-result screen, certificate picker, stored artifact, and public verifier must remain consistent across that workflow.

## Implemented templates

- **CLASSIC** — navy and gold formal double-border layout.
- **MODERN** — navy header/footer with QuantaEdge orange accents.
- **ACADEMIC** — green academic frame with gold detailing.

Each issued certificate includes a scannable QR code that opens the public verification page for its exact certificate ID and a separate Code 128 linear barcode encoding the same certificate number. The human-readable certificate number can also be entered on the public verification page. The URL is built from `APP_PUBLIC_BASE_URL`, never from an untrusted request Host header. Production uses `https://qe.thinkwithsujeet.in` as the canonical public HTTPS origin by default in `docker-compose.prod.yml`. Operators can explicitly override `APP_PUBLIC_BASE_URL` in the production environment if the canonical domain changes. If the value is missing or invalid, certificate issuance fails closed rather than embedding a broken localhost QR. Sample previews deliberately omit verification QR codes and remain visibly marked as samples.

Each template is rendered on one high-resolution A4 landscape canvas. The same canvas becomes the PNG and is embedded losslessly into the PDF; the files share the same text, issue date, recipient and certificate ID. The API container installs Noto core fonts so Hindi can be shaped by Java2D instead of depending on whatever fonts happen to be installed on a host.

## Eligibility rules

1. **SUBJECT_COMPLETION:** an active student must have an active enrollment in the requested subject in their current class, at least one currently published lesson must exist for that subject, and every currently published lesson must have student_lesson_progress.status = COMPLETED.
2. **ASSESSMENT_PASS:** the attempt must belong to the signed-in student and be a manually authored admin assessment (assessment_type='ADMIN_CREATED' and is_generated=false). Its result must be RELEASED, have a positive maximum score, meet app.certificates.assessment-pass-percent (default 80), and belong to an active enrollment in that subject for the student's current class. Completing every published lesson is not a prerequisite for a test certificate; that remains the separate SUBJECT_COMPLETION achievement. Practice sessions, generated topic-end/chapter-end assessments, GRADED, AWAITING_REVIEW, in-progress and abandoned attempts do not qualify. Threshold decisions compare the exact score ratio; rounding is for display only.

SUBJECT_COMPLETION remains a separate certificate type: it requires completion of all currently published lessons in the active subject track, independently of passing an assessment. Previously issued certificates retain their immutable issuance snapshot; revoked certificates are not silently reissued.

The rules and counts/score are recorded as an immutable JSON snapshot at issue time. One certificate per student/achievement key is enforced in PostgreSQL. Issuance locks the student's row and is idempotent on retries. If the original certificate has been revoked, the same achievement cannot silently issue a replacement.

## API

- GET /api/v1/students/me/certificates — signed-in student's certificates.
- GET /api/v1/students/me/certificates/eligible-assessments — released, passing admin-created, non-generated assessment results for the student's active subject tracks (up to 100 newest results). If a result already has an issued or revoked certificate, it remains in the picker with its certificate ID/number/status so an issuance from the test-result screen does not make the assessment disappear.
- POST /api/v1/students/me/certificates/issue — request an earned certificate. JSON fields: achievementType (SUBJECT_COMPLETION or ASSESSMENT_PASS), subjectCode (for subject completion) or assessmentAttemptId (for assessment pass), templateId (CLASSIC, MODERN, ACADEMIC), and language (hi or en). The backend derives the recipient and achievement evidence from database records; client-supplied names, scores and dates are ignored because they are not accepted inputs.
- GET /api/v1/students/me/certificates/{id}/view?format=pdf|png — ownership-checked inline view with SHA-256 integrity validation.
- GET /api/v1/students/me/certificates/{id}/download?format=pdf|png — ownership-checked attachment download with SHA-256 integrity validation.
- GET /api/v1/guardians/children/{studentId}/certificates and its /view and /download routes — read-only access for the child's currently linked guardian when the relationship and consent are active; students and parents cannot retrieve another learner's files.
- GET /api/v1/certificates/verify/{certificateNumber} — privacy-minimal public validity check using the human-readable QE certificate number printed on the document. Certificate QR codes open `/certificate/verify?code={certificateNumber}`, which calls this endpoint live; Code 128 barcodes encode the same number. On supported browsers, the verification page can scan QR/Code 128 with a camera; keyboard-wedge barcode scanners can type into the same input, and manual entry is always available. QR/barcode presence alone is not proof of authenticity. The endpoint returns VALID or REVOKED; unknown or malformed numbers return 404.
- GET /api/v1/certificates/samples/{CLASSIC|MODERN|ACADEMIC}?format=png|pdf&language=hi|en — public design preview. These preview files are permanently marked SAMPLE / NOT VALID (or Hindi equivalent) and are not registered as issued certificates.
- GET /api/v1/admin/certificates?status=ALL|ISSUED|REVOKED&limit=100&offset=0 — administrator-only register.
- POST /api/v1/admin/certificates/{id}/revoke with JSON reason — administrator-only revocation. Revocation is persisted and audited; revoked documents cannot be downloaded as valid proof.

## Data and storage

Migration V58__student_certificates.sql stores the immutable issued details, template/language, artifact keys, sizes and SHA-256 hashes. PDF and PNG artifacts are atomically written below APP_CERTIFICATE_STORAGE_DIR (default /var/lib/quantaedge/media/certificates), inside the existing persistent quantaedge_media volume. The DB never stores user-provided filesystem paths. Public verification reveals only the recipient and certificate details shown on the document, not student contact details or their broader learning history.

The API returns binary attachments with Cache-Control: no-store and X-Content-Type-Options: nosniff. Never commit real student certificates or private learner data to Git.

## Operational notes

- The schema migration must run before certificate endpoints are used; normal Flyway startup applies it.
- Back up both PostgreSQL and the shared media volume. Database metadata without its corresponding artifact is intentionally treated as an unavailable certificate, not silently regenerated.
- The student result history, direct released-result view and progress page expose issue/view/download actions for the exact attempt. Parent result history exposes view/download only; parents cannot issue certificates on behalf of a child.
- Admins may retrieve a revoked original strictly as an audit copy. Student and parent view/download endpoints return a gone response for revoked certificates.
- Sample previews are suitable for design approval only. A certificate is not issued until an eligible achievement is verified.
- Assessment pass threshold can be changed with APP_CERTIFICATES_ASSESSMENT_PASS_PERCENT; the applied threshold is preserved in each issuance snapshot.


## Cross-screen consistency

When a student issues a certificate from a released test result, the same eligible assessment remains in the certificate-page dropdown. The picker labels already-issued records and opens the existing certificate rather than creating a duplicate. Revoked records remain visible with their revoked state and cannot be silently replaced. The dropdown's current API response intentionally includes `certificate_id`, `certificate_number`, and `certificate_status` for this purpose.
