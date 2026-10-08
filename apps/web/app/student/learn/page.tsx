import { Suspense } from 'react';
import LearnClient from './LearnClient';

export default function LearnPage() {
  return <Suspense fallback={<main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow">Loading lesson…</div></section></main>}>
    <LearnClient />
  </Suspense>;
}
