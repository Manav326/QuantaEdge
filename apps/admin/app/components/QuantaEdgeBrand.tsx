'use client';

import Link from 'next/link';
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

function approvedAssetUrl(value: string | null | undefined, code: string, assetType: 'logo' | 'app-icon'): value is string {
  if (!value || !code) return false;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/' + assetType + '?v=';
  return value.startsWith(prefix) && /^\d+$/.test(value.slice(prefix.length));
}

/**
 * Admin follows the learner-web lockup contract: a validated wide logo is used
 * in roomy layouts; compact layouts use the square icon with live text. HRMS
 * remains the source of institution identity, assets and theme configuration.
 */
export default function QuantaEdgeBrand({
  href = '/',
  variant = 'header',
  className = '',
}: QuantaEdgeBrandProps) {
  const { brand, institutionCode, isResolving } = useAdminTenantTheme();
  const tenantContext = Boolean(institutionCode);
  const tenantName = brand?.displayName?.trim() || '';
  const tenantTagline = brand?.tagline?.trim() || '';

  if (tenantContext && !brand && isResolving) {
    return <span className={'qe-brand-loading qe-brand--' + variant} aria-hidden="true" />;
  }

  const brandLabel = tenantName || (tenantContext ? 'Institution learning space' : PLATFORM_BRAND_LABEL);
  const logoUrl = tenantContext
    ? brand?.logoUrl && approvedAssetUrl(brand.logoUrl, institutionCode, 'logo') ? brand.logoUrl : null
    : PLATFORM_LOGO_URL;
  const appIconUrl = tenantContext
    ? brand?.appIconUrl && approvedAssetUrl(brand.appIconUrl, institutionCode, 'app-icon') ? brand.appIconUrl : null
    : PLATFORM_ICON_URL;
  const displayName = tenantContext ? tenantName || 'Your institution' : 'QuantaEdge';
  const useWideLogo = variant !== 'compact' && Boolean(logoUrl);

  const lockup = useWideLogo
    ? <span className={'qe-brand qe-brand--' + variant}>
        <span className="qe-brand__lockup qe-brand__full-lockup">
          <img className="qe-brand__logo" src={logoUrl || PLATFORM_LOGO_URL} alt="" aria-hidden="true" />
        </span>
      </span>
    : <span className={'qe-brand qe-brand--' + variant}>
        <span className="qe-brand__lockup">
          {tenantContext
            ? appIconUrl
              ? <img className="qe-brand__icon qe-brand__tenant-icon" src={appIconUrl} alt="" width={192} height={192} aria-hidden="true" />
              : <span className="qe-brand__tenant-mark" aria-hidden="true">{displayName.slice(0, 1).toLocaleUpperCase()}</span>
            : <img className="qe-brand__icon" src={PLATFORM_ICON_URL} alt="" width={192} height={192} aria-hidden="true" />}
          <span className="qe-brand__copy">
            {tenantContext
              ? <>
                  <span className="qe-brand__name qe-brand__tenant-name">{displayName}</span>
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
    return <div className={'qe-brand-static ' + className} role="img" aria-label={brandLabel}>{lockup}</div>;
  }
  return <Link href={href} className={'qe-brand-link ' + className} aria-label={brandLabel}>{lockup}</Link>;
}
