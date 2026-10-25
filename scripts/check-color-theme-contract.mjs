import { existsSync, readFileSync, readdirSync } from 'node:fs';

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
const webClassesCss = read('apps/web/app/student/classes/Classes.module.css');
const webTestsCss = read('apps/web/app/student/tests/Tests.module.css');
const webCertificatesCss = read('apps/web/app/student/certificates/Certificates.module.css');
const webCoursesCss = read('apps/web/app/student/courses/courses.module.css');
const webMarketplaceCss = read('apps/web/app/student/marketplace/marketplace.module.css');
const webStructuredCss = read('apps/web/app/student/structured-lessons/structured-lessons.css');
const webAssignedResourcesCss = read('apps/web/app/student/structured-lessons/AssignedLearningResources.module.css');
const webVerifyCertificateCss = read('apps/web/app/certificate/verify/Verify.module.css');
const webInstitutionScreen = read('apps/web/app/institution/page.tsx');
const webInstitutionRedirectStyles = read('apps/web/app/institution/InstitutionRedirect.module.css');
const webStudentProfile = read('apps/web/app/student/profile/page.tsx');
const webParentProfile = read('apps/web/app/parent/profile/page.tsx');

const webTextbookCss = read('apps/web/app/student/textbooks/textbooks.css');
const inlineTextbookReader = read('apps/web/app/student/learn/InlineTextbookReader.tsx');
const studentMarketplace = read('apps/web/app/student/marketplace/page.tsx');
const parentCourses = read('apps/web/app/parent/courses/page.tsx');

const androidController = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeThemeController.kt');
const androidTheme = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeTheme.kt');
const androidApp = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt');
const androidFloatingTutorDock = read('apps/android/app/src/main/java/com/quantaedge/android/ui/FloatingTutorDock.kt');
const androidRepository = read('apps/android/app/src/main/java/com/quantaedge/android/data/InstitutionBrandingRepository.kt');
const androidSettings = read('apps/android/app/src/main/java/com/quantaedge/android/ui/ColorThemeSettingsCard.kt');
const androidStudentProfile = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentProfileScreen.kt');
const androidParentProfile = read('apps/android/app/src/main/java/com/quantaedge/android/ui/ParentProfileScreen.kt');
const androidAccountIdentity = read('apps/android/app/src/main/java/com/quantaedge/android/ui/AccountIdentitySettingsScreen.kt');
const androidTutor = read('apps/android/app/src/main/java/com/quantaedge/android/ui/TutorScreen.kt');
const androidScanner = read('apps/android/app/src/main/java/com/quantaedge/android/ui/PremiumCodeScannerScreen.kt');
const androidWelcome = read('apps/android/app/src/main/java/com/quantaedge/android/ui/WelcomeScreen.kt');
const androidPreLoginTheme = read('apps/android/app/src/main/java/com/quantaedge/android/ui/PreLoginThemeControl.kt');
const androidLocalization = read('apps/android/app/src/main/java/com/quantaedge/android/ui/AndroidLocalization.kt');
const publicLanding = read('apps/web/app/page.tsx');

const androidCourseCatalog = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentCourseCatalog.kt');
const androidProgress = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentProgressScreen.kt');
const androidLifecycle = read('apps/android/app/src/main/java/com/quantaedge/android/ui/AccountLifecycleScreen.kt');
const androidMarketplace = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentMarketplaceScreen.kt');
const androidLessonContent = read('apps/android/app/src/main/java/com/quantaedge/android/ui/LessonContentBlock.kt');
const androidStructuredLesson = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonRenderer.kt');
const androidCertificates = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');
const androidParentMarketplace = read('apps/android/app/src/main/java/com/quantaedge/android/ui/ParentCourseMarketplaceScreen.kt');
const docs = read('docs/product/white-label-color-theme-contract.md');

assert(apiTheme.includes('SCHEMA_VERSION = 2'), 'API theme schema must be explicitly versioned.');
assert(apiTheme.includes('public static List<Map<String, Object>> presets()'), 'Server must expose a curated palette catalogue.');
assert(apiTheme.includes('"quantaedge-orange"') && apiTheme.includes('"custom"'), 'QuantaEdge default and custom presets must be supported.');
assert(apiTheme.includes('contrastRatio') && apiTheme.includes('ensureContrast'), 'Generated foregrounds must be contrast-aware.');
assert(apiTheme.includes('String navBackground = darkMode ? "#18243A" : "#F5F7FB";') && apiTheme.includes('String navActiveBackground = primaryContainer;'), 'Navigation must remain neutral and use a restrained active-state tint.');
assert(apiTheme.includes('c.put("accentContainer", accentContainer)') && apiTheme.includes('c.put("onAccentContainer", bestContrastText(accentContainer))'), 'API must expose the accent container and readable foreground.');
assert(platformApi.includes('"/theme-presets"') && platformApi.includes('InstitutionThemeTokens.presets()'), 'Preset API must remain owner-controlled.');
assert(platformApi.includes('"/theme-preview"') && platformApi.includes('InstitutionThemeTokens.normalize(raw)'), 'Palette preview and save must use the owner-protected canonical normalizer.');
assert(tenantApi.includes('InstitutionThemeTokens.normalize'), 'Public tenant API must return generated palettes.');
assert(apiTests.includes('alreadyMaterializedThemeCanBeReadAndNormalizedAgain'), 'Theme round-trip regression test is required.');
assert(apiTheme.includes('PREVIOUS_PRESETS') && apiTheme.includes('previously curated preset') && apiTests.includes('savedCuratedPresetsUpgradeButExplicitCustomColoursRemainUnchanged'), 'Previously saved preset palettes must upgrade while explicit custom colours remain unchanged.');

assert(apiTests.includes('assertAccessible') && apiTests.includes('preset '), 'Palette contrast coverage must include curated presets.');
assert(tintContrastTests.includes('primaryTextKeepsAccessibleContrastOnLightAndDarkTintedContainers'), 'Tinted-container contrast regression test must remain in CI.');
assert(accentContrastTests.includes('accentTextAndForegroundsRemainReadableOnAccentContainersForEveryPreset'), 'Accent-container contrast regression test must remain in CI.');

assert(editor.includes('/api/v1/hrms/platform/institutions/theme-presets'), 'Institution editor must load server-owned presets.');
assert(editor.includes('schemaVersion:2') && editor.includes('brandPrimary') && editor.includes('brandAccent'), 'Institution editor must save schema v2 source colours.');
assert(editor.includes('appearance') && editor.includes('themePreviewGrid'), 'Institution editor must expose appearance and preview.');
assert(editor.includes('/api/v1/hrms/platform/institutions/theme-preview') && editor.includes('themePreviewStates') && !editor.includes('contrastTextForPreview'), 'Institution preview must use the server-generated full palette rather than a duplicate client colour algorithm.');
assert(editor.includes('view.palette.accentContainer') && editor.includes('view.palette.onAccentContainer') && editor.includes('view.palette.accentText'), 'Institution preview must demonstrate primary and accent-container contrast, not just the primary action.');
assert(webRuntime.includes('prefers-color-scheme: dark') && webRuntime.includes('qeAppearance'), 'Web runtime must resolve system/light/dark appearance.');
assert(webRuntime.includes("export type AppearanceMode = 'institution' | 'system' | 'light' | 'dark'") && webRuntime.includes("const mode = savedMode ?? 'institution'") && webRuntime.includes('effectiveMode(mode, configuredMode, media.matches)'), 'Web users must be able to return to the institution-configured default after overriding appearance.');
assert(webRuntime.includes('--qe-color-') && webRuntime.includes('themeTokens') && webRuntime.includes('accentContainer'), 'Web runtime must map all tenant semantic roles, including accent containers.');
assert(webLayout.includes('<ColorThemeProvider>'), 'Learner web root must install the theme provider.');
assert(webWorkspace.includes('<AppearanceModeButton />'), 'Learner/parent workspace must provide appearance control.');
for (const role of ['background', 'surface', 'text-muted', 'primary-container', 'accent-container', 'on-accent-container', 'success-container', 'error-container']) {
  assert(webCss.includes('--qe-color-' + role), 'Web CSS is missing semantic role: ' + role);
}
assert(webCss.includes('html[data-qe-appearance="dark"]'), 'Web CSS must define dark-mode surfaces/states.');
assert(webCss.includes('/* Shared light-mode parity for legacy route components.') && webCss.includes('html[data-qe-appearance="light"] .qe-workspace-content'), 'Light appearance must receive the tenant semantic palette across legacy page components.');
assert(webCss.includes('--qe-elevation-1:') && webCss.includes('--qe-elevation-3:'), 'Shared web surface elevation scale is required for calm layering.');
assert(webCss.includes('background-color:var(--qe-color-surface);') && !webCss.includes('background-color:var(--qe-color-error-container);'), 'Ordinary cards and panels must never use error-container background in dark mode.');
assert(webCss.includes('.qe-language-switcher{display:inline-flex') && webCss.includes('background:var(--qe-color-surface);padding:6px 10px;color:var(--qe-color-text)'), 'The language switcher must use the active semantic surface in either appearance.');
assert(webClassesCss.includes('html[data-qe-appearance="dark"] .shell') && webClassesCss.includes('html[data-qe-appearance="dark"] .chatPanel') && webClassesCss.includes('var(--qe-color-success-container)'), 'Class sessions, live chat and status states must be themed.');
assert(webTestsCss.includes('html[data-qe-appearance="dark"] .leaderboardModal') && webTestsCss.includes('html[data-qe-appearance="dark"] .optionCorrect') && webTestsCss.includes('var(--qe-color-error-container)'), 'Tests, answers, results and leaderboard must use semantic dark states.');
assert(webCertificatesCss.includes('html[data-qe-appearance="dark"] .certificateIcon') && webCertificatesCss.includes('certificateImagePreview') && webCertificatesCss.includes('background: #FFFFFF;'), 'Certificate UI must follow theme while document previews retain paper.');
assert(webCoursesCss.includes('html[data-qe-appearance="dark"] .courseCard') && webCoursesCss.includes('var(--qe-color-primary)'), 'Owned courses cards and progress must consume semantic tokens.');
assert(webMarketplaceCss.includes('html[data-qe-appearance="dark"] .courseCard') && webMarketplaceCss.includes('var(--qe-color-warning-container)'), 'Marketplace, verification, request history and state badges must be themed.');
assert(webStructuredCss.includes('var(--qe-color-surface-elevated)') && webStructuredCss.includes('var(--qe-color-focus)') && webStructuredCss.includes('var(--qe-color-error-container)'), 'Structured lesson activity and lightbox controls must use shared tokens.');
assert(webAssignedResourcesCss.includes('var(--qe-color-error-container)') && webAssignedResourcesCss.includes('var(--qe-color-success)'), 'Assigned learning resource states must be semantic.');
assert(webVerifyCertificateCss.includes('html[data-qe-appearance="dark"] .card') && webVerifyCertificateCss.includes('var(--qe-color-border-subtle)'), 'Certificate verification must theme its page and form surfaces.');
assert(webInstitutionScreen.includes('InstitutionRouteRedirect') &&
  webInstitutionScreen.includes('styles.page') && webInstitutionScreen.includes('styles.card') &&
  webInstitutionRedirectStyles.includes('var(--qe-color-background)') &&
  webInstitutionRedirectStyles.includes('var(--qe-color-border-subtle)') &&
  webInstitutionRedirectStyles.includes('var(--qe-color-surface)'),
  'Retired institution route must redirect to Admin and use the central semantic palette.');
assert(webCss.includes('.student-profile-card{') && webCss.includes('.parent-profile-card{') && webCss.includes('background:var(--qe-color-surface)'), 'Student and parent profile pages must share neutral cards and inputs.');

assert(webCss.includes('--qe-elevation-1:') && webCss.includes('--qe-elevation-3:'), 'Shared web surface elevation scale is required for calm layering.');
assert(webCss.includes('background-color:var(--qe-color-surface);') && !webCss.includes('background-color:var(--qe-color-error-container);'), 'Ordinary cards and panels must never use error-container background in dark mode.');
assert(webCss.includes('prefers-reduced-motion: reduce') && webCss.includes('html[data-qe-appearance="dark"] .qe-workspace-content :is(h1, h2, h3, h4, h5, h6)'), 'Dark page typography must be theme-aware and motion must respect reduced-motion preference.');
assert(webTextbookCss.includes('html[data-qe-appearance="dark"] .qe-textbook-shell') && webTextbookCss.includes('html[data-qe-appearance="dark"] .qe-document-reader .qe-reader-workspace'), 'Textbook library and reader chrome must consume the shared dark palette.');
assert(webTextbookCss.includes('html[data-qe-appearance="light"] .qe-textbook-shell') && webTextbookCss.includes('html[data-qe-appearance="light"] .qe-document-reader .qe-reader-workspace'), 'Textbook library and reader chrome must also consume the shared light palette.');
assert(webCss.includes('/* One restrained elevation scale: cards gently lift from the canvas without looking pasted on. */') && webCss.includes('box-shadow: var(--qe-elevation-1);'), 'Page cards must share the restrained surface elevation and interaction system.');
assert(webCss.includes('/* Route-specific actions/states share the same semantic roles in both appearances. */') && webCss.includes('[class*="statusSCHEDULED"]'), 'Route-specific primary actions and status badges must map to semantic theme roles.');

assert(webTextbookCss.includes('html[data-qe-appearance="dark"] .qe-reader-page-image') && webTextbookCss.includes('background: #FFFFFF;'), 'Actual textbook pages must retain original document-paper colours rather than be recoloured.');
assert(inlineTextbookReader.includes("background: 'var(--qe-color-surface)'") && !inlineTextbookReader.includes("background: '#fffaf6'"), 'Inline textbook reader controls must not retain fixed light-only surfaces.');
assert(studentMarketplace.includes('tenantPrimaryColor(brand)') && !studentMarketplace.includes("theme: { color: '#E87524' }"), 'Student Razorpay checkout accent must follow the trusted institution theme.');
assert(parentCourses.includes('tenantPrimaryColor(brand)') && !parentCourses.includes("theme: { color: '#E87524' }"), 'Parent Razorpay checkout accent must follow the trusted institution theme.');

assert(webCss.includes('html[data-qe-appearance] .qe-workspace-content input:not([type="checkbox"]):not([type="radio"]):not([type="color"])') && webCss.includes('border-color: var(--qe-color-border);'), 'Interactive controls must use the stronger contrast-tested border role in both modes.');
assert(apiTests.includes('control border/background contrast') && apiTests.includes('control border/elevated-surface contrast'), 'API tests must cover non-text control-outline contrast.');
assert(webCss.includes('/* Tenant semantic compatibility layer for the legacy tutor and parent controls.'), 'Legacy tutor and parent controls must have a semantic-token bridge.');
assert(/\.tutor-head\.tutor-head--compact\s*\{\s*background:\s*linear-gradient\(/s.test(webCss) && /\.tutor-head-title,\s*\.tutor-head \.tutor-head-title,\s*\.tutor-heading-copy>strong\s*\{\s*color:\s*var\(--qe-color-on-surface\);/s.test(webCss), 'High-specificity compact tutor headers and titles must not override the tenant palette.');
assert(/\.tutor-language-indicator\s*\{\s*background:\s*var\(--qe-color-surface-elevated\);[\s\S]*?border-color:\s*var\(--qe-color-border-subtle\);/s.test(webCss), 'Tutor language indicator must use semantic neutral surfaces.');
assert(webCss.includes('.tutor-launch {\n  background: var(--qe-color-primary);'), 'Tutor launch action must use the active tenant primary colour.');
assert(webCss.includes('.tutor-message.user {\n  background: var(--qe-color-primary-container);'), 'Tutor learner messages must use tenant semantic container colours.');
assert(webCss.includes('.parent-notice, .parent-courses-page__alert {\n  border-color: var(--qe-color-success-container);'), 'Parent feedback must keep semantic success colours while adopting a tenant palette.');
assert(webCss.includes('.subject-book-link') && !webCss.includes('background:linear-gradient(135deg,#fffaf6,#fff)'), 'Student textbook links must not retain a fixed light-only card gradient.');
assert(webCss.includes('.qe-mcq-progress>i>span') && webCss.includes('linear-gradient(90deg,var(--qe-color-primary),var(--qe-color-accent))'), 'Learner question progress must use tenant primary/accent colours.');
assert(webCss.includes('.goal-card') && webCss.includes('color:var(--qe-color-nav-text)'), 'Learner goal cards must use semantic foreground/background roles.');
assert(webCss.includes('.lesson-rich-content code') && webCss.includes('background:var(--qe-color-surface-elevated);color:var(--qe-color-text)'), 'Inline lesson code blocks must remain readable on dark surfaces.');
assert(!/(?:background|color):#[0-9a-fA-F]{3,8}(?:background|color):/.test(webCss), 'CSS must not contain concatenated duplicate declarations.');
assert(/--qe-brand-orange\s*:\s*#f97316\b/i.test(webCss), 'QuantaEdge orange default must be preserved.');

assert(androidController.includes('KEY_TENANT_THEME_PREFIX') && androidController.includes('activateCachedInstitutionTheme'), 'Android palettes must be cached per institution.');
assert(androidController.includes('clearInstitutionTheme') && androidController.includes('schemaVersion'), 'Android must validate the version and clear stale tenant scope.');
assert(androidController.includes('"accentContainer", "onAccentContainer"'), 'Android must require the full accent-container role pair.');
assert(androidTheme.includes('darkColorScheme') && androidTheme.includes('lightColorScheme'), 'Android theme must support light and dark.');
assert(androidTheme.includes('"accentContainer"') && androidTheme.includes('"onAccentContainer"'), 'Android Material secondary roles must consume accent-container tokens.');
assert(androidSettings.includes('QeAppearanceMode.DARK') && androidSettings.includes('QeAppearanceMode.SYSTEM'), 'Android settings must expose appearance options.');
assert(androidPreLoginTheme.includes('updateAppearanceMode(QeAppearanceMode.INSTITUTION)') && androidPreLoginTheme.includes('updateAppearanceMode(QeAppearanceMode.SYSTEM)') && androidPreLoginTheme.includes('updateAppearanceMode(QeAppearanceMode.LIGHT)') && androidPreLoginTheme.includes('updateAppearanceMode(QeAppearanceMode.DARK)'), 'Android must expose all persisted theme choices before sign-in.');
assert(androidApp.includes('PreLoginThemeControl()') && androidApp.includes('route == AppRoute.ACCOUNT_SIGNUP') && androidApp.includes('route == AppRoute.PARENT_SIGNUP') && androidApp.includes('route == AppRoute.PARENT_RESET'), 'Android theme preference must remain available across all signed-out account routes.');
assert(androidLocalization.includes('UiPhrase("appearance_theme"') && androidLocalization.includes('UiPhrase("welcome_quality_learning"'), 'Android pre-login theme and broad learning copy must use the central phrase registry.');

assert(androidStudentProfile.includes('ColorThemeSettingsCard(language = language)') && androidParentProfile.includes('ColorThemeSettingsCard(language = language)') && !androidAccountIdentity.includes('ColorThemeSettingsCard(language = language)'), 'Appearance controls must be discoverable in both student and parent profiles, not mixed into sign-in identity settings.');
assert(androidTutor.includes('LocalQuantaEdgeSemanticColors.current.primaryText') && androidTutor.includes('color = if (selectedMode == mode.key) MaterialTheme.colorScheme.primary'), 'Android tutor text must use accessible semantic roles while selected mode surfaces retain the tenant primary fill.');
assert(androidTutor.includes('MaterialTheme.colorScheme.primaryContainer') && !androidTutor.includes('primaryTextContainer'), 'Learner tutor bubbles must use the validated primary container role, not a nonexistent text/container role.');
assert(androidWelcome.includes('LocalQuantaEdgeSemanticColors.current.primaryText') && androidWelcome.includes('MaterialTheme.colorScheme.onSurfaceVariant'), 'Android welcome labels must use contrast-safe text and neutral supporting roles.');
assert(androidWelcome.includes('localizedUiText("Quality learning for school, skills and growth.")') && !androidWelcome.includes('Classes 6–8 · Bihar Board'), 'Android general welcome must not present the whole platform as Classes 6–8 only.');
assert(publicLanding.includes('href="/login" className="text-link qe-public-sign-in"'), 'Public homepage must offer a direct sign-in link in addition to account creation.');
assert(publicLanding.includes('public.nav.forFamilies'), 'Public homepage must retain clear audience navigation for school, family, and adult learners.');

assert(androidCourseCatalog.includes('LocalQuantaEdgeSemanticColors.current.primaryText'), 'Android course progress labels must use contrast-safe text.');
assert(androidProgress.includes('LocalQuantaEdgeSemanticColors.current.success') && androidProgress.includes('MaterialTheme.colorScheme.onSurfaceVariant'), 'Android progress and certificate labels must use semantic success and neutral roles.');
assert(androidLifecycle.includes('LocalQuantaEdgeSemanticColors.current.success') && androidLifecycle.includes('MaterialTheme.colorScheme.onSurfaceVariant'), 'Android account lifecycle status and helper copy must use semantic roles.');
assert(androidMarketplace.includes('LocalQuantaEdgeSemanticColors.current.primaryText') && androidMarketplace.includes('LocalQuantaEdgeSemanticColors.current.success'), 'Android marketplace price, free and owned labels must be readable and semantically differentiated.');
assert(androidLessonContent.includes('LocalQuantaEdgeSemanticColors.current.primaryText') && androidLessonContent.includes('MaterialTheme.colorScheme.onSurfaceVariant'), 'Android lesson titles, solutions and captions must use contrast-safe roles.');
assert(androidStructuredLesson.includes('LocalQuantaEdgeSemanticColors.current.primaryText') && androidStructuredLesson.includes('"partially_correct" -> LocalQuantaEdgeSemanticColors.current.warning'), 'Structured lessons must use readable text and semantic practice feedback states.');
assert(androidCertificates.includes('"ISSUED" -> LocalQuantaEdgeSemanticColors.current.success') && androidCertificates.includes('MaterialTheme.colorScheme.error'), 'Certificate validity and revocation must remain semantic status cues.');
assert(androidAccountIdentity.includes('if (gmailVerified) LocalQuantaEdgeSemanticColors.current.success') && androidAccountIdentity.includes('if (mobileVerified) LocalQuantaEdgeSemanticColors.current.success'), 'Verified account methods must use semantic success, not the raw brand fill.');
assert(androidParentMarketplace.includes('LocalQuantaEdgeSemanticColors.current.primaryText') && androidParentMarketplace.includes('LocalQuantaEdgeSemanticColors.current.success'), 'Parent marketplace labels must use accessible primary text and semantic free/owned states.');
assert(androidScanner.includes('scanPrimary = MaterialTheme.colorScheme.primary') && androidScanner.includes('scanOnPrimary = MaterialTheme.colorScheme.onPrimary'), 'Android scanner controls must use the active tenant palette.');
assert(androidRepository.includes('val themeTokensJson: String') && androidRepository.includes('optJSONObject("themeTokens")'), 'Android tenant API response must carry theme JSON.');
assert(androidApp.includes('themeController.applyInstitutionTheme(code, identity.themeTokensJson)'), 'Resolved Android tenant must activate its server palette.');
assert(androidApp.includes('themeController.clearInstitutionTheme()'), 'Android must clear the palette when tenant context is absent/invalid.');
assert(androidFloatingTutorDock.includes('color = MaterialTheme.colorScheme.primary') && androidFloatingTutorDock.includes('contentColor = MaterialTheme.colorScheme.onPrimary') && !androidFloatingTutorDock.includes('Color(0xFF172033)'), 'The single floating tutor launcher/restore bubble must use tenant theme roles rather than a fixed navy palette.');
assert(docs.includes('Contract version') && docs.toLowerCase().includes('acceptance'), 'Contract and acceptance criteria must be documented.');



// Admin and HRMS route/component colour audit. Discover routes and CSS modules from the
// filesystem so new pages cannot silently bypass the common appearance cascade.
const adminThemeCss = read('apps/admin/app/admin-theme.css');
const adminGlobalCss = read('apps/admin/app/globals.css');
const peopleAppearanceCss = read('apps/people/app/people-appearance.css');
const peopleDesignCss = read('apps/people/app/people-design-system.css');
const adminWorkspaceLayout = read('apps/admin/app/components/AdminWorkspaceLayout.tsx');
const peopleWorkspaceLayout = read('apps/people/app/components/PeopleWorkspaceLayout.tsx');
const adminRootLayout = read('apps/admin/app/layout.tsx');
const peopleRootLayout = read('apps/people/app/layout.tsx');

function discoverFiles(directory, predicate) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = directory + '/' + entry.name;
    if (entry.isDirectory()) return discoverFiles(file, predicate);
    return predicate(file) ? [file] : [];
  }).sort();
}
const adminRouteFiles = discoverFiles('apps/admin/app', path => /\/page\.(?:tsx|ts|jsx|js)$/.test(path));
const peopleRouteFiles = discoverFiles('apps/people/app', path => /\/page\.(?:tsx|ts|jsx|js)$/.test(path));
const adminModuleStyles = discoverFiles('apps/admin/app', path => /\.module\.css$/.test(path));
const peopleModuleStyles = discoverFiles('apps/people/app', path => /\.module\.css$/.test(path));

assert(adminRouteFiles.length >= 15, 'The Admin colour audit must discover all current routes, not a hand-picked sample.');
assert(peopleRouteFiles.length >= 12, 'The HRMS colour audit must discover all current routes, not a hand-picked sample.');
assert(adminModuleStyles.length >= 10, 'The Admin component audit must cover all CSS-module component/page families.');
assert(peopleModuleStyles.length >= 5, 'The HRMS component audit must cover all CSS-module component/page families.');
assert(adminRootLayout.includes("import './admin-theme.css'") && adminWorkspaceLayout.includes('qe-admin-workspace-page-slot'),
  'Every Admin route must use the institution-aware shared workspace theme cascade.');
assert(peopleRootLayout.includes("import './people-appearance.css'") && peopleWorkspaceLayout.includes('qe-people-page-slot'),
  'Every HRMS route must use the HRMS-owned shared appearance cascade.');
assert(adminThemeCss.includes('Final dark-mode interaction contract') &&
  adminThemeCss.includes('input:not([type="checkbox"])') &&
  adminThemeCss.includes('background-color: var(--qe-color-surface-elevated) !important') &&
  adminThemeCss.includes('button[class*="sendButton"]') &&
  adminThemeCss.includes('var(--qe-color-on-accent'),
  'Admin must enforce readable focused fields, dark component surfaces and accent-safe action text on every route.');
assert(peopleAppearanceCss.includes('Final route-wide dark interaction contract') &&
  peopleAppearanceCss.includes('background-color: var(--qe-people-field) !important') &&
  peopleAppearanceCss.includes('tbody tr:is(:hover, :focus-within) > td') &&
  peopleAppearanceCss.includes('var(--qe-people-surface-hover)') &&
  peopleAppearanceCss.includes('color: #182337 !important'),
  'HRMS must enforce readable fields, table interaction states and contrast-safe filled actions across every route.');
assert(adminGlobalCss.includes('qe-admin-overview-shortcuts>a:focus-visible') &&
  adminThemeCss.includes('.qe-admin-overview-shortcuts > a:is(:hover, :focus-visible)'),
  'The legacy light-only Admin keyboard-focus override must be neutralized by the final dark theme layer.');
assert(peopleDesignCss.includes('tbody tr:focus-within') &&
  peopleAppearanceCss.includes('tbody tr:is(:hover, :focus-within) > td'),
  'The legacy light-only HRMS table focus/hover fill must be neutralized by the final dark theme layer.');
assert(adminThemeCss.includes('from { opacity: .72; }') && adminThemeCss.includes('to { opacity: 1; }') &&
  'Admin route animations must not create transformed ancestors that trap full-screen dialogs/lightboxes.');
assert(peopleAppearanceCss.includes('from { opacity: .72; }') && peopleAppearanceCss.includes('to { opacity: 1; }') &&
  'HRMS route animations must not create transformed ancestors that trap full-screen overlays.');

for (const path of [...adminRouteFiles, ...peopleRouteFiles]) {
  const source = read(path);
  assert(/\bexport\s+default\b/.test(source), 'Theme-audited route is missing its default page component: ' + path);
}
console.log('Admin/HRMS colour audit coverage: ' + adminRouteFiles.length + ' Admin routes, ' +
  peopleRouteFiles.length + ' HRMS routes, ' + adminModuleStyles.length + ' Admin CSS modules, and ' +
  peopleModuleStyles.length + ' HRMS CSS modules.');



// Learner Web notification inbox is audited alongside Admin and HRMS, because
// its row accordion, filter tabs and actions must not fall back to light-only
// focus/hover surfaces under an institution's dark palette.
const webNotificationCss = read('apps/web/app/components/NotificationInbox.module.css');
assert(webNotificationCss.includes('Notification inbox dark-state coverage') &&
  webNotificationCss.includes('.itemHeader:is(:hover, :focus-visible)') &&
  webNotificationCss.includes('background-color: var(--qe-color-surface-subtle)') &&
  webNotificationCss.includes('var(--qe-color-on-error-container)') &&
  webNotificationCss.includes('.itemActions button:focus-visible'),
  'Learner Web notification rows, filters, error feedback and actions must retain readable dark-mode interaction states.');
assert(webNotificationCss.includes('html[data-qe-appearance="dark"] .segmented button.selected') &&
  webNotificationCss.includes('html[data-qe-appearance="dark"] .item:focus-within'),
  'Learner Web notification selected/focused states must use tenant surfaces and visible focus without white overlays.');


/* Admin/HRMS regression coverage for the Oct 2026 theme and content-authoring audit. */
const adminContentStudio = read('apps/admin/app/content/page.tsx');
const adminContentEnglish = read('apps/admin/app/i18n/catalogs/english.json');
const adminContentHinglish = read('apps/admin/app/i18n/catalogs/hinglish.json');
const adminPagination = read('apps/admin/app/components/PremiumPagination.tsx');
const adminMarketplace = read('apps/admin/app/marketplace/page.tsx');
const adminMarketplaceCss = read('apps/admin/app/marketplace/Marketplace.module.css');
const adminResourcesCss = read('apps/admin/app/resources/Resources.module.css');
const adminQuestionsPage = read('apps/admin/app/questions/page.tsx');
const adminCertificatesCss = read('apps/admin/app/certificates/Certificates.module.css');
const adminAssessmentsCss = read('apps/admin/app/assessments/Assessments.module.css');
const adminClassesCss = read('apps/admin/app/classes/Classes.module.css');
const adminEmployees = read('apps/admin/app/employees/page.tsx');
const adminNotificationStudio = read('apps/admin/app/components/NotificationStudio.tsx');
const adminNotificationCss = read('apps/admin/app/components/NotificationStudio.module.css');
const peopleNotificationStudio = read('apps/people/app/components/NotificationStudio.tsx');
const peopleNotificationCss = read('apps/people/app/components/NotificationStudio.module.css');

assert(adminContentStudio.includes('PremiumPagination') && adminContentStudio.includes('pagedGroups.map') &&
  adminContentStudio.includes('expandedChapters') && adminContentStudio.includes('role="dialog" aria-modal="true"') &&
  adminContentStudio.includes('editorReturnFocusRef') && adminContentStudio.includes('backToLibraryRef.current?.()'),
  'Content Studio must paginate chapters, expand/collapse child micro-topics and preserve a focused accessible editor flow.');
assert(adminContentStudio.includes("String(selectedCourse?.course_type_code||'').toUpperCase()==='ASSESSMENT'") &&
  adminContentStudio.includes("'/api/v1/admin/assessments?'+params.toString()") &&
  adminContentStudio.includes('filteredAssessmentRows.map') &&
  adminContentStudio.includes("'&assessmentId='+encodeURIComponent(String(row.assessment_id))"),
  'Content Studio must use the assessment hierarchy for classless ASSESSMENT courses and expose each imported test question-review path.');
assert(adminContentStudio.includes("!isAssessmentCourse?<label>{t('admin.contentStudio.subject')}") &&
  adminContentStudio.includes('assessmentContentTitle') && adminContentStudio.includes("isAssessmentCourse ? <Link href={assessmentStudioHref"),
  'Assessment-only courses must hide K-12 subject filters/chapter creation and show course-type-specific content navigation.');
assert(adminContentEnglish.includes('"admin.contentStudio.courseSubjects": "Subjects with content"') &&
  adminContentEnglish.includes('"admin.contentStudio.databaseRecords": "From this course’s content records"') &&
  adminContentHinglish.includes('"admin.contentStudio.showMicroTopics"') &&
  adminContentHinglish.includes('"admin.contentStudio.hideMicroTopics"'),
  'Content Studio database metrics and chapter disclosure controls must have clear localized labels.');
assert(adminPagination.includes('var(--qe-color-surface') &&
  adminPagination.includes('var(--qe-color-primary') &&
  adminPagination.includes('var(--qe-color-text-muted'),
  'The shared pagination controls must use semantic institution theme tokens instead of fixed light surfaces.');
assert(adminMarketplace.includes('filteredRows') && adminMarketplace.includes('statusFilter') &&
  adminMarketplace.includes('expandedCourseId') && adminMarketplace.includes('Edit for your institute') &&
  adminMarketplaceCss.includes('.filters') && adminMarketplaceCss.includes('.offerSummary'),
  'Course Marketplace must provide compact summaries, search/status filters and expandable per-institution editing.');
assert(adminResourcesCss.includes('overflow-wrap:anywhere') &&
  adminResourcesCss.includes('word-break:break-word') &&
  adminResourcesCss.includes('.previewReaderToolbar') &&
  adminResourcesCss.includes('.previewPageViewport') &&
  adminResourcesCss.includes('.filterMenu') &&
  adminResourcesCss.includes('.workflowStep'),
  'Textbook PDF provenance must wrap and the picker, filters, review workflow and PDF chrome must remain theme-aware.');
assert(adminCertificatesCss.includes('transition-property:color,border-color !important') &&
  adminThemeCss.includes('tbody tr:is(:hover,:focus-within) > td'),
  'Admin certificate/table row hover must not animate into a white flash in dark mode.');
assert(adminThemeCss.includes('.qe-staff-tabs button b') &&
  adminThemeCss.includes('.qe-audit-event') &&
  adminThemeCss.includes('[class*="panelHeading"]'),
  'Staff, audit trail and teacher review queue counters/activity must use readable themed surfaces.');
assert(adminThemeCss.includes('.qe-workspace-fullscreen') &&
  adminThemeCss.includes('.qe-chapter-title strong') &&
  adminThemeCss.includes('[class*="testCard"]') &&
  adminThemeCss.includes('[class*="reviewQuestion"]') &&
  adminThemeCss.includes('[class*="tableWrap"]'),
  'Content, question-review, grading and student-table controls need the shared Admin theme contract.');
assert(adminQuestionsPage.includes('qe-question-review-card') &&
  adminQuestionsPage.includes('qe-question-review-note') &&
  adminThemeCss.includes('.qe-question-review-notice') &&
  adminThemeCss.includes('.qe-question-review-warning'),
  'Question review cards, explanations, review notes, notices and warning states must use semantic theme surfaces.');
assert(adminClassesCss.includes('.tabs button') &&
  adminThemeCss.includes('button :is(span,b,strong)'),
  'Live and recorded class tab counters must receive theme-aware selected/unselected styling.');
assert(adminEmployees.includes("className={tab==='audit'?'active':''}") &&
  peopleAppearanceCss.includes('.qe-people-page-slot :is(.tabs,.qe-staff-tabs,[role="tablist"]) button'),
  'Staff/audit navigation styling must remain covered in Admin and HRMS.');
assert(adminNotificationStudio.includes('data-surface={surface}') &&
  peopleNotificationStudio.includes('data-surface={surface}') &&
  adminNotificationCss.includes('.page .side') && adminNotificationCss.includes('.page .editor') &&
  peopleNotificationCss.includes('.page .side') && peopleNotificationCss.includes('.page .editor'),
  'Admin and HRMS notification editors must put summary cards under tabs and give the editor the available width.');


/* HRMS exhaustive route/component colour contract: both appearances, every shared and modular surface. */
const peopleSharedComponents = discoverFiles('apps/people/app/components', path => /\.(?:tsx|ts|jsx|js)$/.test(path));
const peopleInstitutionComponents = discoverFiles('apps/people/app/institutions', path => /\.(?:tsx|ts|jsx|js)$/.test(path) && !/\/page\.(?:tsx|ts|jsx|js)$/.test(path));
assert(peopleRouteFiles.length >= 13, 'The HRMS colour audit must enumerate every current route, including public login/change-password routes.');
assert(peopleModuleStyles.length >= 6, 'The HRMS colour audit must cover every CSS module family, including Finance, Institutions, QA and notification/brand editors.');
assert(peopleSharedComponents.length >= 9, 'The HRMS colour audit must enumerate shared UI components such as controls, dialogs, toasts and notification renderers.');
assert(peopleInstitutionComponents.some(path => path.endsWith('/InstitutionBrandAssetEditor.tsx')) &&
  peopleInstitutionComponents.some(path => path.endsWith('/InstitutionBrandTemplate.tsx')),
  'Institution logo crop and branding-template components must be included in the route/component audit.');

for (const path of [...peopleRouteFiles, ...peopleModuleStyles, ...peopleSharedComponents, ...peopleInstitutionComponents]) {
  const source = read(path);
  assert(source.trim().length > 0, 'HRMS route/component is empty or unreadable for theme audit: ' + path);
}
assert(peopleAppearanceCss.includes('HRMS route-complete appearance parity') &&
  peopleAppearanceCss.includes('Route-module surfaces: Finance, Institutions, QA') &&
  peopleAppearanceCss.includes('Nested copy and labels inside the older module layouts'),
  'HRMS shared CSS must centrally theme module-specific hard-coded surfaces and nested legacy text roles.');
assert(peopleAppearanceCss.includes('html[data-qe-people-appearance="light"]') &&
  peopleAppearanceCss.includes('html[data-qe-people-appearance="dark"]') &&
  peopleAppearanceCss.includes(':is(:focus, :focus-visible)') &&
  peopleAppearanceCss.includes('background-color: var(--qe-people-field) !important'),
  'Every HRMS route must keep contrast-safe surfaces, fields and visible focus indicators in both light and dark modes.');
assert(peopleAppearanceCss.includes('.login-card input') &&
  peopleAppearanceCss.includes('.qe-people-auth-panel input') &&
  peopleWorkspaceLayout.includes("pathname === '/login'") &&
  peopleWorkspaceLayout.includes("pathname === '/change-password'"),
  'Public login and password routes must be theme-aware even though they bypass the signed-in page slot.');
assert(peopleAppearanceCss.includes('[class*="primary"], [class*="Primary"]') &&
  peopleAppearanceCss.includes('color: #182337 !important') &&
  peopleAppearanceCss.includes('color: #fff !important'),
  'Filled actions must keep readable foregrounds against the correct light/dark orange and destructive action fills.');
assert(peopleAppearanceCss.includes('[class*="listItem"][class*="selected"]') &&
  peopleAppearanceCss.includes('[class*="warning"]') &&
  peopleAppearanceCss.includes('[class*="danger"]') &&
  peopleAppearanceCss.includes('[class*="cropRatioButton"]'),
  'Selected directory rows, semantic status states and institution asset-editor controls must remain theme-aware.');
assert(peopleAppearanceCss.includes('[class*="themePreview"]') &&
  peopleAppearanceCss.includes('[class*="brandTemplatePreview"]') &&
  peopleAppearanceCss.includes('[class*="paper"]') &&
  peopleAppearanceCss.includes('[class*="readOnlyLogo"]'),
  'Institution palette/logo previews and genuine document-paper assets must keep their intended preview colours.');
assert(peopleAppearanceCss.includes('prefers-reduced-motion: reduce') &&
  peopleAppearanceCss.includes('transition-duration: .01ms !important') &&
  peopleDesignCss.includes('tbody tr:focus-within'),
  'Appearance transitions must respect reduced-motion preferences while legacy focus/hover fills remain covered.');
for (const route of peopleRouteFiles) {
  const source = read(route);
  assert(source.includes('export default'), 'Every HRMS route must expose a component for the shared appearance audit: ' + route);
}
console.log('HRMS exhaustive theme coverage: ' + peopleRouteFiles.length + ' routes, ' +
  peopleSharedComponents.length + ' shared components, ' + peopleInstitutionComponents.length +
  ' institution subcomponents, and ' + peopleModuleStyles.length + ' CSS modules.');


assert(peopleAppearanceCss.includes('--surface-subtle: var(--qe-people-surface-subtle)') &&
  peopleAppearanceCss.includes('--surface-hover: var(--qe-people-surface-hover)') &&
  peopleAppearanceCss.includes('--qe-color-surface-subtle: var(--qe-people-surface-subtle)') &&
  peopleAppearanceCss.includes('--qe-color-text: var(--qe-people-ink)'),
  'Legacy and tokenized HRMS components must resolve their surfaces and text to the active appearance rather than fixed light fallbacks.');
assert(peopleAppearanceCss.includes('--qe-color-primary: var(--qe-people-brand-strong)') &&
  peopleAppearanceCss.includes('--qe-color-on-primary: var(--qe-people-on-brand)') &&
  peopleAppearanceCss.includes('--qe-people-on-brand: #182337;'),
  'Filled HRMS accent surfaces must use a contrast-safe foreground in dark mode without changing the QuantaEdge orange brand.');
assert(peopleAppearanceCss.includes('[class*="selectedTag"]') &&
  peopleAppearanceCss.includes('[class*="previewButton"]') &&
  peopleAppearanceCss.includes('[class*="brandTemplateGenerated"]'),
  'Theme editor and notification studio component actions, selection badges and generated-state panels must remain readable.');


/* Contrast-check the actual HRMS light/dark semantic token pairs (WCAG AA: 4.5:1). */
function themeBlock(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  return start >= 0 && end > start ? source.slice(start, end) : '';
}
function themeHex(block, token) {
  const match = block.match(new RegExp('(?:^|\\n)\\s*--' + token + ':\\s*(#[0-9a-fA-F]{6})\\s*;'));
  return match ? match[1] : null;
}
function relativeLuminance(hex) {
  if (!hex || !/^#[0-9a-fA-F]{6}$/.test(hex)) return null;
  const channels = [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4));
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}
function contrastRatio(foreground, background) {
  const fg = relativeLuminance(foreground);
  const bg = relativeLuminance(background);
  if (fg === null || bg === null) return null;
  return (Math.max(fg, bg) + 0.05) / (Math.min(fg, bg) + 0.05);
}
function assertThemeContrast(block, mode, foregroundToken, backgroundToken) {
  const foreground = themeHex(block, foregroundToken);
  const background = themeHex(block, backgroundToken);
  const ratio = contrastRatio(foreground, background);
  assert(ratio !== null && ratio >= 4.5,
    'HRMS ' + mode + ' theme fails WCAG AA contrast for --' + foregroundToken +
    ' on --' + backgroundToken + ' (ratio=' + (ratio === null ? 'unavailable' : ratio.toFixed(2)) + ':1).');
}
const peopleLightTokens = themeBlock(peopleAppearanceCss, ':root {', '\n}\n\nhtml[data-qe-people-appearance="dark"]');
const peopleDarkTokens = themeBlock(peopleAppearanceCss, 'html[data-qe-people-appearance="dark"] {', '\n}\n\n/* Before client preference restoration');
const contrastPairs = [
  ['qe-people-ink', 'qe-people-card'],
  ['qe-people-muted', 'qe-people-card'],
  ['qe-people-brand-strong', 'qe-people-card'],
  ['qe-people-on-brand', 'qe-people-brand-strong'],
  ['qe-people-success', 'qe-people-success-soft'],
  ['qe-people-warning', 'qe-people-warning-soft'],
  ['qe-people-danger', 'qe-people-danger-soft'],
  ['qe-people-info', 'qe-people-info-soft'],
  ['qe-people-focus', 'qe-people-card']
];
for (const [foreground, background] of contrastPairs) {
  assertThemeContrast(peopleLightTokens, 'light', foreground, background);
  assertThemeContrast(peopleDarkTokens, 'dark', foreground, background);
}

if (failures.length) {
  console.error('White-label colour theme contract failed (' + failures.length + '/' + checks + ' checks):');
  for (const failure of failures) console.error(' - ' + failure);
  process.exitCode = 1;
} else {
  console.log('White-label colour theme contract passed (' + checks + ' checks).');
}
