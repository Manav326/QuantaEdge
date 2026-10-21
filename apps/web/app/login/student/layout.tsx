import type { Metadata } from 'next';
import { learnerMetadataForTenantCookie } from '../../tenantMetadata';

export async function generateMetadata(): Promise<Metadata> {
  return learnerMetadataForTenantCookie('qe-tenant-login-student');
}

export default function StudentLoginLayout({ children }: { children: React.ReactNode }) {
  return children;
}
