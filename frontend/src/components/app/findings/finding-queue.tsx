"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { DataPagination } from "@/components/app/data-pagination";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError, findingApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { FindingQueueResponse } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

export function FindingQueue({ mine = false }: { mine?: boolean }) {
  const { user, isLoading } = useAuth();
  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (!canReadInventory(user?.roles ?? [])) {
    return <p>You don&apos;t have access to findings — an Aegis domain role is required.</p>;
  }
  // Reset both filters and results when the workspace or identity changes.
  return <Queue key={`${user?.orgId}:${user?.id}:${mine}`} mine={mine} />;
}

function Queue({ mine }: { mine: boolean }) {
  const [filters, setFilters] = useState({ serviceId: "", ownerId: "", page: 0 });
  const [data, setData] = useState<FindingQueueResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Reset the request state for new filters.
    setLoading(true);
    setError(null);
    findingApi.list({
      serviceId: filters.serviceId || undefined, ownerId: filters.ownerId || undefined,
      mine: mine ? "true" : undefined, page: filters.page, size: 20,
    }).then((result) => {
      if (!cancelled) setData(result);
    }).catch((err) => {
      if (!cancelled) setError(err instanceof ApiError && err.status === 403
        ? "You don't have access to findings — an Aegis domain role is required."
        : "Could not load findings. Please try again.");
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [filters, mine, refresh]);

  const items = data?.items;
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{mine ? "My services" : "Triage queue"}</h1>
        <p className="text-sm text-muted-foreground">
          Material vulnerabilities and vendor outages, highest impact first{mine ? ", for services you own" : ""}.
          Each change is ranked by its highest material service score within your filters.
        </p>
        <p className="mt-1 text-xs text-muted-foreground">
          Scores reflect the current estate. Security news has no package or vendor identity to score.
          Draft a response from an item&apos;s detail; decisions are in <Link href="/responses/review" className="underline">Review &amp; approve</Link>.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="space-y-1 text-sm">
          <span className="block text-xs font-medium">Service</span>
          <select aria-label="Service" className="h-9 max-w-72 rounded-md border bg-background px-3" value={filters.serviceId}
            onChange={(event) => setFilters({ ...filters, serviceId: event.target.value, page: 0 })}>
            <option value="">All services</option>
            {data?.services.map((service) => <option key={service.id} value={service.id}>{service.name}</option>)}
          </select>
        </label>
        <label className="space-y-1 text-sm">
          <span className="block text-xs font-medium">Owner</span>
          <select aria-label="Owner" className="h-9 max-w-72 rounded-md border bg-background px-3" value={filters.ownerId}
            onChange={(event) => setFilters({ ...filters, ownerId: event.target.value, page: 0 })}>
            <option value="">All owners</option>
            {data?.owners.map((owner) => <option key={owner.id} value={owner.id}>{owner.name}</option>)}
          </select>
        </label>
        {(filters.serviceId || filters.ownerId) &&
          <Button variant="ghost" size="sm" onClick={() => setFilters({ serviceId: "", ownerId: "", page: 0 })}>Clear filters</Button>}
        <Button variant="outline" size="sm" onClick={() => setRefresh((value) => value + 1)} disabled={loading}>Refresh</Button>
      </div>

      <section aria-label="Material findings" className="space-y-3" aria-busy={loading}>
        <div className="rounded-lg border">
          <Table>
            <TableHeader><TableRow>
              <TableHead>Change</TableHead><TableHead>Impacted services</TableHead>
              <TableHead>Owners</TableHead><TableHead className="text-right">Impact score</TableHead>
            </TableRow></TableHeader>
            <TableBody>
              {loading ? Array.from({ length: 3 }, (_, index) => <TableRow key={index}>
                {Array.from({ length: 4 }, (_, cell) => <TableCell key={cell}><Skeleton className="h-4 w-24" /></TableCell>)}
              </TableRow>) : error ? <Message>{error}</Message> : mine && data?.ownedServiceCount === 0 ?
                <Message>You aren&apos;t assigned as an owner of any service. An analyst can use the <Link href="/findings" className="underline">organization&apos;s triage queue</Link>.</Message>
                : items?.content.length ? items.content.map((item) => {
                  const owners = [...new Map(item.services.flatMap((service) => service.owners).map((owner) => [owner.id, owner])).values()];
                  return <TableRow key={item.change.id}>
                    <TableCell className="max-w-md align-top whitespace-normal">
                      <Link href={`/findings/${item.change.id}`} className="font-medium underline-offset-4 hover:underline">{item.change.title ?? item.change.externalId}</Link>
                      <span className="mt-1 block text-xs text-muted-foreground">{item.change.signalType === "VENDOR_OUTAGE" ? "Vendor outage" : "Vulnerability"} · {item.change.externalId}</span>
                    </TableCell>
                    <TableCell className="max-w-xs align-top whitespace-normal">
                      {item.services.map((impact) => <div key={impact.service.id}>
                        <Link href={`/inventory/services/${impact.service.id}`} className="hover:underline">{impact.service.name}</Link>
                        <span className="ml-2 text-xs text-muted-foreground">{impact.score.toFixed(1)}{impact.confidence === "POSSIBLE" && " · Needs review"}</span>
                      </div>)}
                    </TableCell>
                    <TableCell className="max-w-xs align-top whitespace-normal">{owners.map((owner) => owner.name).join(", ") || "No owners assigned"}</TableCell>
                    <TableCell className="text-right align-top">
                      <span className="font-mono font-semibold tabular-nums">{item.score.toFixed(1)}</span>
                      <span className="block text-xs text-muted-foreground">{item.tier.charAt(0) + item.tier.slice(1).toLowerCase()} impact</span>
                    </TableCell>
                  </TableRow>;
                }) : <Message>{filters.serviceId || filters.ownerId ? "No material findings match these filters." : "No material changes affect these services."}</Message>}
            </TableBody>
          </Table>
        </div>
        {!loading && !error && items && items.totalElements > 0 && <DataPagination page={items.number}
          totalPages={items.totalPages} totalElements={items.totalElements} pageSize={items.size}
          itemsOnPage={items.content.length} onPageChange={(page) => setFilters({ ...filters, page })} />}
      </section>
    </div>
  );
}

function Message({ children }: { children: React.ReactNode }) {
  return <TableRow><TableCell colSpan={4} className="py-10 text-center whitespace-normal text-muted-foreground">{children}</TableCell></TableRow>;
}
