# Entity identity and reference contract

## Purpose

Every application surface must identify the same domain entity consistently. Do not use an internal database row ID, a display name, a version number, or a workflow-record ID as a substitute for the entity's stable identity.

## Rules for every entity

1. **Database ID (\`id\`)**: internal relational key. Use it for joins and foreign keys. Do not present it as a business identifier in UI copy or URLs when a stable public identifier is available.
2. **Public ID (\`public_id\`)**: stable external reference for that entity. It must remain unchanged for the entity's lifetime. Use it in client URLs, external integrations, and references that need to survive database movement.
3. **Business key/code**: human-readable identifier with an explicitly defined uniqueness scope (for example, source institution + course key). Normalize and enforce its uniqueness in the database. Never derive entity identity from a mutable name or assume a code is globally unique when its scope is tenant-specific.
4. **Display name**: label only. It can be translated or renamed; it is not a key.
5. **Versioned entity**: keep the stable logical identity separate from the immutable version/content graph. A version gets its own entity ID/public ID and references the stable identity; it must not replace the logical identity.
6. **Workflow records**: release submissions, assignments, offers, approvals, requests, attempts, and other lifecycle records have IDs of their own. Those IDs identify the workflow record only. They are not the business entity, content version, or status.
7. **Ownership and tenant scope**: resolve and authorize references using the owning entity, tenant, and relationship constraints. Never trust a client-supplied name, institution code, or numeric ID alone to grant access.
8. **Legacy reconciliation**: only normalize or link old identifiers when source ownership and uniqueness are unambiguous. Do not auto-merge records because their display names happen to match. Route ambiguous records through an explicit reconciliation path.
9. **UI contract**: user-facing lists group by logical entity identity; detail/edit/review screens may show meaningful business keys and content versions, but not raw database row numbers. If a workflow sequence is useful, label it accurately (for example, “Submission 2”), not “Course 2.”
10. **API contract**: include stable public IDs and scoped business keys when clients need to retain a reference across requests. Return version and workflow metadata as separate named fields; do not overload a single \`id\` or \`version\` field with multiple meanings.

## Mandatory implementation companion

All coding agents must read the repository-root `AGENTS.md` and the mandatory [Course identity and commerce contract](course-identity-and-commerce-contract.md) before changing a course, release, offer, checkout, payment, enrollment or learning-history path. The contract defines the ownership key, upgrade behavior, immutable transaction facts and required negative cases.

## Course identity mapping

| Field | Meaning | Stable across a content fork? | User-facing use |
|---|---|---:|---|
| \`institution_course_identity.public_id\` | Public identity of the logical course | Yes | Preferred stable course reference |
| \`institution_course_identity.stable_course_key\` | Source-institution-scoped business key, such as \`CLASS_6\` or \`TRE4\` | Yes | Human-readable course key, paired with source institution when needed |
| \`curriculum_program.id\` / \`curriculum_program.public_id\` | A specific curriculum/content graph | No: a content fork creates a new graph | Internal joins; public version-specific reference where required |
| \`curriculum_program.content_version_no\` | Content version within the logical course identity | Increments on a new content graph | “Content v1”, “Content v2” |
| \`institution_course_release.id\` | Database ID of one release-workflow row | No | Internal joins only |
| \`institution_course_release.release_version\` | Submission/release sequence for a given content graph | Separate from content version | “Submission 1”, “Submission 2” where the workflow context needs it |
| \`institution_course_assignment.id\` | Database ID of the institution-to-release assignment row | No | Internal joins only; never “Course #12” |
| \`institution_marketplace_offer.id\` | Database ID of one offer/configuration | No | Internal joins only; use the course identity and offer status in UI |

### Course list and lifecycle behaviour

- One logical course identity appears at most once in a course list for a given institution and view.
- Content versions and release submissions remain separate for auditing and approval. Their presence must not create duplicate course cards.
- A newer content version is a new immutable graph linked to the same logical course identity. Re-submitting the same graph creates a new release submission sequence, not a new content version.
- A course identity does not imply publication. Marketplace discovery still requires a current, shareable release, a valid current fingerprint, an active institution assignment, a ready registered course type, and explicit HRMS publication.
- **Purchase ownership and access are keyed to the stable logical course identity plus institution and holder, not to a version row or Marketplace offer.** Publishing content v2 must not create a second billable product for a learner who owns content v1.
- The exact purchase/order, invoice, tax/share/discount snapshots, payment provider IDs and source `course_id`/offer revision remain immutable transaction history; they are never rewritten to a new offer to repair an identity mismatch.
- Learner progress, assessment attempts/results and certificates retain their version provenance. A newer content graph must use an explicit, validated mapping to show carried-forward progress; do not move or delete historical learning records to make identities line up.
- A destination institution is an independent access/billing scope even when it shares the same source course identity. A different `course_identity_id` must never be merged based on names, titles, class labels or price.
- The HRMS launch workflow should show one current course card per identity. Assignment/release IDs are implementation details; their lifecycle can be inspected in audit history.

## Required review question

When adding a list, endpoint, relation, or UI label, ask: **“Am I identifying the domain entity, one immutable version of it, or a workflow record?”** Name and key the field accordingly. If the answer is unclear, do not reuse an existing generic \`id\` or \`version\` field.
