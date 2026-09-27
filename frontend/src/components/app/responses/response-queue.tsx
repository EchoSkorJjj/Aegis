"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

import { DataPagination } from "@/components/app/data-pagination";
import {
  MEMO_HISTORY_STATUSES,
  MEMO_KIND,
  MEMO_REVIEW_STATUSES,
  MemoStatusBadge,
  TICKET_HISTORY_STATUSES,
  TicketStatusBadge,
  UPGRADE_HISTORY_STATUSES,
  UPGRADE_REVIEW_STATUSES,
  UpgradeStatusBadge,
  formatDateTime,
} from "@/components/app/responses/response-status";
import { describeEdits } from "@/components/app/responses/upgrade-edits";
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
import { ApiError, memoApi, ticketApi, upgradeApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type { Page, RegulatorMemo, RiskTicket, UpgradeProposal } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** The review queue holds what still needs a person; history holds what was decided. */
export type QueueView = "review" | "history";

const PAGE_SIZE = 20;
const COLUMN_COUNT = 4;

const VIEW_COPY: Record<QueueView, { title: string; description: string }> = {
  review: {
    title: "Review & approve",
    description:
      "What Aegis recommends and what has been drafted, waiting for a person. Nothing leaves Aegis until " +
      "someone other than its drafter approves it.",
  },
  history: {
    title: "Response history",
    description: "Responses that were approved or dismissed, most recent first.",
  },
};

/**
 * A queue of drafted responses, each kind in its own section with its own paging: responses (a risk ticket
 * with the upgrade pull request that fixes it), regulator memos, and — only while there are any — upgrade pull
 * requests drafted on their own before responses existed. The review view leads with what Aegis recommends
 * responding to, found without anyone searching for a CVE.
 */
export function ResponseQueue({ view }: { view: QueueView }) {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);
  const copy = VIEW_COPY[view];
  const enabled = !authLoading && canRead;
  const orgId = user?.orgId ?? null;
  const userId = user?.id ?? null;
  const review = view === "review";

  return (
    <div className="mx-auto w-full max-w-6xl space-y-8">
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
          {review && <RecommendedUpgrades enabled={enabled} orgId={orgId} />}
          <QueueSection<RiskTicket>
            id="responses"
            title={review ? "Responses awaiting a decision" : "Responses"}
            description="Each one is a vulnerability's or outage's risk to one service: a risk ticket, with the upgrade pull request that fixes it where Aegis can automate one. Approving files the ticket in your organization's Jira, then opens the pull request as a draft."
            what="responses"
            enabled={enabled}
            reloadKey={`${orgId}:${view}`}
            load={(page) =>
              ticketApi.list(
                review
                  ? { pending: true, page, size: PAGE_SIZE }
                  : { status: TICKET_HISTORY_STATUSES, pending: false, page, size: PAGE_SIZE },
              )
            }
            columns={["Response", "Service", "Status", review ? "Drafted" : "Decided"]}
            empty={
              review
                ? "No response is waiting for a decision. Draft one from the recommended upgrades above, or from a change's impact page."
                : "No response has been filed or dismissed yet."
            }
            row={(ticket) => (
              <TicketRow key={ticket.id} ticket={ticket} view={view} draftedByYou={ticket.createdBy === userId} />
            )}
          />
          <QueueSection<RegulatorMemo>
            id="regulator-memos"
            title={review ? "Regulator memos awaiting a decision" : "Regulator memos"}
            description="DORA notifications about changes that materially affect a service. Approving makes one final for a person to submit; Aegis never sends it."
            what="regulator memos"
            enabled={enabled}
            reloadKey={`${orgId}:${view}`}
            load={(page) =>
              memoApi.list({
                status: review ? MEMO_REVIEW_STATUSES : MEMO_HISTORY_STATUSES,
                page,
                size: PAGE_SIZE,
              })
            }
            columns={["Notification", "Services affected", "Status", review ? "Drafted" : "Decided"]}
            empty={
              review
                ? "No regulator memo is waiting for a decision."
                : "No regulator memo has been approved or dismissed yet."
            }
            row={(memo) => (
              <MemoRow key={memo.id} memo={memo} view={view} draftedByYou={memo.createdBy === userId} />
            )}
          />
          <QueueSection<UpgradeProposal>
            id="upgrade-proposals"
            title={review ? "Upgrade pull requests awaiting a decision" : "Upgrade pull requests"}
            description="Drafted on their own, before responses existed: approving one opens its draft pull request. Aegis never merges it."
            what="upgrade proposals"
            enabled={enabled}
            reloadKey={`${orgId}:${view}`}
            load={(page) =>
              upgradeApi.list({
                status: review ? UPGRADE_REVIEW_STATUSES : UPGRADE_HISTORY_STATUSES,
                standalone: true,
                page,
                size: PAGE_SIZE,
              })
            }
            columns={["Upgrade", "Service", "Status", review ? "Drafted" : "Decided"]}
            empty=""
            hideWhenEmpty
            row={(proposal) => (
              <ProposalRow key={proposal.id} proposal={proposal} view={view} draftedByYou={proposal.createdBy === userId} />
            )}
          />
        </>
      )}
    </div>
  );
}

/** One kind of response, paged on its own. `reloadKey` changes when the section must start over. */
function QueueSection<T>({
  id,
  title,
  description,
  what,
  enabled,
  reloadKey,
  load,
  columns,
  empty,
  hideWhenEmpty = false,
  row,
}: {
  id: string;
  title: string;
  description: string;
  what: string;
  enabled: boolean;
  reloadKey: string;
  load: (page: number) => Promise<Page<T>>;
  columns: string[];
  empty: string;
  /** Leave the section out while there is nothing in it. */
  hideWhenEmpty?: boolean;
  row: (item: T) => React.ReactNode;
}) {
  const [pageIndex, setPageIndex] = useState(0);
  const [data, setData] = useState<Page<T> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "error" | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    load(pageIndex)
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
    // `load` is a new function every render; what it loads changes only with these.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, reloadKey, pageIndex]);

  if (hideWhenEmpty && !loading && !error && (data?.totalElements ?? 0) === 0) {
    return null;
  }

  return (
    <section aria-labelledby={`${id}-heading`} className="space-y-3">
      <div>
        <h2 id={`${id}-heading`} className="text-sm font-medium">
          {title}
          {data && data.totalElements > 0 && (
            <span className="ml-1.5 text-muted-foreground">({data.totalElements})</span>
          )}
        </h2>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((column, index) => (
                <TableHead key={column} className={index === columns.length - 1 ? "text-right" : undefined}>
                  {column}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 3 }).map((_, rowIndex) => (
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
              <MessageRow>Could not load {what}. Please try again.</MessageRow>
            ) : data && data.content.length > 0 ? (
              data.content.map(row)
            ) : (
              <MessageRow>{empty}</MessageRow>
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
          {describeEdits(proposal.edits)}
        </Link>
        <span className="mt-0.5 block font-mono text-xs text-muted-foreground">{proposal.vulnerabilityId}</span>
      </TableCell>
      <TableCell className="align-top">
        {proposal.serviceName ?? <span className="text-muted-foreground italic">Deleted service</span>}
      </TableCell>
      <TableCell className="max-w-xs align-top whitespace-normal">
        <UpgradeStatusBadge status={proposal.status} />
        {proposal.status === "OPENED" && proposal.pullRequestUrl && (
          <ExternalRowLink href={proposal.pullRequestUrl}>
            {proposal.pullRequestNumber ? `Pull request #${proposal.pullRequestNumber}` : "Pull request"}
          </ExternalRowLink>
        )}
        <StatusNotes note={note} review={view === "review"} draftedByYou={draftedByYou} />
      </TableCell>
      <DateCell view={view} createdAt={proposal.createdAt} decidedAt={proposal.decidedAt} />
    </TableRow>
  );
}

function TicketRow({ ticket, view, draftedByYou }: { ticket: RiskTicket; view: QueueView; draftedByYou: boolean }) {
  const note =
    ticket.status === "FAILED" ? ticket.failureMessage : ticket.status === "DISMISSED" ? ticket.dismissReason : null;
  const pullRequest = ticket.pullRequests.find((candidate) => candidate.status !== "DISMISSED");
  return (
    <TableRow>
      <TableCell className="max-w-md align-top whitespace-normal">
        <Link href={`/responses/tickets/${ticket.id}`} className="font-medium underline-offset-4 hover:underline">
          {ticket.title}
        </Link>
        <span className="mt-0.5 block text-xs text-muted-foreground">
          {ticket.priority.charAt(0)}
          {ticket.priority.slice(1).toLowerCase()} priority · impact {ticket.score.toFixed(1)}
        </span>
        {pullRequest && (
          <span className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
            <UpgradeStatusBadge status={pullRequest.status} />
            <span className="font-mono">{describeEdits(pullRequest.edits)}</span>
          </span>
        )}
      </TableCell>
      <TableCell className="align-top">
        {ticket.serviceName ?? <span className="text-muted-foreground italic">Deleted service</span>}
      </TableCell>
      <TableCell className="max-w-xs align-top whitespace-normal">
        <TicketStatusBadge status={ticket.status} />
        {ticket.status === "FILED" && ticket.issueUrl && (
          <ExternalRowLink href={ticket.issueUrl}>{ticket.issueKey ?? "Issue"}</ExternalRowLink>
        )}
        <StatusNotes note={note} review={view === "review"} draftedByYou={draftedByYou} />
      </TableCell>
      <DateCell view={view} createdAt={ticket.createdAt} decidedAt={ticket.decidedAt} />
    </TableRow>
  );
}

function MemoRow({ memo, view, draftedByYou }: { memo: RegulatorMemo; view: QueueView; draftedByYou: boolean }) {
  const material = memo.services.filter((service) => service.material);
  return (
    <TableRow>
      <TableCell className="max-w-md align-top whitespace-normal">
        <Link href={`/responses/memos/${memo.id}`} className="font-medium underline-offset-4 hover:underline">
          {MEMO_KIND[memo.kind].title}
        </Link>
        <span className="mt-0.5 block text-xs text-muted-foreground break-words">{memo.changeTitle}</span>
        <span className="mt-0.5 block font-mono text-xs text-muted-foreground">{memo.reference}</span>
      </TableCell>
      <TableCell className="max-w-xs align-top whitespace-normal">
        {material.map((service) => service.name).join(", ") || "—"}
      </TableCell>
      <TableCell className="max-w-xs align-top whitespace-normal">
        <MemoStatusBadge status={memo.status} />
        <StatusNotes
          note={memo.status === "DISMISSED" ? memo.dismissReason : null}
          review={view === "review"}
          draftedByYou={draftedByYou}
        />
      </TableCell>
      <DateCell view={view} createdAt={memo.createdAt} decidedAt={memo.decidedAt} />
    </TableRow>
  );
}

function ExternalRowLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="mt-1 block text-xs font-medium text-primary underline-offset-4 hover:underline"
    >
      {children}
    </a>
  );
}

function StatusNotes({ note, review, draftedByYou }: { note: string | null; review: boolean; draftedByYou: boolean }) {
  return (
    <>
      {note && <span className="mt-1 block text-xs text-muted-foreground line-clamp-2">{note}</span>}
      {review && draftedByYou && (
        <span className="mt-1 block text-xs text-muted-foreground">You drafted this; someone else approves it</span>
      )}
    </>
  );
}

function DateCell({ view, createdAt, decidedAt }: { view: QueueView; createdAt: string; decidedAt: string | null }) {
  return (
    <TableCell className="text-right align-top text-muted-foreground">
      {formatDateTime(view === "review" ? createdAt : (decidedAt ?? createdAt))}
    </TableCell>
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
