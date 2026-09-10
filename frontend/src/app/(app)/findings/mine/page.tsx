import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "My services" };

export default function MyServicesFindingsPage() {
  return (
    <PlaceholderPage
      title="My services"
      description="Findings for the services you own."
    />
  );
}
