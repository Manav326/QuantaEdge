# QuantaEdge brand system

**Primary tagline:** Smarter Decisions. Greater Growth.

## Visual identity
- **Primary mark:** the orange-to-red Q symbol from the approved second logo.
- **Wordmark:** “Quanta” in deep navy and “Edge” in orange.
- **Primary navy:** `#152038`
- **Primary orange:** `#F97316`
- **Deep orange for small text and active controls:** `#C2410C`
- **Soft orange surface:** `#FFF0E5`
- **Canvas / cards:** white and very light neutral surfaces.

The identity follows the practical approach used for mPay: one recognizable mark, a simple wordmark, a short tagline, and consistent placement. QuantaEdge's own Q mark, colours, typography and language are tailored to its AI-powered learning platform.

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
