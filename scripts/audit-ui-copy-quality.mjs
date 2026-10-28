import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = process.cwd();
const DEFAULT_MAX_CHARS = 120;
const DEFAULT_MAX_WORDS = 20;

function flattenCatalog(value, prefix = '', output = {}) {
  for (const [name, message] of Object.entries(value ?? {})) {
    const key = prefix ? prefix + '.' + name : name;
    if (message && typeof message === 'object' && !Array.isArray(message)) {
      flattenCatalog(message, key, output);
    } else {
      output[key] = message;
    }
  }
  return output;
}

function placeholders(message) {
  return [...String(message ?? '').matchAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/g)]
    .map(match => match[1]).sort();
}

function wordCount(message) {
  const trimmed = String(message ?? '').trim();
  return trimmed ? trimmed.split(/\s+/u).length : 0;
}

/**
 * Returns an audit shortlist, not a machine judgment of translation correctness.
 * Long legal, safety, assessment, payment, and reconciliation copy still needs
 * human contextual review before it is shortened.
 */
export function inspectUiCopyQuality(englishCatalog, hinglishCatalog, options = {}) {
  const maxChars = options.maxChars ?? DEFAULT_MAX_CHARS;
  const maxWords = options.maxWords ?? DEFAULT_MAX_WORDS;
  const english = flattenCatalog(englishCatalog);
  const hinglish = flattenCatalog(hinglishCatalog);
  const allKeys = [...new Set([...Object.keys(english), ...Object.keys(hinglish)])].sort();
  const missingEnglish = [];
  const missingHinglish = [];
  const blankMessages = [];
  const placeholderMismatches = [];
  const longCandidates = [];

  for (const key of allKeys) {
    const en = english[key];
    const hi = hinglish[key];
    if (typeof en !== 'string') missingEnglish.push(key);
    if (typeof hi !== 'string') missingHinglish.push(key);
    if (typeof en !== 'string' || typeof hi !== 'string') continue;
    if (!en.trim() || !hi.trim()) blankMessages.push(key);
    if (JSON.stringify(placeholders(en)) !== JSON.stringify(placeholders(hi))) {
      placeholderMismatches.push({ key, english: placeholders(en), hinglish: placeholders(hi) });
    }
    const enWords = wordCount(en);
    const hiWords = wordCount(hi);
    if (en.length > maxChars || hi.length > maxChars || enWords > maxWords || hiWords > maxWords) {
      longCandidates.push({
        key,
        englishChars: en.length,
        hinglishChars: hi.length,
        englishWords: enWords,
        hinglishWords: hiWords,
        english: en,
        hinglish: hi,
      });
    }
  }

  longCandidates.sort((left, right) =>
    Math.max(right.englishChars, right.hinglishChars) - Math.max(left.englishChars, left.hinglishChars));
  return {
    rules: { maxChars, maxWords },
    summary: {
      englishKeys: Object.keys(english).length,
      hinglishKeys: Object.keys(hinglish).length,
      missingEnglish: missingEnglish.length,
      missingHinglish: missingHinglish.length,
      blankMessages: blankMessages.length,
      placeholderMismatches: placeholderMismatches.length,
      longCopyCandidates: longCandidates.length,
    },
    missingEnglish,
    missingHinglish,
    blankMessages,
    placeholderMismatches,
    longCandidates,
    note: 'Long-copy flags are candidates for human contextual review, not automatic failures. Confirm context and preserve legal, safety, payment, and assessment meaning before editing. This report cannot prove contextual translation accuracy.',
  };
}

function run() {
  const englishPath = path.join(ROOT, 'localization/catalogs/english.json');
  const hinglishPath = path.join(ROOT, 'localization/catalogs/hinglish.json');
  const english = JSON.parse(fs.readFileSync(englishPath, 'utf8'));
  const hinglish = JSON.parse(fs.readFileSync(hinglishPath, 'utf8'));
  const report = inspectUiCopyQuality(english, hinglish);
  const jsonIndex = process.argv.indexOf('--json');
  if (jsonIndex >= 0 && process.argv[jsonIndex + 1]) {
    fs.writeFileSync(path.resolve(process.argv[jsonIndex + 1]), JSON.stringify(report, null, 2) + '\n');
    console.log('UI copy-quality report written.');
  }
  console.log(JSON.stringify({ summary: report.summary, rules: report.rules, longestCandidates: report.longCandidates.slice(0, 12).map(({ key, englishChars, hinglishChars, englishWords, hinglishWords }) => ({ key, englishChars, hinglishChars, englishWords, hinglishWords })) }, null, 2));
  if (report.summary.missingEnglish || report.summary.missingHinglish || report.summary.blankMessages || report.summary.placeholderMismatches) {
    process.exitCode = 1;
  }
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (invokedPath === fileURLToPath(import.meta.url)) run();
