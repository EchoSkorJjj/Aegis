import { Check } from "lucide-react";

import { formatDateTime } from "@/components/app/responses/response-status";
import type { RiskTicket } from "@/lib/types";

type StepState = "done" | "current" | "pending" | "skipped";

interface Step {
  label: string;
  state: StepState;
  detail: string | null;
}

/**
 * Where a response's item stands in its lifecycle — New → In review → Approved or Overridden → Actioned or
 * Closed — with who moved it along and when. A response dismissed straight from review skips the decision step.
 */
export function ResponseLifecycle({
  ticket,
  personName,
}: {
  ticket: RiskTicket;
  personName: (id: string | null) => string;
}) {
  const steps = lifecycle(ticket, personName);
  return (
    <ol aria-label="Lifecycle" className="grid gap-3 rounded-lg border p-4 text-sm sm:grid-cols-4">
      {steps.map((step, index) => (
        <li key={index} aria-current={step.state === "current" ? "step" : undefined} className="flex gap-2">
          <StepMarker state={step.state} index={index} />
          <div className="min-w-0">
            <p className={step.state === "pending" || step.state === "skipped" ? "text-muted-foreground" : "font-medium"}>
              {step.label}
            </p>
            {step.detail && <p className="text-xs break-words text-muted-foreground">{step.detail}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}

function StepMarker({ state, index }: { state: StepState; index: number }) {
  const base = "flex size-5 shrink-0 items-center justify-center rounded-full text-[10px] font-medium";
  if (state === "done") {
    return (
      <span className={`${base} bg-emerald-500/15 text-emerald-600 dark:text-emerald-400`}>
        <Check className="size-3" />
      </span>
    );
  }
  if (state === "current") {
    return <span className={`${base} bg-amber-500/15 text-amber-600 ring-1 ring-amber-500/40 dark:text-amber-400`}>{index + 1}</span>;
  }
  return <span className={`${base} bg-muted text-muted-foreground`}>{index + 1}</span>;
}

function lifecycle(ticket: RiskTicket, personName: (id: string | null) => string): Step[] {
  const override = ticket.override;
  // Worded "who did what", so it never repeats the page's own "Drafted by …" or "Approved by …" sentences.
  const overridden = override
    ? `${capitalize(personName(override.by))} overrode the priority and due date on ${formatDateTime(override.at)}.`
    : null;
  const drafted = `${capitalize(personName(ticket.createdBy))} drafted it on ${formatDateTime(ticket.createdAt)}.`;
  const approved = `${capitalize(personName(ticket.decidedBy))} approved it on ${formatDateTime(ticket.decidedAt)}.`;
  const dismissed = `${capitalize(personName(ticket.decidedBy))} dismissed it on ${formatDateTime(ticket.decidedAt)}.`;
  const decisionLabel = override ? "Overridden" : "Approved";
  const steps: Step[] = [
    { label: "New", state: "done", detail: "The change reached this service." },
    { label: "In review", state: "done", detail: drafted },
    { label: decisionLabel, state: "done", detail: null },
    { label: "Actioned", state: "pending", detail: null },
  ];

  switch (ticket.stage) {
    case "IN_REVIEW":
      steps[1].state = "current";
      steps[1].detail = [drafted, overridden].filter(Boolean).join(" ");
      steps[2] = {
        label: override ? "Overridden" : "Approved or overridden",
        state: "pending",
        detail: override ? "Takes effect when someone approves it." : null,
      };
      steps[3].label = "Actioned or closed";
      break;
    case "APPROVED":
    case "OVERRIDDEN":
      steps[2].state = "current";
      steps[2].detail = [overridden, approved].filter(Boolean).join(" ");
      steps[3].detail = ticket.status === "FAILED" ? "Filing failed; it can be tried again." : "Filing in Jira…";
      break;
    case "ACTIONED":
      steps[2].detail = [overridden, approved].filter(Boolean).join(" ");
      steps[3] = {
        label: "Actioned",
        state: "done",
        detail: ticket.issueKey ? `Filed as ${ticket.issueKey}.` : "Filed.",
      };
      break;
    case "CLOSED": {
      // Dismissed after a failed filing had been approved; otherwise straight from review.
      const approvedFirst = ticket.failureMessage != null;
      steps[2] = approvedFirst
        ? { label: decisionLabel, state: "done", detail: overridden ?? "Approved, but the filing failed." }
        : { label: "Approved or overridden", state: "skipped", detail: "Not approved." };
      steps[3] = { label: "Closed", state: "done", detail: dismissed };
      break;
    }
    default:
      break;
  }
  return steps;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
