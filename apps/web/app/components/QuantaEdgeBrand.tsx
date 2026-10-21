'use client';

import Link from 'next/link';
import { useTenantBranding } from './TenantBrandingProvider';

function approvedAssetUrl(url: string | null | undefined, code: string | undefined, type: 'logo' | 'app-icon'): url is string {
  if (!url || !code) return false;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/' + type + '?v=';
  if (!url.startsWith(prefix)) return false;
  return /^\d+$/.test(url.slice(prefix.length));
}

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
  const { brand, isResolving, institutionContextRequested } = useTenantBranding();
  const tenantName = brand?.displayName?.trim() || '';
  const tenantTagline = brand?.tagline?.trim() || '';
  if (!brand && (isResolving || institutionContextRequested)) {
    return <span className={'qe-brand-loading qe-brand--' + variant} aria-hidden="true" />;
  }

  const accessibleLabel = tenantName || (institutionContextRequested ? 'Learning space' : BRAND_LABEL);
  // Platform and tenant brands share exactly the same URL slots and CSS role contract.
  // Only the source differs: a canonical static asset for QuantaEdge, a validated versioned URL for tenants.
  const logoUrl = brand
    ? brand.logoUrl && approvedAssetUrl(brand.logoUrl, brand.institutionCode, 'logo') ? brand.logoUrl : null
    : PLATFORM_LOGO_URL;
  const appIconUrl = brand
    ? brand.appIconUrl && approvedAssetUrl(brand.appIconUrl, brand.institutionCode, 'app-icon') ? brand.appIconUrl : null
    : PLATFORM_ICON_URL;
  const useWideLogo = variant !== 'compact' && Boolean(logoUrl);

  const lockup = useWideLogo
    ? <span className={'qe-brand qe-brand--' + variant}>
        <span className="qe-brand__lockup qe-brand__full-lockup">
          <img className="qe-brand__logo" src={logoUrl || PLATFORM_LOGO_URL} alt="" aria-hidden="true" />
        </span>
      </span>
    : <span className={'qe-brand qe-brand--' + variant}>
        <span className="qe-brand__lockup">
          {brand
            ? appIconUrl
              ? <img className="qe-brand__icon qe-brand__tenant-icon" src={appIconUrl} alt="" width={192} height={192} aria-hidden="true" />
              : <span className="qe-brand__tenant-mark" aria-hidden="true">{(tenantName || 'S').slice(0, 1).toLocaleUpperCase()}</span>
            : <img className="qe-brand__icon" src={PLATFORM_ICON_URL} alt="" width={192} height={192} aria-hidden="true" />}
          <span className="qe-brand__copy">
            {brand
              ? <>
                  <span className="qe-brand__name qe-brand__tenant-name">{tenantName || 'Your school'}</span>
                  {tenantTagline ? <span className="qe-brand__tagline qe-brand__tenant-tagline">{tenantTagline}</span> : null}
                </>
              : <>
                  <span className="qe-brand__name" aria-hidden="true">
                    <span className="qe-brand__name-quant">Quanta</span><span className="qe-brand__name-edge">Edge</span>
                  </span>
                  <span className="qe-brand__tagline">Smarter Decisions. Greater Growth.</span>
                </>}
          </span>
        </span>
      </span>;

  if (href === null) {
    return <div className={'qe-brand-static ' + className} role="img" aria-label={accessibleLabel}>{lockup}</div>;
  }
  return <Link href={href} className={'qe-brand-link ' + className} aria-label={accessibleLabel}>{lockup}</Link>;
}
