"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

import { DataPagination } from "@/components/app/data-pagination";
import {
  UPGRADE_HISTORY_STATUSES,
  UPGRADE_REVIEW_STATUSES,
  UpgradeStatusBadge,
  formatDateTime,
} from "@/components/app/responses/response-status";
import { RecommendedUpgrades } from "@/components/app/responses/upgrade-recommendations";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ApiError, upgradeApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { Page, UpgradeProposal } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** The review queue holds what still needs a person; history holds what was decided. */
export type QueueView = "review" | "history";

const PAGE_SIZE = 20;
const COLUMN_COUNT = 4;

const VIEW_COPY: Record<QueueView, { title: string; description: string }> = {
  review: {
    title: "Review & approve",
    description:
      "What Aegis recommends and what has been drafted, waiting for a person. Nothing reaches a repository " +
      "until someone approves it.",
  },
  history: {
    title: "Response history",
    description: "Responses that were approved or dismissed, most recent first.",
  },
};

/**
 * A queue of drafted responses. Each kind of response is its own section with its own paging: the
 * upgrade pull requests today; risk tickets (story 5) and notification memos (story 6) add theirs here.
 * The review view leads with what Aegis recommends drafting, found without anyone searching for a CVE.
 */
export function ResponseQueue({ view }: { view: QueueView }) {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);
  const copy = VIEW_COPY[view];

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{copy.title}</h1>
        <p className="text-sm text-muted-foreground">{copy.description}</p>
      </div>
      {!authLoading && !canRead ? (
        <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
          You don&apos;t have access to responses — an Aegis domain role is required.
        </div>
      ) : (
        <>
          {view === "review" && (
            <RecommendedUpgrades enabled={!authLoading && canRead} orgId={user?.orgId ?? null} />
          )}
          <UpgradeProposalSection
            view={view}
            enabled={!authLoading && canRead}
            orgId={user?.orgId ?? null}
            userId={user?.id ?? null}
          />
        </>
      )}
    </div>
  );
}

function UpgradeProposalSection({
  view,
  enabled,
  orgId,
  userId,
}: {
  view: QueueView;
  enabled: boolean;
  orgId: string | null;
  userId: string | null;
}) {
  const [pageIndex, setPageIndex] = useState(0);
  const [data, setData] = useState<Page<UpgradeProposal> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    upgradeApi
      .list({
        status: view === "review" ? UPGRADE_REVIEW_STATUSES : UPGRADE_HISTORY_STATUSES,
        page: pageIndex,
        size: PAGE_SIZE,
      })
      .then((page) => {
        if (!cancelled) setData(page);
      })
      .catch((err) => {
        if (cancelled) return;
        setData(null);
        setError(err instanceof ApiError && err.status === 403 ? "forbidden" : "error");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, orgId, view, pageIndex]);

  return (
    <section aria-labelledby="upgrade-proposals-heading" className="space-y-3">
      <div>
        <h2 id="upgrade-proposals-heading" className="text-sm font-medium">
          {view === "review" ? "Drafted, awaiting a decision" : "Upgrade pull requests"}
          {data && data.totalElements > 0 && (
            <span className="ml-1.5 text-muted-foreground">({data.totalElements})</span>
          )}
        </h2>
        <p className="text-xs text-muted-foreground">
          Each one moves a vulnerable dependency to the lowest version clear of its advisories. Approving
          opens a draft pull request; Aegis never merges it.
        </p>
      </div>

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Upgrade</TableHead>
              <TableHead>Service</TableHead>
              <TableHead>Status</TableHead>
              <TableHead className="text-right">{view === "review" ? "Drafted" : "Decided"}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 4 }).map((_, rowIndex) => (
                <TableRow key={rowIndex}>
                  {Array.from({ length: COLUMN_COUNT }).map((__, cellIndex) => (
                    <TableCell key={cellIndex}>
                      <Skeleton className="h-4 w-24" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : error === "forbidden" ? (
              <MessageRow>You don&apos;t have access to responses — an Aegis domain role is required.</MessageRow>
            ) : error ? (
              <MessageRow>Could not load upgrade proposals. Please try again.</MessageRow>
            ) : data && data.content.length > 0 ? (
              data.content.map((proposal) => (
                <ProposalRow
                  key={proposal.id}
                  proposal={proposal}
                  view={view}
                  draftedByYou={proposal.createdBy === userId}
                />
              ))
            ) : (
              <MessageRow>
                {view === "review"
                  ? "Nothing drafted is waiting for a decision. Draft one of the recommended upgrades above."
                  : "No upgrade proposal has been approved or dismissed yet."}
              </MessageRow>
            )}
          </TableBody>
        </Table>
      </div>

      {data && data.totalElements > 0 && (
        <DataPagination
          page={data.number}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          pageSize={data.size}
          itemsOnPage={data.content.length}
          onPageChange={setPageIndex}
        />
      )}
    </section>
  );
}

function ProposalRow({
  proposal,
  view,
  draftedByYou,
}: {
  proposal: UpgradeProposal;
  view: QueueView;
  draftedByYou: boolean;
}) {
  const note =
    proposal.status === "FAILED"
      ? proposal.failureMessage
      : proposal.status === "DISMISSED"
        ? proposal.dismissReason
        : null;
  return (
    <TableRow>
      <TableCell className="max-w-md align-top whitespace-normal">
        <Link
          href={`/responses/upgrades/${proposal.id}`}
          className="font-medium underline-offset-4 hover:underline"
        >
          {proposal.packageName}
        </Link>
        <span className="mt-0.5 block font-mono text-xs text-muted-foreground">
          {proposal.fromVersion} → <span className="text-foreground">{proposal.toVersion}</span>
        </span>
        <span className="mt-0.5 block font-mono text-xs text-muted-foreground">
          {proposal.vulnerabilityId} · {proposal.ecosystem}
        </span>
      </TableCell>
      <TableCell className="align-top">
        {proposal.serviceName ?? <span className="text-muted-foreground italic">Deleted service</span>}
      </TableCell>
      <TableCell className="max-w-xs align-top whitespace-normal">
        <UpgradeStatusBadge status={proposal.status} />
        {proposal.status === "OPENED" && proposal.pullRequestUrl && (
          <a
            href={proposal.pullRequestUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-1 block text-xs font-medium text-primary underline-offset-4 hover:underline"
          >
            {proposal.pullRequestNumber ? `Pull request #${proposal.pullRequestNumber}` : "Pull request"}
          </a>
        )}
        {note && <span className="mt-1 block text-xs text-muted-foreground line-clamp-2">{note}</span>}
        {view === "review" && draftedByYou && (
          <span className="mt-1 block text-xs text-muted-foreground">You drafted this; someone else approves it</span>
        )}
      </TableCell>
      <TableCell className="text-right align-top text-muted-foreground">
        {formatDateTime(view === "review" ? proposal.createdAt : (proposal.decidedAt ?? proposal.createdAt))}
      </TableCell>
    </TableRow>
  );
}

function MessageRow({ children }: { children: React.ReactNode }) {
  return (
    <TableRow>
      <TableCell colSpan={COLUMN_COUNT} className="py-10 text-center whitespace-normal text-muted-foreground">
        {children}
      </TableCell>
    </TableRow>
  );
}
