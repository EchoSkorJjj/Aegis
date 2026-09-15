"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Ban, Check, CircleCheck, MoreHorizontal, ShieldCheck, ShieldOff, Trash2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { InviteUserForm } from "@/components/admin/invite-user-form";
import { useAuth } from "@/providers/auth-provider";
import { DOMAIN_ROLES } from "@/lib/roles";
import type { Page, Role, UserProfile } from "@/lib/types";

const PAGE_SIZE = 10;
const DEFAULT_SORT = "createdAt,desc";
const COLUMN_COUNT = 6;

export function AdminUsersTable() {
  const { user, isLoading: authLoading } = useAuth();
  const isAdmin = !!user?.roles.some((r) => r === "ORG_ADMIN" || r === "PLATFORM_ADMIN");
  const isPlatformAdmin = !!user?.roles.includes("PLATFORM_ADMIN");
  // Domain-role members may view the directory (read-only); admins additionally get row actions.
  const canSeeMembers = !!user?.roles.some((r) => r !== "USER");
  const columnCount = isAdmin ? COLUMN_COUNT : COLUMN_COUNT - 1;

  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState(""); // debounced
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sort, setSort] = useState(DEFAULT_SORT);
  const [page, setPage] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);

  const [data, setData] = useState<Page<UserProfile> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);

  // Debounce the search box, and reset to the first page on any new query.
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    if (authLoading || !canSeeMembers) return;

    const controller = new AbortController();
    // A new search / filter / page refetches from the server; surface the loading state right away.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);

    const params = new URLSearchParams();
    if (search) params.set("search", search);
    if (from) params.set("from", `${from}T00:00:00Z`);
    if (to) params.set("to", `${to}T23:59:59Z`);
    params.set("page", String(page));
    params.set("size", String(PAGE_SIZE));
    params.set("sort", sort);

    fetch(`/api/admin/users?${params.toString()}`, { signal: controller.signal })
      .then(async (res) => {
        if (!res.ok) {
          setData(null);
          setError(res.status === 403 ? "forbidden" : "error");
          return;
        }
        setData((await res.json()) as Page<UserProfile>);
      })
      .catch((err) => {
        if (err.name !== "AbortError") setError("error");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
    // user?.orgId: switching the active organization must refetch this org's members immediately.
  }, [authLoading, canSeeMembers, user?.orgId, search, from, to, page, sort, refreshKey]);

  function toggleSort(field: string) {
    const [current, dir] = sort.split(",");
    setSort(current === field ? `${field},${dir === "asc" ? "desc" : "asc"}` : `${field},asc`);
    setPage(0);
  }

  function clearFilters() {
    setSearchInput("");
    setSearch("");
    setFrom("");
    setTo("");
    setPage(0);
  }

  const refetch = () => setRefreshKey((k) => k + 1);

  if (!authLoading && user && !canSeeMembers) {
    return (
      <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
        You don&apos;t have access to view members.
      </div>
    );
  }

  const hasFilters = search !== "" || from !== "" || to !== "";

  return (
    <div className="flex flex-col gap-4">
      {isAdmin && (
        <div className="flex justify-end">
          <InviteUserForm onInvited={refetch} showRoles={!isPlatformAdmin} />
        </div>
      )}
      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="w-full sm:max-w-xs">
          <label className="mb-1 block text-xs font-medium text-muted-foreground">
            Search
          </label>
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Email or name…"
          />
        </div>
        <div className="flex items-end gap-2">
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              Joined from
            </label>
            <Input
              type="date"
              value={from}
              onChange={(e) => {
                setFrom(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              to
            </label>
            <Input
              type="date"
              value={to}
              onChange={(e) => {
                setTo(e.target.value);
                setPage(0);
              }}
            />
          </div>
          {hasFilters && (
            <Button variant="ghost" onClick={clearFilters}>
              Clear
            </Button>
          )}
        </div>
      </div>

      {/* Table */}
      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>
                <SortButton label="Email" field="email" sort={sort} onSort={toggleSort} />
              </TableHead>
              <TableHead>
                <SortButton label="Name" field="displayName" sort={sort} onSort={toggleSort} />
              </TableHead>
              <TableHead>Roles</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>
                <SortButton label="Joined" field="createdAt" sort={sort} onSort={toggleSort} />
              </TableHead>
              {isAdmin && (
                <TableHead className="w-10 text-right">
                  <span className="sr-only">Actions</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: columnCount }).map((__, j) => (
                    <TableCell key={j}>
                      <Skeleton className="h-4 w-24" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : error === "forbidden" ? (
              <TableRow>
                <TableCell colSpan={columnCount} className="py-10 text-center text-muted-foreground">
                  You are not authorized to view users.
                </TableCell>
              </TableRow>
            ) : error ? (
              <TableRow>
                <TableCell colSpan={columnCount} className="py-10 text-center text-muted-foreground">
                  Could not load users. Please try again.
                </TableCell>
              </TableRow>
            ) : data && data.content.length > 0 ? (
              data.content.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-medium">{u.email}</TableCell>
                  <TableCell>{u.displayName ?? "—"}</TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {u.roles.map((role) => (
                        <span
                          key={role}
                          className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground"
                        >
                          {role}
                        </span>
                      ))}
                    </div>
                  </TableCell>
                  <TableCell>
                    <StatusBadge enabled={u.enabled} emailVerified={u.emailVerified} />
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {new Date(u.createdAt).toLocaleDateString(undefined, {
                      year: "numeric",
                      month: "short",
                      day: "numeric",
                    })}
                  </TableCell>
                  {isAdmin && (
                    <TableCell className="text-right">
                      <RowActions
                        user={u}
                        currentUserId={user?.id}
                        onChanged={refetch}
                        callerIsPlatformAdmin={isPlatformAdmin}
                      />
                    </TableCell>
                  )}
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={columnCount} className="py-10 text-center text-muted-foreground">
                  No users match these filters.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </div>

      {/* Pagination */}
      {data && data.totalElements > 0 && (
        <div className="flex items-center justify-between text-sm text-muted-foreground">
          <span>
            {data.number * data.size + 1}–{data.number * data.size + data.content.length} of{" "}
            {data.totalElements}
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={data.first}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </Button>
            <span>
              Page {data.number + 1} of {Math.max(1, data.totalPages)}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={data.last}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

function RowActions({
  user,
  currentUserId,
  onChanged,
  callerIsPlatformAdmin,
}: {
  user: UserProfile;
  currentUserId?: string;
  onChanged: () => void;
  callerIsPlatformAdmin: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const isSelf = user.id === currentUserId;
  // The member's current org roles (everything but the USER baseline / platform roles). A role update
  // always re-sends USER — the backend keeps it as the baseline and drops it from the org membership.
  const orgRoles = user.roles.filter((r) => r !== "USER" && r !== "PLATFORM_ADMIN");
  const isPlatformAdminUser = user.roles.includes("PLATFORM_ADMIN");

  async function run(request: Promise<Response>, successMessage: string) {
    setBusy(true);
    try {
      const res = await request;
      if (!res.ok) {
        const body = (await res.json().catch(() => null)) as { message?: string } | null;
        toast.error(body?.message ?? "Action failed");
        return;
      }
      toast.success(successMessage);
      onChanged();
    } catch {
      toast.error("Network error — please try again");
    } finally {
      setBusy(false);
    }
  }

  // Guard against self-lockout: an admin cannot disable, demote, or delete their own account here.
  if (isSelf) {
    return (
      <Button
        variant="ghost"
        size="icon-sm"
        disabled
        aria-label="Actions unavailable for your own account"
      >
        <MoreHorizontal />
      </Button>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button variant="ghost" size="icon-sm" disabled={busy} aria-label="User actions">
            <MoreHorizontal />
          </Button>
        }
      />
      <DropdownMenuContent align="end" className="min-w-44">
        {callerIsPlatformAdmin ? (
          // Platform admin: identity-level break-glass only — no org-role assignment (ADR 0001).
          <>
            <DropdownMenuItem
              onClick={() =>
                run(
                  fetch(`/api/admin/users/${user.id}`, {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ enabled: !user.enabled }),
                  }),
                  user.enabled ? "User disabled" : "User enabled",
                )
              }
            >
              {user.enabled ? <Ban /> : <CircleCheck />}
              {user.enabled ? "Disable" : "Enable"}
            </DropdownMenuItem>
            <DropdownMenuItem
              onClick={() =>
                run(
                  fetch(`/api/admin/users/${user.id}/roles`, {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                      roles: isPlatformAdminUser ? ["USER"] : ["USER", "PLATFORM_ADMIN"],
                    }),
                  }),
                  isPlatformAdminUser ? "Platform admin removed" : "Promoted to platform admin",
                )
              }
            >
              {isPlatformAdminUser ? <ShieldOff /> : <ShieldCheck />}
              {isPlatformAdminUser ? "Remove platform admin" : "Make platform admin"}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              variant="destructive"
              onClick={() => {
                if (!window.confirm(`Permanently delete ${user.email}? This cannot be undone.`)) return;
                run(fetch(`/api/admin/users/${user.id}`, { method: "DELETE" }), "User deleted");
              }}
            >
              <Trash2 />
              Delete user
            </DropdownMenuItem>
          </>
        ) : (
          // Org admin: manage the member's domain roles + remove them from the organization.
          <>
            <DropdownMenuSub>
              <DropdownMenuSubTrigger>
                <ShieldCheck />
                Roles
              </DropdownMenuSubTrigger>
              <DropdownMenuSubContent>
                {DOMAIN_ROLES.map(({ value, label }) => {
                  const has = user.roles.includes(value);
                  const next: Role[] = has
                    ? orgRoles.filter((r) => r !== value)
                    : [...orgRoles, value];
                  return (
                    <DropdownMenuItem
                      key={value}
                      onClick={() =>
                        run(
                          fetch(`/api/admin/users/${user.id}/roles`, {
                            method: "PUT",
                            headers: { "Content-Type": "application/json" },
                            body: JSON.stringify({ roles: ["USER", ...next] }),
                          }),
                          `${label} ${has ? "removed" : "assigned"}`,
                        )
                      }
                    >
                      {has ? <Check /> : <span className="size-4" />}
                      {label}
                    </DropdownMenuItem>
                  );
                })}
              </DropdownMenuSubContent>
            </DropdownMenuSub>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              variant="destructive"
              onClick={() => {
                if (
                  !window.confirm(`Remove ${user.email} from this organization? Their account is kept.`)
                )
                  return;
                run(
                  fetch(`/api/admin/users/${user.id}`, { method: "DELETE" }),
                  "Removed from organization",
                );
              }}
            >
              <Trash2 />
              Remove from organization
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function SortButton({
  label,
  field,
  sort,
  onSort,
}: {
  label: string;
  field: string;
  sort: string;
  onSort: (field: string) => void;
}) {
  const [current, dir] = sort.split(",");
  const active = current === field;
  return (
    <button
      type="button"
      onClick={() => onSort(field)}
      className="inline-flex items-center gap-1 hover:text-foreground"
    >
      {label}
      <span className={active ? "text-foreground" : "text-muted-foreground/40"}>
        {active ? (dir === "asc" ? "↑" : "↓") : "↕"}
      </span>
    </button>
  );
}

function StatusBadge({
  enabled,
  emailVerified,
}: {
  enabled: boolean;
  emailVerified: boolean;
}) {
  return (
    <div className="flex items-center gap-2">
      <span
        className={
          enabled
            ? "inline-flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400"
            : "inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground"
        }
      >
        <span
          className={
            enabled
              ? "size-1.5 rounded-full bg-emerald-500"
              : "size-1.5 rounded-full bg-muted-foreground/40"
          }
        />
        {enabled ? "Active" : "Disabled"}
      </span>
      {!emailVerified && (
        <span className="text-xs text-amber-600 dark:text-amber-400">unverified</span>
      )}
    </div>
  );
}
