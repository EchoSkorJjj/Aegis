import type { Metadata } from "next";

import { JiraConnectCallback } from "@/components/admin/jira-connect-callback";

export const metadata: Metadata = { title: "Connect Jira" };

/**
 * Where Atlassian sends the organization admin back after they grant Aegis a Jira site (the OAuth 2.0
 * integration's callback URL): `?code=…&state=…`, or `?error=access_denied` when they cancel.
 */
export default async function JiraCallbackPage({
  searchParams,
}: {
  searchParams: Promise<{ code?: string | string[]; state?: string | string[]; error?: string | string[] }>;
}) {
  const { code, state, error } = await searchParams;
  const first = (value?: string | string[]) => (Array.isArray(value) ? value[0] : value) ?? null;
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Connect Jira</h1>
      </div>
      <JiraConnectCallback code={first(code)} state={first(state)} cancelled={first(error) !== null} />
    </div>
  );
}
