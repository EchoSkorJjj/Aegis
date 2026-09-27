import type { MemoKind, RegulatorMemoStatus, RiskTicketStatus, UpgradeProposalStatus } from "@/lib/types";

/**
 * How far along a drafted response is, whatever its kind: an upgrade pull request, a risk ticket or a
 * regulator memo each map their own statuses onto the same tones.
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

export const TICKET_STATUS: Record<RiskTicketStatus, { label: string; tone: ResponseTone }> = {
  PROPOSED: { label: "Awaiting decision", tone: "waiting" },
  FILING: { label: "Filing in Jira", tone: "working" },
  FILED: { label: "Filed", tone: "done" },
  FAILED: { label: "Filing failed", tone: "failed" },
  DISMISSED: { label: "Dismissed", tone: "declined" },
};

export function TicketStatusBadge({ status }: { status: RiskTicketStatus }) {
  return <StatusBadge tone={TICKET_STATUS[status].tone}>{TICKET_STATUS[status].label}</StatusBadge>;
}

/** Decided: filed in the tracker, or dismissed (the review queue asks for pending responses instead). */
export const TICKET_HISTORY_STATUSES: RiskTicketStatus[] = ["FILED", "DISMISSED"];

export const MEMO_STATUS: Record<RegulatorMemoStatus, { label: string; tone: ResponseTone }> = {
  PROPOSED: { label: "Awaiting decision", tone: "waiting" },
  APPROVED: { label: "Final", tone: "done" },
  DISMISSED: { label: "Dismissed", tone: "declined" },
};

export function MemoStatusBadge({ status }: { status: RegulatorMemoStatus }) {
  return <StatusBadge tone={MEMO_STATUS[status].tone}>{MEMO_STATUS[status].label}</StatusBadge>;
}

export const MEMO_REVIEW_STATUSES: RegulatorMemoStatus[] = ["PROPOSED"];

/** The two DORA notifications Aegis drafts: a vendor outage is an incident, a vulnerability a threat. */
export const MEMO_KIND: Record<MemoKind, { title: string; article: string }> = {
  MAJOR_INCIDENT: {
    title: "Major ICT-related incident notification",
    article: "the initial notification of a major ICT-related incident (DORA Article 19(1))",
  },
  CYBER_THREAT: {
    title: "Significant cyber threat notification",
    article: "a voluntary notification of a significant cyber threat (DORA Article 19(2))",
  },
};

/** Decided: approved as final (ready for a person to submit), or dismissed. */
export const MEMO_HISTORY_STATUSES: RegulatorMemoStatus[] = ["APPROVED", "DISMISSED"];

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

/** A calendar date (yyyy-mm-dd, no time zone) as the reader's locale writes it. */
export function formatDate(date: string | null): string {
  if (!date) return "—";
  const [year, month, day] = date.split("-").map(Number);
  if (!year || !month || !day) return "—";
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}
