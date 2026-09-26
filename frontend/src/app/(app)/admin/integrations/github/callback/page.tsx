import type { Metadata } from "next";

import { GitHubConnectCallback } from "@/components/admin/github-connect-callback";

export const metadata: Metadata = { title: "Connect GitHub" };

/**
 * Where GitHub sends the organization admin back after they sign in (the App's registered callback URL):
 * `?code=…&state=…`, or `?error=access_denied` when they cancel.
 */
export default async function GitHubCallbackPage({
  searchParams,
}: {
  searchParams: Promise<{ code?: string | string[]; state?: string | string[]; error?: string | string[] }>;
}) {
  const { code, state, error } = await searchParams;
  const first = (value?: string | string[]) => (Array.isArray(value) ? value[0] : value) ?? null;
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Connect GitHub</h1>
      </div>
      <GitHubConnectCallback code={first(code)} state={first(state)} cancelled={first(error) !== null} />
    </div>
  );
}
