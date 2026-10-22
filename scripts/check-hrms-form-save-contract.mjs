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
assert.ok(assignments.includes('savedWeeklyLimit') && assignments.includes('weeklyLimit===savedWeeklyLimit'),
  'Unchanged workload-policy values must not be resubmitted.');
assert.ok(assignments.includes("setDraft(v=>({...v,employeeId:'',subjectId:'',replacesAssignmentId:'',assignmentNote:'',reason:''}))"),
  'Successful assignment submission must clear the create/edit draft.');

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
  studioCss.includes('overflow-y:auto'),
  'Notification studio sidebar must stretch with the editor and remain scrollable while sticky.');

console.log('HRMS form lifecycle, duplicate-save prevention, view-first cards and notification layout contract passed.');
