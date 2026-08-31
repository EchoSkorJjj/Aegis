import Link from "next/link";
import { Hexagon } from "lucide-react";

import { siteConfig } from "@/lib/site";

export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Link
        href="/"
        className="flex items-center gap-2 text-lg font-semibold tracking-tight"
      >
        <span className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
          <Hexagon className="size-4.5" />
        </span>
        {siteConfig.name}
      </Link>
      {children}
    </div>
  );
}
