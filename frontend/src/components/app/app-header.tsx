"use client";

import { usePathname } from "next/navigation";

import { pageTitle } from "@/components/app/navigation";
import { SidebarTrigger } from "@/components/ui/sidebar";

/** The top bar: the sidebar toggle and where you are, named as the sidebar names it. */
export function AppHeader() {
  const pathname = usePathname();
  return (
    <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center gap-2 border-b border-border bg-background/80 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <SidebarTrigger className="-ml-1" />
      <div className="mr-1 h-4 w-px bg-border" aria-hidden />
      <p className="text-sm font-medium">{pageTitle(pathname)}</p>
    </header>
  );
}
