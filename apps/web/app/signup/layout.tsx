import type { Metadata } from 'next';
import { learnerMetadataForTenantCookie } from '../tenantMetadata';

export async function generateMetadata(): Promise<Metadata> {
  return learnerMetadataForTenantCookie('qe-tenant-signup');
}

export default function SignupLayout({ children }: { children: React.ReactNode }) {
  return children;
}
