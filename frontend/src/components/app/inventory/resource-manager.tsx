"use client";

import { useEffect, useMemo, useState } from "react";
import { MoreHorizontal, Pencil, Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { DataPagination } from "@/components/app/data-pagination";
import { DateRangePicker } from "@/components/app/date-range-picker";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
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
import { ApiError } from "@/lib/api";
import { canWriteInventory } from "@/lib/roles";
import { cn } from "@/lib/utils";
import type { Page } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** One editable field of a resource form. `select` renders a native dropdown of `options`. */
export type FieldSpec = {
  name: string;
  label: string;
  kind: "text" | "select";
  options?: { value: string; label: string }[];
  placeholder?: string;
  /** Pre-filled value for a new (create) record; falls back to the first select option, else empty. */
  defaultValue?: string;
};

/** A displayed column. `sortField` (a backend entity property) makes the header a sort toggle. */
export type Column<T> = {
  header: string;
  className?: string;
  sortField?: string;
  render: (row: T) => React.ReactNode;
};

/** A filter-bar dropdown. Provide static `options`, or `loadOptions` to fetch distinct values. */
export type SelectFilterSpec = {
  name: string;
  label: string;
  allLabel?: string;
  options?: { value: string; label: string }[];
  loadOptions?: () => Promise<string[]>;
};

/** The assembled query a page's `list` fetcher receives. Dates are already ISO instants. */
export type ListQuery = {
  page: number;
  search?: string;
  from?: string;
  to?: string;
  sort?: string;
  filters: Record<string, string>;
};

interface ResourceManagerProps<T extends { id: string }> {
  /** Singular noun for messages, e.g. "service". */
  entityLabel: string;
  columns: Column<T>[];
  fields: FieldSpec[];
  /** A stable (module-level) fetcher for one filtered, paged, sorted query. */
  list: (query: ListQuery) => Promise<Page<T>>;
  create: (draft: Record<string, string>) => Promise<T>;
  update: (id: string, draft: Record<string, string>) => Promise<T>;
  remove: (id: string) => Promise<void>;
  toDraft: (row: T) => Record<string, string>;
  describe: (row: T) => string;
  /** Filter-bar configuration. */
  searchPlaceholder?: string;
  selectFilters?: SelectFilterSpec[];
  /** Initial sort, e.g. "name,asc". */
  defaultSort?: string;
}

const SELECT_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs " +
  "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none";

export function ResourceManager<T extends { id: string }>({
  entityLabel,
  columns,
  fields,
  list,
  create,
  update,
  remove,
  toDraft,
  describe,
  searchPlaceholder,
  selectFilters = [],
  defaultSort,
}: ResourceManagerProps<T>) {
  const { user, isLoading: authLoading } = useAuth();
  const canWrite = canWriteInventory(user?.roles ?? []);
  const columnCount = columns.length + (canWrite ? 1 : 0);

  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [sort, setSort] = useState(defaultSort ?? "");
  const [filterValues, setFilterValues] = useState<Record<string, string>>({});
  const [dynamicOptions, setDynamicOptions] = useState<Record<string, { value: string; label: string }[]>>({});

  const [pageIndex, setPageIndex] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [data, setData] = useState<Page<T> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);

  const emptyDraft = useMemo(
    () =>
      Object.fromEntries(
        fields.map((field) => [
          field.name,
          field.defaultValue ?? (field.kind === "select" ? field.options?.[0]?.value ?? "" : ""),
        ]),
      ),
    [fields],
  );
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<T | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>(emptyDraft);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Debounce the search box; any new query returns to the first page.
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPageIndex(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    if (authLoading) return;
    const controller = new AbortController();
    // A new page / filter / sort / org / refresh refetches; surface loading right away.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    const activeFilters = Object.fromEntries(
      Object.entries(filterValues).filter(([, value]) => value !== ""),
    );
    const query: ListQuery = {
      page: pageIndex,
      search: search || undefined,
      from: from ? `${from}T00:00:00Z` : undefined,
      to: to ? `${to}T23:59:59Z` : undefined,
      sort: sort || undefined,
      filters: activeFilters,
    };
    list(query)
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
    // user?.orgId refetches this org's rows on a workspace switch; filterValues tracks the select filters.
  }, [authLoading, list, user?.orgId, pageIndex, search, from, to, sort, filterValues, refreshKey]);

  // Load any dynamic filter options (e.g. distinct ecosystems); refresh on org switch / mutation.
  useEffect(() => {
    if (authLoading) return;
    const dynamic = selectFilters.filter((filter) => filter.loadOptions);
    if (dynamic.length === 0) return;
    let cancelled = false;
    Promise.all(
      dynamic.map(async (filter) => {
        try {
          const values = await filter.loadOptions!();
          return [filter.name, values.map((value) => ({ value, label: value }))] as const;
        } catch {
          return [filter.name, [] as { value: string; label: string }[]] as const;
        }
      }),
    ).then((entries) => {
      if (!cancelled) setDynamicOptions(Object.fromEntries(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [authLoading, selectFilters, user?.orgId, refreshKey]);

  const refetch = () => setRefreshKey((key) => key + 1);

  function toggleSort(field: string) {
    const [current, dir] = sort.split(",");
    setSort(current === field && dir === "asc" ? `${field},desc` : `${field},asc`);
    setPageIndex(0);
  }

  function setFilter(name: string, value: string) {
    setFilterValues((prev) => ({ ...prev, [name]: value }));
    setPageIndex(0);
  }

  function clearFilters() {
    setSearchInput("");
    setSearch("");
    setFrom("");
    setTo("");
    setFilterValues({});
    setPageIndex(0);
  }

  const hasFilters =
    search !== "" || from !== "" || to !== "" || Object.values(filterValues).some((value) => value !== "");

  function openCreate() {
    setEditing(null);
    setDraft(emptyDraft);
    setFormError(null);
    setFormOpen(true);
  }

  function openEdit(row: T) {
    setEditing(row);
    setDraft(toDraft(row));
    setFormError(null);
    setFormOpen(true);
  }

  function closeForm() {
    setFormOpen(false);
    setEditing(null);
    setDraft(emptyDraft);
    setFormError(null);
  }

  async function submitForm(event: React.FormEvent) {
    event.preventDefault();
    if (fields.some((field) => !draft[field.name]?.trim())) {
      setFormError("Please fill in every field.");
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      if (editing) {
        await update(editing.id, draft);
        toast.success(`${cap(entityLabel)} updated`);
      } else {
        await create(draft);
        toast.success(`${cap(entityLabel)} created`);
      }
      closeForm();
      refetch();
    } catch (err) {
      setFormError(err instanceof ApiError ? err.message : "Something went wrong — please try again.");
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(row: T) {
    if (!window.confirm(`Delete ${entityLabel} "${describe(row)}"? Its relationship edges are removed too.`)) {
      return;
    }
    try {
      await remove(row.id);
      toast.success(`${cap(entityLabel)} deleted`);
      refetch();
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not delete — please try again.");
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {canWrite && !formOpen && (
        <div className="flex justify-end">
          <Button onClick={openCreate}>
            <Plus />
            New {entityLabel}
          </Button>
        </div>
      )}

      {/* Filter bar */}
      <div className="flex flex-col gap-3 lg:flex-row lg:flex-wrap lg:items-end">
        <div className="w-full sm:max-w-xs">
          <label className="mb-1 block text-xs font-medium text-muted-foreground">Search</label>
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder={searchPlaceholder ?? "Search…"}
          />
        </div>
        {selectFilters.map((filter) => {
          const options = filter.options ?? dynamicOptions[filter.name] ?? [];
          return (
            <div key={filter.name} className="w-full sm:w-44">
              <label className="mb-1 block text-xs font-medium text-muted-foreground">{filter.label}</label>
              <select
                className={cn(SELECT_CLASS)}
                value={filterValues[filter.name] ?? ""}
                onChange={(event) => setFilter(filter.name, event.target.value)}
              >
                <option value="">{filter.allLabel ?? "All"}</option>
                {options.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          );
        })}
        <DateRangePicker
          label="Created"
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

      {formOpen && (
        <form onSubmit={submitForm} noValidate className="w-full rounded-lg border p-4">
          <p className="mb-3 text-sm font-medium">
            {editing ? `Edit ${entityLabel}` : `New ${entityLabel}`}
          </p>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            {fields.map((field) => (
              <label key={field.name} className="flex flex-1 flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">{field.label}</span>
                {field.kind === "select" ? (
                  <select
                    className={cn(SELECT_CLASS)}
                    value={draft[field.name] ?? ""}
                    onChange={(e) => setDraft((d) => ({ ...d, [field.name]: e.target.value }))}
                  >
                    {field.options?.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                ) : (
                  <Input
                    value={draft[field.name] ?? ""}
                    placeholder={field.placeholder}
                    onChange={(e) => setDraft((d) => ({ ...d, [field.name]: e.target.value }))}
                  />
                )}
              </label>
            ))}
          </div>
          {formError && <p className="mt-2 text-sm text-destructive">{formError}</p>}
          <div className="mt-3 flex items-center gap-2">
            <Button type="submit" size="sm" disabled={saving}>
              {saving ? "Saving…" : editing ? "Save changes" : `Create ${entityLabel}`}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={closeForm}>
              Cancel
            </Button>
          </div>
        </form>
      )}

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead key={column.header} className={column.className}>
                  {column.sortField ? (
                    <SortButton label={column.header} field={column.sortField} sort={sort} onSort={toggleSort} />
                  ) : (
                    column.header
                  )}
                </TableHead>
              ))}
              {canWrite && (
                <TableHead className="w-10 text-right">
                  <span className="sr-only">Actions</span>
                </TableHead>
              )}
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 5 }).map((_, rowIndex) => (
                <TableRow key={rowIndex}>
                  {Array.from({ length: columnCount }).map((__, cellIndex) => (
                    <TableCell key={cellIndex}>
                      <Skeleton className="h-4 w-24" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : error === "forbidden" ? (
              <TableRow>
                <TableCell colSpan={columnCount} className="py-10 text-center text-muted-foreground">
                  You don&apos;t have access to this — an Aegis domain role is required.
                </TableCell>
              </TableRow>
            ) : error ? (
              <TableRow>
                <TableCell colSpan={columnCount} className="py-10 text-center text-muted-foreground">
                  Could not load. Please try again.
                </TableCell>
              </TableRow>
            ) : data && data.content.length > 0 ? (
              data.content.map((row) => (
                <TableRow key={row.id}>
                  {columns.map((column) => (
                    <TableCell key={column.header} className={column.className}>
                      {column.render(row)}
                    </TableCell>
                  ))}
                  {canWrite && (
                    <TableCell className="text-right">
                      <DropdownMenu>
                        <DropdownMenuTrigger
                          render={
                            <Button variant="ghost" size="icon-sm" aria-label={`${entityLabel} actions`}>
                              <MoreHorizontal />
                            </Button>
                          }
                        />
                        <DropdownMenuContent align="end" className="min-w-40">
                          <DropdownMenuItem onClick={() => openEdit(row)}>
                            <Pencil />
                            Edit
                          </DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" onClick={() => onDelete(row)}>
                            <Trash2 />
                            Delete
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  )}
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={columnCount} className="py-10 text-center text-muted-foreground">
                  {hasFilters
                    ? `No ${entityLabel}s match these filters.`
                    : `No ${entityLabel}s yet${canWrite ? " — create the first one." : "."}`}
                </TableCell>
              </TableRow>
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

function cap(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}
