# HRMS-managed institution integrations

**Owner:** HRMS / QE Platform Owner  
**Status:** Operating contract for tenant onboarding and the user–institution–course remediation  
**Related:** [Institution-first remediation tracker](../product/user-institution-course-access-remediation-tracker.md)

## Ownership rule

HRMS is the central operator for the institution network. Institution onboarding must not require a school to create, store, rotate, or supply provider credentials. HRMS maintains the platform-owned OAuth identities, payment credentials, OTP delivery credentials, approved origins/domains, provider configuration, and integration readiness checks.

The `QUANTA-EDGE` institution is the destination for pre-tenancy/global legacy accounts and history. It is not a bucket for new users arriving through another institution's entry point.

## Central configuration

These values are managed by the HRMS/platform operator in deployment secrets and runtime configuration. They must not be placed in source code, tenant-admin forms, browser bundles (except deliberately public client IDs), Android resources, logs, or client-visible API responses.

| Integration | Central configuration | Handling rule |
|---|---|---|
| Google identity | `GOOGLE_WEB_CLIENT_ID` (mapped by Compose to `APP_AUTH_GOOGLE_CLIENT_ID`) | The server verifies Google ID tokens against the configured audience. The public client ID may be returned by `/api/v1/auth/google/config`; no OAuth client secret is returned to the client. HRMS registers every active QuantaEdge/tenant web origin with the OAuth provider. Native Android credential flow uses the centrally configured server audience as designed by the client implementation. |
| Razorpay course checkout | `APP_RAZORPAY_KEY_ID`, `APP_RAZORPAY_KEY_SECRET`, `APP_RAZORPAY_BASE_URL` | Key ID is public checkout configuration; secret remains server-side. The server creates orders from an institution-published offer and validates the signature and captured payment against the persisted order. Institution ID, offer ID, amount/currency and entitlement must remain consistent for the entire purchase. |
| OTP delivery | `APP_OTP_PROVIDER_URL`, `APP_OTP_PROVIDER_TOKEN` | One HRMS-managed provider integration. A missing/invalid provider configuration must produce an actionable error, not create an account without verifying the number. |
| Google Meet/Calendar | `GOOGLE_MEET_CLIENT_ID`, `GOOGLE_MEET_CLIENT_SECRET`, `GOOGLE_MEET_REFRESH_TOKEN`, `GOOGLE_MEET_CALENDAR_ID` | Centrally managed integration. Tenant-specific schedules, sessions and access policy are data; provider credentials remain with HRMS. |
| Browser/workspace access | `APP_CORS_ALLOWED_ORIGINS`, `APP_AUTH_LEARNING_ORIGINS`, `APP_AUTH_EDUCATION_ADMIN_ORIGINS`, `APP_AUTH_HRMS_ORIGINS`, `APP_AUTH_INSTITUTION_ORIGINS` | HRMS updates allowlists before activating a tenant URL. A missing origin registration must be reported as an onboarding readiness issue and must never be solved by disabling origin/CSRF controls. |

**Configuration source matters:** production Compose maps the configured values into the API container. Adding a value to GitHub Actions Secrets alone does not make it effective unless the deploy workflow explicitly injects that secret into the deployment environment. Use the configured deployment secret store / protected production environment, then verify the running API's non-secret readiness state.

## Institution signup and identity rules

1. Resolve the institution from the QuantaEdge entry point, school-branded route/domain, configured Android tenant build, or validated institution join flow before account creation.
2. Bind a phone OTP challenge to the selected institution. A signup verification issued for institution A cannot create an account in institution B.
3. Create the identity/profile and institution membership in one transaction. If it fails, roll back the partial signup; do not issue a logged-in session for an unscoped account.
4. Existing Google/mobile/username sign-in checks active membership in the selected institution. Login never silently enrolls an old account into a tenant.
5. Parent-created children are scoped to the parent's selected institution when the parent is already a member. Otherwise, use an institution join code validated for that tenant. Preserve the guardian relationship and learning history.
6. One person may explicitly be a member of more than one institution where policy permits, but each relationship is separate and audited.

## HRMS onboarding checklist before tenant activation

- [ ] Institution row exists, is reviewed and activated by HRMS.
- [ ] Tenant code, learner URL/route, approved hostname and TLS/DNS setup resolve to the intended institution.
- [ ] HRMS registers the URL/origin with the centrally managed OAuth provider and platform allowlists.
- [ ] Centrally managed OAuth credentials are present and a real signup + existing-account login are tested for that tenant context.
- [ ] Razorpay platform credentials are configured by HRMS; the institution's offer/price/merchant display policy is correct.
- [ ] A test checkout in the approved environment proves order, payment verification, webhook/reconciliation path where configured, receipt/brand, purchase and entitlement remain in the same institution.
- [ ] Tenant membership, course assignment, marketplace offer, and course access are tested using both allowed and denied scenarios.
- [ ] The HRMS operator records the verification result and deploy/workflow version. No institution is told to supply credentials to clear an HRMS-side setup omission.

## Failure behavior

Missing central credentials return a clear platform-configuration error. An unknown, suspended or conflicting institution context fails closed. The UI should explain that the platform operator needs to complete setup; it must not redirect a learner to QuantaEdge, create global access, fall back to another tenant's credentials/data, or mark checkout/signup successful prematurely.

## Android Google sign-in: `[16] Account reauth failed`

- The Android client must use the same **Web OAuth client ID** returned by `GET /api/v1/auth/google/config` as the ID-token audience. Do not pass an Android OAuth client ID to `setServerClientId`.
- The Credential Manager bottom sheet first requests previously authorized accounts. If no such credential exists, or Google reports the specific `Account reauth failed` cancellation, the client must fall back to `GetSignInWithGoogleOption` instead of treating the provider's cancellation status as an ordinary user dismiss.
- On app logout, call `CredentialManager.clearCredentialState(ClearCredentialStateRequest())` best-effort to clear provider-side app session state. This does not sign the user out of Android or remove the Google account from the device.
- If the explicit flow still fails, inspect the Android OAuth client in the same Google Cloud project: the **application/package ID must exactly match the installed APK**, and the registered SHA-1 must match the certificate that signed that APK. Debug builds, locally signed release builds, tenant-branded packages with a different `applicationId`, and Play-distributed builds can have different package/SHA-1 combinations. For Play installs, verify the Play App Signing certificate SHA-1 as well as any local development certificate needed for dev builds. Do not change the API's Web client ID audience to the Android client ID.
- Test at minimum: account already authorized, first-time Google account, repeat sign-in after QuantaEdge logout, a Google account that requires re-authentication, and a deliberately mismatched package/SHA-1. Record Android version, installed package name, APK source/signing fingerprint and the exception class/message (never record ID tokens).



## Stable Android CI APK signing and Google OAuth registration

The main-branch workflow publishes the **quantaedge-android-apk-stable** installable artifact only when the permanent production signing keystore is configured in GitHub Actions secrets. Every release APK is signed automatically by Gradle with that same keystore. Do not generate a new keystore per commit or run; the certificate SHA-1 stays the same as long as the signing key remains unchanged.

For the directly installed CI APK:

1. Configure one permanent Android signing keystore in GitHub Actions secrets. The APK workflow and Play Store bundle workflow already use the same four secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Do not generate a new key per build.
2. After CI first publishes the stable-signed `quantaedge-android-apk-stable` artifact, run `scripts/android-fetch-latest-apk.ps1` once, copy the printed SHA-1 into an **Android OAuth client** in the HRMS-managed Google Cloud project for package **com.quantaedge.android**. Register it once; ordinary code commits/builds must not require re-registration.
3. Keep `GOOGLE_WEB_CLIENT_ID` mapped to the **Web application OAuth client** used by `/api/v1/auth/google/config`. Do not replace the server audience with the Android client ID.

Each APK still has to be signed as part of building it, but every build must reuse the same saved keystore. The SHA-1 belongs to that key and therefore stays constant; the routine CI workflow does not calculate or print it each run. Google Play may use a separate Play App Signing certificate if the app is distributed through Play.
