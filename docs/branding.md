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
Both Next.js applications carry matching assets because they build independently:
- `apps/web/public/branding/quantaedge-logo.png` — full approved logo lockup including its tagline, retained as the standalone/export asset.
- `apps/web/public/branding/quantaedge-wordmark.png` — transparent high-resolution Q + QuantaEdge artwork without the baked-in tagline; used in live UI lockups.
- `apps/web/public/branding/quantaedge-icon.png` — square Q mark for browser and Apple icons and tiny icon-only placements.
- Matching copies are available under `apps/admin/public/branding/`.

## Rendering rule
The full lockup PNG includes its tagline in the bitmap. Displaying that entire image at compact navigation widths made the tagline too small to read. The live UI now composes the **transparent wordmark PNG + real HTML tagline** as one vertical lockup, preserving the generated artwork's exact typography and keeping the tagline crisp and readable. The Q-only icon remains reserved for browser tabs, touch icons and compact loading/interaction marks.

The shared web lockup is implemented in `apps/web/app/components/QuantaEdgeBrand.tsx`; the staff/admin shell uses equivalent accessible markup to accommodate its dark sidebar and responsive sign-in layout. Keep both PNG assets unchanged and do not recreate the wordmark from font glyphs.

## Placement map
- Public landing navigation and footer: full responsive lockup.
- Learner/parent sign-in: larger lockup.
- Student home, progress and parent management/report pages: compact lockup.
- Admin sidebar: compact lockup with high-contrast text for its dark background.
- Staff sign-in and loading state: the same Q mark; staff sign-in includes the full tagline.
- Browser tab and Apple touch icon: Q mark configured in each app's metadata.
- Shared web/admin accents: warm orange for brand highlights, deep orange for small text and controls, navy for structure, and neutral semantic success/error colours.

The logo should never be stretched. At narrow mobile widths use the compact size variant while retaining the tagline where the layout can accommodate it.
