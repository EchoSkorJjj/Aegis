import { SidebarTrigger } from "@/components/ui/sidebar";

export function AppHeader({ title = "Dashboard" }: { title?: string }) {
  return (
    <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center gap-2 border-b border-border bg-background/80 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <SidebarTrigger className="-ml-1" />
      <div className="mr-1 h-4 w-px bg-border" aria-hidden />
      <h1 className="text-sm font-medium">{title}</h1>
    </header>
  );
}
