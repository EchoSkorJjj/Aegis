"use client";

import { Fragment, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, ArrowRight } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, matchApi } from "@/lib/api";
import { canReadInventory } from "@/lib/roles";
import type {
  AffectedProductEvidence,
  BusinessCriticality,
  ChangeMatchResponse,
  EvidenceChain,
  IdentityMatch,
  ImpactedServiceMatch,
  MatchConfidence,
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

export function ChangeImpactView({ signalId }: { signalId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  const canRead = canReadInventory(user?.roles ?? []);

  const [data, setData] = useState<ChangeMatchResponse | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    if (authLoading || !canRead) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setStatus("loading");
    matchApi
      .matchChange(signalId)
      .then((result) => {
        if (cancelled) return;
        setData(result);
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

  const { change, impactedServices, matchedComponentCount, matchedVendorCount } = data;
  const type = SIGNAL_TYPE[change.signalType];
  const needsReview = impactedServices.filter((s) => s.confidence === "POSSIBLE").length;

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
          Impact of this change on your organization&apos;s estate, with the evidence for each hit.
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
          {impactedServices.map((impact) => (
            <ServiceCard key={impact.service.id} impact={impact} />
          ))}
        </div>
      )}
    </div>
  );
}

function ServiceCard({ impact }: { impact: ImpactedServiceMatch }) {
  const confidence = CONFIDENCE[impact.confidence];
  return (
    <div className="rounded-lg border">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b px-4 py-3">
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
        {impact.evidence.map((evidence, index) => (
          <Evidence key={index} evidence={evidence} />
        ))}
      </div>
    </div>
  );
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
