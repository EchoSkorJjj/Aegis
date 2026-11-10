"use client";

import { useEffect, useState } from "react";
import { Sparkles } from "lucide-react";

import { formatDateTime } from "@/components/app/responses/response-status";
import { findingApi } from "@/lib/api";
import type {
  ConditionMet,
  FindingInterpretation,
  InterpretationConfidence,
  InterpretationSignal,
  SuggestedAction,
} from "@/lib/types";

const SIGNAL: Record<InterpretationSignal, { label: string; className: string }> = {
  LIKELY_REAL: { label: "Likely real", className: "bg-red-500/10 text-red-800 dark:text-red-400" },
  UNCERTAIN: { label: "Uncertain", className: "bg-amber-500/10 text-amber-800 dark:text-amber-400" },
  LIKELY_NOISE: { label: "Likely noise", className: "bg-muted text-muted-foreground" },
};

export const SIGNALS: { value: InterpretationSignal; label: string }[] = [
  { value: "LIKELY_REAL", label: SIGNAL.LIKELY_REAL.label },
  { value: "UNCERTAIN", label: SIGNAL.UNCERTAIN.label },
  { value: "LIKELY_NOISE", label: SIGNAL.LIKELY_NOISE.label },
];

const CONFIDENCE: Record<InterpretationConfidence, string> = {
  LOW: "Low confidence",
  MEDIUM: "Medium confidence",
  HIGH: "High confidence",
};

const ACTION: Record<SuggestedAction, string> = {
  VERIFY: "Verify first",
  ACT_NOW: "Act now",
  SCHEDULE: "Schedule",
  MONITOR: "Monitor",
};

const CONDITION: Record<ConditionMet, string> = {
  UNKNOWN: "Aegis can't confirm this from what it can see.",
  YES: "The sources show this holds.",
  NO: "The sources show this doesn't hold.",
};

/** The AI's signal on a triage queue row. */
export function AiSignal({ signal }: { signal: InterpretationSignal }) {
  return (
    <span className={`mt-1 inline-block rounded-full px-2 py-0.5 text-xs font-medium ${SIGNAL[signal].className}`}>
      AI: {SIGNAL[signal].label}
    </span>
  );
}

/** What the AI has read this change to mean, by service: loaded once. The impact view remounts it on a workspace change. */
export function useInterpretations(changeSignalId: string, enabled: boolean) {
  const [byService, setByService] = useState<Map<string, FindingInterpretation[]> | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new change, so nothing is known about it yet
    setByService(null);
    findingApi
      .interpretations(changeSignalId)
      .then((items) => {
        if (cancelled) return;
        const grouped = new Map<string, FindingInterpretation[]>();
        for (const item of items) grouped.set(item.serviceId, [...(grouped.get(item.serviceId) ?? []), item]);
        setByService(grouped);
      })
      .catch(() => {
        // Beside the score, never needed for it: the page works without it.
        if (!cancelled) setByService(null);
      });
    return () => {
      cancelled = true;
    };
  }, [changeSignalId, enabled]);

  return byService;
}

/**
 * What a material finding means for this service, as the AI read it with nobody asking: a suggestion beside the score,
 * never part of it. Nothing shows until the readings have loaded, or at all if they couldn't be.
 */
export function AiInterpretation({ items }: { items: FindingInterpretation[] | null }) {
  if (items === null) return null;
  return (
    <section aria-label="AI reading" className="space-y-3 rounded-md border border-dashed p-3 text-xs">
      <p className="flex items-center gap-1.5 font-medium text-muted-foreground">
        <Sparkles className="size-3.5" />
        AI reading: what this means for this service
      </p>
      {items.length === 0 ? (
        <p className="text-muted-foreground">
          The AI hasn&apos;t read this finding yet. It reads the highest-impact findings first.
        </p>
      ) : (
        <ul className="space-y-4">
          {items.map((item) => (
            <li key={item.subject} className="space-y-2">
              {items.length > 1 && <p className="font-mono text-muted-foreground">{item.subject}</p>}
              <p className="flex flex-wrap items-center gap-2">
                <span className={`rounded-full px-2 py-0.5 font-medium ${SIGNAL[item.signal].className}`}>
                  {SIGNAL[item.signal].label}
                </span>
                <span className="text-muted-foreground">{CONFIDENCE[item.confidence]}</span>
              </p>
              <Reading label="What changed">{item.whatChanged}</Reading>
              <Reading label="Why it matters here">{item.whyItMatters}</Reading>
              {item.condition && (
                <div className="space-y-0.5">
                  <p className="font-medium text-muted-foreground">Applies only if</p>
                  <p className="text-foreground">{item.condition}</p>
                  <p className="text-muted-foreground">{CONDITION[item.conditionMet]}</p>
                </div>
              )}
              <Reading label="What could happen next">{item.whatNext}</Reading>
              <div className="space-y-0.5">
                <p className="font-medium text-muted-foreground">Suggested next step</p>
                <p className="text-foreground">
                  <span className="font-medium">{ACTION[item.suggestedAction]}</span>
                  <span aria-hidden="true"> · </span>
                  <span>{item.actionDetail}</span>
                </p>
              </div>
              {item.assumptions.length > 0 && (
                <div className="space-y-0.5">
                  <p className="font-medium text-muted-foreground">Assumes</p>
                  <ul className="list-disc space-y-0.5 pl-4 text-foreground">
                    {item.assumptions.map((assumption) => <li key={assumption}>{assumption}</li>)}
                  </ul>
                </div>
              )}
              {item.citations.length > 0 && (
                <details>
                  <summary className="cursor-pointer font-medium text-muted-foreground">What this rests on</summary>
                  <ul className="mt-1 space-y-1 border-l pl-2 text-muted-foreground">
                    {item.citations.map((citation) => <li key={citation}>{citation}</li>)}
                  </ul>
                </details>
              )}
              <p className="text-muted-foreground">
                Read by the AI {formatDateTime(item.interpretedAt)}. A suggestion for you to weigh: it never changes the
                score or decides anything.
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Reading({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-0.5">
      <p className="font-medium text-muted-foreground">{label}</p>
      <p className="text-foreground">{children}</p>
    </div>
  );
}
