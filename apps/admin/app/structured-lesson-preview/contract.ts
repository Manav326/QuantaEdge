export type LessonLocale = 'en' | 'hi';

export type LocalizedText = {
  en: string;
  hi: string;
};

export type StructuredQuestionType =
  | 'multiple_choice'
  | 'multiple_select'
  | 'true_false'
  | 'matching'
  | 'fill_blank'
  | 'image_observation'
  | 'short_answer'
  | 'scenario_based';

export type StructuredOption = { id: string; text_by_locale: LocalizedText };
export type StructuredPair = { left_id: string; right_id: string };

export type StructuredQuestion = {
  id: string;
  type: StructuredQuestionType;
  rendering_contract_id: string;
  prompt_by_locale: LocalizedText;
  feedback_by_locale?: LocalizedText;
  options?: StructuredOption[];
  correct_answer_ids?: string[];
  correct_answer?: boolean;
  left_items?: Array<{ id: string; label_by_locale: LocalizedText }>;
  right_items?: Array<{ id: string; label_by_locale: LocalizedText }>;
  correct_pairs?: StructuredPair[];
  accepted_answers?: { en: string[]; hi: string[] };
  answer_guide_by_locale?: Record<LessonLocale, { key_points?: string[]; sample_answer?: string }>;
  grading?: Record<string, unknown>;
  scenario_by_locale?: LocalizedText;
  visual_asset_ref?: string;
  renderability?: { status?: string; reason?: string; [key: string]: unknown };
  difficulty?: string;
  objective_ids?: string[];
  [key: string]: unknown;
};

export type StructuredExplainBlock = {
  id: string;
  type: string;
  order: number;
  objective_ids?: string[];
  text_by_locale?: Record<LessonLocale, {
    heading?: string;
    paragraphs?: string[];
    key_point?: string;
    callout?: string;
    [key: string]: unknown;
  }>;
  content_by_locale?: LocalizedText | Record<LessonLocale, unknown>;
  asset?: Record<string, unknown>;
  diagram_data?: Record<string, unknown>;
  alt_by_locale?: LocalizedText;
  caption_by_locale?: LocalizedText;
  configuration?: Record<string, unknown>;
  instructions_by_locale?: LocalizedText;
  [key: string]: unknown;
};

export type StructuredLesson = {
  schema_version: string;
  artifact_type: string;
  artifact_id: string;
  workflow?: Record<string, unknown>;
  curriculum?: Record<string, unknown>;
  chapter: { chapter_number: number; title_by_locale: LocalizedText; [key: string]: unknown };
  topic: { topic_number: number; topic_id: string; title_by_locale: LocalizedText; [key: string]: unknown };
  learning_objectives?: Array<{ id: string; statement_by_locale: LocalizedText }>;
  source_refs?: Array<Record<string, unknown>>;
  explain: { title_by_locale: LocalizedText; blocks: StructuredExplainBlock[]; [key: string]: unknown };
  practice: { title_by_locale: LocalizedText; questions: StructuredQuestion[]; question_count?: number; [key: string]: unknown };
  language_runtime_contract?: Record<string, unknown>;
  [key: string]: unknown;
};

export type ValidationIssue = { path: string; code: string; message: string };
export type ValidationResult =
  | { ok: true; lesson: StructuredLesson; issues: [] }
  | { ok: false; lesson: null; issues: ValidationIssue[] };

export const QUESTION_CONTRACTS: Record<StructuredQuestionType, string> = {
  multiple_choice: 'qtype-multiple-choice.v1',
  multiple_select: 'qtype-multiple-select.v1',
  true_false: 'qtype-true-false.v1',
  matching: 'qtype-matching.v1',
  fill_blank: 'qtype-fill-blank.v1',
  image_observation: 'qtype-image-observation.v1',
  short_answer: 'qtype-short-answer.v1',
  scenario_based: 'qtype-scenario-based.v1',
};

const QUESTION_TYPES = new Set<string>(Object.keys(QUESTION_CONTRACTS));
const BLOCK_TYPES = new Set(['rich_text', 'image', 'diagram', 'interactive', 'activity', 'recap']);
const isRecord = (value: unknown): value is Record<string, any> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const nonEmpty = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
const localized = (value: unknown): value is LocalizedText =>
  isRecord(value) && nonEmpty(value.en) && nonEmpty(value.hi);

export function readLocale(value: unknown, locale: LessonLocale): string | null {
  if (!localized(value)) return null;
  const text = value[locale].trim();
  return text || null;
}

export function isStudentRenderableAsset(asset: unknown): boolean {
  if (!isRecord(asset)) return false;
  const review = String(asset.review_status || '').toUpperCase();
  return asset.publication_allowed === true &&
    asset.student_renderable === true &&
    typeof asset.url === 'string' && asset.url.startsWith('https://') &&
    localized(asset.alt_by_locale) &&
    ['APPROVED', 'APPROVED_FOR_STUDENT_RENDERING'].includes(review);
}

export function validateStructuredLesson(input: unknown): ValidationResult {
  const issues: ValidationIssue[] = [];
  const add = (path: string, code: string, message: string) => issues.push({ path, code, message });
  if (!isRecord(input)) return { ok: false, lesson: null, issues: [{ path: '$', code: 'OBJECT_REQUIRED', message: 'Lesson artifact must be a JSON object.' }] };

  if (!nonEmpty(input.schema_version)) add('schema_version', 'REQUIRED', 'schema_version is required.');
  if (!nonEmpty(input.artifact_id)) add('artifact_id', 'REQUIRED', 'artifact_id is required.');
  if (!isRecord(input.chapter)) add('chapter', 'REQUIRED', 'chapter metadata is required.');
  else if (!localized(input.chapter.title_by_locale)) add('chapter.title_by_locale', 'LOCALE_GAP', 'English and Hindi chapter titles are both required.');
  if (!isRecord(input.topic)) add('topic', 'REQUIRED', 'topic metadata is required.');
  else {
    if (!nonEmpty(input.topic.topic_id)) add('topic.topic_id', 'REQUIRED', 'topic_id is required.');
    if (!localized(input.topic.title_by_locale)) add('topic.title_by_locale', 'LOCALE_GAP', 'English and Hindi topic titles are both required.');
  }
  if (!isRecord(input.explain) || !Array.isArray(input.explain.blocks)) add('explain.blocks', 'REQUIRED', 'explain.blocks must be an array.');
  if (!isRecord(input.practice) || !Array.isArray(input.practice.questions)) add('practice.questions', 'REQUIRED', 'practice.questions must be an array.');

  const blocks: unknown[] = isRecord(input.explain) && Array.isArray(input.explain.blocks) ? input.explain.blocks : [];
  const questions: unknown[] = isRecord(input.practice) && Array.isArray(input.practice.questions) ? input.practice.questions : [];
  const seenBlocks = new Set<string>();
  blocks.forEach((block, index) => {
    const path = 'explain.blocks[' + index + ']';
    if (!isRecord(block)) { add(path, 'OBJECT_REQUIRED', 'Content block must be an object.'); return; }
    if (!nonEmpty(block.id)) add(path + '.id', 'REQUIRED', 'Block id is required.');
    else if (seenBlocks.has(block.id)) add(path + '.id', 'DUPLICATE_ID', 'Block IDs must be unique within a lesson.');
    else seenBlocks.add(block.id);
    if (!BLOCK_TYPES.has(String(block.type))) add(path + '.type', 'UNKNOWN_BLOCK_TYPE', 'Unsupported block type: ' + String(block.type));
    if (!Number.isFinite(block.order)) add(path + '.order', 'INVALID_ORDER', 'Block order must be numeric.');
    if (block.type === 'rich_text') {
      const text = block.text_by_locale;
      if (!isRecord(text) || !isRecord(text.en) || !isRecord(text.hi)) add(path + '.text_by_locale', 'LOCALE_GAP', 'Rich text needs English and Hindi content.');
      else {
        ['en', 'hi'].forEach(locale => {
          const body = text[locale];
          if (!nonEmpty(body.heading) && !Array.isArray(body.paragraphs)) add(path + '.text_by_locale.' + locale, 'CONTENT_EMPTY', 'Localized rich text needs a heading or paragraphs.');
          if (Array.isArray(body.paragraphs) && body.paragraphs.some((p: unknown) => !nonEmpty(p))) add(path + '.text_by_locale.' + locale + '.paragraphs', 'CONTENT_EMPTY', 'Paragraphs cannot be blank.');
        });
      }
    }
    if (block.type === 'diagram' && !isRecord(block.diagram_data)) add(path + '.diagram_data', 'REQUIRED', 'Diagram data is required.');
    if (block.type === 'interactive' && !isRecord(block.configuration)) add(path + '.configuration', 'REQUIRED', 'Interactive activity configuration is required.');
    if (block.type === 'activity') {
      const instructions = block.instructions_by_locale;
      const hasInstructions = (locale: 'en' | 'hi') => isRecord(instructions) && (nonEmpty(instructions[locale]) || (Array.isArray(instructions[locale]) && instructions[locale].some(nonEmpty)));
      if (!hasInstructions('en') || !hasInstructions('hi')) add(path + '.instructions_by_locale', 'LOCALE_GAP', 'Activity instructions need English and Hindi text or non-empty step arrays.');
    }
  });

  const seenQuestions = new Set<string>();
  questions.forEach((question, index) => {
    const path = 'practice.questions[' + index + ']';
    if (!isRecord(question)) { add(path, 'OBJECT_REQUIRED', 'Question must be an object.'); return; }
    if (!nonEmpty(question.id)) add(path + '.id', 'REQUIRED', 'Stable question id is required.');
    else if (seenQuestions.has(question.id)) add(path + '.id', 'DUPLICATE_ID', 'Question IDs must be unique within a lesson.');
    else seenQuestions.add(question.id);
    if (!QUESTION_TYPES.has(String(question.type))) { add(path + '.type', 'UNKNOWN_QUESTION_TYPE', 'Question type is not registered.'); return; }
    const type = question.type as StructuredQuestionType;
    if (question.rendering_contract_id !== QUESTION_CONTRACTS[type]) add(path + '.rendering_contract_id', 'CONTRACT_MISMATCH', 'The rendering contract does not match question type.');
    if (!localized(question.prompt_by_locale)) add(path + '.prompt_by_locale', 'LOCALE_GAP', 'Question prompt needs English and Hindi.');
    if (type !== 'short_answer' && !localized(question.feedback_by_locale)) add(path + '.feedback_by_locale', 'LOCALE_GAP', 'Question feedback needs English and Hindi.');
    const optionTypes: StructuredQuestionType[] = ['multiple_choice', 'multiple_select', 'image_observation', 'scenario_based'];
    if (optionTypes.includes(type)) {
      if (!Array.isArray(question.options) || question.options.length < 2) add(path + '.options', 'OPTIONS_REQUIRED', 'At least two options are required.');
      else {
        const ids = new Set<string>();
        question.options.forEach((option: unknown, optionIndex: number) => {
          if (!isRecord(option) || !nonEmpty(option.id) || !localized(option.text_by_locale)) add(path + '.options[' + optionIndex + ']', 'INVALID_OPTION', 'Each option needs a stable id and English/Hindi label.');
          else if (ids.has(option.id)) add(path + '.options[' + optionIndex + '].id', 'DUPLICATE_ID', 'Option IDs must be unique.');
          else ids.add(option.id);
        });
        if (!Array.isArray(question.correct_answer_ids) || question.correct_answer_ids.length < 1) add(path + '.correct_answer_ids', 'ANSWER_REQUIRED', 'At least one correct option ID is required.');
        else question.correct_answer_ids.forEach((id: unknown) => { if (typeof id !== 'string' || !ids.has(id)) add(path + '.correct_answer_ids', 'INVALID_ANSWER_REF', 'Correct answer references a missing option ID.'); });
        if (type === 'multiple_choice' && Array.isArray(question.correct_answer_ids) && question.correct_answer_ids.length !== 1) add(path + '.correct_answer_ids', 'SINGLE_ANSWER_REQUIRED', 'Multiple choice requires exactly one correct option.');
      }
    }
    if (type === 'true_false' && typeof question.correct_answer !== 'boolean') add(path + '.correct_answer', 'BOOLEAN_REQUIRED', 'True/false needs a boolean answer.');
    if (type === 'matching') {
      const left = Array.isArray(question.left_items) ? question.left_items : [];
      const right = Array.isArray(question.right_items) ? question.right_items : [];
      const leftIds = new Set<string>();
      const rightIds = new Set<string>();
      left.forEach((item: unknown) => { if (isRecord(item) && nonEmpty(item.id) && localized(item.label_by_locale)) leftIds.add(item.id); else add(path + '.left_items', 'INVALID_MATCH_ITEM', 'Each left item needs id and bilingual label.'); });
      right.forEach((item: unknown) => { if (isRecord(item) && nonEmpty(item.id) && localized(item.label_by_locale)) rightIds.add(item.id); else add(path + '.right_items', 'INVALID_MATCH_ITEM', 'Each right item needs id and bilingual label.'); });
      if (!left.length || !right.length || !Array.isArray(question.correct_pairs)) add(path + '.correct_pairs', 'MATCHING_REQUIRED', 'Matching requires left/right items and correct pairs.');
      else question.correct_pairs.forEach((pair: unknown) => { if (!isRecord(pair) || !leftIds.has(String(pair.left_id)) || !rightIds.has(String(pair.right_id))) add(path + '.correct_pairs', 'INVALID_PAIR_REF', 'A pair refers to an unknown left/right ID.'); });
    }
    if (type === 'fill_blank') {
      const answers = question.accepted_answers;
      if (!isRecord(answers) || !Array.isArray(answers.en) || !Array.isArray(answers.hi) || !answers.en.some(nonEmpty) || !answers.hi.some(nonEmpty)) add(path + '.accepted_answers', 'LOCALE_GAP', 'Accepted answers need non-empty English and Hindi variants.');
    }
    if (type === 'short_answer') {
      const guide = question.answer_guide_by_locale;
      if (!isRecord(guide) || !isRecord(guide.en) || !isRecord(guide.hi) || !nonEmpty(guide.en.sample_answer) || !nonEmpty(guide.hi.sample_answer)) add(path + '.answer_guide_by_locale', 'LOCALE_GAP', 'Short answer guide needs localized sample answers.');
      if (!isRecord(question.grading) || question.grading.mode !== 'rubric') add(path + '.grading', 'RUBRIC_REQUIRED', 'Short answers require an explicit rubric grading contract.');
    }
    if (type === 'scenario_based' && !localized(question.scenario_by_locale)) add(path + '.scenario_by_locale', 'LOCALE_GAP', 'Scenario context needs English and Hindi.');
    if (type === 'image_observation') {
      if (!nonEmpty(question.visual_asset_ref)) add(path + '.visual_asset_ref', 'VISUAL_REF_REQUIRED', 'Image observation requires a visual reference.');
      else {
        const ref = question.visual_asset_ref;
        const linked = blocks.some(block => isRecord(block) && (
          block.id === ref || block.visual_id === ref || block.asset?.asset_id === ref ||
          (Array.isArray(block.asset_refs) && block.asset_refs.includes(ref)) ||
          block.diagram_data?.visual_id === ref || block.diagram_data?.diagram_id === ref
        ));
        if (!linked) add(path + '.visual_asset_ref', 'MISSING_VISUAL_REF', 'Visual reference does not match any block/asset in this lesson.');
      }
      // Draft visuals may remain blocked; student rendering requires explicit approval.
    }
  });

  if (issues.length) return { ok: false, lesson: null, issues };
  return { ok: true, lesson: input as unknown as StructuredLesson, issues: [] };
}

export type StudentSanitizationResult =
  | { ok: true; lesson: StructuredLesson; issues: [] }
  | { ok: false; lesson: null; issues: ValidationIssue[] };

/**
 * Server-side delivery helper. Call this before serializing a published artifact to a learner.
 * Do not send a source lesson JSON containing answer keys directly to a browser/mobile client.
 */
export function sanitizePublishedLessonForStudent(input: unknown): StudentSanitizationResult {
  const checked = validateStructuredLesson(input);
  if (!checked.ok) return checked;
  const lesson = checked.lesson;
  const status = String(lesson.workflow?.status || '').trim().toUpperCase();
  if (status !== 'PUBLISHED' || lesson.workflow?.publication_allowed !== true) {
    return { ok: false, lesson: null, issues: [{
      path: 'workflow',
      code: 'PUBLICATION_BLOCKED',
      message: 'Only explicitly published, publication-allowed lessons may be delivered to students.',
    }] };
  }
  const safeBlocks = lesson.explain.blocks.filter(block => {
    if (block.type === 'image') return isStudentRenderableAsset(block.asset);
    if (block.type === 'diagram') {
      const data = block.diagram_data || {};
      const renderable = (block as any).student_renderable ?? data.student_renderable;
      const review = String((block as any).review_status || data.review_status || '').toUpperCase();
      return renderable === true && ['APPROVED', 'APPROVED_FOR_STUDENT_RENDERING'].includes(review) && localized(block.alt_by_locale);
    }
    return true;
  });
  const safeQuestions = lesson.practice.questions
    .filter(question => question.type !== 'image_observation' || isQuestionVisualApproved(lesson, question))
    .map(question => {
      const {
        correct_answer_ids: _correctAnswerIds,
        correct_answer: _correctAnswer,
        correct_pairs: _correctPairs,
        accepted_answers: _acceptedAnswers,
        answer_guide_by_locale: _answerGuide,
        grading: _grading,
        ...safeQuestion
      } = question;
      return safeQuestion as StructuredQuestion;
    });
  return {
    ok: true,
    lesson: {
      ...lesson,
      workflow: { status: 'PUBLISHED', publication_allowed: true },
      explain: { ...lesson.explain, blocks: safeBlocks },
      practice: { ...lesson.practice, questions: safeQuestions, question_count: safeQuestions.length },
    },
    issues: [],
  };
}

export function findQuestionVisual(lesson: StructuredLesson, question: StructuredQuestion): StructuredExplainBlock | null {
  if (!question.visual_asset_ref) return null;
  return lesson.explain.blocks.find(block =>
    block.id === question.visual_asset_ref ||
    block.id === question.visual_asset_ref + '-block' ||
    block.asset?.asset_id === question.visual_asset_ref ||
    (Array.isArray(block.asset_refs) && block.asset_refs.includes(question.visual_asset_ref)) ||
    block.diagram_data?.visual_id === question.visual_asset_ref ||
    block.diagram_data?.diagram_id === question.visual_asset_ref ||
    block.visual_id === question.visual_asset_ref
  ) || null;
}

export function isQuestionVisualApproved(lesson: StructuredLesson, question: StructuredQuestion): boolean {
  if (question.type !== 'image_observation') return true;
  if (question.renderability?.status !== 'APPROVED_FOR_STUDENT_RENDERING') return false;
  const block = findQuestionVisual(lesson, question);
  if (!block) return false;
  if (block.type === 'image') return isStudentRenderableAsset(block.asset);
  if (block.type === 'diagram') {
    const data = block.diagram_data || {};
    const renderable = (block as any).student_renderable ?? data.student_renderable;
    const review = String((block as any).review_status || data.review_status || '').toUpperCase();
    return renderable === true && ['APPROVED', 'APPROVED_FOR_STUDENT_RENDERING'].includes(review) && localized(block.alt_by_locale);
  }
  return false;
}
