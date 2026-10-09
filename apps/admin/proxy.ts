import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

function hasSessionCookie(request: NextRequest) {
  return request.cookies.has('QE_SESSION') || request.cookies.has('QE_REFRESH');
}

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const hasSession = hasSessionCookie(request);
  if (path === '/login' && hasSession) {
    return NextResponse.redirect(new URL('/session', request.url));
  }
  if (path !== '/login' && path !== '/session' && !hasSession) {
    return NextResponse.redirect(new URL('/login', request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/', '/login', '/session', '/content/:path*', '/employees/:path*', '/parents/:path*', '/students/:path*', '/legacy-content/:path*'],
};
