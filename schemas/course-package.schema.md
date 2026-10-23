# Course package schema v1 — reference

Machine-readable contract: `course-package.schema.json`.

## Scope

Version `quantaedge.course-package.v1` imports **K12 curriculum only**. It represents the complete hierarchy `course → subjects → chapters → lessons → teaching blocks`. The registry's `COMPETITIVE` and `ASSESSMENT` types advertise separate layouts, but their nested hierarchy importer is not part of v1; the importer rejects these types rather than placing them in K12 subject/chapter tables.

## Workflow

1. Submit a package to `POST /api/v1/admin/content/course-packages/validate`. This performs server-side structure, unique-code, order, supported-block and maximum-size checks without writing course data.
2. After a valid response, submit the same JSON to `POST /api/v1/admin/content/course-packages/import`. Both endpoints require the current Education Admin session, HRMS-bound institution, and `CONTENT_CREATE`; import additionally requires `CONTENT_EDIT`.
3. A successful import creates all content in one transaction, logs an import ledger entry and lifecycle event, and leaves the course release, every chapter and every lesson in `DRAFT`. Source references are stored but marked unverified.
4. The package is idempotent by `source institution + package_id + package_version + canonical content SHA-256`. Same ID/version and same content returns the original course/release. Different content under the same ID/version conflicts; increment the version to submit changed content.
5. HRMS release-quality review, origin approval, and Marketplace publication remain separate. Imported content is never student-deliverable at the end of the import transaction.

## Root fields

| Field | Requirement | Meaning |
|---|---|---|
| `schema` | Required; exact constant | `quantaedge.course-package.v1` |
| `package_id` | Required; unique lowercase slug per institution | Stable logical source-package identity |
| `package_version` | Required; 1–40 characters | Increment when content changes |
| `course` | Required object | Course code, display name, curriculum version, `K12` type, active `class_code`, optional stream |
| `subjects` | Required, 1–30 | Ordered subject track list; each subject includes chapters |
| `editorial` | Optional | Notes and source-rights metadata; rights clearance is checked again by the HRMS launch checklist |

## Nested fields and limits

| Level | Required fields | Constraints |
|---|---|---|
| Subject | `code`, `display_name`, `chapters` | Unique subject codes; up to 30 subjects; 1–1,000 chapters per subject |
| Chapter | `code`, `display_name`, `lessons` | Unique code within its subject; 1–2,000 lessons; optional description/order/source reference |
| Lesson | `code`, `title`, `blocks` | Unique code within its chapter; 1–200 blocks; optional summary, duration (1–120 minutes), order and source reference |
| Teaching block | `sequence_no`, `block_type`, `content` | Unique sequence within lesson; sequence 1–10,000; active flag defaults to true; block type must be a supported QuantaEdge content block |

All levels reject undeclared properties. Database identifiers, tenant ownership, release state, and approvals are generated or resolved server-side and cannot be supplied by JSON.

## Source references and rights

A chapter or lesson may contain a `source` object with `title`, `url`, `edition`, and `pages`. These fields preserve provenance and are not considered verified by import. `editorial.source_rights_status` may be `UNCONFIRMED`, `CLEARED_FOR_INTERNAL_USE`, `CLEARED_FOR_PUBLIC_REPOSITORY`, or `DO_NOT_REDISTRIBUTE`. Package import may create a draft while rights are unresolved, but HRMS Marketplace launch is blocked until the imported package has an internal-use or public-repository clearance. `DO_NOT_REDISTRIBUTE` is not a publishable rights state.

## Validation notes

The importer rejects unsupported course types, unsupported block types, duplicate codes or block sequence numbers, empty subject/chapter/lesson/block collections, malformed order values, extra fields, and packages exceeding hierarchy-size limits. Validate the exact JSON text immediately before importing; editing the package invalidates the UI validation state.
