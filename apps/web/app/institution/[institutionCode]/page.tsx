'use client';

import { useEffect } from 'react';
import { useParams } from 'next/navigation';

/**
 * Public school invitation route.
 * The server sees only the institution code in the path; the private join token stays in the URL
 * fragment until client-side enrollment, so it is not sent in the initial HTTP request.
 */
export default function InstitutionLearnerEntryPage() {
  const params = useParams<{ institutionCode: string }>();
  const institutionCode = String(params.institutionCode || '').trim().toUpperCase();

  useEffect(() => {
    if (!/^[A-Z0-9][A-Z0-9_-]{2,31}$/.test(institutionCode)) {
      window.location.replace('/');
      return;
    }
    const query = new URLSearchParams(window.location.search);
    query.set('institution', institutionCode);
    window.location.replace('/?' + query.toString() + window.location.hash);
  }, [institutionCode]);

  return (
    <main className="qe-tenant-landing" aria-busy="true">
      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow">YOUR SCHOOL LEARNING SPACE</div>
          <h1>Opening your<br /><em>school learning space</em></h1>
          <p className="hero-lead">Preparing your school’s learning experience…</p>
        </div>
      </section>
    </main>
  );
}
