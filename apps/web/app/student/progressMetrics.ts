export type MetricNumber = number | null;

function numeric(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value === 'string' && !value.trim()) return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function safeCount(value: unknown): MetricNumber {
  const parsed = numeric(value);
  return parsed !== null && parsed >= 0 ? Math.floor(parsed) : null;
}

export function safePercent(value: unknown): MetricNumber {
  const parsed = numeric(value);
  return parsed !== null && parsed >= 0 && parsed <= 100 ? Math.round(parsed) : null;
}

function firstCount(values: unknown[]): MetricNumber {
  for (const value of values) {
    const count = safeCount(value);
    if (count !== null) return count;
  }
  return null;
}

export function ratioPercent(completedValue: unknown, totalValue: unknown): MetricNumber {
  const completed = safeCount(completedValue);
  const total = safeCount(totalValue);
  if (completed === null || total === null || total <= 0) return null;
  return Math.round(Math.min(completed, total) / total * 100);
}

export function lessonProgress(stats: any) {
  const completed = firstCount([stats?.complete_lessons, stats?.completed_lessons, stats?.completedLessons]);
  const total = firstCount([stats?.total_lessons, stats?.totalLessons]);
  const calculatedPercent = ratioPercent(completed, total);
  const providedPercent = total === 0 || (completed === null && total !== null)
    ? null
    : safePercent(stats?.completion_percent);
  return { completed, total, percent: calculatedPercent ?? providedPercent };
}

export function curriculumProgress(item: any) {
  const completed = firstCount([item?.complete, item?.complete_lessons, item?.completed_lessons, item?.completed]);
  const total = firstCount([item?.lessons, item?.total_lessons, item?.totalLessons]);
  const calculatedPercent = ratioPercent(completed, total);
  const providedPercent = total === 0 || (completed === null && total !== null)
    ? null
    : safePercent(item?.completion_percent);
  return { completed, total, percent: calculatedPercent ?? providedPercent };
}

export function displayCount(value: MetricNumber): string {
  return value === null ? '—' : String(value);
}

export function displayPercent(value: MetricNumber): string {
  return value === null ? '—' : value + '%';
}
