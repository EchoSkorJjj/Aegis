"use client";

import Link from "next/link";

import { ResourceManager } from "@/components/app/inventory/resource-manager";
import type { Column, ListQuery, SelectFilterSpec } from "@/components/app/inventory/resource-manager";
import { inventoryApi } from "@/lib/api";
import type { BusinessCriticality, BusinessService } from "@/lib/types";

const PAGE_SIZE = 10;

const CRITICALITY: { value: BusinessCriticality; label: string }[] = [
  { value: "LOW", label: "Low" },
  { value: "MEDIUM", label: "Medium" },
  { value: "HIGH", label: "High" },
  { value: "CRITICAL", label: "Critical" },
];

const CRITICALITY_CLASS: Record<BusinessCriticality, string> = {
  LOW: "bg-muted text-muted-foreground",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
};

// Module-level (stable) so the manager's fetch effect doesn't re-run every render.
const listServices = (query: ListQuery) =>
  inventoryApi.listServices({
    page: query.page,
    size: PAGE_SIZE,
    search: query.search,
    sort: query.sort,
    from: query.from,
    to: query.to,
    criticality: query.filters.criticality,
  });

const filters: SelectFilterSpec[] = [{ name: "criticality", label: "Criticality", options: CRITICALITY }];

const columns: Column<BusinessService>[] = [
  {
    header: "Name",
    sortField: "name",
    render: (service) => (
      <Link
        href={`/inventory/services/${service.id}`}
        className="font-medium underline-offset-4 hover:underline"
      >
        {service.name}
      </Link>
    ),
  },
  {
    header: "Criticality",
    sortField: "criticality",
    render: (service) => (
      <span
        className={`rounded-full px-2 py-0.5 text-xs font-medium ${CRITICALITY_CLASS[service.criticality]}`}
      >
        {service.criticality}
      </span>
    ),
  },
  {
    header: "Updated",
    sortField: "updatedAt",
    className: "text-muted-foreground",
    render: (service) => formatDate(service.updatedAt),
  },
];

export default function ServicesPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Services</h1>
        <p className="text-sm text-muted-foreground">
          Business services in your estate, each with a criticality tier. Select a service to
          compose it from components, vendors, and owners.
        </p>
      </div>

      <ResourceManager<BusinessService>
        entityLabel="service"
        columns={columns}
        fields={[
          { name: "name", label: "Name", kind: "text", placeholder: "Checkout API" },
          { name: "criticality", label: "Criticality", kind: "select", options: CRITICALITY },
        ]}
        list={listServices}
        create={(draft) =>
          inventoryApi.createService({ name: draft.name, criticality: draft.criticality as BusinessCriticality })
        }
        update={(id, draft) =>
          inventoryApi.updateService(id, { name: draft.name, criticality: draft.criticality as BusinessCriticality })
        }
        remove={(id) => inventoryApi.deleteService(id)}
        toDraft={(service) => ({ name: service.name, criticality: service.criticality })}
        describe={(service) => service.name}
        searchPlaceholder="Service name…"
        selectFilters={filters}
        defaultSort="name,asc"
      />
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
