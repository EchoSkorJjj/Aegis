import type { Metadata } from "next";

import { GitHubIntegration } from "@/components/admin/github-integration";
import { JiraIntegration } from "@/components/admin/jira-integration";

export const metadata: Metadata = { title: "Integrations" };

export default function IntegrationsPage() {
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Integrations</h1>
        <p className="text-sm text-muted-foreground">
          The outside systems Aegis may act on for this organization.
        </p>
      </div>

      <GitHubIntegration />
      <JiraIntegration />
    </div>
  );
}
