import type { Metadata } from "next";

import { RiskTicketView } from "@/components/app/responses/risk-ticket-view";

export const metadata: Metadata = { title: "Response" };

export default async function RiskTicketPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <RiskTicketView ticketId={id} />;
}
