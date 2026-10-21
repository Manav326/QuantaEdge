import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

function hasStaffSessionCookie(request: NextRequest) {
  return request.cookies.has('QE_STAFF_SESSION') || request.cookies.has('QE_STAFF_REFRESH');
}

function hasLegacySessionCookie(request: NextRequest) {
  return request.cookies.has('QE_SESSION') || request.cookies.has('QE_REFRESH');
}

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const hasStaffSession = hasStaffSessionCookie(request);
  // Legacy shared cookies are only a migration hint; the API checks that the
  // underlying identity really belongs to this workspace before granting access.
  const hasLegacySession = hasLegacySessionCookie(request);
  if (path === '/login' && hasStaffSession) {
    return NextResponse.redirect(new URL('/session', request.url));
  }
  if (path !== '/login' && path !== '/session' && !hasStaffSession && !hasLegacySession) {
    return NextResponse.redirect(new URL('/login', request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/', '/login', '/session', '/content/:path*', '/employees/:path*', '/parents/:path*', '/students/:path*', '/legacy-content/:path*'],
};
