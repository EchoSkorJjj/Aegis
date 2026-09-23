import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { REFRESH_COOKIE } from "@/lib/auth-cookies";

/**
 * Server-side route guard. In Next.js 16 the `middleware` convention was renamed
 * to `proxy` (same behaviour, `proxy.ts` at the `src` root next to `app`).
 *
 * This is an OPTIMISTIC check only, as the Next.js docs recommend: it gates on
 * the presence of the long-lived refresh cookie, never trusting it as proof of a
 * valid session. The real authorization happens in the BFF (`/api/auth/me` and
 * the backend's JWT validation), which clears dead cookies. So the worst a
 * forged/stale cookie earns is a brief render before `/api/auth/me` returns 401
 * and the app redirects back to /login.
 */
// Authenticated app-shell route prefixes. Keep in sync with `config.matcher` below: the matcher
// decides which requests run this proxy at all; this list decides which of those need a session.
// Admin/domain-role enforcement stays on the backend (403) and the page itself — this only checks
// that a session exists.
const PROTECTED_PREFIXES = [
  "/dashboard",
  "/inventory",
  "/advisories",
  "/findings",
  "/responses",
  "/reports",
  "/admin",
  "/organizations",
  "/settings",
];

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasSession = request.cookies.has(REFRESH_COOKIE);

  const isProtected = PROTECTED_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
  if (isProtected) {
    if (!hasSession) {
      const loginUrl = new URL("/login", request.url);
      loginUrl.searchParams.set("next", pathname);
      return NextResponse.redirect(loginUrl);
    }
    return NextResponse.next();
  }

  // Signed-in users have no reason to see the auth screens.
  if (hasSession && (pathname === "/login" || pathname === "/signup")) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    "/dashboard",
    "/dashboard/:path*",
    "/inventory",
    "/inventory/:path*",
    "/advisories",
    "/advisories/:path*",
    "/findings",
    "/findings/:path*",
    "/responses",
    "/responses/:path*",
    "/reports",
    "/reports/:path*",
    "/admin",
    "/admin/:path*",
    "/organizations",
    "/organizations/:path*",
    "/settings",
    "/settings/:path*",
    "/login",
    "/signup",
  ],
};
