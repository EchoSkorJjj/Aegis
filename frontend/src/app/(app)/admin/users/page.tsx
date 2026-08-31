import type { Metadata } from "next";

import { AdminUsersTable } from "@/components/admin/admin-users-table";

export const metadata: Metadata = { title: "Users" };

export default function AdminUsersPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Users</h1>
        <p className="text-sm text-muted-foreground">
          Search and review every account in the directory.
        </p>
      </div>

      <AdminUsersTable />
    </div>
  );
}
