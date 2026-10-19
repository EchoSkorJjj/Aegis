"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ExternalLink, GitBranch } from "lucide-react";
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
import { UpgradeStatusBadge, formatDateTime } from "@/components/app/responses/response-status";
import { UpgradeEditsTable, describeEdits } from "@/components/app/responses/upgrade-edits";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, upgradeApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { UpgradeProposal } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** An approval interrupted mid-way may be retried after this long — aegis-core's STALE_OPENING. */
const STALE_OPENING_MS = 10 * 60 * 1000;

type LoadState = "loading" | "ok" | "forbidden" | "notfound" | "error";

/**
 * One safe-upgrade proposal: everything the approver needs to see — the upgrade, where it will be
 * written, and the exact pull-request title and body — and the decision itself. Approving opens a
 * draft pull request (Aegis never merges it); dismissing records why.
 */
export function UpgradeProposalView({ proposalId }: { proposalId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  const roles = user?.roles ?? [];
  const canRead = canReadInventory(roles);
  const userId = user?.id;

  // `fetchedAt` is when the proposal was read, so "is this approval stale?" is decided from data, not
  // from the clock during render.
  const [loaded, setLoaded] = useState<{ proposal: UpgradeProposal; fetchedAt: number } | null>(null);
  const [state, setState] = useState<LoadState>("loading");
  const [busy, setBusy] = useState<"approve" | "dismiss" | null>(null);
  const personName = useMemberNames(!authLoading && canRead, user?.orgId, userId);
  const canDecide = useServiceDecisionRight(roles, loaded?.proposal.serviceId, userId);

  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState("loading");
    upgradeApi
      .get(proposalId)
      .then((proposal) => {
        if (cancelled) return;
        setLoaded({ proposal, fetchedAt: Date.now() });
        setState("ok");
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 403) setState("forbidden");
        else if (err instanceof ApiError && (err.status === 404 || err.status === 400)) setState("notfound");
        else setState("error");
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, canRead, user?.orgId, proposalId]);

  async function reload() {
    try {
      const proposal = await upgradeApi.get(proposalId);
      setLoaded({ proposal, fetchedAt: Date.now() });
    } catch {
      // Keep what is on screen; the toast already said what went wrong.
    }
  }

  async function approve() {
    setBusy("approve");
    try {
      const proposal = await upgradeApi.approve(proposalId);
      setLoaded({ proposal, fetchedAt: Date.now() });
      toast.success(
        proposal.pullRequestNumber
          ? `Draft pull request #${proposal.pullRequestNumber} opened`
          : "Draft pull request opened",
      );
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not open the draft pull request.");
      // A failed opening is recorded on the proposal, and a conflict means someone decided first.
      await reload();
    } finally {
      setBusy(null);
    }
  }

  async function dismiss(reason: string): Promise<boolean> {
    setBusy("dismiss");
    try {
      const proposal = await upgradeApi.dismiss(proposalId, reason);
      setLoaded({ proposal, fetchedAt: Date.now() });
      toast.success("Proposal dismissed");
      return true;
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not dismiss the proposal.");
      await reload();
      return false;
    } finally {
      setBusy(null);
    }
  }

  const proposal = loaded?.proposal;
  const back = <BackToQueue decided={proposal?.status === "OPENED" || proposal?.status === "DISMISSED"} />;

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

  if (state !== "ok" || !loaded || !proposal) {
    return (
      <DetailShell back={back}>
        <StateCard>
          {state === "forbidden"
            ? "You don't have access to responses — an Aegis domain role is required."
            : state === "notfound"
              ? "This upgrade proposal doesn't exist in your organization."
              : "Could not load the upgrade proposal. Please try again."}
        </StateCard>
      </DetailShell>
    );
  }

  const stale =
    proposal.status === "OPENING" &&
    proposal.decidedAt != null &&
    Date.parse(proposal.decidedAt) < loaded.fetchedAt - STALE_OPENING_MS;

  return (
    <DetailShell back={back}>
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <UpgradeStatusBadge status={proposal.status} />
          <span className="font-mono text-sm text-muted-foreground">{proposal.vulnerabilityId}</span>
        </div>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight break-words">{heading(proposal)}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {describeEdits(proposal.edits)} for{" "}
          {proposal.serviceName ? (
            <Link
              href={`/inventory/services/${proposal.serviceId}`}
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              {proposal.serviceName}
            </Link>
          ) : (
            <span className="italic">a deleted service</span>
          )}
          . Drafted by {personName(proposal.createdBy)} on {formatDateTime(proposal.createdAt)}.
        </p>
      </div>

      {proposal.riskTicketId ? (
        <PartOfResponse proposal={proposal} ticketId={proposal.riskTicketId} personName={personName} />
      ) : (
        <Decision
          proposal={proposal}
          canDecide={canDecide}
          draftedByYou={proposal.createdBy === userId}
          auditor={roles.includes("COMPLIANCE_ADMIN")}
          stale={stale}
          busy={busy}
          personName={personName}
          onApprove={approve}
          onDismiss={dismiss}
        />
      )}

      <section aria-labelledby="proposal-facts" className="rounded-lg border">
        <h2 id="proposal-facts" className="border-b px-4 py-3 text-sm font-medium">
          What will change
        </h2>
        <div className="px-4 pt-4">
          <UpgradeEditsTable edits={proposal.edits} />
        </div>
        <dl className="grid gap-x-6 gap-y-3 p-4 text-sm sm:grid-cols-[max-content_1fr]">
          <Fact label="Repository">
            <a
              href={proposal.repositoryUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 break-all underline-offset-4 hover:underline"
            >
              {proposal.repositoryUrl}
              <ExternalLink className="size-3 shrink-0" />
            </a>
          </Fact>
          <Fact label="Branches">
            <span className="inline-flex flex-wrap items-center gap-1.5">
              <GitBranch className="size-3.5 text-muted-foreground" />
              <span className="font-mono break-all">{proposal.headBranch}</span>
              <span className="text-muted-foreground">into</span>
              <span className="font-mono">{proposal.baseBranch ?? "the default branch"}</span>
            </span>
          </Fact>
          <Fact label="Match">
            {proposal.matchConfidence === "CONFIRMED" ? (
              "Confirmed — the installed version is inside the advisory's affected range"
            ) : (
              <span className="text-amber-600 dark:text-amber-400">
                Needs review — the match could not be fully confirmed
              </span>
            )}
          </Fact>
          <Fact label="Change">
            <Link
              href={`/advisories/${proposal.changeSignalId}`}
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              View the impact of {proposal.vulnerabilityId} →
            </Link>
          </Fact>
        </dl>
      </section>

      <section aria-labelledby="proposal-preview" className="rounded-lg border">
        <div className="border-b px-4 py-3">
          <h2 id="proposal-preview" className="text-sm font-medium">
            Pull request
          </h2>
          <p className="text-xs text-muted-foreground">
            Exactly what will be opened, in Markdown. The AI-drafted rationale inside it is marked
            unverified; the versions and facts above come from deterministic matching.
            {proposal.riskTicketId && " It opens with its risk ticket's key in front of the title."}
          </p>
        </div>
        <div className="space-y-3 p-4">
          <p className="font-medium break-words">{proposal.title}</p>
          <pre className="max-h-[32rem] overflow-auto rounded-md bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
            {proposal.body}
          </pre>
        </div>
      </section>
    </DetailShell>
  );
}

/** What the page is about: the package, or the first of several. */
function heading(proposal: UpgradeProposal): string {
  const packages = [...new Set(proposal.edits.map((edit) => edit.packageName))];
  return packages.length <= 1
    ? `Upgrade ${packages[0] ?? "dependencies"}`
    : `Upgrade ${packages[0]} and ${packages.length - 1} more`;
}

/**
 * A response's pull request is decided on the response: approving it files the risk ticket and opens this
 * pull request under the ticket's key. Here, only where it stands.
 */
function PartOfResponse({
  proposal,
  ticketId,
  personName,
}: {
  proposal: UpgradeProposal;
  ticketId: string;
  personName: (id: string | null) => string;
}) {
  const decider = personName(proposal.decidedBy);
  const when = formatDateTime(proposal.decidedAt);
  const tone =
    proposal.status === "OPENED" ? "done" : proposal.status === "FAILED" ? "failed" : proposal.status === "DISMISSED"
      ? "declined" : proposal.status === "OPENING" ? "working" : "waiting";
  return (
    <DecisionPanel tone={tone} title="Part of a response">
      {proposal.status === "OPENED" ? (
        <p>
          Opened by {decider} on {when}.{" "}
          {proposal.pullRequestUrl && (
            <a
              href={proposal.pullRequestUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline"
            >
              {proposal.pullRequestNumber ? `Pull request #${proposal.pullRequestNumber}` : "Open the pull request"}
              <ExternalLink className="size-3" />
            </a>
          )}
        </p>
      ) : proposal.status === "DISMISSED" ? (
        <p>
          Left out by {decider} on {when}
          {proposal.dismissReason ? `: ${proposal.dismissReason}` : "."}
        </p>
      ) : proposal.status === "FAILED" ? (
        <p>
          Approved by {decider} on {when}, but it could not be opened
          {proposal.failureMessage ? `: ${proposal.failureMessage}` : "."}
        </p>
      ) : (
        <p className="text-muted-foreground">
          It is decided with its risk ticket: approving the response files the ticket, then opens this pull request
          under the ticket&apos;s key.
        </p>
      )}
      <Link
        href={`/responses/tickets/${ticketId}`}
        className="inline-block font-medium text-primary underline-offset-4 hover:underline"
      >
        View the response →
      </Link>
    </DecisionPanel>
  );
}

function Decision({
  proposal,
  canDecide,
  draftedByYou,
  auditor,
  stale,
  busy,
  personName,
  onApprove,
  onDismiss,
}: {
  proposal: UpgradeProposal;
  /** "checking" while a service owner's ownership of this service is being confirmed. */
  canDecide: boolean | "checking";
  /** Whoever drafted a proposal may dismiss it, but someone else approves it. */
  draftedByYou: boolean;
  /** A compliance admin audits these decisions rather than making them. */
  auditor: boolean;
  stale: boolean;
  busy: "approve" | "dismiss" | null;
  personName: (id: string | null) => string;
  onApprove: () => void;
  onDismiss: (reason: string) => Promise<boolean>;
}) {
  const decider = personName(proposal.decidedBy);
  const when = formatDateTime(proposal.decidedAt);

  if (proposal.status === "OPENED") {
    return (
      <DecisionPanel tone="done" title="Draft pull request opened">
        <p>
          Approved by {decider} on {when}.{" "}
          {proposal.pullRequestUrl && (
            <a
              href={proposal.pullRequestUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-foreground underline-offset-4 hover:underline"
            >
              {proposal.pullRequestNumber ? `Pull request #${proposal.pullRequestNumber}` : "Open the pull request"}
              <ExternalLink className="size-3" />
            </a>
          )}
        </p>
        <p className="text-muted-foreground">
          Aegis never merges. Review, test and merge it in the repository like any other change.
        </p>
      </DecisionPanel>
    );
  }

  if (proposal.status === "DISMISSED") {
    return <DismissedPanel decider={decider} when={when} reason={proposal.dismissReason} />;
  }

  const canDismiss = proposal.status !== "OPENING";
  const actions = canDecide === "checking" ? null : canDecide ? (
    <>
      {draftedByYou && (
        <p className="text-muted-foreground">
          You drafted this, so someone else approves it: an analyst or one of this service&apos;s owners.
        </p>
      )}
      {(!draftedByYou || canDismiss) && (
        <div className="flex flex-wrap items-center gap-2 pt-1">
          {!draftedByYou && (
            <ConfirmButton
              label={proposal.status !== "PROPOSED" ? "Try again" : "Approve"}
              busyLabel="Opening draft PR…"
              busy={busy === "approve"}
              disabled={busy !== null}
              question="Open a draft pull request?"
              confirmLabel="Open draft PR"
              onConfirm={onApprove}
            >
              Aegis re-reads each manifest from{" "}
              <span className="font-mono text-foreground">{proposal.baseBranch ?? "the default branch"}</span>,
              applies the upgrade, commits it to{" "}
              <span className="font-mono break-all text-foreground">{proposal.headBranch}</span>, and opens a{" "}
              <span className="font-medium text-foreground">draft</span> pull request. It never merges.
            </ConfirmButton>
          )}
          {canDismiss && (
            <DismissButton
              busy={busy === "dismiss"}
              disabled={busy !== null}
              question="Why is this upgrade not being made?"
              placeholder="e.g. The vulnerable code path isn't reachable in this service; upgrading with the next release."
              what="proposal"
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

  if (proposal.status === "FAILED") {
    return (
      <DecisionPanel tone="failed" title="Opening the draft pull request failed">
        <p>
          Approved by {decider} on {when}, but the pull request could not be opened
          {proposal.failureMessage ? ":" : "."}
        </p>
        {proposal.failureMessage && (
          <p className="rounded-md bg-background/60 px-3 py-2 font-mono text-xs break-words">
            {proposal.failureMessage}
          </p>
        )}
        <p className="text-muted-foreground">Fix the cause and try again, or dismiss the proposal.</p>
        {actions}
      </DecisionPanel>
    );
  }

  if (proposal.status === "OPENING") {
    return (
      <DecisionPanel tone="working" title={stale ? "The approval was interrupted" : "Opening the draft pull request"}>
        <p>
          Approved by {decider} on {when}.{" "}
          {stale
            ? "The pull request was never confirmed as opened, so the approval can be retried."
            : "Aegis is opening the pull request — refresh in a moment."}
        </p>
        {stale && actions}
      </DecisionPanel>
    );
  }

  return (
    <DecisionPanel tone="waiting" title="Awaiting a decision">
      <p className="text-muted-foreground">
        Nothing has been written to the repository. Approving opens a draft pull request; dismissing
        records why this upgrade isn&apos;t being made.
      </p>
      {actions}
    </DecisionPanel>
  );
}
