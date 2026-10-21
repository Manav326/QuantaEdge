import english from './catalogs/english.json';
import hinglish from './catalogs/hinglish.json';
import locales from './locales.json';

export const uiCatalogs = {
  'english': english,
  'hinglish': hinglish,
} as const;

export const uiLocaleMetadata = locales as {
  readonly [locale: string]: {
    readonly label: string;
    readonly nativeLabel: string;
    readonly lang: string;
    readonly aliases: readonly string[];
    readonly fallback: string;
    readonly default: boolean;
  };
};
