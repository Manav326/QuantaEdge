'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { useAdminTenantTheme } from './AdminThemeProvider';

type BrandVariant = 'header' | 'compact' | 'footer' | 'auth';
type QuantaEdgeBrandProps = {
  href?: string | null;
  variant?: BrandVariant;
  className?: string;
};

const PLATFORM_LOGO_URL = '/branding/quantaedge-logo.svg';
const PLATFORM_ICON_URL = '/branding/quantaedge-icon.png';
const PLATFORM_BRAND_LABEL = 'QuantaEdge — Smarter Decisions. Greater Growth.';

export default function QuantaEdgeBrand({
  href = '/',
  variant = 'header',
  className = '',
}: QuantaEdgeBrandProps) {
  const { brand, institutionCode, isResolving } = useAdminTenantTheme();
  const tenantContext = Boolean(institutionCode);
  const brandLabel = tenantContext
    ? [brand?.displayName || (isResolving ? 'Institution identity loading' : 'Institution'), brand?.tagline].filter(Boolean).join(' — ')
    : PLATFORM_BRAND_LABEL;

  let lockup: ReactNode;
  if (tenantContext) {
    const displayName = brand?.displayName || (isResolving ? 'Institution' : 'Institution');
    const tagline = brand?.tagline?.trim();
    const mark = displayName.slice(0, 1).toUpperCase() || '•';
    lockup = variant !== 'compact'
      ? <span className={'qe-brand qe-brand--' + variant}>
          <span className="qe-brand__lockup qe-brand__full-lockup">
            {brand?.logoUrl
              ? <img className="qe-brand__logo qe-brand__tenant-logo" src={brand.logoUrl} alt="" aria-hidden="true" />
              : <span className="qe-brand__tenant-lockup">
                  <span className="qe-brand__tenant-fallback" aria-hidden="true">{mark}</span>
                  <span className="qe-brand__copy">
                    <span className="qe-brand__name">{displayName}</span>
                    {tagline ? <span className="qe-brand__tagline">{tagline}</span> : null}
                  </span>
                </span>}
          </span>
        </span>
      : <span className={'qe-brand qe-brand--' + variant}>
          <span className="qe-brand__lockup qe-brand__tenant-lockup">
            {brand?.appIconUrl
              ? <img className="qe-brand__icon" src={brand.appIconUrl} alt="" width={192} height={192} aria-hidden="true" />
              : <span className="qe-brand__tenant-icon-fallback" aria-hidden="true">{mark}</span>}
            <span className="qe-brand__copy">
              <span className="qe-brand__name">{displayName}</span>
              {tagline ? <span className="qe-brand__tagline">{tagline}</span> : null}
            </span>
          </span>
        </span>;
  } else {
    lockup = variant !== 'compact'
      ? <span className={'qe-brand qe-brand--' + variant}>
          <span className="qe-brand__lockup qe-brand__full-lockup">
            <img className="qe-brand__logo" src={PLATFORM_LOGO_URL} alt="" aria-hidden="true" />
          </span>
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
  }

  if (href === null) {
    return <div className={'qe-brand-static ' + className} role="img" aria-label={brandLabel}>{lockup}</div>;
  }
  return <Link href={href} className={'qe-brand-link ' + className} aria-label={brandLabel}>{lockup}</Link>;
}
