import type { Metadata } from "next";
import { ChangeImpactView } from "@/components/app/advisories/change-impact-view";

export const metadata: Metadata = { title: "Finding detail" };

export default async function FindingPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ChangeImpactView signalId={id} finding />;
}
