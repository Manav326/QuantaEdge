'use client';

import { createContext } from 'react';

export type AdminLocale = 'hinglish' | 'english';
export type AdminWorkspaceContextValue = {
  insideWorkspace: boolean;
  locale: AdminLocale;
  role: string;
  permissions: string[];
  displayName: string;
};

export const AdminWorkspaceContext = createContext<AdminWorkspaceContextValue>({
  insideWorkspace: false,
  locale: 'hinglish',
  role: '',
  permissions: [],
  displayName: '',
});
