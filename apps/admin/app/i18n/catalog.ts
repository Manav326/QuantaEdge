import english from './catalogs/english.json';
import hinglish from './catalogs/hinglish.json';

export type UiLocale = 'english' | 'hinglish';
export type MessageKey = keyof typeof english;
export type MessageValues = Record<string, string | number>;

export function normalizeUiLocale(value: unknown): UiLocale {
  const locale = String(value ?? '').trim().toLowerCase();
  return locale === 'english' || locale === 'en' ? 'english' : 'hinglish';
}

export function translateUi(localeValue: unknown, key: MessageKey | string, values: MessageValues = {}): string {
  const locale = normalizeUiLocale(localeValue);
  const primary = (locale === 'english' ? english : hinglish) as Record<string, string>;
  const fallback = english as Record<string, string>;
  const template = primary[key] ?? fallback[key] ?? key;
  return template.replace(/\{([a-zA-Z][a-zA-Z0-9_]*)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : match,
  );
}

export function hasUiTranslation(key: string, localeValue: unknown): boolean {
  const locale = normalizeUiLocale(localeValue);
  return Object.prototype.hasOwnProperty.call(locale === 'english' ? english : hinglish, key);
}

export const UI_MESSAGE_KEYS = Object.keys(english) as MessageKey[];
