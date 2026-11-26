import { Suspense } from 'react';
import { RouteLoadingSkeleton } from '../../components/RouteLoadingSkeleton';
import TextbooksClient from './TextbooksClient';

export default function TextbooksPage() {
  return <Suspense fallback={<RouteLoadingSkeleton description="Loading textbook library…" variant="list" />}>
    <TextbooksClient />
  </Suspense>;
}
