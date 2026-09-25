import type { UpgradeProposalStatus } from "@/lib/types";

/**
 * How far along a drafted response is, whatever its kind — an upgrade pull request today; a risk
 * ticket (story 5) or a notification memo (story 6) maps its own statuses onto the same tones.
 */
export type ResponseTone = "waiting" | "working" | "done" | "failed" | "declined";

const TONE_CLASS: Record<ResponseTone, string> = {
  waiting: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  working: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  done: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  failed: "bg-red-500/10 text-red-600 dark:text-red-400",
  declined: "bg-muted text-muted-foreground",
};

export function StatusBadge({ tone, children }: { tone: ResponseTone; children: React.ReactNode }) {
  return (
    <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${TONE_CLASS[tone]}`}>
      {children}
    </span>
  );
}

export const UPGRADE_STATUS: Record<UpgradeProposalStatus, { label: string; tone: ResponseTone }> = {
  PROPOSED: { label: "Awaiting decision", tone: "waiting" },
  OPENING: { label: "Opening draft PR", tone: "working" },
  OPENED: { label: "Draft PR open", tone: "done" },
  FAILED: { label: "Opening failed", tone: "failed" },
  DISMISSED: { label: "Dismissed", tone: "declined" },
};

export function UpgradeStatusBadge({ status }: { status: UpgradeProposalStatus }) {
  return <StatusBadge tone={UPGRADE_STATUS[status].tone}>{UPGRADE_STATUS[status].label}</StatusBadge>;
}

/** Still a person's to act on: awaiting a decision, an approval in flight, or an opening that failed. */
export const UPGRADE_REVIEW_STATUSES: UpgradeProposalStatus[] = ["PROPOSED", "FAILED", "OPENING"];

/** Decided: a draft pull request was opened, or the proposal was dismissed. */
export const UPGRADE_HISTORY_STATUSES: UpgradeProposalStatus[] = ["OPENED", "DISMISSED"];

export function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}
