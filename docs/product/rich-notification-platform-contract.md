# QuantaEdge Rich Notification Platform — Product and Implementation Contract

**Status:** Product contract approved for implementation; implementation starts with durable in-app delivery and reusable rich-content templates.
**Repository:** `Manav326/QuantaEdge`
**Ownership model:** HRMS is the control plane; each institution (including QuantaEdge) owns only its permitted audiences. Education Admin and institution-admin workspaces remain distinct principals.
**Decision date:** 2026-10-20

## 1. Product objective

Provide one durable notification platform across the learner/parent web app, native Android app, Education Admin, the separate Institution Admin portal, and HRMS. Authorized senders choose a scope and audience, create a modern visual notification using a reusable template/content builder, preview the exact result on supported surfaces, then send or schedule it. The backend resolves and validates the audience, records durable recipient rows, dispatches delivery, exposes status, retries transient failures, and preserves an audit trail.

This is **not** the existing transient UI-toast feature and is **not** a replacement for course chat. Chat remains conversation-oriented. Notifications are authored, targeted, durable, independently read, and may deep-link into a course or workflow.

## 2. Notification scope and authority

Supported scopes:
1. **USER** — explicit individual target(s), validated by server identity and tenant eligibility.
2. **COURSE** — current eligible learners enrolled in an institution's assigned course offering; optionally notify eligible linked guardians when the sender explicitly selects that audience. Resolve recipients at send time, not from a browser-provided count.
3. **INSTITUTION** — one active institution and a permitted audience (currently: active learners, consented linked guardians, and institution-admin accounts, individually or in combination). Institution employee/staff recipients are deliberately excluded from this first slice: `hr_employee` and `staff_account` are separate from tenant membership and there is no authoritative institution-to-HR-organization mapping to safely resolve those recipients yet.
4. **HRMS_PLATFORM** — explicit target institutions and supported end-user groups (currently: learners, consented linked guardians, and institution-admin accounts), available only to HRMS users with `QE_PLATFORM_OWNER`; global targets require a confirmation and an immutable audience-resolution snapshot/count.

Hard rules:
- Every user/course relationship and learner recipient must be tenant-scoped. No global or orphan users/courses/notifications.
- A client-provided tenant header or ID is a selector, never proof of membership or authority.
- Institution Admin authority is resolved only from `institution_admin_session`; a user-controlled tenant ID cannot override it.
- Education Admin uses current Education Admin permissions and cannot inherit HRMS control-plane privileges.
- HRMS cross-tenant sends require explicit `QE_PLATFORM_OWNER`. Ordinary HR personnel must not gain broadcast ability from their job title or generic HR permissions.
- At dispatch time, recheck recipient active status, institution membership, course assignment/enrollment and campaign state. Exclude inactive, revoked, expired, or out-of-scope accounts.
- Parents receive messages about a child only through a current eligible guardian-child relation and must not gain access to unrelated children.
- Record sender, workspace, institution/course scope, audience filter, resolved recipient count, timestamp and send outcome. Campaign/template mutations are auditable.
- Support idempotency so request retries do not create duplicate campaigns or recipient deliveries.

## 2B. Receiving inboxes by workspace

The durable recipient row is the inbox source of truth. Student and parent Web inboxes and Android remain on their existing recipient APIs. Institution Admin uses the already authenticated institution-admin inbox API in a separate Inbox tab within the tenant-branded institution portal; Notification Studio remains a separate sender tab.

Education Admin uses a distinct staff inbox route that binds every query and state transition to the authenticated staff account plus the authenticated QuantaEdge institution. It does not display institution-less platform notifications. HRMS uses a separate authenticated staff inbox route that binds to the signed-in staff account and only HRMS-originated campaigns. Read, unread, archive, pagination and unread badge queries are all server-scoped to the same identity and workspace. The header bell is a convenience and is not itself authorization. Staff targeting remains fail-closed until canonical staff-to-institution/course mappings exist; adding inbox screens does not enable broad or inferred staff delivery.

## 2A. Recipient review and individual exclusions

Before send or schedule, the preview step returns the eligible recipient directory resolved by the server, grouped in the sender UI by institution and recipient role. Each row includes recipient display name, account identity, class where applicable, and institution context. Institution options show status plus active learner, eligible consented guardian, and active institution-admin counts.

The sender may exclude one eligible recipient or one selected institution. Exclusions only subtract from the server-resolved audience; the client cannot add recipient identities through this mechanism. A preview must be run again after an exclusion change, and sending remains disabled when zero recipients are selected. The backend resolves eligibility and applies exclusions again at immediate send and scheduled dispatch.

Suspended/inactive institutions remain visible in the selection directory with a reason but are disabled and cannot be targeted. For HRMS platform sends, an institution with zero active learners is also disabled and excluded—even if active institution-admin accounts exist. "All active institutions" resolves only to active institutions with at least one active learner; manually supplied zero-learner institution IDs are rejected by the backend. HRMS can exclude a specific eligible institution even when "all active institutions" is selected. Within eligible institutions, zero eligible guardian groups are explicitly called out, and all recipient rows still resolve through active membership and consent rules. Recipient details contain names, account IDs, class where relevant, and institution context—not contact details.

## 3. Rich notification content builder

Use a versioned, provider-neutral JSON document with a strict schema and a reusable server-side validator/renderer. Do not store arbitrary executable HTML, script, iframe code, or unsanitized SVG. A template is layout/content configuration, not executable code.

Initial content blocks:
- **Hero / cover:** headline, optional eyebrow, subtitle, safe image or approved decorative graphic, background/surface treatment, alignment and crop.
- **Rich text:** paragraphs, bold/emphasis, lists, inline links and language variants; sanitize/escape at render time.
- **Image / illustration / graphic:** managed media asset, caption, alternative text, focal point and aspect ratio. Raster images are first supported; generated/exported SVG must be sanitized or rasterized before delivery.
- **Video / animation:** approved video or animated GIF/WebP asset for the full in-app view where allowed; show a static poster and accessible action if autoplay is blocked or motion is reduced. Do not assume Android system push can render the full media block.
- **Course / lesson card:** canonical course identity, visible course label and server-owned deep-link destination.
- **Event / live-class card:** title, approved schedule/time zone, location/join action resolved by server; never expose private meeting URLs in a public preview or notification payload.
- **Highlight / statistic card:** approved label/value/supporting text. Dynamic values must be supplied by a typed, permission-checked server data binding, not arbitrary query text.
- **Badge / status, icon, divider, spacer and section/container:** for modern visual hierarchy and consistent templates.
- **CTA button / link:** label, action kind and a validated internal route/reference; arbitrary external links are HTTPS-only and policy-checked.
- **Footer:** sender identity, institution branding, optional expiry/expiry explanation and relevant service/privacy text.

Initial design presets: Announcement, Course Update, New Resource, Live Class Reminder, Schedule Change, Purchase/Access Status, Achievement/Celebration, Important Service Notice, and Custom. Presets are starting layouts; users can edit, duplicate and save drafts/templates.

Builder requirements:
- Drag/reorder blocks, add/remove blocks, layout variants, mobile/desktop preview, in-app/Android-push preview, preview in English and Hinglish, image/media upload, alt text, crop/focal point, CTA destination picker, draft save, duplicate template and versioning.
- Show validation errors inline; prevent send until title, body/content, audience, required alt text/targets and media references pass validation.
- Use preview data only for presentation; sending must send template/document IDs, versions, normalized audience selection and schedule/idempotency metadata. The server revalidates all references and permissions. Do not send the screenshot or client-rendered HTML as the notification itself.
- Use institution brand tokens for institution/course notices and approved platform styling for HRMS platform notices. Do not hard-code QuantaEdge branding into white-label tenant experiences.
- Responsive, calm surfaces, readable contrast, keyboard/screen-reader support and reduced-motion handling are required.

## 3A. Logo & Graphics Lab (part of the same Canva-like Studio)

The sender Studio also includes a **Logo & Graphics** workspace. This is not a separate top-level application. It can create original logo/brand-mark compositions and notification graphics, then pass the resulting asset directly into the current notification design.

Initial capabilities:
- Editable brand name and optional tagline; symbol/mark selection (book, star/spark, shield, leaf, circle/abstract geometric), logo type (wordmark, monogram, emblem, icon), horizontal/stacked/icon-only layouts and spacing.
- Brand color palettes plus user-selected primary/secondary/background colors; light/dark surface preview; transparent and solid-background export.
- Generate multiple controlled concept variations, revise text/symbol/palette/layout, inspect at app-icon, small header and notification-hero sizes, then export high-resolution PNG.
- Upload the exported raster asset into the same scoped media library and choose **Use in notification** to insert it into the selected hero/image block without downloading and re-uploading.
- Institution admins can create designs only for their own tenant; HRMS platform owners can create platform-owned assets. Do not silently replace an institution's canonical HRMS-managed brand/logo. Explicitly distinguish a campaign graphic from an institution's persistent logo and record asset ownership/provenance.
- Initial implementation must use deterministic, editable vector primitives rendered locally to raster; it must not pretend to be AI-generated. The interface can expose prompt-to-image generation only when a server-configured provider capable of image generation is verified and enabled. AI generation is an optional provider adapter with rate limits, cost/consent controls, asset validation and review; never expose provider credentials to browsers.
- Do not upload SVG/scriptable documents. Export a validated PNG through the existing private media endpoint. Keep generated assets tenant-scoped and keep the original editable composition as validated JSON if that persistence is implemented.

## 4. Surface and channel contract

### In-app inbox — first-class source of truth
- Persistent bell with unread count and a dedicated notification center.
- All / Unread / category filters; mark read/unread, mark all read, archive/dismiss according to policy, clear timestamps and sender/scope identity.
- Render the full rich-content document with safe fallbacks for unknown future blocks.
- The same notification and read state must be available to a user on supported web and Android clients.
- Deep-link to an authorized canonical destination. On navigation, preserve current student/parent role and selected institution/course state.
- Inbox/history survives page navigation, sign-out/sign-in and app restart; eligibility and privacy are rechecked when opening a destination.

### Live delivery
- Use database-backed durable rows first; push/realtime are delivery optimizations.
- Use existing authenticated WebSocket patterns only with a scoped notification destination or use a foreground refresh strategy if an authenticated subscription cannot be proved safe. On reconnect, fetch the durable inbox to catch up. Avoid adding a new message broker until deployment scale requires it.

### Android push
- Register device token, platform/app version, last seen, active/revoked state; never treat the token as identity.
- Send a compact title/body, optional supported big-picture image, and opaque notification ID/deep-link reference. The app then fetches the full rich document under normal auth.
- Respect Android notification permission, channels, account switching, logout/revocation, invalid-token cleanup, retry/backoff and duplicate suppression.
- Do not claim that full video/carousel/interactive block layouts render in system push; they render after opening the in-app view.
- Credentials stay server-side; provider adapter is configured centrally through HRMS and must be sandbox/no-op capable in tests.

### Later optional channels
Email, web push and SMS are extension points, not prerequisites for reliable in-app delivery. Do not silently send them before provider credentials, consent/category preferences, user expectations and operating cost are explicitly established.

## 5. Template and campaign lifecycle

Template: `DRAFT -> VALIDATED -> ACTIVE` with version history and soft archive. Editing an already-used version creates a new version; past campaigns keep their frozen content snapshot.

Campaign: `DRAFT -> PREVIEWED -> SCHEDULED or QUEUED -> PROCESSING -> SENT / PARTIALLY_FAILED / FAILED`, with `CANCELLED` before dispatch and safe `EXPIRED` behavior. Each transition is server-owned and audited. A send-now action records immutable template/document version, audience selection, audience-resolution snapshot/count, target scopes, sender, schedule and idempotency key.

Each recipient: `QUEUED -> IN_APP_CREATED -> READ` for in-app state, plus separate channel delivery states such as `PENDING / SENT / RETRYING / FAILED / NOT_CONFIGURED`. A push failure does not roll back the in-app notification. Read/open is not equivalent to provider delivery acknowledgement.

## 6. Media and asset security

- Media is uploaded through authenticated, permission-checked endpoints; use allow-listed raster image types initially (JPEG/PNG/WebP; animated media only if safely validated) and validated video types with configurable byte/duration limits.
- Verify actual file signatures, dimensions, checksum, declared MIME type and upload bounds; reject SVG/scriptable document payloads unless safely sanitized/rasterized. Generate immutable asset IDs and store private originals using the existing private media storage conventions where appropriate; PostgreSQL stores metadata, not large video bytes.
- Private assets are tenant/scope-bound and served through authorization-checked endpoints or short-lived references. Never put secret/private meeting links in public catalogues, system push payloads or unscoped permanent URLs.
- Image alt text is required for meaningful images; decorative graphics can be explicitly marked decorative.
- Scan/validate uploads as supported by current infrastructure. Make upload processing status visible and fail closed if a referenced asset fails validation.
- Use published media retention/removal workflow; preserve audit/provenance records.

## 7. Required UI workspaces

- **Education Admin web:** Notifications workspace for authorized course/institution sends, template library, visual builder, preview, recipient-resolution preview, send/schedule, campaign history and retry.
- **HRMS / People Operations:** platform template library and creator, explicit institution/audience selector, multi-institution preview summary, platform-only send confirmation, cross-tenant audit and delivery monitoring.
- **Institution Admin portal:** same builder and template capabilities for the authenticated institution only; institution is inferred from `institution_admin_session`, never selected by the browser to broaden scope.
- **Learner/parent web:** global bell, inbox and detail rendering.
- **Android:** bell/inbox/detail/full-content rendering and push open handling; consistent API and read state with web.

Use shared API contracts and common rich-document schema/validation. Avoid triplicate divergent builders when a shared package fits the current Next.js workspace architecture.

## 8. Proposed persistence/API architecture

Use additive Flyway migrations only; do not edit already released migrations. At minimum:
- versioned `notification_template` metadata and safe content JSON;
- `notification_campaign` with sender/workspace/scope, audience JSON, immutable content/template snapshot, lifecycle, idempotency and times;
- `notification_recipient` with nullable FK columns for exactly one existing identity kind (student, user-account or staff-account), institution context, read/dismiss timestamps and per-recipient idempotency;
- `notification_delivery_attempt` with channel, state, attempts, provider reference and sanitized failure;
- `notification_media_asset` with owner/scope, MIME, checksum, length, dimensions/duration, storage key and lifecycle;
- `notification_device_registration` for user-owned Android registrations when push starts;
- immutable `notification_audit` events.
Exact FK shapes are finalized from the canonical identity schema before migration; never invent a new user identity table.

Candidate REST contracts:
- Learner/parent: `GET /api/v1/notifications`, `GET /api/v1/notifications/{id}`, `POST /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all`.
- Education Admin: `/api/v1/admin/notifications/templates`, template preview/validation, media, audience preview, campaign send/list/detail/cancel/retry under permission guards.
- HRMS: equivalent routes below `/api/v1/hrms/notifications`, with explicit `QE_PLATFORM_OWNER` for cross-tenant send.
- Institution: equivalent routes below `/api/v1/institution/notifications`; scope derives solely from the institution principal.
- Android device registration and push delivery endpoints are added in the push phase.

## 9. Testing / definition of done

Use at least three real-schema test institutions with synthetic, explicitly test-owned users. Cover:
- user, course, institution and HRMS scopes; include eligible and ineligible audiences;
- student/parent/staff identity resolution; consent/guardian relationship; account/institution suspension; revoked course enrollment; transferred/multiple memberships;
- one user with several roles/workspaces; identity and read state never collide;
- direct-ID guessing, request header manipulation, tenant-A -> tenant-B access, HRMS non-owner denial, institution admin cross-tenant denial and Education Admin/HRMS permission separation;
- schema validation, unsupported blocks, script/HTML injection, unsafe URLs, oversized/corrupt/incorrect MIME assets, rejected uploads and private asset denial;
- template-version immutability, retries/idempotency, partial delivery, cancelled/scheduled campaigns, expired messages, outbox recovery, device token revocation and duplicate push;
- accessibility, responsive previews, English/Hinglish, reduced motion, offline/reconnect and web/Android parity;
- clean-database and upgraded-database Flyway run, API test suite, web/admin/People builds, Android build and relevant CI jobs.
Do not send real notifications to production users from fixtures or QA automation. Completion is not declared until exact-head CI and API/database acceptance evidence is green.

## 10. Delivery plan

1. **Contract and schema baseline:** verify current main, canonical IDs/relationships, auth filter/workspace handling, course assignment/enrollment tables, media capabilities and permission catalog.
2. **Durable backend and rich document validator:** migration, templates, campaigns, audience resolver, recipient inbox/read state, audit and idempotent transactional outbox. Initial dispatch creates persistent in-app notifications; external push remains disabled until configured.
3. **Template creator:** shared rich-block builder, media upload hooks, validation and live responsive/channel previews, template CRUD.
4. **Sender workspaces:** Education Admin, HRMS and Institution Admin creator/send UI, audience resolution preview, send confirmation and campaign history.
5. **User inbox and rich renderer:** web/parent and Android inbox/detail renderer; same records/read state.
6. **Realtime and Android push:** authenticated catch-up, device registration, provider adapter and delivery/retry observability.
7. **System-event notifications:** only after core audience/sending is secure; add explicit notification policy for course publication, live-class reminders, purchase/access results and account events.
8. **Acceptance and rollout:** three-institution isolation tests, asset hardening, usability/a11y, exact-head CI and staged enablement.

## 11. Implementation status (2026-10-20)

**Committed to `main` (runtime/CI verification still pending):**
- additive Flyway persistence for versioned templates, immutable campaign content snapshots, recipient/read state, media metadata, per-channel delivery attempts and audit events;
- permission-checked Education Admin, HRMS and Institution Admin template/options/recipient/preview/send/campaign APIs, including scheduled dispatch and cancellation;
- tenant/course/guardian-aware audience resolution, immutable HRMS target-institution snapshots, sender-scoped idempotency keys, and recipient-side institution checks for inbox/read/media;
- rich-document validation (block allow-list, URL checks, executable-markup rejection, nesting/size limits and alternative-text rules), private media upload/read handling, and unit tests for validator behavior;
- Canva-like editor with selectable/reorderable elements, appearance inspector, crop/focus controls, phone/web/push-layout preview, template library and campaign history; identical component source is kept across the separate Web, Education Admin and People/HRMS builds;
- a local vector-based Logo & Graphics Lab with editable concepts, palettes, layouts, PNG export and direct insertion into the notification draft;
- persistent learner/parent web inbox and Android inbox dialog, unread/read state, sender identity and safe destination handling;
- a CI contract script for shared surfaces, routes, tenant boundaries and rich-content safety.

**Not yet delivered/configured:**
- Live provider delivery is not yet verified against a configured Firebase project. Android FCM token registration, installation/account binding with per-recipient tenant eligibility checks, notification permission/channels, push-tap inbox detail, invalid-token revocation and bounded retries are now implemented. The provider remains disabled when server credentials are absent; no provider secret is committed.
- prompt-to-image generation: the logo tool creates editable local vector concepts and is not labelled AI-generated. An image-generation provider must be selected/configured before that capability can be truthfully enabled.
- institution employee/staff recipients: the existing schema intentionally separates tenant membership from `hr_employee`/`staff_account`; do not infer membership until HRMS defines and enforces the tenant-to-organization mapping.
- live database/Flyway tests, application builds and a green GitHub Actions result have not been confirmed from this session. CI now contains a contract check that will run on the main push, but source commits alone do not count as runtime verification.

Runtime configuration is server-side: set `APP_NOTIFICATIONS_FCM_PROJECT_ID` and `APP_NOTIFICATIONS_FCM_SERVICE_ACCOUNT_JSON_BASE64` on the API deployment, then provide the matching Android Firebase app values (`QUANTAEDGE_FIREBASE_PROJECT_ID`, `QUANTAEDGE_FIREBASE_APPLICATION_ID`, `QUANTAEDGE_FIREBASE_API_KEY`, `QUANTAEDGE_FIREBASE_SENDER_ID`) when building the APK/AAB. Encode the service-account JSON as one base64 line. HRMS platform owners can inspect delivery health at `GET /api/v1/hrms/notifications/push/status`; the endpoint never returns credentials or device tokens. Do not store real service-account credentials in GitHub source files.

The next acceptance gate is to apply the additive migration, run API/Android tests and exact-head CI, then verify real delivery against a Firebase test project and test-owned users before enabling production credentials.

## Server-enforced audience review

A new notification campaign must be previewed through the authenticated workspace API before it can be created or scheduled. Preview returns a random, single-use proof token that expires after 10 minutes; the database stores only its SHA-256 hash. The proof is bound to the sender identity/workspace, scope, institution/course, audience filters and exclusions, title, summary and normalized content document. Sending consumes the proof in the same transaction as campaign creation. A changed audience or message, expired token, missing token, or token already used for a different campaign requires a new review. Idempotent retries of an already accepted campaign remain replayable without creating a duplicate.

Institution exclusions are applied before cross-institution recipient deduplication. This preserves an account as a recipient through an included institution when its other institution is excluded, and keeps preview and send-time recipient semantics aligned. Eligibility and tenant membership are still re-resolved at send/dispatch time; preview is not a substitute for authorization or fresh eligibility checks.

Individual staff audience delivery remains fail-closed until a canonical staff-to-institution/course assignment contract and permissions are established. The staff inbox is available for messages delivered through supported, authorized paths; the presence of the inbox UI does not enable broad staff fan-out.

