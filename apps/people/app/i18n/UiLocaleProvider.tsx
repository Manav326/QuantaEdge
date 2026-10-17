'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { translateUi, type MessageKey, type MessageValues, type UiLocale } from './catalog';

type UiLocaleContextValue = {
  locale: UiLocale;
  setLocale: (locale: UiLocale) => void;
  t: (key: MessageKey | string, values?: MessageValues) => string;
};
const UiLocaleContext = createContext<UiLocaleContextValue | null>(null);
const STORAGE_KEY = 'qe-locale';

function normalize(value: unknown): UiLocale | null {
  const raw = String(value ?? '').trim().toLowerCase();
  if (raw === 'english' || raw === 'en') return 'english';
  if (raw === 'hinglish' || raw === 'hindi' || raw === 'hi') return 'hinglish';
  return null;
}
function cookieLocale(): UiLocale | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|;\\s*)qe-locale=([^;]+)/);
  return match ? normalize(decodeURIComponent(match[1])) : null;
}
function persist(locale: UiLocale) {
  if (typeof window === 'undefined') return;
  try { window.localStorage.setItem(STORAGE_KEY, locale); } catch {}
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = STORAGE_KEY + '=' + locale + '; Path=/; Max-Age=31536000; SameSite=Lax' + secure;
}

export function UiLocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<UiLocale>('hinglish');
  const setLocale = useCallback((next: UiLocale) => {
    setLocaleState(next);
    document.documentElement.lang = next === 'english' ? 'en' : 'hi';
    persist(next);
  }, []);

  useEffect(() => {
    const shared = cookieLocale();
    let local: UiLocale | null = null;
    try { local = normalize(window.localStorage.getItem(STORAGE_KEY)); } catch {}
    const resolved = shared ?? local;
    if (resolved) {
      setLocaleState(resolved);
      document.documentElement.lang = resolved === 'english' ? 'en' : 'hi';
      if (!shared || !local) persist(resolved);
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key !== STORAGE_KEY) return;
      const next = normalize(event.newValue);
      if (next) {
        setLocaleState(next);
        document.documentElement.lang = next === 'english' ? 'en' : 'hi';
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const t = useCallback((key: MessageKey | string, values: MessageValues = {}) => translateUi(locale, key, values), [locale]);
  const value = useMemo(() => ({ locale, setLocale, t }), [locale, setLocale, t]);
  return <UiLocaleContext.Provider value={value}>{children}</UiLocaleContext.Provider>;
}

export function useUiTranslation() {
  const context = useContext(UiLocaleContext);
  if (!context) throw new Error('useUiTranslation must be used inside UiLocaleProvider');
  return context;
}
