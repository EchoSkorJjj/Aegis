"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";

import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import { ApiError, inventoryApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { BusinessCriticality, ComponentImpact, Owner, VendorImpact } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

type Mode = "component" | "vendor";

const CRITICALITY_CLASS: Record<BusinessCriticality, string> = {
  LOW: "bg-muted text-muted-foreground",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
};

export default function DependencyMapPage() {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);

  const [mode, setMode] = useState<Mode>("component");
  const [componentImpact, setComponentImpact] = useState<ComponentImpact | null>(null);
  const [vendorImpact, setVendorImpact] = useState<VendorImpact | null>(null);
  const [loadingImpact, setLoadingImpact] = useState(false);

  function switchMode(next: Mode) {
    setMode(next);
    setComponentImpact(null);
    setVendorImpact(null);
  }

  async function selectTarget(id: string) {
    setComponentImpact(null);
    setVendorImpact(null);
    if (!id) return;
    setLoadingImpact(true);
    try {
      if (mode === "component") {
        setComponentImpact(await inventoryApi.componentImpact(id));
      } else {
        setVendorImpact(await inventoryApi.vendorImpact(id));
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not load impact.");
    } finally {
      setLoadingImpact(false);
    }
  }

  if (!authLoading && !canRead) {
    return (
      <div className="mx-auto w-full max-w-6xl space-y-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Dependency map</h1>
          <p className="text-sm text-muted-foreground">Trace impact across the estate.</p>
        </div>
        <EmptyCard>You don&apos;t have access to the estate map — an Aegis domain role is required.</EmptyCard>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Dependency map</h1>
        <p className="text-sm text-muted-foreground">
          Trace impact across the estate: search for a component or vendor to see which services it
          reaches, directly or through the dependency chain.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="inline-flex rounded-md border p-0.5">
          <ModeButton active={mode === "component"} onClick={() => switchMode("component")}>
            By component
          </ModeButton>
          <ModeButton active={mode === "vendor"} onClick={() => switchMode("vendor")}>
            By vendor
          </ModeButton>
        </div>
        <label className="flex min-w-64 flex-1 flex-col gap-1 sm:max-w-md">
          <span className="text-xs font-medium text-muted-foreground">
            {mode === "component" ? "Component" : "Vendor"}
          </span>
          {/* Keyed by mode so switching resets the search field. */}
          <TargetPicker key={mode} mode={mode} orgId={user?.orgId} onPick={selectTarget} />
        </label>
      </div>

      {loadingImpact ? (
        <div className="space-y-3">
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </div>
      ) : mode === "component" && componentImpact ? (
        <ImpactResults
          summary={`${componentImpact.services.length} service(s) impacted by ${componentImpact.component.name} ${componentImpact.component.version}`}
          empty={componentImpact.services.length === 0}
        >
          {componentImpact.services.map((impacted) => (
            <ServiceCard
              key={impacted.service.id}
              name={impacted.service.name}
              criticality={impacted.service.criticality}
              owners={impacted.owners}
              tag={
                impacted.direct
                  ? { label: "Direct", className: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" }
                  : {
                      label: `via ${impacted.viaComponent.name}`,
                      className: "bg-muted text-muted-foreground",
                    }
              }
            />
          ))}
        </ImpactResults>
      ) : mode === "vendor" && vendorImpact ? (
        <ImpactResults
          summary={`${vendorImpact.services.length} service(s) depend on ${vendorImpact.vendor.name}`}
          empty={vendorImpact.services.length === 0}
        >
          {vendorImpact.services.map((impacted) => (
            <ServiceCard
              key={impacted.service.id}
              name={impacted.service.name}
              criticality={impacted.service.criticality}
              owners={impacted.owners}
            />
          ))}
        </ImpactResults>
      ) : (
        <EmptyCard>Search for a {mode} above to see the services it impacts.</EmptyCard>
      )}
    </div>
  );
}

/** A debounced server typeahead over components / vendors — no client-side cap. */
function TargetPicker({
  mode,
  orgId,
  onPick,
}: {
  mode: Mode;
  orgId?: string | null;
  onPick: (id: string) => void;
}) {
  const [term, setTerm] = useState("");
  const [focused, setFocused] = useState(false);
  const [results, setResults] = useState<{ id: string; label: string }[]>([]);
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    if (!focused) return;
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSearching(true);
    const timer = setTimeout(() => {
      const search = term.trim();
      const request =
        mode === "component"
          ? inventoryApi
              .listComponents({ search, size: 20 })
              .then((page) =>
                page.content.map((c) => ({ id: c.id, label: `${c.ecosystem} · ${c.name} ${c.version}` })),
              )
          : inventoryApi
              .listVendors({ search, size: 20 })
              .then((page) => page.content.map((v) => ({ id: v.id, label: v.name })));
      request
        .then((rows) => {
          if (!controller.signal.aborted) setResults(rows);
        })
        .catch(() => {
          if (!controller.signal.aborted) setResults([]);
        })
        .finally(() => {
          if (!controller.signal.aborted) setSearching(false);
        });
    }, 200);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [focused, term, mode, orgId]);

  function pick(row: { id: string; label: string }) {
    setTerm(row.label);
    setResults([]);
    onPick(row.id);
  }

  return (
    <div className="relative">
      <Input
        value={term}
        placeholder={mode === "component" ? "Search components…" : "Search vendors…"}
        onFocus={() => setFocused(true)}
        onBlur={() => setTimeout(() => setFocused(false), 150)}
        onChange={(event) => setTerm(event.target.value)}
      />
      {focused && (
        <div className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-md border bg-popover p-1 text-popover-foreground shadow-md">
          {searching ? (
            <p className="px-2 py-2 text-sm text-muted-foreground">Searching…</p>
          ) : results.length === 0 ? (
            <p className="px-2 py-2 text-sm text-muted-foreground">
              {term.trim() ? "No matches." : "Type to search."}
            </p>
          ) : (
            results.map((row) => (
              <button
                key={row.id}
                type="button"
                onMouseDown={(event) => {
                  event.preventDefault();
                  pick(row);
                }}
                className="block w-full truncate rounded px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
              >
                {row.label}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}

function ModeButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "rounded px-3 py-1 text-sm font-medium transition-colors",
        active ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground",
      )}
    >
      {children}
    </button>
  );
}

function ImpactResults({
  summary,
  empty,
  children,
}: {
  summary: string;
  empty: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-3">
      <p className="text-sm text-muted-foreground">{summary}</p>
      {empty ? <EmptyCard>No services are impacted.</EmptyCard> : <div className="space-y-2">{children}</div>}
    </div>
  );
}

function ServiceCard({
  name,
  criticality,
  owners,
  tag,
}: {
  name: string;
  criticality: BusinessCriticality;
  owners: Owner[];
  tag?: { label: string; className: string };
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border p-3">
      <span className="font-medium">{name}</span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${CRITICALITY_CLASS[criticality]}`}>
        {criticality}
      </span>
      {tag && <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tag.className}`}>{tag.label}</span>}
      <span className="ml-auto text-xs text-muted-foreground">
        {owners.length > 0 ? owners.map((owner) => owner.name).join(", ") : "No owners"}
      </span>
    </div>
  );
}

function EmptyCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
