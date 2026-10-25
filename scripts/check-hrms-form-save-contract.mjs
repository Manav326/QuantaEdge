import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(path, 'utf8');
const people = (path) => read('apps/people/app/' + path);

const notificationHosts = [
  'apps/web/app/components/GlobalNotificationHost.tsx',
  'apps/admin/app/components/GlobalNotificationHost.tsx',
  'apps/people/app/components/GlobalNotificationHost.tsx',
].map(read);

assert.ok(
  notificationHosts.every(source => source === notificationHosts[0]),
  'Web, Admin and People must keep the shared notification host contract identical.',
);
assert.ok(notificationHosts[0].includes("isPeopleWorkspace ? '50%' : 'auto'"),
  'HRMS notices must be centred while other workspaces keep their existing alignment.');
assert.ok(notificationHosts[0].includes('max(76px, calc(env(safe-area-inset-top) + 76px))'),
  'HRMS notices must sit below the sticky workspace header.');

const workforce = people('workforce/page.tsx');
assert.ok(workforce.includes('return true;') && workforce.includes("setVacancy({title:'',description:'',openings:'1'})"),
  'Create-vacancy flow must clear its draft only after a successful API response.');
assert.ok(workforce.includes("(!candidate.email.trim()&&!candidate.phoneE164.trim())"),
  'Candidate creation must require at least one contact method.');
assert.ok(workforce.includes("setCandidate({fullName:'',email:'',phoneE164:'',vacancyId:'',notes:''})"),
  'Create-candidate flow must clear the saved draft.');
assert.ok(workforce.includes('async function createReview') && workforce.includes("setReview({cycleId:'',employeeId:'',reviewerEmployeeId:''})"),
  'Successful performance-review assignment must clear the saved draft.');
assert.ok(workforce.includes('async function assignAsset') && workforce.includes("setAssign({assetId:'',employeeId:'',condition:'GOOD',note:'',expectedReturnDate:''})"),
  'Successful asset assignment must clear the saved draft.');

const attendance = people('attendance/page.tsx');
assert.ok(attendance.includes("entriesJson:''") && attendance.includes('holidays.length===0'),
  'Holiday import must not treat an empty array as useful input.');
assert.ok(attendance.includes('approvalRuleUnchanged'),
  'Unchanged approval-policy updates must be disabled.');
assert.ok(attendance.includes("setNotice('');notifyUser(t('people.attendance.attendanceSaved"),
  'Successful attendance saves must use the shared notice and retain correction-reason protection.');

const payroll = people('payroll/page.tsx');
assert.ok(payroll.includes("setPeriodDraft({periodName:'',startDate:monthStart,endDate:monthEnd})"),
  'A saved payroll-period draft must be cleared so it cannot be submitted again unchanged.');
assert.ok(payroll.includes("setPayDraft(v=>({...v,employeeId:'',amount:'',providerReference:'',note:''}))"),
  'A successful payment reconciliation must clear its repeatable payment fields.');
assert.ok(payroll.includes("!payDraft.providerReference.trim()"),
  'A payroll payment must require its provider reference.');

const assignments = people('assignments/page.tsx');
assert.ok(assignments.includes('savedWeeklyLimit') && assignments.includes('Number(weeklyLimit)===Number(savedWeeklyLimit)'),
  'Unchanged workload-policy values must not be resubmitted.');
assert.ok(assignments.includes("setDraft({employeeId:'',subjectId:'',academicPeriodId:'',effectiveStart:'',effectiveEnd:'',replacesAssignmentId:'',assignmentNote:'',reason:''})"),
  'Successful assignment submission and edit cancellation must clear every draft field.');

const employeeDirectory = people('page.tsx');
assert.ok(employeeDirectory.includes('notifyUser(') && employeeDirectory.includes('employee_number'),
  'Employee create/edit forms must use the central success notification.');
assert.ok(employeeDirectory.includes('!editChanged') && employeeDirectory.includes('!draft.displayName.trim()'),
  'Employee forms must block meaningless submissions and unchanged edits.');
assert.ok(employeeDirectory.includes('pattern="^\\+[1-9][0-9]{6,14}$"'),
  'Optional phone fields must validate the E.164 format accepted by the backend.');

const institutions = people('institutions/page.tsx');
assert.ok(institutions.includes('const createDraftIssue=') && institutions.includes('Boolean(createDraftIssue())'),
  'Institution provisioning must block meaningless fields and explain the required correction.'); 
assert.ok(institutions.includes('const adminDraftIssue=') && institutions.includes('Boolean(adminDraftIssue())'),
  'Institution-admin provisioning must validate username, name and temporary password before submission.');
assert.ok(institutions.includes("notifyUser('Tenant branding settings saved.','success')") &&
  institutions.includes("notifyUser('Billing contract saved."),
  'Institution settings writes must use central success notifications.');
assert.ok(institutions.includes('!profileChanged') && institutions.includes('!brandChanged') &&
  institutions.includes('!billingChanged'),
  'Institution profile, branding and billing must disable unchanged saves.');
assert.ok(institutions.includes("Number(billing.unitPriceRupees)!==(Number(detail?.unit_price_paise||0)/100)"),
  'Equivalent rupee values must not be treated as a changed billing contract.');
assert.ok(institutions.includes('Course release access') &&
  institutions.includes("onClick={()=>setTab('marketplace')}") &&
  !institutions.includes("'/courses/'+course.id") &&
  !institutions.includes('saveCourseAuthorisation'),
  'Institution settings must show release-scoped access and route assignment changes through Marketplace launch instead of the retired program-level entitlement route.');
const qa = people('qa/page.tsx');
assert.ok(qa.includes('idempotencyKey.trim()') && qa.includes('people.qa.installSuccess'),
  'QA installs must keep their server-backed idempotency key and explicit install outcome.');
assert.ok(qa.includes("notifyUser(t('people.qa.installSuccess'),'success')") &&
  qa.includes("notifyUser(t('people.qa.baselinePassed'),'success')"),
  'QA lifecycle success must use the central HRMS notification host.');

const selfService = people('self-service/page.tsx');
assert.ok(selfService.includes("setDraft(v=>({...v,startDate:'',endDate:'',reason:''}))"),
  'A successful self-service leave request must clear the repeatable date/reason draft.');
assert.ok(selfService.includes("setProfileDraft({email:'',phoneE164:'',reason:''})"),
  'A submitted profile-change request must not remain ready for duplicate submission.');

const access = people('access/page.tsx');
assert.ok(access.includes('Read-only access summary') && access.includes('setEditingRoles(true)'),
  'Access roles and permissions must be read-only until an explicit edit action.');
assert.ok(access.includes('!hasRoleChanges') && access.includes('setEditingRoles(false)'),
  'Unchanged role assignments must not be saved repeatedly.');
assert.ok(access.includes('setEditingRoles(!row.staff_id);'),
  'Disabled HRMS access must not open an editor full of disabled role controls.');

const finance = people('finance/page.tsx');
assert.ok(finance.includes("notifyUser(success,'success')"),
  'Successful finance actions must use the shared notification host.');
assert.ok(finance.includes('editingEntityId===e.id'),
  'Legal-entity settings must be compact and read-only until explicitly edited.');
assert.ok(finance.includes('Tax profile draft #') && finance.includes('Discount draft '),
  'Two-step finance actions must preserve partial drafts and guide users to retry submission instead of recreating records.');
assert.ok(finance.includes('recipientsUnchanged') && finance.includes('disabled={busy||recipientsUnchanged}'),
  'Unchanged revenue-share recipients must not be submitted repeatedly.');
assert.ok(finance.includes('designUnchanged') && finance.includes('disabled={busy||designUnchanged}'),
  'An unchanged invoice design must not be saved repeatedly.');

const studio = people('components/NotificationStudio.tsx');
const studioCss = people('components/NotificationStudio.module.css');
assert.ok(studio.includes('!templateDirty') && studio.includes('Audience & preview') && studio.includes('Safe delivery checklist'),
  'Notification templates must track unsaved changes and show meaningful right-rail status.');
assert.ok(studio.includes("recipientTypes.length === 0"),
  'Notification preview must require at least one recipient type.');
assert.ok(studioCss.includes('align-items:stretch') && studioCss.includes('max-height:calc(100dvh - 32px)') &&
  studioCss.includes('overflow-y:auto') && studioCss.includes('height:calc(100dvh - 32px)'),
  'Notification studio sidebar must stretch with the editor and remain scrollable while sticky.');

console.log('HRMS form lifecycle, duplicate-save prevention, view-first cards and notification layout contract passed.');
