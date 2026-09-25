import {
  Boxes,
  FileText,
  GitPullRequest,
  LayoutDashboard,
  type LucideIcon,
  Rss,
  Settings,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";

export type NavLink = { title: string; href: string; icon: LucideIcon };
export type NavGroup = { title: string; icon: LucideIcon; items: { title: string; href: string }[] };
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
      { title: "Dependency map", href: "/inventory/map" },
    ],
  },
  { title: "Advisories", href: "/advisories", icon: Rss },
  {
    title: "Findings",
    icon: ShieldAlert,
    items: [
      { title: "Triage queue", href: "/findings" },
      { title: "My services", href: "/findings/mine" },
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

/** Pages reached from inside a section rather than from the sidebar. */
const unlistedPages = [
  { href: "/responses/upgrades", title: "Responses / Upgrade proposal" },
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
  const pages = [...baseNav, ...domainNav, ...accountNav, ...adminNav].flatMap((item) =>
    "items" in item
      ? item.items.map((sub) => ({ href: sub.href, title: `${item.title} / ${sub.title}` }))
      : [{ href: item.href, title: item.title }],
  );
  const match = [...pages, ...unlistedPages]
    .filter((page) => isWithin(pathname, page.href))
    .sort((a, b) => b.href.length - a.href.length)[0];
  return match?.title ?? "Aegis";
}
