# Course identity and commerce contract

**Status: mandatory architecture contract.** This document governs all course authoring, versioning, distribution, learner access, checkout, payment, parent purchase and analytics work. A change that violates it is a defect even if its local screen appears to work.

## 1. The identity card: exactly what each identifier means

| Field | Identifier type | What it identifies | Stable across a content upgrade? | May define purchase deduplication? |
|---|---|---|---:|---:|
| `institution_course_identity.id` | Canonical relational key | One logical course product | Yes | **Yes**, together with institution and learner/holder |
| `institution_course_identity.public_id` | Stable public UUID | The same logical course product outside the database | Yes | Yes, as the public representation of the same identity |
| `(source_institution_id, stable_course_key)` | Scoped business key | Logical course within its source institution | Yes | Yes only after resolving to the canonical identity FK |
| `curriculum_program.id` | Version-row FK | One immutable course content graph | No | **No** |
| `curriculum_program.public_id` | Version public ID | One particular content graph | No | **No** |
| `curriculum_program.code` | Internal version/business code | One course graph in its source convention | May change | **No** |
| `curriculum_program.content_version_no` | Version sequence | Content revision number for a logical identity | No | **No** |
| `institution_course_release.id` / `release_version` | Workflow ID / sequence | One submission of one content graph to source/HRMS review | No | **No** |
| `institution_course_assignment.id` | Workflow/relationship ID | Assignment of a specific release to an institution | No | **No** |
| `institution_marketplace_offer.id` / `public_id` | Commercial-listing ID / public UUID | One institution-specific listing revision, with its commercial lifecycle | No | **No** |
| `course_payment_order.id` | Transaction ID | One checkout attempt/order | No | **No** |
| Razorpay `order_...` / `pay_...` | Provider transaction IDs | One external order/payment | No | **No** |
| display name, translated title, price, class label | Presentation/terms | How a product is described/priced | May change | **Never** |

If any proposed field is not in this table, define its scope, owner, lifetime and collision rules before using it as a key.

## 2. Identity creation and versioning

- Every supported course must link to exactly one active `institution_course_identity` via `curriculum_program.course_identity_id`.
- One logical course identity is unique by source institution plus normalized stable business key; its public UUID is globally unique and never changes.
- A new content version is a new immutable `curriculum_program` row that references the same `course_identity_id`, with its own increasing `content_version_no` and explicit `supersedes_program_id`.
- A genuinely different course receives a different identity UUID, even if its title, class, subjects, price or syllabus resembles another course.
- Sharing a course with a destination institution creates a tenant assignment/offer; it does not create a second source identity. Access and commercial records must still include the destination institution as a separate scope.
- Release submissions, assignments, approvals and offers are lifecycle records, never substitutes for the course identity.

## 3. The purchase ownership key

For a student-facing paid/free course product, the logical ownership key is:

`(institution_id, course_identity_id, holder_student_id)`

For a parent-owned purchase that has not been transferred to a learner, the ownership key is:

`(institution_id, course_identity_id, holder_user_id)`

A checkout is additionally idempotent per target learner and logical course. New checkout/open-order uniqueness must be keyed by tenant + logical identity + target learner, **not** by `curriculum_program.id` or Marketplace offer ID. Parent purchase routes must preserve the intended recipient: buying for self, buying for a child, and transferring an entitlement are different operations.

The institution is part of these keys because course access and sale contracts are institution-scoped. Purchasing at one institution must not grant access at another institution merely because both use the same source identity.

**Consequences:**

- One learner cannot buy the same logical course again just because content v1 became v2 or offer #11 replaced offer #1.
- Two different logical course identities never become the same purchasable item because titles/codes happen to look alike.
- A new eligible content version can become the learner's current learning graph while the original acquisition, invoice and provider transaction remain attached to the exact version/offer bought.
- If the product ever intentionally sells a separately chargeable edition, model it as a separate logical identity/product or explicit entitlement tier. Do not infer a new charge from a content-version increment.

## 4. Transaction identity and immutable financial evidence

A purchase attempt must persist both:

1. **What product the user was buying:** stable `course_identity_id`, the exact `course_id` content graph/version, destination `institution_id`, and exact `institution_offer_id`.
2. **What financial contract was offered at that time:** price/currency, tax profile/calculation snapshot, approved revenue-share policy/split snapshot, discount facts, invoice/buyer snapshot, provider order/payment IDs, and timestamps.

Keep these facts immutable after order preparation, except for narrowly defined provider-confirmed status transitions and audited, safe legacy snapshot repair. Never fix an old-order mismatch by replacing its offer ID, course version, price, policy reference or buyer facts with current data.

## 5. Checkout decision order

1. Resolve the current eligible Marketplace offer and its course identity through explicit foreign-key relationships and current HRMS publication gates.
2. Verify the identity belongs to the selected content graph and tenant assignment; never infer it from request labels.
3. Check access/ownership against the stable identity plus institution and target learner. If the learner already owns/has access to the logical course, do not create another billable checkout.
4. Check for open checkouts across **all content versions** of that logical course for that target learner.
5. If an old order is tied to a different offer/version, query the provider using the saved provider order ID and verify amount/currency. If paid/authorised/processing/ambiguous, block another checkout and route the exact old order through verification/reconciliation. Only if it is conclusively unpaid and safe to retry may the old order be expired and a new order prepared.
6. For a new checkout, snapshot the exact current offer's approved financial terms and create one provider order. A database unique constraint must protect concurrent requests.
7. At payment verification, verify the original order/payment/amount/currency and original saved offer facts. Do not require a historical order to magically become the latest offer; reconcile it by its own contract. Never silently migrate its financial facts to a newer offer.
8. Grant ownership once, using the stable course identity; retain all payment/order rows as an append-only financial history.

## 6. Content upgrades and learner UX

- Publishing a later content version is not a new purchase event.
- A learner who owns or has an active entitlement for the identity must not see an actionable Buy path for another content version of the same product.
- Keep access/enrollment and ownership distinct: a free assignment, institutional entitlement, paid purchase and parent transfer can all grant access but have different authorities and audit evidence.
- Present one current course card per logical identity in a given institution/view. Detail screens may expose the current content version and older learning history.
- Do not destructively move enrollment, lesson progress, test attempts, results or certificates to new row IDs. Define an explicit, testable version/progress mapping; retain original provenance.
- Two tenants remain separate even when they share the same identity. Check tenant membership, source/assignment and publisher authorization on the server.

## 7. Legacy reconciliation

- Backfill `course_identity_id` only from `curriculum_program.course_identity_id` or another explicit validated relationship.
- If identity is null or multiple source identities disagree, fail closed and queue explicit reconciliation. Never guess by name, code, list position or similarity.
- Historical duplicate orders/purchases are evidence of money movement and must not be deleted or merged. Preserve their transaction IDs and amounts; deduplicate learner ownership, not financial history.
- When legacy open orders exist, check the provider first. Do not expire an order with unknown/paid/in-flight status.
- All repair/migration operations must be forward-only, auditable and safe to retry.

## 8. Required invariants and review checklist

Every change involving courses or billing must demonstrate:

1. Same `course_identity_id`, different content versions => same logical product and at most one ownership entitlement per holder/tenant.
2. Different `course_identity_id` values => independent products, regardless of equal names/class codes/prices.
3. Same identity, different institution => isolated access and billing per tenant.
4. A replayed API request is idempotent; concurrent requests cannot produce two payable orders for one target learner + logical identity + tenant.
5. Old pending, paid, authorised, retryable, failed and provider-unknown orders behave safely when an offer is superseded.
6. A course upgrade does not re-charge an existing holder and does not silently destroy old progress/certificates.
7. Parent self-purchase, parent-to-child purchase, and one-way transfer use the intended holder key.
8. Database constraints and server queries share the exact semantics; a UI-only check is never accepted as enforcement.

Any implementation that cannot prove these cases is incomplete. Update this contract only through a reviewed architecture change that explains the migration, compatibility and user-facing consequences.
