"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Landmark } from "lucide-react";
import { toast } from "sonner";

import { MEMO_KIND, MemoStatusBadge } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { ApiError, memoApi } from "@/lib/api";
import type { MemoKind, RegulatorMemo } from "@/lib/types";

/** The memo drafted for one change and not dismissed: undefined while loading, null when there is none. */
export function useLiveMemo(changeSignalId: string, enabled: boolean): RegulatorMemo | null | undefined {
  const [memo, setMemo] = useState<RegulatorMemo | null | undefined>(undefined);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMemo(undefined);
    memoApi
      .list({ changeSignalId, status: ["PROPOSED", "APPROVED"], size: 1 })
      .then((page) => {
        if (!cancelled) setMemo(page.content[0] ?? null);
      })
      .catch(() => {
        // Offer drafting anyway; the backend refuses a duplicate with a link to the live memo.
        if (!cancelled) setMemo(null);
      });
    return () => {
      cancelled = true;
    };
  }, [changeSignalId, enabled]);

  return memo;
}

/**
 * The impact page's regulator-notification panel, shown when the change materially affects a service: the
 * memo already drafted for it, or — for an analyst — the draft button. A vendor outage is drafted as a major
 * ICT-related incident, a vulnerability as a significant cyber threat.
 */
export function MemoAction({
  changeSignalId,
  kind,
  materialCount,
  existing,
  canDraft,
}: {
  changeSignalId: string;
  kind: MemoKind;
  materialCount: number;
  existing: RegulatorMemo | null;
  canDraft: boolean;
}) {
  const router = useRouter();
  const [drafting, setDrafting] = useState(false);

  async function draft() {
    setDrafting(true);
    try {
      const memo = await memoApi.propose(changeSignalId);
      toast.success("Regulator memo drafted — another analyst approves or dismisses it");
      router.push(`/responses/memos/${memo.id}`);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not draft the regulator memo.");
      setDrafting(false);
    }
  }

  const affected = `${materialCount} ${materialCount === 1 ? "service is" : "services are"} materially affected`;
  return (
    <section
      aria-label="Regulator notification"
      className="flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3 text-sm"
    >
      <div className="min-w-0 space-y-0.5">
        <p className="font-medium">Regulator notification (DORA)</p>
        {existing ? (
          <p className="flex flex-wrap items-center gap-2 text-muted-foreground">
            <MemoStatusBadge status={existing.status} />
            {MEMO_KIND[existing.kind].title} <span className="font-mono">{existing.reference}</span>
          </p>
        ) : (
          <p className="text-muted-foreground">
            {affected}.{" "}
            {canDraft
              ? `Aegis can draft ${MEMO_KIND[kind].article} for review; nothing is sent.`
              : "An analyst can draft a regulator notification for review."}
          </p>
        )}
      </div>
      {existing ? (
        <Link
          href={`/responses/memos/${existing.id}`}
          className="font-medium text-primary underline-offset-4 hover:underline"
        >
          View memo →
        </Link>
      ) : (
        canDraft && (
          <Button size="sm" variant="outline" onClick={draft} disabled={drafting}>
            <Landmark />
            {drafting ? "Drafting…" : "Draft regulator memo"}
          </Button>
        )
      )}
    </section>
  );
}
