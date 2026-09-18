import type { Metadata } from "next";

import { ServiceDetailView } from "@/components/app/inventory/service-detail-view";

export const metadata: Metadata = { title: "Service" };

export default async function ServiceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ServiceDetailView serviceId={id} />;
}
