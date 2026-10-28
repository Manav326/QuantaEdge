import { Suspense } from 'react';
import { RouteLoadingSkeleton } from '../../components/RouteLoadingSkeleton';
import LearnClient from './LearnClient';

export default function LearnPage() {
  return <Suspense fallback={<RouteLoadingSkeleton description="Loading lesson…" variant="lesson" />}>
    <LearnClient />
  </Suspense>;
}
