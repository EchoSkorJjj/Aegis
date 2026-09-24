"use client";

import { Fragment, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ArrowRight, Info } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ApiError, matchApi, scoringApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type {
  AffectedProductEvidence,
  BusinessCriticality,
  ChangeImpactResponse,
  ChangeMatchResponse,
  EvidenceChain,
  IdentityMatch,
  ImpactedServiceMatch,
  ImpactTier,
  MatchConfidence,
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
  CRITICALITY: "Criticality",
  CONFIDENCE: "Confidence",
};

export function ChangeImpactView({ signalId }: { signalId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);

  const [data, setData] = useState<ChangeMatchResponse | null>(null);
  // The score is an enrichment of the match: if it fails, the evidence still renders without it.
  const [impact, setImpact] = useState<ChangeImpactResponse | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setStatus("loading");
    Promise.allSettled([matchApi.matchChange(signalId), scoringApi.scoreChange(signalId)]).then(
      ([match, score]) => {
        if (cancelled) return;
        if (match.status === "rejected") {
          const err = match.reason;
          if (err instanceof ApiError && err.status === 403) setStatus("forbidden");
          else if (err instanceof ApiError && err.status === 404) setStatus("notfound");
          else if (err instanceof ApiError && err.status === 400) setStatus("unmatchable");
          else setStatus("error");
          return;
        }
        setData(match.value);
        setImpact(score.status === "fulfilled" ? score.value : null);
        setStatus("ok");
      },
    );
    return () => {
      cancelled = true;
    };
  }, [authLoading, canRead, user?.orgId, signalId]);

  const backLink = (
    <Link
      href="/advisories"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      Advisories
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

  const { change, matchedComponentCount, matchedVendorCount } = data;
  const type = SIGNAL_TYPE[change.signalType];
  const needsReview = data.impactedServices.filter((s) => s.confidence === "POSSIBLE").length;
  // Scored services arrive highest first — a work queue — so order the cards the same way.
  const scored = impact?.services ?? [];
  const scoreById = new Map<string, ScoredService>(scored.map((row) => [row.service.id, row]));
  const rank = new Map<string, number>(scored.map((row, index) => [row.service.id, index]));
  const unranked = scored.length;
  const impactedServices = [...data.impactedServices].sort(
    (a, b) => (rank.get(a.service.id) ?? unranked) - (rank.get(b.service.id) ?? unranked),
  );

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

      {/* Summary bar */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border bg-muted/30 px-4 py-3 text-sm">
        {change.signalType === "VENDOR_OUTAGE" ? (
          <Stat value={matchedVendorCount} label="vendor" plural="vendors" trailing="matched" />
        ) : (
          <Stat value={matchedComponentCount} label="component" plural="components" trailing="matched" />
        )}
        <span className="text-muted-foreground/40">·</span>
        <Stat value={impactedServices.length} label="service" plural="services" trailing="impacted" />
        {needsReview > 0 && (
          <>
            <span className="text-muted-foreground/40">·</span>
            <span className="font-medium text-amber-600 dark:text-amber-400">{needsReview} need review</span>
          </>
        )}
        <span className="text-muted-foreground/40">·</span>
        {impact ? (
          <span>
            <span className="text-muted-foreground">Severity</span>{" "}
            <span className="font-semibold text-foreground">{describeSeverity(impact.severity)}</span>
          </span>
        ) : (
          <span className="text-muted-foreground">Impact scores unavailable</span>
        )}
      </div>

      {impactedServices.length === 0 ? (
        <StateCard>
          {matchedComponentCount === 0 && matchedVendorCount === 0
            ? "Nothing in your inventory matches this change."
            : "The change matches your inventory, but no business service depends on the affected components or vendor."}
        </StateCard>
      ) : (
        <div className="space-y-4">
          <h2 className="text-sm font-medium text-muted-foreground">
            Impacted services ({impactedServices.length})
          </h2>
          {impactedServices.map((match) => (
            <ServiceCard key={match.service.id} impact={match} scored={scoreById.get(match.service.id)} />
          ))}
        </div>
      )}
    </div>
  );
}

function ServiceCard({ impact, scored }: { impact: ImpactedServiceMatch; scored?: ScoredService }) {
  const confidence = CONFIDENCE[impact.confidence];
  return (
    <div className="rounded-lg border">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b px-4 py-3">
        {scored && <ScoreBadge score={scored.score} tier={scored.tier} />}
        <span className="font-medium">{impact.service.name}</span>
        <span
          className={`rounded-full px-2 py-0.5 text-xs font-medium ${CRITICALITY_CLASS[impact.service.criticality]}`}
        >
          {impact.service.criticality}
        </span>
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${confidence.className}`}>
          {confidence.label}
        </span>
        <span className="ml-auto text-xs text-muted-foreground">
          {impact.owners.length > 0
            ? impact.owners.map((owner) => owner.name).join(", ")
            : "No owners assigned"}
        </span>
      </div>
      <div className="space-y-3 p-4">
        {scored && <WhyThisScore scored={scored} />}
        {impact.evidence.map((evidence, index) => (
          <Evidence key={index} evidence={evidence} />
        ))}
      </div>
    </div>
  );
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
  // backend leaves it out of the explanation sentence.
  const factors = scored.factors.filter((factor) => factor.factor !== "CONFIDENCE" || factor.weight !== 1);
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
          <FactorRow key={factor.factor} factor={factor} />
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

function FactorRow({ factor }: { factor: ScoreFactor }) {
  return (
    <li className="flex items-center gap-3">
      <div className="flex w-24 shrink-0 items-center gap-1 text-sm font-medium">
        {FACTOR_LABEL[factor.factor]}
        <SettingHint factor={factor} />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2 text-xs">
          <span className="truncate text-muted-foreground">{describeInput(factor)}</span>
          <span className="font-mono tabular-nums">×{factor.weight.toFixed(2)}</span>
        </div>
        <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-muted">
          <div
            className={`h-full rounded-full ${factor.limiting ? "bg-amber-500" : "bg-foreground/50"}`}
            style={{ width: `${Math.round(factor.weight * 100)}%` }}
          />
        </div>
      </div>
      <div className="w-20 shrink-0 text-right text-xs tabular-nums">
        {factor.headroom > 0 ? (
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
function SettingHint({ factor }: { factor: ScoreFactor }) {
  const text = factor.setting
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

/** Enum inputs ("INTERNET_FACING") read as words; severity inputs are already prose ("CVSS 3.1 base 9.8"). */
function describeInput(factor: ScoreFactor) {
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
