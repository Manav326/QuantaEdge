# White-label colour theme contract

**Scope:** Colour theming only. Institution display names, logos, icons, store listing identity and other brand-asset work remain under the separate branding rollout.

**Contract version:** `themeTokens.schemaVersion = 2`.

## Runtime contract

An institution's `themeTokens` is server-normalized data, never tenant-provided CSS. The HRMS/platform-owner configuration endpoint accepts only:
- `schemaVersion: 2`
- `presetId` from the curated preset catalogue or `custom`
- `appearance: system | light | dark`
- `brandPrimary` and `brandAccent` as `#RRGGBB`

Legacy five-colour records remain readable. The API normalizes them to schema v2 and returns the complete generated palette; unknown properties, malformed colours, invalid presets and unsupported schema versions are rejected.

The normalized record keeps legacy `primary`, `accent`, `background`, `surface`, and `text` at the root for compatibility, plus generated `light` and `dark` maps. Each map provides action colours/foregrounds/containers, canvas and surfaces, main/muted text, borders/focus, selected/disabled states, semantic success/warning/error states, and navigation roles. Primary and accent each expose a container with a contrast-safe foreground (`primaryContainer`/`onPrimaryContainer`, `accentContainer`/`onAccentContainer`) for selected controls and secondary actions. The Java API tests and Android theme controller enforce the required role set.

## Catalogue and configuration

The platform owner can select from 16 presets, including QuantaEdge Orange (the QuantaEdge default), Academic Blue, Forest Green, Royal Violet, Teal, Burgundy, Navy & Gold, Graphite, Indigo, Emerald, Plum, Maroon, Raspberry, Cobalt, Slate and Custom. Custom selection configures primary/accent colours; surrounding semantic values are generated centrally. The preset catalogue is exposed from the owner-protected `GET /api/v1/hrms/platform/institutions/theme-presets` endpoint. `POST /api/v1/hrms/platform/institutions/theme-preview` accepts a candidate `themeTokens` source object and returns the normalized generated palette without saving it.

The institution editor saves the preset, default appearance and primary/accent colours. Its live learner preview calls the owner-protected preview endpoint, which runs the same server normalizer as save; it shows light/dark surfaces, a course card, selected state, primary action and success/warning/error feedback. QuantaEdge operator/Admin/HRMS brand identity remains on the canonical orange palette; tenant palettes belong only to trusted tenant-facing workspaces.

## Web implementation

- `TenantBrandingProvider` resolves institution context and supplies API-normalized `themeTokens`.
- `ColorThemeProvider` chooses light/dark from the user's explicit appearance preference or the system preference and maps semantic CSS variables on the document root.
- `AppearanceModeButton` cycles through School default, System, Dark and Light in the learner/parent workspace. School default always re-applies the institution's configured appearance after a user override.
- Existing CSS aliases are bridged to semantic roles during migration; new components should consume `--qe-color-*` roles rather than add fixed colours.
- Canonical QuantaEdge logo/name assets are not recoloured by this system.

## Android implementation

- `InstitutionThemeTokens` generates the API contract.
- `InstitutionBrandingRepository` returns the normalized theme payload as JSON alongside institution identity.
- `QuantaEdgeThemeController` validates required roles, persists the user's appearance preference and caches palettes under institution-code-specific keys. The active tenant palette is activated only after the existing trusted tenant-resolution flow identifies the institution.
- `QuantaEdgeTheme` maps the palette into Material 3 light/dark colour schemes.
- `ColorThemeSettingsCard` offers Institution, System, Light and Dark choices.
- Invalid payloads or unresolved context clear the active tenant palette; a locally cached palette is not activated from an unvalidated URL alone.

## Contrast, safety and acceptance

All input colours are six-digit hexadecimal values and tenant configuration cannot submit arbitrary CSS. Generated foreground colours are selected centrally. The navigation background is darkened further when necessary to maintain 4.5:1 contrast with navigation text, including bright custom primary colours. API tests cover schema handling, legacy migration, round trips, curated preset palettes and contrast. Normal text targets at least 4.5:1 contrast; focus indicators and interactive form-control outlines use the stronger border/focus roles and target at least 3:1. Decorative card separators may use the softer borderSubtle role. These checks do not substitute for visual verification of actual components.

CI runs `scripts/check-color-theme-contract.mjs` to assert that API, HRMS editor, web provider/CSS, Android controller/settings and tenant resolution remain connected. Release acceptance still requires a green exact-commit CI run plus browser/Android rendering checks; static source checks alone are not proof of visual correctness.
