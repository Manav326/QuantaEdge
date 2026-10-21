import NotificationInboxClient from '../../components/NotificationInboxClient';

export const metadata = { title: 'Notifications' };

export default function StudentNotificationsPage() {
  return <NotificationInboxClient role="student" />;
}
