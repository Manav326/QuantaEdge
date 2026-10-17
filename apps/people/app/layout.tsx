import './globals.css';
import './people-design-system.css';
import PeopleWorkspaceLayout from './components/PeopleWorkspaceLayout';
import PeopleSessionBoundary from './components/PeopleSessionBoundary';
import { UiLocaleProvider } from './i18n/UiLocaleProvider';

export const metadata = {
  title: { default: 'QuantaEdge People', template: '%s | QuantaEdge People' },
  description: 'People Operations for QuantaEdge.',
  applicationName: 'QuantaEdge People',
  icons: {
    icon: [{ url: '/branding/quantaedge-icon.png', type: 'image/png' }],
    shortcut: '/branding/quantaedge-icon.png',
    apple: '/branding/quantaedge-icon.png',
  },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="hi">
      <body>
        <UiLocaleProvider>
          <PeopleSessionBoundary><PeopleWorkspaceLayout>{children}</PeopleWorkspaceLayout></PeopleSessionBoundary>
        </UiLocaleProvider>
      </body>
    </html>
  );
}
