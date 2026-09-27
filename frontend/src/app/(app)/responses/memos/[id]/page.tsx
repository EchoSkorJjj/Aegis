import type { Metadata } from "next";

import { RegulatorMemoView } from "@/components/app/responses/regulator-memo-view";

export const metadata: Metadata = { title: "Regulator memo" };

export default async function RegulatorMemoPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <RegulatorMemoView memoId={id} />;
}
