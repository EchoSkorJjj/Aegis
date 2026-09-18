"use client";

import { ResourceManager } from "@/components/app/inventory/resource-manager";
import type { Column, ListQuery } from "@/components/app/inventory/resource-manager";
import { inventoryApi } from "@/lib/api";
import type { Owner } from "@/lib/types";

const PAGE_SIZE = 10;

const listOwners = (query: ListQuery) =>
  inventoryApi.listOwners({
    page: query.page,
    size: PAGE_SIZE,
    search: query.search,
    sort: query.sort,
    from: query.from,
    to: query.to,
  });

const columns: Column<Owner>[] = [
  { header: "Name", sortField: "name", render: (owner) => <span className="font-medium">{owner.name}</span> },
  {
    header: "Updated",
    sortField: "updatedAt",
    className: "text-muted-foreground",
    render: (owner) => formatDate(owner.updatedAt),
  },
];

export default function OwnersPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Owners</h1>
        <p className="text-sm text-muted-foreground">
          People or teams accountable for services — they&apos;re who impact analysis notifies.
        </p>
      </div>

      <ResourceManager<Owner>
        entityLabel="owner"
        columns={columns}
        fields={[{ name: "name", label: "Name", kind: "text", placeholder: "Platform Team" }]}
        list={listOwners}
        create={(draft) => inventoryApi.createOwner({ name: draft.name })}
        update={(id, draft) => inventoryApi.updateOwner(id, { name: draft.name })}
        remove={(id) => inventoryApi.deleteOwner(id)}
        toDraft={(owner) => ({ name: owner.name })}
        describe={(owner) => owner.name}
        searchPlaceholder="Owner name…"
        defaultSort="name,asc"
      />
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
