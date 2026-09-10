import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Dependency map" };

export default function DependencyMapPage() {
  return (
    <PlaceholderPage
      title="Dependency map"
      description="How services, components, and vendors connect — trace impact across the graph."
    />
  );
}
