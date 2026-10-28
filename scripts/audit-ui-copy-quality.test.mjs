import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectUiCopyQuality } from './audit-ui-copy-quality.mjs';

test('copy-quality audit detects missing locale keys and placeholder drift', () => {
  const report = inspectUiCopyQuality(
    { 'course.loadError': 'Could not load {course}.', 'course.open': 'Open course' },
    { 'course.loadError': 'कोर्स लोड नहीं हुआ।' },
  );
  assert.deepEqual(report.missingHinglish, ['course.open']);
  assert.equal(report.summary.placeholderMismatches, 1);
});

test('copy-quality audit flags long messages for contextual review without rejecting them', () => {
  const english = { 'admin.report.help': 'Review the saved report and check every flagged result before publishing the course.' };
  const hinglish = { 'admin.report.help': 'रिपोर्ट देखें और कोर्स प्रकाशित करने से पहले हर समस्या जाँचें।' };
  const report = inspectUiCopyQuality(english, hinglish, { maxChars: 30, maxWords: 5 });
  assert.equal(report.summary.longCopyCandidates, 1);
  assert.equal(report.longCandidates[0].key, 'admin.report.help');
  assert.match(report.note, /human contextual review/i);
  assert.equal(report.summary.placeholderMismatches, 0);
});

test('matching short copy passes the style shortlist', () => {
  const report = inspectUiCopyQuality(
    { 'common.save': 'Save changes' },
    { 'common.save': 'बदलाव सेव करें' },
  );
  assert.equal(report.summary.longCopyCandidates, 0);
  assert.equal(report.summary.missingEnglish, 0);
  assert.equal(report.summary.missingHinglish, 0);
});
