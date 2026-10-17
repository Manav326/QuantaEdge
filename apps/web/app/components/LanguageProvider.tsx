'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';
import { translateUi } from '../i18n/catalog';

export type Locale = 'hindi' | 'english';
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
  const normalized = String(value ?? '').trim().toLowerCase();
  if (normalized === 'english' || normalized === 'en') return 'english';
  if (normalized === 'hinglish' || normalized === 'hindi' || normalized === 'hi') return 'hindi';
  return null;
}

function localeCookieValue(): Locale | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|;\\s*)qe-locale=([^;]+)/);
  return match ? toLocale(decodeURIComponent(match[1])) : null;
}

function persistSharedLocale(next: Locale) {
  if (typeof window === 'undefined') return;
  const stored = next === 'english' ? 'english' : 'hinglish';
  try { window.localStorage.setItem(STORAGE_KEY, stored); } catch {}
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = STORAGE_KEY + '=' + stored + '; Path=/; Max-Age=31536000; SameSite=Lax' + secure;
}

function isStoredLocale(value: string | null): boolean {
  return value === 'english' || value === 'hinglish' || value === 'hindi';
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
  const [locale, setLocaleState] = useState<Locale>('hindi');
  const [learningLanguage, setLearningLanguageState] = useState<LearningLanguage>('hi');
  const [certificateLanguage, setCertificateLanguageState] = useState<LearningLanguage>('hi');

  const applyLocale = useCallback((next: Locale, persist: boolean) => {
    setLocaleState(next);
    if (typeof document !== 'undefined') document.documentElement.lang = next === 'hindi' ? 'hi' : 'en';
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
      void fetch('/api/v1/students/me/language', {
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
    try {
      const cookieLocale = localeCookieValue();
      const saved = window.localStorage.getItem(STORAGE_KEY);
      const resolved = cookieLocale ?? toLocale(saved);
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
      if (isStudentRoute) {
        try {
          const response = await fetch('/api/v1/students/me/profile', { cache: 'no-store', credentials: 'same-origin' });
          const profile = response.ok ? await response.json() : null;
          if (active && profile) {
            const savedLanguage = normalizedLanguage(profile.learningLanguage ?? profile.learning_language ?? profile.language);
            if (savedLanguage) applyLearningLanguage(savedLanguage, true);
          }
        } catch {
          // The resolved local preference remains the offline fallback.
        }
      }
      if (active) setLanguageReady(true);
    };
    void resolveLanguage();

    return () => {
      active = false;
      window.removeEventListener('storage', onStorage);
    };
  }, [applyLocale, applyLearningLanguage, applyCertificateLanguage, isStudentRoute]);

  useEffect(() => {
    if (!isParentRoute || isStudentRoute) return;
    let active = true;
    const localLocaleIsSet = () => {
      try {
        return Boolean(localeCookieValue() || toLocale(window.localStorage.getItem(STORAGE_KEY)));
      } catch {
        return false;
      }
    };
    // An explicit interface-language choice on this device must not be overwritten
    // by the parent's older profile preference during navigation or reload.
    if (localLocaleIsSet()) return;
    fetch('/api/v1/guardians/profile', { cache: 'no-store', credentials: 'same-origin' })
      .then(response => response.ok ? response.json() : null)
      .then(profile => {
        if (!active || !profile || localLocaleIsSet()) return;
        const preference = String(profile.preferred_language || '');
        if (preference === 'English') applyLocale('english', true);
        else if (preference === 'Hindi' || preference === 'Hindi & English') applyLocale('hindi', true);
      })
      .catch(() => undefined);
    return () => { active = false; };
  }, [isParentRoute, isStudentRoute, applyLocale]);

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
      <span className="qe-language-switcher__label">
        {translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.label')}
      </span>
      <select value={locale} onChange={event => setLocale(event.target.value as Locale)}
        aria-label={translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.label')}>
        <option value="hindi">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.hinglish')}</option>
        <option value="english">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.english')}</option>
      </select>
      <small className="qe-language-switcher__help">
        {translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.changeHelp')}
      </small>
    </label>
  );
}

export function LearningLanguageSwitcher({ compact = false, menu = false }: { compact?: boolean; menu?: boolean }) {
  const { locale, learningLanguage, setLearningLanguage } = useLocale();
  return (
    <label className={'qe-language-switcher qe-learning-language-switcher' + (compact ? ' is-compact' : '') + (menu ? ' qe-language-switcher--menu-setting' : '')}>
      <span aria-hidden="true">📚</span>
      <span className="qe-language-switcher__label">
        {translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.learningLanguage.label')}
      </span>
      <select value={learningLanguage} onChange={event => setLearningLanguage(event.target.value as LearningLanguage)}
        aria-label={translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.learningLanguage.label')}>
        <option value="hi">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.hinglish')}</option>
        <option value="en">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.language.english')}</option>
      </select>
      <small className="qe-language-switcher__help">
        {translateUi(locale === 'english' ? 'english' : 'hinglish', 'common.learningLanguage.changeHelp')}
      </small>
    </label>
  );
}
