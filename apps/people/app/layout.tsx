import './globals.css';
import GlobalNotificationHost from './components/GlobalNotificationHost';
import './people-design-system.css';
import './people-appearance.css';
import PeopleWorkspaceLayout from './components/PeopleWorkspaceLayout';
import { PeopleAppearanceProvider } from './components/PeopleAppearanceProvider';
import PeopleSessionBoundary from './components/PeopleSessionBoundary';
import { UiLocaleProvider } from './i18n/UiLocaleProvider';

export const metadata = {
  title: { default: 'QuantaEdge', template: '%s | QuantaEdge' },
  description: 'People Operations for QuantaEdge.',
  applicationName: 'QuantaEdge',
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
        <GlobalNotificationHost workspace="people" />
        <UiLocaleProvider>
          <PeopleAppearanceProvider>
            <PeopleSessionBoundary><PeopleWorkspaceLayout>{children}</PeopleWorkspaceLayout></PeopleSessionBoundary>
          </PeopleAppearanceProvider>
        </UiLocaleProvider>
      </body>
    </html>
  );
}
