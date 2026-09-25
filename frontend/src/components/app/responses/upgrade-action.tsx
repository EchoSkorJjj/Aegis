"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { GitPullRequest } from "lucide-react";
import { toast } from "sonner";

import { UpgradeStatusBadge } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ApiError, upgradeApi } from "@/lib/api";
import type { EvidenceChain, MatchedComponentRef, UpgradeProposal } from "@/lib/types";

/**
 * The manifests aegis-core can edit (its `ManifestEditor.forEcosystem`), with the file each defaults
 * to. Only decides whether to offer the button; the backend refuses anything else regardless.
 */
const DEFAULT_MANIFEST: Record<string, string> = {
  maven: "pom.xml",
  npm: "package.json",
  pypi: "requirements.txt",
  pip: "requirements.txt",
};

/** An upgrade the impact page can offer for one evidence chain. */
export type UpgradeCandidate = {
  /** The vulnerable component: what the upgrade moves. */
  component: MatchedComponentRef;
  /** What the service depends on directly, when that isn't the vulnerable component itself. */
  via: MatchedComponentRef | null;
};

/**
 * Which of a service's evidence chains carry an upgrade row, aligned with `evidence`: one row per
 * vulnerable component — on its direct chain when the service uses it directly (the chain aegis-core
 * drafts from), otherwise on its first transitive chain.
 */
export function upgradeCandidates(evidence: EvidenceChain[]): (UpgradeCandidate | null)[] {
  const usedDirectly = new Set(
    evidence
      .filter((chain) => chain.kind === "ADVISORY_COMPONENT" && chain.componentPath.length === 1)
      .map((chain) => chain.componentPath[0].id),
  );
  const offered = new Set<string>();
  return evidence.map((chain) => {
    if (chain.kind !== "ADVISORY_COMPONENT" || chain.componentPath.length === 0) return null;
    const component = chain.componentPath[0];
    const direct = chain.componentPath.length === 1;
    if (offered.has(component.id) || (!direct && usedDirectly.has(component.id))) return null;
    offered.add(component.id);
    return { component, via: direct ? null : chain.componentPath[chain.componentPath.length - 1] };
  });
}

export function upgradeKey(serviceId: string, componentId: string): string {
  return `${serviceId}:${componentId}`;
}

/**
 * The upgrade proposals already drafted for one change and not dismissed, keyed by `upgradeKey` — so
 * the impact page links to a proposal instead of offering to draft it again. Null while loading.
 */
export function useUpgradeProposals(changeSignalId: string, enabled: boolean): Map<string, UpgradeProposal> | null {
  const [proposals, setProposals] = useState<Map<string, UpgradeProposal> | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setProposals(null);
    upgradeApi
      .list({ changeSignalId, status: ["PROPOSED", "OPENING", "OPENED", "FAILED"], size: 100 })
      .then((page) => {
        if (cancelled) return;
        // Newest first, so the first proposal for a service and component is the one to show.
        const byKey = new Map<string, UpgradeProposal>();
        for (const proposal of page.content) {
          const key = upgradeKey(proposal.serviceId, proposal.componentId);
          if (!byKey.has(key)) byKey.set(key, proposal);
        }
        setProposals(byKey);
      })
      .catch(() => {
        // Offer drafting anyway; the backend refuses a duplicate with a link to the live proposal.
        if (!cancelled) setProposals(new Map());
      });
    return () => {
      cancelled = true;
    };
  }, [changeSignalId, enabled]);

  return proposals;
}

/**
 * The upgrade row under one evidence chain: the proposal already drafted for it, or — for someone who
 * may draft one (an analyst, or this service's owners) — the draft button, or why it can't be automated.
 */
export function UpgradeAction({
  changeSignalId,
  serviceId,
  candidate,
  existing,
  canDraft,
}: {
  changeSignalId: string;
  serviceId: string;
  candidate: UpgradeCandidate;
  existing: UpgradeProposal | undefined;
  canDraft: boolean;
}) {
  if (existing) {
    return (
      <div className="mt-2 flex flex-wrap items-center gap-2 border-t pt-2 text-xs">
        <UpgradeStatusBadge status={existing.status} />
        <span className="text-muted-foreground">
          Upgrade to <span className="font-mono text-foreground">{existing.toVersion}</span>
        </span>
        <Link
          href={`/responses/upgrades/${existing.id}`}
          className="font-medium text-primary underline-offset-4 hover:underline"
        >
          View proposal →
        </Link>
      </div>
    );
  }
  const { component, via } = candidate;
  if (!canDraft) {
    // Say who can, rather than showing nothing where a direct dependency could be upgraded.
    return via ? null : <Note>An analyst or this service&apos;s owner can draft the upgrade.</Note>;
  }
  if (via) {
    return (
      <Note>
        {component.name} reaches this service through {via.name}. Transitive upgrades aren&apos;t automated
        yet: upgrade {via.name}, or pin {component.name} by hand.
      </Note>
    );
  }
  const manifest = DEFAULT_MANIFEST[component.ecosystem.trim().toLowerCase()];
  if (!manifest) {
    return (
      <Note>
        Automated upgrades support Maven, npm and PyPI manifests; upgrade this {component.ecosystem} package by
        hand.
      </Note>
    );
  }
  return (
    <DraftUpgradeButton
      changeSignalId={changeSignalId}
      serviceId={serviceId}
      component={component}
      defaultManifest={manifest}
    />
  );
}

function DraftUpgradeButton({
  changeSignalId,
  serviceId,
  component,
  defaultManifest,
}: {
  changeSignalId: string;
  serviceId: string;
  component: MatchedComponentRef;
  defaultManifest: string;
}) {
  const router = useRouter();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [manifestPath, setManifestPath] = useState("");
  const [drafting, setDrafting] = useState(false);

  async function draft(event: React.FormEvent) {
    event.preventDefault();
    setDrafting(true);
    try {
      const proposal = await upgradeApi.propose({
        changeSignalId,
        serviceId,
        componentId: component.id,
        manifestPath: manifestPath.trim() || undefined,
      });
      toast.success("Upgrade drafted — review it, then approve or dismiss it");
      router.push(`/responses/upgrades/${proposal.id}`);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not draft the upgrade.");
      setDrafting(false);
    }
  }

  return (
    <div className="mt-2 border-t pt-2">
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger render={<Button size="sm" variant="outline" disabled={drafting} />}>
          <GitPullRequest />
          {drafting ? "Drafting…" : "Draft upgrade PR"}
        </PopoverTrigger>
        <PopoverContent align="start" className="w-96">
          <form onSubmit={draft} className="space-y-3 text-sm">
            <p className="font-medium">Draft an upgrade pull request</p>
            <p className="text-muted-foreground">
              Aegis picks the lowest {component.name} version clear of this advisory and every other one naming
              the package, and drafts the pull request for review. Nothing is opened until someone approves it.
            </p>
            <div className="space-y-1">
              <label htmlFor={`${id}-manifest`} className="text-xs font-medium">
                Manifest path
              </label>
              <Input
                id={`${id}-manifest`}
                maxLength={400}
                placeholder={defaultManifest}
                value={manifestPath}
                disabled={drafting}
                onChange={(event) => setManifestPath(event.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                In the service&apos;s linked repository. Leave blank for the manifest the SBOM import found
                ({defaultManifest} at the root if it found none).
              </p>
            </div>
            <Button type="submit" className="w-full" disabled={drafting}>
              {drafting ? "Drafting…" : "Draft proposal"}
            </Button>
          </form>
        </PopoverContent>
      </Popover>
    </div>
  );
}

function Note({ children }: { children: React.ReactNode }) {
  return <p className="mt-2 border-t pt-2 text-xs text-muted-foreground">{children}</p>;
}
