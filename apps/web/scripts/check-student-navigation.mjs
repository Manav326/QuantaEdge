import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativePath) => readFileSync(new URL(relativePath, import.meta.url), 'utf8');
const layout = read('../app/components/WorkspaceLayout.tsx');
const css = read('../app/globals.css');
const accountMenu = read('../app/components/StudentAccountMenu.tsx');

const arrayStart = layout.indexOf('const studentNavGroups: StudentNavGroup[] = [');
const arrayEnd = layout.indexOf('\nconst parentItems: Item[] = [', arrayStart);
assert.notEqual(arrayStart, -1, 'Student navigation configuration must exist.');
assert.notEqual(arrayEnd, -1, 'Parent menu must follow the student navigation configuration.');
const studentGroups = layout.slice(arrayStart, arrayEnd);

const groupIds = [...studentGroups.matchAll(/\bid: '(today|learn|practice|progress|library)'/g)].map((match) => match[1]);
assert.deepEqual(groupIds, ['today', 'learn', 'practice', 'progress', 'library'], 'Student navigation must have exactly five ordered top-level groups.');

const requiredGroupRoutes = [
  { group: 'today', routes: ['/student'] },
  { group: 'learn', routes: ['/student/learn', '/student/classes'] },
  { group: 'practice', routes: ['/student/practice', '/student/tests', '/student/diagnostic'] },
  { group: 'progress', routes: ['/student/progress', '/student/certificates'] },
  { group: 'library', routes: ['/student/textbooks'] },
];

for (const { group, routes } of requiredGroupRoutes) {
  const start = studentGroups.indexOf(`id: '${group}'`);
  assert.notEqual(start, -1, `The ${group} group must be defined.`);
  const nextGroupIndex = groupIds.indexOf(group) + 1;
  const nextId = groupIds[nextGroupIndex];
  const end = nextId ? studentGroups.indexOf(`id: '${nextId}'`, start + 1) : studentGroups.length;
  const groupSource = studentGroups.slice(start, end);
  for (const route of routes) {
    assert.ok(groupSource.includes(route), `Expected route ${route} in the ${group} group.`);
  }
}

assert.ok(!studentGroups.includes('/student/profile'), 'Profile must not be a primary navigation destination.');
assert.ok(accountMenu.includes("href=\"/student/profile\"") || accountMenu.includes("href='/student/profile'"), 'Profile & Settings must remain accessible from the student account menu.');

const parentStart = layout.indexOf('const parentItems: Item[] = [');
const parentEnd = layout.indexOf('\nexport default function WorkspaceLayout', parentStart);
const parentItems = layout.slice(parentStart, parentEnd);
for (const route of ['/parent', '/parent/children', '/parent/profile']) {
  assert.ok(parentItems.includes(route), `Parent route ${route} must remain configured.`);
}

assert.ok(layout.includes('visibleStudentNavGroups.map(group => group.href ?'), 'Desktop student navigation must use the grouped configuration.');
assert.ok(layout.includes('qe-student-mobile-nav'), 'Mobile student navigation must use the grouped configuration.');
assert.ok(layout.includes("event.key === 'Escape'"), 'Mobile navigation must close on Escape.');
assert.ok(layout.includes('qe-workspace-mobile-nav-backdrop'), 'Mobile navigation must support dismissal by tapping the backdrop.');
assert.ok(layout.includes('hidden={expandedStudentGroup !== group.id}'), 'Desktop submenu target must remain mounted for aria-controls.');
assert.ok(layout.includes('hidden={mobileOpenGroup !== group.id}'), 'Mobile submenu targets must remain mounted for aria-controls.');
assert.ok(css.includes('.qe-workspace-subnav[hidden]') && css.includes('.qe-workspace-mobile-subnav[hidden]'), 'Closed submenu targets must be visually hidden.');
assert.ok(css.includes('.qe-workspace-mobile-nav-backdrop'), 'Mobile submenu backdrop styles must exist.');

console.log('Student navigation contract passed: five groups, route ownership, profile access, parent preservation and accessibility hooks.');
