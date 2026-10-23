import './globals.css';
import GlobalNotificationHost from './components/GlobalNotificationHost';
import { LanguageProvider } from './components/LanguageProvider';
import SessionBoundary from './components/SessionBoundary';
import WorkspaceLayout from './components/WorkspaceLayout';
import { TenantBrandingProvider } from './components/TenantBrandingProvider';
import { ColorThemeProvider } from './components/ColorThemeProvider';

export const metadata = {
  title: { default: 'QuantaEdge | Smarter Decisions. Greater Growth.', template: '%s | QuantaEdge' },
  description: 'Quality learning for different ages, subjects and goals, with guided practice, topic-aware learning support and meaningful progress.',
  applicationName: 'QuantaEdge',
  icons: {
    icon: [
      { url: '/favicon.svg', type: 'image/svg+xml' },
      { url: '/branding/quantaedge-icon.png', type: 'image/png' },
    ],
    shortcut: '/branding/quantaedge-icon.png',
    apple: '/branding/quantaedge-icon.png',
  },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="hi"><body><GlobalNotificationHost /><LanguageProvider><TenantBrandingProvider><ColorThemeProvider><SessionBoundary><WorkspaceLayout>{children}</WorkspaceLayout></SessionBoundary></ColorThemeProvider></TenantBrandingProvider></LanguageProvider></body></html>;
}
