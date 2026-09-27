"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";

import type { ResponseTone } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { directoryApi, inventoryApi } from "@/lib/api";
import { canRespondForService, respondsForAnyService } from "@/lib/roles";
import type { Role } from "@/lib/types";

/**
 * The pieces every drafted response's page shares — an upgrade pull request, a risk ticket, a regulator
 * memo: the decision panel, confirming an approval, dismissing with a reason, and who drafted and decided.
 */

const MAX_REASON = 1000;

/**
 * Names for "drafted by" / "decided by", from the organization's member directory (readable by every
 * member): "you" for the caller, "a member" for anyone the directory didn't return.
 */
export function useMemberNames(
  enabled: boolean,
  orgId: string | null | undefined,
  userId: string | undefined,
): (id: string | null) => string {
  const [people, setPeople] = useState<Map<string, string>>(new Map());

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    directoryApi
      .listMembers({ size: 100 })
      .then((page) => {
        if (!cancelled) setPeople(new Map(page.content.map((member) => [member.id, member.displayName || member.email])));
      })
      .catch(() => {
        // Names are a nicety; the page works without them.
      });
    return () => {
      cancelled = true;
    };
  }, [enabled, orgId]);

  return (id) => {
    if (!id) return "—";
    if (id === userId) return "you";
    return people.get(id) ?? "a member";
  };
}

/**
 * May the caller decide a response for this service: an analyst, or one of its assigned owners (aegis-core's
 * `UpgradeRoles` / `TicketRoles`)? "checking" while a service owner's ownership is being confirmed, so their
 * controls wait rather than flash "you can't decide".
 */
export function useServiceDecisionRight(
  roles: readonly Role[],
  serviceId: string | undefined,
  userId: string | undefined,
): boolean | "checking" {
  const needsOwnerCheck = roles.includes("SERVICE_OWNER") && !respondsForAnyService(roles);
  const [isAssignedOwner, setIsAssignedOwner] = useState<boolean | null>(null);

  useEffect(() => {
    if (!needsOwnerCheck || !serviceId || !userId) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setIsAssignedOwner(null);
    inventoryApi
      .listServiceOwners(serviceId, { size: 100 })
      .then((page) => {
        if (!cancelled) setIsAssignedOwner(page.content.some((owner) => owner.userId === userId));
      })
      .catch(() => {
        if (!cancelled) setIsAssignedOwner(false);
      });
    return () => {
      cancelled = true;
    };
  }, [needsOwnerCheck, serviceId, userId]);

  if (needsOwnerCheck && isAssignedOwner === null) return "checking";
  return canRespondForService(roles, isAssignedOwner === true);
}

/** Back to the queue a response belongs in: review while it needs a person, history once decided. */
export function BackToQueue({ decided }: { decided: boolean }) {
  return (
    <Link
      href={decided ? "/responses/history" : "/responses/review"}
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      {decided ? "Response history" : "Review & approve"}
    </Link>
  );
}

/** An approval, confirmed first: the popover says exactly what approving will do. */
export function ConfirmButton({
  label,
  busyLabel,
  busy,
  disabled,
  question,
  confirmLabel,
  onConfirm,
  children,
}: {
  label: string;
  busyLabel: string;
  /** This action is running. */
  busy: boolean;
  /** Any decision is running. */
  disabled: boolean;
  question: string;
  confirmLabel: string;
  onConfirm: () => void;
  /** What will happen, in a sentence or two. */
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button disabled={disabled} />}>{busy ? busyLabel : label}</PopoverTrigger>
      <PopoverContent align="start" className="w-96 space-y-3 text-sm">
        <p className="font-medium">{question}</p>
        <p className="text-muted-foreground">{children}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            onClick={() => {
              setOpen(false);
              onConfirm();
            }}
          >
            {confirmLabel}
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** Dismissing asks why; the reason is recorded on the response for whoever looks at it next. */
export function DismissButton({
  busy,
  disabled,
  question,
  placeholder,
  what,
  label = "Dismiss",
  busyLabel = "Dismissing…",
  submitLabel = `Dismiss ${what}`,
  onDismiss,
}: {
  /** Dismissing is running. */
  busy: boolean;
  /** Any decision is running. */
  disabled: boolean;
  question: string;
  placeholder: string;
  /** What is being dismissed: "proposal", "response", "memo". */
  what: string;
  /** The button's own label, while it runs, and the confirming one's. */
  label?: string;
  busyLabel?: string;
  submitLabel?: string;
  onDismiss: (reason: string) => Promise<boolean>;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!reason.trim()) return;
    if (await onDismiss(reason.trim())) {
      setOpen(false);
      setReason("");
    }
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button variant="outline" disabled={disabled} />}>
        {busy ? busyLabel : label}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-96">
        <form onSubmit={submit} className="space-y-3 text-sm">
          <label htmlFor={`${id}-reason`} className="block font-medium">
            {question}
          </label>
          <textarea
            id={`${id}-reason`}
            required
            autoFocus
            rows={4}
            maxLength={MAX_REASON}
            value={reason}
            disabled={disabled}
            onChange={(event) => setReason(event.target.value)}
            placeholder={placeholder}
            className="w-full rounded-md border border-input bg-transparent px-2.5 py-2 text-sm shadow-xs outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          />
          <p className="text-xs text-muted-foreground">Recorded on the {what} for whoever looks at it next.</p>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={disabled || !reason.trim()}>
              {submitLabel}
            </Button>
          </div>
        </form>
      </PopoverContent>
    </Popover>
  );
}

/** A dismissed response: who dismissed it, when, and why. */
export function DismissedPanel({
  decider,
  when,
  reason,
}: {
  decider: string;
  when: string;
  reason: string | null;
}) {
  return (
    <DecisionPanel tone="declined" title="Dismissed">
      <p>
        Dismissed by {decider} on {when}.
      </p>
      {reason && <blockquote className="border-l-2 pl-3 whitespace-pre-wrap text-muted-foreground">{reason}</blockquote>}
    </DecisionPanel>
  );
}

const PANEL_CLASS: Record<ResponseTone, string> = {
  waiting: "border-amber-500/30 bg-amber-500/5",
  working: "border-sky-500/30 bg-sky-500/5",
  done: "border-emerald-500/30 bg-emerald-500/5",
  failed: "border-red-500/30 bg-red-500/5",
  declined: "bg-muted/30",
};

/** Where a response stands and what can be done about it. */
export function DecisionPanel({
  tone,
  title,
  children,
}: {
  tone: ResponseTone;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section aria-label="Decision" className={`space-y-2 rounded-lg border p-4 text-sm ${PANEL_CLASS[tone]}`}>
      <h2 className="font-medium">{title}</h2>
      {children}
    </section>
  );
}

export function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </>
  );
}

export function DetailShell({ back, children }: { back: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      {back}
      {children}
    </div>
  );
}

export function StateCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">{children}</div>
  );
}
