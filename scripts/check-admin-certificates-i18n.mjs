#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const readJson = async (path) => JSON.parse(await readFile(join(root, path), 'utf8'));
const [page, english, hinglish] = await Promise.all([
  readFile(join(root, 'apps/admin/app/certificates/page.tsx'), 'utf8'),
  readJson('localization/catalogs/english.json'),
  readJson('localization/catalogs/hinglish.json'),
]);
const failures = [];
const assert = (condition, message) => { if (!condition) failures.push(message); };
const placeholders = (value) => [...value.matchAll(/\{([a-zA-Z][a-zA-Z0-9_]*)\}/g)].map((match) => match[1]).sort();

assert(page.includes("import { useUiTranslation } from '../i18n/useUiTranslation';"), 'Certificates page must use the central Admin translation hook.');
assert(page.includes("import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';"), 'Certificates page must use the shared Admin locale for date formatting.');
assert(/t\('admin\.certificates\./.test(page), 'Certificates page must read semantic admin.certificates catalog keys.');
const used = new Set([...page.matchAll(/t\('admin\.certificates\.([A-Za-z0-9]+)'/g)].map((match) => 'admin.certificates.' + match[1]));
assert(used.size > 0, 'No certificate message keys were found.');
for (const key of used) {
  assert(typeof english[key] === 'string' && english[key].trim(), 'Missing English message: ' + key);
  assert(typeof hinglish[key] === 'string' && hinglish[key].trim(), 'Missing Hinglish message: ' + key);
  if (english[key] && hinglish[key]) assert(JSON.stringify(placeholders(english[key])) === JSON.stringify(placeholders(hinglish[key])), 'Placeholder mismatch for ' + key);
}
for (const literal of ['LEARNER OPERATIONS','Issued learning achievements, file availability, validity and revocation audit.','Loading the certificate register…','No certificates found','Recipient / certificate ID','Confirm revocation','Loading audit events…','Unable to load the certificate register.']) {
  assert(!page.includes('>' + literal + '<') && !page.includes("'" + literal + "'") && !page.includes('"' + literal + '"'), 'Page still embeds an English UI literal intended for the catalog: ' + literal);
}
if (failures.length) {
  console.error('Admin Certificates localization contract failed:\n- ' + failures.join('\n- '));
  process.exitCode = 1;
} else console.log('Admin Certificates localization contract passed: ' + used.size + ' semantic message keys, both locales, matching placeholders.');
