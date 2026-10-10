'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

export type Locale = 'hinglish' | 'english';
type LocaleContextValue = { locale: Locale; setLocale: (locale: Locale) => void };
const STORAGE_KEY = 'qe-locale';
const LocaleContext = createContext<LocaleContextValue | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>('hinglish');

  const applyLocale = useCallback((next: Locale, persist: boolean) => {
    setLocaleState(next);
    if (typeof document !== 'undefined') document.documentElement.lang = next === 'hinglish' ? 'hi' : 'en';
    if (persist && typeof window !== 'undefined') {
      try { window.localStorage.setItem(STORAGE_KEY, next); } catch {}
    }
  }, []);

  const setLocale = useCallback((next: Locale) => applyLocale(next, true), [applyLocale]);

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(STORAGE_KEY);
      if (saved === 'hinglish' || saved === 'english') applyLocale(saved, false);
    } catch {}
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY && (event.newValue === 'hinglish' || event.newValue === 'english')) {
        applyLocale(event.newValue, false);
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [applyLocale]);

  const value = useMemo(() => ({ locale, setLocale }), [locale, setLocale]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale() {
  const context = useContext(LocaleContext);
  if (!context) throw new Error('useLocale must be used inside LanguageProvider');
  return context;
}

export function LocaleText({ hinglish, english }: { hinglish: string; english: string }) {
  const { locale } = useLocale();
  return <>{locale === 'english' ? english : hinglish}</>;
}

export function LanguageSwitcher({ compact = false }: { compact?: boolean }) {
  const { locale, setLocale } = useLocale();
  return (
    <label className={'qe-language-switcher' + (compact ? ' is-compact' : '')}>
      <span aria-hidden="true">文</span>
      <span className="qe-language-switcher__label">भाषा / Language</span>
      <select
        value={locale}
        onChange={event => setLocale(event.target.value as Locale)}
        aria-label="भाषा चुनें / Choose language"
      >
        <option value="hinglish">Hinglish (हिन्दी + English)</option>
        <option value="english">English</option>
      </select>
    </label>
  );
}
