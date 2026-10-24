'use client';

import { useContext } from 'react';
import NotificationStudio from '../components/NotificationStudio';
import StaffNotificationInbox from '../components/StaffNotificationInbox';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';

export const metadata = { title: 'Notifications · QuantaEdge Admin' };

export default function AdminNotificationsPage() {
  const { identityType } = useContext(AdminWorkspaceContext);
  if (identityType === 'INSTITUTION') {
    return (
      <div className="admin-shell">
        <main className="admin-main">
          <NotificationStudio surface="institution" />
          <section style={{ marginTop: 18 }}>
            <StaffNotificationInbox workspace="institution" />
          </section>
        </main>
      </div>
    );
  }
  return <NotificationStudio surface="admin" />;
}
