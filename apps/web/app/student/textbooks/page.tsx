import { Suspense } from 'react';
import TextbooksClient from './TextbooksClient';

export default function TextbooksPage() {
  return <Suspense fallback={<main className="app-shell qe-textbook-shell"><section className="qe-textbook-loading">Loading textbook library…</section></main>}>
    <TextbooksClient />
  </Suspense>;
}
