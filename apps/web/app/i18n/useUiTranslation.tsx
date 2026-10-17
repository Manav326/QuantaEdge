'use client';

import { useCallback } from 'react';
import { useLocale } from '../components/LanguageProvider';
import { translateUi, type MessageKey, type MessageValues } from './catalog';

export function useUiTranslation() {
  const { locale } = useLocale();
  return useCallback((key: MessageKey | string, values: MessageValues = {}) =>
    translateUi(locale === 'english' ? 'english' : 'hinglish', key, values), [locale]);
}

export function UiText({ message, values }: { message: MessageKey | string; values?: MessageValues }) {
  const t = useUiTranslation();
  return <>{t(message, values)}</>;
}
