import type { Metadata } from 'next';
import { cookies } from 'next/headers';

const CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{2,31}$/;
const NEUTRAL_TITLE = 'School learning space';
const NEUTRAL_DESCRIPTION = 'Your school learning space.';

type PublicBranding = {
  institutionCode?: string;
  displayName?: string;
  logoUrl?: string | null;
  appIconUrl?: string | null;
};

function normalizedCode(value: string | undefined): string | null {
  const code = String(value ?? '').trim().toUpperCase();
  return CODE_PATTERN.test(code) ? code : null;
}

function approvedAssetUrl(value: unknown, code: string, type: 'logo' | 'app-icon'): string | null {
  if (typeof value !== 'string') return null;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/' + type + '?v=';
  if (!value.startsWith(prefix) || !/^\d+$/.test(value.slice(prefix.length))) return null;
  return value;
}

function neutralSchoolMetadata(): Metadata {
  return {
    title: { absolute: NEUTRAL_TITLE },
    description: NEUTRAL_DESCRIPTION,
    applicationName: NEUTRAL_TITLE,
    icons: { icon: '/favicon.svg', shortcut: '/favicon.svg', apple: '/favicon.svg' },
  };
}

async function publicBranding(code: string): Promise<PublicBranding | null> {
  const backend = (process.env.BACKEND_INTERNAL_URL || process.env.API_BASE_URL || 'http://api:8080').replace(/\/+$/, '');
  try {
    const response = await fetch(backend + '/api/v1/institution/public/branding/' + encodeURIComponent(code), {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return null;
    const body = await response.json() as PublicBranding;
    if (normalizedCode(body.institutionCode) !== code ||
        typeof body.displayName !== 'string' ||
        body.displayName.trim().length < 2 ||
        body.displayName.trim().length > 180 ||
        /[\u0000-\u001f\u007f]/.test(body.displayName)) return null;
    return body;
  } catch {
    return null;
  }
}

/**
 * Tenant identity is intentionally resolved only on learner routes that receive a
 * path-scoped, public institution-code cookie. No global/root cookie can rebrand
 * Admin, HRMS, institution-admin, or other QuantaEdge operator pages.
 */
export async function learnerMetadataForTenantCookie(cookieName: string): Promise<Metadata> {
  const store = await cookies();
  const raw = store.get(cookieName)?.value;
  if (!raw) return {};
  const code = normalizedCode(raw);
  if (!code) return neutralSchoolMetadata();

  const brand = await publicBranding(code);
  const displayName = brand?.displayName?.trim();
  if (!brand || !displayName) return neutralSchoolMetadata();

  const icon = approvedAssetUrl(brand.appIconUrl, code, 'app-icon')
    || approvedAssetUrl(brand.logoUrl, code, 'logo')
    || '/favicon.svg';
  return {
    title: { absolute: displayName },
    description: 'Learning and progress with ' + displayName + '.',
    applicationName: displayName,
    icons: { icon, shortcut: icon, apple: icon },
  };
}
