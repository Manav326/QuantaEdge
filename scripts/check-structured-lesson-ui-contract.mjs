#!/usr/bin/env node
/**
 * Dependency-free regression guard for the structured-lesson Web/Admin/Android
 * contract. This is deliberately a source/data contract check, not a substitute
 * for browser/device interaction tests.
 */
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const questionContracts = {
  multiple_choice: 'qtype-multiple-choice.v1',
  multiple_select: 'qtype-multiple-select.v1',
  true_false: 'qtype-true-false.v1',
  matching: 'qtype-matching.v1',
  fill_blank: 'qtype-fill-blank.v1',
  image_observation: 'qtype-image-observation.v1',
  short_answer: 'qtype-short-answer.v1',
  scenario_based: 'qtype-scenario-based.v1',
};
const blockTypes = ['rich_text', 'image', 'diagram', 'interactive', 'activity', 'recap'];

const files = {
  webContract: 'apps/web/app/student/structured-lessons/contract.ts',
  webQuestions: 'apps/web/app/student/structured-lessons/StructuredQuestionRenderer.tsx',
  webContent: 'apps/web/app/student/structured-lessons/StructuredLessonContent.tsx',
  webExperience: 'apps/web/app/student/structured-lessons/StructuredLessonExperience.tsx',
  webDetail: 'apps/web/app/student/structured-lessons/StructuredLessonDetailClient.tsx',
  adminContract: 'apps/admin/app/structured-lesson-preview/contract.ts',
  adminQuestions: 'apps/admin/app/structured-lesson-preview/StructuredQuestionRenderer.tsx',
  adminContent: 'apps/admin/app/structured-lesson-preview/StructuredLessonContent.tsx',
  adminPreview: 'apps/admin/app/structured-lesson-preview/page.tsx',
  androidRenderer: 'apps/android/app/src/main/java/com/quantaedge/android/ui/StructuredLessonRenderer.kt',
  androidApp: 'apps/android/app/src/main/java/com/quantaedge/android/ui/QuantaEdgeApp.kt',
  apiService: 'services/api/src/main/java/com/quantaedge/api/StructuredLessonArtifactService.java',
  apiController: 'services/api/src/main/java/com/quantaedge/api/StructuredLessonController.java',
};
const source = Object.fromEntries(Object.entries(files).map(([key, path]) => {
  const value = read(path);
  assert.ok(value.length > 0, path + ' must not be empty');
  return [key, value];
}));

for (const [type, contractId] of Object.entries(questionContracts)) {
  assert.ok(source.webContract.includes("'" + type + "'"), 'Web contract missing question type: ' + type);
  assert.ok(source.webContract.includes(type + ": '" + contractId + "'"), 'Web contract ID mismatch for ' + type);
  assert.ok(source.adminContract.includes(type + ": '" + contractId + "'"), 'Admin contract ID mismatch for ' + type);
  assert.ok(new RegExp("question\\.type\\s*===\\s*['\"]" + type + "['\"]").test(source.webQuestions),
    'Web renderer missing dispatcher for ' + type);
  assert.ok(new RegExp("question\\.type\\s*===\\s*['\"]" + type + "['\"]").test(source.adminQuestions),
    'Admin renderer missing dispatcher for ' + type);
  assert.ok(source.androidRenderer.includes('"' + type + '"'), 'Android renderer missing type branch: ' + type);
}

for (const type of blockTypes) {
  assert.ok(source.webContract.includes("'" + type + "'"), 'Web validator missing block type ' + type);
  assert.ok(source.webContent.includes("'" + type + "'"), 'Web block renderer missing ' + type);
  assert.ok(source.adminContent.includes("'" + type + "'"), 'Admin block renderer missing ' + type);
  assert.ok(source.androidRenderer.includes('"' + type + '"'), 'Android block renderer missing ' + type);
}

for (const [key, needle] of [
  ['webContract', 'sanitizePublishedLessonForStudent'],
  ['webContract', 'publication_allowed'],
  ['webExperience', 'blockedByPublicationGate'],
  ['webQuestions', 'isQuestionVisualApproved'],
  ['adminPreview', 'validateStructuredLesson'],
  ['adminPreview', 'publicationAllowed'],
  ['apiService', 'publication_allowed'],
  ['apiService', 'stripPrivateFields'],
  ['apiService', 'correct_answer_ids'],
  ['apiService', 'isApprovedVisual'],
  ['apiController', '/responses'],
  ['apiController', 'requireStudent'],
  ['apiController', 'ensureStudentTrackAccess'],
  ['webDetail', 'initialResponses={responses}'],
  ['webDetail', 'initialActivityResponses={saved.activities || {}}'],
  ['androidRenderer', 'savedResponses'],
  ['androidApp', '/api/v1/learning/structured-lessons/'],
]) {
  assert.ok(source[key].includes(needle), key + ' is missing expected security/state integration marker: ' + needle);
}

assert.ok(source.adminPreview.includes("setLocale('hi')"), 'Admin preview must have Hindi language selection.');
assert.ok(source.adminPreview.includes("setLocale('en')"), 'Admin preview must have English language selection.');
assert.ok(source.webDetail.includes('learningLanguage'), 'Web learner must use the global learning language.');

function walk(path) {
  return readdirSync(join(root, path)).flatMap((name) => {
    const child = join(path, name);
    return statSync(join(root, child)).isDirectory() ? walk(child) : [child];
  });
}
const lessonFiles = walk('data/structured-lessons/class-6/science')
  .filter((path) => /\/chapter-\d+\/topic-[^/]+\.json$/.test(path));
assert.ok(lessonFiles.length >= 10, 'Expected at least 10 Class 6 Science lesson topics; found ' + lessonFiles.length);

const artifactIds = new Set();
const chapters = new Set();
let questionCount = 0;
const encounteredTypes = new Set();
for (const path of lessonFiles) {
  let artifact;
  try { artifact = JSON.parse(read(path)); }
  catch (error) { throw new Error(path + ': invalid JSON (' + error.message + ')'); }
  assert.ok(typeof artifact.artifact_id === 'string' && artifact.artifact_id.trim(), path + ': artifact_id is required');
  assert.ok(!artifactIds.has(artifact.artifact_id), path + ': duplicate artifact_id ' + artifact.artifact_id);
  artifactIds.add(artifact.artifact_id);
  assert.ok(artifact.workflow && typeof artifact.workflow.publication_allowed === 'boolean',
    path + ': explicit publication_allowed gate is required');
  assert.ok(artifact.chapter && Number.isInteger(artifact.chapter.chapter_number), path + ': chapter_number is required');
  assert.ok(artifact.topic?.title_by_locale?.en && artifact.topic?.title_by_locale?.hi,
    path + ': both topic title locales are required');
  assert.ok(Array.isArray(artifact.explain?.blocks), path + ': explain.blocks must be an array');
  assert.ok(Array.isArray(artifact.practice?.questions), path + ': practice.questions must be an array');
  chapters.add(artifact.chapter.chapter_number);
  for (const question of artifact.practice.questions) {
    questionCount += 1;
    assert.ok(typeof question.id === 'string' && question.id.trim(), path + ': question ID is required');
    assert.ok(questionContracts[question.type], path + ': unsupported question type ' + question.type);
    encounteredTypes.add(question.type);
  }
}
for (const chapter of [1, 2, 6]) {
  assert.ok(chapters.has(chapter), 'Class 6 Science chapter ' + chapter + ' fixture coverage is missing');
}

console.log('Structured lesson contract passed: ' + Object.keys(questionContracts).length +
  ' question types, ' + blockTypes.length + ' block types, ' + lessonFiles.length +
  ' JSON lesson artifacts, ' + questionCount + ' authored questions; chapters 1/2/6 present.');
console.log('Types in current data: ' + ([...encounteredTypes].sort().join(', ') || '(none)') + '.');
console.log('Note: contract/source checks do not replace browser/device interaction and accessibility acceptance tests.');
