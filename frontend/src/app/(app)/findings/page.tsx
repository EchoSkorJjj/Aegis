import type { Metadata } from "next";
import { Suspense } from "react";

import { FindingQueue } from "@/components/app/findings/finding-queue";

export const metadata: Metadata = { title: "Triage queue" };

export default function TriageQueuePage() {
  return <Suspense fallback={<p className="text-sm text-muted-foreground">Loading triage queue…</p>}><FindingQueue /></Suspense>;
}
