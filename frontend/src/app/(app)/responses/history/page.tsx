import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Response history" };

export default function ResponseHistoryPage() {
  return (
    <PlaceholderPage
      title="Response history"
      description="Actions taken so far — pull requests, risk tickets, and notification memos."
    />
  );
}
