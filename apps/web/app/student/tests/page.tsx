import { Suspense } from 'react';
import TestsClient from './TestsClient';

export default function StudentTestsPage(){
  return <Suspense fallback={<main className="app-shell"><section style={{padding:32}}>Loading tests and saved results…</section></main>}>
    <TestsClient />
  </Suspense>;
}
