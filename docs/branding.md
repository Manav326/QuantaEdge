# QuantaEdge brand system

**Primary tagline:** Smarter Decisions. Greater Growth.

## Visual identity

**Approved interface direction (2026-10-16): premium hybrid with midnight navy, royal blue and electric-blue UI accents, plus light reading/work surfaces.**

- **Canonical logo mark:** the existing approved orange-to-red Q symbol. Do not recolour, redraw, stretch or replace the source asset as part of a CSS/UI rollout.
- **Wordmark:** “Quanta” in deep navy and “Edge” in orange, using the approved shared rendering component.
- **Brand navy:** `#152038` (canonical identity and wordmark colour).
- **Interface midnight navy:** `#101828` (dark hero/navigation surfaces).
- **Primary interface blue:** `#315EFB` (primary controls, links, active/selected states).
- **Electric blue:** `#6C8CFF` (restrained highlights and gradients; not for small text on light backgrounds).
- **Light canvas:** `#F5F7FC`; white cards and reading surfaces.
- **Neutral border:** `#E5EAF3`; primary light-surface text `#172033`; secondary text `#667085`, subject to contrast validation.
- **Legacy brand orange:** `#F97316`; deep orange `#C2410C`; soft orange `#FFF0E5`. Retain these for the canonical mark/wordmark and existing brand-specific contexts, but do not use orange as the default UI action colour in the refreshed interface.

The interface palette is intentionally refreshed without altering the approved logo asset or wordmark. Use the existing Q mark and shared QuantaEdgeBrand component; blue is the interface interaction colour, while orange remains a recognisable part of the official logo. Maintain one consistent mark, name, tagline and placement across languages and apps.

## Canonical assets
The approved square Q mark is the canonical, byte-identical asset at `apps/web/public/branding/quantaedge-icon.png`; it is the shared live mark and the API renderer mirrors that exact file. The standalone `quantaedge-logo.png` and `quantaedge-wordmark.png` are legacy/export assets. The current wordmark PNG does not have a complete PNG data stream, so do not use it for live rendering or treat it as a trustworthy input until a valid approved export replaces it. Matching branding assets are copied into the separately built admin and People Operations applications.

## Rendering rule
The live learner-site lockup is implemented by `apps/web/app/components/QuantaEdgeBrand.tsx`: it pairs the approved Q mark with the unchanged `QuantaEdge` wordmark in the canonical navy/orange palette and the readable HTML tagline. This remains the same in every language; never translate or transliterate the company name/tagline, and never create language-specific logo variants. All certificate languages use the same company name, mark, tagline, and brand colours.


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
