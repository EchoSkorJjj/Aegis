import type { Metadata } from "next";

import { FindingQueue } from "@/components/app/findings/finding-queue";

export const metadata: Metadata = { title: "My services" };

export default function MyServicesFindingsPage() {
  return <FindingQueue mine />;
}
