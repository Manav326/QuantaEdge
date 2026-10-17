'use client';

import Link from 'next/link';
import { useTenantBranding } from './TenantBrandingProvider';

function approvedLogoUrl(url: string | null | undefined, code: string | undefined): url is string {
  if (!url || !code) return false;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/logo?v=';
  if (!url.startsWith(prefix)) return false;
  return /^\d+$/.test(url.slice(prefix.length));
}

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
  const { brand, isResolving, institutionContextRequested } = useTenantBranding();
  const tenantName = brand && brand.displayName ? brand.displayName.trim() : '';
  if (!brand && (isResolving || institutionContextRequested)) return <span className={'qe-brand-loading qe-brand--' + variant} aria-hidden="true" />;
  const accessibleLabel = tenantName || (institutionContextRequested ? 'Learning space' : BRAND_LABEL);
  const logoUrl = approvedLogoUrl(brand && brand.logoUrl, brand && brand.institutionCode) ? brand!.logoUrl! : null;
  const lockup = (
    <span className={'qe-brand qe-brand--' + variant}>
      <span className="qe-brand__lockup">
        {brand
          ? logoUrl
            ? <img className="qe-brand__icon qe-brand__tenant-logo" src={logoUrl} alt="" width={192} height={192} aria-hidden="true" />
            : <span className="qe-brand__tenant-mark" aria-hidden="true">{(tenantName || 'S').slice(0, 1).toLocaleUpperCase()}</span>
          : <img className="qe-brand__icon" src="/branding/quantaedge-icon.png" alt="" width={192} height={192} aria-hidden="true" />}
        <span className="qe-brand__copy">
          {brand
            ? <span className="qe-brand__name qe-brand__tenant-name">{tenantName || 'Your school'}</span>
            : <>
                <span className="qe-brand__name" aria-hidden="true">
                  <span className="qe-brand__name-quant">Quanta</span><span className="qe-brand__name-edge">Edge</span>
                </span>
                <span className="qe-brand__tagline">Smarter Decisions. Greater Growth.</span>
              </>}
        </span>
      </span>
    </span>
  );

  if (href === null) {
    return <div className={'qe-brand-static ' + className} role="img" aria-label={accessibleLabel}>{lockup}</div>;
  }

  return <Link href={href} className={'qe-brand-link ' + className} aria-label={accessibleLabel}>{lockup}</Link>;
}
