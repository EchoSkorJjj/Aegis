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
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const hasSession = request.cookies.has(REFRESH_COOKIE);

  // Protect the authenticated app shell. Admin-role enforcement stays on the backend
  // (403) and the page itself; this only checks that a session exists.
  const isProtected =
    pathname === "/dashboard" ||
    pathname.startsWith("/dashboard/") ||
    pathname === "/admin" ||
    pathname.startsWith("/admin/") ||
    pathname === "/organizations" ||
    pathname.startsWith("/organizations/") ||
    pathname === "/settings" ||
    pathname.startsWith("/settings/");
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
