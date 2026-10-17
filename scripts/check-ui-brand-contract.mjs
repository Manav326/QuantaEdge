import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const failures = [];
let checks = 0;

function assert(condition, message) {
  checks += 1;
  if (!condition) failures.push(message);
}

function read(path) {
  const file = join(root, path);
  assert(existsSync(file), 'Required UI contract file is missing: ' + path);
  return existsSync(file) ? readFileSync(file, 'utf8') : '';
}

function requireBrandTokens(source, app) {
  assert(/--qe-brand-orange\s*:\s*#f97316\b/i.test(source), app + ' must preserve canonical QuantaEdge orange (#F97316).');
  assert(/--qe-brand-orange-deep\s*:\s*#c2410c\b/i.test(source), app + ' must preserve canonical deep orange (#C2410C).');
}

function requireInteractionBaseline(source, app) {
  assert(/focus-visible/i.test(source), app + ' must retain a visible keyboard focus treatment.');
  assert(/prefers-reduced-motion\s*:\s*reduce/i.test(source), app + ' must honor reduced-motion preferences.');
  assert(/forced-colors\s*:\s*active/i.test(source), app + ' must provide a forced-colors focus fallback.');
  assert(/@media\s*\(\s*max-width\s*:\s*\d+px\s*\)/i.test(source), app + ' must include responsive breakpoint rules.');
}

const branding = read('docs/branding.md');
const brandComponent = read('apps/web/app/components/QuantaEdgeBrand.tsx');
const tenantBrandingProvider = read('apps/web/app/components/TenantBrandingProvider.tsx');
const schoolEntryPage = read('apps/web/app/school/[institutionCode]/page.tsx');
const hrmsInstitutionPage = read('apps/people/app/institutions/page.tsx');
const androidBuildConfig = read('apps/android/app/build.gradle.kts');
const androidTenantBrand = read('apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeBrand.kt');
const androidTenantRepository = read('apps/android/app/src/main/java/com/quantaedge/android/data/InstitutionBrandingRepository.kt');
const androidAppManifest = read('apps/android/app/src/main/AndroidManifest.xml');
const androidTenantMigration = read('services/api/src/main/resources/db/migration/V121__institution_branding_asset_storage.sql');
const institutionTenantController = read('services/api/src/main/java/com/quantaedge/api/InstitutionTenantController.java');
const institutionPlatformController = read('services/api/src/main/java/com/quantaedge/api/PlatformInstitutionController.java');
const institutionAuthService = read('services/api/src/main/java/com/quantaedge/api/InstitutionAuthService.java');
const androidCertificateScreen = read('apps/android/app/src/main/java/com/quantaedge/android/ui/StudentCertificatesScreen.kt');
const tutorService = read('services/api/src/main/java/com/quantaedge/api/TutorService.java');
const colorThemeProvider = read('apps/web/app/components/ColorThemeProvider.tsx');
const adminBrandComponent = read('apps/admin/app/components/QuantaEdgeBrand.tsx');
const peopleBrandComponent = read('apps/people/app/components/QuantaEdgeBrand.tsx');
const adminSidebar = read('apps/admin/app/components/AdminSidebar.tsx');
const adminLogin = read('apps/admin/app/login/page.tsx');
const adminWorkspaceLayout = read('apps/admin/app/components/AdminWorkspaceLayout.tsx');
const peopleWorkspace = read('apps/people/app/components/PeopleWorkspaceLayout.tsx');
const peopleLogin = read('apps/people/app/login/page.tsx');
const peopleChangePassword = read('apps/people/app/change-password/page.tsx');
const webLayout = read('apps/web/app/layout.tsx');
const workspaceLayout = read('apps/web/app/components/WorkspaceLayout.tsx');
const adminLayout = read('apps/admin/app/layout.tsx');
const peopleLayout = read('apps/people/app/layout.tsx');
const webStyles = read('apps/web/app/globals.css');
const adminStyles = read('apps/admin/app/globals.css');
const peopleBaseStyles = read('apps/people/app/globals.css');
const peopleStyles = read('apps/people/app/people-design-system.css');

for (const token of ['#F97316', '#C2410C', '#FFF0E5', 'Smarter Decisions. Greater Growth.']) {
  assert(branding.includes(token), 'Canonical brand documentation must include ' + token + '.');
}
assert(brandComponent.includes('/branding/quantaedge-icon.png'), 'Live QuantaEdge brand component must use the canonical Q-mark asset.');
assert(brandComponent.includes('Smarter Decisions. Greater Growth.'), 'Live QuantaEdge brand component must retain the unchanged tagline.');
assert(adminBrandComponent === peopleBrandComponent, 'Admin and People Operations must keep the same canonical operator brand component.');
assert(brandComponent.includes('useTenantBranding') && brandComponent.includes('brand.displayName'), 'Learner web brand lockup must consume resolved tenant branding.');
assert(tenantBrandingProvider.includes('/api/v1/institution/public/branding/') && tenantBrandingProvider.includes('institution/memberships/me'), 'Tenant branding provider must resolve trusted institution codes and a single learner membership.');
assert(!/\.style\.setProperty\(['"]--qe-(?:brand-orange|color-)/.test(tenantBrandingProvider), 'Tenant identity resolution must not write palette variables outside the shared theme system.');
assert(!workspaceLayout.includes('/student/institutions') && !workspaceLayout.includes('mySchool'), 'Learner navigation must not expose a school/institution selector; school identity is implicit in the branded app.');
assert(androidTenantBrand.includes('LocalInstitutionBrandIdentity') && androidTenantBrand.includes('institution.displayName'), 'Android shared brand lockup must render a resolved institution name.');
assert(androidTenantRepository.includes('/api/v1/institution/public/branding/') && androidTenantRepository.includes('/api/v1/institution/join'), 'Android must resolve public school branding and use the membership join API.');
assert(tenantBrandingProvider.includes('tenantCodeFromSchoolPath') && tenantBrandingProvider.includes('JOIN_TENANT_SESSION_KEY'), 'Web branding must bind invitation context to the school-specific path across sign-in redirects.');
assert(tenantBrandingProvider.includes('window.location.hash') && tenantBrandingProvider.includes('history.replaceState'), 'Invitation codes must travel in URL fragments and be removed from browser history after safe capture.');
assert(institutionTenantController.includes('value(body,"institutionCode")') && institutionAuthService.includes("and (?='' or t.tenant_code=?)"), 'Server redemption must bind a join code to the expected school when the link specifies one.');
assert(institutionTenantController.includes('RequestParam(value="institutionCode"') && institutionTenantController.includes('requiresInstitutionContext'), 'The learner membership endpoint must scope to a school and avoid multi-school disclosure.');
assert(androidCertificateScreen.includes('institutionBrand?.institutionCode?.let { body.put("institutionCode", it) }'), 'Android certificate issuance must preserve the current school issuer context.');
assert(tutorService.includes('You are a helpful tutor for school learners') && !tutorService.includes('You are QuantaEdge Tutor for school learners'), 'Generated tutor answers must not identify QuantaEdge as the learner-facing tutor.');
assert(colorThemeProvider.includes('brand?.themeTokens') && colorThemeProvider.includes('applyPalette(actual, tokens)'), 'Tenant identity must continue to feed the existing shared v2 theme provider without a parallel palette system.');
assert(schoolEntryPage.includes('brand.displayName') && schoolEntryPage.includes('/login/student') && schoolEntryPage.includes('/signup'), 'A direct school invitation must show the school brand before learner authentication.');
assert(hrmsInstitutionPage.includes('schoolLearningLink') && hrmsInstitutionPage.includes("new URL('/school/" ) && hrmsInstitutionPage.includes('Copy school learning link'), 'HRMS must generate a shareable school-branded invite URL rather than only exposing the raw join code.');
assert(androidAppManifest.includes('${institutionLinkPath}') && androidAppManifest.includes('${applicationLabel}') && androidAppManifest.includes('${applicationIcon}'), 'Android launcher identity and app-link route must be configured per branded build.');
assert(androidBuildConfig.includes('configuredInstitutionApplicationId') && androidBuildConfig.includes('configuredInstitutionIconPath') && androidBuildConfig.includes('institutionLinkPath'), 'Android per-school builds must use a unique package ID, supplied school icon and school-scoped link path.');
assert(androidTenantMigration.includes('CREATE TABLE institution_branding_asset') && androidTenantMigration.includes('image_bytes'), 'Institution branding image storage must be tenant-scoped and bounded.');
assert(institutionTenantController.includes('/assets/{assetType}') && institutionTenantController.includes('assetUrl(normalized'), 'The API must serve verified logo and app-icon assets only through the resolved tenant.');
assert(institutionPlatformController.includes('ownedBrandAssetKey') && institutionPlatformController.includes('uploadBrandingAsset'), 'Only uploaded, tenant-owned branding assets may be selected from HRMS.');
assert(adminSidebar.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'Admin sidebar must use the canonical shared brand component.');
assert(adminWorkspaceLayout.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'Admin mobile workspace header must use the shared brand component.');
assert(adminWorkspaceLayout.includes('className="qe-admin-global-brand"'), 'Admin mobile workspace header must render the canonical brand lockup.');
assert(peopleWorkspace.includes('className="qe-people-mobile-brand-lockup"'), 'People mobile workspace header must render the canonical brand lockup.');
assert(!peopleWorkspace.includes('<span>QUANTAEDGE</span>'), 'People workspace must not hard-code a duplicate brand name outside the shared component.');
assert(peopleWorkspace.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'People workspace must use the canonical shared brand component.');
assert(!adminSidebar.includes('quantaedge-wordmark.png'), 'Admin sidebar must not render the invalid legacy wordmark PNG.');
assert(adminLogin.includes("import QuantaEdgeBrand from '../components/QuantaEdgeBrand'"), 'Admin login must use the canonical shared brand component.');
assert(adminLogin.includes('<QuantaEdgeBrand href="/" variant="auth"') && adminLogin.includes('<QuantaEdgeBrand href="/" variant="compact"'), 'Admin login must use the canonical lockup on desktop and mobile.');
assert(!adminLogin.includes('quantaedge-wordmark.png'), 'Admin login must not render the invalid legacy wordmark PNG.');
assert(!peopleWorkspace.includes('quantaedge-wordmark.png') && !peopleLogin.includes('quantaedge-wordmark.png') && !peopleChangePassword.includes('quantaedge-wordmark.png'), 'People workspace and authentication must not render the invalid legacy wordmark PNG.');
assert(peopleLogin.includes('<QuantaEdgeBrand href={null} variant="auth"') && peopleChangePassword.includes('<QuantaEdgeBrand href={null} variant="auth"'), 'HRMS login and password-change screens must use the shared brand lockup.');
assert(webLayout.includes('/branding/quantaedge-icon.png'), 'Learner web must retain the QuantaEdge fallback browser icon.');
assert(webLayout.includes('TenantBrandingProvider'), 'Learner web must mount the tenant branding provider globally.');
assert(adminLayout.includes('/branding/quantaedge-icon.png'), 'Admin must configure the canonical browser/app icon.');
assert(peopleLayout.includes('/branding/quantaedge-icon.png'), 'People Operations must configure the canonical browser/app icon.');
requireBrandTokens(webStyles, 'Learner web');
requireBrandTokens(adminStyles, 'Admin');
assert(/--brand\s*:\s*#f97316\b/i.test(peopleStyles), 'People Operations must use canonical orange as its primary brand token.');
assert(/--brand2\s*:\s*#c2410c\b/i.test(peopleStyles), 'People Operations must use canonical deep orange for its strong brand token.');
assert(/--brand-soft\s*:\s*#fff0e5\b/i.test(peopleStyles), 'People Operations must use the canonical soft-orange surface.');
requireInteractionBaseline(webStyles, 'Learner web');
requireInteractionBaseline(adminStyles, 'Admin');
requireInteractionBaseline(peopleStyles, 'People Operations');
assert(/--qe-answer-incorrect-text\s*:\s*#b42318\b/i.test(webStyles), 'Learner web must retain distinct incorrect-answer feedback styling.');
assert(/--qe-answer-incorrect-text\s*:\s*#b42318\b/i.test(adminStyles), 'Admin preview must retain distinct incorrect-answer feedback styling.');
assert(/\.practice-feedback-incorrect/.test(webStyles), 'Practice UI must retain the explicit incorrect-answer feedback treatment.');
assert(/\.qe-people-status--success/.test(peopleStyles) && /\.qe-people-status--danger/.test(peopleStyles), 'People Operations must retain distinct success and error status variants.');
assert(/overscroll-behavior-inline:\s*contain/.test(webStyles), 'Learner surfaces must protect horizontal scroll regions on touch devices.');
assert(/overscroll-behavior-inline:\s*contain/.test(adminStyles), 'Admin data/preview surfaces must protect horizontal scroll regions.');
assert(webStyles.includes('.qe-workspace-layout') && webStyles.includes('.qe-structured-student-page') && webStyles.includes('.qe-practice-page'), 'Learner responsive polish must target actual learner workspace, lesson and practice markup.');
assert(adminStyles.includes('.qe-editor-actions') && adminStyles.includes('.qe-readiness-summary') && adminStyles.includes('@media (max-width: 720px)'), 'Admin responsive polish must cover Content Studio authoring actions and small-screen grids.');
assert(/tbody tr:focus-within/.test(peopleStyles), 'People tables must keep keyboard focus within rows discoverable.');
assert(!/(#244c9a|#17376f|#10264b|#3569c8|#6e94d6)/i.test(peopleBaseStyles + peopleStyles), 'People Operations must not regress to its former blue palette.');

const brandAssetPaths = [
  'apps/web/public/branding/quantaedge-icon.png',
  'apps/admin/public/branding/quantaedge-icon.png',
  'apps/people/public/branding/quantaedge-icon.png',
];
const brandAssets = brandAssetPaths.map(path => {
  const absolute = join(root, path);
  assert(existsSync(absolute) && statSync(absolute).size > 0, 'Missing packaged canonical brand asset: ' + path);
  return existsSync(absolute) ? readFileSync(absolute) : Buffer.alloc(0);
});
if (brandAssets.every(asset => asset.length > 0)) {
  assert(brandAssets[0].equals(brandAssets[1]) && brandAssets[0].equals(brandAssets[2]), 'All app contexts must package a byte-identical canonical Q-mark asset.');
}

const institutionThemeTokens = read('services/api/src/main/java/com/quantaedge/api/InstitutionThemeTokens.java');
assert(institutionPlatformController.includes('InstitutionThemeTokens.normalize(raw)') && institutionThemeTokens.includes('value.matches("^#[0-9A-Fa-f]{6}$")'), 'Tenant theme values must be restricted to valid six-digit hex colours.');

if (failures.length) {
  console.error('QuantaEdge cross-app UI/brand contract failed (' + failures.length + '/' + checks + ' checks):');
  for (const failure of failures) console.error('- ' + failure);
  process.exitCode = 1;
} else {
  console.log('QuantaEdge cross-app UI/brand contract passed: ' + checks + ' checks across learner web, Admin, People Operations, and official brand assets.');
}
