import './globals.css';
import './admin-theme.css';
import GlobalNotificationHost from './components/GlobalNotificationHost';
import AdminSessionBoundary from './components/AdminSessionBoundary';
import AdminWorkspaceLayout from './components/AdminWorkspaceLayout';

export const metadata = {
  title: { default: 'QuantaEdge Admin', template: '%s | QuantaEdge' },
  description: 'QuantaEdge learning platform administration and course content management.',
  applicationName: 'QuantaEdge',
  icons: {
    icon: [
      { url: '/favicon.svg', type: 'image/svg+xml' },
      { url: '/branding/quantaedge-icon.png', type: 'image/png' },
    ],
    shortcut: '/favicon.svg',
    apple: '/branding/quantaedge-icon.png',
  },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="en"><body><GlobalNotificationHost /><AdminSessionBoundary><AdminWorkspaceLayout>{children}</AdminWorkspaceLayout></AdminSessionBoundary></body></html>;
}
