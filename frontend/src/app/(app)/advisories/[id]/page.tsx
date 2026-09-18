import type { Metadata } from "next";

import { ChangeImpactView } from "@/components/app/advisories/change-impact-view";

export const metadata: Metadata = { title: "Change impact" };

export default async function ChangeImpactPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ChangeImpactView signalId={id} />;
}
