"use client";

import { Fragment, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ArrowRight, Info } from "lucide-react";

import { AiSuggestion, useAssessments } from "@/components/app/advisories/ai-suggestion";
import { ReachabilityEvidence } from "@/components/app/responses/code-usage";
import { MemoAction, useLiveMemo } from "@/components/app/responses/memo-action";
import { ResponseAction, useResponses } from "@/components/app/responses/response-action";
import { UpgradeEditsTable } from "@/components/app/responses/upgrade-edits";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ApiError, scoringApi, signalApi } from "@/lib/api";
import { canReadInventory, canRespondForService, respondsForAnyService } from "@/lib/roles";
import type {
  AffectedProductEvidence,
  BusinessCriticality,
  ChangeImpactResponse,
  ChangeSignal,
  EvidenceChain,
  IdentityMatch,
  ImpactTier,
  MatchConfidence,
  RiskTicket,
  ScoredService,
  ScoreFactor,
  ScoreFactorKind,
  SeverityInput,
  SignalType,
  VersionMatchState,
} from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

type Status = "loading" | "ok" | "forbidden" | "notfound" | "unmatchable" | "error";

const SIGNAL_TYPE: Record<SignalType, { label: string; className: string }> = {
  VULNERABILITY: { label: "Vulnerability", className: "bg-red-500/10 text-red-600 dark:text-red-400" },
  VENDOR_OUTAGE: { label: "Vendor outage", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
  SECURITY_NEWS: { label: "Security news", className: "bg-sky-500/10 text-sky-600 dark:text-sky-400" },
};

const CRITICALITY_CLASS: Record<BusinessCriticality, string> = {
  LOW: "bg-muted text-muted-foreground",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
};

const CONFIDENCE: Record<MatchConfidence, { label: string; className: string }> = {
  CONFIRMED: { label: "Confirmed", className: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" },
  POSSIBLE: { label: "Needs review", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
};

const IDENTITY_LABEL: Record<IdentityMatch, string> = {
  EXACT_PACKAGE: "Exact package",
  CPE_VENDOR_PRODUCT_ALIAS: "CPE alias",
  AMBIGUOUS_NAME: "Ambiguous name",
};

const VERSION_LABEL: Record<VersionMatchState, string> = {
  IN_RANGE: "Version in range",
  OUT_OF_RANGE: "Version out of range",
  UNDETERMINED: "Version unverified",
};

const TIER_CLASS: Record<ImpactTier, string> = {
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  LOW: "bg-muted text-muted-foreground",
  NEGLIGIBLE: "bg-muted text-muted-foreground/70",
};

const FACTOR_LABEL: Record<ScoreFactorKind, string> = {
  SEVERITY: "Severity",
  EXPOSURE: "Exposure",
  REACHABILITY: "Reachability",
  CRITICALITY: "Criticality",
  CONFIDENCE: "Confidence",
};

export function ChangeImpactView({ signalId, finding = false }: { signalId: string; finding?: boolean }) {
  const { user } = useAuth();
  return <ImpactView key={`${user?.orgId}:${user?.id}:${signalId}:${finding}`} signalId={signalId} finding={finding} />;
}

function ImpactView({ signalId, finding }: { signalId: string; finding: boolean }) {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);

  const [data, setData] = useState<ChangeImpactResponse | null>(null);
  const [signal, setSignal] = useState<ChangeSignal | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setStatus("loading");
    // One request: the scored response carries the match it was computed from (evidence and counts),
    // so the match never runs twice and the scores and evidence can't disagree.
    Promise.all([scoringApi.scoreChange(signalId), finding ? signalApi.get(signalId) : Promise.resolve(null)])
      .then(([result, change]) => {
        if (cancelled) return;
        setData(result);
        setSignal(change);
        setStatus("ok");
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 403) setStatus("forbidden");
        else if (err instanceof ApiError && err.status === 404) setStatus("notfound");
        else if (err instanceof ApiError && err.status === 400) setStatus("unmatchable");
        else setStatus("error");
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, canRead, user?.orgId, signalId, finding]);

  // A vulnerability or a vendor outage gets a response per service (its risk ticket, and for a vulnerability
  // the upgrade pull request), and a regulator notification when it is material.
  const respondable =
    status === "ok" && (data?.change.signalType === "VULNERABILITY" || data?.change.signalType === "VENDOR_OUTAGE");
  const { responses, error: responseError } = useResponses(signalId, respondable);
  const memo = useLiveMemo(signalId, respondable);
  // The AI's suggestion on whether the code calls the vulnerable function (E11-5): vulnerabilities only.
  const assessments = useAssessments(signalId, status === "ok" && data?.change.signalType === "VULNERABILITY");

  const backLink = (
    <Link
      href={finding ? "/findings" : "/advisories"}
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      {finding ? "Triage queue" : "Advisories"}
    </Link>
  );

  if (!authLoading && !canRead) {
    return (
      <Shell back={backLink} title="Change impact">
        <StateCard>You don&apos;t have access to matching — an Aegis domain role is required.</StateCard>
      </Shell>
    );
  }

  if (status === "loading") {
    return (
      <Shell back={backLink} title="Change impact">
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-40 w-full" />
      </Shell>
    );
  }

  if (status !== "ok" || !data) {
    const message =
      status === "forbidden"
        ? "You don't have access to matching — an Aegis domain role is required."
        : status === "notfound"
          ? "This change signal doesn't exist."
          : status === "unmatchable"
            ? "This signal can't be matched: security-news items have no package or vendor identity to correlate against your estate."
            : "Could not run the match. Please try again.";
    return (
      <Shell back={backLink} title="Change impact">
        <StateCard>{message}</StateCard>
      </Shell>
    );
  }

  // Scored services arrive highest first — a work queue — so the cards render in that order.
  const { change, matchedComponentCount, matchedVendorCount, services } = data;
  const type = SIGNAL_TYPE[change.signalType];
  const needsReview = services.filter((s) => s.confidence === "POSSIBLE").length;
  const materialCount = services.filter((s) => s.material).length;
  const roles = user?.roles ?? [];

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      {backLink}

      <div>
        <div className="flex flex-wrap items-center gap-2">
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${type.className}`}>{type.label}</span>
          <span className="font-mono text-sm text-muted-foreground">{change.externalId}</span>
        </div>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight">{change.title ?? change.externalId}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Impact of this change on your organization&apos;s estate, ranked by impact score, with what
          drives each score and the evidence for each hit.
        </p>
      </div>

      {signal && <section aria-label="Change" className="space-y-2 rounded-lg border p-4 text-sm">
        <p className="whitespace-pre-wrap">{signal.summary || "No summary was published."}</p>
        <p className="text-xs text-muted-foreground">Source: {signal.sourceId}{signal.occurredAt && ` · ${new Date(signal.occurredAt).toLocaleString()}`}</p>
        {signal.url && /^https?:\/\//i.test(signal.url) && <a href={signal.url} target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">Read original change →</a>}
      </section>}

      {responseError && <StateCard>Could not load drafted responses. Refresh this page to try again.</StateCard>}

      {/* Summary bar */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border bg-muted/30 px-4 py-3 text-sm">
        {change.signalType === "VENDOR_OUTAGE" ? (
          <Stat value={matchedVendorCount} label="vendor" plural="vendors" trailing="matched" />
        ) : (
          <Stat value={matchedComponentCount} label="component" plural="components" trailing="matched" />
        )}
        <span className="text-muted-foreground/40">·</span>
        <Stat value={services.length} label="service" plural="services" trailing="impacted" />
        {needsReview > 0 && (
          <>
            <span className="text-muted-foreground/40">·</span>
            <span className="font-medium text-amber-600 dark:text-amber-400">{needsReview} need review</span>
          </>
        )}
        <span className="text-muted-foreground/40">·</span>
        <span>
          <span className="text-muted-foreground">Severity</span>{" "}
          <span className="font-semibold text-foreground">{describeSeverity(data.severity)}</span>
        </span>
      </div>

      {respondable && memo !== undefined && (memo || materialCount > 0) && (
        <MemoAction
          changeSignalId={signalId}
          kind={change.signalType === "VENDOR_OUTAGE" ? "MAJOR_INCIDENT" : "CYBER_THREAT"}
          materialCount={materialCount}
          existing={memo}
          canDraft={respondsForAnyService(roles)}
        />
      )}

      {services.length === 0 ? (
        <StateCard>
          {matchedComponentCount === 0 && matchedVendorCount === 0
            ? "Nothing in your inventory matches this change."
            : "The change matches your inventory, but no business service depends on the affected components or vendor."}
        </StateCard>
      ) : (
        <div className="space-y-4">
          <h2 className="text-sm font-medium text-muted-foreground">
            Impacted services ({services.length})
          </h2>
          {services.map((scored) => {
            const canDraft = canRespondForService(roles, scored.owners.some((owner) => owner.userId === user?.id));
            return (
              <ServiceCard
                key={scored.service.id}
                scored={scored}
                response={respondable ? { signalId, responses, canDraft, preview: finding } : null}
                suggestion={change.signalType === "VULNERABILITY" && !assessments.error ? (
                  <AiSuggestion
                    changeSignalId={signalId}
                    serviceId={scored.service.id}
                    items={assessments.byService ? (assessments.byService.get(scored.service.id) ?? []) : null}
                    canAsk={canDraft}
                    onAnswered={(items) => assessments.replace(scored.service.id, items)}
                  />
                ) : null}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * What a service card needs to offer a response: the change, the responses already drafted for it by service
 * (null while loading), and whether the caller may draft one for this service.
 */
type ResponseContext = {
  signalId: string;
  responses: Map<string, RiskTicket> | null;
  canDraft: boolean;
  preview: boolean;
};

function ServiceCard({
  scored,
  response,
  suggestion,
}: {
  scored: ScoredService;
  response: ResponseContext | null;
  /** The AI's reachability suggestion (E11-5), beside the score and never part of it. */
  suggestion: React.ReactNode;
}) {
  const confidence = CONFIDENCE[scored.confidence];
  const existing = response?.responses?.get(scored.service.id);
  return (
    <div className="rounded-lg border">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b px-4 py-3">
        <ScoreBadge score={scored.score} tier={scored.tier} />
        <Link href={`/inventory/services/${scored.service.id}`} className="font-medium hover:underline">{scored.service.name}</Link>
        <span
          className={`rounded-full px-2 py-0.5 text-xs font-medium ${CRITICALITY_CLASS[scored.service.criticality]}`}
        >
          {scored.service.criticality}
        </span>
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${confidence.className}`}>
          {confidence.label}
        </span>
        <span className="ml-auto text-xs text-muted-foreground">
          {scored.owners.length > 0
            ? scored.owners.map((owner) => owner.name).join(", ")
            : "No owners assigned"}
        </span>
      </div>
      <div className="space-y-3 p-4">
        <WhyThisScore scored={scored} />
        {suggestion}
        {scored.evidence.map((evidence, index) => (
          <Evidence key={index} evidence={evidence} />
        ))}
      </div>
      {response?.preview && existing && <DraftedResponse response={existing} />}
      {response?.responses && (
        <ResponseAction
          changeSignalId={response.signalId}
          serviceId={scored.service.id}
          existing={existing}
          canDraft={response.canDraft}
        />
      )}
    </div>
  );
}

function DraftedResponse({ response }: { response: RiskTicket }) {
  return <section aria-label={`Drafted response for ${response.serviceName}`} className="space-y-3 border-t p-4 text-sm">
    <h3 className="font-medium">Drafted response: {response.title}</h3>
    <p className="text-xs text-muted-foreground">Impact when drafted: {response.score.toFixed(1)} · {response.priority.toLowerCase()} priority{response.dueDate && ` · Due ${response.dueDate}`}</p>
    <dl className="space-y-2">
      <div><dt className="font-medium">Summary</dt><dd className="whitespace-pre-wrap text-muted-foreground">{response.summary}</dd></div>
      <div><dt className="font-medium">Risk</dt><dd className="whitespace-pre-wrap text-muted-foreground">{response.risk}</dd></div>
      <div><dt className="font-medium">Impact</dt><dd className="whitespace-pre-wrap text-muted-foreground">{response.impact}</dd></div>
    </dl>
    {response.actions.length > 0 && <div><p className="font-medium">Recommended actions</p>
      <ul className="list-disc space-y-1 pl-5 text-muted-foreground">{response.actions.map((action, index) => <li key={index}>{action}</li>)}</ul></div>}
    {response.citations.length > 0 && <div><p className="font-medium">Citations</p>
      <ul className="list-disc pl-5 text-muted-foreground">{response.citations.map((citation, index) => <li key={index}>{citation}</li>)}</ul></div>}
    {response.pullRequests.filter((proposal) => proposal.status !== "DISMISSED").map((proposal) =>
      <details key={proposal.id} className="rounded-md border p-3">
        <summary className="cursor-pointer font-medium">Upgrade pull request: {proposal.title}</summary>
        <div className="mt-3 space-y-3">
          <UpgradeEditsTable edits={proposal.edits} />
          <pre className="whitespace-pre-wrap break-words font-sans text-xs text-muted-foreground">{proposal.body}</pre>
          <Link href={`/responses/upgrades/${proposal.id}`} className="text-primary hover:underline">View pull request →</Link>
        </div>
      </details>)}
  </section>;
}

function ScoreBadge({ score, tier }: { score: number; tier: ImpactTier }) {
  // Worded as an impact, in sentence case, so it never reads as the service's own criticality badge
  // (which shares the vocabulary — a CRITICAL service can carry a LOW-impact score and vice versa).
  const label = `${tier.charAt(0)}${tier.slice(1).toLowerCase()} impact`;
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="rounded-md border px-1.5 py-0.5 font-mono text-sm font-semibold tabular-nums">
        {score.toFixed(1)}
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${TIER_CLASS[tier]}`}>{label}</span>
    </span>
  );
}

/**
 * The score's contributing factors (CS203-72): each multiplier, what it was read from, and how many
 * points it is costing — so "why is this 36 and not 60" is answered on the page, not in a spreadsheet.
 */
function WhyThisScore({ scored }: { scored: ScoredService }) {
  // Confidence only discounts when a deployment configures it; at ×1.00 it is noise here, just as the
  // backend leaves it out of the explanation sentence. Reachability is about a package, so an outage has none.
  const factors = scored.factors.filter(
    (factor) =>
      (factor.factor !== "CONFIDENCE" || factor.weight !== 1) &&
      (factor.factor !== "REACHABILITY" || scored.breakdown.reachabilityApplies),
  );
  const limiting = factors.filter((factor) => factor.limiting);
  const formula = `${factors.map((factor) => factor.weight.toFixed(2)).join(" × ")} × 100 = ${scored.score.toFixed(1)}`;

  return (
    <div className="rounded-md border p-3">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <p className="text-xs font-medium text-muted-foreground">Why this score</p>
        <p className="font-mono text-xs text-muted-foreground">{formula}</p>
      </div>
      <ul className="space-y-2.5">
        {factors.map((factor) => (
          <Fragment key={factor.factor}>
            <FactorRow
              factor={factor}
              notApplied={factor.factor === "EXPOSURE" && !scored.breakdown.exposureApplies}
            />
            {/* The files and lines reachability rests on (E11-4), under its row. */}
            {factor.factor === "REACHABILITY" && scored.reachability && (
              <li className="-mt-1 pl-[6.75rem]" aria-label="What reachability rests on">
                <ReachabilityEvidence reachability={scored.reachability} />
              </li>
            )}
          </Fragment>
        ))}
      </ul>
      <p className="mt-3 text-xs text-muted-foreground">
        {limiting.length > 0 ? (
          <>
            Held down most by{" "}
            <span className="font-medium text-foreground">
              {limiting.map((factor) => FACTOR_LABEL[factor.factor].toLowerCase()).join(" and ")}
            </span>
            {limiting.length === 1 ? ": at its maximum this would score " : ": each at its maximum would score "}
            <span className="font-medium text-foreground tabular-nums">
              {(scored.score + limiting[0].headroom).toFixed(1)}
            </span>
            .
          </>
        ) : (
          "Every factor is at its maximum — this is the worst case."
        )}
        {!scored.material && " Below the materiality threshold: shown for the record, not queued."}
      </p>
    </div>
  );
}

function FactorRow({ factor, notApplied }: { factor: ScoreFactor; notApplied: boolean }) {
  return (
    <li className="flex items-center gap-3">
      <div className="flex w-24 shrink-0 items-center gap-1 text-sm font-medium">
        {FACTOR_LABEL[factor.factor]}
        <SettingHint factor={factor} notApplied={notApplied} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2 text-xs">
          <span className="truncate text-muted-foreground">{describeInput(factor)}</span>
          <span className="font-mono tabular-nums">×{factor.weight.toFixed(2)}</span>
        </div>
        <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
          {/* A term that does not apply gets no bar: ×1.00 there is "not counted", not "at max". */}
          {!notApplied && (
            <div
              className={`h-full rounded-full ${factor.limiting ? "bg-amber-500" : "bg-foreground/50"}`}
              style={{ width: `${Math.round(factor.weight * 100)}%` }}
            />
          )}
        </div>
      </div>
      <div className="w-20 shrink-0 text-right text-xs tabular-nums">
        {notApplied ? (
          <span className="text-muted-foreground/70">n/a</span>
        ) : factor.headroom > 0 ? (
          <span
            className={
              factor.limiting ? "font-medium text-amber-600 dark:text-amber-400" : "text-muted-foreground"
            }
            title={`${factor.headroom.toFixed(1)} points higher if this factor were at its maximum`}
          >
            −{factor.headroom.toFixed(1)} pts
          </span>
        ) : (
          <span className="text-muted-foreground/70">at max</span>
        )}
      </div>
    </li>
  );
}

/** Where a weight comes from — the setting to change, or why there is none. */
function SettingHint({ factor, notApplied }: { factor: ScoreFactor; notApplied: boolean }) {
  const text = notApplied
    ? "Not applied — exposure is attack reach, and an outage takes internal services down too"
    : factor.setting
      ? `Weight set by ${factor.setting}`
      : factor.factor === "SEVERITY"
        ? "Published by the advisory feed (CVSS) — not a configurable weight"
        : "Built-in fallback — the service doesn't declare this attribute";
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <button
            type="button"
            aria-label={`Where the ${FACTOR_LABEL[factor.factor].toLowerCase()} weight comes from`}
            className="inline-flex text-muted-foreground hover:text-foreground"
          >
            <Info className="size-3" />
          </button>
        }
      />
      <TooltipContent side="top" align="start" className="max-w-xs">
        <span className="font-mono">{text}</span>
      </TooltipContent>
    </Tooltip>
  );
}

const REACHABILITY_INPUT: Record<string, string> = {
  IMPORTED: "Imported by the code",
  NOT_IMPORTED: "Not imported, nor what pulls it in",
  UNKNOWN: "Unknown — scored as imported",
};

/** Enum inputs ("INTERNET_FACING") read as words; severity inputs are already prose ("CVSS 3.1 base 9.8"). */
function describeInput(factor: ScoreFactor) {
  if (factor.factor === "REACHABILITY" && REACHABILITY_INPUT[factor.input]) return REACHABILITY_INPUT[factor.input];
  if (factor.factor === "SEVERITY") {
    // "severity major" sits in the Severity row, so name it for what it is: the feed's own word.
    if (factor.input.startsWith("severity ")) return `Feed label “${factor.input.slice("severity ".length)}”`;
    return factor.input.charAt(0).toUpperCase() + factor.input.slice(1);
  }
  const words = factor.input.toLowerCase().replace(/_/g, "-");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function describeSeverity(severity: SeverityInput) {
  if (severity.source === "CVSS") {
    return `CVSS ${severity.cvssVersion ? `${severity.cvssVersion} ` : ""}${severity.cvssBaseScore}`;
  }
  if (severity.source === "SEVERITY_LABEL") return severity.label;
  return severity.label ? `unrecognised (“${severity.label}”)` : "not published";
}

function Evidence({ evidence }: { evidence: EvidenceChain }) {
  return (
    <div className="rounded-md border bg-muted/20 p-3">
      {evidence.kind === "VENDOR_DEPENDENCY" && evidence.vendor ? (
        <p className="text-sm">
          Depends on vendor <span className="font-medium">{evidence.vendor.name}</span>
        </p>
      ) : (
        <>
          <p className="mb-1 text-xs font-medium text-muted-foreground">
            Dependency path <span className="font-normal">(affected → used by service)</span>
          </p>
          <div className="flex flex-wrap items-center gap-1.5">
            {evidence.componentPath.map((component, index) => (
              <Fragment key={component.id}>
                {index > 0 && <ArrowRight className="size-3 shrink-0 text-muted-foreground" />}
                <span className="rounded border bg-background px-1.5 py-0.5 text-xs">
                  <span className="font-medium">{component.name}</span>{" "}
                  <span className="text-muted-foreground">{component.version}</span>
                </span>
              </Fragment>
            ))}
          </div>
          {evidence.affectedProduct && <AffectedProduct affected={evidence.affectedProduct} />}
        </>
      )}
      <p className="mt-2 text-xs text-muted-foreground">{evidence.explanation}</p>
    </div>
  );
}

function AffectedProduct({ affected }: { affected: AffectedProductEvidence }) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
      {(affected.vendor || affected.product) && (
        <span>
          CPE:{" "}
          <span className="text-foreground">
            {[affected.vendor, affected.product].filter(Boolean).join(" / ")}
          </span>
        </span>
      )}
      {affected.versionRange && (
        <span>
          affected <span className="font-mono text-foreground">{affected.versionRange}</span>
        </span>
      )}
      {affected.identityMatch && <MetaBadge>{IDENTITY_LABEL[affected.identityMatch]}</MetaBadge>}
      {affected.versionMatch && affected.versionMatch !== "IN_RANGE" && (
        <MetaBadge tone="warn">{VERSION_LABEL[affected.versionMatch]}</MetaBadge>
      )}
    </div>
  );
}

function MetaBadge({ children, tone }: { children: React.ReactNode; tone?: "warn" }) {
  const className =
    tone === "warn"
      ? "bg-amber-500/10 text-amber-600 dark:text-amber-400"
      : "bg-muted text-muted-foreground";
  return <span className={`rounded px-1.5 py-0.5 font-medium ${className}`}>{children}</span>;
}

function Stat({
  value,
  label,
  plural,
  trailing,
}: {
  value: number;
  label: string;
  plural: string;
  trailing: string;
}) {
  return (
    <span>
      <span className="font-semibold text-foreground">{value}</span> {value === 1 ? label : plural} {trailing}
    </span>
  );
}

function Shell({
  back,
  title,
  children,
}: {
  back: React.ReactNode;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      {back}
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      {children}
    </div>
  );
}

function StateCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
