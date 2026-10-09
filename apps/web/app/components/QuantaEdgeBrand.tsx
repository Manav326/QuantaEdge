import Link from 'next/link';

type BrandVariant = 'header' | 'compact' | 'footer' | 'auth';
type QuantaEdgeBrandProps = {
  href?: string | null;
  variant?: BrandVariant;
  className?: string;
};

const BRAND_LABEL = 'QuantaEdge — Smarter Decisions. Greater Growth.';

export default function QuantaEdgeBrand({
  href = '/',
  variant = 'header',
  className = '',
}: QuantaEdgeBrandProps) {
  const lockup = (
    <span className={`qe-brand qe-brand--${variant}`}>
      <img className="qe-brand__mark" src="/branding/quantaedge-icon.png" alt="" width={48} height={48} aria-hidden="true" />
      <span className="qe-brand__copy">
        <span className="qe-brand__wordmark" aria-hidden="true">
          <span className="qe-brand__quanta">Quanta</span>
          <span className="qe-brand__edge">Edge</span>
        </span>
        <span className="qe-brand__tagline">Smarter Decisions. Greater Growth.</span>
      </span>
    </span>
  );

  if (href === null) {
    return <div className={`qe-brand-static ${className}`.trim()} role="img" aria-label={BRAND_LABEL}>{lockup}</div>;
  }

  return <Link href={href} className={`qe-brand-link ${className}`.trim()} aria-label={BRAND_LABEL}>{lockup}</Link>;
}