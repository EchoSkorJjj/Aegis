"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Download } from "lucide-react";
import { toast } from "sonner";

import {
  BackToQueue,
  ConfirmButton,
  DecisionPanel,
  DetailShell,
  DismissButton,
  DismissedPanel,
  StateCard,
  useMemberNames,
} from "@/components/app/responses/response-parts";
import { MEMO_KIND, MemoStatusBadge, formatDateTime } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ApiError, memoApi } from "@/lib/api";
import { canReadInventory, respondsForAnyService } from "@/lib/roles";
import type { RegulatorMemo } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

/** DORA: the initial notification of a major incident is due no later than 24 hours after detection. */
const INITIAL_NOTIFICATION_MS = 24 * 60 * 60 * 1000;

type LoadState = "loading" | "ok" | "forbidden" | "notfound" | "error";

/**
 * One DORA notification Aegis drafted: the timeline, the services it affects, and the whole memo as it will
 * be downloaded. A second analyst approves it — making it final, ready for a person to complete and submit
 * through the competent authority's channel — or dismisses it with a reason. Aegis never sends it.
 */
export function RegulatorMemoView({ memoId }: { memoId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  const roles = user?.roles ?? [];
  const canRead = canReadInventory(roles);
  const userId = user?.id;

  const [memo, setMemo] = useState<RegulatorMemo | null>(null);
  const [state, setState] = useState<LoadState>("loading");
  const [busy, setBusy] = useState<"approve" | "dismiss" | null>(null);
  const personName = useMemberNames(!authLoading && canRead, user?.orgId, userId);

  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState("loading");
    memoApi
      .get(memoId)
      .then((loaded) => {
        if (cancelled) return;
        setMemo(loaded);
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
  }, [authLoading, canRead, user?.orgId, memoId]);

  async function reload() {
    try {
      setMemo(await memoApi.get(memoId));
    } catch {
      // Keep what is on screen; the toast already said what went wrong.
    }
  }

  async function approve() {
    setBusy("approve");
    try {
      setMemo(await memoApi.approve(memoId));
      toast.success("Memo approved — it is final and ready to submit");
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not approve the memo.");
      await reload();
    } finally {
      setBusy(null);
    }
  }

  async function dismiss(reason: string): Promise<boolean> {
    setBusy("dismiss");
    try {
      setMemo(await memoApi.dismiss(memoId, reason));
      toast.success("Memo dismissed");
      return true;
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not dismiss the memo.");
      await reload();
      return false;
    } finally {
      setBusy(null);
    }
  }

  const back = <BackToQueue decided={memo != null && memo.status !== "PROPOSED"} />;

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

  if (state !== "ok" || !memo) {
    return (
      <DetailShell back={back}>
        <StateCard>
          {state === "forbidden"
            ? "You don't have access to responses — an Aegis domain role is required."
            : state === "notfound"
              ? "This regulator memo doesn't exist in your organization."
              : "Could not load the regulator memo. Please try again."}
        </StateCard>
      </DetailShell>
    );
  }

  const kind = MEMO_KIND[memo.kind];
  return (
    <DetailShell back={back}>
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <MemoStatusBadge status={memo.status} />
          <span className="font-mono text-sm text-muted-foreground">{memo.reference}</span>
        </div>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight">{kind.title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          A draft of {kind.article} about{" "}
          <Link
            href={`/advisories/${memo.changeSignalId}`}
            className="font-medium text-foreground underline-offset-4 hover:underline"
          >
            {memo.changeTitle}
          </Link>
          . Drafted by {personName(memo.createdBy)} on {formatDateTime(memo.createdAt)}.
        </p>
      </div>

      <Decision
        memo={memo}
        canDecide={respondsForAnyService(roles)}
        draftedByYou={memo.createdBy === userId}
        auditor={roles.includes("COMPLIANCE_ADMIN")}
        busy={busy}
        personName={personName}
        onApprove={approve}
        onDismiss={dismiss}
      />

      <Timeline memo={memo} />

      <section aria-labelledby="memo-services" className="rounded-lg border">
        <div className="border-b px-4 py-3">
          <h2 id="memo-services" className="text-sm font-medium">
            Affected services
          </h2>
          <p className="text-xs text-muted-foreground">As Aegis scored them when the memo was drafted.</p>
        </div>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Service</TableHead>
              <TableHead>Criticality</TableHead>
              <TableHead>Exposure</TableHead>
              <TableHead className="text-right">Impact</TableHead>
              <TableHead>Material</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {memo.services.map((service) => (
              <TableRow key={service.serviceId}>
                <TableCell>
                  <Link
                    href={`/inventory/services/${service.serviceId}`}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {service.name}
                  </Link>
                </TableCell>
                <TableCell>{service.criticality}</TableCell>
                <TableCell>{service.exposure ?? "Unknown"}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {service.score.toFixed(1)} <span className="text-muted-foreground">({service.tier})</span>
                </TableCell>
                <TableCell>{service.material ? "Yes" : "No"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </section>

      <section aria-labelledby="memo-body" className="rounded-lg border">
        <div className="border-b px-4 py-3">
          <h2 id="memo-body" className="text-sm font-medium">
            Notification
          </h2>
          <p className="text-xs text-muted-foreground">
            {memo.aiDrafted
              ? "The whole memo in Markdown, as it downloads. The description, impact, measures and next steps are AI-drafted and unverified; the reference, timeline, services and evidence are Aegis's own."
              : "The whole memo in Markdown, as it downloads. The AI couldn't draft this one, so it holds only Aegis's own facts."}{" "}
            Everything marked &ldquo;[To be confirmed]&rdquo; is for a person to complete.
          </p>
        </div>
        <pre className="max-h-[40rem] overflow-auto p-4 font-mono text-xs leading-relaxed whitespace-pre-wrap break-words">
          {memo.body}
        </pre>
        {memo.citations.length > 0 && (
          <div className="space-y-1 border-t px-4 py-3">
            <h3 className="text-xs font-medium text-muted-foreground">Sources the AI cited</h3>
            <ul className="space-y-1 font-mono text-xs text-muted-foreground">
              {memo.citations.map((citation, index) => (
                <li key={index} className="break-words">
                  {citation}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
    </DetailShell>
  );
}

function Decision({
  memo,
  canDecide,
  draftedByYou,
  auditor,
  busy,
  personName,
  onApprove,
  onDismiss,
}: {
  memo: RegulatorMemo;
  /** Only an analyst decides a memo: it covers every service the change affects. */
  canDecide: boolean;
  /** Whoever drafted a memo may dismiss it, but another analyst approves it. */
  draftedByYou: boolean;
  /** A compliance admin audits these decisions rather than making them. */
  auditor: boolean;
  busy: "approve" | "dismiss" | null;
  personName: (id: string | null) => string;
  onApprove: () => void;
  onDismiss: (reason: string) => Promise<boolean>;
}) {
  const decider = personName(memo.decidedBy);
  const when = formatDateTime(memo.decidedAt);

  if (memo.status === "APPROVED") {
    return (
      <DecisionPanel tone="done" title="Final — ready to submit">
        <p>Approved by {decider} on {when}.</p>
        <p className="text-muted-foreground">
          Aegis never sends it. Download it, complete everything marked &ldquo;[To be confirmed]&rdquo;, and submit
          it through your competent authority&apos;s reporting channel.
        </p>
        <div className="pt-1">
          <Button variant="outline" onClick={() => download(memo)}>
            <Download />
            Download memo (.md)
          </Button>
        </div>
      </DecisionPanel>
    );
  }

  if (memo.status === "DISMISSED") {
    return <DismissedPanel decider={decider} when={when} reason={memo.dismissReason} />;
  }

  return (
    <DecisionPanel tone="waiting" title="Awaiting a decision">
      <p className="text-muted-foreground">
        Nothing is sent, now or after approval. Approving makes this memo final for a person to complete and
        submit; dismissing records why no notification is needed.
      </p>
      {canDecide ? (
        <>
          {draftedByYou && (
            <p className="text-muted-foreground">You drafted this, so another analyst approves it.</p>
          )}
          <div className="flex flex-wrap items-center gap-2 pt-1">
            {!draftedByYou && (
              <ConfirmButton
                label="Approve"
                busyLabel="Approving…"
                busy={busy === "approve"}
                disabled={busy !== null}
                question="Make this memo final?"
                confirmLabel="Make final"
                onConfirm={onApprove}
              >
                It is locked as approved and ready to download. Aegis never sends it: a person completes each
                &ldquo;[To be confirmed]&rdquo; and submits it to the competent authority.
              </ConfirmButton>
            )}
            <DismissButton
              busy={busy === "dismiss"}
              disabled={busy !== null}
              question="Why is no notification needed?"
              placeholder="e.g. Below the major-incident thresholds; recorded in the incident register."
              what="memo"
              onDismiss={onDismiss}
            />
          </div>
        </>
      ) : (
        <p className="text-muted-foreground">
          {auditor && "You audit these decisions rather than make them. "}
          An analyst approves or dismisses it.
        </p>
      )}
    </DecisionPanel>
  );
}

/** When the organization became aware, and — for a major incident — when the initial notification is due. */
function Timeline({ memo }: { memo: RegulatorMemo }) {
  const detected = memo.detectedAt ? Date.parse(memo.detectedAt) : Number.NaN;
  const known = !Number.isNaN(detected);
  return (
    <section aria-label="Timeline" className="rounded-lg border bg-muted/30 px-4 py-3 text-sm">
      {memo.kind === "MAJOR_INCIDENT" ? (
        <p>
          <span className="text-muted-foreground">Detected</span>{" "}
          <span className="font-medium">{known ? formatDateTime(memo.detectedAt) : "at an unknown time"}</span>
          {known && (
            <>
              <span className="text-muted-foreground">. If it is classified as major, the initial notification is due within 4 hours of classifying it, and no later than</span>{" "}
              <span className="font-medium">{formatDateTime(new Date(detected + INITIAL_NOTIFICATION_MS).toISOString())}</span>
              <span className="text-muted-foreground">.</span>
            </>
          )}
        </p>
      ) : (
        <p>
          <span className="text-muted-foreground">Published</span>{" "}
          <span className="font-medium">{known ? formatDateTime(memo.detectedAt) : "at an unknown time"}</span>
          <span className="text-muted-foreground">. Notifying a significant cyber threat is voluntary.</span>
        </p>
      )}
    </section>
  );
}

/** Save the memo as Markdown, named after its reference. */
function download(memo: RegulatorMemo) {
  const url = URL.createObjectURL(new Blob([memo.body], { type: "text/markdown;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `${memo.reference}.md`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
