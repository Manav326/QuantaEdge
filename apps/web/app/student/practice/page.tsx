import { Suspense } from 'react';
import { RouteLoadingSkeleton } from '../../components/RouteLoadingSkeleton';
import PracticeClient from './PracticeClient';

export default function PracticePage() {
  return <Suspense fallback={<RouteLoadingSkeleton description="Loading topic practice…" variant="practice" />}>
    <PracticeClient />
  </Suspense>;
}
