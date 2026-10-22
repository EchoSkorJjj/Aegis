"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowUpRight, RefreshCw } from "lucide-react";

import { RiskTrend } from "@/components/app/dashboard/risk-trend";
import { DataPagination } from "@/components/app/data-pagination";
import { Button, buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError, findingApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { PostureBreakdown, RiskPostureDashboard } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

export function RiskDashboard() {
  const { user, isLoading } = useAuth();
  if (isLoading) return <Skeleton className="h-64 w-full" />;
  if (!canReadInventory(user?.roles ?? [])) {
    return (
      <div className="mx-auto w-full max-w-6xl space-y-4">
        <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
        <div className="rounded-xl border bg-card p-6 text-sm text-muted-foreground">
          <p>Risk posture is available to analysts, compliance admins and service owners in their organization.</p>
          {user?.roles.includes("ORG_ADMIN") && (
            <Link href="/admin/users" className="mt-3 inline-block text-foreground underline underline-offset-4">
              Manage organization members
            </Link>
          )}
        </div>
      </div>
    );
  }
  // Discard data and in-flight results when the workspace or signed-in user changes.
  return <Posture key={`${user?.orgId}:${user?.id}`} />;
}

function Posture() {
  const [days, setDays] = useState(30);
  const [trendScope, setTrendScope] = useState("organization");
  const [refresh, setRefresh] = useState(0);
  const [data, setData] = useState<RiskPostureDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load the selected period or a refreshed observation
    setLoading(true);
    setError(null);
    findingApi.posture(days)
      .then((result) => { if (!cancelled) setData(result); })
      .catch((err) => {
        if (!cancelled) setError(err instanceof ApiError && err.status === 403
          ? "You don't have access to risk posture in this organization."
          : "Could not load risk posture. Please try again.");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [days, refresh]);

  const totals = data?.totals;
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="max-w-2xl space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">Risk posture</h1>
          <p className="text-sm text-muted-foreground">Open material vulnerabilities and vendor outages across your organization.</p>
          <p className="text-xs leading-relaxed text-muted-foreground">
            Open means New, In review, Approved or Overridden. Actioned and dismissed responses are excluded;
            filing a response does not confirm that its underlying risk is fixed.
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Button variant="outline" disabled={loading} onClick={() => setRefresh((value) => value + 1)}>
            <RefreshCw aria-hidden="true" className={loading ? "animate-spin" : ""} /> Refresh
          </Button>
          {/* A link styled as a button: it navigates, so assistive technology must announce a link. */}
          <Link href="/findings?open=true" className={buttonVariants()}>
            Open triage <ArrowUpRight aria-hidden="true" />
          </Link>
        </div>
      </header>

      {error ? (
        <div role="alert" aria-label="Risk posture error" className="rounded-xl border p-8 text-center text-sm">
          <p>{error}</p>
          <Button className="mt-3" variant="outline" onClick={() => setRefresh((value) => value + 1)}>Try again</Button>
        </div>
      ) : loading ? (
        <div role="status" aria-label="Loading risk posture" className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {Array.from({ length: 4 }, (_, index) => <Skeleton key={index} className="h-32" />)}
          </div>
          <Skeleton className="h-72" />
        </div>
      ) : data && totals && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Metric label="Open material items" value={totals.openItems} detail="One item per dependency or outage, per service" />
            <Metric label="Critical and high" value={totals.criticalItems + totals.highItems}
              detail={`${totals.criticalItems} critical · ${totals.highItems} high`} />
            <Metric label="Affected services" value={`${totals.affectedServices} / ${totals.totalServices}`} detail="Services with at least one open material item" />
            <Metric label="Unassigned items" value={totals.unassignedItems} detail="In services with no assigned owner"
              href="/findings?open=true&unassigned=true" />
          </div>

          <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-sm">
            <span><strong className="tabular-nums">{totals.newItems}</strong> New</span>
            <span><strong className="tabular-nums">{totals.inReviewItems}</strong> In review</span>
            <span><strong className="tabular-nums">{totals.approvedItems}</strong> Approved / Overridden</span>
            <span className="text-xs text-muted-foreground sm:ml-auto">
              Updated <time dateTime={data.asOf}>{new Date(data.asOf).toLocaleString()}</time>
            </span>
          </div>

          {totals.totalServices === 0 ? (
            <div className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
              No services registered yet. <Link href="/inventory/services" className="text-foreground underline underline-offset-4">Add your estate</Link> to start monitoring risk.
            </div>
          ) : totals.openItems === 0 && (
            <p role="status" className="rounded-xl border bg-muted/30 p-5 text-sm">
              No open material items. Aegis checks the current estate and feeds; import SBOMs to keep dependency coverage up to date.
            </p>
          )}

          <RiskTrend data={data} days={days} onDaysChange={setDays} scope={trendScope} onScopeChange={setTrendScope} />

          <div className="grid gap-6 lg:grid-cols-2">
            <BreakdownTable title="By service" rows={data.services} kind="service" />
            <BreakdownTable title="By owner" rows={data.owners} kind="owner" />
          </div>
        </>
      )}
    </div>
  );
}

function Metric({ label, value, detail, href }: { label: string; value: number | string; detail: string; href?: string }) {
  return (
    <section aria-label={label} className="rounded-xl border bg-card p-5">
      <h2 className="text-sm text-muted-foreground">{label}</h2>
      <p className="mt-2 text-3xl font-semibold tracking-tight tabular-nums">
        {href ? <Link href={href} className="underline-offset-4 hover:underline">{value}<span className="sr-only"> {label}</span></Link> : value}
      </p>
      <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{detail}</p>
    </section>
  );
}

function BreakdownTable({ title, rows, kind }: { title: string; rows: PostureBreakdown[]; kind: "service" | "owner" }) {
  const [page, setPage] = useState(0);
  const pageSize = 10;
  const current = Math.min(page, Math.max(0, Math.ceil(rows.length / pageSize) - 1));
  const visible = rows.slice(current * pageSize, (current + 1) * pageSize);
  return (
    <section aria-label={title} className="min-w-0 space-y-3">
      <div>
        <h2 className="text-base font-semibold">{title}</h2>
        <p className="text-xs text-muted-foreground">
          {kind === "owner" ? "Shared items count for each assigned owner; owner totals can overlap." : "Ranked by open items, then critical impact. Select a service to triage."}
        </p>
      </div>
      <div className="overflow-hidden rounded-xl border">
        <Table>
          <TableHeader><TableRow>
            <TableHead>{kind === "service" ? "Service" : "Owner"}</TableHead>
            <TableHead className="text-right">Open</TableHead>
            <TableHead className="text-right">Critical</TableHead>
            <TableHead className="text-right">High</TableHead>
            <TableHead className="text-right">Top score</TableHead>
          </TableRow></TableHeader>
          <TableBody>
            {visible.length === 0 ? <TableRow><TableCell colSpan={5} className="py-8 text-center text-muted-foreground">No services registered.</TableCell></TableRow>
              : visible.map((row) => (
                <TableRow key={row.id ?? "unassigned"}>
                  <TableCell className="max-w-60 whitespace-normal">
                    <Link href={`/findings?open=true&${row.id === null ? "unassigned=true" : `${kind}Id=${encodeURIComponent(row.id)}`}`}
                      className="font-medium underline-offset-4 hover:underline">{row.name}</Link>
                  </TableCell>
                  <TableCell className="text-right font-semibold tabular-nums">{row.openItems}</TableCell>
                  <TableCell className="text-right tabular-nums">{row.criticalItems}</TableCell>
                  <TableCell className="text-right tabular-nums">{row.highItems}</TableCell>
                  <TableCell className="text-right tabular-nums">{row.topScore?.toFixed(1) ?? "—"}</TableCell>
                </TableRow>
              ))}
          </TableBody>
        </Table>
      </div>
      {rows.length > pageSize && <DataPagination page={current} totalPages={Math.ceil(rows.length / pageSize)}
        totalElements={rows.length} pageSize={pageSize} itemsOnPage={visible.length} onPageChange={setPage} />}
    </section>
  );
}
