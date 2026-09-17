"use client";

import { useEffect, useState } from "react";
import { X } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError } from "@/lib/api";
import type { Page } from "@/lib/types";

const PAGE_SIZE = 20;
const RESULT_SIZE = 20;

/** One kind of edge a service has (components / vendors / owners). Rows are erased to `{ id }`; the
 *  config's own closures cast back to the concrete type, keeping loaders and renderers paired. */
export interface RelationshipConfig {
  /** Singular noun for toasts, e.g. "component". */
  entityLabel: string;
  title: string;
  addPlaceholder: string;
  emptyLabel: string;
  /** Page through the edges linked to this service (0-based). */
  listLinked: (serviceId: string, page: number, size: number) => Promise<Page<{ id: string }>>;
  /** Server-side search over candidates to link, excluding any already linked to this service. */
  searchOptions: (serviceId: string, term: string, size: number) => Promise<{ id: string }[]>;
  attach: (serviceId: string, id: string) => Promise<void>;
  detach: (serviceId: string, id: string) => Promise<void>;
  optionLabel: (row: { id: string }) => string;
  renderRow: (row: { id: string }) => React.ReactNode;
  /** Optional note under the section title (e.g. the direct-vs-transitive clarification). */
  note?: string;
}

interface RelationshipSectionProps {
  serviceId: string;
  canWrite: boolean;
  config: RelationshipConfig;
  /** Bump from a parent (e.g. after an SBOM import) to force a reload. */
  refreshSignal?: number;
}

export function RelationshipSection({
  serviceId,
  canWrite,
  config,
  refreshSignal = 0,
}: RelationshipSectionProps) {
  const [pageIndex, setPageIndex] = useState(0);
  const [data, setData] = useState<Page<{ id: string }> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [localRefresh, setLocalRefresh] = useState(0);

  // Typeahead picker state.
  const [term, setTerm] = useState("");
  const [focused, setFocused] = useState(false);
  const [results, setResults] = useState<{ id: string }[]>([]);
  const [searching, setSearching] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(false);
    config
      .listLinked(serviceId, pageIndex, PAGE_SIZE)
      .then((linkedPage) => {
        if (!controller.signal.aborted) setData(linkedPage);
      })
      .catch(() => {
        if (!controller.signal.aborted) setError(true);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [serviceId, config, pageIndex, refreshSignal, localRefresh]);

  // Debounced server-side search for the picker — only while the field is focused.
  useEffect(() => {
    if (!canWrite || !focused) return;
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSearching(true);
    const timer = setTimeout(() => {
      config
        .searchOptions(serviceId, term.trim(), RESULT_SIZE)
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
  }, [canWrite, focused, term, config, serviceId, localRefresh]);

  const total = data?.totalElements ?? 0;

  async function attach(id: string) {
    if (!id) return;
    setBusy(true);
    try {
      await config.attach(serviceId, id);
      toast.success(`${cap(config.entityLabel)} linked`);
      setTerm("");
      setResults([]);
      setLocalRefresh((n) => n + 1);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not link — please try again.");
    } finally {
      setBusy(false);
    }
  }

  async function detach(id: string) {
    setBusy(true);
    try {
      await config.detach(serviceId, id);
      toast.success(`${cap(config.entityLabel)} unlinked`);
      // Removing the last row on a later page → step back so we don't land on an empty page.
      if (data && data.content.length === 1 && pageIndex > 0) {
        setPageIndex((page) => page - 1);
      } else {
        setLocalRefresh((n) => n + 1);
      }
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not unlink — please try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-lg border">
      <div className="border-b px-4 py-3">
        <h2 className="text-sm font-medium">
          {config.title}
          {!loading && !error ? <span className="text-muted-foreground"> ({total})</span> : null}
        </h2>
        {config.note && <p className="mt-0.5 text-xs text-muted-foreground">{config.note}</p>}
      </div>
      <div className="space-y-3 p-4">
        {canWrite && (
          <div className="relative">
            <Input
              value={term}
              disabled={busy}
              placeholder={config.addPlaceholder}
              onFocus={() => setFocused(true)}
              // Delay so a click on a result registers before the list unmounts.
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
                      // onMouseDown fires before the input's onBlur, so the click isn't lost.
                      onMouseDown={(event) => {
                        event.preventDefault();
                        attach(row.id);
                      }}
                      className="block w-full truncate rounded px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
                    >
                      {config.optionLabel(row)}
                    </button>
                  ))
                )}
              </div>
            )}
          </div>
        )}

        {loading ? (
          <div className="space-y-2">
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
          </div>
        ) : error ? (
          <p className="py-4 text-center text-sm text-muted-foreground">
            Could not load {config.title.toLowerCase()}. Please try again.
          </p>
        ) : !data || data.content.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">{config.emptyLabel}</p>
        ) : (
          <>
            <ul className="divide-y rounded-md border">
              {data.content.map((row) => (
                <li key={row.id} className="flex items-center gap-2 px-3 py-2">
                  <div className="min-w-0 flex-1">{config.renderRow(row)}</div>
                  {canWrite && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Unlink ${config.entityLabel}`}
                      disabled={busy}
                      onClick={() => detach(row.id)}
                    >
                      <X />
                    </Button>
                  )}
                </li>
              ))}
            </ul>
            {data.totalPages > 1 && (
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  {data.number * data.size + 1}–{data.number * data.size + data.content.length} of {total}
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={data.first || busy}
                    onClick={() => setPageIndex((page) => Math.max(0, page - 1))}
                  >
                    Previous
                  </Button>
                  <span>
                    Page {data.number + 1} of {Math.max(1, data.totalPages)}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={data.last || busy}
                    onClick={() => setPageIndex((page) => page + 1)}
                  >
                    Next
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function cap(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}
