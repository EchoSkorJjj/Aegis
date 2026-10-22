"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";

import { DataPagination } from "@/components/app/data-pagination";
import { StageBadge, formatDateTime } from "@/components/app/responses/response-status";
import {
  FilterSelect,
  ImpactBadge,
  MessageRow,
  ResponseCell,
  TIERS,
  UpgradeSummary,
  tierLabel,
} from "@/components/app/responses/upgrade-recommendations";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError, findingApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { TriageItem, TriageQueueResponse, VendorOutage } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

const PAGE_SIZE = 20;
const COLUMNS = 5;

interface Filters {
  serviceId: string;
  ownerId: string;
  ecosystem: string;
  tier: string;
  page: number;
  openOnly: boolean;
  unassigned: boolean;
}

const NO_FILTERS: Filters = { serviceId: "", ownerId: "", ecosystem: "", tier: "", page: 0, openOnly: false, unassigned: false };

/**
 * The triage queue (E9 story 1): what to respond to next, highest impact first — each service's material vulnerable
 * dependencies with the version that fixes them, and the vendor outages still in progress. Each row links the
 * response already drafted for it or offers to draft one; a drafted response then waits in Review & approve.
 */
export function FindingQueue() {
  const { user, isLoading } = useAuth();
  const search = useSearchParams();
  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (!canReadInventory(user?.roles ?? [])) {
    return (
      <div className="mx-auto w-full max-w-6xl rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
        You don&apos;t have access to the triage queue — an Aegis domain role is required.
      </div>
    );
  }
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Triage queue</h1>
        <p className="text-sm text-muted-foreground">
          What to respond to next, highest impact first: each service&apos;s material vulnerable dependencies, with
          the version that fixes them, and the vendor outages still in progress. Drafting a response prepares its
          risk ticket and, where Aegis can automate it, the upgrade pull request; it then waits in{" "}
          <Link href="/responses/review" className="underline underline-offset-4 hover:text-foreground">
            Review &amp; approve
          </Link>
          .
        </p>
        <p className="mt-1 text-xs text-muted-foreground">
          Worked out from the current estate and feeds each time it loads. An outage leaves the queue once its vendor
          resolves it or it goes quiet for two weeks; security news names no package or vendor, so it isn&apos;t
          ranked here.
        </p>
      </div>
      {/* Another workspace or user starts over: its filters may name services this one doesn't have. */}
      <TriageList key={`${user?.orgId}:${user?.id}:${search.toString()}`} initialFilters={{
        serviceId: search.get("serviceId") ?? "", ownerId: search.get("ownerId") ?? "",
        openOnly: search.get("open") === "true", unassigned: search.get("unassigned") === "true",
      }} />
    </div>
  );
}

/**
 * The queue's items with their filters and paging. `mine` limits it to the services the caller is an assigned
 * owner of (their own page, so there is no owner to filter by), `initialServiceId` starts it on one of them, and
 * `onRefresh` also tells the page when the reader asks for fresh data.
 */
export function TriageList({
  mine = false,
  initialServiceId = "",
  initialFilters,
  onRefresh,
}: {
  mine?: boolean;
  initialServiceId?: string;
  initialFilters?: Partial<Filters>;
  onRefresh?: () => void;
}) {
  const [filters, setFilters] = useState<Filters>({ ...NO_FILTERS, ...initialFilters,
    serviceId: initialServiceId || initialFilters?.serviceId || "" });
  const [data, setData] = useState<TriageQueueResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);
  const [refresh, setRefresh] = useState(0);
  const filterId = useId();

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new request for the new filters
    setLoading(true);
    setError(null);
    findingApi
      .list({
        serviceId: filters.serviceId || undefined,
        ownerId: filters.ownerId || undefined,
        ecosystem: filters.ecosystem || undefined,
        tier: filters.tier || undefined,
        mine: mine ? "true" : undefined,
        open: filters.openOnly ? "true" : undefined,
        unassigned: filters.unassigned ? "true" : undefined,
        page: filters.page,
        size: PAGE_SIZE,
      })
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filters, mine, refresh]);

  const filtering = filters.serviceId !== "" || filters.ownerId !== "" || filters.ecosystem !== "" || filters.tier !== ""
    || filters.openOnly || filters.unassigned;
  const filter = (change: Partial<Filters>) => setFilters((current) => ({ ...current, ...change, page: 0 }));
  // The ecosystems follow the service and owner chosen; keep the one picked selectable when another drops it.
  const ecosystems = [...new Set([...(data?.ecosystems ?? []), filters.ecosystem].filter(Boolean))].sort();
  const items = data?.items;

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <FilterSelect
          id={`${filterId}-service`}
          label="Service"
          value={filters.serviceId}
          allLabel="All services"
          onChange={(serviceId) => filter({ serviceId })}
        >
          {data?.services.map((service) => (
            <option key={service.id} value={service.id}>
              {service.name}
            </option>
          ))}
        </FilterSelect>
        {!mine && (
          <FilterSelect
            id={`${filterId}-owner`}
            label="Owner"
            value={filters.ownerId}
            allLabel="All owners"
            onChange={(ownerId) => filter({ ownerId })}
          >
            {data?.owners.map((owner) => (
              <option key={owner.id} value={owner.id}>
                {owner.name}
              </option>
            ))}
          </FilterSelect>
        )}
        <FilterSelect
          id={`${filterId}-ecosystem`}
          label="Ecosystem"
          value={filters.ecosystem}
          allLabel="All ecosystems"
          onChange={(ecosystem) => filter({ ecosystem })}
        >
          {ecosystems.map((ecosystem) => (
            <option key={ecosystem} value={ecosystem}>
              {ecosystem}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          id={`${filterId}-impact`}
          label="Impact"
          value={filters.tier}
          allLabel="All impacts"
          onChange={(tier) => filter({ tier })}
        >
          {TIERS.map((tier) => (
            <option key={tier} value={tier}>
              {tierLabel(tier)}
            </option>
          ))}
        </FilterSelect>
        <label className="flex h-9 items-center gap-2 text-sm">
          <input type="checkbox" checked={filters.openOnly} onChange={(event) => filter({ openOnly: event.target.checked })} />
          Open items only
        </label>
        <label className="flex h-9 items-center gap-2 text-sm">
          <input type="checkbox" checked={filters.unassigned} onChange={(event) => filter({ unassigned: event.target.checked })} />
          Unassigned only
        </label>
        {filtering && (
          <Button variant="ghost" onClick={() => setFilters(NO_FILTERS)}>
            Clear
          </Button>
        )}
        <Button
          variant="outline"
          disabled={loading}
          onClick={() => {
            setRefresh((count) => count + 1);
            onRefresh?.();
          }}
        >
          Refresh
        </Button>
      </div>

      <section aria-label="Items to respond to" aria-busy={loading} className="space-y-3">
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Change</TableHead>
                <TableHead>Service</TableHead>
                <TableHead>Impact</TableHead>
                <TableHead>Stage</TableHead>
                <TableHead className="text-right">
                  <span className="sr-only">Response</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                Array.from({ length: 3 }).map((_, row) => (
                  <TableRow key={row}>
                    {Array.from({ length: COLUMNS }).map((__, cell) => (
                      <TableCell key={cell}>
                        <Skeleton className="h-4 w-28" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : error ? (
                <MessageRow columns={COLUMNS}>
                  {error === "forbidden"
                    ? "You don't have access to the triage queue — an Aegis domain role is required."
                    : "Could not load the triage queue. Please try again."}
                </MessageRow>
              ) : items && items.content.length > 0 ? (
                items.content.map((item) => <TriageRow key={rowKey(item)} item={item} />)
              ) : (
                <MessageRow columns={COLUMNS}>
                  {filtering
                    ? "Nothing matches these filters."
                    : mine
                      ? "Nothing to respond to in your services."
                      : "Nothing to respond to: no known vulnerability or vendor outage materially affects your " +
                        "services. Aegis checks again as advisories arrive and SBOMs are imported."}
                </MessageRow>
              )}
            </TableBody>
          </Table>
        </div>
        {!loading && !error && items && items.totalElements > 0 && (
          <DataPagination
            page={items.number}
            totalPages={items.totalPages}
            totalElements={items.totalElements}
            pageSize={items.size}
            itemsOnPage={items.content.length}
            onPageChange={(page) => setFilters((current) => ({ ...current, page }))}
          />
        )}
      </section>
    </div>
  );
}

function rowKey(item: TriageItem): string {
  return `${item.kind}:${item.service.id}:${item.upgrade?.component.id ?? item.changeSignalId}`;
}

function TriageRow({ item }: { item: TriageItem }) {
  return (
    <TableRow>
      <TableCell className="max-w-md align-top whitespace-normal">
        {item.upgrade ? (
          <UpgradeSummary item={item.upgrade} changeHref={(changeSignalId) => `/findings/${changeSignalId}`} />
        ) : (
          item.outage && <OutageSummary outage={item.outage} />
        )}
      </TableCell>
      <TableCell className="max-w-xs align-top whitespace-normal">
        <Link href={`/inventory/services/${item.service.id}`} className="font-medium underline-offset-4 hover:underline">
          {item.service.name}
        </Link>
        <span className="mt-0.5 block text-xs text-muted-foreground">
          {item.owners.length > 0 ? item.owners.map((owner) => owner.name).join(", ") : "No owners assigned"}
        </span>
      </TableCell>
      <TableCell className="align-top">
        <ImpactBadge score={item.score} tier={item.tier} />
        {item.confidence === "POSSIBLE" && (
          <span className="mt-1 block text-xs text-muted-foreground">Possible match — check the evidence</span>
        )}
      </TableCell>
      <TableCell className="align-top">
        <StageBadge stage={item.stage} />
        {/* The one thing the stage doesn't say: an approved response whose filing failed waits to be tried again. */}
        {item.response?.status === "FAILED" && (
          <span className="mt-1 block text-xs text-red-600 dark:text-red-400">Filing failed</span>
        )}
      </TableCell>
      <TableCell className="text-right align-top">
        <ResponseCell
          response={item.response}
          proposal={item.upgrade?.proposal ?? null}
          changeSignalId={item.changeSignalId}
          serviceId={item.service.id}
          canDraft={item.canDraft}
          showStatus={false}
        />
      </TableCell>
    </TableRow>
  );
}

/** A vendor outage still in progress: what its status page calls it, and since when. */
function OutageSummary({ outage }: { outage: VendorOutage }) {
  return (
    <>
      <Link href={`/findings/${outage.id}`} className="font-medium underline-offset-4 hover:underline">
        {outage.title ?? "Untitled incident"}
      </Link>
      <span className="mt-0.5 block text-xs text-muted-foreground">
        Vendor outage
        {outage.vendorName && ` · ${outage.vendorName}`}
        {outage.status && ` · ${outage.status}`}
        {outage.startedAt
          ? ` · since ${formatDateTime(outage.startedAt)}`
          : outage.lastUpdateAt && ` · updated ${formatDateTime(outage.lastUpdateAt)}`}
        {outage.url && /^https?:\/\//i.test(outage.url) && (
          <>
            {" · "}
            <a
              href={outage.url}
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary underline-offset-4 hover:underline"
            >
              Status page ↗
            </a>
          </>
        )}
      </span>
    </>
  );
}
