"use client";

import Link from "next/link";

import { ResourceManager } from "@/components/app/inventory/resource-manager";
import type { Column, ListQuery, SelectFilterSpec } from "@/components/app/inventory/resource-manager";
import { inventoryApi } from "@/lib/api";
import type { BusinessCriticality, BusinessService, ServiceExposure } from "@/lib/types";

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

// Ordered least → most exposed. INTERNAL is the shipped default (see the field below): it is the
// middle posture, so a service registered without a considered exposure is neither cried wolf over
// nor quietly understated.
const EXPOSURE: { value: ServiceExposure; label: string }[] = [
  { value: "ISOLATED", label: "Isolated" },
  { value: "INTERNAL", label: "Internal" },
  { value: "INTERNET_FACING", label: "Internet-facing" },
];

const EXPOSURE_CLASS: Record<ServiceExposure, string> = {
  ISOLATED: "bg-muted text-muted-foreground",
  INTERNAL: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  INTERNET_FACING: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
};

const EXPOSURE_LABEL: Record<ServiceExposure, string> = {
  ISOLATED: "Isolated",
  INTERNAL: "Internal",
  INTERNET_FACING: "Internet-facing",
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
    exposure: query.filters.exposure,
  });

const filters: SelectFilterSpec[] = [
  { name: "criticality", label: "Criticality", options: CRITICALITY },
  { name: "exposure", label: "Exposure", allLabel: "All exposures", options: EXPOSURE },
];

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
    header: "Exposure",
    sortField: "exposure",
    render: (service) => (
      <span
        className={`rounded-full px-2 py-0.5 text-xs font-medium ${EXPOSURE_CLASS[service.exposure]}`}
      >
        {EXPOSURE_LABEL[service.exposure]}
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
          Business services in your estate, each with a criticality tier and a network exposure —
          the two inputs impact scoring reads off the service. Select a service to compose it from
          components, vendors, and owners.
        </p>
      </div>

      <ResourceManager<BusinessService>
        entityLabel="service"
        columns={columns}
        fields={[
          { name: "name", label: "Name", kind: "text", placeholder: "Checkout API" },
          { name: "criticality", label: "Criticality", kind: "select", options: CRITICALITY },
          { name: "exposure", label: "Exposure", kind: "select", options: EXPOSURE, defaultValue: "INTERNAL" },
        ]}
        list={listServices}
        create={(draft) =>
          inventoryApi.createService({
            name: draft.name,
            criticality: draft.criticality as BusinessCriticality,
            exposure: draft.exposure as ServiceExposure,
          })
        }
        update={(id, draft) =>
          inventoryApi.updateService(id, {
            name: draft.name,
            criticality: draft.criticality as BusinessCriticality,
            exposure: draft.exposure as ServiceExposure,
          })
        }
        remove={(id) => inventoryApi.deleteService(id)}
        toDraft={(service) => ({
          name: service.name,
          criticality: service.criticality,
          exposure: service.exposure,
        })}
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
