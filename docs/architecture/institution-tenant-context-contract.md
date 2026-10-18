# Institution tenant context contract

Status: **Phase 1 implementation in progress**  
Implementation tracker: [#71 — Institution-first multi-tenant implementation tracklist](https://github.com/Manav326/QuantaEdge/issues/71)

## Request contract

Tenant-scoped learner API requests may carry:

```http
X-QE-Workspace: STUDENT
X-QE-Institution-Code: QUANTA-EDGE
```

The institution code is a selector only. It does not authenticate the caller, create membership, prove parent/child association, assign a course, publish an offer, grant an entitlement, or authorize a read/write/payment. The backend resolves the supplied code against an active `institution_tenant` row and exposes the resulting `InstitutionTenantContext` as the request attribute `institutionTenantContext`. A malformed selector returns `400 INVALID_INSTITUTION_CODE`; unknown or inactive institutions return `403 INSTITUTION_UNAVAILABLE`. No tenant is guessed when the header is absent.

## Context ownership

- **Learner web:** `SessionBoundary` adds the selector centrally from the `/school/{code}` route, an institution query on the current URL, route-scoped tenant metadata cookies, or the established tenant-brand session value. This data is untrusted metadata; the API is the authorization authority.
- **Android:** `ApiClient` adds the selector centrally. A separately branded build is pinned to `BuildConfig.INSTITUTION_CODE`; a general build may use the last independently resolved tenant identity from the existing institution-theme preference. Intent/deep-link input alone is not authority.
- **Institution admin:** `institution_admin_session` remains authoritative for institution scope. A client header must never override the admin principal's institution ID.
- **HRMS/platform owner:** platform-owner permission plus explicit target institution/course IDs remain the cross-tenant control plane. The learner tenant selector is not used to broaden HRMS access.
- **Public branding and join/auth routes:** the public branding URL and validated join/auth workflow keep their existing explicit behavior; a tenant code alone never establishes membership.

## Required enforcement before rollout

The context record is deliberately separate from access policy. Each protected course path must require the appropriate context and then enforce, in order, the authenticated student/parent relationship, active institution membership, active institution-course assignment, destination offer approval/publication, and enrolment/purchase/grant policy. Missing context must fail closed once the route has been moved to the tenant-required inventory. There must be no no-membership or global-offer fallback.

See issue #71 for the end-to-end sequence, legacy QUANTA-EDGE reconciliation, institution-specific course release/offer workflow, endpoint audit, and multi-institution acceptance tests.
