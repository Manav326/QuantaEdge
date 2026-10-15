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

function readKotlinStringArgument(source, startIndex) {
  let index = startIndex;
  while (/\\s/.test(source[index] || '')) index += 1;
  if (source[index] !== '"') return null;
  index += 1;
  let value = '';
  while (index < source.length) {
    if (source[index] === '\\\\') {
      value += source[index + 1] || '';
      index += 2;
      continue;
    }
    if (source[index] === '"') return { value, end: index + 1 };
    value += source[index];
    index += 1;
  }
  return null;
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

// Catch visible Compose labels that bypass the shared mapping entirely (for example,
// Text("Privacy")). Existing per-screen t(...) and explicit language branches remain valid.
// Self-named language options and opaque issued/sample identifiers are intentionally language-neutral.
let checkedDirectText = 0;
const languageNameLabels = new Set(['English', 'हिन्दी', 'हिंदी']);
const directTextNeutralTokens = new Set(['OTP', 'PDF', 'PNG', 'QR', 'URL', 'ID', 'OK', '←', '↑', '↓', '·', '✓', '×', '—']);
for (const file of walk(uiRoot)) {
  const source = readFileSync(file, 'utf8');
  const token = 'Text(';
  let cursor = 0;
  while ((cursor = source.indexOf(token, cursor)) >= 0) {
    if (/[A-Za-z0-9_]/.test(source[cursor - 1] || '')) {
      cursor += token.length;
      continue;
    }
    const argsStart = cursor + token.length;
    cursor = argsStart;
    const literal = readKotlinStringArgument(source, argsStart);
    if (!literal) continue;
    checkedDirectText += 1;

    const raw = literal.value.trim();
    const visible = stripKotlinInterpolation(raw).trim();
    if (!hasUiLetters(visible)) continue;
    if (directTextNeutralTokens.has(raw) || languageNameLabels.has(raw)) continue;
    if (/^[A-Z][A-Z0-9_-]{8,}$/.test(raw)) continue; // certificate/reference IDs, not UI copy

    failures.push(relative(root, file) + ': direct Text("' + raw +
      '") bypasses the bilingual UI mapping; use localizedUiText(...), string resources, or an explicit bilingual branch.');
  }
}

// Also audit literal EmptyState title/help pairs. These pass through localizedUiText at runtime,
// so checking them catches blankly untranslated empty states before they ship.
let checkedStaticEmptyStates = 0;
for (const file of walk(uiRoot)) {
  const source = readFileSync(file, 'utf8');
  const token = 'EmptyState(';
  let cursor = 0;
  while ((cursor = source.indexOf(token, cursor)) >= 0) {
    const argsStart = cursor + token.length;
    cursor = argsStart;
    const title = readKotlinStringArgument(source, argsStart);
    if (!title) continue;
    let next = title.end;
    while (/\\s/.test(source[next] || '')) next += 1;
    if (source[next] !== ',') continue;
    const detail = readKotlinStringArgument(source, next + 1);
    if (!detail) continue;
    checkedStaticEmptyStates += 1;

    for (const [field, raw] of [['title', title.value], ['detail', detail.value]]) {
      const withoutInterpolation = stripKotlinInterpolation(raw);
      if (!hasUiLetters(withoutInterpolation)) continue;
      const exactMatch = exact.has(raw) || exact.has(withoutInterpolation);
      const prefixMatch = prefixes.some(prefix => withoutInterpolation.startsWith(prefix));
      if (!exactMatch && !prefixMatch) {
        failures.push(relative(root, file) + ': EmptyState ' + field + ' "' + raw + '" has no Hindi/English UiPhrase mapping.');
      }
    }
  }
}

if (failures.length) {
  console.error('Android localization contract failed: ' + failures.length + ' unmapped literal phrase(s) across ' + scannedFiles + ' screen file(s).');
  for (const failure of failures) console.error('- ' + failure);
  process.exit(1);
}

console.log('Android localization contract passed: ' + checkedPhrases + ' mapped localizedUiText call(s), ' + checkedDirectText + ' direct Compose Text literal(s) audited, and ' + checkedStaticEmptyStates + ' EmptyState title/detail pair(s) checked across ' + scannedFiles + ' screen file(s), using ' + phraseLines.length + ' shared bilingual phrase(s).');
