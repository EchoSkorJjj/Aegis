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
    header: "Email",
    className: "text-muted-foreground",
    render: (owner) => owner.email ?? "—",
  },
  {
    header: "Owner since",
    sortField: "createdAt",
    className: "text-muted-foreground",
    render: (owner) => formatDate(owner.createdAt),
  },
];

/**
 * Read-only: an owner is an organization member holding the Service Owner role, and becomes one by
 * being assigned to a service (from that service's page) — there is nothing to create or edit here.
 */
export default function OwnersPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Service owners</h1>
        <p className="text-sm text-muted-foreground">
          Members with the Service Owner role who are accountable for at least one service. Assign
          owners from a service&apos;s page; invite members and grant the role under Users.
        </p>
      </div>

      <ResourceManager<Owner>
        entityLabel="owner"
        columns={columns}
        fields={[]}
        list={listOwners}
        readOnly
        describe={(owner) => owner.name}
        searchPlaceholder="Name or email…"
        defaultSort="name,asc"
      />
    </div>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
