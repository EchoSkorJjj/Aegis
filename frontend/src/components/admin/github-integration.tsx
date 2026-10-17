"use client";

import { useEffect, useState } from "react";
import { ExternalLink, GitBranch } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, directoryApi, integrationsApi } from "@/lib/api";
import type { GitHubConnection, GitHubIntegration as Integration } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

type LoadState = "loading" | "ok" | "forbidden" | "error";

/**
 * The organization's GitHub connection: which GitHub accounts' App installations Aegis may open draft pull
 * requests on and import SBOMs from. GitHub only knows the App is installed on an account, not which Aegis
 * organization it is for, so the organization admin connects it here — Aegis has them sign in to GitHub
 * and connects only the installations they control.
 */
export function GitHubIntegration() {
  const { user, isLoading: authLoading } = useAuth();
  const isOrgAdmin = user?.roles.includes("ORG_ADMIN") ?? false;
  const signedIn = user != null;
  const orgId = user?.orgId;
  const [integration, setIntegration] = useState<Integration | null>(null);
  const [state, setState] = useState<LoadState>("loading");
  const [people, setPeople] = useState<Map<string, string>>(new Map());
  const [busy, setBusy] = useState<"connect" | number | null>(null);

  async function load() {
    try {
      setIntegration(await integrationsApi.github());
      setState("ok");
    } catch (err) {
      setState(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
    }
  }

  useEffect(() => {
    if (authLoading || !signedIn) return;
    let cancelled = false;
    integrationsApi
      .github()
      .then((loaded) => {
        if (cancelled) return;
        setIntegration(loaded);
        setState("ok");
      })
      .catch((err) => {
        if (!cancelled) setState(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
      });
    // Names for "connected by": the org's member directory, readable by every member.
    directoryApi
      .listMembers({ size: 100 })
      .then((page) => {
        if (!cancelled) setPeople(new Map(page.content.map((member) => [member.id, member.displayName || member.email])));
      })
      .catch(() => {
        // Names are a nicety; the page works without them.
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, signedIn, orgId]);

  async function connect() {
    setBusy("connect");
    try {
      const { authorizeUrl } = await integrationsApi.startGitHub();
      window.location.assign(authorizeUrl);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not start connecting GitHub.");
      setBusy(null);
    }
  }

  async function disconnect(connection: GitHubConnection) {
    setBusy(connection.installationId);
    try {
      await integrationsApi.disconnectGitHub(connection.installationId);
      toast.success(`Disconnected ${connection.accountLogin}`);
      await load();
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not disconnect it.");
    } finally {
      setBusy(null);
    }
  }

  function personName(id: string): string {
    if (id === user?.id) return "you";
    return people.get(id) ?? "a member";
  }

  if (state === "loading") {
    return <Skeleton className="h-48 w-full" />;
  }
  if (state !== "ok" || !integration) {
    return (
      <Section>
        <p className="text-sm text-muted-foreground">
          {state === "forbidden"
            ? "You need a role in this organization to see its integrations."
            : "Could not load the GitHub connection. Please try again."}
        </p>
      </Section>
    );
  }
  if (!integration.enabled) {
    return (
      <Section>
        <p className="text-sm text-muted-foreground">
          GitHub isn&apos;t enabled on this Aegis deployment. Whoever runs Aegis turns it on by registering the
          Aegis GitHub App (see <span className="font-mono">backend/aegis-core/GITHUB_APP.md</span>).
        </p>
      </Section>
    );
  }

  const connections = integration.connections;
  return (
    <Section>
      <p className="text-sm text-muted-foreground">
        Aegis opens approved draft pull requests and imports SBOMs through the Aegis GitHub App, but only on the
        GitHub accounts connected here. Connecting asks you to sign in to GitHub so Aegis can check the
        installation is yours: on your own account, or on a GitHub organization you administer.
      </p>

      <div className="rounded-md border">
        {connections.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">No GitHub account is connected yet.</p>
        ) : (
          <ul aria-label="Connected GitHub accounts" className="divide-y">
            {connections.map((connection) => (
              <li key={connection.installationId} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm">
                <span className="font-medium">{connection.accountLogin}</span>
                <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                  {connection.accountType === "Organization" ? "GitHub organization" : "Personal account"}
                </span>
                <span className="text-xs text-muted-foreground">
                  Connected by {personName(connection.connectedBy)} on{" "}
                  {new Date(connection.connectedAt).toLocaleDateString()}
                </span>
                {isOrgAdmin && (
                  <DisconnectButton
                    connection={connection}
                    busy={busy !== null}
                    onDisconnect={() => disconnect(connection)}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {isOrgAdmin ? (
        <ol className="space-y-3 text-sm">
          <li>
            <span className="font-medium">1. Install the App on the GitHub account that owns your repositories.</span>{" "}
            <span className="text-muted-foreground">Choose only the repositories Aegis should work on.</span>{" "}
            {integration.installUrl && (
              <a
                href={integration.installUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 font-medium text-primary underline-offset-4 hover:underline"
              >
                Install on GitHub
                <ExternalLink className="size-3" />
              </a>
            )}
          </li>
          <li className="space-y-2">
            <p>
              <span className="font-medium">2. Connect it.</span>{" "}
              <span className="text-muted-foreground">
                Aegis connects every installation you control and tells you about any it can&apos;t.
              </span>
            </p>
            <Button onClick={connect} disabled={busy !== null}>
              <GitBranch className="size-4" />
              {busy === "connect" ? "Opening GitHub…" : "Connect GitHub"}
            </Button>
          </li>
        </ol>
      ) : (
        <p className="text-sm text-muted-foreground">Your organization admin connects GitHub.</p>
      )}
    </Section>
  );
}

function DisconnectButton({
  connection,
  busy,
  onDisconnect,
}: {
  connection: GitHubConnection;
  busy: boolean;
  onDisconnect: () => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        render={<Button variant="outline" size="sm" className="ml-auto" disabled={busy} aria-label={`Disconnect ${connection.accountLogin}`} />}
      >
        Disconnect
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 space-y-3 text-sm">
        <p className="font-medium">Disconnect {connection.accountLogin}?</p>
        <p className="text-muted-foreground">
          Aegis stops opening pull requests and importing SBOMs on its repositories for this organization. The
          App stays installed on GitHub until you uninstall it there.
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              setOpen(false);
              onDisconnect();
            }}
          >
            Disconnect
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

function Section({ children }: { children: React.ReactNode }) {
  return (
    <section aria-labelledby="github-integration" className="space-y-4 rounded-lg border p-5">
      <h2 id="github-integration" className="text-base font-medium">
        GitHub
      </h2>
      {children}
    </section>
  );
}
