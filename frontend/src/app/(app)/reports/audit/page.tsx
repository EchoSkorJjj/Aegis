import type { Metadata } from "next";

import { DecisionTrail } from "@/components/app/reports/decision-trail";

export const metadata: Metadata = { title: "Audit log" };

export default function AuditLogPage() {
  return <DecisionTrail />;
}
