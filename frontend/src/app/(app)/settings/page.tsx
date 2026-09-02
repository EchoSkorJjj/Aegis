import type { Metadata } from "next";

import { AccountMfaCard } from "@/components/account/account-mfa-card";
import { AccountPasskeysCard } from "@/components/account/account-passkeys-card";
import { AccountProfileCard } from "@/components/account/account-profile-card";
import { AccountSecurityCard } from "@/components/account/account-security-card";

export const metadata: Metadata = { title: "Settings" };

export default function SettingsPage() {
  return (
    <div className="mx-auto w-full max-w-2xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-sm text-muted-foreground">
          Manage your profile and account security.
        </p>
      </div>

      <AccountProfileCard />
      <AccountSecurityCard />
      <AccountMfaCard />
      <AccountPasskeysCard />
    </div>
  );
}
