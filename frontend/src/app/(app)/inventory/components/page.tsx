import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Components" };

export default function ComponentsPage() {
  return (
    <PlaceholderPage
      title="Components"
      description="Software components and versions discovered from your SBOMs."
    />
  );
}
