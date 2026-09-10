import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Audit log" };

export default function AuditLogPage() {
  return (
    <PlaceholderPage
      title="Audit log"
      description="The append-only record of every finding, decision, and action."
    />
  );
}
