'use client';

import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import Link from 'next/link';

export default function ParentRouteError({
  error,
  reset
}:{
  error:Error&{digest?:string};
  reset:()=>void;
}){
  return <main className="parent-app parent-error-page">
    <header className="parent-header">
      <QuantaEdgeBrand variant="compact" />
      <Link href="/login" className="text-link">Sign in</Link>
    </header>
    <section className="parent-dashboard">
      <span className="eyebrow">PARENT REPORT</span>
      <h1>This page couldn’t load</h1>
      <p>The parent page hit an unexpected error. Retry the page; if it repeats, the message below identifies where to investigate.</p>
      <div className="parent-error-detail">
        <strong>Error details</strong>
        <code>{error?.message||'No error message was provided.'}</code>
        {error?.digest&&<small>Reference: {error.digest}</small>}
      </div>
      <div className="parent-recovery-actions">
        <button className="button button-dark" onClick={()=>reset()}>Reload this page →</button>
        <Link href="/parent/children" className="button button-light">Manage children</Link>
        <Link href="/" className="text-link">Back to home</Link>
      </div>
    </section>
  </main>;
}
