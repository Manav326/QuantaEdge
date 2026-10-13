import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

// Cookie presence is a routing hint only. The API remains responsible for
// validating the identity and its workspace on every protected request.
const PARENT_SESSION_COOKIE_NAMES = [
  'QE_PARENT_SESSION',
  'QE_PARENT_REFRESH',
] as const;

const STUDENT_SESSION_COOKIE_NAMES = [
  'QE_STUDENT_SESSION',
  'QE_STUDENT_REFRESH',
] as const;

const LEGACY_SESSION_COOKIE_NAMES = [
  'QE_LEARN_SESSION',
  'QE_LEARN_REFRESH',
  'QE_SESSION',
  'QE_REFRESH',
] as const;

function hasAnyCookie(request: NextRequest, names: readonly string[]) {
  return names.some(name => request.cookies.has(name));
}

function hasWorkspaceSessionHint(request: NextRequest, workspace: 'PARENT' | 'STUDENT') {
  const scoped = workspace === 'PARENT'
    ? PARENT_SESSION_COOKIE_NAMES
    : STUDENT_SESSION_COOKIE_NAMES;
  // Legacy cookies can belong to either learner workspace. Let the API's
  // workspace-scoped auth check decide; never treat one scoped cookie as the other.
  return hasAnyCookie(request, scoped) || hasAnyCookie(request, LEGACY_SESSION_COOKIE_NAMES);
}

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const studentRoute = path === '/student' || path.startsWith('/student/');
  const parentRoute = path === '/parent' || path.startsWith('/parent/');

  // Protect each workspace with its own session-cookie family. An active
  // parent session must not be mistaken for a student session, or vice versa.
  if (studentRoute && !hasWorkspaceSessionHint(request, 'STUDENT')) {
    return NextResponse.redirect(new URL('/login/student', request.url));
  }
  if (parentRoute && !hasWorkspaceSessionHint(request, 'PARENT')) {
    return NextResponse.redirect(new URL('/login', request.url));
  }

  // Public entry pages deliberately reach the client session boundary. It
  // checks the intended role before redirecting, so a parent's cookie cannot
  // hijack /login/student and a student's cookie cannot hijack /login.
  return NextResponse.next();
}

export const config = {
  matcher: ['/', '/login', '/login/student', '/parent/:path*', '/student/:path*'],
};
