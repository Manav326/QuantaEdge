'use client';

import { createContext } from 'react';
import type { UiLocale } from '../i18n/catalog';

export type AdminLocale = UiLocale;
export type AdminWorkspaceContextValue = {
  insideWorkspace: boolean;
  locale: AdminLocale;
  setLocale: (locale: AdminLocale) => void;
  role: string;
  permissions: string[];
  displayName: string;
};

export const AdminWorkspaceContext = createContext<AdminWorkspaceContextValue>({
  insideWorkspace: false,
  locale: 'hinglish',
  setLocale: () => undefined,
  role: '',
  permissions: [],
  displayName: '',
});
