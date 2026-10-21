import type { Metadata } from 'next';
import { learnerMetadataForTenantCookie } from '../tenantMetadata';

export async function generateMetadata(): Promise<Metadata> {
  return learnerMetadataForTenantCookie('qe-tenant-student');
}

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  return children;
}
