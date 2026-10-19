"use client";

import { ResourceManager } from "@/components/app/inventory/resource-manager";
import { ComponentUsedBy } from "@/components/app/inventory/used-by";
import type { Column, ListQuery, SelectFilterSpec } from "@/components/app/inventory/resource-manager";
import { inventoryApi } from "@/lib/api";
import type { SoftwareComponent } from "@/lib/types";

const PAGE_SIZE = 10;

const listComponents = (query: ListQuery) =>
  inventoryApi.listComponents({
    page: query.page,
    size: PAGE_SIZE,
    search: query.search,
    sort: query.sort,
    from: query.from,
    to: query.to,
    ecosystem: query.filters.ecosystem,
  });

// Distinct ecosystems in the org drive the select — loaded (and refreshed on org switch) by the manager.
const filters: SelectFilterSpec[] = [
  { name: "ecosystem", label: "Ecosystem", allLabel: "All ecosystems", loadOptions: () => inventoryApi.componentEcosystems() },
];

const columns: Column<SoftwareComponent>[] = [
  {
    header: "Ecosystem",
    sortField: "ecosystem",
    render: (component) => (
      <span className="rounded bg-muted px-1.5 py-0.5 text-xs font-medium text-muted-foreground">
        {component.ecosystem}
      </span>
    ),
  },
  { header: "Name", sortField: "name", render: (component) => <span className="font-medium">{component.name}</span> },
  { header: "Version", sortField: "version", className: "text-muted-foreground", render: (component) => component.version },
  {
    header: "Updated",
    sortField: "updatedAt",
    className: "text-muted-foreground",
    render: (component) => formatDate(component.updatedAt),
  },
];

const usedBy = { label: "Used by", render: (component: SoftwareComponent) => <ComponentUsedBy component={component} /> };

export default function ComponentsPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Components</h1>
        <p className="text-sm text-muted-foreground">
          Software components (ecosystem, name, version) — created by importing a service&apos;s
          SBOM, or added by hand here. <span className="font-medium text-foreground">Used by</span> shows
          every service using a package, in any version, and whether directly or through which component.
        </p>
      </div>

      <ResourceManager<SoftwareComponent>
        entityLabel="component"
        columns={columns}
        fields={[
          { name: "ecosystem", label: "Ecosystem", kind: "text", placeholder: "npm" },
          { name: "name", label: "Name", kind: "text", placeholder: "log4j-core" },
          { name: "version", label: "Version", kind: "text", placeholder: "2.14.1" },
        ]}
        list={listComponents}
        create={(draft) =>
          inventoryApi.createComponent({ ecosystem: draft.ecosystem, name: draft.name, version: draft.version })
        }
        update={(id, draft) =>
          inventoryApi.updateComponent(id, {
            ecosystem: draft.ecosystem,
            name: draft.name,
            version: draft.version,
          })
        }
        remove={(id) => inventoryApi.deleteComponent(id)}
        toDraft={(component) => ({
          ecosystem: component.ecosystem,
          name: component.name,
          version: component.version,
        })}
        describe={(component) => `${component.name} ${component.version}`}
        searchPlaceholder="Name or version…"
        selectFilters={filters}
        defaultSort="name,asc"
        rowDetail={usedBy}
      />
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
