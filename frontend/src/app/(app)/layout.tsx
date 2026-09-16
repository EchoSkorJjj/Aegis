"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { AppHeader } from "@/components/app/app-header";
import { AppSidebar } from "@/components/app/app-sidebar";
import { NoOrgScreen } from "@/components/app/no-org-screen";
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar";
import { TooltipProvider } from "@/components/ui/tooltip";
import { useAuth } from "@/providers/auth-provider";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { user, isLoading } = useAuth();
  const router = useRouter();

  // Client-side route guard for the authenticated app shell.
  useEffect(() => {
    if (!isLoading && !user) {
      router.replace("/login");
    }
  }, [isLoading, user, router]);

  if (isLoading || !user) {
    return (
      <div className="flex min-h-dvh items-center justify-center text-sm text-muted-foreground">
        Loading…
      </div>
    );
  }

  // A signed-in user with no organization yet can't use the app shell — send them to create or
  // join one first (ADR 0001). The platform admin is intentionally org-less (it operates across
  // tenants), so it skips this gate.
  const isPlatformAdmin = user.roles.includes("PLATFORM_ADMIN");
  if (!user.orgId && !isPlatformAdmin) {
    return <NoOrgScreen />;
  }

  return (
    // delay=0 so collapsed-sidebar icon tooltips appear immediately on hover
    // (without a provider, Base UI falls back to its ~600ms default).
    <TooltipProvider delay={0}>
      <SidebarProvider>
        <AppSidebar />
        <SidebarInset>
          <AppHeader />
          <div className="flex-1 p-4 sm:p-6">{children}</div>
        </SidebarInset>
      </SidebarProvider>
    </TooltipProvider>
  );
}
