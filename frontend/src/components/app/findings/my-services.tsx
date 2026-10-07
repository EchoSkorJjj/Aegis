"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";

import { TriageList } from "@/components/app/findings/finding-queue";
import { formatDateTime } from "@/components/app/responses/response-status";
import { ImpactBadge } from "@/components/app/responses/upgrade-recommendations";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { findingApi } from "@/lib/api";
import type { BusinessCriticality, OwnedService, ServiceExposure } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

const CRITICALITY_LABEL: Record<BusinessCriticality, string> = {
  LOW: "Low",
  MEDIUM: "Medium",
  HIGH: "High",
  CRITICAL: "Critical",
};

const EXPOSURE_LABEL: Record<ServiceExposure, string> = {
  ISOLATED: "Isolated",
  INTERNAL: "Internal",
  INTERNET_FACING: "Internet-facing",
};

const WARNING = "text-amber-600 dark:text-amber-400";

/**
 * My services (E9 story 2): a service owner's own page. Each service they are an assigned owner of, with what it
 * needs from them — its items in the triage queue, the responses for it waiting for a decision, and whether Aegis
 * has its current dependencies, which only its owners (or governance) keep up — then those items, worst first.
 * Only a service owner can be assigned a service, so the sidebar shows it to them alone, and anyone else who
 * opens it is pointed to the triage queue.
 */
export function MyServices() {
  const { user, isLoading } = useAuth();
  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (!user?.roles.includes("SERVICE_OWNER")) {
    return (
      <Notice>
        My services is a service owner&apos;s page: the services they&apos;re the assigned owner of, and what each needs
        from them. You don&apos;t hold the Service Owner role in this organization, so the{" "}
        <Link href="/findings" className="underline underline-offset-4 hover:text-foreground">
          triage queue
        </Link>{" "}
        is where to look.
      </Notice>
    );
  }
  // Another workspace or user starts over.
  return <Owned key={`${user.orgId}:${user.id}`} />;
}

function Owned() {
  const [services, setServices] = useState<OwnedService[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [refresh, setRefresh] = useState(0);
  // The service "Show them" picked for the list below; a new pick starts the list over on it.
  const [focus, setFocus] = useState({ serviceId: "", pick: 0 });
  const list = useRef<HTMLElement>(null);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new request
    setFailed(false);
    findingApi
      .myServices()
      .then((result) => {
        if (!cancelled) setServices(result);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  function show(serviceId: string) {
    setFocus((current) => ({ serviceId, pick: current.pick + 1 }));
    list.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">My services</h1>
        <p className="text-sm text-muted-foreground">
          The services you&apos;re the assigned owner of, and what each needs from you: what to respond to, the
          responses waiting for a decision, and the SBOM and repository Aegis needs to see what each depends on.
        </p>
      </div>

      {failed ? (
        <Notice>
          Could not load your services.{" "}
          <Button variant="link" className="h-auto p-0" onClick={() => setRefresh((count) => count + 1)}>
            Try again
          </Button>
        </Notice>
      ) : services === null ? (
        <Skeleton className="h-32 w-full" />
      ) : services.length === 0 ? (
        <Notice>
          You aren&apos;t assigned as the owner of any service yet. An analyst or compliance admin assigns owners on
          each service&apos;s page; until then, the{" "}
          <Link href="/findings" className="underline underline-offset-4 hover:text-foreground">
            triage queue
          </Link>{" "}
          covers every service.
        </Notice>
      ) : (
        <>
          <section aria-label="Your services" className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Service</TableHead>
                  <TableHead>To respond to</TableHead>
                  <TableHead>Awaiting a decision</TableHead>
                  <TableHead>Dependencies</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {services.map((owned) => (
                  <OwnedRow key={owned.service.id} owned={owned} onShow={() => show(owned.service.id)} />
                ))}
              </TableBody>
            </Table>
          </section>

          <section ref={list} aria-labelledby="my-items-heading" className="scroll-mt-4 space-y-3">
            <h2 id="my-items-heading" className="text-sm font-medium">
              To respond to in your services
            </h2>
            <TriageList
              key={focus.pick}
              mine
              initialServiceId={focus.serviceId}
              onRefresh={() => setRefresh((count) => count + 1)}
            />
          </section>
        </>
      )}
    </div>
  );
}

function OwnedRow({ owned, onShow }: { owned: OwnedService; onShow: () => void }) {
  const { service } = owned;
  const incomplete = !owned.sbomImportedAt || !owned.repositoryUrl;
  return (
    <TableRow>
      <TableCell className="align-top whitespace-normal">
        <Link href={`/inventory/services/${service.id}`} className="font-medium underline-offset-4 hover:underline">
          {service.name}
        </Link>
        <span className="mt-0.5 block text-xs text-muted-foreground">
          {CRITICALITY_LABEL[service.criticality]} criticality · {EXPOSURE_LABEL[service.exposure]}
        </span>
      </TableCell>
      <TableCell className="align-top whitespace-normal">
        {owned.toRespond > 0 && owned.topScore !== null && owned.topTier !== null ? (
          <>
            <ImpactBadge score={owned.topScore} tier={owned.topTier} />
            <span className="mt-1 block text-xs text-muted-foreground">
              {count(owned.toRespond, "item")}
              {owned.noResponseYet > 0 && ` · ${owned.noResponseYet} with no response yet`}
            </span>
            <Button variant="link" size="xs" className="h-auto px-0" onClick={onShow}>
              Show them ↓
            </Button>
          </>
        ) : (
          <span className="text-xs text-muted-foreground">Nothing to respond to</span>
        )}
      </TableCell>
      <TableCell className="align-top whitespace-normal">
        {owned.awaitingDecision > 0 ? (
          <Link
            href="/responses/review"
            className="text-sm font-medium text-primary underline-offset-4 hover:underline"
          >
            {count(owned.awaitingDecision, "response")} →
          </Link>
        ) : (
          <span className="text-xs text-muted-foreground">None</span>
        )}
      </TableCell>
      <TableCell className="max-w-sm align-top text-xs whitespace-normal text-muted-foreground">
        {owned.sbomImportedAt ? (
          <span className="block">
            SBOM {owned.sbomImportSource === "UPLOAD" ? "uploaded" : "imported from its repository"}{" "}
            {formatDateTime(owned.sbomImportedAt)}
          </span>
        ) : (
          <span className={`block ${WARNING}`}>No SBOM imported yet: only components linked by hand are checked.</span>
        )}
        {owned.repositoryUrl ? (
          <span className="block font-mono">{owned.repositoryUrl.replace(/^https?:\/\//i, "")}</span>
        ) : (
          <span className={`block ${WARNING}`}>
            No repository linked: a response is the ticket alone, with no upgrade pull request.
          </span>
        )}
        {incomplete && (
          <Link
            href={`/inventory/services/${service.id}`}
            className="mt-1 inline-block font-medium text-primary underline-offset-4 hover:underline"
          >
            Set it up on the service&apos;s page →
          </Link>
        )}
      </TableCell>
    </TableRow>
  );
}

function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? "" : "s"}`;
}

function Notice({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-6xl rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
