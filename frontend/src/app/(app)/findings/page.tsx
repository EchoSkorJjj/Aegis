import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Triage queue" };

export default function TriageQueuePage() {
  return (
    <PlaceholderPage
      title="Triage queue"
      description="All findings across services, ranked by impact for analyst triage."
    />
  );
}
