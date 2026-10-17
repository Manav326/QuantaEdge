import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const root = process.cwd();
const files = [
  'apps/web/app/parent/children/page.tsx',
  'apps/web/app/parent/profile/page.tsx',
  'apps/web/app/login/student/page.tsx',
];
const forbidden = [
  [/\btx\s*\(/, 'inline language-pair tx() helper/call'],
  [/function\s+tx\s*\(/, 'page-level tx helper'],
  [/setLocale\(\s*['"]hindi['"]\s*\)/, 'legacy UI locale value that is not a catalog ID'],
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
  'parent.children.archived', 'parent.profile.photoSaved', 'parent.profile.saved',
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
console.log('Centralized static copy guard passed for Parent children/profile and Student login first-pass migration.');
