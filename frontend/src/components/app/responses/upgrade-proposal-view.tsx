"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ExternalLink, GitBranch } from "lucide-react";
import { toast } from "sonner";

import {
  UpgradeStatusBadge,
  formatDateTime,
  type ResponseTone,
} from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, directoryApi, inventoryApi, upgradeApi } from "@/lib/api";
import { canReadInventory, canRespondForService, respondsForAnyService } from "@/lib/roles";
import type { UpgradeProposal } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** An approval interrupted mid-way may be retried after this long — aegis-core's STALE_OPENING. */
const STALE_OPENING_MS = 10 * 60 * 1000;
const MAX_REASON = 1000;

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
  // Null until known: an owner's controls wait for the check rather than flash "you can't decide".
  const [isAssignedOwner, setIsAssignedOwner] = useState<boolean | null>(null);
  const [people, setPeople] = useState<Map<string, string>>(new Map());
  const [busy, setBusy] = useState<"approve" | "dismiss" | null>(null);

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

  // Names for "drafted by" / "decided by": the org's member directory (readable by every member).
  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
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
  }, [authLoading, canRead, user?.orgId]);

  // A service owner who isn't also an analyst may decide only for a service they own.
  const needsOwnerCheck = roles.includes("SERVICE_OWNER") && !respondsForAnyService(roles);
  const serviceId = loaded?.proposal.serviceId;
  useEffect(() => {
    if (!needsOwnerCheck || !serviceId || !userId) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setIsAssignedOwner(null);
    inventoryApi
      .listServiceOwners(serviceId, { size: 100 })
      .then((page) => {
        if (!cancelled) setIsAssignedOwner(page.content.some((owner) => owner.userId === userId));
      })
      .catch(() => {
        if (!cancelled) setIsAssignedOwner(false);
      });
    return () => {
      cancelled = true;
    };
  }, [needsOwnerCheck, serviceId, userId]);

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

  function personName(id: string | null): string {
    if (!id) return "—";
    if (id === userId) return "you";
    return people.get(id) ?? "a member";
  }

  const proposal = loaded?.proposal;
  const decided = proposal?.status === "OPENED" || proposal?.status === "DISMISSED";
  const back = (
    <Link
      href={decided ? "/responses/history" : "/responses/review"}
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      {decided ? "Response history" : "Review & approve"}
    </Link>
  );

  if (!authLoading && !canRead) {
    return (
      <Shell back={back}>
        <StateCard>You don&apos;t have access to responses — an Aegis domain role is required.</StateCard>
      </Shell>
    );
  }

  if (state === "loading") {
    return (
      <Shell back={back}>
        <Skeleton className="h-8 w-96" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </Shell>
    );
  }

  if (state !== "ok" || !loaded || !proposal) {
    return (
      <Shell back={back}>
        <StateCard>
          {state === "forbidden"
            ? "You don't have access to responses — an Aegis domain role is required."
            : state === "notfound"
              ? "This upgrade proposal doesn't exist in your organization."
              : "Could not load the upgrade proposal. Please try again."}
        </StateCard>
      </Shell>
    );
  }

  const canDecide = canRespondForService(roles, isAssignedOwner === true);
  const deciding = needsOwnerCheck && isAssignedOwner === null ? "checking" : canDecide;
  const stale =
    proposal.status === "OPENING" &&
    proposal.decidedAt != null &&
    Date.parse(proposal.decidedAt) < loaded.fetchedAt - STALE_OPENING_MS;

  return (
    <Shell back={back}>
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <UpgradeStatusBadge status={proposal.status} />
          <span className="font-mono text-sm text-muted-foreground">
            {proposal.vulnerabilityId} · {proposal.ecosystem}
          </span>
        </div>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight break-words">Upgrade {proposal.packageName}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          <span className="font-mono">{proposal.fromVersion}</span> →{" "}
          <span className="font-mono font-medium text-foreground">{proposal.toVersion}</span> for{" "}
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

      <Decision
        proposal={proposal}
        canDecide={deciding}
        draftedByYou={proposal.createdBy === userId}
        auditor={roles.includes("COMPLIANCE_ADMIN")}
        stale={stale}
        busy={busy}
        personName={personName}
        onApprove={approve}
        onDismiss={dismiss}
      />

      <section aria-labelledby="proposal-facts" className="rounded-lg border">
        <h2 id="proposal-facts" className="border-b px-4 py-3 text-sm font-medium">
          What will change
        </h2>
        <dl className="grid gap-x-6 gap-y-3 p-4 text-sm sm:grid-cols-[max-content_1fr]">
          <Fact label="Package">
            <span className="font-mono break-all">{proposal.packageName}</span>
          </Fact>
          <Fact label="Version">
            <span className="font-mono">
              {proposal.fromVersion} → {proposal.toVersion}
            </span>
          </Fact>
          <Fact label="Manifest">
            <span className="font-mono break-all">{proposal.manifestPath}</span>
          </Fact>
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
          </p>
        </div>
        <div className="space-y-3 p-4">
          <p className="font-medium break-words">{proposal.title}</p>
          <pre className="max-h-[32rem] overflow-auto rounded-md bg-muted/40 p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
            {proposal.body}
          </pre>
        </div>
      </section>
    </Shell>
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
      <Panel tone="done" title="Draft pull request opened">
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
      </Panel>
    );
  }

  if (proposal.status === "DISMISSED") {
    return (
      <Panel tone="declined" title="Dismissed">
        <p>
          Dismissed by {decider} on {when}.
        </p>
        {proposal.dismissReason && (
          <blockquote className="border-l-2 pl-3 whitespace-pre-wrap text-muted-foreground">
            {proposal.dismissReason}
          </blockquote>
        )}
      </Panel>
    );
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
            <ApproveButton
              proposal={proposal}
              busy={busy}
              retry={proposal.status !== "PROPOSED"}
              onApprove={onApprove}
            />
          )}
          {canDismiss && <DismissButton busy={busy} onDismiss={onDismiss} />}
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
      <Panel tone="failed" title="Opening the draft pull request failed">
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
      </Panel>
    );
  }

  if (proposal.status === "OPENING") {
    return (
      <Panel tone="working" title={stale ? "The approval was interrupted" : "Opening the draft pull request"}>
        <p>
          Approved by {decider} on {when}.{" "}
          {stale
            ? "The pull request was never confirmed as opened, so the approval can be retried."
            : "Aegis is opening the pull request — refresh in a moment."}
        </p>
        {stale && actions}
      </Panel>
    );
  }

  return (
    <Panel tone="waiting" title="Awaiting a decision">
      <p className="text-muted-foreground">
        Nothing has been written to the repository. Approving opens a draft pull request; dismissing
        records why this upgrade isn&apos;t being made.
      </p>
      {actions}
    </Panel>
  );
}

function ApproveButton({
  proposal,
  busy,
  retry,
  onApprove,
}: {
  proposal: UpgradeProposal;
  busy: "approve" | "dismiss" | null;
  retry: boolean;
  onApprove: () => void;
}) {
  const [open, setOpen] = useState(false);
  const label = retry ? "Try again" : "Approve";
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button disabled={busy !== null} />}>
        {busy === "approve" ? "Opening draft PR…" : label}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96 space-y-3 text-sm">
        <p className="font-medium">Open a draft pull request?</p>
        <p className="text-muted-foreground">
          Aegis re-reads <span className="font-mono text-foreground">{proposal.manifestPath}</span> from{" "}
          <span className="font-mono text-foreground">{proposal.baseBranch ?? "the default branch"}</span>, applies
          the upgrade to it, commits that to{" "}
          <span className="font-mono break-all text-foreground">{proposal.headBranch}</span>, and opens a{" "}
          <span className="font-medium text-foreground">draft</span> pull request. It never merges.
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            onClick={() => {
              setOpen(false);
              onApprove();
            }}
          >
            Open draft PR
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

function DismissButton({
  busy,
  onDismiss,
}: {
  busy: "approve" | "dismiss" | null;
  onDismiss: (reason: string) => Promise<boolean>;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!reason.trim()) return;
    if (await onDismiss(reason.trim())) {
      setOpen(false);
      setReason("");
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button variant="outline" disabled={busy !== null} />}>
        {busy === "dismiss" ? "Dismissing…" : "Dismiss"}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96">
        <form onSubmit={submit} className="space-y-3 text-sm">
          <label htmlFor={`${id}-reason`} className="block font-medium">
            Why is this upgrade not being made?
          </label>
          <textarea
            id={`${id}-reason`}
            required
            autoFocus
            rows={4}
            maxLength={MAX_REASON}
            value={reason}
            disabled={busy !== null}
            onChange={(event) => setReason(event.target.value)}
            placeholder="e.g. The vulnerable code path isn't reachable in this service; upgrading with the next release."
            className="w-full rounded-md border border-input bg-transparent px-2.5 py-2 text-sm shadow-xs outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          />
          <p className="text-xs text-muted-foreground">Recorded on the proposal for whoever looks at it next.</p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={busy !== null || !reason.trim()}>
              Dismiss proposal
            </Button>
          </div>
        </form>
      </PopoverContent>
    </Popover>
  );
}

const PANEL_CLASS: Record<ResponseTone, string> = {
  waiting: "border-amber-500/30 bg-amber-500/5",
  working: "border-sky-500/30 bg-sky-500/5",
  done: "border-emerald-500/30 bg-emerald-500/5",
  failed: "border-red-500/30 bg-red-500/5",
  declined: "bg-muted/30",
};

function Panel({
  tone,
  title,
  children,
}: {
  tone: ResponseTone;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-label="Decision" className={`space-y-2 rounded-lg border p-4 text-sm ${PANEL_CLASS[tone]}`}>
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </>
  );
}

function Shell({ back, children }: { back: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      {back}
      {children}
    </div>
  );
}

function StateCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
