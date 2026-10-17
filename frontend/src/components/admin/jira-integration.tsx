"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { ExternalLink, TicketCheck } from "lucide-react";
import { toast } from "sonner";

import { useMemberNames } from "@/components/app/responses/response-parts";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, integrationsApi } from "@/lib/api";
import type { JiraIntegration as Integration, JiraOptions } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

type LoadState = "loading" | "ok" | "forbidden" | "error";

const SELECT_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs " +
  "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px] focus-visible:outline-none " +
  "disabled:opacity-50";

/**
 * The organization's Jira: where approved risk tickets are filed. The organization admin connects it by
 * granting Aegis one Jira site in Atlassian's consent screen, then chooses the project and issue type. Issues
 * are created as the Atlassian account that connected it.
 */
export function JiraIntegration() {
  const { user, isLoading: authLoading } = useAuth();
  const isOrgAdmin = user?.roles.includes("ORG_ADMIN") ?? false;
  const signedIn = user != null;
  const orgId = user?.orgId;
  const [integration, setIntegration] = useState<Integration | null>(null);
  const [state, setState] = useState<LoadState>("loading");
  const [busy, setBusy] = useState<"connect" | "disconnect" | null>(null);
  const [choosing, setChoosing] = useState(false);
  const personName = useMemberNames(!authLoading && signedIn, orgId, user?.id);

  useEffect(() => {
    if (authLoading || !signedIn) return;
    let cancelled = false;
    integrationsApi
      .jira()
      .then((loaded) => {
        if (cancelled) return;
        setIntegration(loaded);
        setState("ok");
      })
      .catch((err) => {
        if (!cancelled) setState(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, signedIn, orgId]);

  async function connect() {
    setBusy("connect");
    try {
      const { authorizeUrl } = await integrationsApi.startJira();
      window.location.assign(authorizeUrl);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not start connecting Jira.");
      setBusy(null);
    }
  }

  async function disconnect() {
    setBusy("disconnect");
    try {
      await integrationsApi.disconnectJira();
      toast.success("Jira disconnected");
      setIntegration(await integrationsApi.jira());
      setChoosing(false);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not disconnect Jira.");
    } finally {
      setBusy(null);
    }
  }

  if (state === "loading") {
    return <Skeleton className="h-40 w-full" />;
  }
  if (state !== "ok" || !integration) {
    return (
      <Section>
        <p className="text-sm text-muted-foreground">
          {state === "forbidden"
            ? "You need a role in this organization to see its integrations."
            : "Could not load the Jira connection. Please try again."}
        </p>
      </Section>
    );
  }
  if (!integration.enabled) {
    return (
      <Section>
        <p className="text-sm text-muted-foreground">
          Jira isn&apos;t enabled on this Aegis deployment. Whoever runs Aegis turns it on by registering an
          Atlassian OAuth 2.0 integration (see <span className="font-mono">backend/aegis-core/JIRA.md</span>).
        </p>
      </Section>
    );
  }

  const intro = (
    <p className="text-sm text-muted-foreground">
      Approved risk tickets are filed in your organization&apos;s Jira. Connecting sends you to Atlassian to grant
      Aegis one Jira site; Aegis creates issues there as your Atlassian account, and nothing else.{" "}
      <Link href="/privacy#jira" className="font-medium text-primary underline-offset-4 hover:underline">
        What Aegis keeps
      </Link>
    </p>
  );

  if (!integration.connected) {
    return (
      <Section>
        {intro}
        <p className="rounded-md border px-4 py-6 text-center text-sm text-muted-foreground">Jira isn&apos;t connected yet.</p>
        {isOrgAdmin ? (
          <Button onClick={connect} disabled={busy !== null}>
            <TicketCheck className="size-4" />
            {busy === "connect" ? "Opening Atlassian…" : "Connect Jira"}
          </Button>
        ) : (
          <p className="text-sm text-muted-foreground">Your organization admin connects Jira.</p>
        )}
      </Section>
    );
  }

  const showForm = isOrgAdmin && (choosing || !integration.projectKey);
  return (
    <Section>
      {intro}
      <dl aria-label="Jira connection" className="grid gap-x-6 gap-y-2 rounded-md border p-4 text-sm sm:grid-cols-[max-content_1fr]">
        <dt className="text-muted-foreground">Site</dt>
        <dd className="min-w-0">
          {integration.siteUrl ? (
            <a
              href={integration.siteUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium underline-offset-4 hover:underline"
            >
              {integration.siteName ?? integration.siteUrl}
              <ExternalLink className="size-3" />
            </a>
          ) : (
            integration.siteName
          )}
        </dd>
        <dt className="text-muted-foreground">Risk tickets go to</dt>
        <dd className="min-w-0">
          {integration.projectKey ? (
            <>
              <span className="font-mono font-medium">{integration.projectKey}</span>
              {integration.issueType && <span className="text-muted-foreground"> as a {integration.issueType}</span>}
            </>
          ) : (
            <span className="text-amber-600 dark:text-amber-400">
              No project chosen yet — risk tickets can&apos;t be filed until one is.
            </span>
          )}
        </dd>
        <dt className="text-muted-foreground">Connected</dt>
        <dd className="min-w-0">
          By {personName(integration.connectedBy)}
          {integration.connectedAt && ` on ${new Date(integration.connectedAt).toLocaleDateString()}`}
        </dd>
      </dl>

      {showForm ? (
        <JiraProjectForm
          integration={integration}
          onSaved={(saved) => {
            setIntegration(saved);
            setChoosing(false);
          }}
          onCancel={integration.projectKey ? () => setChoosing(false) : null}
        />
      ) : isOrgAdmin ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" onClick={() => setChoosing(true)} disabled={busy !== null}>
            Change project
          </Button>
          <DisconnectButton busy={busy !== null} onDisconnect={disconnect} />
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Your organization admin manages the Jira connection.</p>
      )}
    </Section>
  );
}

/**
 * Choose where risk tickets go: a site the grant reaches, one of its projects, and one of the project's issue
 * types. Options load from Jira as each choice is made.
 */
function JiraProjectForm({
  integration,
  onSaved,
  onCancel,
}: {
  integration: Integration;
  onSaved: (saved: Integration) => void;
  onCancel: (() => void) | null;
}) {
  const id = useId();
  // "" means the connected site, which the options call falls back to.
  const [cloudId, setCloudId] = useState("");
  const [projectKey, setProjectKey] = useState(integration.projectKey ?? "");
  const [issueType, setIssueType] = useState(integration.issueType ?? "");
  const [options, setOptions] = useState<JiraOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    integrationsApi
      .jiraOptions({ cloudId: cloudId || undefined, projectKey: projectKey || undefined })
      .then((loaded) => {
        if (cancelled) return;
        setOptions(loaded);
        if (projectKey) {
          // Keep the issue type when the project has it; otherwise start from Task, Jira's usual one.
          setIssueType((current) =>
            loaded.issueTypes.includes(current)
              ? current
              : loaded.issueTypes.includes("Task")
                ? "Task"
                : (loaded.issueTypes[0] ?? ""),
          );
        }
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof ApiError ? err.message : "Could not load your Jira projects.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [cloudId, projectKey]);

  const site = cloudId || options?.cloudId || "";
  const projectListed = options?.projects.some((project) => project.key === projectKey) ?? false;

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!site || !projectKey || !issueType) return;
    setSaving(true);
    try {
      const saved = await integrationsApi.configureJira({ cloudId: site, projectKey, issueType });
      toast.success(`Risk tickets will be filed in ${saved.projectKey}`);
      onSaved(saved);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not save where risk tickets go.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={save} aria-label="Where risk tickets go" className="space-y-3 rounded-md border p-4">
      <p className="text-sm font-medium">Where risk tickets go</p>
      {error && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
      {options && options.sites.length > 1 && (
        <Field id={`${id}-site`} label="Site">
          <select
            id={`${id}-site`}
            className={SELECT_CLASS}
            value={site}
            disabled={saving}
            onChange={(event) => {
              setCloudId(event.target.value);
              setProjectKey("");
              setIssueType("");
            }}
          >
            {options.sites.map((option) => (
              <option key={option.cloudId} value={option.cloudId}>
                {option.name}
              </option>
            ))}
          </select>
        </Field>
      )}
      <Field id={`${id}-project`} label="Project">
        <select
          id={`${id}-project`}
          className={SELECT_CLASS}
          value={projectListed ? projectKey : ""}
          disabled={saving || !options}
          onChange={(event) => setProjectKey(event.target.value)}
        >
          <option value="">{options ? "Choose a project" : "Loading projects…"}</option>
          {options?.projects.map((project) => (
            <option key={project.key} value={project.key}>
              {project.name} ({project.key})
            </option>
          ))}
        </select>
        {options && options.projects.length === 0 && (
          <span className="text-xs text-muted-foreground">
            Your Atlassian account can&apos;t create issues in any project on this site.
          </span>
        )}
      </Field>
      <Field id={`${id}-type`} label="Issue type">
        <select
          id={`${id}-type`}
          className={SELECT_CLASS}
          value={issueType}
          disabled={saving || loading || !projectListed}
          onChange={(event) => setIssueType(event.target.value)}
        >
          {!projectListed && <option value="">Choose a project first</option>}
          {projectListed &&
            options?.issueTypes.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
        </select>
      </Field>
      <div className="flex flex-wrap gap-2 pt-1">
        <Button type="submit" disabled={saving || loading || !site || !projectListed || !issueType}>
          {saving ? "Saving…" : "Save"}
        </Button>
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel} disabled={saving}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}

function Field({ id, label, children }: { id: string; label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1 sm:max-w-sm">
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">
        {label}
      </label>
      {children}
    </div>
  );
}

function DisconnectButton({ busy, onDisconnect }: { busy: boolean; onDisconnect: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button variant="outline" disabled={busy} />}>Disconnect</PopoverTrigger>
      <PopoverContent align="start" className="w-80 space-y-3 text-sm">
        <p className="font-medium">Disconnect Jira?</p>
        <p className="text-muted-foreground">
          Aegis forgets its Jira tokens and stops filing risk tickets for this organization. To withdraw the grant
          itself, remove Aegis under Connected apps in your Atlassian account settings.
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
    <section aria-labelledby="jira-integration" className="space-y-4 rounded-lg border p-5">
      <h2 id="jira-integration" className="text-base font-medium">
        Jira
      </h2>
      {children}
    </section>
  );
}
