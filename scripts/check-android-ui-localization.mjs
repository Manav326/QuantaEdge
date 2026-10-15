import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.cwd();
const uiRoot = join(root, 'apps/android/app/src/main/java/com/quantaedge/android/ui');
const localizationPath = join(uiRoot, 'AndroidLocalization.kt');
const failures = [];
let scannedFiles = 0;
let checkedPhrases = 0;

function walk(directory) {
  const result = [];
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) result.push(...walk(path));
    else if (path.endsWith('.kt') && path !== localizationPath) result.push(path);
  }
  return result;
}

function stripKotlinInterpolation(value) {
  let result = '';
  for (let i = 0; i < value.length; i += 1) {
    if (value[i] !== '$') {
      result += value[i];
      continue;
    }
    if (value[i + 1] === '{') {
      const end = value.indexOf('}', i + 2);
      i = end < 0 ? value.length : end;
      continue;
    }
    if (/[A-Za-z_]/.test(value[i + 1] || '')) {
      i += 1;
      while (i + 1 < value.length && /[A-Za-z0-9_]/.test(value[i + 1])) i += 1;
      continue;
    }
    result += value[i];
  }
  return result;
}

function hasUiLetters(value) {
  for (const character of value) {
    const code = character.codePointAt(0);
    if ((code >= 65 && code <= 90) || (code >= 97 && code <= 122) || (code >= 0x0900 && code <= 0x097f)) return true;
  }
  return false;
}

const localization = readFileSync(localizationPath, 'utf8');
const phraseLines = localization.split('\n').filter(line => line.includes('UiPhrase("'));
const exact = new Set();
const prefixes = [];
for (const line of phraseLines) {
  const fields = line.split('"');
  const english = fields[3];
  const hindi = fields[5];
  for (const value of [english, hindi]) {
    if (typeof value !== 'string') continue;
    exact.add(value);
    if (value.endsWith(' ')) prefixes.push(value);
  }
}
if (exact.size === 0) {
  console.error('No UiPhrase entries found; cannot validate Android UI localization.');
  process.exit(1);
}

// Acronyms and standalone navigation glyphs are intentionally language-neutral.
const neutralTokens = new Set(['OTP', 'PDF', 'PNG', '←', '↑', '↓']);

for (const file of walk(uiRoot)) {
  scannedFiles += 1;
  const source = readFileSync(file, 'utf8');
  const token = 'localizedUiText("';
  let cursor = 0;
  while ((cursor = source.indexOf(token, cursor)) >= 0) {
    const start = cursor + token.length;
    const end = source.indexOf('")', start);
    if (end < 0) break;
    const value = source.slice(start, end);
    cursor = end + 2;
    checkedPhrases += 1;

    const withoutInterpolation = stripKotlinInterpolation(value);
    if (neutralTokens.has(value) || !hasUiLetters(withoutInterpolation)) continue;
    const exactMatch = exact.has(value) || exact.has(withoutInterpolation);
    const prefixMatch = prefixes.some(prefix => withoutInterpolation.startsWith(prefix));
    if (!exactMatch && !prefixMatch) {
      failures.push(relative(root, file) + ': localizedUiText("' + value + '") has no Hindi/English UiPhrase mapping.');
    }
  }
}

if (failures.length) {
  console.error('Android localization contract failed: ' + failures.length + ' unmapped literal phrase(s) across ' + scannedFiles + ' screen file(s).');
  for (const failure of failures) console.error('- ' + failure);
  process.exit(1);
}

console.log('Android localization contract passed: ' + checkedPhrases + ' literal localizedUiText call(s) checked across ' + scannedFiles + ' screen file(s), using ' + phraseLines.length + ' shared bilingual phrase(s).');
