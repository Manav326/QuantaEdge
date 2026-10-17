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
const adminBrandComponent = read('apps/admin/app/components/QuantaEdgeBrand.tsx');
const peopleBrandComponent = read('apps/people/app/components/QuantaEdgeBrand.tsx');
const adminSidebar = read('apps/admin/app/components/AdminSidebar.tsx');
const adminLogin = read('apps/admin/app/login/page.tsx');
const adminWorkspaceLayout = read('apps/admin/app/components/AdminWorkspaceLayout.tsx');
const peopleWorkspace = read('apps/people/app/components/PeopleWorkspaceLayout.tsx');
const peopleLogin = read('apps/people/app/login/page.tsx');
const peopleChangePassword = read('apps/people/app/change-password/page.tsx');
const webLayout = read('apps/web/app/layout.tsx');
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
assert(!tenantBrandingProvider.includes('--qe-brand-orange'), 'Tenant identity resolution must leave palette variables to the shared theme system.');
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

if (failures.length) {
  console.error('QuantaEdge cross-app UI/brand contract failed (' + failures.length + '/' + checks + ' checks):');
  for (const failure of failures) console.error('- ' + failure);
  process.exitCode = 1;
} else {
  console.log('QuantaEdge cross-app UI/brand contract passed: ' + checks + ' checks across learner web, Admin, People Operations, and official brand assets.');
}
), 'Tenant theme values must be restricted to valid six-digit hex colours.');
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
assert(webLayout.includes('/branding/quantaedge-icon.png'), 'Learner web must configure the canonical browser/app icon.');
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

if (failures.length) {
  console.error('QuantaEdge cross-app UI/brand contract failed (' + failures.length + '/' + checks + ' checks):');
  for (const failure of failures) console.error('- ' + failure);
  process.exitCode = 1;
} else {
  console.log('QuantaEdge cross-app UI/brand contract passed: ' + checks + ' checks across learner web, Admin, People Operations, and official brand assets.');
}
