"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, integrationsApi } from "@/lib/api";
import type { GitHubConnectResult } from "@/lib/types";
import { cn } from "@/lib/utils";

type Outcome = { result: GitHubConnectResult } | { error: string };

/**
 * Finishes connecting GitHub with what GitHub sent back. The code and state are single-use, so they are sent
 * once (React runs effects twice in development) and taken out of the address bar straight away.
 */
export function GitHubConnectCallback({
  code,
  state,
  cancelled,
}: {
  code: string | null;
  state: string | null;
  cancelled: boolean;
}) {
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const sent = useRef(false);
  const missing = cancelled || !code || !state;

  useEffect(() => {
    if (sent.current || missing || !code || !state) return;
    sent.current = true;
    window.history.replaceState(null, "", window.location.pathname);
    integrationsApi
      .finishGitHub(code, state)
      .then((result) => setOutcome({ result }))
      .catch((err) =>
        setOutcome({ error: err instanceof ApiError ? err.message : "Could not connect GitHub. Please try again." }),
      );
  }, [code, state, missing]);

  const back = (
    <Link href="/admin/integrations" className={cn(buttonVariants({ variant: "outline" }))}>
      Back to Integrations
    </Link>
  );

  if (missing) {
    return (
      <Panel>
        <p className="text-sm text-muted-foreground">
          {cancelled ? "You cancelled the GitHub sign-in; nothing was connected." : "This link is missing what GitHub sends back."}
        </p>
        {back}
      </Panel>
    );
  }
  if (!outcome) {
    return (
      <Panel>
        <p className="text-sm text-muted-foreground">Checking which installations you control…</p>
        <Skeleton className="h-6 w-64" />
      </Panel>
    );
  }
  if ("error" in outcome) {
    return (
      <Panel>
        <p role="alert" className="text-sm">
          {outcome.error}
        </p>
        {back}
      </Panel>
    );
  }

  const { connected, skipped } = outcome.result;
  return (
    <Panel>
      {connected.length > 0 ? (
        <div className="space-y-1 text-sm">
          <p className="font-medium">Connected</p>
          <ul aria-label="Connected" className="list-inside list-disc text-muted-foreground">
            {connected.map((connection) => (
              <li key={connection.installationId}>
                <span className="text-foreground">{connection.accountLogin}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <p className="text-sm">
          Nothing was connected: GitHub shows no installation of the Aegis App on an account you control. Install it
          on your GitHub account (or a GitHub organization you administer), then connect again.
        </p>
      )}
      {skipped.length > 0 && (
        <div className="space-y-1 text-sm">
          <p className="font-medium">Not connected</p>
          <ul aria-label="Not connected" className="space-y-1">
            {skipped.map((account) => (
              <li key={account.accountLogin}>
                <span className="font-medium">{account.accountLogin}</span>{" "}
                <span className="text-muted-foreground">— {account.reason}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {back}
    </Panel>
  );
}

function Panel({ children }: { children: React.ReactNode }) {
  return <section className="space-y-4 rounded-lg border p-5">{children}</section>;
}
