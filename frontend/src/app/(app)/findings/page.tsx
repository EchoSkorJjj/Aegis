import type { Metadata } from "next";

import { FindingQueue } from "@/components/app/findings/finding-queue";

export const metadata: Metadata = { title: "Triage queue" };

export default function TriageQueuePage() {
  return <FindingQueue />;
}
