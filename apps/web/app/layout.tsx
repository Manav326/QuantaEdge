import './globals.css';
import { LanguageProvider } from './components/LanguageProvider';
import SessionBoundary from './components/SessionBoundary';
import WorkspaceLayout from './components/WorkspaceLayout';

export const metadata = {
  title: { default: 'QuantaEdge | Smarter Decisions. Greater Growth.', template: '%s | QuantaEdge' },
  description: 'AI-powered learning for Hindi-medium students, starting with Bihar Board Classes 6–8. Smarter Decisions. Greater Growth.',
  applicationName: 'QuantaEdge',
  icons: { icon: '/branding/quantaedge-icon.png', apple: '/branding/quantaedge-icon.png' },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="hi"><body><LanguageProvider><SessionBoundary><WorkspaceLayout>{children}</WorkspaceLayout></SessionBoundary></LanguageProvider></body></html>;
}
