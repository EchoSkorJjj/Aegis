"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronDown, GitPullRequest, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { DataPagination } from "@/components/app/data-pagination";
import { UpgradeStatusBadge } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
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
import { ApiError, upgradeApi } from "@/lib/api";
import type { ImpactTier, UpgradeRecommendation } from "@/lib/types";

const TIER_CLASS: Record<ImpactTier, string> = {
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  LOW: "bg-muted text-muted-foreground",
  NEGLIGIBLE: "bg-muted text-muted-foreground/70",
};

/**
 * The upgrades Aegis recommends, found from the services' side — nobody has to know a CVE to find them.
 * With a `serviceId`, one service's vulnerable dependencies (its page), including those another package
 * pulls in; without one, every service's direct ones (the review queue), highest impact first.
 * `refreshSignal` re-reads the list, e.g. after an SBOM import.
 */
export function RecommendedUpgrades({
  serviceId,
  enabled,
  orgId,
  refreshSignal = 0,
}: {
  serviceId?: string;
  enabled: boolean;
  orgId: string | null;
  refreshSignal?: number;
}) {
  const [items, setItems] = useState<UpgradeRecommendation[] | null>(null);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);
  const [impact, setImpact] = useState<ImpactTier | "">("");
  const [ecosystem, setEcosystem] = useState("");
  const [search, setSearch] = useState("");
  const [selectedServices, setSelectedServices] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const filterId = useId();
  const forService = serviceId !== undefined;
  const headingId = forService ? "vulnerable-dependencies-heading" : "recommended-upgrades-heading";

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setError(null);
    upgradeApi
      .recommendations(forService ? { serviceId } : {})
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
  }, [enabled, orgId, serviceId, forService, refreshSignal]);

  // The review queue lists what can be drafted; what comes in through another package is on each service's page.
  const base = items === null ? null : forService ? items : items.filter((item) => item.readiness !== "TRANSITIVE");
  const ecosystems = [...new Set((base ?? []).map((item) => item.component.ecosystem))].sort();
  const services = [...new Map((base ?? []).map((item) => [item.serviceId, item.serviceName])).entries()]
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name));
  const matches = (item: UpgradeRecommendation) =>
    (!impact || item.tier === impact) &&
    (!ecosystem || item.component.ecosystem === ecosystem) &&
    (!search.trim() || item.component.name.toLowerCase().includes(search.trim().toLowerCase())) &&
    (selectedServices.length === 0 || selectedServices.includes(item.serviceId));
  const filtered = base?.filter(matches) ?? null;
  const elsewhere =
    items === null || forService ? 0 : items.filter((item) => item.readiness === "TRANSITIVE" && matches(item)).length;
  const pageSize = forService ? 10 : 20;
  const totalPages = Math.max(1, Math.ceil((filtered?.length ?? 0) / pageSize));
  const currentPage = Math.min(page, totalPages - 1);
  const rows = filtered?.slice(currentPage * pageSize, (currentPage + 1) * pageSize) ?? null;
  const filtering = impact !== "" || ecosystem !== "" || search.trim() !== "" || selectedServices.length > 0;
  const columns = forService ? 3 : 4;

  function clearFilters() {
    setImpact("");
    setEcosystem("");
    setSearch("");
    setSelectedServices([]);
    setPage(0);
  }

  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <div>
        <h2 id={headingId} className="text-sm font-medium">
          {forService ? "Vulnerable dependencies" : "Recommended upgrades"}
          {base && base.length > 0 && <span className="ml-1.5 text-muted-foreground">({base.length})</span>}
        </h2>
        <p className="text-xs text-muted-foreground">
          {forService
            ? "Every dependency of this service a known advisory affects, and the version that fixes it."
            : "Aegis checks every service's dependencies against the advisories it ingests, so there is nothing " +
              "to look up. Drafting one shows the exact pull request; nothing is opened until someone approves it."}
        </p>
      </div>

      {base && base.length > 0 && (
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
          {!forService && (
            <ServiceFilter
              options={services}
              selected={selectedServices}
              onChange={(selected) => {
                setSelectedServices(selected);
                setPage(0);
              }}
            />
          )}
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
              {!forService && <TableHead>Service</TableHead>}
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
                <RecommendationRow key={`${item.serviceId}:${item.component.id}`} item={item} showService={!forService} />
              ))
            ) : (
              <MessageRow columns={columns}>
                {filtering
                  ? "Nothing matches these filters."
                  : forService
                    ? "No known vulnerabilities in this service's dependencies."
                    : "No known vulnerabilities in your services' direct dependencies. Aegis checks again as " +
                      "advisories arrive and SBOMs are imported."}
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
          pageSize={pageSize}
          itemsOnPage={rows?.length ?? 0}
          onPageChange={setPage}
        />
      )}
      {elsewhere > 0 && (
        <p className="text-xs text-muted-foreground">
          {elsewhere} more vulnerable {elsewhere === 1 ? "dependency comes" : "dependencies come"} in through other
          packages — each service&apos;s page lists them with what to upgrade.
        </p>
      )}
    </section>
  );
}

const TIERS: ImpactTier[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "NEGLIGIBLE"];

function tierLabel(tier: ImpactTier): string {
  return tier.charAt(0) + tier.slice(1).toLowerCase();
}

const SELECT_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs " +
  "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none";

function FilterSelect({
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

/** Several services at once; none selected means every service. */
function ServiceFilter({
  options,
  selected,
  onChange,
}: {
  options: { id: string; name: string }[];
  selected: string[];
  onChange: (selected: string[]) => void;
}) {
  const summary =
    selected.length === 0
      ? "All services"
      : selected.length === 1
        ? (options.find((option) => option.id === selected[0])?.name ?? "1 service")
        : `${selected.length} services`;
  return (
    <div className="w-full sm:w-52">
      <span className="mb-1 block text-xs font-medium text-muted-foreground">Services</span>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={<Button variant="outline" aria-label="Filter by service" className="w-full justify-between font-normal" />}
        >
          <span className="truncate">{summary}</span>
          <ChevronDown className="opacity-60" />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="max-h-72 w-56 overflow-y-auto">
          <DropdownMenuGroup>
            <DropdownMenuLabel>Show services</DropdownMenuLabel>
            {options.map((option) => (
              <DropdownMenuCheckboxItem
                key={option.id}
                checked={selected.includes(option.id)}
                onCheckedChange={(checked) =>
                  onChange(checked ? [...selected, option.id] : selected.filter((id) => id !== option.id))
                }
              >
                {option.name}
              </DropdownMenuCheckboxItem>
            ))}
          </DropdownMenuGroup>
          {selected.length > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => onChange([])}>Show all services</DropdownMenuItem>
            </>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

function RecommendationRow({ item, showService }: { item: UpgradeRecommendation; showService: boolean }) {
  const [first, ...others] = item.vulnerabilities;
  return (
    <TableRow>
      <TableCell className="max-w-md align-top whitespace-normal">
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
                  href={`/advisories/${first.changeSignalId}`}
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
          {item.manifestPath && (
            <>
              {" · "}
              <span className="font-mono">{item.manifestPath}</span>
            </>
          )}
        </span>
        {item.reason && item.readiness !== "NO_REPOSITORY" && (
          <span className="mt-1 block text-xs text-muted-foreground">{item.reason}</span>
        )}
      </TableCell>
      {showService && (
        <TableCell className="align-top">
          <Link
            href={`/inventory/services/${item.serviceId}`}
            className="underline-offset-4 hover:underline"
          >
            {item.serviceName}
          </Link>
        </TableCell>
      )}
      <TableCell className="align-top">
        <span className="inline-flex items-center gap-1.5">
          <span className="rounded-md border px-1.5 py-0.5 font-mono text-xs font-semibold tabular-nums">
            {item.score.toFixed(1)}
          </span>
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${TIER_CLASS[item.tier]}`}>
            {item.tier.charAt(0)}
            {item.tier.slice(1).toLowerCase()}
          </span>
        </span>
      </TableCell>
      <TableCell className="text-right align-top">
        <RecommendationAction item={item} />
      </TableCell>
    </TableRow>
  );
}

/** What can be done about it now: see the draft, draft one, link the repository — or nothing, with why. */
function RecommendationAction({ item }: { item: UpgradeRecommendation }) {
  if (item.proposal) {
    return (
      <span className="inline-flex flex-col items-end gap-1">
        <UpgradeStatusBadge status={item.proposal.status} />
        <Link
          href={`/responses/upgrades/${item.proposal.id}`}
          className="text-xs font-medium text-primary underline-offset-4 hover:underline"
        >
          View proposal →
        </Link>
      </span>
    );
  }
  if (item.readiness === "NO_REPOSITORY") {
    return (
      <Link
        href={`/inventory/services/${item.serviceId}`}
        className="text-xs font-medium text-primary underline-offset-4 hover:underline"
      >
        Link the repository →
      </Link>
    );
  }
  if (item.readiness !== "READY") {
    return null;
  }
  if (!item.canDraft) {
    return (
      <span className="text-xs text-muted-foreground">
        An analyst or the service&apos;s owner can draft it
      </span>
    );
  }
  return <DraftButton item={item} />;
}

function DraftButton({ item }: { item: UpgradeRecommendation }) {
  const router = useRouter();
  const [drafting, setDrafting] = useState(false);

  async function draft() {
    if (!item.changeSignalId) return;
    setDrafting(true);
    try {
      const proposal = await upgradeApi.propose({
        changeSignalId: item.changeSignalId,
        serviceId: item.serviceId,
        componentId: item.component.id,
      });
      toast.success("Upgrade drafted — review the pull request, then approve or dismiss it");
      router.push(`/responses/upgrades/${proposal.id}`);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not draft the upgrade.");
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
          <GitPullRequest />
          Draft PR
        </>
      )}
    </Button>
  );
}

function MessageRow({ columns, children }: { columns: number; children: React.ReactNode }) {
  return (
    <TableRow>
      <TableCell colSpan={columns} className="py-10 text-center whitespace-normal text-muted-foreground">
        {children}
      </TableCell>
    </TableRow>
  );
}
