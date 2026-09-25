"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  BadgeCheck,
  Bell,
  Check,
  ChevronRight,
  ChevronsUpDown,
  Hexagon,
  LogOut,
  Plus,
} from "lucide-react";
import { toast } from "sonner";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  useSidebar,
} from "@/components/ui/sidebar";
import {
  accountNav,
  adminNav,
  baseNav,
  domainNav,
  type NavGroup,
  type NavItem,
} from "@/components/app/navigation";
import { canReadInventory } from "@/lib/roles";
import { useAuth } from "@/providers/auth-provider";

export function AppSidebar() {
  const { isMobile, state, setOpen } = useSidebar();
  const pathname = usePathname();
  const { user, organizations, switchOrg, logout } = useAuth();
  // Anyone with a role beyond the USER baseline can open the member directory (read-only for the
  // domain roles, full management for admins). The Users page itself gates what each can do.
  const canSeeMembers = !!user?.roles.some((r) => r !== "USER");
  // Domain data (inventory, advisories, and the rest of the estate features) needs a domain read role.
  const platformNav: NavItem[] = canReadInventory(user?.roles ?? [])
    ? [...baseNav, ...domainNav]
    : baseNav;

  const activeOrgName =
    organizations.find((org) => org.id === user?.orgId)?.name ?? "Workspace";
  const switchTo = async (orgId: string) => {
    if (orgId === user?.orgId) return;
    try {
      await switchOrg(orgId);
    } catch {
      toast.error("Could not switch organization");
    }
  };

  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>({});
  const isGroupActive = (group: NavGroup) =>
    group.items.some((sub) => pathname === sub.href || pathname.startsWith(`${sub.href}/`));
  // A group is open when the user has toggled it, otherwise when it contains the active route.
  const isGroupOpen = (group: NavGroup) => openGroups[group.title] ?? isGroupActive(group);
  const toggleGroup = (group: NavGroup) => {
    const open = isGroupOpen(group);
    setOpenGroups((prev) => ({ ...prev, [group.title]: !open }));
  };

  // When the sidebar is icon-collapsed, a group's inline sub-menu is hidden — so clicking a group
  // expands the sidebar and opens that group, rather than toggling a menu the user can't see.
  const onGroupClick = (group: NavGroup) => {
    if (state === "collapsed" && !isMobile) {
      setOpen(true);
      setOpenGroups((prev) => ({ ...prev, [group.title]: true }));
    } else {
      toggleGroup(group);
    }
  };

  const displayName = user?.displayName || user?.email || "User";
  const email = user?.email ?? "";
  const initial = displayName.charAt(0).toUpperCase();

  return (
    <Sidebar variant="sidebar" collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <DropdownMenu>
              <DropdownMenuTrigger
                render={
                  <SidebarMenuButton
                    size="lg"
                    className="data-[popup-open]:bg-sidebar-accent data-[popup-open]:text-sidebar-accent-foreground"
                  >
                    <span className="flex aspect-square size-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
                      <Hexagon className="size-4" />
                    </span>
                    <span className="grid flex-1 text-left leading-tight">
                      <span className="truncate font-semibold">{activeOrgName}</span>
                      <span className="truncate text-xs text-muted-foreground">Aegis</span>
                    </span>
                    <ChevronsUpDown className="ml-auto size-4" />
                  </SidebarMenuButton>
                }
              />
              <DropdownMenuContent
                className="w-(--anchor-width) min-w-56 rounded-lg"
                side={isMobile ? "bottom" : "right"}
                align="start"
                sideOffset={4}
              >
                <DropdownMenuGroup>
                  <DropdownMenuLabel>Organizations</DropdownMenuLabel>
                  {organizations.map((org) => (
                    <DropdownMenuItem key={org.id} onClick={() => switchTo(org.id)}>
                      <Hexagon className="size-4" />
                      <span className="truncate">{org.name}</span>
                      {org.id === user?.orgId && <Check className="ml-auto size-4" />}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuGroup>
                <DropdownMenuSeparator />
                <DropdownMenuItem render={<Link href="/organizations/new" />}>
                  <Plus className="size-4" />
                  Create organization
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>Platform</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {platformNav.map((item) =>
                "items" in item ? (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      isActive={isGroupActive(item)}
                      onClick={() => onGroupClick(item)}
                      tooltip={item.title}
                    >
                      <item.icon />
                      <span>{item.title}</span>
                      <ChevronRight
                        className={`ml-auto transition-transform ${
                          isGroupOpen(item) ? "rotate-90" : ""
                        }`}
                      />
                    </SidebarMenuButton>
                    {isGroupOpen(item) && (
                      <SidebarMenuSub>
                        {item.items.map((sub) => (
                          <SidebarMenuSubItem key={sub.href}>
                            <SidebarMenuSubButton
                              isActive={pathname === sub.href}
                              render={<Link href={sub.href} />}
                            >
                              <span>{sub.title}</span>
                            </SidebarMenuSubButton>
                          </SidebarMenuSubItem>
                        ))}
                      </SidebarMenuSub>
                    )}
                  </SidebarMenuItem>
                ) : (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      isActive={pathname === item.href}
                      render={<Link href={item.href} />}
                      tooltip={item.title}
                    >
                      <item.icon />
                      <span>{item.title}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ),
              )}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        {canSeeMembers && (
          <SidebarGroup>
            <SidebarGroupLabel>Organization</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {adminNav.map((item) => (
                  <SidebarMenuItem key={item.title}>
                    <SidebarMenuButton
                      isActive={pathname === item.href}
                      render={<Link href={item.href} />}
                      tooltip={item.title}
                    >
                      <item.icon />
                      <span>{item.title}</span>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                ))}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        )}

        <SidebarGroup>
          <SidebarGroupLabel>Account</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {accountNav.map((item) => (
                <SidebarMenuItem key={item.title}>
                  <SidebarMenuButton
                    isActive={pathname === item.href}
                    render={<Link href={item.href} />}
                    tooltip={item.title}
                  >
                    <item.icon />
                    <span>{item.title}</span>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>

      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <DropdownMenu>
              <DropdownMenuTrigger
                render={
                  <SidebarMenuButton
                    size="lg"
                    className="data-[popup-open]:bg-sidebar-accent data-[popup-open]:text-sidebar-accent-foreground"
                  >
                    <Avatar className="size-8 rounded-lg">
                      <AvatarFallback className="rounded-lg">
                        {initial}
                      </AvatarFallback>
                    </Avatar>
                    <div className="grid flex-1 text-left text-sm leading-tight">
                      <span className="truncate font-semibold">
                        {displayName}
                      </span>
                      <span className="truncate text-xs text-muted-foreground">
                        {email}
                      </span>
                    </div>
                    <ChevronsUpDown className="ml-auto size-4" />
                  </SidebarMenuButton>
                }
              />
              <DropdownMenuContent
                className="w-(--anchor-width) min-w-56 rounded-lg"
                side={isMobile ? "bottom" : "right"}
                align="end"
                sideOffset={4}
              >
                <div className="flex items-center gap-2 px-2 py-1.5 text-left text-sm">
                  <Avatar className="size-8 rounded-lg">
                    <AvatarFallback className="rounded-lg">
                      {initial}
                    </AvatarFallback>
                  </Avatar>
                  <div className="grid flex-1 text-left text-sm leading-tight">
                    <span className="truncate font-semibold">{displayName}</span>
                    <span className="truncate text-xs text-muted-foreground">
                      {email}
                    </span>
                  </div>
                </div>
                <DropdownMenuSeparator />
                <DropdownMenuItem>
                  <BadgeCheck className="size-4" />
                  Account
                </DropdownMenuItem>
                <DropdownMenuItem>
                  <Bell className="size-4" />
                  Notifications
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem variant="destructive" onClick={() => logout()}>
                  <LogOut className="size-4" />
                  Log out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  );
}
