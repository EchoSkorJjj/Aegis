import {
  Boxes,
  FileText,
  GitPullRequest,
  LayoutDashboard,
  type LucideIcon,
  Plug,
  Rss,
  Settings,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";

import type { Role } from "@/lib/types";

export type NavLink = { title: string; href: string; icon: LucideIcon };
/** A page in a group; `roles`, when set, shows it only to holders of one of them. */
export type NavGroup = { title: string; icon: LucideIcon; items: { title: string; href: string; roles?: Role[] }[] };
export type NavItem = NavLink | NavGroup;

// Always available — the landing page for every signed-in user.
export const baseNav: NavItem[] = [{ title: "Dashboard", href: "/dashboard", icon: LayoutDashboard }];

// Aegis domain data. Shown only to users with a domain read role (SERVICE_OWNER / ANALYST /
// COMPLIANCE_ADMIN), mirroring aegis-core's read gate — a pure ORG_ADMIN is 403 on these APIs.
export const domainNav: NavItem[] = [
  {
    title: "Inventory",
    icon: Boxes,
    items: [
      { title: "Services", href: "/inventory/services" },
      { title: "Components", href: "/inventory/components" },
      { title: "Vendors", href: "/inventory/vendors" },
      { title: "Owners", href: "/inventory/owners" },
    ],
  },
  { title: "Advisories", href: "/advisories", icon: Rss },
  {
    title: "Findings",
    icon: ShieldAlert,
    items: [
      { title: "Triage queue", href: "/findings" },
      // Only an assigned owner has services of their own, and only a service owner can be assigned one.
      { title: "My services", href: "/findings/mine", roles: ["SERVICE_OWNER"] },
    ],
  },
  {
    title: "Responses",
    icon: GitPullRequest,
    items: [
      { title: "Review & approve", href: "/responses/review" },
      { title: "History", href: "/responses/history" },
    ],
  },
  {
    title: "Reports",
    icon: FileText,
    items: [
      { title: "Evidence", href: "/reports/evidence" },
      { title: "Audit log", href: "/reports/audit" },
    ],
  },
];

export const accountNav: NavLink[] = [{ title: "Settings", href: "/settings", icon: Settings }];

export const adminNav: NavLink[] = [{ title: "Users", href: "/admin/users", icon: ShieldCheck }];

// The organization's own settings, which only its admin (ORG_ADMIN) may change.
export const orgAdminNav: NavLink[] = [{ title: "Integrations", href: "/admin/integrations", icon: Plug }];

/** The navigation as these roles see it: a group's pages meant for other roles left out. */
export function visibleTo(items: NavItem[], roles: readonly Role[]): NavItem[] {
  return items.map((item) =>
    "items" in item
      ? { ...item, items: item.items.filter((sub) => !sub.roles || sub.roles.some((role) => roles.includes(role))) }
      : item,
  );
}

/** Pages reached from inside a section rather than from the sidebar. */
const unlistedPages = [
  { href: "/responses/upgrades", title: "Responses / Upgrade proposal" },
  { href: "/responses/tickets", title: "Responses / Response" },
  { href: "/responses/memos", title: "Responses / Regulator memo" },
  { href: "/organizations/new", title: "New organization" },
];

function isWithin(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * The header's name for a page, from the same navigation the sidebar shows: "Section / Page" inside a
 * group ("Inventory / Services"), the link's own name otherwise ("Advisories"). A detail page takes the
 * name of the list it belongs to (a service is under "Inventory / Services"); the longest match wins.
 */
export function pageTitle(pathname: string): string {
  const pages = [...baseNav, ...domainNav, ...accountNav, ...adminNav, ...orgAdminNav].flatMap((item) =>
    "items" in item
      ? item.items.map((sub) => ({ href: sub.href, title: `${item.title} / ${sub.title}` }))
      : [{ href: item.href, title: item.title }],
  );
  const match = [...pages, ...unlistedPages]
    .filter((page) => isWithin(pathname, page.href))
    .sort((a, b) => b.href.length - a.href.length)[0];
  return match?.title ?? "Aegis";
}
