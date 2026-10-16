'use client';

import { useParams } from 'next/navigation';
import StructuredLessonDetailClient from '../StructuredLessonDetailClient';

export default function StructuredLessonPage() {
  const params = useParams<{ artifactId: string }>();
  return <StructuredLessonDetailClient artifactId={String(params.artifactId || '')} />;
}
