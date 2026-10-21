# Session management audit — QuantaEdge

**Scope:** learner/parent accounts, Education Admin staff, and HRMS employee accounts.
**Reviewed against:** the main-branch authentication controllers, auth filter/service, session boundaries, and login/landing flows.

## Identity and workspace isolation

The API issues `HttpOnly`, `SameSite=Strict` access and refresh cookies scoped by workspace:

- Parent: `QE_PARENT_SESSION` / `QE_PARENT_REFRESH`
- Student: `QE_STUDENT_SESSION` / `QE_STUDENT_REFRESH`
- Education Admin: `QE_STAFF_SESSION` / `QE_STAFF_REFRESH`, with the persisted session workspace constrained to Education Admin/legacy staff
- HRMS: `QE_HRMS_SESSION` / `QE_HRMS_REFRESH`

The server validates the workspace header and session identity; a parent identity cannot become a student identity, education staff cannot use HRMS endpoints, and HRMS sessions cannot access learner or Education Admin APIs. Protected pages remain hidden until the appropriate session identity is validated.

## Confirmed session policy in the API

- Access tokens are short-lived (15 minutes) and refreshed through the workspace-matched refresh cookie.
- Refresh cookies have a sliding four-hour lifetime, bounded by absolute expiry.
- Learner sessions have a 30-day absolute limit and a four-hour idle limit.
- Education staff and HRMS sessions have an eight-hour absolute limit and a 30-minute idle limit.
- HRMS intentionally permits only one active HRMS session per staff identity.
- Production requires secure cookies and prohibits demo seeding/repeated demo OTP.

## Defects found and remediations

1. **Unbounded auth/refresh fetches:** the frontend refresh interceptors could await `/auth/me` or `/auth/refresh` without a deadline. Since the learner, Education Admin, and HRMS layouts deliberately withhold protected children during session validation, a stalled request could leave a permanent “checking workspace/session” screen even though other API calls had returned data. Auth requests/retries are now bounded, and the session-check sequence has a total deadline.
2. **Router-only session handoff:** successful checks depended entirely on the client router completing a redirect. A bounded full-navigation fallback now runs only if the browser is still on the source route; it does not override a navigation that has already proceeded elsewhere.
3. **Education Admin permissionless staff loop:** a valid staff identity with neither the recognised content nor audit landing permission could resolve to `/login`, then the boundary would redirect `/login` to itself without marking the page ready. The landing resolver now treats “no eligible staff destination” as no workspace destination, displays the login surface when already there, and routes protected paths back to sign-in.
4. **Standalone session landing pages:** `/session` routes bypass their app's normal boundary, so their own `/auth/me` requests also had no deadline. Those probes now time out into the existing retry/sign-out recovery UI.

## Security behaviour retained

Timeouts do not grant access. A timeout on a protected route redirects to that portal's sign-in surface; a public landing/login route remains usable. Workspace identity, cookie scope, server-side expiry, role/permission checks, and logout isolation remain enforced by the API.

## Validation

The expected validation path is the repository's main CI workflow: API Maven verification, web/admin/People image builds, Android build where affected, and the content safeguards suite. A real browser smoke test should confirm login and expired-session refresh independently for Parent, Student, Education Admin, and HRMS, including account switching and a revoked session.