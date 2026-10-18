# Phase 1 — Role, Workspace and Permission Matrix

**Status:** Source-derived authorization map, not runtime certification.  
**Sources inspected:** `AuthorizationService.java`, `AuthFilter.java`, `AdminStaffController.java`, `HrmsAccessController.java`, `PeopleWorkspaceLayout.tsx`, `admin-navigation.ts`, migrations `V80`, `V81`, `V83`, `V88`, `V89`, and `V120`.

## 1. Rules that govern this matrix

1. A role label does not by itself prove effective access. Permission checks may be workspace-scoped, read live grants from the database, require an active staff identity, or use additional roles.
2. The UI navigation condition is only the visibility rule. The API must independently authorize every request and resource ID.
3. The platform-owner grant `QE_PLATFORM_OWNER` is distinct from an Education Admin `ADMIN` role and a general HR staff role.
4. HRMS permissions (including `HR_*`, `HR_SELF_SERVICE_VIEW`, `TEACHING_ASSIGNMENT_VIEW`, `CLASS_SCHEDULE_POLICY_MANAGE`, and `QE_PLATFORM_OWNER`) are recognized as HRMS-only by `AuthorizationService`. Education Admin global-admin bypass does not satisfy them when the workspace is not HRMS.
5. An employee can have additional HRMS roles separately from their base `staff_account.role`. A person with legitimate identities/grants in more than one workspace must still use the correct workspace session.
6. A row below describes source-intended access and identifies tests needed. It is not a complete generated permission catalog, nor proof of success on the running application.

## 2. Authentication/workspace dimensions

| Context | Source guard evidence | Required deny/edge tests |
|---|---|---|
| Anonymous / expired session | `AuthorizationService.requireAuth` rejects missing or unauthenticated context; `AuthFilter` routes session to workspace-specific cookies/current session. | Protected page/API, expired access, refresh replay, logged-out cached page, suspended identity. |
| Student workspace | Student endpoints call `requireStudent`; tenant-aware education endpoints need selected institution and active membership/entitlement where applicable. | Parent cookie cannot become student; another student's attempt/profile/document is rejected; missing/invalid/inactive tenant fails closed. |
| Parent/guardian workspace | Parent endpoint families use `requireParent` and guardian-child/consent checks; parent access is not interchangeable with child login. | Guessed child/certificate/attempt ID, archived or unconsented child, cross-family and cross-tenant calls. |
| Educational Admin workspace | Admin controllers use `requireAdmin`, `requirePermission`, or controller-specific staff permission gates. AdminStaffController only edits Education Admin grants. | HRMS token cannot enter Education Admin routes; student/parent session cannot use content/admin endpoint; role permission removed during active session. |
| HRMS workspace | `AuthFilter` restricts HRMS endpoints to HRMS workspace; `AuthorizationService.hasPermission` reads scoped grants/role permissions; HRMS controllers check explicit HR permissions. | Education Admin cannot use HR payroll/access/attendance APIs; revoked grant blocks existing session; organization and manager reporting scope holds for collection and detail endpoints. |
| Institution-admin workspace | Separate `institution_admin_session` and institution-admin auth service; institution portal uses principal checks and explicit tenant binding. | Institution session cannot access student, Education Admin or HRMS APIs; institution A admin cannot read/update institution B; public branding lookup grants no membership. |

## 3. Education Admin roles and permissions

Source: `AdminStaffController.java` and `apps/admin/app/components/admin-navigation.ts`.

The supported base Education Admin roles in `AdminStaffController.ROLES` are `ADMIN`, `MANAGER`, `MODERATOR`, `CONTENT_AUTHOR`, `CONTENT_REVIEWER`, and `PUBLISHER`. The defaults below are taken from `defaultPermissions`; admins may receive an explicit permission selection subject to validation. HRMS permission keys are excluded from the Education Admin permission catalogue and cannot be added through this endpoint.

| Base role | Default grants in source | Navigation/operational expectation | Required tests |
|---|---|---|---|
| `ADMIN` (Education Admin) | Current Education Admin permission catalog excluding HRMS-only keys | Admin-wide education operations; some nav items (overview, certificates, marketplace, students, parents) are Admin-only. Must **not** become HRMS platform owner solely by this role. | HRMS/platform endpoints forbidden; demotion safeguards; cannot remove last active education admin; current session invalidation after status change. |
| `MANAGER` | `CONTENT_VIEW`, `CONTENT_CREATE`, `CONTENT_EDIT`, `CONTENT_SUBMIT`, `CONTENT_REVIEW`, `AUDIT_VIEW` | Content management/review and audit as explicitly permitted; does not imply platform-wide institution ownership or HR payroll privileges. | Cannot grant HRMS-only permission; cannot perform Admin-only nav actions unless another explicit permitted path exists; verify API independently. |
| `MODERATOR` | `CONTENT_VIEW`, `CONTENT_REVIEW` | Question/content review surfaces subject to their endpoint permissions. | Can review only permitted items; cannot edit/publish if missing grant; denied write must occur before DB mutation. |
| `CONTENT_AUTHOR` | `CONTENT_VIEW`, `CONTENT_CREATE`, `CONTENT_EDIT`, `CONTENT_SUBMIT` | Draft/edit/submit content; submission must not equal final approval/publish. | Cannot approve/publish without review/publish grants; cannot access hidden draft through learner routes. |
| `CONTENT_REVIEWER` | `CONTENT_VIEW`, `CONTENT_REVIEW` | Review queue/history and governed content workflow. | Cannot change editable content or publish unless separately permitted; review audit is attributable. |
| `PUBLISHER` | `CONTENT_VIEW`, `CONTENT_PUBLISH` | Publish only when required prior review/source/preview gates are satisfied. | Cannot bypass review gate or publish incomplete-source lesson; stale revision confirmation is rejected. |

### Education Admin permission invariants observed

- `CONTENT_VIEW` is mandatory if a permission set contains content tasks.
- `CONTENT_CREATE` requires `CONTENT_EDIT`.
- `STAFF_MANAGE` is restricted to the `ADMIN` role in the server validator.
- An Education Admin cannot be assigned `HR_*`, `HR_SELF_SERVICE_VIEW`, `TEACHING_ASSIGNMENT_VIEW` or `CLASS_SCHEDULE_POLICY_MANAGE` through `AdminStaffController`.
- `replacePermissions` deletes/replaces only `EDUCATION_ADMIN` grants, intentionally retaining HRMS grants for a dual-workspace staff identity.
- Self role/permission changes are rejected. Suspending Education Admin access revokes its Education Admin/staff sessions, and the API protects the last enabled configured admin.

## 4. HRMS base/additional role model

The HRMS access controller supports assigning additional roles `EMPLOYEE`, `HR`, `ACCOUNTANT`, `PEOPLE_MANAGER` to eligible employees. The HR identity is linked to `hr_employee`; permissions and sessions are HRMS-scoped. Role templates originate from migration history and were corrected/expanded in later migrations, so the current effective mapping reflects the later template changes rather than the first historical insertion alone.

| HRMS role/template | Effective template described by latest relevant migrations | UI/workflow scope | Required negative tests |
|---|---|---|---|
| `EMPLOYEE` | `HR_SELF_SERVICE_VIEW` | Own profile, own attendance/leave/balances/payslips/profile change requests and corrections through self-service contract. | Cannot change employee ID to read another employee; cannot manage attendance/leave/payroll/access unless another explicit permitted role grants it. |
| `HR` | HR employee view/manage; attendance view/manage/correction-review; leave view/manage; employee-change review; leave balance view/manage; holiday view/manage; approval policy manage; recruitment/performance/assets/exit view/manage; academic assignment view/manage/approve; payroll view; class/teaching schedule policies, per V88. V83 removes HR template access-manage and payroll manage/approve/pay; V89 removes the manager-team-view template from base HR. | Broad HR operations within assigned permissions and applicable organization/employee reporting scope. | Cannot use generic HR role to provision access, approve/pay payroll, or automatically gain manager-team scope solely via old template; must obey current effective scoped grants. |
| `ACCOUNTANT` | Employee view; payroll view/manage/approve/pay. | Finance preparation and payroll approvals/payments according to workflow and explicit permission; does not imply HR access governance. | Cannot onboard/reassign staff roles without `HR_ACCESS_MANAGE`; test approval separation, payment idempotency and wrong-organization access. |
| `PEOPLE_MANAGER` | Employee view, attendance view, leave view, employee-change review, attendance-correction review, manager-team view. | Team-scoped visibility and review where permitted. | Cannot see peers/seniors/out-of-tree employees; cannot inherit payroll payments or global access management. |
| HRMS system bootstrap administrator | The special `hrms_admin` identity is created/maintained as a separate staff identity with base role `HR`, a forced password-change condition, HRMS-only direct grants including `HR_ACCESS_MANAGE`, and in V88 the teaching/view policy grants; protected from generic Education Admin staff edit and HRMS self-reprovision/status changes. V89 removes its manager-team scope grant. | Initial platform HRMS operations, after password change. This is not the same as Education Admin `ADMIN` or `QE_PLATFORM_OWNER`; institution-wide actions that use `QE_PLATFORM_OWNER` require that explicit grant. | Cannot access through Education Admin cookie; no staff-role editor can repurpose the bootstrap identity; forced password change; no manager-team scope unless explicitly granted by current rules. |

### HRMS workspace/role safeguards in controller source

- `HrmsAccessController` requires `HR_ACCESS_MANAGE` before listing permission keys, listing access users, provisioning/resetting login access, changing roles or toggling HRMS access.
- HRMS access provisioning is only allowed for employment statuses `ACTIVE`, `ON_LEAVE`, `NOTICE_PERIOD`; it refuses bootstrap identity changes and refuses self-credential/role/access changes.
- Provision/reset requires an initial password 12–128 characters and sets `must_change_password=true`; username conflicts become a conflict response.
- Provisioning, role changes and disable paths revoke active HRMS sessions; disabling access or assigning roles is auditable.
- `HrReportingScopeService` applies recursive team scope when `HR_MANAGER_TEAM_VIEW` is effective; detail and collection queries both must apply the scope consistently.
- Payroll run operations have distinct manage/approve/pay grants; an existing test forbids a payroll submitter from approving their own submitted run.
- An employee designation (teacher/professor/clerk) must not itself grant CMS/Admin. The actual staff identity + current workspace grants control access.

## 5. Platform owner vs institution admin vs Education Admin

| Capability | Expected authority from source design | How to test |
|---|---|---|
| Create/edit/suspend institution, tenant code and institution billing | `QE_PLATFORM_OWNER` for `/api/v1/hrms/platform/institutions/*` | Test with platform owner, HR bootstrap admin lacking owner grant, Education Admin, institution admin and normal learner. Assert deny occurs before mutation. |
| Provision institution admin and manage institution's course release/offer/branding assets | Platform-owner endpoints; institution admin has separate own-tenant session for institution portal and permitted institution course-lifecycle endpoints. | Platform-owner can centrally govern; institution admin can't switch tenant by header/ID; public branding isn't membership. |
| Education content author/review/publish/staff administration | Education Admin base role plus Education Admin workspace permission grants. | HRMS role alone cannot use content APIs; publisher/author/reviewer boundaries per table above. |
| HR employee access provisioning | `HR_ACCESS_MANAGE` within HRMS workspace, not Education Admin `STAFF_MANAGE`. | Education Admin STAFF_MANAGE and HRMS HR_ACCESS_MANAGE must be distinct; a cross-workspace request should fail. |
| Payroll approve/pay | `HR_PAYROLL_APPROVE` / `HR_PAYROLL_PAY` effective in HRMS workspace. | `HR` role alone must not satisfy these by inherited legacy template; ACCOUNTANT or explicit grant can where assigned; preparer self-approval rules remain enforced. |
| Teacher live-class visibility and meeting link | `TEACHING_ASSIGNMENT_VIEW` plus valid approved assignment; meeting link additionally follows class-manager/private-link and join-window rules. | Teacher can see only assigned sessions; no unverified private link; no class schedule authority from designation alone. |

## 6. Scenario IDs to assert for role and workspace boundaries

Use these existing cases in `phase-1-workflow-scenario-register.csv`:
- `AUTH-006`, `AUTH-007`: workspace-bound sessions, revocation and stale-session rejection.
- `FAM-003`, `FAM-005`: cross-guardian target ID isolation and report scope.
- `TEN-001`–`TEN-007`: platform owner, tenant membership, branding, course release/offer and membership lifecycle.
- `CAT-002`, `CAT-005`–`CAT-007`: hidden content/entitlement, parent purchase and course transfer.
- `CNT-001`, `CNT-004`: content approval/publish/question-review boundaries.
- `LIVE-001`, `LIVE-002`, `LIVE-004`: class manager/teacher/learner permissions and WebSocket scope.
- `HR-001`–`HR-011`: HR access, hierarchy, assignments, attendance, leave, payroll and payment boundaries.
- `QA-007`: block QA installer/uninstaller when environment safety conditions are not met.

## 7. Required role-matrix tests not considered complete yet

- [ ] Extract every effective `staff_permission_catalog` key and workspace assignment into a generated permission catalog at the current migration state.
- [ ] Verify all initial, additional-role and direct-grant mappings against the migrated PostgreSQL schema, including migrations that remove earlier grants (`V83`, `V89`).
- [ ] Build allow/deny API tests for every route family with at least one allowed role and one visually similar but unauthorized role.
- [ ] Confirm navigation visibility matches API affordances but does not stand in for authorization.
- [ ] Test role/grant change in one workspace while the same staff identity holds permissions in another workspace; verify the intended grants persist and session revocation affects the correct workspace only.
- [ ] Check institution-admin routes for own-tenant enforcement and platform owner for global scope without treating generic HR administrator as platform owner.
- [ ] Execute through Web, Android, Admin and HRMS UIs with real seeded personas; attach screenshots/network evidence and current SHA.
- [ ] Keep the matrix under revision control with the source migrations and update it whenever permission templates or routes change.

---

**Audit integrity:** This is a source-derived role/permission matrix. It does not claim that the current deployed database has all migrations applied correctly or that every role was tested in the running UI/API.
