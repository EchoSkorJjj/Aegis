"use client";

import { useEffect, useState } from "react";
import { Sparkles } from "lucide-react";
import { toast } from "sonner";

import { formatDateTime } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { ApiError, assessmentApi } from "@/lib/api";
import type { ReachabilityAssessmentItem, ReachabilitySearch, ReachabilityVerdict } from "@/lib/types";

// Whether the code reaches the vulnerable part — by a call, the type of data it binds, a setting — not only a call.
const VERDICT: Record<ReachabilityVerdict, { label: string; className: string }> = {
  CALLED: { label: "Uses it", className: "bg-red-500/10 text-red-600 dark:text-red-400" },
  NOT_CALLED: { label: "Doesn't use it", className: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" },
  CANT_TELL: { label: "Can't tell", className: "bg-muted text-muted-foreground" },
};

/**
 * The AI's reachability suggestions for one change, by service (E11-5): loaded once; `replace` swaps a service's in
 * after someone asks. The impact view remounts this hook on a workspace change.
 */
export function useAssessments(changeSignalId: string, enabled: boolean) {
  const [byService, setByService] = useState<Map<string, ReachabilityAssessmentItem[]> | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setByService(null);
    setError(false);
    assessmentApi
      .list(changeSignalId)
      .then((items) => {
        if (!cancelled) setByService(byServiceOf(items));
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [changeSignalId, enabled]);

  function replace(serviceId: string, items: ReachabilityAssessmentItem[]) {
    setByService((current) => new Map(current ?? []).set(serviceId, items));
  }

  return { byService, error, replace };
}

function byServiceOf(items: ReachabilityAssessmentItem[]) {
  const byService = new Map<string, ReachabilityAssessmentItem[]>();
  for (const item of items) byService.set(item.serviceId, [...(byService.get(item.serviceId) ?? []), item]);
  return byService;
}

/** "template (no match), merge (2 lines in 1 file)": what the code was searched for and how often it was found. */
function searched(searches: ReachabilitySearch[]) {
  const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? "" : "s"}`;
  return searches
    .map(({ term, lines, files }) =>
      `${term} (${lines === 0 ? "no match" : `${plural(lines, "line")} in ${plural(files, "file")}`})`)
    .join(", ");
}

/**
 * Whether the service's code uses the vulnerable function, feature or condition, as the AI suggests from a few lines
 * where the code uses what it imports and where it names what the advisory or its fix names: its answer, why, the lines
 * it cites, what the code was searched for and what a reviewer should still check — beside the score, never part of it
 * — or, not assessed, why. Whoever may draft a response for the service may ask (again).
 */
export function AiSuggestion({
  changeSignalId,
  serviceId,
  items,
  canAsk,
  onAnswered,
}: {
  changeSignalId: string;
  serviceId: string;
  /** Null while loading. */
  items: ReachabilityAssessmentItem[] | null;
  canAsk: boolean;
  onAnswered: (items: ReachabilityAssessmentItem[]) => void;
}) {
  const [asking, setAsking] = useState(false);

  if (items === null) {
    return <p className="text-xs text-muted-foreground">Loading the AI&apos;s suggestion…</p>;
  }
  if (items.length === 0) return null;

  async function ask() {
    setAsking(true);
    try {
      onAnswered(await assessmentApi.ask({ serviceId, changeSignalId }));
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not ask the AI.");
    } finally {
      setAsking(false);
    }
  }

  const askable = canAsk && items.some((item) => item.assessable);
  const answered = items.some((item) => item.status === "ASSESSED");

  return (
    <section aria-label="AI suggestion" className="space-y-2 rounded-md border border-dashed p-3 text-xs">
      <p className="flex items-center gap-1.5 font-medium text-muted-foreground">
        <Sparkles className="size-3.5" />
        AI suggestion: does the code reach the vulnerable part?
      </p>
      <ul className="space-y-3">
        {items.map((item) => (
          <li key={`${item.ecosystem}:${item.packageName}`} className="space-y-1">
            {items.length > 1 && <p className="font-mono text-muted-foreground">{item.packageName}</p>}
            {item.status === "ASSESSED" && item.verdict ? (
              <>
                <p className="flex flex-wrap items-center gap-2">
                  <span className={`rounded-full px-2 py-0.5 font-medium ${VERDICT[item.verdict].className}`}>
                    {VERDICT[item.verdict].label}
                  </span>
                  {item.assessedAt && (
                    <span className="text-muted-foreground">Asked {formatDateTime(item.assessedAt)}</span>
                  )}
                </p>
                <p className="text-foreground">{item.rationale}</p>
                {item.searches.length > 0 && (
                  <p className="text-muted-foreground">Searched the code for {searched(item.searches)}.</p>
                )}
                {item.checks.length > 0 && (
                  <div className="space-y-0.5">
                    <p id={`to-check-${serviceId}-${item.packageName}`} className="font-medium text-muted-foreground">
                      To check
                    </p>
                    <ul
                      aria-labelledby={`to-check-${serviceId}-${item.packageName}`}
                      className="list-disc space-y-0.5 pl-4 text-foreground"
                    >
                      {item.checks.map((check) => (
                        <li key={check}>{check}</li>
                      ))}
                    </ul>
                  </div>
                )}
                {item.citedLines.length > 0 && (
                  <p className="text-muted-foreground">
                    Cites{" "}
                    {item.citedLines.map((lines, index) => {
                      const label = `${lines.path}:${lines.startLine}–${lines.endLine}`;
                      return (
                        <span key={`${lines.path}:${lines.startLine}`}>
                          {index > 0 && ", "}
                          {lines.url ? (
                            <a
                              href={lines.url}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="font-mono text-primary underline-offset-4 hover:underline"
                            >
                              {label}
                            </a>
                          ) : (
                            <span className="font-mono">{label}</span>
                          )}
                        </span>
                      );
                    })}
                  </p>
                )}
                <p className="text-muted-foreground">A suggestion from the AI: it doesn&apos;t change the score.</p>
              </>
            ) : (
              <p className="text-muted-foreground">Not assessed: {item.reason}</p>
            )}
          </li>
        ))}
      </ul>
      {asking ? (
        <p className="text-muted-foreground">Reading the code and asking the AI…</p>
      ) : (
        askable && (
          <Button size="xs" variant="outline" onClick={ask}>
            <Sparkles />
            {answered ? "Ask again" : "Ask the AI"}
          </Button>
        )
      )}
    </section>
  );
}
