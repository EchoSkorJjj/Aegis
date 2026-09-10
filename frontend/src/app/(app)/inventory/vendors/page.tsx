import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Vendors" };

export default function VendorsPage() {
  return (
    <PlaceholderPage
      title="Vendors"
      description="Third-party vendors and services your estate depends on."
    />
  );
}
