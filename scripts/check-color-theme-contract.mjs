import { existsSync, readFileSync } from 'node:fs';

const failures = [];
let checks = 0;
function read(path) {
  checks += 1;
  if (!existsSync(path)) {
    failures.push('Required colour-theme contract file missing: ' + path);
    return '';
  }
  return readFileSync(path, 'utf8');
}
function assert(condition, message) {
  checks += 1;
  if (!condition) failures.push(message);
}

const apiTheme = read('services/api/src/main/java/com/quantaedge/api/InstitutionThemeTokens.java');
const platformApi = read('services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java');
const tenantApi = read('services/api/src/main/java/com/quantaedge/api/InstitutionTenantController.java');
const apiTests = read('services/api/src/test/java/com/quantaedge/api/InstitutionThemeTokensTest.java');
const tintContrastTests = read('services/api/src/test/java/com/quantaedge/api/InstitutionThemeTintContrastTest.java');
const accentContrastTests = read('services/api/src/test/java/com/quantaedge/api/InstitutionThemeAccentContrastTest.java');
const editor = read('apps/people/app/institutions/page.tsx');
const webRuntime = read('apps/web/app/components/ColorThemeProvider.tsx');
const webLayout = read('apps/web/app/layout.tsx');
const webWorkspace = read('apps/web/app/components/WorkspaceLayout.tsx');
const webCss = read('apps/web/app/globals.css');
const androidController = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeThemeController.kt');
const androidTheme = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeTheme.kt');
const androidApp = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidRepository = read('apps/android/app/src/main/java/com/quantaedge/android/data/InstitutionBrandingRepository.kt');
const androidSettings = read('apps/android/app/src/main/java/com/quantaedge/android/ui/ColorThemeSettingsCard.kt');
const androidTutor = read('apps/android/app/src/main/java/com/quantaedge/android/ui/TutorScreen.kt');
const androidScanner = read('apps/android/app/src/main/java/com/quantaedge/android/ui/PremiumCodeScannerScreen.kt');
const docs = read('docs/product/white-label-color-theme-contract.md');

assert(apiTheme.includes('SCHEMA_VERSION = 2'), 'API theme schema must be explicitly versioned.');
assert(apiTheme.includes('public static List<Map<String, Object>> presets()'), 'Server must expose a curated palette catalogue.');
assert(apiTheme.includes('"quantaedge-orange"') && apiTheme.includes('"custom"'), 'QuantaEdge default and custom presets must be supported.');
assert(apiTheme.includes('contrastRatio') && apiTheme.includes('ensureContrast'), 'Generated foregrounds must be contrast-aware.');
assert(apiTheme.includes('c.put("accentContainer", accentContainer)') && apiTheme.includes('c.put("onAccentContainer", bestContrastText(accentContainer))'), 'API must expose the accent container and readable foreground.');
assert(platformApi.includes('"/theme-presets"') && platformApi.includes('InstitutionThemeTokens.presets()'), 'Preset API must remain owner-controlled.');
assert(platformApi.includes('"/theme-preview"') && platformApi.includes('InstitutionThemeTokens.normalize(raw)'), 'Palette preview and save must use the owner-protected canonical normalizer.');
assert(tenantApi.includes('InstitutionThemeTokens.normalize'), 'Public tenant API must return generated palettes.');
assert(apiTests.includes('alreadyMaterializedThemeCanBeReadAndNormalizedAgain'), 'Theme round-trip regression test is required.');
assert(apiTests.includes('assertAccessible') && apiTests.includes('preset '), 'Palette contrast coverage must include curated presets.');
assert(tintContrastTests.includes('primaryTextKeepsAccessibleContrastOnLightAndDarkTintedContainers'), 'Tinted-container contrast regression test must remain in CI.');
assert(accentContrastTests.includes('accentTextAndForegroundsRemainReadableOnAccentContainersForEveryPreset'), 'Accent-container contrast regression test must remain in CI.');

assert(editor.includes('/api/v1/hrms/platform/institutions/theme-presets'), 'Institution editor must load server-owned presets.');
assert(editor.includes('schemaVersion:2') && editor.includes('brandPrimary') && editor.includes('brandAccent'), 'Institution editor must save schema v2 source colours.');
assert(editor.includes('appearance') && editor.includes('themePreviewGrid'), 'Institution editor must expose appearance and preview.');
assert(editor.includes('/api/v1/hrms/platform/institutions/theme-preview') && editor.includes('themePreviewStates') && !editor.includes('contrastTextForPreview'), 'Institution preview must use the server-generated full palette rather than a duplicate client colour algorithm.');
assert(webRuntime.includes('prefers-color-scheme: dark') && webRuntime.includes('qeAppearance'), 'Web runtime must resolve system/light/dark appearance.');
assert(webRuntime.includes('--qe-color-') && webRuntime.includes('themeTokens') && webRuntime.includes('accentContainer'), 'Web runtime must map all tenant semantic roles, including accent containers.');
assert(webLayout.includes('<ColorThemeProvider>'), 'Learner web root must install the theme provider.');
assert(webWorkspace.includes('<AppearanceModeButton />'), 'Learner/parent workspace must provide appearance control.');
for (const role of ['background', 'surface', 'text-muted', 'primary-container', 'accent-container', 'on-accent-container', 'success-container', 'error-container']) {
  assert(webCss.includes('--qe-color-' + role), 'Web CSS is missing semantic role: ' + role);
}
assert(webCss.includes('html[data-qe-appearance="dark"]'), 'Web CSS must define dark-mode surfaces/states.');
assert(webCss.includes('/* Tenant semantic compatibility layer for the legacy tutor and parent controls.'), 'Legacy tutor and parent controls must have a semantic-token bridge.');
assert(webCss.includes('.tutor-launch {\n  background: var(--qe-color-primary);'), 'Tutor launch action must use the active tenant primary colour.');
assert(webCss.includes('.tutor-message.user {\n  background: var(--qe-color-primary-container);'), 'Tutor learner messages must use tenant semantic container colours.');
assert(webCss.includes('.parent-notice, .parent-courses-page__alert {\n  border-color: var(--qe-color-success-container);'), 'Parent feedback must keep semantic success colours while adopting a tenant palette.');
assert(!/(?:background|color):#[0-9a-fA-F]{3,8}(?:background|color):/.test(webCss), 'CSS must not contain concatenated duplicate declarations.');
assert(/--qe-brand-orange\s*:\s*#f97316\b/i.test(webCss), 'QuantaEdge orange default must be preserved.');

assert(androidController.includes('KEY_TENANT_THEME_PREFIX') && androidController.includes('activateCachedInstitutionTheme'), 'Android palettes must be cached per institution.');
assert(androidController.includes('clearInstitutionTheme') && androidController.includes('schemaVersion'), 'Android must validate the version and clear stale tenant scope.');
assert(androidController.includes('"accentContainer", "onAccentContainer"'), 'Android must require the full accent-container role pair.');
assert(androidTheme.includes('darkColorScheme') && androidTheme.includes('lightColorScheme'), 'Android theme must support light and dark.');
assert(androidTheme.includes('"accentContainer"') && androidTheme.includes('"onAccentContainer"'), 'Android Material secondary roles must consume accent-container tokens.');
assert(androidSettings.includes('QeAppearanceMode.DARK') && androidSettings.includes('QeAppearanceMode.SYSTEM'), 'Android settings must expose appearance options.');
assert(androidTutor.includes('MaterialTheme.colorScheme.primary') && !/Color\\(0xFF151B2C\\)|Color\\(0xFFFFF0E3\\)/i.test(androidTutor), 'Android tutor must use semantic theme roles rather than fixed brand colours.');
assert(androidScanner.includes('scanPrimary = MaterialTheme.colorScheme.primary') && androidScanner.includes('scanOnPrimary = MaterialTheme.colorScheme.onPrimary'), 'Android scanner controls must use the active tenant palette.');
assert(androidRepository.includes('val themeTokensJson: String') && androidRepository.includes('optJSONObject("themeTokens")'), 'Android tenant API response must carry theme JSON.');
assert(androidApp.includes('themeController.applyInstitutionTheme(code, identity.themeTokensJson)'), 'Resolved Android tenant must activate its server palette.');
assert(androidApp.includes('themeController.clearInstitutionTheme()'), 'Android must clear the palette when tenant context is absent/invalid.');
assert(docs.includes('Contract version') && docs.toLowerCase().includes('acceptance'), 'Contract and acceptance criteria must be documented.');

if (failures.length) {
  console.error('White-label colour theme contract failed (' + failures.length + '/' + checks + ' checks):');
  for (const failure of failures) console.error(' - ' + failure);
  process.exitCode = 1;
} else {
  console.log('White-label colour theme contract passed (' + checks + ' checks).');
}
