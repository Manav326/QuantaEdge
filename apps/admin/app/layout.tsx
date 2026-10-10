import './globals.css';
import AdminSessionBoundary from './components/AdminSessionBoundary';
import AdminWorkspaceLayout from './components/AdminWorkspaceLayout';

export const metadata = {
  title: { default: 'QuantaEdge Admin', template: '%s | QuantaEdge' },
  description: 'QuantaEdge learning platform administration and curriculum operations.',
  applicationName: 'QuantaEdge',
  icons: { icon: '/branding/quantaedge-icon.png', apple: '/branding/quantaedge-icon.png' },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="en"><body><AdminSessionBoundary><AdminWorkspaceLayout>{children}</AdminWorkspaceLayout></AdminSessionBoundary></body></html>;
}
