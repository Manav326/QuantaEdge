import { Suspense } from 'react';
import PracticeClient from './PracticeClient';

export default function PracticePage() {
  return <Suspense fallback={<main className="practice-page"><section className="practice-wrap"><div className="eyebrow">Loading topic practice…</div></section></main>}>
    <PracticeClient />
  </Suspense>;
}
