"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClipboardList, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { DataPagination } from "@/components/app/data-pagination";
import { TicketStatusBadge, UpgradeStatusBadge } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
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
import { ApiError, ticketApi, upgradeApi } from "@/lib/api";
import type { ImpactTier, UpgradeRecommendation } from "@/lib/types";

const TIER_CLASS: Record<ImpactTier, string> = {
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  LOW: "bg-muted text-muted-foreground",
  NEGLIGIBLE: "bg-muted text-muted-foreground/70",
};

const PAGE_SIZE = 10;

/**
 * One service's vulnerable dependencies (its page), including those another package pulls in, and the version
 * that fixes each — found from the service's side, so nobody has to know a CVE. Each row offers to draft a
 * response — the risk ticket, and the upgrade pull request where Aegis can automate it — or links the one already
 * drafted. Every service's, ranked with the vendor outages, is the triage queue (`/findings`), which renders its
 * rows with the same parts. `refreshSignal` re-reads the list, e.g. after an SBOM import.
 */
export function RecommendedUpgrades({
  serviceId,
  enabled,
  orgId,
  refreshSignal = 0,
}: {
  serviceId: string;
  enabled: boolean;
  orgId: string | null;
  refreshSignal?: number;
}) {
  const [items, setItems] = useState<UpgradeRecommendation[] | null>(null);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);
  const [impact, setImpact] = useState<ImpactTier | "">("");
  const [ecosystem, setEcosystem] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const filterId = useId();

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setError(null);
    upgradeApi
      .recommendations({ serviceId })
      .then((result) => {
        if (!cancelled) setItems(Array.isArray(result) ? result : []);
      })
      .catch((err) => {
        if (cancelled) return;
        setItems(null);
        setError(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, orgId, serviceId, refreshSignal]);

  const ecosystems = [...new Set((items ?? []).map((item) => item.component.ecosystem))].sort();
  const matches = (item: UpgradeRecommendation) =>
    (!impact || item.tier === impact) &&
    (!ecosystem || item.component.ecosystem === ecosystem) &&
    (!search.trim() || item.component.name.toLowerCase().includes(search.trim().toLowerCase()));
  const filtered = items?.filter(matches) ?? null;
  const totalPages = Math.max(1, Math.ceil((filtered?.length ?? 0) / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages - 1);
  const rows = filtered?.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE) ?? null;
  const filtering = impact !== "" || ecosystem !== "" || search.trim() !== "";
  const columns = 3;

  function clearFilters() {
    setImpact("");
    setEcosystem("");
    setSearch("");
    setPage(0);
  }

  return (
    <section aria-labelledby="vulnerable-dependencies-heading" className="space-y-3">
      <div>
        <h2 id="vulnerable-dependencies-heading" className="text-sm font-medium">
          Vulnerable dependencies
          {items && items.length > 0 && <span className="ml-1.5 text-muted-foreground">({items.length})</span>}
        </h2>
        <p className="text-xs text-muted-foreground">
          Every dependency of this service a known advisory affects, and the version that fixes it. A response drafts
          its risk ticket and, where Aegis can automate it, the upgrade pull request.
        </p>
      </div>

      {items && items.length > 0 && (
        <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
          <div className="w-full sm:w-56">
            <label htmlFor={`${filterId}-package`} className="mb-1 block text-xs font-medium text-muted-foreground">
              Package
            </label>
            <Input
              id={`${filterId}-package`}
              value={search}
              placeholder="Package name…"
              onChange={(event) => {
                setSearch(event.target.value);
                setPage(0);
              }}
            />
          </div>
          <FilterSelect
            id={`${filterId}-impact`}
            label="Impact"
            value={impact}
            allLabel="All impacts"
            onChange={(value) => {
              setImpact(value as ImpactTier | "");
              setPage(0);
            }}
          >
            {TIERS.map((tier) => (
              <option key={tier} value={tier}>
                {tierLabel(tier)}
              </option>
            ))}
          </FilterSelect>
          <FilterSelect
            id={`${filterId}-ecosystem`}
            label="Ecosystem"
            value={ecosystem}
            allLabel="All ecosystems"
            onChange={(value) => {
              setEcosystem(value);
              setPage(0);
            }}
          >
            {ecosystems.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </FilterSelect>
          {filtering && (
            <Button variant="ghost" onClick={clearFilters}>
              Clear
            </Button>
          )}
        </div>
      )}

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Upgrade</TableHead>
              <TableHead>Impact</TableHead>
              <TableHead className="text-right">
                <span className="sr-only">Action</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows === null && !error ? (
              Array.from({ length: 3 }).map((_, row) => (
                <TableRow key={row}>
                  {Array.from({ length: columns }).map((__, cell) => (
                    <TableCell key={cell}>
                      <Skeleton className="h-4 w-28" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : error ? (
              <MessageRow columns={columns}>
                {error === "forbidden"
                  ? "You don't have access to recommendations — an Aegis domain role is required."
                  : "Could not load recommended upgrades. Please try again."}
              </MessageRow>
            ) : rows && rows.length > 0 ? (
              rows.map((item) => (
                <TableRow key={`${item.serviceId}:${item.component.id}`}>
                  <TableCell className="max-w-md align-top whitespace-normal">
                    <UpgradeSummary item={item} />
                  </TableCell>
                  <TableCell className="align-top">
                    <ImpactBadge score={item.score} tier={item.tier} />
                  </TableCell>
                  <TableCell className="text-right align-top">
                    <ResponseCell
                      response={item.response}
                      proposal={item.proposal}
                      changeSignalId={item.changeSignalId}
                      serviceId={item.serviceId}
                      canDraft={item.canDraft}
                    />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <MessageRow columns={columns}>
                {filtering ? "Nothing matches these filters." : "No known vulnerabilities in this service's dependencies."}
              </MessageRow>
            )}
          </TableBody>
        </Table>
      </div>
      {filtered && filtered.length > 0 && (
        <DataPagination
          page={currentPage}
          totalPages={totalPages}
          totalElements={filtered.length}
          pageSize={PAGE_SIZE}
          itemsOnPage={rows?.length ?? 0}
          onPageChange={setPage}
        />
      )}
    </section>
  );
}

export const TIERS: ImpactTier[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "NEGLIGIBLE"];

export function tierLabel(tier: ImpactTier): string {
  return tier.charAt(0) + tier.slice(1).toLowerCase();
}

const SELECT_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs " +
  "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none";

export function FilterSelect({
  id,
  label,
  value,
  allLabel,
  onChange,
  children,
}: {
  id: string;
  label: string;
  value: string;
  allLabel: string;
  onChange: (value: string) => void;
  children: React.ReactNode;
}) {
  return (
    <div className="w-full sm:w-40">
      <label htmlFor={id} className="mb-1 block text-xs font-medium text-muted-foreground">
        {label}
      </label>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{allLabel}</option>
        {children}
      </select>
    </div>
  );
}

/**
 * What to upgrade and to which version, the vulnerabilities that clears, what pulls it in and where that is declared,
 * where the pull request overrides it when no manifest declares it, and why the pull request can't be automated when
 * it can't. `changeHref` is where a vulnerability links: its change's impact page
 * here, the item detail in the triage queue.
 */
export function UpgradeSummary({
  item,
  changeHref = (changeSignalId) => `/advisories/${changeSignalId}`,
}: {
  item: Omit<UpgradeRecommendation, "canDraft">;
  changeHref?: (changeSignalId: string) => string;
}) {
  const [first, ...others] = item.vulnerabilities;
  return (
    <>
      <span className="font-medium break-all">{item.component.name}</span>{" "}
      <span className="font-mono text-xs text-muted-foreground">
        {item.component.version}
        {item.toVersion && (
          <>
            {" "}
            → <span className="text-foreground">{item.toVersion}</span>
          </>
        )}
      </span>
      <span className="mt-0.5 block text-xs text-muted-foreground">
        {first && (
          <>
            Fixes{" "}
            {first.changeSignalId ? (
              <Link
                href={changeHref(first.changeSignalId)}
                className="font-mono text-foreground underline-offset-4 hover:underline"
              >
                {first.id}
              </Link>
            ) : (
              <span className="font-mono text-foreground">{first.id}</span>
            )}
            {others.length > 0 && ` and ${others.length} more`}
            {" · "}
          </>
        )}
        {item.component.ecosystem}
        {item.via && (
          <>
            {" · through "}
            <span className="font-mono">{item.via.name}</span>
          </>
        )}
        {item.manifestPaths.length > 0 && (
          <>
            {" · "}
            <span className="font-mono">{item.manifestPaths.join(", ")}</span>
          </>
        )}
      </span>
      {item.readiness === "READY" && item.overrideIn.length > 0 ? (
        <span className="mt-1 block text-xs text-muted-foreground">
          {item.via ? "Not declared, so " : "No manifest declares it, so "}Aegis overrides it in{" "}
          <span className="font-mono">{item.overrideIn.join(", ")}</span>.
        </span>
      ) : item.readiness === "NO_REPOSITORY" ? (
        <span className="mt-1 block text-xs text-muted-foreground">
          Ticket only until the service&apos;s repository is linked.{" "}
          <Link
            href={`/inventory/services/${item.serviceId}`}
            className="font-medium text-primary underline-offset-4 hover:underline"
          >
            Link it →
          </Link>
        </span>
      ) : (
        item.reason && <span className="mt-1 block text-xs text-muted-foreground">Ticket only: {item.reason}</span>
      )}
    </>
  );
}

/** The impact score and its tier, worded as an impact so it never reads as the service's own criticality. */
export function ImpactBadge({ score, tier }: { score: number; tier: ImpactTier }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="rounded-md border px-1.5 py-0.5 font-mono text-xs font-semibold tabular-nums">
        {score.toFixed(1)}
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${TIER_CLASS[tier]}`}>{tierLabel(tier)}</span>
    </span>
  );
}

/**
 * What can be done about it now: see the response already drafted for it, draft one, or who can. A pull
 * request drafted on its own before responses existed is linked as it is.
 */
export function ResponseCell({
  response,
  proposal,
  changeSignalId,
  serviceId,
  canDraft,
  showStatus = true,
}: {
  response: UpgradeRecommendation["response"];
  proposal: UpgradeRecommendation["proposal"];
  changeSignalId: string | null;
  serviceId: string;
  canDraft: boolean;
  /** The response's status badge; off where the row shows its stage instead (the triage queue). */
  showStatus?: boolean;
}) {
  if (response) {
    return (
      <span className="inline-flex flex-col items-end gap-1">
        {showStatus && <TicketStatusBadge status={response.status} />}
        <Link
          href={`/responses/tickets/${response.ticketId}`}
          className="text-xs font-medium text-primary underline-offset-4 hover:underline"
        >
          View response →
        </Link>
      </span>
    );
  }
  if (proposal && !proposal.riskTicketId) {
    return (
      <span className="inline-flex flex-col items-end gap-1">
        <UpgradeStatusBadge status={proposal.status} />
        <Link
          href={`/responses/upgrades/${proposal.id}`}
          className="text-xs font-medium text-primary underline-offset-4 hover:underline"
        >
          View proposal →
        </Link>
      </span>
    );
  }
  if (!changeSignalId) {
    return null;
  }
  if (!canDraft) {
    return (
      <span className="text-xs text-muted-foreground">
        An analyst or the service&apos;s owner can draft a response
      </span>
    );
  }
  return <DraftResponseButton changeSignalId={changeSignalId} serviceId={serviceId} />;
}

function DraftResponseButton({ changeSignalId, serviceId }: { changeSignalId: string; serviceId: string }) {
  const router = useRouter();
  const [drafting, setDrafting] = useState(false);

  async function draft() {
    setDrafting(true);
    try {
      const response = await ticketApi.propose({ changeSignalId, serviceId });
      toast.success("Response drafted — review it, then approve or dismiss it");
      router.push(`/responses/tickets/${response.id}`);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not draft the response.");
      setDrafting(false);
    }
  }

  return (
    <Button size="sm" disabled={drafting} onClick={() => void draft()}>
      {drafting ? (
        <>
          <Loader2 className="animate-spin" />
          Drafting…
        </>
      ) : (
        <>
          <ClipboardList />
          Draft response
        </>
      )}
    </Button>
  );
}

export function MessageRow({ columns, children }: { columns: number; children: React.ReactNode }) {
  return (
    <TableRow>
      <TableCell colSpan={columns} className="py-10 text-center whitespace-normal text-muted-foreground">
        {children}
      </TableCell>
    </TableRow>
  );
}
