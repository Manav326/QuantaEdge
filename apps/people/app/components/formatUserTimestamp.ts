/**
 * Full human-readable timestamp in the user's local timezone.
 * Example: "Sat 17 Oct 2026, 3:30 PM".
 */
export function formatUserTimestamp(value?: string | number | Date | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) return '—';
  const parts = new Intl.DateTimeFormat('en-IN', {
    weekday: 'short', day: '2-digit', month: 'short', year: 'numeric',
  }).formatToParts(date);
  const read = (type: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === type)?.value ?? '';
  const formattedTime = new Intl.DateTimeFormat('en-IN', {
    hour: 'numeric', minute: '2-digit', hour12: true,
  }).format(date);
  const time = formattedTime.replace(/\b(am|pm)\b/i, period => period.toUpperCase());
  return `${read('weekday')} ${read('day')} ${read('month')} ${read('year')}, ${time}`;
}
