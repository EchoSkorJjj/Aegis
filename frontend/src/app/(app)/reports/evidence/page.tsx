import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Evidence" };

export default function EvidencePage() {
  return (
    <PlaceholderPage
      title="Evidence"
      description="Exportable evidence per service and time window for audits and reviews."
    />
  );
}
