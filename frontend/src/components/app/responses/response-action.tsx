"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ClipboardList } from "lucide-react";
import { toast } from "sonner";

import { TicketStatusBadge, UpgradeStatusBadge } from "@/components/app/responses/response-status";
import { describeEdits } from "@/components/app/responses/upgrade-edits";
import { Button } from "@/components/ui/button";
import { ApiError, ticketApi } from "@/lib/api";
import type { RiskTicket } from "@/lib/types";

/**
 * The responses drafted for one change and not dismissed, keyed by service — so the impact page links to a
 * response instead of offering to draft it again. Fetches every page; failures remain visible rather
 * than being treated as an empty list. The impact view remounts this hook on a workspace change.
 */
export function useResponses(changeSignalId: string, enabled: boolean) {
  const [responses, setResponses] = useState<Map<string, RiskTicket> | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setResponses(null);
    setError(false);
    async function load() {
      // Newest first, so the first response for a service is the one to show.
      const byService = new Map<string, RiskTicket>();
      let pageIndex = 0;
      while (!cancelled) {
        const page = await ticketApi.list({
          changeSignalId, status: ["PROPOSED", "FILING", "FILED", "FAILED"], size: 100, page: pageIndex,
        });
        for (const response of page.content) {
          if (!byService.has(response.serviceId)) byService.set(response.serviceId, response);
        }
        pageIndex++;
        if (pageIndex >= page.totalPages) break;
      }
      if (!cancelled) setResponses(byService);
    }
    load()
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [changeSignalId, enabled]);

  return { responses, error };
}

/**
 * A service card's response row: the response already drafted for this change and service — its ticket and
 * its upgrade pull request — or, for someone who may draft one (an analyst, or this service's owners), the
 * draft button.
 */
export function ResponseAction({
  changeSignalId,
  serviceId,
  existing,
  canDraft,
}: {
  changeSignalId: string;
  serviceId: string;
  existing: RiskTicket | undefined;
  canDraft: boolean;
}) {
  const router = useRouter();
  const [drafting, setDrafting] = useState(false);

  if (existing) {
    const pullRequest = existing.pullRequests.find((candidate) => candidate.status !== "DISMISSED");
    return (
      <Row>
        <span className="font-medium">Response</span>
        <TicketStatusBadge status={existing.status} />
        {existing.issueKey && <span className="font-mono text-muted-foreground">{existing.issueKey}</span>}
        {pullRequest && (
          <span className="inline-flex items-center gap-1.5">
            <UpgradeStatusBadge status={pullRequest.status} />
            <span className="font-mono text-muted-foreground">{describeEdits(pullRequest.edits)}</span>
          </span>
        )}
        <Link
          href={`/responses/tickets/${existing.id}`}
          className="font-medium text-primary underline-offset-4 hover:underline"
        >
          View response →
        </Link>
      </Row>
    );
  }
  if (!canDraft) {
    return (
      <Row>
        <span className="text-muted-foreground">An analyst or this service&apos;s owner can draft a response.</span>
      </Row>
    );
  }

  async function draft() {
    setDrafting(true);
    try {
      const response = await ticketApi.propose({ changeSignalId, serviceId });
      toast.success("Response drafted — review it, then approve or dismiss it");
      router.push(`/responses/tickets/${response.id}`);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not draft the response.");
      setDrafting(false);
    }
  }

  return (
    <Row>
      <Button size="sm" variant="outline" onClick={draft} disabled={drafting}>
        <ClipboardList />
        {drafting ? "Drafting…" : "Draft response"}
      </Button>
      <span className="text-muted-foreground">
        The risk ticket and, where Aegis can automate it, one upgrade pull request. Nothing leaves Aegis until
        someone approves it.
      </span>
    </Row>
  );
}

function Row({ children }: { children: React.ReactNode }) {
  return <div className="flex flex-wrap items-center gap-2 border-t px-4 py-3 text-xs">{children}</div>;
}
