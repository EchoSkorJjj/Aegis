"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { Copy, ExternalLink, GitBranch, GitPullRequest, Pencil } from "lucide-react";
import { toast } from "sonner";

import {
  BackToQueue,
  ConfirmButton,
  DecisionPanel,
  DetailShell,
  DismissButton,
  DismissedPanel,
  Fact,
  StateCard,
  useMemberNames,
  useServiceDecisionRight,
} from "@/components/app/responses/response-parts";
import { ResponseLifecycle } from "@/components/app/responses/response-lifecycle";
import {
  TicketStatusBadge,
  UpgradeStatusBadge,
  formatDate,
  formatDateTime,
} from "@/components/app/responses/response-status";
import { UpgradeEditsTable } from "@/components/app/responses/upgrade-edits";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, integrationsApi, ticketApi, upgradeApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { JiraIntegration, RiskTicket, TicketPriority, UpgradeProposal } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** A filing interrupted mid-way may be retried after this long — aegis-core's STALE_FILING. */
const STALE_FILING_MS = 10 * 60 * 1000;

type LoadState = "loading" | "ok" | "forbidden" | "notfound" | "error";
type Busy = "approve" | "dismiss" | "edit" | "override" | "pr-approve" | "pr-leave-out" | "pr-draft" | null;

const PRIORITIES: TicketPriority[] = ["HIGHEST", "HIGH", "MEDIUM", "LOW", "LOWEST"];
const MAX_REASON = 1000;
const FIELD_CLASS =
  "w-full rounded-md border border-input bg-transparent px-2.5 py-2 text-sm shadow-xs outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

/** What a reviewer can change before deciding: the drafted narrative, or Aegis's priority and due date. */
type EditInput = { summary: string; risk: string; impact: string; actions: string[] };
type OverrideInput = { priority: TicketPriority; dueDate: string | null; reason: string };

/**
 * One response: the risk ticket for a change's impact on a service and, for a vulnerability, the upgrade pull
 * request that fixes it — everything the approver needs, and the decision itself. Approving files the ticket
 * in the organization's Jira, then opens the pull request as a draft under the ticket's key; dismissing records
 * why. The pull request can also be left out, retried or drafted on its own. Before deciding, a reviewer may edit
 * the drafted narrative or override Aegis's priority and due date with a reason; both stay on record, and every
 * decision carries the version the reviewer read, so one made on an outdated view is refused. The ticket is shown
 * as ServiceNow incident fields too, for an organization that tracks risk there.
 */
export function RiskTicketView({ ticketId }: { ticketId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  const roles = user?.roles ?? [];
  const canRead = canReadInventory(roles);
  const userId = user?.id;

  // `fetchedAt` is when the response was read, so "is this filing stale?" is decided from data, not from the
  // clock during render.
  const [loaded, setLoaded] = useState<{ ticket: RiskTicket; fetchedAt: number } | null>(null);
  const [state, setState] = useState<LoadState>("loading");
  // Undefined while loading; null when it couldn't be read.
  const [jira, setJira] = useState<JiraIntegration | null | undefined>(undefined);
  const [busy, setBusy] = useState<Busy>(null);
  const [editing, setEditing] = useState(false);
  const personName = useMemberNames(!authLoading && canRead, user?.orgId, userId);
  const canDecide = useServiceDecisionRight(roles, loaded?.ticket.serviceId, userId);

  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState("loading");
    ticketApi
      .get(ticketId)
      .then((ticket) => {
        if (cancelled) return;
        setLoaded({ ticket, fetchedAt: Date.now() });
        setState("ok");
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 403) setState("forbidden");
        else if (err instanceof ApiError && (err.status === 404 || err.status === 400)) setState("notfound");
        else setState("error");
      });
    // Where approving files it. Unknown on failure: the decision still works, the backend says why it can't.
    integrationsApi
      .jira()
      .then((integration) => {
        if (!cancelled) setJira(integration);
      })
      .catch(() => {
        if (!cancelled) setJira(null);
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, canRead, user?.orgId, ticketId]);

  async function reload() {
    try {
      const ticket = await ticketApi.get(ticketId);
      setLoaded({ ticket, fetchedAt: Date.now() });
    } catch {
      // Keep what is on screen; the toast already said what went wrong.
    }
  }

  /** Run a decision; show what came back, or say why it didn't and show where things now stand. */
  async function decide(
    kind: Busy,
    action: () => Promise<RiskTicket | void>,
    done: (ticket: RiskTicket) => void,
    failed: string,
  ): Promise<boolean> {
    setBusy(kind);
    try {
      const result = await action();
      const ticket = result ?? (await ticketApi.get(ticketId));
      setLoaded({ ticket, fetchedAt: Date.now() });
      done(ticket);
      return true;
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : failed);
      // A failure is recorded on the ticket or pull request, and a conflict means someone decided first.
      await reload();
      return false;
    } finally {
      setBusy(null);
    }
  }

  function approve() {
    void decide(
      "approve",
      () => ticketApi.approve(ticketId, loaded?.ticket.version),
      (ticket) => {
        const pullRequest = current(ticket);
        const key = ticket.issueKey ?? "a ticket";
        if (pullRequest?.status === "FAILED") {
          toast.warning(`Filed as ${key}, but the pull request could not be opened.`);
        } else {
          toast.success(`Filed as ${key}${pullRequest?.status === "OPENED" ? ", and the draft pull request is open" : ""}`);
        }
      },
      "Could not file the ticket.",
    );
  }

  function dismiss(reason: string) {
    return decide(
      "dismiss",
      () => ticketApi.dismiss(ticketId, reason, loaded?.ticket.version),
      () => toast.success("Response dismissed"),
      "Could not dismiss the response.",
    );
  }

  async function saveEdit(input: EditInput): Promise<boolean> {
    const version = loaded?.ticket.version ?? 0;
    const saved = await decide(
      "edit",
      () => ticketApi.edit(ticketId, { version, ...input }),
      () => toast.success("Draft updated — the first draft stays on record"),
      "Could not save the edit.",
    );
    if (saved) setEditing(false);
    return saved;
  }

  function override(input: OverrideInput) {
    const version = loaded?.ticket.version ?? 0;
    return decide(
      "override",
      () => ticketApi.override(ticketId, { version, ...input }),
      () => toast.success("Priority and due date overridden — Aegis's assessment stays on record"),
      "Could not override the priority and due date.",
    );
  }

  function approvePullRequest(proposalId: string) {
    void decide(
      "pr-approve",
      () => ticketApi.approvePullRequest(ticketId, proposalId),
      (ticket) => {
        const pullRequest = ticket.pullRequests.find((candidate) => candidate.id === proposalId);
        toast.success(
          pullRequest?.pullRequestNumber
            ? `Draft pull request #${pullRequest.pullRequestNumber} opened`
            : "Draft pull request opened",
        );
      },
      "Could not open the draft pull request.",
    );
  }

  function leaveOut(proposalId: string, reason: string) {
    return decide(
      "pr-leave-out",
      async () => {
        await upgradeApi.dismiss(proposalId, reason);
      },
      () => toast.success("Pull request left out"),
      "Could not leave the pull request out.",
    );
  }

  function draftPullRequest() {
    void decide(
      "pr-draft",
      () => ticketApi.draftPullRequest(ticketId),
      () => toast.success("Upgrade pull request drafted — someone other than you opens it"),
      "Could not draft the pull request.",
    );
  }

  const ticket = loaded?.ticket;
  const back = <BackToQueue decided={ticket?.status === "FILED" || ticket?.status === "DISMISSED"} />;

  if (!authLoading && !canRead) {
    return (
      <DetailShell back={back}>
        <StateCard>You don&apos;t have access to responses — an Aegis domain role is required.</StateCard>
      </DetailShell>
    );
  }

  if (state === "loading") {
    return (
      <DetailShell back={back}>
        <Skeleton className="h-8 w-96" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </DetailShell>
    );
  }

  if (state !== "ok" || !loaded || !ticket) {
    return (
      <DetailShell back={back}>
        <StateCard>
          {state === "forbidden"
            ? "You don't have access to responses — an Aegis domain role is required."
            : state === "notfound"
              ? "This response doesn't exist in your organization."
              : "Could not load the response. Please try again."}
        </StateCard>
      </DetailShell>
    );
  }

  const stale =
    ticket.status === "FILING" &&
    ticket.decidedAt != null &&
    Date.parse(ticket.decidedAt) < loaded.fetchedAt - STALE_FILING_MS;
  const pullRequest = current(ticket);
  const leftOut = pullRequest
    ? null
    : (ticket.pullRequests.find((candidate) => candidate.status === "DISMISSED") ?? null);

  return (
    <DetailShell back={back}>
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <TicketStatusBadge status={ticket.status} />
          <span className="text-sm text-muted-foreground">
            {sentenceCase(ticket.priority)} priority · impact {ticket.score.toFixed(1)} ({sentenceCase(ticket.tier)})
          </span>
        </div>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight break-words">{ticket.title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Response for{" "}
          {ticket.serviceName ? (
            <Link
              href={`/inventory/services/${ticket.serviceId}`}
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              {ticket.serviceName}
            </Link>
          ) : (
            <span className="italic">a deleted service</span>
          )}
          {pullRequest ? ": its risk ticket and the upgrade pull request that fixes it" : ": its risk ticket"}.
          Drafted by {personName(ticket.createdBy)} on {formatDateTime(ticket.createdAt)}.
        </p>
      </div>

      <ResponseLifecycle ticket={ticket} personName={personName} />

      <Decision
        ticket={ticket}
        pullRequest={pullRequest}
        jira={jira}
        canDecide={canDecide}
        userId={userId}
        auditor={roles.includes("COMPLIANCE_ADMIN")}
        stale={stale}
        busy={busy}
        personName={personName}
        onApprove={approve}
        onDismiss={dismiss}
      />

      {ticket.signalType === "VULNERABILITY" && (
        <PullRequestSection
          ticket={ticket}
          pullRequest={pullRequest}
          leftOut={leftOut}
          canDecide={canDecide === true}
          userId={userId}
          busy={busy}
          personName={personName}
          onApprove={approvePullRequest}
          onLeaveOut={leaveOut}
          onDraft={draftPullRequest}
        />
      )}

      <section aria-labelledby="ticket-content" className="rounded-lg border">
        <div className="flex flex-wrap items-start justify-between gap-3 border-b px-4 py-3">
          <div className="min-w-0">
            <h2 id="ticket-content" className="text-sm font-medium">
              Ticket
            </h2>
            <p className="text-xs text-muted-foreground">
              {ticket.aiDrafted
                ? "Exactly what will be filed. The summary, risk, impact and actions are AI-drafted and unverified; the title, priority, due date, labels and evidence come from Aegis's matching and scoring."
                : "Exactly what will be filed. The AI couldn't draft this one, so it holds only Aegis's own facts."}
            </p>
            {ticket.edit && (
              <p className="mt-1 text-xs text-muted-foreground">
                Edited by {personName(ticket.edit.by)} on {formatDateTime(ticket.edit.at)}.
              </p>
            )}
          </div>
          {ticket.status === "PROPOSED" && canDecide === true && !editing && (
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" size="sm" disabled={busy !== null} onClick={() => setEditing(true)}>
                <Pencil />
                Edit draft
              </Button>
              <OverrideButton ticket={ticket} busy={busy} onOverride={override} />
            </div>
          )}
        </div>
        {ticket.override && (
          <div className="space-y-1 border-b bg-sky-500/5 px-4 py-3 text-sm">
            <p>
              <span className="font-medium">Priority and due date overridden</span> by {personName(ticket.override.by)}{" "}
              on {formatDateTime(ticket.override.at)}. Aegis assessed {sentenceCase(ticket.override.assessedPriority)}{" "}
              priority,{" "}
              {ticket.override.assessedDueDate ? `due ${formatDate(ticket.override.assessedDueDate)}` : "no due date"}.
            </p>
            <blockquote className="border-l-2 pl-3 whitespace-pre-wrap text-muted-foreground">
              {ticket.override.reason}
            </blockquote>
          </div>
        )}
        <dl className="grid gap-x-6 gap-y-3 border-b p-4 text-sm sm:grid-cols-[max-content_1fr]">
          <Fact label="Priority">
            {sentenceCase(ticket.priority)}
            {ticket.override && <span className="text-muted-foreground"> (overridden)</span>}
          </Fact>
          <Fact label="Due">
            {ticket.dueDate ? formatDate(ticket.dueDate) : "No due date"}
            {ticket.override && <span className="text-muted-foreground"> (overridden)</span>}
          </Fact>
          <Fact label="Labels">
            <span className="flex flex-wrap gap-1.5">
              {ticket.labels.map((label) => (
                <span key={label} className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">
                  {label}
                </span>
              ))}
            </span>
          </Fact>
          <Fact label="Change">
            <Link
              href={`/advisories/${ticket.changeSignalId}`}
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              View the impact of {ticket.changeTitle} →
            </Link>
          </Fact>
        </dl>
        <div className="space-y-4 p-4 text-sm">
          {editing ? (
            <EditDraftForm ticket={ticket} busy={busy} onSave={saveEdit} onCancel={() => setEditing(false)} />
          ) : (
            <Narrative summary={ticket.summary} risk={ticket.risk} impact={ticket.impact} actions={ticket.actions} />
          )}
          {ticket.edit && !editing && (
            <details className="rounded-md border px-3 py-2">
              <summary className="cursor-pointer text-xs font-medium text-muted-foreground">
                The draft as first written
              </summary>
              <div className="mt-3 space-y-4 text-muted-foreground">
                <Narrative
                  summary={ticket.edit.draftedSummary}
                  risk={ticket.edit.draftedRisk}
                  impact={ticket.edit.draftedImpact}
                  actions={ticket.edit.draftedActions}
                />
              </div>
            </details>
          )}
          <TicketSection title="Evidence">
            <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
              {ticket.evidence.map((line, index) => (
                <li key={index}>{line}</li>
              ))}
            </ul>
          </TicketSection>
          {ticket.citations.length > 0 && (
            <TicketSection title="Sources the AI cited">
              <ul className="space-y-1 font-mono text-xs text-muted-foreground">
                {ticket.citations.map((citation, index) => (
                  <li key={index} className="break-words">
                    {citation}
                  </li>
                ))}
              </ul>
            </TicketSection>
          )}
        </div>
      </section>

      {ticket.serviceNow && <ServiceNowFields fields={ticket.serviceNow} />}
    </DetailShell>
  );
}

/** The response's pull request that isn't left out, if any: at most one ever is. */
function current(ticket: RiskTicket): UpgradeProposal | null {
  return ticket.pullRequests.find((candidate) => candidate.status !== "DISMISSED") ?? null;
}

function Decision({
  ticket,
  pullRequest,
  jira,
  canDecide,
  userId,
  auditor,
  stale,
  busy,
  personName,
  onApprove,
  onDismiss,
}: {
  ticket: RiskTicket;
  pullRequest: UpgradeProposal | null;
  /** Where approving files it: undefined while loading, null when it couldn't be read. */
  jira: JiraIntegration | null | undefined;
  /** "checking" while a service owner's ownership of this service is being confirmed. */
  canDecide: boolean | "checking";
  userId: string | undefined;
  /** A compliance admin audits these decisions rather than making them. */
  auditor: boolean;
  stale: boolean;
  busy: Busy;
  personName: (id: string | null) => string;
  onApprove: () => void;
  onDismiss: (reason: string) => Promise<boolean>;
}) {
  const decider = personName(ticket.decidedBy);
  const when = formatDateTime(ticket.decidedAt);

  if (ticket.status === "FILED") {
    return (
      <DecisionPanel tone="done" title="Filed in Jira">
        <p>
          Approved by {decider} on {when}.{" "}
          {ticket.issueUrl && (
            <a
              href={ticket.issueUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline"
            >
              {ticket.issueKey ?? "Open the issue"}
              <ExternalLink className="size-3" />
            </a>
          )}
        </p>
        <p className="text-muted-foreground">
          Track and close it in Jira; Aegis doesn&apos;t change it after filing.
          {pullRequest && " Its upgrade pull request carries the ticket's key, below."}
        </p>
      </DecisionPanel>
    );
  }

  if (ticket.status === "DISMISSED") {
    return <DismissedPanel decider={decider} when={when} reason={ticket.dismissReason} />;
  }

  // Approving can only work once Jira is connected with a project chosen; say what is missing instead of
  // offering a button that is bound to fail. Unreadable (null) leaves the decision to the backend, and the
  // button waits while the status loads rather than flash and vanish.
  const tracker =
    jira == null
      ? null
      : !jira.enabled
        ? "No issue tracker is enabled on this Aegis deployment, so the ticket can't be filed from here: use the ServiceNow fields below or file it by hand, then dismiss it with where it went."
        : !jira.connected
          ? "Jira isn't connected to this organization yet. An organization admin connects it under Organization → Integrations; until then, use the ServiceNow fields below or file it by hand."
          : !jira.projectKey
            ? "Jira is connected, but no project is chosen for risk tickets yet. An organization admin chooses it under Organization → Integrations."
            : null;
  const destination =
    jira?.connected && jira.projectKey
      ? `${jira.projectKey}${jira.siteName ? ` on ${jira.siteName}` : ""}${jira.issueType ? ` as a ${jira.issueType}` : ""}`
      : "your organization's Jira";

  // Approving opens the pull request too, which changes a repository: never its drafter's call. A response
  // without one only records the risk, so its drafter may file it.
  const opens = pullRequest != null && (pullRequest.status === "PROPOSED" || pullRequest.status === "FAILED");
  const drafted = ticket.createdBy === userId || pullRequest?.createdBy === userId;
  const canApprove = !(opens && drafted);
  const canDismiss = ticket.status !== "FILING";
  const actions =
    canDecide === "checking" ? null : canDecide ? (
      <>
        {!canApprove && (
          <p className="text-muted-foreground">
            You drafted this response, so someone else approves it: an analyst or one of this service&apos;s owners.
            Leave its pull request out and you can file the ticket yourself.
          </p>
        )}
        {((canApprove && !tracker) || canDismiss) && (
          <div className="flex flex-wrap items-center gap-2 pt-1">
            {canApprove && !tracker && jira !== undefined && (
              <ConfirmButton
                label={ticket.status !== "PROPOSED" ? "Try again" : opens ? "Approve" : "File ticket"}
                busyLabel="Filing in Jira…"
                busy={busy === "approve"}
                disabled={busy !== null}
                question={opens ? "File the ticket and open the pull request?" : "File this ticket in Jira?"}
                confirmLabel={opens ? "Approve response" : "File ticket"}
                onConfirm={onApprove}
              >
                Aegis files it in <span className="font-medium text-foreground">{destination}</span>, exactly as shown
                below, as the Atlassian account that connected Jira
                {opens
                  ? ", then opens the upgrade pull request as a draft with the ticket's key in its title. It never merges it"
                  : ""}
                , and doesn&apos;t change the issue afterwards.
              </ConfirmButton>
            )}
            {canDismiss && (
              <DismissButton
                busy={busy === "dismiss"}
                disabled={busy !== null}
                question="Why is no response needed?"
                placeholder="e.g. Already tracked in PAY-231; the vulnerable feature is switched off."
                what="response"
                onDismiss={onDismiss}
              />
            )}
          </div>
        )}
      </>
    ) : (
      <p className="text-muted-foreground">
        {auditor && "You audit these decisions rather than make them. "}
        An analyst or one of this service&apos;s owners approves or dismisses it.
      </p>
    );

  if (ticket.status === "FAILED") {
    return (
      <DecisionPanel tone="failed" title="Filing the ticket failed">
        <p>
          Approved by {decider} on {when}, but the ticket could not be filed{ticket.failureMessage ? ":" : "."}
        </p>
        {ticket.failureMessage && (
          <p className="rounded-md bg-background/60 px-3 py-2 font-mono text-xs break-words">
            {ticket.failureMessage}
          </p>
        )}
        <p className="text-muted-foreground">Fix the cause and try again, or dismiss the response.</p>
        {actions}
      </DecisionPanel>
    );
  }

  if (ticket.status === "FILING") {
    return (
      <DecisionPanel tone="working" title={stale ? "The approval was interrupted" : "Filing the ticket in Jira"}>
        <p>
          Approved by {decider} on {when}.{" "}
          {stale
            ? "The ticket was never confirmed as filed, so the approval can be retried."
            : "Aegis is filing the ticket — refresh in a moment."}
        </p>
        {stale && actions}
      </DecisionPanel>
    );
  }

  return (
    <DecisionPanel tone="waiting" title="Awaiting a decision">
      <p className="text-muted-foreground">
        Nothing has been filed{opens ? " or opened" : ""}.{" "}
        {tracker ??
          (opens
            ? `Approving files this ticket in ${destination}, then opens the upgrade pull request below with the ticket's key in its title; dismissing records why no response is needed.`
            : `Approving files this ticket in ${destination}; dismissing records why it isn't needed.`)}
      </p>
      {actions}
    </DecisionPanel>
  );
}

/**
 * The response's upgrade pull request: what it changes and where it stands, with what can be done about it on
 * its own — leave it out, open it once the ticket is filed, try again — or, when it has none, draft one.
 */
function PullRequestSection({
  ticket,
  pullRequest,
  leftOut,
  canDecide,
  userId,
  busy,
  personName,
  onApprove,
  onLeaveOut,
  onDraft,
}: {
  ticket: RiskTicket;
  pullRequest: UpgradeProposal | null;
  /** The last one left out, when there is no current one. */
  leftOut: UpgradeProposal | null;
  canDecide: boolean;
  userId: string | undefined;
  busy: Busy;
  personName: (id: string | null) => string;
  onApprove: (proposalId: string) => void;
  onLeaveOut: (proposalId: string, reason: string) => Promise<boolean>;
  onDraft: () => void;
}) {
  const filed = ticket.status === "FILED";
  const pending = pullRequest != null && (pullRequest.status === "PROPOSED" || pullRequest.status === "FAILED");
  return (
    <section aria-labelledby="response-pull-request" className="rounded-lg border">
      <div className="flex flex-wrap items-center gap-2 border-b px-4 py-3">
        <GitPullRequest className="size-4 text-muted-foreground" />
        <h2 id="response-pull-request" className="text-sm font-medium">
          Upgrade pull request
        </h2>
        {pullRequest && <UpgradeStatusBadge status={pullRequest.status} />}
      </div>
      {pullRequest ? (
        <div className="space-y-3 p-4 text-sm">
          <p className="font-medium break-words">{pullRequest.title}</p>
          <UpgradeEditsTable edits={pullRequest.edits} />
          <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
            <GitBranch className="size-3.5" />
            <span className="font-mono break-all">{pullRequest.headBranch}</span>
            into
            <span className="font-mono">{pullRequest.baseBranch ?? "the default branch"}</span>
            of
            <span className="font-mono break-all">{pullRequest.repositoryUrl}</span>
          </p>
          <PullRequestState pullRequest={pullRequest} filed={filed} personName={personName} />
          <div className="flex flex-wrap items-center gap-2">
            {canDecide && filed && pending && pullRequest.createdBy !== userId && (
              <ConfirmButton
                label={pullRequest.status === "FAILED" ? "Try again" : "Open pull request"}
                busyLabel="Opening draft PR…"
                busy={busy === "pr-approve"}
                disabled={busy !== null}
                question="Open the draft pull request?"
                confirmLabel="Open draft PR"
                onConfirm={() => onApprove(pullRequest.id)}
              >
                Aegis re-reads each manifest, applies the upgrade, commits it to{" "}
                <span className="font-mono break-all text-foreground">{pullRequest.headBranch}</span> and opens a{" "}
                <span className="font-medium text-foreground">draft</span> pull request titled with{" "}
                {ticket.issueKey ?? "the ticket's key"}. It never merges.
              </ConfirmButton>
            )}
            {canDecide && pending && (
              <DismissButton
                busy={busy === "pr-leave-out"}
                disabled={busy !== null}
                question="Why leave the pull request out?"
                placeholder="e.g. We upgrade by hand with the next release."
                what="pull request"
                label="Leave out"
                busyLabel="Leaving out…"
                submitLabel="Leave it out"
                onDismiss={(reason) => onLeaveOut(pullRequest.id, reason)}
              />
            )}
            <Link
              href={`/responses/upgrades/${pullRequest.id}`}
              className="text-xs font-medium text-primary underline-offset-4 hover:underline"
            >
              The pull request&apos;s full text →
            </Link>
          </div>
          {canDecide && filed && pending && pullRequest.createdBy === userId && (
            <p className="text-xs text-muted-foreground">You drafted this pull request, so someone else opens it.</p>
          )}
        </div>
      ) : (
        <div className="space-y-3 p-4 text-sm">
          <p className="text-muted-foreground">
            {leftOut
              ? `Left out by ${personName(leftOut.decidedBy)} on ${formatDateTime(leftOut.decidedAt)}${
                  leftOut.dismissReason ? `: ${leftOut.dismissReason}` : "."
                }`
              : "None: Aegis couldn't automate one when the response was drafted. The evidence below says why."}
          </p>
          {canDecide && ticket.status !== "DISMISSED" && (
            <Button variant="outline" size="sm" disabled={busy !== null} onClick={onDraft}>
              <GitPullRequest />
              {busy === "pr-draft" ? "Drafting…" : "Draft upgrade pull request"}
            </Button>
          )}
        </div>
      )}
    </section>
  );
}

function PullRequestState({
  pullRequest,
  filed,
  personName,
}: {
  pullRequest: UpgradeProposal;
  filed: boolean;
  personName: (id: string | null) => string;
}) {
  switch (pullRequest.status) {
    case "PROPOSED":
      return (
        <p className="text-muted-foreground">
          {filed
            ? "The ticket is filed; this pull request waits to be opened on its own."
            : "Opens as a draft, with the ticket's key in front of its title, when the response is approved."}
        </p>
      );
    case "OPENING":
      return <p className="text-muted-foreground">Aegis is opening the draft pull request — refresh in a moment.</p>;
    case "FAILED":
      return (
        <div className="space-y-1">
          <p>
            Approved by {personName(pullRequest.decidedBy)} on {formatDateTime(pullRequest.decidedAt)}, but it could
            not be opened{pullRequest.failureMessage ? ":" : "."}
          </p>
          {pullRequest.failureMessage && (
            <p className="rounded-md bg-red-500/5 px-3 py-2 font-mono text-xs break-words">
              {pullRequest.failureMessage}
            </p>
          )}
          <p className="text-muted-foreground">Fix the cause and try again, or leave it out.</p>
        </div>
      );
    case "OPENED":
      return (
        <p>
          Opened as a draft after {personName(pullRequest.decidedBy)} approved it on{" "}
          {formatDateTime(pullRequest.decidedAt)}.{" "}
          {pullRequest.pullRequestUrl && (
            <a
              href={pullRequest.pullRequestUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline"
            >
              {pullRequest.pullRequestNumber ? `Pull request #${pullRequest.pullRequestNumber}` : "Open the pull request"}
              <ExternalLink className="size-3" />
            </a>
          )}
        </p>
      );
    default:
      return null;
  }
}

/** The same ticket as a ServiceNow incident: fields to paste, or to POST to the Table API. */
function ServiceNowFields({ fields }: { fields: Record<string, string> }) {
  const json = JSON.stringify(fields, null, 2);

  async function copy() {
    try {
      await navigator.clipboard.writeText(json);
      toast.success("ServiceNow fields copied");
    } catch {
      toast.error("Could not copy; select the fields and copy them instead.");
    }
  }

  return (
    <section aria-labelledby="ticket-servicenow" className="rounded-lg border">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b px-4 py-3">
        <div className="min-w-0">
          <h2 id="ticket-servicenow" className="text-sm font-medium">
            ServiceNow incident
          </h2>
          <p className="text-xs text-muted-foreground">
            Aegis files tickets in Jira. To track this one in ServiceNow, paste these fields into a new incident,
            or POST them to its Table API (<span className="font-mono">/api/now/table/incident</span>).
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={copy}>
          <Copy />
          Copy fields
        </Button>
      </div>
      <pre className="max-h-96 overflow-auto p-4 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
        {json}
      </pre>
    </section>
  );
}

function Narrative({
  summary,
  risk,
  impact,
  actions,
}: {
  summary: string;
  risk: string;
  impact: string;
  actions: string[];
}) {
  return (
    <>
      <TicketSection title="Summary">
        <p className="whitespace-pre-wrap">{summary}</p>
      </TicketSection>
      <TicketSection title="Risk">
        <p className="whitespace-pre-wrap">{risk}</p>
      </TicketSection>
      <TicketSection title="Impact">
        <p className="whitespace-pre-wrap">{impact}</p>
      </TicketSection>
      <TicketSection title="Recommended actions">
        <ul className="list-disc space-y-1 pl-5">
          {actions.map((action, index) => (
            <li key={index}>{action}</li>
          ))}
        </ul>
      </TicketSection>
    </>
  );
}

/** Rewrite the drafted narrative exactly as it will be filed; the draft as first written stays on record. */
function EditDraftForm({
  ticket,
  busy,
  onSave,
  onCancel,
}: {
  ticket: RiskTicket;
  busy: Busy;
  onSave: (input: EditInput) => Promise<boolean>;
  onCancel: () => void;
}) {
  const id = useId();
  const [summary, setSummary] = useState(ticket.summary);
  const [risk, setRisk] = useState(ticket.risk);
  const [impact, setImpact] = useState(ticket.impact);
  const [actions, setActions] = useState(ticket.actions.join("\n"));
  const lines = actions
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const complete = summary.trim() !== "" && risk.trim() !== "" && impact.trim() !== "" && lines.length > 0;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!complete) return;
    await onSave({ summary: summary.trim(), risk: risk.trim(), impact: impact.trim(), actions: lines });
  }

  const fields: { key: string; label: string; value: string; set: (value: string) => void; rows: number }[] = [
    { key: "summary", label: "Summary", value: summary, set: setSummary, rows: 3 },
    { key: "risk", label: "Risk", value: risk, set: setRisk, rows: 3 },
    { key: "impact", label: "Impact", value: impact, set: setImpact, rows: 3 },
    { key: "actions", label: "Recommended actions, one per line", value: actions, set: setActions, rows: 4 },
  ];

  return (
    <form onSubmit={submit} aria-label="Edit the draft" className="space-y-3">
      {fields.map((field) => (
        <div key={field.key} className="space-y-1">
          <label htmlFor={`${id}-${field.key}`} className="block text-xs font-medium text-muted-foreground">
            {field.label}
          </label>
          <textarea
            id={`${id}-${field.key}`}
            required
            rows={field.rows}
            value={field.value}
            disabled={busy !== null}
            onChange={(event) => field.set(event.target.value)}
            className={FIELD_CLASS}
          />
        </div>
      ))}
      <p className="text-xs text-muted-foreground">
        Saved exactly as it will be filed. The draft as first written stays on record.
      </p>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" disabled={busy === "edit"} onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={busy !== null || !complete}>
          {busy === "edit" ? "Saving…" : "Save draft"}
        </Button>
      </div>
    </form>
  );
}

/** Replace Aegis's priority and due date, with a reason; Aegis's own assessment stays on record. */
function OverrideButton({
  ticket,
  busy,
  onOverride,
}: {
  ticket: RiskTicket;
  busy: Busy;
  onOverride: (input: OverrideInput) => Promise<boolean>;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [priority, setPriority] = useState<TicketPriority>(ticket.priority);
  const [dueDate, setDueDate] = useState(ticket.dueDate ?? "");
  const [reason, setReason] = useState("");
  const unchanged = priority === ticket.priority && (dueDate || null) === ticket.dueDate;

  function toggle(next: boolean) {
    if (next) {
      setPriority(ticket.priority);
      setDueDate(ticket.dueDate ?? "");
      setReason("");
    }
    setOpen(next);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!reason.trim() || unchanged) return;
    if (await onOverride({ priority, dueDate: dueDate || null, reason: reason.trim() })) {
      setOpen(false);
    }
  }

  return (
    <Popover open={open} onOpenChange={toggle}>
      <PopoverTrigger render={<Button variant="outline" size="sm" disabled={busy !== null} />}>
        {busy === "override" ? "Overriding…" : "Override priority"}
      </PopoverTrigger>
      <PopoverContent align="end" className="w-96">
        <form onSubmit={submit} className="space-y-3 text-sm">
          <p className="font-medium">Override Aegis&apos;s priority and due date</p>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label htmlFor={`${id}-priority`} className="block text-xs font-medium text-muted-foreground">
                Priority
              </label>
              <select
                id={`${id}-priority`}
                value={priority}
                disabled={busy !== null}
                onChange={(event) => setPriority(event.target.value as TicketPriority)}
                className={FIELD_CLASS}
              >
                {PRIORITIES.map((option) => (
                  <option key={option} value={option}>
                    {sentenceCase(option)}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-1">
              <label htmlFor={`${id}-due`} className="block text-xs font-medium text-muted-foreground">
                Due date (empty: none)
              </label>
              <input
                id={`${id}-due`}
                type="date"
                value={dueDate}
                disabled={busy !== null}
                onChange={(event) => setDueDate(event.target.value)}
                className={FIELD_CLASS}
              />
            </div>
          </div>
          <div className="space-y-1">
            <label htmlFor={`${id}-reason`} className="block text-xs font-medium text-muted-foreground">
              Why is Aegis&apos;s assessment wrong here?
            </label>
            <textarea
              id={`${id}-reason`}
              required
              rows={3}
              maxLength={MAX_REASON}
              value={reason}
              disabled={busy !== null}
              onChange={(event) => setReason(event.target.value)}
              placeholder="e.g. The vulnerable endpoint is switched off behind a feature flag."
              className={FIELD_CLASS}
            />
          </div>
          <p className="text-xs text-muted-foreground">
            The ticket is filed with yours; Aegis&apos;s assessment and your reason stay on record.
          </p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy !== null || !reason.trim() || unchanged}>
              Override
            </Button>
          </div>
        </form>
      </PopoverContent>
    </Popover>
  );
}

function TicketSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <h3 className="text-xs font-medium text-muted-foreground">{title}</h3>
      {children}
    </div>
  );
}

function sentenceCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}
