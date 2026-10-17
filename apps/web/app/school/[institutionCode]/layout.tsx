import type { Metadata } from 'next';

type SchoolBrandResponse = {
  institutionCode?: string;
  displayName?: string;
  logoUrl?: string | null;
  appIconUrl?: string | null;
};

type SchoolRouteProps = {
  children: React.ReactNode;
};

type SchoolMetadataProps = {
  params: Promise<{ institutionCode: string }>;
};

const NEUTRAL_SCHOOL_TITLE = 'School learning space';
const NEUTRAL_SCHOOL_DESCRIPTION = 'Open your school’s learning space using the official link shared by your school.';

function normalizedInstitutionCode(value: string): string | null {
  const code = value.trim().toUpperCase();
  return /^[A-Z0-9][A-Z0-9_-]{2,31}$/.test(code) ? code : null;
}

function approvedAssetUrl(value: unknown, code: string, type: 'logo' | 'app-icon'): string | null {
  if (typeof value !== 'string') return null;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/' + type + '?v=';
  if (!value.startsWith(prefix) || !/^\d+$/.test(value.slice(prefix.length))) return null;
  return value;
}

async function resolvePublicSchoolBrand(code: string): Promise<SchoolBrandResponse | null> {
  const backend = (process.env.BACKEND_INTERNAL_URL || 'http://api:8080').replace(/\/+$/, '');
  try {
    const response = await fetch(backend + '/api/v1/institution/public/branding/' + encodeURIComponent(code), {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return null;
    const body = await response.json() as SchoolBrandResponse;
    if (normalizedInstitutionCode(String(body.institutionCode || '')) !== code ||
        typeof body.displayName !== 'string' || body.displayName.trim().length < 2) return null;
    return body;
  } catch {
    return null;
  }
}

export async function generateMetadata({ params }: SchoolMetadataProps): Promise<Metadata> {
  const { institutionCode: rawCode } = await params;
  const code = normalizedInstitutionCode(rawCode);
  if (!code) {
    return {
      title: { absolute: NEUTRAL_SCHOOL_TITLE },
      description: NEUTRAL_SCHOOL_DESCRIPTION,
      applicationName: NEUTRAL_SCHOOL_TITLE,
      icons: { icon: '/favicon.svg', apple: '/favicon.svg' },
    };
  }

  const brand = await resolvePublicSchoolBrand(code);
  const name = brand?.displayName?.trim();
  if (!brand || !name) {
    // A failed school lookup must not leak the platform brand through inherited metadata.
    return {
      title: { absolute: NEUTRAL_SCHOOL_TITLE },
      description: NEUTRAL_SCHOOL_DESCRIPTION,
      applicationName: NEUTRAL_SCHOOL_TITLE,
      icons: { icon: '/favicon.svg', apple: '/favicon.svg' },
    };
  }

  const appIconUrl = approvedAssetUrl(brand.appIconUrl, code, 'app-icon');
  const logoUrl = approvedAssetUrl(brand.logoUrl, code, 'logo');
  const iconUrl = appIconUrl || logoUrl || '/favicon.svg';
  return {
    title: { absolute: name },
    description: 'Learning and progress with ' + name + '.',
    applicationName: name,
    icons: { icon: iconUrl, shortcut: iconUrl, apple: iconUrl },
  };
}

export default function SchoolRouteLayout({ children }: SchoolRouteProps) {
  return children;
}
