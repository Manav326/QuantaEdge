import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

// Cookie presence is only a routing hint; the API verifies identity and workspace.
const LEARNER_SESSION_COOKIE_NAMES = [
  'QE_LEARN_SESSION',
  'QE_LEARN_REFRESH',
  'QE_PARENT_SESSION',
  'QE_PARENT_REFRESH',
  'QE_STUDENT_SESSION',
  'QE_STUDENT_REFRESH',
] as const;

function hasLearnerSessionCookie(request: NextRequest) {
  return LEARNER_SESSION_COOKIE_NAMES.some(name => request.cookies.has(name));
}

function hasLegacySessionCookie(request: NextRequest) {
  return request.cookies.has('QE_SESSION') || request.cookies.has('QE_REFRESH');
}

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const learnerSession = hasLearnerSessionCookie(request);
  // Legacy shared cookies are only a migration hint; the API validates their role.
  const legacySession = hasLegacySessionCookie(request);
  const publicEntry = path === '/' || path === '/login' || path === '/login/student';
  const studentRoute = path === '/student' || path.startsWith('/student/');
  const parentRoute = path === '/parent' || path.startsWith('/parent/');

  // Staff cookies are deliberately ignored by the learning site and vice versa.
  if (publicEntry && learnerSession) {
    return NextResponse.redirect(new URL('/session', request.url));
  }
  if ((studentRoute || parentRoute) && !learnerSession && !legacySession) {
    return NextResponse.redirect(new URL(studentRoute ? '/login/student' : '/login', request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/', '/login', '/login/student', '/parent/:path*', '/student/:path*'],
};
