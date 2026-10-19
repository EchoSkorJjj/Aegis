"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

import { DataPagination } from "@/components/app/data-pagination";
import { DateRangePicker } from "@/components/app/date-range-picker";
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
import { ApiError, signalApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import { cn } from "@/lib/utils";
import type { ChangeSignal, Page, SignalType } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

const PAGE_SIZE = 20;
const COLUMN_COUNT = 4;

const SELECT_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs " +
  "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none";

const SIGNAL_TYPE: Record<SignalType, { label: string; className: string }> = {
  VULNERABILITY: { label: "Vulnerability", className: "bg-red-500/10 text-red-600 dark:text-red-400" },
  VENDOR_OUTAGE: { label: "Vendor outage", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
  SECURITY_NEWS: { label: "Security news", className: "bg-sky-500/10 text-sky-600 dark:text-sky-400" },
};

const TYPE_OPTIONS: { value: SignalType; label: string }[] = [
  { value: "VULNERABILITY", label: "Vulnerability" },
  { value: "VENDOR_OUTAGE", label: "Vendor outage" },
  { value: "SECURITY_NEWS", label: "Security news" },
];

export default function AdvisoriesPage() {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);

  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [type, setType] = useState("");
  const [severity, setSeverity] = useState("");
  const [ecosystem, setEcosystem] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [pageIndex, setPageIndex] = useState(0);

  const [severityOptions, setSeverityOptions] = useState<string[]>([]);
  const [ecosystemOptions, setEcosystemOptions] = useState<string[]>([]);
  const [data, setData] = useState<Page<ChangeSignal> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);

  // Debounce the search box; any new query returns to the first page.
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPageIndex(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Severity filter options (distinct, upper-cased server-side). Refetch on org switch — read gate is per-org.
  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    signalApi
      .facets()
      .then((result) => {
        if (cancelled) return;
        setSeverityOptions(result.severities);
        setEcosystemOptions(result.ecosystems);
      })
      .catch(() => {
        if (cancelled) return;
        setSeverityOptions([]);
        setEcosystemOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, canRead, user?.orgId]);

  useEffect(() => {
    if (authLoading || !canRead) return;
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    signalApi
      .list({
        page: pageIndex,
        size: PAGE_SIZE,
        search: search || undefined,
        type: type || undefined,
        severity: severity || undefined,
        ecosystem: ecosystem || undefined,
        from: from ? `${from}T00:00:00Z` : undefined,
        to: to ? `${to}T23:59:59Z` : undefined,
      })
      .then((page) => {
        if (!controller.signal.aborted) setData(page);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setData(null);
        setError(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [authLoading, canRead, user?.orgId, pageIndex, search, type, severity, ecosystem, from, to]);

  function clearFilters() {
    setSearchInput("");
    setSearch("");
    setType("");
    setSeverity("");
    setEcosystem("");
    setFrom("");
    setTo("");
    setPageIndex(0);
  }

  const hasFilters =
    search !== "" || type !== "" || severity !== "" || ecosystem !== "" || from !== "" || to !== "";

  if (!authLoading && !canRead) {
    return (
      <div className="mx-auto w-full max-w-6xl space-y-6">
        <Header />
        <EmptyState>
          You don&apos;t have access to advisories — an Aegis domain role is required.
        </EmptyState>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <Header />

      {/* Filter bar */}
      <div className="flex flex-col gap-3 lg:flex-row lg:flex-wrap lg:items-end">
        <div className="w-full sm:max-w-xs">
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Search</label>
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder="Title or identifier…"
          />
        </div>
        <FilterSelect label="Type" value={type} onChange={setType} allLabel="All types">
          {TYPE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect label="Severity" value={severity} onChange={setSeverity} allLabel="All severities">
          {severityOptions.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </FilterSelect>
        {ecosystemOptions.length > 0 && (
          <FilterSelect label="Ecosystem" value={ecosystem} onChange={setEcosystem} allLabel="All ecosystems">
            {ecosystemOptions.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </FilterSelect>
        )}
        <DateRangePicker
          label="Published"
          from={from}
          to={to}
          onChange={(range) => {
            setFrom(range.from ?? "");
            setTo(range.to ?? "");
            setPageIndex(0);
          }}
        />
        {hasFilters && (
          <Button variant="ghost" onClick={clearFilters}>
            Clear
          </Button>
        )}
      </div>

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Type</TableHead>
              <TableHead>Signal</TableHead>
              <TableHead>Severity</TableHead>
              <TableHead className="text-right">When</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 6 }).map((_, rowIndex) => (
                <TableRow key={rowIndex}>
                  {Array.from({ length: COLUMN_COUNT }).map((__, cellIndex) => (
                    <TableCell key={cellIndex}>
                      <Skeleton className="h-4 w-24" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : error === "forbidden" ? (
              <MessageRow>You don&apos;t have access to advisories — an Aegis domain role is required.</MessageRow>
            ) : error ? (
              <MessageRow>Could not load advisories. Please try again.</MessageRow>
            ) : data && data.content.length > 0 ? (
              data.content.map((signal) => <SignalRow key={signal.id} signal={signal} />)
            ) : (
              <MessageRow>
                {hasFilters ? "No advisories match these filters." : "No advisories yet — they appear here as feeds are ingested."}
              </MessageRow>
            )}
          </TableBody>
        </Table>
      </div>

      {data && data.totalElements > 0 && (
        <DataPagination
          page={data.number}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          pageSize={data.size}
          itemsOnPage={data.content.length}
          onPageChange={setPageIndex}
        />
      )}
    </div>
  );
}

function Header() {
  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Advisories</h1>
      <p className="text-sm text-muted-foreground">
        Every change signal we ingest — vulnerabilities, vendor outages, and security news —
        newest first.
      </p>
    </div>
  );
}

function FilterSelect({
  label,
  value,
  onChange,
  allLabel,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  allLabel: string;
  children: React.ReactNode;
}) {
  return (
    <div className="w-full sm:w-44">
      <label className="mb-1 block text-xs font-medium text-muted-foreground">{label}</label>
      <select className={cn(SELECT_CLASS)} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{allLabel}</option>
        {children}
      </select>
    </div>
  );
}

function SignalRow({ signal }: { signal: ChangeSignal }) {
  const type = SIGNAL_TYPE[signal.signalType];
  const title = signal.title?.trim() || signal.externalId;
  return (
    <TableRow>
      <TableCell className="align-top">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${type.className}`}>
          {type.label}
        </span>
      </TableCell>
      <TableCell className="max-w-md align-top whitespace-normal">
        {signal.url ? (
          <a
            href={signal.url}
            target="_blank"
            rel="noreferrer noopener"
            className="font-medium underline-offset-4 hover:underline"
          >
            {title}
          </a>
        ) : (
          <span className="font-medium">{title}</span>
        )}
        <span className="mt-0.5 block font-mono text-xs text-muted-foreground">{signal.externalId}</span>
        {signal.summary && (
          <span className="mt-1 block text-xs text-muted-foreground line-clamp-2">{signal.summary}</span>
        )}
        {signal.signalType !== "SECURITY_NEWS" && (
          <Link
            href={`/advisories/${signal.id}`}
            className="mt-1.5 inline-block text-xs font-medium text-primary underline-offset-4 hover:underline"
          >
            View impact →
          </Link>
        )}
      </TableCell>
      <TableCell className="align-top">
        <SeverityBadge severity={signal.severity} />
      </TableCell>
      <TableCell className="text-right align-top text-muted-foreground">{formatWhen(signal.occurredAt)}</TableCell>
    </TableRow>
  );
}

function SeverityBadge({ severity }: { severity: string | null }) {
  if (!severity) return <span className="text-muted-foreground">—</span>;
  const key = severity.toUpperCase();
  const className = key.includes("CRIT")
    ? "bg-red-500/10 text-red-600 dark:text-red-400"
    : key.includes("HIGH") || key.includes("IMPORT")
      ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
      : key.includes("MED") || key.includes("MODER")
        ? "bg-sky-500/10 text-sky-600 dark:text-sky-400"
        : "bg-muted text-muted-foreground";
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${className}`}>{severity}</span>;
}

function MessageRow({ children }: { children: React.ReactNode }) {
  return (
    <TableRow>
      <TableCell colSpan={COLUMN_COUNT} className="py-10 text-center text-muted-foreground">
        {children}
      </TableCell>
    </TableRow>
  );
}

function EmptyState({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}

function formatWhen(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
