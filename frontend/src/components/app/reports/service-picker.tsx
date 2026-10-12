"use client";

import { useEffect, useState } from "react";

import { inventoryApi } from "@/lib/api";
import type { BusinessService, RemovedService } from "@/lib/types";
import { cn } from "@/lib/utils";

const MATCHES = 50;

const CONTROL_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs " +
  "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none";

/** How a service no longer in the inventory is offered: by the name its records gave it, else by its id. */
export function removedServiceLabel(service: RemovedService): string {
  return `${service.name ?? `A service (${service.id.slice(0, 8)})`} (removed)`;
}

/**
 * Choose one service for the reports: the inventory searched by name on the server (so any service can be found,
 * however many there are), and the services removed from it whose decisions are still on record (`removed`). The
 * service chosen stays offered whatever the search.
 */
export function ServicePicker({
  id,
  label,
  value,
  emptyLabel,
  removed,
  valueName,
  onChange,
  className,
}: {
  id: string;
  label: string;
  value: string;
  /** The option for no service: "All services" for a filter, "Choose a service" where one is needed. */
  emptyLabel: string;
  removed: RemovedService[] | null;
  /** The chosen service's name when the caller knows it (picked from a row rather than here). */
  valueName?: string;
  onChange: (serviceId: string) => void;
  className?: string;
}) {
  const [search, setSearch] = useState("");
  const [matches, setMatches] = useState<{ services: BusinessService[]; more: boolean } | null>(null);
  const [chosen, setChosen] = useState<Map<string, string>>(new Map());

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      inventoryApi
        .listServices({ search: search.trim() || undefined, size: MATCHES, sort: "name,asc" })
        .then((page) => {
          if (!cancelled) setMatches({ services: page.content, more: page.totalElements > page.content.length });
        })
        .catch(() => {
          if (!cancelled) setMatches({ services: [], more: false });
        });
    }, search ? 250 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search]);

  const listed = new Set([...(matches?.services ?? []).map((s) => s.id), ...(removed ?? []).map((s) => s.id)]);
  const keep = value && !listed.has(value) ? (valueName ?? chosen.get(value) ?? "The chosen service") : null;

  const choose = (serviceId: string) => {
    const gone = removed?.find((s) => s.id === serviceId);
    const name = matches?.services.find((s) => s.id === serviceId)?.name ?? (gone ? removedServiceLabel(gone) : null);
    if (serviceId && name) setChosen((known) => new Map(known).set(serviceId, name));
    onChange(serviceId);
  };

  return (
    <div className={cn("w-full space-y-1.5 sm:w-56", className)}>
      <label htmlFor={id} className="block text-xs font-medium text-muted-foreground">
        {label}
      </label>
      <input
        type="search"
        aria-label={`Search ${label.toLowerCase()}s by name`}
        placeholder="Search by name…"
        className={CONTROL_CLASS}
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      <select id={id} className={CONTROL_CLASS} value={value} onChange={(event) => choose(event.target.value)}>
        <option value="">{emptyLabel}</option>
        {keep && <option value={value}>{keep}</option>}
        {matches?.services.map((service) => (
          <option key={service.id} value={service.id}>
            {service.name}
          </option>
        ))}
        {matches?.more && (
          <option disabled value="__more">
            {`Showing the first ${MATCHES}: search to narrow`}
          </option>
        )}
        {removed && removed.length > 0 && (
          <optgroup label="No longer in the inventory">
            {removed.map((service) => (
              <option key={service.id} value={service.id}>
                {removedServiceLabel(service)}
              </option>
            ))}
          </optgroup>
        )}
      </select>
    </div>
  );
}
