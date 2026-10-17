import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const root = process.cwd();
const files = [
  'apps/web/app/parent/children/page.tsx',
  'apps/web/app/parent/profile/page.tsx',
  'apps/web/app/login/student/page.tsx',
  'apps/web/app/login/page.tsx',
];
const forbidden = [
  [/\btx\s*\(/, 'inline language-pair tx() helper/call'],
  [/function\s+tx\s*\(/, 'page-level tx helper'],
  [/setLocale\(\s*['"]hindi['"]\s*\)/, 'legacy UI locale value that is not a catalog ID'],
  [/translateUi\(locale\s*===\s*['"]english['"]\s*\?\s*['"]english['"]\s*:\s*['"]hinglish['"]/, 'forced two-locale mapping that blocks future locales'],
];
for (const file of files) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  for (const [pattern, label] of forbidden) {
    assert.equal(pattern.test(source), false, file + ': found ' + label);
  }
}
const english = JSON.parse(fs.readFileSync(path.join(root, 'localization/catalogs/english.json'), 'utf8'));
const hinglish = JSON.parse(fs.readFileSync(path.join(root, 'localization/catalogs/hinglish.json'), 'utf8'));
const required = [
  'parent.children.created', 'parent.children.createdRefreshFailed', 'parent.children.profileSaved',
  'parent.children.credentialsSaved', 'parent.children.courseAssigned', 'parent.children.tracksSaved',
  'parent.children.restoreConfirm', 'parent.children.restored', 'parent.children.archiveConfirm',
  'parent.children.archived', 'parent.login.unexpectedResponse', 'parent.login.passwordNotSet', 'parent.login.invalidCredentials', 'parent.login.sessionNotConfirmed', 'parent.login.signInFailed', 'parent.login.enterName', 'parent.login.passwordMinimum', 'parent.login.passwordMismatch', 'parent.login.otpSentSignup', 'parent.login.otpSentReset', 'parent.login.otpSentLogin', 'parent.login.sendVerificationFailed', 'parent.login.invalidOtp', 'parent.login.newPasswordMismatch', 'parent.login.verificationFailed', 'parent.login.signingIn', 'parent.login.signIn', 'parent.login.sendingLoginOtp', 'parent.login.verifying', 'parent.login.sendLoginOtp', 'parent.login.verifyOtpAndSignIn', 'parent.login.sendingOtp', 'parent.login.creatingAccount', 'parent.login.verifyMobile', 'parent.login.createAccount', 'parent.login.resettingPassword', 'parent.login.sendResetOtp', 'parent.login.resetPassword', 'parent.login.fullNamePlaceholder', 'parent.login.mobilePlaceholder', 'parent.login.passwordPlaceholder', 'parent.login.passwordMinimumPlaceholder', 'parent.login.confirmPasswordPlaceholder', 'parent.login.otpPlaceholder', 'tutor.error.startSession', 'tutor.error.startSessionConnection', 'tutor.error.loadHistory', 'tutor.error.loadOlder', 'tutor.error.loadOlderConnection', 'tutor.error.startNewChat', 'tutor.error.startNewChatConnection', 'tutor.error.answerFailed', 'tutor.error.reachTutor', 'parent.profile.photoSaved', 'parent.profile.saved',
  'parent.profile.photoRemoved', 'student.login.detailsMismatch', 'student.login.sessionNotConfirmed',
  'student.login.signInFailed', 'student.login.parentMobilePlaceholder', 'student.login.usernamePlaceholder',
  'student.login.passwordPlaceholder',
  'common.accountMenu.openOptions', 'common.accountMenu.options', 'common.accountMenu.openParentMenu', 'common.accountMenu.parentAccount', 'common.accountMenu.parentOptions', 'common.accountMenu.openStudentSettings', 'common.accountMenu.studentOptions', 'common.accountMenu.loggingOut', 'common.accountMenu.logOut', 'common.navigation.closeMenu', 'common.navigation.adminLabel', 'common.navigation.primaryWorkspace', 'common.pagination.navigationForItems', 'common.pagination.noItems', 'common.pagination.showingRange', 'common.pagination.singleItem', 'common.pagination.itemCount', 'common.pagination.rowsPerPage', 'common.pagination.previousPage', 'common.pagination.previous', 'common.pagination.pageNumber', 'common.pagination.nextPage', 'common.pagination.next', 'common.pagination.tableNavigation', 'common.pagination.pageOf',
  'admin.structuredPreview.answerChecked', 'admin.structuredPreview.shortAnswerNeedsReview', 'admin.structuredPreview.gradingAdapterNeeded', 'admin.structuredPreview.submitEndpointUnavailable', 'admin.structuredPreview.answerSubmitFailed', 'admin.structuredPreview.lessonUnavailable', 'admin.structuredPreview.payloadNotSanitized', 'admin.structuredPreview.publicationGatePending', 'admin.structuredPreview.explainPracticeEyebrow', 'admin.structuredPreview.practiceTitle', 'admin.structuredPreview.practiceInstructions', 'admin.structuredPreview.visualQuestionsPending', 'admin.structuredPreview.noApprovedQuestions', 'admin.structuredPreview.questionProgress', 'admin.structuredPreview.question', 'admin.structuredPreview.tryAgain', 'admin.structuredPreview.previousQuestion', 'admin.structuredPreview.nextQuestion', 'student.learn.missingTeachingBlocks', 'student.learn.missingQuestions',
];
required.push('admin.contentStudio.questionsMustJsonArray', 'admin.contentStudio.advancedQuestionDataLoaded', 'admin.contentStudio.invalidQuestionJson', 'admin.contentStudio.atLeastOneTeachingBlock', 'admin.contentStudio.assessmentQuestionsMustList', 'admin.contentStudio.lessonSavedForReview', 'admin.contentStudio.publishRequestComplete', 'admin.contentStudio.changesSavedAsDraft', 'admin.contentStudio.lessonAndBlocksSaved', 'admin.contentStudio.lessonSaveFailed', 'admin.contentStudio.contentLibraryLoadFailed', 'admin.contentStudio.staffIdentityUnverified', 'admin.contentStudio.submitDraftBeforeQuestionReview', 'admin.contentStudio.authorFeedbackPrompt', 'admin.contentStudio.authorFeedbackRequired', 'admin.contentStudio.questionApproved', 'admin.contentStudio.questionReturned', 'admin.contentStudio.questionReviewFailed', 'admin.contentStudio.maxTeachingBlocksReached', 'admin.contentStudio.wholePagePreviewContext', 'admin.contentStudio.visualSourceMismatch', 'admin.contentStudio.candidateAlreadyAttached', 'admin.contentStudio.candidateNoImage', 'admin.contentStudio.visualAddedSourceOverlap', 'admin.contentStudio.visualAddedSuggestedPlacement', 'admin.contentStudio.unsavedChangesConfirm', 'admin.contentStudio.previewVersionMissing', 'admin.contentStudio.previewConfirmed', 'admin.contentStudio.previewConfirmFailed', 'admin.contentStudio.openContentFailed', 'admin.contentStudio.draftCreated', 'admin.contentStudio.createFailed', 'admin.contentStudio.curriculumVerified', 'admin.contentStudio.curriculumVerifyFailed', 'admin.contentStudio.textbookMappingVerified', 'admin.contentStudio.textbookVerifyFailed', 'admin.contentStudio.chapterPublished', 'admin.contentStudio.chapterUnpublished', 'admin.contentStudio.chapterPublicationFailed', 'admin.contentStudio.microTopicPublished', 'admin.contentStudio.microTopicUnpublished', 'admin.contentStudio.publicationFailed', 'admin.contentStudio.submittedToReview', 'admin.contentStudio.submitReviewFailed', 'admin.employees.auditEvents', 'admin.employees.noUserAgentRecorded');
required.push('admin.employees.requestFailed', 'admin.employees.never', 'admin.employees.auditLoadFailed', 'admin.employees.workspaceLoadFailed', 'admin.employees.accountCreated', 'admin.employees.accountCreateFailed', 'admin.employees.permissionsUpdated', 'admin.employees.permissionsSaveFailed', 'admin.employees.suspendConfirm', 'admin.employees.accountActivated', 'admin.employees.accountSuspended', 'admin.employees.statusUpdateFailed', 'admin.employees.loadingKicker', 'admin.employees.preparingWorkspace', 'admin.employees.loadingDescription', 'admin.employees.governanceKicker', 'admin.employees.title', 'admin.employees.auditOnlyTitle', 'admin.employees.directoryDescription', 'admin.employees.auditOnlyDescription', 'admin.employees.directoryLive', 'admin.employees.auditReadOnly', 'admin.employees.principlesTitle', 'admin.employees.principlesDescription', 'admin.employees.needsAttention', 'admin.employees.workspaceUpdate', 'admin.employees.dismiss', 'admin.employees.staffDirectory', 'admin.employees.auditTrail', 'admin.employees.staffIdentities', 'admin.employees.notLearnerAccounts', 'admin.employees.activeCount', 'admin.employees.searchStaff', 'admin.employees.searchPlaceholder', 'admin.employees.taskPermissionCount', 'admin.employees.active', 'admin.employees.suspended', 'admin.employees.suspendAccess', 'admin.employees.reactivate', 'admin.employees.noMatchingAccounts', 'admin.employees.createOrSearchHelp', 'admin.employees.staffAccounts', 'admin.employees.permissionAssignment', 'admin.employees.role', 'admin.employees.taskPermissions', 'admin.employees.selectedCount', 'admin.employees.permissionHelp', 'admin.employees.adminRoleWarning', 'admin.employees.saving', 'admin.employees.saveRolePermissions', 'admin.employees.addStaffIdentity', 'admin.employees.createStaffAccount', 'admin.employees.createStaffHelp', 'admin.employees.fullName', 'admin.employees.staffNamePlaceholder', 'admin.employees.mobileNumber', 'admin.employees.mobilePlaceholder', 'admin.employees.initialRole', 'admin.employees.initialTaskPermissions', 'admin.employees.roleDefaultHelp', 'admin.employees.creating', 'admin.employees.recordedActivity', 'admin.employees.auditDescription', 'admin.employees.eventCount', 'admin.employees.ipUnavailable', 'admin.employees.details', 'admin.employees.noActivity', 'admin.employees.newActivityHelp', 'admin.employees.governanceFooter', 'admin.employees.governanceFooterDescription', 'admin.employees.roleContentAuthor', 'admin.employees.roleContentReviewer', 'admin.employees.roleModerator', 'admin.employees.rolePublisher', 'admin.employees.roleManager', 'admin.employees.roleAdministrator', 'admin.login.serverUnavailable', 'admin.login.unexpectedResponse', 'admin.login.mobileStepInstruction', 'admin.login.firstAccessInstruction', 'admin.login.otpStepInstruction', 'admin.login.firstAccessOtpHelp', 'admin.login.registeredOtpHelp', 'people.auth.usernamePlaceholder');
for (const key of required) {
  assert.equal(typeof english[key], 'string', 'English catalog missing ' + key);
  assert.equal(typeof hinglish[key], 'string', 'Hinglish catalog missing ' + key);
  assert.ok(english[key].trim() && hinglish[key].trim(), 'Empty catalog message ' + key);
  const placeholders = value => [...value.matchAll(/\{([a-zA-Z][a-zA-Z0-9_]*)\}/g)].map(match => match[1]).sort().join(',');
  assert.equal(placeholders(english[key]), placeholders(hinglish[key]), 'Placeholder drift for ' + key);
}
const sourceRoots = ['apps/web/app', 'apps/admin/app', 'apps/people/app'];
function sourceFiles(directory) {
  return fs.readdirSync(path.join(root, directory), { withFileTypes: true }).flatMap(entry => {
    const child = path.posix.join(directory, entry.name);
    if (entry.isDirectory()) return sourceFiles(child);
    return /\.(?:ts|tsx|js|jsx)$/.test(entry.name) && !entry.name.endsWith('.d.ts') ? [child] : [];
  });
}
const forcedLocalePatterns = [
  /translateUi\(\s*locale\s*===\s*['"]english['"]\s*\?\s*['"]english['"]\s*:\s*['"]hinglish['"]/,
  /translateUi\(\s*english\s*\?\s*['"]english['"]\s*:\s*['"]hinglish['"]/,
];
for (const file of sourceRoots.flatMap(sourceFiles)) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  for (const pattern of forcedLocalePatterns) {
    assert.equal(pattern.test(source), false, file + ': forced English/Hinglish mapping blocks registered locales');
  }
}


const sharedCopyRegressions = [
  ['apps/web/app/components/LandingAccountMenu.tsx', ['aria-label="Open account options"', 'aria-label="QuantaEdge account options"']],
  ['apps/web/app/components/ParentAccountMenu.tsx', ['aria-label="Parent account options"', "aria-label={loggingOut ? 'Logging out' : 'Log out'}", '<LocaleText']],
  ['apps/web/app/components/StudentAccountMenu.tsx', ['aria-label="Student account options"', "aria-label={loggingOut ? 'Logging out' : 'Log out'}", '<LocaleText']],
  ['apps/admin/app/components/PremiumPagination.tsx', ["'Pagination for '", "'No ' + itemLabel", "'Rows per page'", "'Previous page'", "'Next page'", '>Previous</span>', '>Next</span>']],
  ['apps/people/app/components/PeopleUI.tsx', ['aria-label="Table pagination"', 'Showing {first}–{last} of {total}', 'Rows per page', '>Previous</button>', '>Next</button>']],
  ['apps/admin/app/components/AdminWorkspaceLayout.tsx', ['aria-label="Close navigation menu"', 'aria-label="Admin navigation"']],
  ['apps/people/app/components/PeopleWorkspaceLayout.tsx', ['aria-label="Primary workspace"']],
  ['apps/admin/app/login/page.tsx', ['Use the mobile number assigned to you by your QuantaEdge administrator.', 'Your administrator has created your staff account. Verify your assigned mobile to activate access.', 'Enter the six-digit code sent to your assigned mobile number.', 'Verify your assigned number to activate your invitation.', 'Enter the code sent to your registered staff number.', '<span className="qe-admin-language-label">', '<option value="hinglish">']],
  ['apps/people/app/login/page.tsx', ['placeholder="HRMS_Admin"']],
  ['apps/admin/app/employees/page.tsx', ['Preparing staff workspace', 'Loading staff identities, permission grants and activity history…', 'Keep staff identities separate from parent/student accounts', 'Staff directory · live permissions', 'Separate identities. Least privilege. Traceable actions.', 'Audit history could not be loaded.', 'Staff workspace could not be loaded.', 'Staff account created. The person can now use Staff access', 'Permissions updated. The new grants are checked by the API', 'Staff status could not be updated.', 'No matching staff accounts', 'Choose the tasks this person is allowed to perform.', 'Administrator accounts retain every permission.', 'placeholder="Name, mobile or role"', 'placeholder="Staff member name"', 'placeholder="10-digit Indian mobile"', 'No user-agent recorded', 'IP unavailable']],
];
for (const [file, forbiddenCopy] of sharedCopyRegressions) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  for (const literal of forbiddenCopy) {
    assert.equal(source.includes(literal), false, file + ': found raw shared UI copy ' + literal);
  }
}


const lessonCopyHelpers = [
  ['apps/web/app/student/learn/LearnClient.tsx', /const tx\s*=\s*\(hinglish:\s*string,\s*english:\s*string\)/],
  ['apps/admin/app/structured-lesson-preview/StructuredLessonExperience.tsx', /const tx\s*=\s*\(locale:\s*LessonLocale/],
];
for (const [file, pattern] of lessonCopyHelpers) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  assert.equal(pattern.test(source), false, file + ': inline locale-pair translation helper remains');
}

const contentFeedbackCopyRegressions = [
  "You have unsaved changes. Leave this editor and discard them?",
  "The saved content version could not be identified. Refresh the topic and try again.",
  "Official curriculum source verified. The chapter can be published when its micro-topic checks pass.",
  "Official textbook mapping verified. The micro-topic can be submitted after the remaining checks pass.",
  "Submitted to the review queue. An authorized reviewer—including an administrator—can approve or return the active questions before publication.",
];
for (const literal of contentFeedbackCopyRegressions) {
  assert.equal(fs.readFileSync(path.join(root, 'apps/admin/app/content/page.tsx'), 'utf8').includes(literal), false,
    'apps/admin/app/content/page.tsx: found raw lifecycle feedback ' + literal);
}

const contentReviewCopyRegressions = [
  "Submit a draft micro-topic for review first.",
  "Tell the author what needs to change before resubmission:",
  "Add feedback explaining what the author must correct.",
  "Question approved.",
  "Question returned to the author with review feedback.",
  "This whole-page preview is source context only.",
  "This candidate is already attached to this micro-topic.",
];
for (const literal of contentReviewCopyRegressions) {
  assert.equal(fs.readFileSync(path.join(root, 'apps/admin/app/content/page.tsx'), 'utf8').includes(literal), false,
    'apps/admin/app/content/page.tsx: found raw review/visual copy ' + literal);
}

const contentValidationCopyRegressions = [
  "Questions must be a JSON array.",
  "Advanced question data loaded. Save to persist it.",
  "A lesson needs at least one teaching block. Add another block before removing this one.",
  "Assessment questions must be a list.",
  "Lesson saved for review.",
  "Publish request completed. Confirm final status and readiness below.",
  "Changes saved as a draft. Check the current learner preview",
  "Lesson and teaching material saved.",
  "Lesson could not be saved.",
  "Content library or question counts could not be loaded.",
  "Staff identity could not be verified.",
];
for (const literal of contentValidationCopyRegressions) {
  assert.equal(fs.readFileSync(path.join(root, 'apps/admin/app/content/page.tsx'), 'utf8').includes(literal), false,
    'apps/admin/app/content/page.tsx: found raw validation/save copy ' + literal);
}

console.log('Catalog keys/placeholders and migrated auth copy passed; no forced English/Hinglish mappings found across Web, Admin or People Operations UI source.');
