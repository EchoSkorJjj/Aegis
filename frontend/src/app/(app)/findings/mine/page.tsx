import type { Metadata } from "next";

import { MyServices } from "@/components/app/findings/my-services";

export const metadata: Metadata = { title: "My services" };

export default function MyServicesPage() {
  return <MyServices />;
}
