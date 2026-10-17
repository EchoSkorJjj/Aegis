import type { Metadata } from "next";

import { EvidenceExport } from "@/components/app/reports/evidence-export";

export const metadata: Metadata = { title: "Evidence" };

export default function EvidencePage() {
  return <EvidenceExport />;
}
