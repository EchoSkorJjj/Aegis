"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, integrationsApi } from "@/lib/api";
import type { JiraIntegration } from "@/lib/types";
import { cn } from "@/lib/utils";

type Outcome = { connected: JiraIntegration } | { error: string };

/**
 * Finishes connecting Jira with what Atlassian sent back. The code and state are single-use, so they are sent
 * once (React runs effects twice in development) and taken out of the address bar straight away.
 */
export function JiraConnectCallback({
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
      .finishJira(code, state)
      .then((connected) => setOutcome({ connected }))
      .catch((err) =>
        setOutcome({ error: err instanceof ApiError ? err.message : "Could not connect Jira. Please try again." }),
      );
  }, [code, state, missing]);

  if (missing) {
    return (
      <Panel>
        <p className="text-sm text-muted-foreground">
          {cancelled
            ? "You cancelled on Atlassian's consent screen; nothing was connected."
            : "This link is missing what Atlassian sends back."}
        </p>
        <BackLink>Back to Integrations</BackLink>
      </Panel>
    );
  }
  if (!outcome) {
    return (
      <Panel>
        <p className="text-sm text-muted-foreground">Connecting the Jira site you granted…</p>
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
        <BackLink>Back to Integrations</BackLink>
      </Panel>
    );
  }

  return (
    <Panel>
      <div className="space-y-1 text-sm">
        <p className="font-medium">Connected to {outcome.connected.siteName ?? "your Jira site"}</p>
        <p className="text-muted-foreground">
          Next, choose the project and issue type risk tickets are filed as. Until then, approving a ticket
          can&apos;t file it.
        </p>
      </div>
      <BackLink>Choose the project</BackLink>
    </Panel>
  );
}

function BackLink({ children }: { children: React.ReactNode }) {
  return (
    <Link href="/admin/integrations" className={cn(buttonVariants({ variant: "outline" }))}>
      {children}
    </Link>
  );
}

function Panel({ children }: { children: React.ReactNode }) {
  return <section className="space-y-4 rounded-lg border p-5">{children}</section>;
}
