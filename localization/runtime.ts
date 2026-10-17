import { uiCatalogs, uiLocaleMetadata } from './registry';

export type UiLocale = keyof typeof uiCatalogs;
export type MessageKey = keyof typeof uiCatalogs.english;
export type MessageValues = Record<string, string | number>;

type LocaleMetadata = {
  readonly label: string;
  readonly nativeLabel: string;
  readonly lang: string;
  readonly aliases: readonly string[];
  readonly fallback: string;
  readonly default: boolean;
};
const localeMetadata = uiLocaleMetadata as unknown as Record<string, LocaleMetadata>;

export const DEFAULT_UI_LOCALE: UiLocale = (
  Object.entries(localeMetadata).find(([, metadata]) => metadata.default)?.[0] ?? 'english'
) as UiLocale;

export const UI_LOCALE_OPTIONS = Object.keys(uiCatalogs).map((id) => ({
  id: id as UiLocale,
  label: localeMetadata[id]?.label ?? id,
  nativeLabel: localeMetadata[id]?.nativeLabel ?? id,
  lang: localeMetadata[id]?.lang ?? 'en',
}));

/** Returns a registered catalog ID, resolving legacy aliases such as Hindi -> Hinglish. */
export function resolveUiLocale(value: unknown): UiLocale | null {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (Object.prototype.hasOwnProperty.call(uiCatalogs, normalized)) return normalized as UiLocale;
  const alias = Object.entries(localeMetadata).find(([, metadata]) =>
    metadata.aliases.some((candidate) => candidate.trim().toLowerCase() === normalized),
  );
  return alias ? alias[0] as UiLocale : null;
}

export function normalizeUiLocale(value: unknown): UiLocale {
  return resolveUiLocale(value) ?? DEFAULT_UI_LOCALE;
}

export function getUiLocaleLanguage(value: unknown): string {
  return localeMetadata[normalizeUiLocale(value)]?.lang ?? 'en';
}

export function translateUi(localeValue: unknown, key: MessageKey | string, values: MessageValues = {}): string {
  const locale = normalizeUiLocale(localeValue);
  const primary = uiCatalogs[locale] as Record<string, string>;
  const fallbackLocale = (localeMetadata[locale]?.fallback ?? 'english') as UiLocale;
  const fallback = (uiCatalogs[fallbackLocale] ?? uiCatalogs.english) as Record<string, string>;
  const template = primary[key] ?? fallback[key] ?? key;
  return template.replace(/\{([a-zA-Z][a-zA-Z0-9_]*)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : match,
  );
}

export function hasUiTranslation(key: string, localeValue: unknown): boolean {
  const locale = normalizeUiLocale(localeValue);
  return Object.prototype.hasOwnProperty.call(uiCatalogs[locale], key);
}

export const UI_MESSAGE_KEYS = Object.keys(uiCatalogs[DEFAULT_UI_LOCALE]) as MessageKey[];
