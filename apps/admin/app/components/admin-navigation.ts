import { translateUi, type MessageKey, type UiLocale } from '../i18n/catalog';

export type AdminSection =
  | 'overview'
  | 'content'
  | 'questions'
  | 'resources'
  | 'sources'
  | 'assessments'
  | 'classes'
  | 'students'
  | 'parents'
  | 'lifecycle'
  | 'staff'
  | 'translations'
  | 'certificates'
  | 'marketplace'
  | 'institution'
  | 'notifications';

export type AdminLocale = UiLocale;
export type AdminNavItem = { key: AdminSection; href: string; icon: string };
export type AdminNavGroup = { id: 'curriculum' | 'teaching' | 'people' | 'administration'; items: AdminNavItem[] };

export const ADMIN_OVERVIEW_ITEM: AdminNavItem = { key: 'overview', href: '/', icon: '▦' };
export const ADMIN_NAV_GROUPS: AdminNavGroup[] = [
  { id: 'curriculum', items: [
    { key: 'content', href: '/content', icon: '◈' }, { key: 'resources', href: '/resources', icon: '▤' },
    { key: 'sources', href: '/source-ingestion', icon: '⇣' }, { key: 'translations', href: '/translations', icon: '文' },
    { key: 'questions', href: '/questions', icon: '✓' },
  ] },
  { id: 'teaching', items: [
    { key: 'assessments', href: '/assessments', icon: '✓' }, { key: 'classes', href: '/classes', icon: '◷' },
  ] },
  { id: 'people', items: [
    { key: 'students', href: '/students', icon: '◉' }, { key: 'parents', href: '/parents', icon: '♧' },
    { key: 'lifecycle', href: '/account-lifecycle', icon: '✓' },
  ] },
  { id: 'administration', items: [
    { key: 'staff', href: '/employees', icon: '♙' }, { key: 'certificates', href: '/certificates', icon: '▧' },
    { key: 'institution', href: '/institution-operations', icon: '⌂' },
    { key: 'marketplace', href: '/marketplace', icon: '₹' },
    { key: 'notifications', href: '/notifications', icon: '♧' },
  ] },
];

const ITEM_MESSAGE_KEYS: Record<AdminSection, MessageKey> = {
  overview: 'admin.navigation.overview', content: 'admin.navigation.contentStudio', questions: 'admin.navigation.questionReview',
  resources: 'admin.navigation.textbookLibrary', sources: 'admin.navigation.sourceIngestion', assessments: 'admin.navigation.testsGrading',
  classes: 'admin.navigation.liveRecordedClasses', students: 'admin.navigation.students', parents: 'admin.navigation.parentsFamilies',
  lifecycle: 'admin.navigation.accountLifecycle', staff: 'admin.navigation.staffAudit', translations: 'admin.navigation.languageReview',
  certificates: 'admin.navigation.certificates', marketplace: 'admin.navigation.courseMarketplace',
  institution: 'admin.institutionOperations.title', notifications: 'admin.navigation.notifications',
};
const ITEM_SHORT_MESSAGE_KEYS: Record<AdminSection, MessageKey> = {
  overview: 'admin.navigation.short.home', content: 'admin.navigation.short.content', questions: 'admin.navigation.short.questions',
  resources: 'admin.navigation.short.books', sources: 'admin.navigation.short.sources', assessments: 'admin.navigation.short.tests',
  classes: 'admin.navigation.short.classes', students: 'admin.navigation.short.students', parents: 'admin.navigation.short.families',
  lifecycle: 'admin.navigation.short.lifecycle', staff: 'admin.navigation.short.staff', translations: 'admin.navigation.short.language',
  certificates: 'admin.navigation.short.certificates', marketplace: 'admin.navigation.short.marketplace',
  institution: 'admin.institutionOperations.title', notifications: 'admin.navigation.short.notifications',
};
const GROUP_MESSAGE_KEYS: Record<AdminNavGroup['id'], MessageKey> = {
  curriculum: 'admin.navigation.group.curriculum', teaching: 'admin.navigation.group.teaching',
  people: 'admin.navigation.group.people', administration: 'admin.navigation.group.administration',
};

export function canSeeAdminItem(item: AdminNavItem, role: string, permissions: string[]): boolean {
  // Preserve the current loading behavior while the staff session is being resolved.
  if (!role) return true;
  if (item.key === 'overview') return role === 'ADMIN';
  if (item.key === 'certificates' || item.key === 'marketplace') return role === 'ADMIN';
  if (item.key === 'institution') return permissions.includes('INSTITUTION_REPORTS_VIEW');
  // This destination is the full creation/send studio; campaign-view-only access does not grant send authority.
  if (item.key === 'notifications') return role === 'ADMIN' || ['NOTIFICATION_CAMPAIGN_VIEW','NOTIFICATION_TEMPLATE_MANAGE','NOTIFICATION_COURSE_SEND','NOTIFICATION_INSTITUTION_SEND'].some(permission => permissions.includes(permission));
  if (item.key === 'students' || item.key === 'parents') return role === 'ADMIN';
  if (item.key === 'lifecycle') return role === 'ADMIN' || permissions.includes('ACCOUNT_LIFECYCLE_REVIEW');
  if (item.key === 'content') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW');
  if (item.key === 'translations') {
    return role === 'ADMIN'
      || permissions.includes('CONTENT_VIEW')
      || permissions.includes('CONTENT_REVIEW')
      || permissions.includes('CONTENT_EDIT');
  }
  if (item.key === 'questions') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW') || permissions.includes('CONTENT_REVIEW');
  if (item.key === 'resources') {
    return role === 'ADMIN'
      || permissions.includes('CONTENT_VIEW')
      || permissions.includes('CONTENT_EDIT')
      || permissions.includes('CONTENT_PUBLISH');
  }
  if (item.key === 'sources') return role === 'ADMIN' || permissions.includes('CONTENT_REVIEW');
  if (item.key === 'assessments') {
    return role === 'ADMIN'
      || permissions.includes('CONTENT_VIEW')
      || permissions.includes('CONTENT_CREATE')
      || permissions.includes('ASSESSMENT_GRADE');
  }
  if (item.key === 'classes') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW') || permissions.includes('CLASS_MANAGE');
  if (item.key === 'staff') return role === 'ADMIN' || permissions.includes('AUDIT_VIEW');
  return false;
}

export function adminNavHref(item: AdminNavItem, role: string): string {
  return item.key === 'staff' && role !== 'ADMIN' ? '/employees?view=audit' : item.href;
}

export function adminNavLabel(item: AdminNavItem, locale: AdminLocale, role: string): string {
  if (item.key === 'staff' && role !== 'ADMIN') return translateUi(locale, 'admin.role.activityTrail');
  return translateUi(locale, ITEM_MESSAGE_KEYS[item.key]);
}
export function adminNavShortLabel(item: AdminNavItem, locale: AdminLocale): string {
  return translateUi(locale, ITEM_SHORT_MESSAGE_KEYS[item.key]);
}
export function adminNavGroupLabel(group: AdminNavGroup, locale: AdminLocale): string {
  return translateUi(locale, GROUP_MESSAGE_KEYS[group.id]);
}

const ADMIN_ROLE_MESSAGE_KEYS: Record<string, MessageKey> = {
  ADMIN: 'admin.role.admin', MANAGER: 'admin.role.manager', CONTENT_AUTHOR: 'admin.role.contentAuthor',
  CONTENT_REVIEWER: 'admin.role.contentReviewer', MODERATOR: 'admin.role.moderator', PUBLISHER: 'admin.role.publisher',
};
const ADMIN_ROLE_COMPACT_LABELS: Record<string, string> = {
  ADMIN: 'ADMIN', MANAGER: 'MGR', CONTENT_AUTHOR: 'AUTHOR', CONTENT_REVIEWER: 'REVIEW', MODERATOR: 'MOD', PUBLISHER: 'PUB',
};
export function adminRoleLabel(role: string, locale: AdminLocale = 'hinglish'): string {
  const normalized = role.trim().toUpperCase();
  if (!normalized) return translateUi(locale, 'admin.role.staff');
  const key = ADMIN_ROLE_MESSAGE_KEYS[normalized];
  return key ? translateUi(locale, key) : normalized.toLowerCase().replace(/_/g, ' ').replace(/\\b\\w/g, letter => letter.toUpperCase());
}
export function adminRoleCompactLabel(role: string): string {
  const normalized = role.trim().toUpperCase();
  return ADMIN_ROLE_COMPACT_LABELS[normalized] || (normalized ? normalized.slice(0, 5) : 'STAFF');
}

export function adminRoleClass(role: string): string {
  return role.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'staff';
}
