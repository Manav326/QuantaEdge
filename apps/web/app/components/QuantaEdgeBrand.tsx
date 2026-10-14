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
      <span className="qe-brand__lockup">
        <img className="qe-brand__icon" src="/branding/quantaedge-icon.png" alt="" width={192} height={192} aria-hidden="true" />
        <span className="qe-brand__copy">
          <span className="qe-brand__name" aria-hidden="true">
            <span className="qe-brand__name-quant">Quanta</span><span className="qe-brand__name-edge">Edge</span>
          </span>
          <span className="qe-brand__tagline">Smarter Decisions. Greater Growth.</span>
        </span>
      </span>
    </span>
  );

  if (href === null) {
    return <div className={`qe-brand-static ${className}`.trim()} role="img" aria-label={BRAND_LABEL}>{lockup}</div>;
  }

  return <Link href={href} className={`qe-brand-link ${className}`.trim()} aria-label={BRAND_LABEL}>{lockup}</Link>;
}