import Link from "next/link";
import { Hexagon } from "lucide-react";

import { buttonVariants } from "@/components/ui/button";
import { getAuthConfig } from "@/lib/server/config";
import { siteConfig } from "@/lib/site";
import { cn } from "@/lib/utils";

export async function SiteHeader() {
  const { registrationEnabled } = await getAuthConfig();
  return (
    <header className="sticky top-0 z-50 w-full border-b border-border/60 bg-background/80 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div className="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
        <Link
          href="/"
          className="flex items-center gap-2 font-semibold tracking-tight"
        >
          <span className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <Hexagon className="size-4" />
          </span>
          {siteConfig.name}
        </Link>

        <nav className="hidden items-center gap-1 md:flex">
          {siteConfig.nav.map((item) => (
            <Link
              key={item.title}
              href={item.href}
              className="rounded-md px-3 py-2 text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              {item.title}
            </Link>
          ))}
        </nav>

        <div className="flex items-center gap-2">
          <Link
            href="/login"
            className={cn(buttonVariants({ variant: "ghost", size: "sm" }))}
          >
            Log in
          </Link>
          {registrationEnabled && (
            <Link href="/signup" className={cn(buttonVariants({ size: "sm" }))}>
              Sign up
            </Link>
          )}
        </div>
      </div>
    </header>
  );
}
