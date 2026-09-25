import type { Metadata } from "next";

import { ResponseQueue } from "@/components/app/responses/response-queue";

export const metadata: Metadata = { title: "Review & approve" };

export default function ReviewPage() {
  return <ResponseQueue view="review" />;
}
