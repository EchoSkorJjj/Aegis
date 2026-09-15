import type { Metadata } from "next";
import Link from "next/link";

import { CreateOrgForm } from "@/components/app/create-org-form";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { getAuthConfig } from "@/lib/server/config";

export const metadata: Metadata = { title: "New organization" };

export default async function NewOrganizationPage() {
  const { organizationSignupEnabled } = await getAuthConfig();

  return (
    <div className="mx-auto w-full max-w-md">
      <Card>
        <CardHeader>
          <CardTitle>Create an organization</CardTitle>
          <CardDescription>
            Spin up a new workspace you own — separate from your current one. You can switch between
            them anytime.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {organizationSignupEnabled ? (
            <CreateOrgForm />
          ) : (
            <p className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">
              Self-serve organization creation is turned off. Contact your platform administrator.
            </p>
          )}
        </CardContent>
      </Card>
      <p className="mt-4 text-center text-sm text-muted-foreground">
        <Link href="/dashboard" className="hover:text-foreground">
          ← Back to dashboard
        </Link>
      </p>
    </div>
  );
}
