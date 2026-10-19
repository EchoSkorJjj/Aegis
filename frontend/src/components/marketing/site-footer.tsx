import Link from "next/link";
import { Hexagon } from "lucide-react";

import { getAuthConfig } from "@/lib/server/config";
import { siteConfig } from "@/lib/site";

export async function SiteFooter() {
  const { registrationEnabled } = await getAuthConfig();
  const footerNav = [
    { title: "Product", links: siteConfig.nav.map((item) => ({ label: item.title, href: item.href })) },
    {
      title: "Account",
      links: [
        { label: "Log in", href: "/login" },
        ...(registrationEnabled ? [{ label: "Create an account", href: "/signup" }] : []),
      ],
    },
    { title: "Legal", links: [{ label: "Privacy", href: "/privacy" }] },
  ];

  return (
    <footer className="border-t border-border/60">
      <div className="mx-auto grid w-full max-w-6xl gap-8 px-4 py-12 sm:px-6 md:grid-cols-[1.5fr_repeat(3,1fr)]">
        <div className="space-y-3">
          <div className="flex items-center gap-2 font-semibold tracking-tight">
            <span className="flex size-6 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Hexagon className="size-3.5" />
            </span>
            {siteConfig.name}
          </div>
          <p className="max-w-xs text-sm text-muted-foreground">{siteConfig.description}</p>
        </div>

        {footerNav.map((column) => (
          <div key={column.title} className="space-y-3">
            <div className="text-sm font-medium">{column.title}</div>
            <ul className="space-y-2">
              {column.links.map((link) => (
                <li key={link.label}>
                  <Link
                    href={link.href}
                    className="text-sm text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div className="border-t border-border/60">
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center justify-between gap-2 px-4 py-6 text-sm text-muted-foreground sm:flex-row sm:px-6">
          <span>
            © {new Date().getFullYear()} {siteConfig.name}
          </span>
          <span>Drafts only: a person approves every action.</span>
        </div>
      </div>
    </footer>
  );
}
