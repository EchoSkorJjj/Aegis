"use client";

import { ResourceManager } from "@/components/app/inventory/resource-manager";
import { VendorUsedBy } from "@/components/app/inventory/used-by";
import type { Column, ListQuery } from "@/components/app/inventory/resource-manager";
import { inventoryApi } from "@/lib/api";
import type { ThirdPartyVendor } from "@/lib/types";

const PAGE_SIZE = 10;

const listVendors = (query: ListQuery) =>
  inventoryApi.listVendors({
    page: query.page,
    size: PAGE_SIZE,
    search: query.search,
    sort: query.sort,
    from: query.from,
    to: query.to,
  });

const columns: Column<ThirdPartyVendor>[] = [
  { header: "Name", sortField: "name", render: (vendor) => <span className="font-medium">{vendor.name}</span> },
  {
    header: "Updated",
    sortField: "updatedAt",
    className: "text-muted-foreground",
    render: (vendor) => formatDate(vendor.updatedAt),
  },
];

const usedBy = { label: "Used by", render: (vendor: ThirdPartyVendor) => <VendorUsedBy vendor={vendor} /> };

export default function VendorsPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Vendors</h1>
        <p className="text-sm text-muted-foreground">
          Third-party vendors your services depend on — matched to vendor-status outages.{" "}
          <span className="font-medium text-foreground">Used by</span> shows every service using one, outage or not.
        </p>
      </div>

      <ResourceManager<ThirdPartyVendor>
        entityLabel="vendor"
        columns={columns}
        fields={[{ name: "name", label: "Name", kind: "text", placeholder: "Amazon Web Services" }]}
        list={listVendors}
        create={(draft) => inventoryApi.createVendor({ name: draft.name })}
        update={(id, draft) => inventoryApi.updateVendor(id, { name: draft.name })}
        remove={(id) => inventoryApi.deleteVendor(id)}
        toDraft={(vendor) => ({ name: vendor.name })}
        describe={(vendor) => vendor.name}
        searchPlaceholder="Vendor name…"
        defaultSort="name,asc"
        rowDetail={usedBy}
      />
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
