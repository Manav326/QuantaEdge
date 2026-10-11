import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

function hasLearningSessionCookie(request: NextRequest) {
  return request.cookies.has('QE_LEARN_SESSION') || request.cookies.has('QE_LEARN_REFRESH');
}

function hasLegacySessionCookie(request: NextRequest) {
  return request.cookies.has('QE_SESSION') || request.cookies.has('QE_REFRESH');
}

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const learningSession = hasLearningSessionCookie(request);
  // Legacy shared cookies are only a migration hint; the API validates their role.
  const legacySession = hasLegacySessionCookie(request);
  const publicEntry = path === '/' || path === '/login' || path === '/login/student';
  const studentRoute = path === '/student' || path.startsWith('/student/');
  const parentRoute = path === '/parent' || path.startsWith('/parent/');

  // Staff cookies are deliberately ignored by the learning site and vice versa.
  if (publicEntry && learningSession) {
    return NextResponse.redirect(new URL('/session', request.url));
  }
  if ((studentRoute || parentRoute) && !learningSession && !legacySession) {
    return NextResponse.redirect(new URL(studentRoute ? '/login/student' : '/login', request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/', '/login', '/login/student', '/parent/:path*', '/student/:path*'],
};
