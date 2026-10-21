'use client';

import { useCallback, useContext } from 'react';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import { translateUi, type MessageKey, type MessageValues } from './catalog';

export function useUiTranslation() {
  const { locale } = useContext(AdminWorkspaceContext);
  return useCallback((key: MessageKey | string, values: MessageValues = {}) =>
    translateUi(locale, key, values), [locale]);
}

export function UiText({ message, values }: { message: MessageKey | string; values?: MessageValues }) {
  const t = useUiTranslation();
  return <>{t(message, values)}</>;
}
