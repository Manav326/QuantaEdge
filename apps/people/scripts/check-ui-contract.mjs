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
  const absolute = join(root, path);
  assert(existsSync(absolute), `Required file is missing: ${path}`);
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : '';
}

const rootLayout = read('apps/people/app/layout.tsx');
const workspace = read('apps/people/app/components/PeopleWorkspaceLayout.tsx');
const sessionBoundary = read('apps/people/app/components/PeopleSessionBoundary.tsx');
const components = read('apps/people/app/components/PeopleUI.tsx');
const styles = read('apps/people/app/people-design-system.css');
const brandComponent = read('apps/people/app/components/QuantaEdgeBrand.tsx');
const adminStyles = read('apps/admin/app/globals.css');
const webStyles = read('apps/web/app/globals.css');
const alignmentPlan = read('docs/ui/hrms-educational-admin-alignment-plan.md');

assert(rootLayout.includes("import './people-design-system.css'"), 'Root layout must load the People design system.');
assert(rootLayout.includes('PeopleSessionBoundary'), 'Root layout must preserve the HRMS session boundary.');
assert(rootLayout.includes('PeopleWorkspaceLayout'), 'Root layout must use the common People workspace shell.');
assert(workspace.includes("X-QE-Workspace': 'HRMS'"), 'Workspace session requests must retain the HRMS workspace header.');
assert(sessionBoundary.includes("'HRMS'"), 'Session boundary must preserve HRMS workspace validation.');
assert(workspace.includes("pathname === '/change-password'"), 'Forced password-change route must remain outside the workspace chrome.');
assert(workspace.includes('id="main-content" tabIndex={-1}'), 'Skip link target must be programmatically focusable.');
assert(workspace.includes("import QuantaEdgeBrand from './QuantaEdgeBrand'"), 'HRMS workspace must use the shared canonical brand component.');
assert(!workspace.includes('quantaedge-wordmark.png'), 'HRMS workspace must not reference the invalid legacy wordmark PNG.');
assert(workspace.includes("href: '/access', icon: '⌁', permissions: ['HR_ACCESS_MANAGE']"), 'HRMS access management must be permission-gated in navigation.');
assert(workspace.includes("label: 'Access governance'"), 'Access management must have a distinct Access Governance navigation group.');
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

if (failures.length) {
  console.error(`People Operations UI contract failed (${failures.length}/${checks} checks):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log(`People Operations UI contract passed: ${checks} structural, design-token, route, accessibility, and asset checks.`);
}
