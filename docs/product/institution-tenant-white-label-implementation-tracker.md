# Institution tenancy, client reporting and white-label delivery — implementation tracker

Status: **IMPLEMENTATION IN PROGRESS; mandatory security, billing, web/Android and CI acceptance gates remain open.**  
Canonical epic: [#67 — HRMS-owned institutional tenancy, white-label app and enrollment billing](https://github.com/Manav326/QuantaEdge/issues/67)  
Repository: `Manav326/QuantaEdge`; target branch: `main`.

Keep this file in sync with #67 on every handoff. Do not check off a task because a route or UI placeholder exists: require API authorization, persistence, migration/upgrade compatibility, negative-path tests and the acceptance evidence stated below.

## Current implementation checkpoint — 2026-10-17

This is a working implementation slice, **not a release approval**. The following has been added to `main`; acceptance boxes below remain unchecked until CI and negative-path tests prove it.

| Area | Current code / behavior landed | Still must be verified or finished |
|---|---|---|
| Tenant schema | Tenant, separate institution-admin account/session, tenant branding, centrally granted course entitlements, join codes, learner memberships, membership/audit events, billing contracts and append-only billable events in `V120__institution_tenant_white_label_foundation.sql`. | Apply V120 on a clean database and an upgraded database; review FK/constraint correctness, grants, accounting rules and rollback/recovery. No tenant/sample data was seeded. |
| Identity boundary | Added the `INSTITUTION` workspace/cookie path to existing auth scoping; institution sessions use their own session table and do not become staff/HR identities. `QE_PLATFORM_OWNER` is an explicit permission key scoped to HRMS. | Prove login, refresh, logout, password change, session revocation, origin/CSRF checks, and API denial for HRMS non-owner and cross-workspace callers. Verify the intended owner grant maps to the actual HRMS bootstrap account. |
| Institution APIs | `InstitutionAuthService.java`, `InstitutionTenantController.java` and `PlatformInstitutionController.java` implement separate login, tenant-only reports, code redemption, owner-only provisioning/status/admin/course/brand/contract operations and audit. | Compile/integration tests; ensure every query matches production schema; prove tenant A cannot read tenant B; harden report pagination and high-volume limits. Exact home addresses are not currently in the learner profile—the roster currently exposes city/state only. |
| Membership/course gates | The current branch also contains institution membership and course grant enforcement in the course/learning paths, including checks against inactive/suspended institution grants, plus learner self-service institution membership UI. | Audit all remaining lesson, structured-lesson, assessments, tutor, downloadable media, live class, certificates, marketplace purchase, progress and Android endpoints for alternate paths. Existing personal purchases must remain valid when unrelated to institution access. |
| HRMS owner UI | `apps/people/app/institutions/page.tsx` and `Institutions.module.css`; navigation is gated on `QE_PLATFORM_OWNER`. It provides tenant overview, provisioning, course entitlement, admin, branding, contract, join-code and audit controls. | Run type/lint/build and role-matrix tests. Asset storage/upload and launcher icon switching are not delivered by storing an asset key. Verify every owner action has a corresponding server permission check. |
| Institution portal | `apps/web/app/institution/page.tsx` provides institution identity resolution, separate sign-in, initial-password change, read-only roster/progress/authorised-course views and code copy/share. `SessionBoundary.tsx` handles the institution workspace header. | Verify cookie behavior and refresh/logout, data fetch after first password change, live brand logo/theme rendering, empty/denied states, tenant switch/cache isolation, responsive/accessibility tests, and route smoke tests. |
| Runtime configuration | Added `APP_INSTITUTION_CODE_ENCRYPTION_KEY` wiring to application config and Compose examples, plus an institution-origin setting. Join codes are hash-indexed and encrypted for controlled reveal/copy. | Configure secrets in each actual deployment; prove rotating/redeploying with the same secret works and secret rotation has an explicit re-encryption/revocation plan. Never commit a real secret. |
| Migration sequencing | Initial migration name conflicted with the existing V102. It has been moved atomically to `V120__institution_tenant_white_label_foundation.sql` after inspecting the live migration directory (V119 was the highest existing version). | Confirm the current tree still has exactly one migration per version and that Flyway validates/applies it successfully. |

**Latest known CI at this checkpoint:** [QuantaEdge Build and Publish run #39046383655](https://github.com/Manav326/QuantaEdge/actions/runs/39046383655) was *in progress* for migration-fix commit `4406fc5e34dc8ac0bae5adb8bbfef3290641a146` when last checked; [the current tracker commit run #39046451900](https://github.com/Manav326/QuantaEdge/actions/runs/39046451900) was *pending*. The earlier run for learner self-service commit `3034cfc18dfee6e2228c3fc1d48e36a9385bb784` failed because Flyway found **two V102 migrations** (`V102__additive_public_user_id.sql` and the institution foundation file), preventing Spring context startup. The institution migration was atomically moved to `V120__institution_tenant_white_label_foundation.sql` in commit [4406fc5e](https://github.com/Manav326/QuantaEdge/commit/4406fc5e34dc8ac0bae5adb8bbfef3290641a146), after checking the live directory's existing highest version was V119. Await the new run's result and continue API tests; green CI is not yet claimed.


## 1. Domain model and source of authority

| Principal | What it represents | Allowed | Explicitly not allowed |
|---|---|---|---|
| Platform owner, authenticated through the HRMS authority | QuantaEdge's owner/operator. It is a product-level capability granted to explicitly selected HRMS owner/admin identities, not an employee designation shortcut. | Cross-institution economics and progress reporting; provision/suspend institutions and institution admins; choose products/courses; control publication through the existing central content-management boundary; manage tenant branding, join-code policy, contracts and billing; audit access. | No blanket grant to every employee or manager because of HRMS role/title. |
| Institution administrator | A paying school/college customer and a separate principal—not an HRMS employee or QuantaEdge staff identity. | Read that institution's roster, memberships, allowed/published course catalogue, enrollment and learner progress; see address only within that institution and under an explicit audited capability; view/copy the join code issued by the platform. | No course authoring/edit/publish/unpublish; no user/learner creation or suspension; no manager/role/staff creation; no entitlement/brand/billing changes; no access to another tenant. |
| Learner / guardian | Existing learner or guardian identity. A learner can have multiple institution memberships. | Join an institution via its valid code/link; use only the course entitlements actually granted to the learner/institution, subject to existing publication, purchase and account rules. | A code cannot bypass course publication, suspension, individual safety restrictions or other access policies. |
| QuantaEdge education/content staff | Existing platform staff identity. | Continue using explicitly granted central content/product permissions. | Institution membership or institution-admin status cannot grant platform staff access. |

### Model boundaries

- **Institution tenant** is the ownership/scope boundary for institution admins and student reporting.
- **Login identity** is distinct from employment, tenant membership and course access. Never re-purpose `hr_employee` or add institution clients to the platform-only `staff_account` directory.
- **Institution-admin principal/session** must be distinct from staff, HRMS, guardian and learner workspaces; do not encode it as `ADMIN` or rely on frontend route visibility.
- **Learner–institution membership** associates the canonical learner ID with a tenant and retains join/leave/suspension history. The same learner can belong to multiple institutions, each with independent visibility and billing attribution.
- **Institution course entitlement** is a centrally-managed allow-list and effective-dated policy over published course/program IDs. It is not a client-editable field. Student access must retain existing assessment, publication, purchase and subscription checks.
- **Join code** is an onboarding capability, not a tenant ID or authorization token after redemption. Store a hash, not the usable secret. Support expiry, rotation/revocation, optional use caps, rate-limits, audit and idempotent retries. Do not leak whether a student account exists through responses.
- **Billable enrollment** is a separate immutable event/ledger relation. Default billing contract: count each distinct learner–institution membership at most once per institution and billing period after the defined billable activation point; re-clicks/retries or rejoining the same membership do not double count. A learner at two different institutions is countable for each tenant under its own agreement. Platform owner configures price, period and billable activation rule before invoices are issued.
- **Branding** is centrally controlled per tenant and separately cached/namespaced. A valid code/link resolves tenant context before onboarding or learner UI. Never trust a user-provided logo URL, arbitrary CSS or tenant ID without a server-resolved context.

## 2. Locked Google Play / Android distribution decision

Google Play's official documentation advertises up to **50 custom store listings** per app and supports a tailored listing name/icon/graphics. These listing pages do not themselves change the icon/name baked into the installed APK. See [Google Play custom store listings](https://support.google.com/googleplay/android-developer/answer/9867158) and [Custom store listings overview](https://google.play/business/customstorelistings/).

For the 50-institution target:
1. Use one shared application ID and the appropriate institution-specific custom listing URL for discovery.
2. Implement branded in-app login/sign-up/learner UI from the institution code/deep link/QR.
3. For device launcher branding, assess and implement up to 50 pre-packaged, reviewed launcher icon/name aliases switched only after the tenant is safely resolved; test restarts, upgrades, OEM launcher delays and a single visible launcher entry. Do not download arbitrary remote launcher icons.
4. Document that before the first code/link resolution, a one-binary app cannot reliably know which institution launcher icon to display. If a tenant requires a unique icon/name immediately on first install, this needs a separately branded binary/package or a distinct distribution solution; custom listing URLs alone do not satisfy it.
5. Keep required developer identity, store disclosures, privacy policy and legally required notices truthful, even when the learner-facing product UI is white-labelled.

The approved QuantaEdge central branding system remains intact for QuantaEdge operator tools. Tenant branding is data-driven and isolated; it is not a forked per-screen palette or a source of untrusted CSS.

## 3. Actionable implementation plan

### T0 — Repository and workflow baseline
- [ ] Trace current main commit and newest Flyway migration; identify overlapping in-flight work before adding migrations.
- [ ] Trace `AuthContext`, `AuthService`, `AuthorizationService`, session filter/cookies/workspace headers, HRMS permission catalog and HRMS login.
- [ ] Trace student/guardian signup and phone OTP / Google sign-in paths, identity mapping, course signup, `student_program_enrollment`, `student_track_enrollment`, purchases, course publication and current billing/payment contracts.
- [ ] Trace current central Admin content/course/marketplace API boundaries, branding component, web routing and Android manifest/launcher resources.
- [ ] Record exact path/table/endpoint map in this section or a linked architecture note. Do not guess the latest migration version.

### T1 — Additive data model and migration
- [ ] Add institution tenant identity, unique public tenant code/slug, status/lifecycle timestamps, legal/display metadata and audit.
- [ ] Add a separate institution-admin identity and tenant binding with independent session/credential lifecycle. Ensure unique scoped login, password/OTP recovery, suspension, logout/session revocation and no staff permission inheritance.
- [ ] Add explicit, effective-dated institution-to-course/program entitlements, granted/revoked by the platform owner only.
- [ ] Add hashed join-code records, expiry/use policy, version/revocation metadata and redemption/audit events.
- [ ] Add learner–institution membership, status history, source attribution, idempotency keys and a uniqueness constraint preventing duplicate active/chargeable membership.
- [ ] Add append-only billable enrollment events and invoice-period/line snapshots. Keep monetary units/currency explicit; never compute invoices from client-side counters alone.
- [ ] Add tenant brand configuration + safe asset metadata/version. Keep file storage private and validated.
- [ ] Add useful indexes and FK/check/unique constraints. Do not edit released migrations or create production institution/student/demo records.

### T2 — Authentication and permission boundary
- [ ] Add a distinct institution workspace/session identity and its own cookie/session scope; keep cross-workspace session isolation and revocation.
- [ ] Add an explicit HRMS-granted platform-owner capability; only named/approved owner identities receive it. A generic HR employee, manager or HR permission is not enough.
- [ ] Audit all control-plane and tenant-facing APIs: require correct workspace, active identity, active institution binding, permission and server-resolved tenant ID per request.
- [ ] Exclude institution principals from staff provisioning, HR endpoints, Admin staff/permission screens, CMS writes and role creation.
- [ ] Use consistent, safe authorization errors and avoid leaking tenant/student existence.

### T3 — Platform owner controls
- [ ] Create/view/update/activate/suspend institutions with actor, timestamp, reason and before/after audit.
- [ ] Provision, rotate/revoke and suspend institution-admin identities centrally. Institution admins cannot invite or create other admins/managers.
- [ ] Assign/suspend published products/courses for each institution with effective dates and history.
- [ ] Configure tenant identity, join policy, approved brand assets, contract terms and billing period.
- [ ] Preserve existing central lesson/content authoring, reviewing, publishing, marketplace, subscription and infrastructure controls; do not duplicate these as customer-side capabilities.

### T4 — Signup, join-code and membership lifecycle
- [ ] Support joining during signup and after login for existing students, using code/deep link/QR.
- [ ] Bind redemption to the authenticated/created learner only; validate code + tenant status + expiry/use cap and transactionally create or reactivate membership idempotently.
- [ ] Support students who already exist and multiple legitimate institutions. Do not clone learner identities to make tenant separation work.
- [ ] Handle disabled tenant, revoked/expired/exhausted code, duplicate redemption, network retry and suspended student safely.
- [ ] Make code visibility/shareable QR available to tenant admin only as read-only copy/share; code policy changes stay central.

### T5 — Course access and attribution
- [ ] Enforce centrally managed institution course allow-list server-side on every tenant-derived learner-access path.
- [ ] Determine how institution membership produces a learner's course enrollment/entitlement without altering direct purchase paths or granting unapproved/draft/unpublished material.
- [ ] Attribute each institution-derived enrollment to the source institution and effective period; preserve independent personal purchases and course progress.
- [ ] Test that tampering with `institutionId`, `studentId`, `courseId`, code or request body cannot extend scope.
- [ ] Ensure publishing/unpublishing and tenant entitlement changes take effect under the existing authorization policy and are audited.

### T6 — Institution web portal (read-only client workspace)
- [ ] Add dedicated institution sign-in and tenant dashboard; determine tenant from the authenticated binding, not a URL/body parameter.
- [ ] Show read-only metrics: total/current memberships, course entitlements, enrollment trend and progress summary with clear date ranges.
- [ ] Show tenant-only learner roster and permitted learner details (name, class, enrollment status, address where needed, and progress). Restrict high-sensitivity fields and exports; log access where applicable.
- [ ] Show only centrally authorized and student-published courses/parts; no course-content mutations or authoring tools.
- [ ] Remove/omit create user, disable user, manager, roles, staff, course entitlement, branding and billing write actions at both API and UI.
- [ ] Include clear loading, empty, denied, stale-data and error states; responsive web UI and accessible tables.

### T7 — HRMS platform overview and operator controls
- [ ] Add cross-institution view in the HRMS/platform-owner workspace: institution status, contract, billable enrollments, financial totals/invoices, authorized courses, progress and engagement summaries.
- [ ] Drill down only to relevant learner data; clearly distinguish aggregate metrics from identifiable records.
- [ ] Wire platform control actions through explicit owner permissions and preserve current QuantaEdge Admin controls where those belong.
- [ ] Add audit view for institution status, course access, brand, join-code, privileged data reads and billing operations.
- [ ] Ensure other HRMS users without the specific owner capability cannot reach global data via navigation, direct URL, API, export or guessed tenant ID.

### T8 — Billing and reconciliation
- [ ] Lock configuration for effective billable activation point, billing cadence, unit price, taxes/fees if applicable, currency, contract and credits before issuing invoices.
- [ ] Make membership activation -> billable ledger insertion transactional and idempotent; cancellations/credits are compensating events rather than silent historical edits.
- [ ] Generate period snapshots/line items from the ledger with deterministic totals and clear count definition; unique learner–institution count must agree with ledger.
- [ ] Add reconciliation and audit; test concurrent redemption, retries, membership reactivation, duplicate invoice attempts and period boundaries.
- [ ] Do not claim revenue or produce production invoices from mock data.

### T9 — White-label web and Android
- [ ] Define versioned tenant branding contract: approved display name, logo/mark, optional theme tokens, app-link/store-listing URL and safe fallback.
- [ ] Resolve tenant by trusted invite/link before displaying branded onboarding; cache branding only under tenant ID + version and clear/switch it on logout/tenant change.
- [ ] Apply institution brand consistently to relevant learner web and Android surfaces while keeping QuantaEdge operator UI on the centralized platform brand.
- [ ] Keep internal API/product diagnostics and QuantaEdge marketing copy out of the tenant-branded learner UI; maintain required legal/developer disclosures.
- [ ] Configure and validate custom Play listing URLs (up to 50); distinguish store-listing identity from installed launcher icon/runtime brand in documentation.
- [ ] If using packaged icon aliases, test correct initial default, one icon enabled, post-code switch, app restart, Android upgrade and OEM behavior. No false promise that a Play listing automatically changes the installed icon.
- [ ] Test Web/Android logout, offline start, account switching and multiple institution memberships to prevent cross-tenant brand or data cache bleed.

### T10 — Privacy, audit and operations
- [ ] Apply purpose limitation and least-privilege exposure to addresses, guardian/contact details, learner identifiers and exports.
- [ ] Add append-only audit records for tenant/admin lifecycle, code operations, course grants, privileged data reads, billing, branding and access-denied abuse signals.
- [ ] Add rate limits for login, OTP, code guessing, join redemption, admin reports and downloads; secrets and PII must not enter logs.
- [ ] Define retention/deletion/archive behavior consistent with accounting/audit needs; never hard-delete billable/audited history on leave or tenant suspension.
- [ ] Verify backup/restore and restore of tenant configuration/billing ledger; metrics degrade safely if an analytics query fails.

### T11 — Verification and controlled release
- [ ] Migration applies to a clean database and upgrades a representative current schema with no re-used version.
- [ ] API permission matrix covers platform owner, HRMS non-owner, institution admin A, institution admin B, learner, guardian, education staff and unauthenticated caller.
- [ ] Negative tests cover cross-tenant roster, learner, address, course, progress, billing, brand, code and export access—including client-supplied ID tampering.
- [ ] Signup/join tests cover new and existing account, multiple memberships, duplicate concurrent requests, expired/revoked codes, suspended tenant/student and retry after timeout.
- [ ] Course tests cover unpublished, unentitled, suspended, paid direct purchase and tenant-derived access.
- [ ] Billing tests cover duplicate/retry/concurrency, no duplicate bill, period cutover, reversal/credit and ledger-to-invoice reconciliation.
- [ ] Branding tests cover missing/invalid assets, theme contrast, offline cache, tenant switching, logout and Android launcher aliases.
- [ ] Run API unit/integration tests, web lint/build/tests, Android build/tests, migration checks and applicable accessibility checks.
- [ ] Verify GitHub Actions against exact `main` SHA; record commit, run links, statuses and blockers here and in issue #67. Leave tasks unchecked if code/tests/CI are not verified.

## 4. Verified integration touchpoints found during remote audit

This is a focused list, not a substitute for completing T0/T11 route enumeration.

- `services/api/src/main/java/com/quantaedge/api/AuthContext.java` — core identity principal still models staff/user/student; institution admins deliberately use a separate principal record and token/session table.
- `services/api/src/main/java/com/quantaedge/api/AuthService.java` and `AuthFilter.java` — standard workspace normalization, cookie selection, origin checks and workspace routing. Institution workspace is now reserved from ordinary staff/learner sessions and restricted to its API namespace.
- `services/api/src/main/java/com/quantaedge/api/AuthorizationService.java` — explicit `QE_PLATFORM_OWNER` permission is recognised; every platform-owner API must also require the HRMS workspace.
- `services/api/src/main/java/com/quantaedge/api/InstitutionAuthService.java` — separate password/session auth, rate-limit counters, join-code hashing + encrypted reveal, membership redemption and billable-event creation/reconciliation helper.
- `services/api/src/main/java/com/quantaedge/api/InstitutionTenantController.java` — client sign-in, password change, tenant-only roster/course/summary reports, branding lookup and learner code redemption.
- `services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java` — HRMS owner API for tenant/admin lifecycle, central course entitlement, branding, contracts, join-code rotation, scoped roster/audit and ledger reconciliation.
- `services/api/src/main/resources/db/migration/V120__institution_tenant_white_label_foundation.sql` — additive schema. The remote migration directory had V119 as its highest existing migration; V120 is the new tenant migration. Flyway execution is still unverified.
- `services/api/src/main/java/com/quantaedge/api/StudentProgramController.java` and `LearningController.java` — existing course enrollment and learning runtime where institution grant gating intersects with consumer purchase/access behavior. Review every path, including structured learning and assessments.
- `services/api/src/main/java/com/quantaedge/api/AdminStaffController.java` — existing education-platform staff management; institution clients must not be added to this API.
- `apps/people/app/institutions/page.tsx`, `apps/people/app/institutions/Institutions.module.css` and `apps/people/app/components/PeopleWorkspaceLayout.tsx` — owner console and permission-gated HRMS navigation.
- `apps/web/app/institution/page.tsx` and `apps/web/app/components/SessionBoundary.tsx` — institution-branded customer workspace and session header selection.
- `apps/web` and `apps/android` — further learner views and brand/cache paths still require end-to-end review. Android white-label launcher/icon switching is not complete.
- `.env.example`, `.env.production.example`, `docker-compose.yml`, `docker-compose.prod.yml` and `services/api/src/main/resources/application.yml` — runtime secret/origin wiring; live deployment configuration still needs secure secret provisioning.


## 5. Implementation journal

| Date | Status | Evidence / next action |
|---|---|---|
| 2026-10-17 | Specification and epic locked on GitHub | Issue #67 created. |
| 2026-10-17 | Initial backend/schema and web/HRMS UI slice committed to `main` | Separate institution accounts and session table; tenant membership/course entitlement/branding/audit/ledger schema; institution and HRMS-owner API controllers; HRMS owner page; institution portal; config; explicit workspace/permission boundary. See the current implementation checkpoint above. All acceptance boxes remain open pending tests and CI. |
| 2026-10-17 | Migration version conflict discovered in failing CI and corrected | Run #39046023710 logs confirmed Flyway failed application startup because V102 was duplicated. The tenant foundation was atomically moved to V120 after reading the live migration directory (highest prior file V119). Commit: [4406fc5e](https://github.com/Manav326/QuantaEdge/commit/4406fc5e34dc8ac0bae5adb8bbfef3290641a146). Replacement run #39046383655 was in progress at last check; its result remains unverified. |
| 2026-10-17 | Tenant course access integration also appeared on current `main` | Commits [edecb93](https://github.com/Manav326/QuantaEdge/commit/edecb9386cf7e846a1aff34aca54171778eb1f26), [2362fbf](https://github.com/Manav326/QuantaEdge/commit/2362fbfdcf5ace517e9f9ed7b9773d64761438c1), [d524313](https://github.com/Manav326/QuantaEdge/commit/d52431363e59648a01df46104143b10730e4ed9d) and [3034cfc](https://github.com/Manav326/QuantaEdge/commit/3034cfc18dfee6e2228c3fc1d48e36a9385bb784) extend tenant-derived learning access, suspended-tenant handling and learner institution self-service. Their full regression coverage and the failed Actions run #39046023710 still need review. |
| 2026-10-17 | Next | Inspect the failed Actions job logs; validate the current run; add service/controller/authorization and migration tests; finish audit of every alternate course-access endpoint; implement Android branding; complete billing reconciliation/invoice lifecycle and close each acceptance gate only with evidence. |

## 6. Definition of done

The epic is complete only when all mandatory tasks pass, existing consumer signup/purchase/content journeys remain intact, institution A cannot obtain institution B's data by any API or client manipulation, billable counts reconcile from an auditable server ledger, web and Android branding resolve safely, the exact final `main` commit has green GitHub Actions, and the evidence is recorded in #67. No synthetic institution/student/financial records are to be seeded to make the demo look populated.
