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
  | 'staff'
  | 'translations'
  | 'certificates';

export type AdminLocale = 'english' | 'hinglish';

export type AdminNavItem = {
  key: AdminSection;
  href: string;
  icon: string;
  english: string;
  hinglish: string;
  shortEnglish: string;
  shortHinglish: string;
};

export type AdminNavGroup = {
  id: 'curriculum' | 'teaching' | 'people' | 'administration';
  english: string;
  hinglish: string;
  items: AdminNavItem[];
};

export const ADMIN_OVERVIEW_ITEM: AdminNavItem = {
  key: 'overview',
  href: '/',
  icon: '▦',
  english: 'Overview',
  hinglish: 'Overview',
  shortEnglish: 'Home',
  shortHinglish: 'Home',
};

export const ADMIN_NAV_GROUPS: AdminNavGroup[] = [
  {
    id: 'curriculum',
    english: 'Curriculum & Content',
    hinglish: 'पाठ्यक्रम और सामग्री',
    items: [
      { key: 'content', href: '/content', icon: '◈', english: 'Content Studio', hinglish: 'Content Studio', shortEnglish: 'Content', shortHinglish: 'Content' },
      { key: 'resources', href: '/resources', icon: '▤', english: 'Textbook library', hinglish: 'Textbook library', shortEnglish: 'Books', shortHinglish: 'Books' },
      { key: 'sources', href: '/source-ingestion', icon: '⇣', english: 'Source ingestion', hinglish: 'Source जोड़ें', shortEnglish: 'Sources', shortHinglish: 'Sources' },
      { key: 'translations', href: '/translations', icon: '文', english: 'Language review', hinglish: 'भाषा अनुवाद समीक्षा', shortEnglish: 'Language', shortHinglish: 'भाषा' },
      { key: 'questions', href: '/questions', icon: '✓', english: 'Question review', hinglish: 'MCQ समीक्षा', shortEnglish: 'Questions', shortHinglish: 'MCQ' },
    ],
  },
  {
    id: 'teaching',
    english: 'Teaching & Assessment',
    hinglish: 'शिक्षण और मूल्यांकन',
    items: [
      { key: 'assessments', href: '/assessments', icon: '✓', english: 'Tests & grading', hinglish: 'Tests & grading', shortEnglish: 'Tests', shortHinglish: 'Tests' },
      { key: 'classes', href: '/classes', icon: '◷', english: 'Live & recorded classes', hinglish: 'Live & recorded classes', shortEnglish: 'Classes', shortHinglish: 'Classes' },
    ],
  },
  {
    id: 'people',
    english: 'People',
    hinglish: 'लोग',
    items: [
      { key: 'students', href: '/students', icon: '◉', english: 'Students', hinglish: 'Students', shortEnglish: 'Students', shortHinglish: 'Students' },
      { key: 'parents', href: '/parents', icon: '♧', english: 'Parents & families', hinglish: 'Parents & families', shortEnglish: 'Families', shortHinglish: 'Families' },
    ],
  },
  {
    id: 'administration',
    english: 'Administration & Audit',
    hinglish: 'प्रशासन और ऑडिट',
    items: [
      { key: 'staff', href: '/employees', icon: '♙', english: 'Staff & audit', hinglish: 'Staff & audit', shortEnglish: 'Staff', shortHinglish: 'Staff' },
      { key: 'certificates', href: '/certificates', icon: '▧', english: 'Certificates', hinglish: 'प्रमाणपत्र', shortEnglish: 'Certificates', shortHinglish: 'प्रमाणपत्र' },
    ],
  },
];

export function canSeeAdminItem(item: AdminNavItem, role: string, permissions: string[]): boolean {
  // Preserve the current loading behavior while the staff session is being resolved.
  if (!role) return true;
  if (item.key === 'overview') return role === 'ADMIN';
  if (item.key === 'certificates') return role === 'ADMIN';
  if (item.key === 'students' || item.key === 'parents') return role === 'ADMIN';
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
  if (item.key === 'staff' && role !== 'ADMIN') return locale === 'english' ? 'Activity trail' : 'गतिविधि ट्रेल';
  return locale === 'english' ? item.english : item.hinglish;
}

export function adminNavShortLabel(item: AdminNavItem, locale: AdminLocale): string {
  return locale === 'english' ? item.shortEnglish : item.shortHinglish;
}

export function adminNavGroupLabel(group: AdminNavGroup, locale: AdminLocale): string {
  return locale === 'english' ? group.english : group.hinglish;
}


const ADMIN_ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Administrator',
  MANAGER: 'Manager',
  CONTENT_AUTHOR: 'Content Author',
  CONTENT_REVIEWER: 'Content Reviewer',
  MODERATOR: 'Moderator',
  PUBLISHER: 'Publisher',
};

const ADMIN_ROLE_COMPACT_LABELS: Record<string, string> = {
  ADMIN: 'ADMIN',
  MANAGER: 'MGR',
  CONTENT_AUTHOR: 'AUTHOR',
  CONTENT_REVIEWER: 'REVIEW',
  MODERATOR: 'MOD',
  PUBLISHER: 'PUB',
};

export function adminRoleLabel(role: string): string {
  const normalized = role.trim().toUpperCase();
  if (!normalized) return 'Staff';
  return ADMIN_ROLE_LABELS[normalized]
    || normalized.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
}

export function adminRoleCompactLabel(role: string): string {
  const normalized = role.trim().toUpperCase();
  return ADMIN_ROLE_COMPACT_LABELS[normalized] || (normalized ? normalized.slice(0, 5) : 'STAFF');
}

export function adminRoleClass(role: string): string {
  return role.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'staff';
}
