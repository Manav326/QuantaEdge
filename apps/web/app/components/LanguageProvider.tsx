'use client';


import { apiFetch } from './apiClient';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';
import { DEFAULT_UI_LOCALE, UI_LOCALE_OPTIONS, getUiLocaleLanguage, resolveUiLocale, translateUi, type UiLocale } from '../i18n/catalog';

export type Locale = UiLocale;
export type LearningLanguage = 'hi' | 'en';
type LocaleContextValue = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  learningLanguage: LearningLanguage;
  setLearningLanguage: (language: LearningLanguage) => void;
  certificateLanguage: LearningLanguage;
  setCertificateLanguage: (language: LearningLanguage) => void;
  /** True after stored preferences and (for students) the account language are resolved. */
  languageReady: boolean;
};
const STORAGE_KEY = 'qe-locale';
const LEARNING_STORAGE_KEY = 'qe-learning-language';
const CERTIFICATE_STORAGE_KEY = 'qe-certificate-language';

function toLocale(value: unknown): Locale | null {
  return resolveUiLocale(value);
}

function localeCookieValue(): Locale | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|;\s*)qe-locale=([^;]+)/);
  return match ? toLocale(decodeURIComponent(match[1])) : null;
}

function persistSharedLocale(next: Locale) {
  if (typeof window === 'undefined') return;
  const stored = next;
  try { window.localStorage.setItem(STORAGE_KEY, stored); } catch {}
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = STORAGE_KEY + '=' + stored + '; Path=/; Max-Age=31536000; SameSite=Lax' + secure;
}

function isStoredLocale(value: string | null): boolean {
  return Boolean(value && resolveUiLocale(value) === value);
}

const LocaleContext = createContext<LocaleContextValue | null>(null);

function normalizedLanguage(value: unknown): LearningLanguage | null {
  const language = String(value || '').trim().toLowerCase();
  if (language === 'en' || language === 'english') return 'en';
  if (language === 'hi' || language === 'hindi') return 'hi';
  return null;
}

export function LanguageProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const isStudentRoute = pathname?.startsWith('/student') ?? false;
  const isParentRoute = pathname?.startsWith('/parent') ?? false;
  const [locale, setLocaleState] = useState<Locale>(DEFAULT_UI_LOCALE);
  const [learningLanguage, setLearningLanguageState] = useState<LearningLanguage>('hi');
  const [certificateLanguage, setCertificateLanguageState] = useState<LearningLanguage>('hi');

  const applyLocale = useCallback((next: Locale, persist: boolean) => {
    setLocaleState(next);
    if (typeof document !== 'undefined') document.documentElement.lang = getUiLocaleLanguage(next);
    if (persist) persistSharedLocale(next);
  }, []);

  const applyLearningLanguage = useCallback((next: LearningLanguage, persist: boolean) => {
    setLearningLanguageState(next);
    if (persist && typeof window !== 'undefined') {
      try { window.localStorage.setItem(LEARNING_STORAGE_KEY, next); } catch {}
    }
  }, []);

  const applyCertificateLanguage = useCallback((next: LearningLanguage, persist: boolean) => {
    setCertificateLanguageState(next);
    if (persist && typeof window !== 'undefined') {
      try { window.localStorage.setItem(CERTIFICATE_STORAGE_KEY, next); } catch {}
    }
  }, []);

  // App/UI language is a device preference. It must never overwrite the account's
  // learning-content language through the student-language API.
  const setLocale = useCallback((next: Locale) => {
    applyLocale(next, true);
  }, [applyLocale]);

  // Learning language is a separate preference and is the only preference saved
  // through the account's learning-language endpoint.
  const setLearningLanguage = useCallback((next: LearningLanguage) => {
    applyLearningLanguage(next, true);
    if (isStudentRoute && typeof window !== 'undefined') {
      void apiFetch('/api/v1/students/me/language', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ language: next })
      }).catch(() => undefined);
    }
  }, [applyLearningLanguage, isStudentRoute]);

  const setCertificateLanguage = useCallback((next: LearningLanguage) => {
    applyCertificateLanguage(next, true);
  }, [applyCertificateLanguage]);

  const [languageReady, setLanguageReady] = useState(false);

  // Resolve the persisted UI/content preferences before language-sensitive tools
  // are allowed to create requests. On student pages, the account preference is
  // authoritative, so wait for it (or a failed/offline request) before marking ready.
  useEffect(() => {
    let active = true;
    setLanguageReady(false);
    let hasStoredLocale = false;
    try {
      const cookieLocale = localeCookieValue();
      const saved = window.localStorage.getItem(STORAGE_KEY);
      const resolved = cookieLocale ?? toLocale(saved);
      hasStoredLocale = Boolean(resolved);
      if (resolved) {
        applyLocale(resolved, false);
        // Normalize older localStorage-only values and make the preference available
        // to the other QuantaEdge apps running on the same host.
        if (!cookieLocale || !isStoredLocale(saved)) persistSharedLocale(resolved);
      }
      const savedLearning = normalizedLanguage(window.localStorage.getItem(LEARNING_STORAGE_KEY));
      if (savedLearning) applyLearningLanguage(savedLearning, false);
      const savedCertificate = normalizedLanguage(window.localStorage.getItem(CERTIFICATE_STORAGE_KEY));
      if (savedCertificate) applyCertificateLanguage(savedCertificate, false);
    } catch {}

    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY) {
        const next = toLocale(event.newValue);
        if (next) applyLocale(next, false);
      } else if (event.key === LEARNING_STORAGE_KEY) {
        const saved = normalizedLanguage(event.newValue);
        if (saved) applyLearningLanguage(saved, false);
      } else if (event.key === CERTIFICATE_STORAGE_KEY) {
        const saved = normalizedLanguage(event.newValue);
        if (saved) applyCertificateLanguage(saved, false);
      }
    };
    window.addEventListener('storage', onStorage);

    const resolveLanguage = async () => {
      const requests: Promise<void>[] = [];
      if (!hasStoredLocale && (isStudentRoute || isParentRoute)) {
        requests.push((async () => {
          try {
            const response = await apiFetch('/api/v1/account/me/profile', { cache: 'no-store', credentials: 'same-origin' });
            const profile = response.ok ? await response.json() : null;
            const accountLocale = resolveUiLocale(profile?.preferredLocale ?? profile?.preferred_locale);
            let localLocaleIsSet = false;
            try {
              localLocaleIsSet = Boolean(localeCookieValue() || toLocale(window.localStorage.getItem(STORAGE_KEY)));
            } catch {}
            if (active && accountLocale && !localLocaleIsSet) applyLocale(accountLocale, true);
          } catch {
            // Keep the default locale when the account preference is unavailable.
          }
        })());
      }
      if (isStudentRoute) {
        requests.push((async () => {
          try {
            const response = await apiFetch('/api/v1/students/me/profile', { cache: 'no-store', credentials: 'same-origin' });
            const profile = response.ok ? await response.json() : null;
            if (active && profile) {
              const savedLanguage = normalizedLanguage(profile.learningLanguage ?? profile.learning_language ?? profile.language);
              if (savedLanguage) applyLearningLanguage(savedLanguage, true);
            }
          } catch {
            // Keep the resolved local learning language when offline or unauthenticated.
          }
        })());
      }
      await Promise.all(requests);
      if (active) setLanguageReady(true);
    };
    void resolveLanguage();

    return () => {
      active = false;
      window.removeEventListener('storage', onStorage);
    };
  }, [applyLocale, applyLearningLanguage, applyCertificateLanguage, isStudentRoute, isParentRoute]);

  const value = useMemo(() => ({
    locale, setLocale, learningLanguage, setLearningLanguage, certificateLanguage, setCertificateLanguage, languageReady
  }), [locale, setLocale, learningLanguage, setLearningLanguage, certificateLanguage, setCertificateLanguage, languageReady]);
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

export function LanguageSwitcher({ compact = false, menu = false }: { compact?: boolean; menu?: boolean }) {
  const { locale, setLocale } = useLocale();
  return (
    <label className={'qe-language-switcher' + (compact ? ' is-compact' : '') + (menu ? ' qe-language-switcher--menu-setting' : '')}>
      <span aria-hidden="true">文</span>
      <select value={locale} onChange={event => setLocale(event.target.value as Locale)}
        aria-label={translateUi(locale, 'common.language.label')}>
        {UI_LOCALE_OPTIONS.map(option => <option key={option.id} value={option.id}>{option.nativeLabel}</option>)}
      </select>
    </label>
  );
}

export function LearningLanguageSwitcher({ compact = false, menu = false }: { compact?: boolean; menu?: boolean }) {
  const { locale, learningLanguage, setLearningLanguage } = useLocale();
  return (
    <label className={'qe-language-switcher qe-learning-language-switcher' + (compact ? ' is-compact' : '') + (menu ? ' qe-language-switcher--menu-setting' : '')}>
      <span aria-hidden="true">📚</span>
      <span className="qe-language-switcher__label">
        {translateUi(locale, 'common.learningLanguage.label')}
      </span>
      <select value={learningLanguage} onChange={event => setLearningLanguage(event.target.value as LearningLanguage)}
        aria-label={translateUi(locale, 'common.learningLanguage.label')}>
        <option value="hi">{translateUi(locale, 'common.language.hinglish')}</option>
        <option value="en">{translateUi(locale, 'common.language.english')}</option>
      </select>
      <small className="qe-language-switcher__help">
        {translateUi(locale, 'common.learningLanguage.changeHelp')}
      </small>
    </label>
  );
}
