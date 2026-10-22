import type { Metadata } from "next";

import { RiskDashboard } from "@/components/app/dashboard/risk-dashboard";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return <RiskDashboard />;
}
