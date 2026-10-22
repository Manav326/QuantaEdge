# QuantaEdge brand system

**Primary tagline:** Smarter Decisions. Greater Growth.

## Visual identity

**Approved interface direction (2026-10-16): preserve the existing orange-led QuantaEdge brand palette; improve layout and UX without changing brand colours.**

- **Canonical logo mark:** the existing approved orange-to-red Q symbol. Do not recolour, redraw, stretch or replace the source asset as part of a CSS/UI rollout.
- **Wordmark:** “Quanta” in deep navy and “Edge” in orange, using the approved shared rendering component.
- **Brand navy:** `#152038` (canonical identity and wordmark colour).
- **Interface surfaces:** continue using the current repository-approved navy, orange, neutral and semantic tokens; audit actual CSS before changing any placement or component styling.
- **Brand orange:** `#F97316`; deep orange `#C2410C`; soft orange `#FFF0E5` remain part of the orange-led visual identity. Preserve the existing interface use of these colours.
- **Light canvas:** `#F5F7FC`; white cards and reading surfaces.
- **Neutral border:** `#E5EAF3`; primary light-surface text `#172033`; secondary text `#667085`, subject to contrast validation.
- **Orange-led palette:** `#F97316`; deep orange `#C2410C`; soft orange `#FFF0E5`. Preserve the current approved uses throughout the interface; do not shift to a different dominant accent.

No brand colour refresh is approved. Preserve the current orange-led palette and use the existing Q mark and shared QuantaEdgeBrand component. Maintain one consistent mark, name, tagline, logo colours and wordmark across languages and apps. The user approved the planned UI/UX and layout/placement implementation on 2026-10-17. This does not authorize any change to the existing brand colours, canonical assets, wordmark, tagline, or cross-language brand treatment.

## Canonical assets
The approved square Q mark is the canonical, byte-identical asset at `apps/web/public/branding/quantaedge-icon.png`; the Admin, People Operations and API bundles use matching copies. The new canonical full lockup is `branding/quantaedge-logo.svg` in the Web, Admin and People Operations public assets. It embeds the approved Q mark and pairs it with the unchanged live brand name in navy/orange and the official tagline. The three lockup SVGs are byte-identical and checked by `scripts/check-ui-brand-contract.mjs`.

The standalone `quantaedge-logo.png` and `quantaedge-wordmark.png` remain legacy/export assets. The current wordmark PNG does not have a complete PNG data stream, so do not use it for live rendering. Use `quantaedge-logo.svg` for wide slots and `quantaedge-icon.png` for square slots.

## Rendering rule
The shared rendering contract now has two explicit roles:
- **Full logo URL:** wide header, landing, login and footer spaces. QuantaEdge itself uses `/branding/quantaedge-logo.svg`; a tenant uses its validated, versioned `logoUrl`.
- **Square icon URL:** compact navigation, browser/app-icon uses and launcher icon generation. A tenant uses its validated, versioned `appIconUrl`; the full logo URL must never be used as a favicon.
- **Institution name and tagline:** separately rendered text in compact layouts, with surrounding text and surfaces kept theme-aware. The uploaded artwork is not recoloured just because the app switches light/dark theme.

The shared learner renderer uses the full-logo slot for non-compact layouts and the icon + live text lockup for compact layouts. Admin and People Operations use the same central full-logo asset for wide variants while retaining the canonical icon/text compact variant. Android follows the equivalent role contract: it displays a tenant full logo in wide in-app space and uses the square app icon in compact headers. The static QuantaEdge name/tagline and approved colours remain unchanged across languages.


## Placement map
- Public landing navigation and footer: full responsive lockup.
- Learner/parent sign-in: larger lockup.
- Student home, progress and parent management/report pages: compact lockup.
- Admin sidebar: compact lockup with high-contrast text for its dark background.
- Staff sign-in and loading state: the same Q mark; staff sign-in includes the full tagline.
- Browser tab and Apple touch icon: Q mark configured in each app's metadata.
- Shared web/admin accents: warm orange for brand highlights, deep orange for small text and controls, navy for structure, and neutral semantic success/error colours.

The logo should never be stretched. At narrow mobile widths use the compact size variant while retaining the tagline where the layout can accommodate it.


## Certificate renderer asset

The API renderer packages a byte-for-byte mirror of `apps/web/public/branding/quantaedge-icon.png`
at `services/api/src/main/resources/branding/quantaedge-icon.png`, so PDF/PNG generation does not depend
on a live HTTP request to the website. It uses the same canonical Q mark, `QuantaEdge` name, tagline,
navy and orange as the learner-site lockup. The company name and tagline remain unchanged in every
language. If a valid approved wordmark export is supplied later, it can replace the live-text lockup
only as a coordinated central-brand update, not as an independent certificate restyle.
