import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

function hasSessionCookie(request: NextRequest) {
  return request.cookies.has('QE_SESSION') || request.cookies.has('QE_REFRESH');
}

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const authenticatedCookie = hasSessionCookie(request);
  const publicEntry = path === '/' || path === '/login' || path === '/login/student';
  const studentRoute = path === '/student' || path.startsWith('/student/');
  const parentRoute = path === '/parent' || path.startsWith('/parent/');

  // Cookie presence is only a fast navigation hint; server-backed session checks
  // still run in SessionBoundary before protected content is displayed.
  if (publicEntry && authenticatedCookie) {
    return NextResponse.redirect(new URL('/session', request.url));
  }
  if ((studentRoute || parentRoute) && !authenticatedCookie) {
    return NextResponse.redirect(new URL(studentRoute ? '/login/student' : '/login', request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/', '/login', '/login/student', '/parent/:path*', '/student/:path*'],
};
