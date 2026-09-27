import type { Metadata } from "next";

import { ResponseQueue } from "@/components/app/responses/response-queue";

export const metadata: Metadata = { title: "Response history" };

export default function ResponseHistoryPage() {
  return <ResponseQueue view="history" />;
}
