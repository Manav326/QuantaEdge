import type { Metadata } from 'next';
import { learnerMetadataForTenantCookie } from '../tenantMetadata';

export async function generateMetadata(): Promise<Metadata> {
  return learnerMetadataForTenantCookie('qe-tenant-session');
}

export default function SessionLayout({ children }: { children: React.ReactNode }) {
  return children;
}
