import './globals.css';

export const metadata = {
  title: 'QuantaEdge Learning',
  description: 'पढ़ाई, अब आपके बच्चे के हिसाब से।',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="hi"><body>{children}</body></html>;
}
