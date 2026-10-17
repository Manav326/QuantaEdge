'use client';

import { createContext } from 'react';

export type AdminLocale = 'hinglish' | 'english';
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
