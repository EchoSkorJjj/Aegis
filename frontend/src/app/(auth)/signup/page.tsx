import type { Metadata } from "next";
import Link from "next/link";

import { SignupForm } from "@/components/form/signup-form";
import { getAuthConfig } from "@/lib/server/config";

export const metadata: Metadata = { title: "Sign up" };

export default async function SignupPage() {
  // When self-registration is disabled, accounts are provisioned by an admin (invite-only).
  const { registrationEnabled } = await getAuthConfig();
  if (!registrationEnabled) {
    return (
      <div className="w-full max-w-sm rounded-xl border border-border bg-card p-8 text-center">
        <h1 className="text-xl font-semibold tracking-tight">
          Sign-up is disabled
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Accounts are created by an administrator. Contact your admin for
          access.
        </p>
        <Link
          href="/login"
          className="mt-6 inline-block text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          ← Back to sign in
        </Link>
      </div>
    );
  }

  return <SignupForm />;
}
