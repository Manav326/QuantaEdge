# QuantaEdge Android — Play Store release checklist

## Build status and release inputs

The release configuration targets Android 16 / API 36, requires an HTTPS API endpoint, enables R8 code minification and resource shrinking, and requires an upload-key signature for both `assembleRelease` and `bundleRelease`. The distributable is the Android App Bundle (`app-release.aab`); Play generates device-specific APK splits from it.

Use the GitHub Actions workflow **Android Play Store release bundle**. Before running it, add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 of the private Play upload keystore.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Keep a secure offline backup of the upload key. Never commit key material or passwords. The workflow accepts the production HTTPS API URL and version fields; the version code must be higher than the latest version uploaded to Play Console.

## Store assets

Download `play-store-icon-512.png` from the workflow artifact and upload it as the app icon in Play Console. It is generated from the canonical small Q mark in `apps/web/public/branding/quantaedge-icon.png`, without the wordmark or a generated replacement.

## Required final checks before submission

- Confirm the production API URL is correct and reachable from a non-development network.
- Install the release build and test login/logout/session restoration for both student and parent accounts.
- Test lesson/practice flows, timed and offline assessments, AI Tutor, certificates and QR/barcode verification, textbook viewing, offline downloads, and saving PDF/PNG files.
- Check phone and tablet/landscape layouts, system bars and Android 16 edge-to-edge behavior, and verify there are no runtime crashes after R8 shrinking.
- Complete the Play listing, screenshots and feature graphic, public privacy-policy URL, Data safety declarations, content rating, target-audience declaration, app-access instructions, and any required review credentials.
- Upload the signed AAB to an internal testing track first, install it through Play, review Pre-launch report results, then promote through the intended release track.

Passing the source build is necessary but does not mean Play Console metadata is complete or that the app has been approved.
