# QuantaEdge brand system

**Primary tagline:** Smarter Decisions. Greater Growth.

## Visual identity
- **Primary mark:** the orange-to-red Q symbol from the approved QuantaEdge logo.
- **Wordmark:** “Quanta” in deep navy and “Edge” in orange.
- **Primary navy:** `#152038`
- **Primary orange:** `#F97316`
- **Deep orange:** `#C2410C`
- **Soft orange surface:** `#FFF0E5`
- **Canvas / cards:** white and very light cool neutral surfaces.

The identity takes the same practical approach as mPay: one recognizable mark, a simple wordmark, one short tagline, and consistent use across the product. QuantaEdge’s visual expression is its own and is tailored to an AI-powered learning platform.

## Asset locations
Both Next.js applications use the same canonical assets:
- `apps/web/public/branding/quantaedge-logo.png` — full lockup with tagline for public landing and sign-in.
- `apps/web/public/branding/quantaedge-icon.png` — square Q mark for compact brand positions and browser icons.
- Matching copies are available under `apps/admin/public/branding/` because the web and admin applications are independently built.

## Placement map
- Public landing page: full logo in the navigation and footer.
- Parent/student sign-in: full logo at the entry point.
- Student home, progress and parent pages: compact Q mark with the QuantaEdge wordmark.
- Admin workspace: compact Q mark in the sidebar and loading state; the staff sign-in page has the same mark and wordmark treatment.
- Browser tab and Apple touch icon: Q mark, set through each app's metadata.
- Shared web/admin styling: violet/purple accent tones are replaced by the QuantaEdge orange family, while navy, neutral surfaces and semantic success/error colors remain legible.

Keep logo aspect ratio; do not recreate the Q with a font glyph or recolor the logo asset. On dark surfaces, place the icon on a small white tile rather than altering its approved colors.
