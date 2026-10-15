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

const localization = readFileSync(localizationPath, 'utf8');
const phrases = [...localization.matchAll(/UiPhrase\("([^"]+)",\s*"((?:\\\\.|[^"\\\\])*)",\s*"((?:\\\\.|[^"\\\\])*)"\)/g)];
if (phrases.length === 0) {
  console.error('No UiPhrase entries found; cannot validate Android UI localization.');
  process.exit(1);
}

const exact = new Set();
const prefixes = [];
for (const [, , english, hindi] of phrases) {
  for (const value of [english, hindi]) {
    exact.add(value);
    if (value.endsWith(' ')) prefixes.push(value);
  }
}

// Acronyms and standalone navigation glyphs are intentionally platform/language neutral.
const neutralTokens = new Set(['OTP', 'PDF', 'PNG', '←', '↑', '↓']);

for (const file of walk(uiRoot)) {
  scannedFiles += 1;
  const source = readFileSync(file, 'utf8');
  const callPattern = /localizedUiText\(\s*"((?:\\\\.|[^"\\\\])*)"\s*\)/g;

  for (const [, raw] of source.matchAll(callPattern)) {
    checkedPhrases += 1;
    const value = raw.replace(/\\\\"/g, '"').replace(/\\\\\\\\/g, '\\\\');
    const withoutInterpolation = value.replace(/\\$\\{[^}]*\\}|\\$[A-Za-z_][A-Za-z0-9_]*/g, '');
    if (neutralTokens.has(value) || !/[A-Za-z\\u0900-\\u097F]/.test(withoutInterpolation)) continue;

    const exactMatch = exact.has(value) || exact.has(withoutInterpolation);
    const prefixMatch = prefixes.some(prefix => withoutInterpolation.startsWith(prefix));
    if (!exactMatch && !prefixMatch) {
      failures.push(`${relative(root, file)}: localizedUiText("${value}") has no Hindi/English UiPhrase mapping.`);
    }
  }
}

if (failures.length) {
  console.error(`Android localization contract failed: ${failures.length} unmapped literal phrase(s) across ${scannedFiles} screen file(s).`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Android localization contract passed: ${checkedPhrases} literal localizedUiText call(s) checked across ${scannedFiles} screen file(s), using ${phrases.length} shared bilingual phrase(s).`);
