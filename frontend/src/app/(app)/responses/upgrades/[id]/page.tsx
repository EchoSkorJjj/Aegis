import type { Metadata } from "next";

import { UpgradeProposalView } from "@/components/app/responses/upgrade-proposal-view";

export const metadata: Metadata = { title: "Upgrade proposal" };

export default async function UpgradeProposalPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <UpgradeProposalView proposalId={id} />;
}
