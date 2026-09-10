import type { Metadata } from "next";

import { PlaceholderPage } from "@/components/app/placeholder-page";

export const metadata: Metadata = { title: "Review & approve" };

export default function ReviewPage() {
  return (
    <PlaceholderPage
      title="Review & approve"
      description="AI-drafted responses awaiting your approval, edit, or override."
    />
  );
}
