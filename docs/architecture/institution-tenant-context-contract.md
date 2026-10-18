# Institution tenant context contract

Status: **Phase 1 implementation in progress**  
Implementation tracker: [#71 — Institution-first multi-tenant implementation tracklist](https://github.com/Manav326/QuantaEdge/issues/71)

## Request contract

Tenant-scoped learner API requests may carry:

```http
X-QE-Workspace: STUDENT
X-QE-Institution-Code: QUANTA-EDGE
```

The institution code is a selector only. It does not authenticate the caller, create membership, prove parent/child association, assign a course, publish an offer, grant an entitlement, or authorize a read/write/payment. The backend resolves the selected code against an active `institution_tenant` row and exposes the resulting `InstitutionTenantContext` as the request attribute `institutionTenantContext`. A malformed selector returns `400 INVALID_INSTITUTION_CODE`; unknown or inactive institutions return `403 INSTITUTION_UNAVAILABLE`.

## Deployment context policy (generic; no tenant is special-cased)

The API accepts two environment settings:

- `APP_INSTITUTION_CONTEXT_MODE=ROUTED` (default): explicit context from a school URL, validated invite flow or configured Android build wins. `APP_DEFAULT_INSTITUTION_CODE`, when set, is a compatibility fallback for entry points which have no route/build selector. Leave it blank for a fully routed shared deployment.
- `APP_INSTITUTION_CONTEXT_MODE=FIXED`: requires `APP_DEFAULT_INSTITUTION_CODE` and pins that API deployment to that tenant. A conflicting request header is rejected with `403 INSTITUTION_CONTEXT_MISMATCH`. Use only when the deployment is intentionally dedicated to one institution.

For example, a single-tenant deployment can configure `APP_DEFAULT_INSTITUTION_CODE=SCHOOL-A` and `APP_INSTITUTION_CONTEXT_MODE=FIXED`; a shared web/API deployment should use `ROUTED` and normally leave the default blank. The value `SCHOOL-A` is illustrative—not a built-in institution name or code. Set the corresponding variables in the actual deployment environment and redeploy; these are not secrets, so they do not need to be embedded in source. Local and production Compose files pass these variables through to the API.

A deployment default selects the tenant scope for legacy entry points; it still must resolve to an active institution and pass all membership, course assignment, offer publication and entitlement checks. It never creates membership. New users are not assigned to the default institution merely because it is configured. Existing legacy membership association remains governed by the one-time migration, not request handling.

## Context ownership

- **Learner web:** `SessionBoundary` adds the selector centrally from the `/school/{code}` route, an institution query on the current URL, route-scoped tenant metadata cookies, or the established tenant-brand session value. This data is untrusted metadata; the API is the authorization authority.
- **Android:** `ApiClient` adds the selector centrally. A separately branded build is pinned to `BuildConfig.INSTITUTION_CODE`; a general build may use the last independently resolved tenant identity from the existing institution-theme preference. Intent/deep-link input alone is not authority. Build-specific tenant identity is configured through `QUANTAEDGE_INSTITUTION_CODE`; no institution code is hard-coded for all builds.
- **Institution admin:** `institution_admin_session` remains authoritative for institution scope. A client header must never override the admin principal's institution ID.
- **HRMS/platform owner:** platform-owner permission plus explicit target institution/course IDs remain the cross-tenant control plane. The learner tenant selector is not used to broaden HRMS access.
- **Public branding and join/auth routes:** the public branding URL and validated join/auth workflow keep their existing explicit behavior; a tenant code alone never establishes membership.

## Required enforcement before rollout

The context record is deliberately separate from access policy. Each protected course path must require the appropriate context and then enforce, in order, the authenticated student/parent relationship, active institution membership, active institution-course assignment, destination offer approval/publication, and enrolment/purchase/grant policy. Missing context must fail closed once the route has been moved to the tenant-required inventory. There must be no no-membership or global-offer fallback.

See issue #71 for the end-to-end sequence, legacy QUANTA-EDGE reconciliation, institution-specific course release/offer workflow, endpoint audit, and multi-institution acceptance tests.
