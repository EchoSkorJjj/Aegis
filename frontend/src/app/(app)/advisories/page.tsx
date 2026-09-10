import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Advisories" };

export default function AdvisoriesPage() {
  return (
    <PlaceholderPage
      title="Advisories"
      description="Advisories ingested from OSV, NVD, and vendor feeds."
    />
  );
}
