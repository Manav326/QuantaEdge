# Phase 1 — API, Workspace and Permission Boundary Map

**Status:** Partial source review; method-level coverage is not exhaustive.  
**Purpose:** Give the UI audit an evidence-based backend map for identity, tenancy, commerce, learning and HRMS, and identify the negative tests needed before synthetic data is installed.

## 1. Authentication and workspace enforcement

### Shared authorization primitives

Source: `services/api/src/main/java/com/quantaedge/api/AuthorizationService.java`.

Observed behavior:
- `requireAuth` rejects missing/unauthenticated context.
- `requireStudent`, `requireParent`, and `requireAdmin` enforce the relevant identity class before route-specific work.
- `hasPermission` treats `HR_*`, `HR_SELF_SERVICE_VIEW`, `TEACHING_ASSIGNMENT_VIEW`, `CLASS_SCHEDULE_POLICY_MANAGE`, and `QE_PLATFORM_OWNER` as HRMS-scoped permissions. A non-HRMS workspace cannot satisfy them.
- A global Educational Admin context does not automatically satisfy HRMS-only capabilities.
- Employee role/permission grants are queried live from `staff_account`, `staff_permission_catalog`, `staff_permission_grant`, and `staff_additional_role` / `staff_role_permission`, with workspace scoping.
- `requirePermission` rejects callers without the required live grant.

### Workspace filter

Source: `AuthFilter.java`.

Observed guard rails include:
- Institution workspace calls outside the permitted institution/auth route families are rejected with `WORKSPACE_MISMATCH`.
- HRMS endpoints require an HRMS-scoped session.
- An HRMS-scoped request is denied access to Educational Admin and learner endpoints outside auth.
- Authentication operations are checked against workspace with `AUTH_OPERATION_SCOPE_MISMATCH`.
- Learner cookie compatibility is workspace-checked so a parent cookie must not be treated as a student session.

**Required tests:** attempt the same protected request with no session, a learner session, parent session, Educational Admin session, HRMS session, institution-admin session, expired session and revoked/suspended account. Verify both HTTP/API outcome and that no unauthorized record or mutation is returned.

## 2. Institution/tenant boundary

Sources: `InstitutionTenantContextService.java`, `InstitutionTenantController.java`, `PlatformInstitutionController.java`, tenant migration family.

Observed behavior:
- `InstitutionTenantContextService.resolve` validates a selected institution code and resolves only an active tenant. It returns no guessed default when no tenant was selected.
- `requireSelector` rejects a request without the required institution selector/context.
- `assertActiveMembership` checks the signed-in student's active membership for the exact selected institution. For parents, it requires an active guardian-child link with consent and an active institution membership; specific child routes still require target-child validation.
- `/api/v1/institution/auth/*` is a separate institution-admin session lifecycle.
- Institution public branding and asset routes are unauthenticated by design; public branding must not grant institution membership or admin privileges.
- Platform institution methods visibly call `requirePlatformOwner` for theme presets/previews, overview/catalog, institution list/detail/create, profile/status, institutional admin provisioning/status, course entitlements/releases/review, branding/assets, billing contracts/reconciliation, join-code rotation, roster and audit events.

**Required tests:** create at least two synthetic institutions. Confirm each user's allowed reads/writes for its institution and explicit denial when a course, learner, guardian, teacher, report, media item, join code or resource is referenced across the boundary. Verify the tenant selector alone does not create membership. Verify non-platform users cannot call platform institution operations even if they can load or guess the URL.

## 3. Endpoint family to guard map

This is a **representative method-level map based on controller source inspection**. The [API route inventory](./phase-1-api-route-inventory.csv) now lists 435 Spring request/STOMP mapping entries from 48 Java source files and links each to candidate workflow scenario IDs. Those scenario mappings and guard hints are static signals, not a complete OpenAPI/permission catalog; shared filters, helper authorization, tenant/resource predicates, DB effects and runtime behavior must still be reviewed per operation before Phase 1 sign-off.

| Domain/controller | Observed route family / authorization pattern | Important negative/edge tests |
|---|---|---|
| `AuthController` | `/api/v1/auth/*`: Google config/link/sign-in, account signup OTP request/verify, account login, OTP, HRMS login/password change, parent/student/learner login, session selection/me/refresh/logout. Auth routes use explicit workspace/client context as applicable. | Invalid Google audience/signature; unverified Gmail claim; expired/wrong OTP; OTP throttling; duplicate identity; workspace mismatch; parent token used as student; logout invalidates session. |
| `AccountProfileController` | `/api/v1/account/me/profile`, `/username`, `/password`; methods call `requireAuth` and account-ID validation. | Another account's ID, invalid/duplicate username, cooldown, password policy, unauthorized fields, stale session. |
| `GuardianController` | `/api/v1/guardians/*`; parent guard on account/profile/children/report/create/update/archive/restore. Child track/program/enrollment paths also call tenant/guardian-child scope checks. | Unlinked child IDs, inactive/archived child, missing consent, institution mismatch, cross-family request, restore/archive idempotency, role switched incorrectly. |
| `InstitutionTenantController` | `/api/v1/institution/auth/*`, public branding/assets, student institution join/membership, institution reports. Institution-admin routes use the separate principal/session; join requires student identity and report routes require an institution principal. | Invalid tenant code, inactive tenant, invalid join code, unauthenticated report, learner misuse of institution-admin cookie, cross-tenant roster/report access. |
| `PlatformInstitutionController` | `/api/v1/hrms/platform/institutions/*`; observed methods require platform-owner permission. Includes create/update/status, institution admin grants, courses/releases, branding/assets, billing, join-code rotation, roster and audit. | Non-owner denial for every mutating/read-sensitive action; invalid asset/oversize upload; duplicate code; unauthorized billing/status changes; audit history retained. |
| `CourseMarketplaceController` | `/api/v1/marketplace/*`; student or parent guards; tenant checks on student/family listings, enrollment, entitlements, transfer, orders and verification; admin offer routes require admin. Order creation accepts idempotency key. | Hidden/unpublished offer, absent entitlement, course/tenant mismatch, cross-child entitlement, duplicate order/replay, wrong amount/currency/signature, cancel/failure/retry, unauthorized transfer. |
| `ParentCoursePurchaseRequestController` | Student purchase request and parent view/dismiss endpoints; tenant and caller-role guards. | Student cannot dismiss/approve as parent; parent cannot access another family's request; expired/cancelled request; repeat dismiss. |
| `AdminContentController` | `/api/v1/admin/content/*`; authorization helper on catalog/CRUD plus explicit auth/permission guards on review queue/history, chapter/lesson publish/unpublish, source verification, preview acknowledgement and question review. | View-only role attempts write/publish; editor attempts review if separate grant required; reviewer attempts publish without grant; incomplete source reference; repeated transitions. |
| `AssessmentController` | `/api/v1/admin/assessments/*` and review/grade routes use content-view, permission, assignment or grader guards. Student catalog/attempt/answers/submit/results/leaderboard use student/session and attempt ownership logic. | No-grader grade; cross-student attempt IDs; submission replay; answer modification after closed/submitted attempt; unpublished assessment; offline token expiry; learner sees wrong ranking scope. |
| `LearningClassController` | Admin class routes use class-view/manage guards; student class listing checks student course scope; teacher routes require authentication and relevant teaching assignment. Join/leave and recording access/progress routes are separate operations. | Unassigned teacher, unenrolled student, early/late join window, other tenant's recording, forged media URL, revoked recording, duplicate join/leave and provider failure. |
| `CertificateController` | Student-owned certificate listing/issue/download, guardian child certificates, public verification and admin actions use role-specific guards such as student, parent, guardian-child and admin checks. | Ineligible issue, wrong recipient, unrelated child, cross-account download, revoked file, public verification leaks private data, QR mismatch. |
| `HrmsAccessController` | `/api/v1/hrms/access-users/*`; permissions catalogue, user list, provision, roles and account status all visibly call `requireAccessManager`. | Non-manager access, education-admin role bypass, role escalation, workspace-switched request, revoke with existing session, unsupported permission assignment. |
| `HrEmployeeController` | `/api/v1/hr/*` employee/organization/department/designation routes use HR view/manage guards and employee access/reporting-scope checks on individual records. | Unauthorized organization/employee, cross-organization ID, manager reads outside reporting tree, inactive staff, update without grant. |
| `HrAttendanceLeaveController` | `/api/v1/hr/attendance*` and `/leave/*`; shared HR guard plus employee access checks are visible on mutations/employee-specific records. | Staff marks attendance outside allowed scope, self-approval, duplicate/reversed ledger transaction, correction race, leave overdraw, holiday/working-day mismatch. |
| `HrPayrollController` | `/api/v1/hr/payroll/*`; view/manage guard families for components/compensation/periods/calculation/submission; explicit permission calls for run approval and payment reconciliation. | View-only mutation, approver/pay separation, mixed currency/overlap, calculate twice, submit/pay twice, employee outside manager/organization scope, test payroll mixed with real payroll. |
| `HrTeachingAssignmentController` | `/api/v1/hr/academic-subjects`, academic periods, teaching assignments, decisions/history, workload policy, reports; view/manage/permission guards are present. | Ineligible teacher/subject, conflicting assignment, manager outside scope, unauthorized approval or policy update, unapproved assignment used to join class. |
| `HrReportingScopeService` | `HR_MANAGER_TEAM_VIEW` creates recursive reporting-tree access for scoped managers; employee access can return not-found outside scope. | Parent/child reporting graph isolation, manager tries peer/senior/out-of-tree record, inactive/separated employee path, collection and detail query scope consistency. |

## 4. Test-pack and QA architecture implications

1. **Use distinct workspace sessions.** One fixture identity may not safely reuse the same cookie or context across student, parent, Educational Admin, HRMS and institution-admin applications.
2. **Use genuine relationships.** Parent-child links, institution membership, teacher assignment, course entitlement and payroll reporting scope must be real relational fixtures, not text-only role labels.
3. **Exercise denial paths deliberately.** Most high-value isolation bugs are unauthorized reads/writes that still return 200, not obvious UI failures.
4. **Do not seed live commerce side effects.** Use Razorpay test mode or a controlled payment adapter. Verification must be server-authoritative and idempotent.
5. **Retain audit evidence.** Record actor, workspace, tenant, action, expected denial/allow result and resulting entity IDs for both successful and rejected tests.
6. **Cleanup must be run-scoped.** Build the dependency/ownership manifest from actual schema and asset storage before deciding uninstall SQL. Never rely on one global flag to locate every resource.

## 5. Phase 1 follow-up

- [x] Create a static source-derived route inventory (435 mapping rows; source method, HTTP/channel, path, line and guard hints). [ ] Reconcile against the application’s effective Spring mapping table and review exact per-route permission, workspace, tenant and resource-ownership rules.
- [ ] Trace helper-built routes from every screen into the endpoint map.
- [ ] Link each endpoint to database table/entities, unique constraints, audit rows and external side effects.
- [ ] Build paired allow/deny test cases for every sensitive permission and tenant predicate.
- [ ] Cross-check the 435 route entries and controller guards against existing API tests; record per-route allow/deny assertion coverage in the [consolidated coverage gap plan](./phase-1-coverage-gaps.md).
- [ ] Record tested commit SHA and CI results; source review is not runtime certification.
