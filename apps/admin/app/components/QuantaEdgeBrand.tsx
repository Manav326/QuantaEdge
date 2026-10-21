import Link from 'next/link';

type BrandVariant = 'header' | 'compact' | 'footer' | 'auth';
type QuantaEdgeBrandProps = {
  href?: string | null;
  variant?: BrandVariant;
  className?: string;
};

const PLATFORM_LOGO_URL = '/branding/quantaedge-logo.svg';
const PLATFORM_ICON_URL = '/branding/quantaedge-icon.png';
const BRAND_LABEL = 'QuantaEdge — Smarter Decisions. Greater Growth.';

export default function QuantaEdgeBrand({
  href = '/',
  variant = 'header',
  className = '',
}: QuantaEdgeBrandProps) {
  const lockup = variant !== 'compact'
    ? <span className={'qe-brand qe-brand--' + variant}>
        <img className="qe-brand__logo" src={PLATFORM_LOGO_URL} alt="" aria-hidden="true" />
      </span>
    : <span className={'qe-brand qe-brand--' + variant}>
        <span className="qe-brand__lockup">
          <img className="qe-brand__icon" src={PLATFORM_ICON_URL} alt="" width={192} height={192} aria-hidden="true" />
          <span className="qe-brand__copy">
            <span className="qe-brand__name" aria-hidden="true">
              <span className="qe-brand__name-quant">Quanta</span><span className="qe-brand__name-edge">Edge</span>
            </span>
            <span className="qe-brand__tagline">Smarter Decisions. Greater Growth.</span>
          </span>
        </span>
      </span>;

  if (href === null) {
    return <div className={'qe-brand-static ' + className} role="img" aria-label={BRAND_LABEL}>{lockup}</div>;
  }
  return <Link href={href} className={'qe-brand-link ' + className} aria-label={BRAND_LABEL}>{lockup}</Link>;
}
