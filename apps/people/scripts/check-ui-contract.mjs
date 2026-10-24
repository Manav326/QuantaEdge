import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const failures = [];
let checks = 0;

function assert(condition, message) {
  checks += 1;
  if (!condition) failures.push(message);
}

function read(path) {
  const absolute = join(root, path);
  assert(existsSync(absolute), `Required file is missing: ${path}`);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
}

function findPeoplePageFiles(directory) {
  const absolute = join(root, directory);
  if (!existsSync(absolute)) return [];
  return readdirSync(absolute, { withFileTypes: true }).flatMap(entry => {
    const childPath = directory + '/' + entry.name;
    if (entry.isDirectory()) return findPeoplePageFiles(childPath);
    return /^page\.(?:tsx|ts|jsx|js)$/.test(entry.name) ? [childPath] : [];
  });
}

const rootLayout = read('apps/people/app/layout.tsx');
const appearanceProvider = read('apps/people/app/components/PeopleAppearanceProvider.tsx');
const appearanceStyles = read('apps/people/app/people-appearance.css');
const workspace = read('apps/people/app/components/PeopleWorkspaceLayout.tsx');
const sessionBoundary = read('apps/people/app/components/PeopleSessionBoundary.tsx');
const components = read('apps/people/app/components/PeopleUI.tsx');
const styles = read('apps/people/app/people-design-system.css');
const brandComponent = read('apps/people/app/components/QuantaEdgeBrand.tsx');
const adminStyles = read('apps/admin/app/globals.css');
const webStyles = read('apps/web/app/globals.css');
const alignmentPlan = read('docs/ui/hrms-educational-admin-alignment-plan.md');

assert(rootLayout.includes("import './people-design-system.css'"), 'Root layout must load the People design system.');
assert(rootLayout.includes("import './people-appearance.css'") && rootLayout.includes('PeopleAppearanceProvider'), 'Root layout must apply HRMS-owned appearance tokens globally across protected and public routes.');
assert(rootLayout.includes("default: 'QuantaEdge'") && rootLayout.includes("applicationName: 'QuantaEdge'"), 'The HRMS platform identity must remain QuantaEdge while the HRMS product name is finalised.');
assert(appearanceProvider.includes("qe.people.appearance-mode.v1") && appearanceProvider.includes("'system', 'light', 'dark'"), 'HRMS must keep its own persisted system/light/dark preference, separate from learner/Admin preferences.');
assert(appearanceProvider.includes('PeopleAppearanceToggle') && appearanceProvider.includes('prefers-color-scheme'), 'HRMS appearance controls must support accessible mode selection and OS theme changes.');
assert(!/\/api\/v1\/institution\/public\/branding|logoUrl|appIconUrl|themeTokens/i.test(appearanceProvider + appearanceStyles), 'HRMS appearance must not fetch or depend on tenant logo URLs or institution theme tokens.');
assert(appearanceStyles.includes('--qe-people-brand: #f97316') && appearanceStyles.includes('--qe-people-brand-strong: #c2410c'), 'HRMS light theme must preserve canonical QuantaEdge orange branding.');
assert(appearanceStyles.includes('html[data-qe-people-appearance="dark"]') && appearanceStyles.includes(':focus-visible') && appearanceStyles.includes('prefers-reduced-motion'), 'HRMS dark theme must retain semantic surfaces, visible focus, and reduced-motion support.');
assert(sessionBoundary.includes('Do not replace the HRMS workspace with a full-screen gate on client-side') && !sessionBoundary.includes('setReady(false)'), 'HRMS session checks must not unmount the whole workspace on every route change.');
assert(workspace.includes('key={pathname}') && !workspace.includes('router.refresh()'), 'HRMS navigation and logout must keep the shared shell mounted and avoid unnecessary route refreshes.');
assert(appearanceStyles.includes('qe-people-route-enter') && appearanceStyles.includes('prefers-reduced-motion: reduce'), 'HRMS route content transitions must be subtle and respect reduced-motion preferences.');
assert(appearanceStyles.includes('.qe-people-navigation-icon') && appearanceStyles.includes('background: transparent !important'), 'HRMS navigation glyphs must not be surrounded by unnecessary icon tiles.');

assert(appearanceStyles.includes('[class*="Finance_page__"]') && appearanceStyles.includes('[class*="Institutions_page__"]') && appearanceStyles.includes('[class*="QaCenter_page__"]'), 'Bespoke finance, institution-management, and QA routes must remap their local theme tokens.');
assert(workspace.includes('<PeopleAppearanceToggle />'), 'Authenticated workspace header must expose the shared appearance control.');

assert(rootLayout.includes('PeopleSessionBoundary'), 'Root layout must preserve the HRMS session boundary.');
assert(rootLayout.includes('PeopleWorkspaceLayout'), 'Root layout must use the common People workspace shell.');
assert(workspace.includes("X-QE-Workspace': 'HRMS'"), 'Workspace session requests must retain the HRMS workspace header.');
assert(sessionBoundary.includes("'HRMS'"), 'Session boundary must preserve HRMS workspace validation.');
assert(workspace.includes("pathname === '/change-password'"), 'Forced password-change route must remain outside the workspace chrome.');
assert(workspace.includes('id="main-content" tabIndex={-1}'), 'Skip link target must be programmatically focusable.');
assert(workspace.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'HRMS workspace must use the shared canonical brand component.');
assert(workspace.includes('className="qe-people-mobile-brand-lockup"'), 'HRMS mobile workspace header must use the canonical brand lockup.');
assert(!workspace.includes('<span>QUANTAEDGE</span>'), 'HRMS mobile workspace must not hard-code a duplicate brand name.');
assert(!workspace.includes('quantaedge-wordmark.png'), 'HRMS workspace must not reference the invalid legacy wordmark PNG.');
assert(workspace.includes("href: '/access', icon: '⌁', permissions: ['HR_ACCESS_MANAGE']"), 'HRMS access management must be permission-gated in navigation.');
assert(workspace.includes("message: 'people.navigation.group.access'"), 'Access management must have a distinct centralized Access Governance navigation group.');
assert(workspace.includes("permissions: ['HR_ACADEMIC_ASSIGNMENT_VIEW', 'HR_ACADEMIC_ASSIGNMENT_MANAGE', 'HR_ACADEMIC_ASSIGNMENT_APPROVE']"), 'Teaching-assignment navigation must match the page authorization contract.');

assert(workspace.includes("'TEACHING_ASSIGNMENT_VIEW'"), 'Teaching classes/assignment navigation must honor TEACHING_ASSIGNMENT_VIEW.');
assert(!workspace.includes('<main className="qe-people-page-slot"'), 'Workspace must not nest a main landmark around page mains.');

for (const exportName of [
  'PeoplePageHeader',
  'PeopleMetricCard',
  'PeopleStatusBadge',
  'PeopleFeedback',
  'PeopleButton',
  'PeopleEmptyState',
  'PeopleDialog',
]) {
  assert(components.includes(`export function ${exportName}`), `Missing shared People UI primitive: ${exportName}`);
}

assert(components.includes("'primary' | 'secondary' | 'neutral' | 'danger'"), 'Shared People buttons must include the semantic variants.');
assert(components.includes('loading = false') && components.includes('disabled={disabled || loading}'), 'Shared People buttons must expose safe loading/disabled behavior.');
assert(styles.includes('.qe-people-button--neutral') && styles.includes('.qe-people-button-spinner'), 'Shared People button variants and loading indicator styles must exist.');

const routePages = [
  ['/', 'apps/people/app/page.tsx'],
  ['/attendance', 'apps/people/app/attendance/page.tsx'],
  ['/payroll', 'apps/people/app/payroll/page.tsx'],
  ['/workforce', 'apps/people/app/workforce/page.tsx'],
  ['/self-service', 'apps/people/app/self-service/page.tsx'],
  ['/assignments', 'apps/people/app/assignments/page.tsx'],
  ['/my-classes', 'apps/people/app/my-classes/page.tsx'],
  ['/access', 'apps/people/app/access/page.tsx'],
];

const peopleLoginSource = read('apps/people/app/login/page.tsx');
const peoplePasswordSource = read('apps/people/app/change-password/page.tsx');
assert(peopleLoginSource.includes('<PeopleAppearanceToggle compact />') && peoplePasswordSource.includes('<PeopleAppearanceToggle compact />'), 'Login and forced password-change screens must expose the same HRMS-owned theme control.');

const discoveredPeoplePages = findPeoplePageFiles('apps/people/app').sort();
assert(discoveredPeoplePages.length > routePages.length, 'Route discovery must cover routes beyond the representative workflow sample.');
assert(discoveredPeoplePages.includes('apps/people/app/change-password/page.tsx') && discoveredPeoplePages.includes('apps/people/app/institutions/page.tsx') && discoveredPeoplePages.includes('apps/people/app/finance/page.tsx') && discoveredPeoplePages.includes('apps/people/app/qa/page.tsx'), 'Route discovery must include public account and bespoke platform-management screens.');
assert(discoveredPeoplePages.includes('apps/people/app/page.tsx') && discoveredPeoplePages.includes('apps/people/app/login/page.tsx'), 'Route discovery must include the HRMS workspace and public login.');
for (const routePath of discoveredPeoplePages) {
  const pageSource = read(routePath);
  assert(/\bexport\s+default\b/.test(pageSource), 'HRMS route ' + routePath.replace(/^apps\/people\/app/, '') + ' must retain its default route component.');
}

const attendanceSource = read('apps/people/app/attendance/page.tsx');
assert(attendanceSource.includes('<PeopleStatusBadge status={pretty(r.requested_status)} />'), 'Attendance-correction requested status must use a semantic status badge.');
assert(!attendanceSource.includes("{r.paid?'Yes':'No'}"), 'Leave paid/unpaid status must not remain an unstyled Yes/No value.');
assert(!attendanceSource.includes('<div className="small">Inactive</div>'), 'Inactive holiday state must use the semantic status badge.');

const payrollSource = read('apps/people/app/payroll/page.tsx');
assert(payrollSource.includes("<PeopleStatusBadge status={c.active?'Active':'Inactive'} />"), 'Salary component active status must use the semantic badge.');

const selfServiceSource = read('apps/people/app/self-service/page.tsx');
assert(selfServiceSource.includes("<PeopleStatusBadge status={item.paid?'Paid':'Unpaid'} />"), 'Self-service leave/balance paid status must use the semantic badge.');
assert(!selfServiceSource.includes("<td>{item.paid?'Yes':'No'}</td>"), 'Self-service paid status must not remain an unstyled Yes/No value.');

const assignmentsSource = read('apps/people/app/assignments/page.tsx');
assert(assignmentsSource.includes("tone={Number(x.approved_assignment_count)>0?'success':'danger'}"), 'Class and subject coverage must visibly distinguish covered and missing coverage.');

const accessRouteSource = read('apps/people/app/access/page.tsx');
assert(!selfServiceSource.includes('replace(/\\\\b\\\\w/g'), 'Self-service status formatter must not double-escape regex word boundaries.');
assert(!accessRouteSource.includes('replace(/\\\\b\\\\w/g'), 'Access governance status formatter must not double-escape regex word boundaries.');
assert(accessRouteSource.includes('HR_ACCESS_MANAGE'), 'Access governance page must retain its permission check.');
assert(accessRouteSource.includes('<PeopleButton variant='), 'Access governance actions must use shared People buttons.');

for (const [route, path] of routePages) {
  const source = read(path);
  assert((source.match(/<PeoplePageHeader\b/g) || []).length === 1, `${route} must use exactly one shared page header.`);
  assert(source.includes('<PeopleStatusBadge'), `${route} must use semantic status badges.`);
  assert(source.includes('<PeopleFeedback'), `${route} must use shared feedback presentation.`);
  assert(source.includes('<PeopleEmptyState'), `${route} must use shared empty-state presentation.`);
  assert(!source.includes('className="topbar"'), `${route} must not recreate the legacy route-level navigation header.`);
}

assert(styles.includes('--brand: #f97316;') && styles.includes('--brand2: #c2410c;'), 'People theme must retain the canonical QuantaEdge orange palette.');
for (const token of [
  '--qe-font-body',
  '--qe-space-1',
  '--qe-radius-control',
  '--qe-shadow-card',
  '--qe-control-height',
  '--qe-button-height',
]) {
  assert(styles.includes(token), `Missing design token ${token}.`);
}
assert(styles.includes('@media (max-width: 900px)'), 'Tablet/mobile workspace breakpoint must be retained.');
assert(styles.includes('@media (max-width: 600px)'), 'Small-screen layout breakpoint must be retained.');
assert(styles.includes('prefers-reduced-motion: reduce'), 'Reduced-motion styles must be retained.');
assert(styles.includes('forced-colors: active'), 'Forced-colors accessibility styles must be retained.');
assert(styles.includes('body.printing-payslip'), 'Payslip print styling must be retained.');

for (const asset of [
  'apps/people/public/branding/quantaedge-wordmark.png',
  'apps/people/public/branding/quantaedge-icon.png',
]) {
  const absolute = join(root, asset);
  assert(existsSync(absolute) && statSync(absolute).size > 0, `Missing packaged QuantaEdge asset: ${asset}`);
}

for (const [route, path] of [
  ['/login', 'apps/people/app/login/page.tsx'],
  ['/change-password', 'apps/people/app/change-password/page.tsx'],
]) {
  const source = read(path);
  assert(source.includes('<QuantaEdgeBrand href={null} variant="auth"'), `${route} must render the canonical shared QuantaEdge brand lockup.`);
  assert(!source.includes('quantaedge-wordmark.png'), `${route} must not use the invalid legacy wordmark PNG.`);
  assert(source.includes('<PeopleButton variant="primary"'), `${route} must use the shared primary button.`);
  assert(source.includes('<PeopleFeedback tone="danger" role="alert">'), `${route} must use accessible shared error feedback.`);
}
assert(brandComponent.includes('Smarter Decisions. Greater Growth.'), 'People Operations must use the unchanged official brand tagline.');
assert(styles.includes('.qe-people-login-brand-lockup .qe-brand__icon'), 'Login brand lockup must size the canonical Q mark.');
assert(styles.includes('.qe-people-login-brand-lockup .qe-brand__tagline'), 'Login brand lockup must keep the official tagline readable.');
assert(styles.includes('.login-card .qe-people-button'), 'Authentication primary actions must retain adequate button sizing.');


// Route-wide responsive polish and accessible state presentation.
assert(adminStyles.includes('--qe-brand-orange:#f97316') && adminStyles.includes('--qe-brand-orange-deep:#c2410c'), 'Admin must retain the canonical QuantaEdge orange tokens.');
assert(webStyles.includes('--qe-brand-orange:#f97316') && webStyles.includes('--qe-brand-orange-deep:#c2410c'), 'Learner web must retain the canonical QuantaEdge orange tokens.');
assert(!styles.toLowerCase().includes('#244c9a') && !styles.toLowerCase().includes('#10264b'), 'HRMS must not regress to its former standalone blue palette.');
assert(styles.includes('--brand: #f97316;') && styles.includes('--brand2: #c2410c;'), 'HRMS primary and deep accents must match QuantaEdge orange.');
assert(styles.includes('--brand-soft: #fff0e5;'), 'HRMS soft accent must use the approved light-orange surface.');
assert(styles.includes('background: #fffaf6;'), 'Data-table hover states must stay within the approved warm-neutral palette.');
assert(styles.includes('input[aria-invalid="true"]') && styles.includes('textarea[aria-invalid="true"]'), 'Invalid form controls must have a visible semantic error border.');
assert(styles.includes('font-variant-numeric: tabular-nums;'), 'Numeric HR/payroll values must align for scanning.');
assert(styles.includes('overscroll-behavior-inline: contain;'), 'Scrollable data tables must preserve horizontal touch-scroll behavior.');
assert(styles.includes('tbody tr:focus-within'), 'Keyboard focus within data rows must remain visually discoverable.');
assert(styles.includes('qe-people-page-actions > *'), 'Responsive page actions must have a tablet layout rule.');
assert(styles.includes('.qe-people-page-slot .qe-people-pagination'), 'Pagination must have a responsive shared layout.');

assert(alignmentPlan.includes('Phase 10'), 'Persistent HRMS alignment plan must be present.');


/* Full-route theme coverage and authored document preview boundaries. */
function findFilesMatching(directory, predicate) {
  const absolute = join(root, directory);
  if (!existsSync(absolute)) return [];
  return readdirSync(absolute, { withFileTypes: true }).flatMap(entry => {
    const childPath = directory + '/' + entry.name;
    if (entry.isDirectory()) return findFilesMatching(childPath, predicate);
    return predicate(childPath) ? [childPath] : [];
  });
}
const discoveredPeopleModules = findFilesMatching('apps/people/app', path => /\.module\.css$/.test(path)).sort();
assert(discoveredPeoplePages.length >= 14, 'HRMS route inventory must cover the full set of account, workforce, finance, notification, institution and QA pages.');
assert(discoveredPeopleModules.length >= 6, 'Theme audit must discover every route/component CSS module, including Finance and rich notifications.');
for (const modulePath of discoveredPeopleModules) {
  const moduleSource = read(modulePath);
  assert(moduleSource.length > 0, 'Discovered HRMS component stylesheet cannot be empty: ' + modulePath);
}
assert(appearanceStyles.includes(':not([data-qe-invoice-document] *)') && appearanceStyles.includes(':not([data-qe-authored-notification] *)'), 'Global HRMS appearance sweeps must stop before invoice and authored-notification content.');
assert(styles.includes(':not([data-qe-invoice-document] *)') && styles.includes(':not([data-qe-authored-notification] *)'), 'The shared design system must not recolour invoice or authored-notification descendants.');
const globalsTheme = read('apps/people/app/globals.css');
assert(globalsTheme.includes('[data-qe-invoice-document] table{background:transparent!important;min-width:0!important;}'), 'Legacy global table styling must not impose an app-surface background or minimum width on invoice paper.');
const financePageTheme = read('apps/people/app/finance/page.tsx');
const financeModuleTheme = read('apps/people/app/finance/Finance.module.css');
assert(financePageTheme.includes("['overview','Overview'") && financePageTheme.includes("['documents','Issued documents'") && financePageTheme.includes("['studio','Invoice Design Studio'"), 'Finance route must retain its in-page billing, document and design subpages.');
const marketplaceLaunchFinance = read('apps/people/app/institutions/MarketplaceLaunchStepper.tsx');
assert(marketplaceLaunchFinance.includes('Trusted course updates') && marketplaceLaunchFinance.includes('trusted_auto_approval') && marketplaceLaunchFinance.includes('course-identities/') && marketplaceLaunchFinance.includes('Audit reason (required for every change)'), 'HRMS must expose an audited trusted-course control, and only HRMS can change the policy.');
assert(marketplaceLaunchFinance.includes('destination ownership, prices, tax, invoices and learner history remain protected') && marketplaceLaunchFinance.includes('Previously HRMS-published'), 'Trusted-course UI must explain the initial-publication gate and must not imply commercial/access checks are bypassed.');
assert(marketplaceLaunchFinance.includes('financeSetupHref(item, invoiceSetupTarget(item))') && marketplaceLaunchFinance.includes("setup: 'marketplace'") && marketplaceLaunchFinance.includes("params.set('offerId', String(item.offerId))"), 'Marketplace invoice/revenue-share actions must deep-link to Finance with the exact course offer context.');
assert(marketplaceLaunchFinance.includes("href=\"/institutions#hrms-integrations-heading\"") && marketplaceLaunchFinance.includes("blockerHref(item, blocker)") && marketplaceLaunchFinance.includes("SOURCE_RIGHTS_CLEARANCE', 'ORIGIN_RELEASE_APPROVAL"), 'Launch blockers must route to shared integrations, source rights, or the right release owner rather than generic Finance.');
assert(marketplaceLaunchFinance.includes("href={financeSetupHref(item, sellerSetupTarget(item))}") && marketplaceLaunchFinance.includes("financeSetupHref(item, 'entities')") && marketplaceLaunchFinance.includes("financeSetupHref(item, 'tax')"), 'Seller/tax links must preserve the exact course offer context.');
const institutionCompatibilityRoute = read('apps/web/app/institution/page.tsx');
const institutionAdminWorkspace = read('apps/admin/app/institution-operations/page.tsx');
const guidedCourseCreator = read('apps/admin/app/content/create/page.tsx');
const hrmsCourseHandoff = read('apps/people/app/institutions/MarketplaceLaunchStepper.tsx');
assert(institutionCompatibilityRoute.includes('InstitutionRouteRedirect') && institutionCompatibilityRoute.includes('NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN') && !institutionCompatibilityRoute.includes('Submit for HRMS review'), 'The learner-facing web app must redirect the retired institution-management page into Admin instead of hosting a third workspace.');
const institutionAdminEnglishCatalog = read('apps/admin/app/i18n/catalogs/english.json');
const institutionAdminHinglishCatalog = read('apps/admin/app/i18n/catalogs/hinglish.json');
assert(institutionAdminWorkspace.includes('/api/v1/admin/institution-operations/course-releases')
  && institutionAdminWorkspace.includes('/api/v1/admin/institution-operations/courses/')
  && institutionAdminWorkspace.includes("t('admin.institutionOperations.approveForSharing')")
  && institutionAdminWorkspace.includes("t('admin.institutionOperations.rosterTitle')")
  && institutionAdminEnglishCatalog.includes('"admin.institutionOperations.approveForSharing": "Approve for sharing"')
  && institutionAdminEnglishCatalog.includes('"admin.institutionOperations.rosterTitle": "Institution learner roster"')
  && institutionAdminHinglishCatalog.includes('"admin.institutionOperations.approveForSharing": "Sharing approve karein"')
  && institutionAdminHinglishCatalog.includes('"admin.institutionOperations.rosterTitle": "Institution learner roster"'),
  'Institution reports, roster and release handoff must be implemented in Admin with shared localized labels.');
assert(guidedCourseCreator.includes("'/api/v1/admin/content/courses'") && guidedCourseCreator.includes("router.push('/content?courseId='"), 'Guided course creation must open Content Studio in the newly created course context.');
assert(hrmsCourseHandoff.includes('NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN') && hrmsCourseHandoff.includes("'/institution-operations?'"), 'HRMS release blockers must hand the authorised actor into the Admin institution workflow, never the learner portal.');
const financeSetupContract = read('apps/people/app/finance/page.tsx');
assert(financeSetupContract.includes("setTaxForm(v=>({...v,offerId:requestedOfferId,legalEntityId:linkedSeller?String(linkedSeller.id):''}))") && financeSetupContract.includes("setDiscountForm(v=>({...v,offerId:requestedOfferId}))"), 'Launch-linked Finance forms must use the exact selected offer instead of a first-catalogue default.');
assert(financeSetupContract.includes('Legal seller &amp; GST') && financeSetupContract.includes('Offer tax profile') && financeSetupContract.includes('Discount rules · optional'), 'Guided finance setup must follow the same sequence and keep unplanned discounts optional.');
assert(financePageTheme.includes('marketplaceSetupContext&&<section className={styles.marketplaceSetupGuide}') && financePageTheme.includes("onClick={()=>setTab('distribution')}") && financeModuleTheme.includes('.marketplaceSetupSteps{'), 'Finance must provide a visible guided path between invoice templates, revenue shares and discount validation.');
for (const mode of ['light', 'dark']) {
  assert(financePageTheme.includes('data-preview-theme="' + mode + '"'), 'Finance invoice design must preview in the ' + mode + ' workspace context.');
  assert(financeModuleTheme.includes('.invoiceThemeFrame[data-preview-theme="' + mode + '"]'), 'Invoice comparison must style the ' + mode + ' preview frame explicitly.');
}
assert(financePageTheme.includes('data-qe-invoice-document="true"') && financePageTheme.includes('body:JSON.stringify({designDefinition:design})'), 'Invoice stationery must stay separately authored and PDF rendering must use the selected design definition.');
assert(financeModuleTheme.includes('--finance-field: var(--qe-people-field') && financeModuleTheme.includes('.table:not(.paperTable)'), 'Finance controls and ledgers must follow HRMS theme tokens without recolouring invoice tables.');
const notificationPageTheme = read('apps/people/app/components/NotificationStudio.tsx');
const notificationModuleTheme = read('apps/people/app/components/NotificationStudio.module.css');
const rendererTheme = read('apps/people/app/components/RichNotificationRenderer.tsx');
const rendererCssTheme = read('apps/people/app/components/RichNotificationRenderer.module.css');
assert(notificationPageTheme.includes('See it in both appearances') && notificationPageTheme.includes('data-preview-theme="light"') && notificationPageTheme.includes('data-preview-theme="dark"'), 'Notification editor must include side-by-side recipient previews in light and dark appearances.');
assert(notificationPageTheme.includes('<RichNotificationRenderer surface={surface} contentDocument={document} />'), 'Theme previews must render the real notification renderer in read-only mode.');
assert(notificationModuleTheme.includes('themePreviewCard[data-preview-theme="light"]') && notificationModuleTheme.includes('themePreviewCard[data-preview-theme="dark"]'), 'Notification preview frames must use independently defined premium appearance palettes.');
assert(rendererTheme.includes('data-qe-authored-notification="true"') && rendererTheme.includes('--qe-authored-text-color') && rendererTheme.includes('contrastForeground(accent)'), 'Notification document colours must remain authored and CTA foreground contrast must be selected against accent luminance.');
assert(rendererCssTheme.includes('--qe-authored-background-color') && rendererCssTheme.includes('var(--qe-notification-on-accent,#fff)'), 'Notification block backgrounds and button foreground contrast must survive theme changes.');
if (failures.length) {
  console.error(`People Operations UI contract failed (${failures.length}/${checks} checks):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log(`People Operations UI contract passed: ${checks} structural, design-token, route, accessibility, and asset checks.`);
}
