# Smooth Loading and Performance Implementation Plan

**Branch:** `feat/smooth-loading-architecture`  
**Base:** `main`  
**Status:** In progress  
**Scope:** Learner/parent Web, Institution Admin, HRMS/People Operations, native Android, and API behavior.

## Goal

Make QuantaEdge feel responsive on initial load, repeat navigation, slow networks, and low-end devices without introducing unnecessary requests, changing authorization boundaries, or breaking current page flows.

The guiding rule is: **render the app shell first; load essential content next; load secondary and expensive work only when it is needed.** A skeleton improves perceived speed but is not a substitute for reducing real latency.

## Non-negotiable implementation rules

- Keep the existing shared app shell, branding, theme, language selection, permissions, routes, and API contracts intact.
- Render a page-shaped skeleton for route/content loading; do not replace the whole shell with a spinner.
- Show skeletons only during initial loading. Preserve existing content during background refreshes.
- Distinguish loading, loaded-empty, error, offline, and stale-but-usable states.
- Fetch only data required by the current route and visible section.
- Deduplicate identical in-flight requests and reuse valid cached responses where safe.
- Cancel obsolete search/navigation requests when supported; prevent stale responses from overwriting newer state.
- Make retries bounded and safe. Do not automatically replay payment creation, enrollment, or other non-idempotent mutations.
- Keep user/session/permission and payment-status freshness/security rules authoritative; never use a client cache as an authorization boundary.
- Respect reduced-motion preferences, accessible status announcements, responsive layouts, light/dark themes, and supported locales.
- Measure before and after. Do not claim an optimization is complete without build/test or performance evidence.

## Phased delivery

### Phase 0 — Baseline and architecture audit

- [x] Confirm application surfaces: Next.js App Router in `apps/web`, `apps/admin`, and `apps/people`; native Jetpack Compose Android app in `apps/android`.
- [ ] Inventory all route/page loading states, shared shells, and high-value user journeys.
- [ ] Trace API calls per route: duplicates, request waterfalls, oversized responses, unbounded polling/retries, and calls triggered by rerenders.
- [ ] Record baseline cold/warm navigation, JavaScript bundle sizes, Core Web Vitals where available, Android cold start/first frame, and slow-endpoint timings.
- [x] Review the three web API wrappers and app manifests before selecting a dependency: they currently use a centralized `apiFetch` wrapper around `fetch`, with no installed shared query/cache library. Route-specific API and cache policy audit remains open.

### Phase 1 — No-blank loading foundation

- [x] Add App Router route-level loading boundaries to Web, Admin, and HRMS/People.
- [x] Use layout-shaped skeletons, theme-aware colors, responsive layout, and reduced-motion support.
- [x] Add a native Compose skeleton primitive and replace high-visibility course/class initial-load spinners.
- [ ] Audit route-by-route for page-specific skeletons where the generic fallback does not match the actual content.
- [ ] Confirm error and true-empty states never remain stuck in skeleton mode.

### Phase 2 — Route and component loading

- [ ] Split large route/feature bundles only where analysis shows meaningful savings.
- [ ] Defer charts, report builders, rich editors, PDF/rendering engines, and lesson media until opened.
- [ ] Lazy-load below-the-fold media; reserve image and chart dimensions to prevent layout shifts.
- [ ] Preserve the header/sidebar/bottom navigation when route content suspends or changes.

### Phase 3 — API efficiency and caching

- [ ] Produce a route-to-data matrix for learner, parent, institution admin, and HRMS screens.
- [x] Coalesce identical in-flight same-origin API GETs across all three web clients when browser caching is default and no explicit abort/forced refresh is requested. Return a separate Response clone per caller; inspect/notify once per shared request. API mutations clear the in-flight registry to isolate reads across session/workspace changes. This is in-flight deduplication, not a resolved-response cache.
- [ ] Audit and standardize resolved-data cache keys, freshness policies, cancellation, bounded retries, and targeted invalidation using the existing stack where possible.
- [ ] Avoid waterfalls for independent requests; retain explicit sequencing for real dependencies.
- [ ] Use paginated endpoints for large rosters, course catalogues, transaction histories, and question banks.
- [ ] Debounce search/filter inputs and ensure only the latest query can update the screen.
- [ ] Keep payment/order status refresh explicit, scoped, and safe; do not use broad polling or stale cached payment state as proof of payment.
- [ ] Check backend query time and payload size before adding more frontend concurrency.

### Phase 4 — Android runtime efficiency

- [ ] Audit each destination for duplicated API calls and unnecessary recomposition.
- [ ] Share eligible data through repositories/state holders; keep user/session/institution scopes in cache keys.
- [ ] Use stable keys and lazy containers for large lists; render only what is needed.
- [ ] Review startup initialization, image/resource loading, offline cache policies, and background work.
- [ ] Measure release-build cold start and slow-frame behavior before/after changes.

### Phase 5 — Verification and regression protection

- [ ] Build all affected Next.js apps and run repository CI checks.
- [ ] Run Android JVM tests and debug/release build checks available in CI.
- [ ] Test slow/offline network, cold start, repeat navigation, empty data, failed APIs, retry, language/theme changes, logout/account switch, and permission changes.
- [ ] Compare API request counts and page timings against Phase 0 baselines.
- [ ] Add regression checks for loading state accessibility and duplicate request behavior where practical.
- [ ] Update this tracker with each completed batch and the actual commit/CI evidence.

## First implementation batch

**Implemented on this branch:**
- App Router route loading boundaries for the three web portals.
- An accessible, responsive skeleton fallback that leaves each portal's existing root layout in place.
- A reusable theme-aware Compose skeleton and replacement of the initial loading indicators on the learner's All Courses and Live Classes/Recordings screens.
- Exact-match, in-flight same-origin GET deduplication in the three web API wrappers; forced refreshes, no-store requests, AbortSignal requests, and non-GET API requests bypass sharing, with API mutations clearing the registry.
- This implementation plan and follow-up checklist.

**Not yet claimed complete:** full route-level audit, component code splitting, resolved-data caching, API/backend optimization, Android startup optimization, performance baselines, and end-to-end regression testing.

**Verification status:** the initial PR-triggered workflow run was cancelled while branch commits were being updated. The current head has no successful completed CI run available yet, and this environment cannot clone/build the repository locally. Build and runtime evidence remain a merge blocker.

## Acceptance criteria

This effort is complete only when each major route has an appropriate loading/empty/error state; initial loading does not hide the shared shell; duplicate/waterfall API behavior is materially reduced where verified; expensive modules are deferred based on bundle evidence; and before/after checks show no regressions to auth, permissions, theme, localization, learning state, checkout, or the HRMS flow.
