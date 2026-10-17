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
];
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

console.log('Catalog keys/placeholders and migrated auth copy passed; no forced English/Hinglish mappings found across Web, Admin or People Operations UI source.');
