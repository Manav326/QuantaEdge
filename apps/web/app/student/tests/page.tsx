import { Suspense } from 'react';
import { RouteLoadingSkeleton } from '../../components/RouteLoadingSkeleton';
import TestsClient from './TestsClient';

export default function StudentTestsPage(){
  return <Suspense fallback={<RouteLoadingSkeleton description="Loading tests and saved results…" variant="practice" />}>
    <TestsClient />
  </Suspense>;
}
