"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError, inventoryApi } from "@/lib/api";
import type {
  BusinessCriticality,
  BusinessService,
  Owner,
  SoftwareComponent,
  ThirdPartyVendor,
} from "@/lib/types";

/**
 * "Used by" on the Components and Vendors pages: who uses the item, and how. A component's lookup covers its whole
 * package — every version in the organization — and each service's own dependency graph, the same one a
 * vulnerability's impact is worked out with.
 */

const CRITICALITY_CLASS: Record<BusinessCriticality, string> = {
  LOW: "bg-muted text-muted-foreground",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
};

/** Every service using any version of this component's package: directly, or through which component. */
export function ComponentUsedBy({ component }: { component: SoftwareComponent }) {
  const usage = useLookup(
    () => inventoryApi.packageUsage(component.ecosystem, component.name),
    `${component.ecosystem}:${component.name}`,
  );
  if (usage.state === "loading") return <Loading />;
  if (usage.state === "failed") return <Failed message={usage.message} />;

  const { uses } = usage.value;
  if (uses.length === 0) {
    return <Summary>No service uses any version of {component.name}.</Summary>;
  }
  const services = new Set(uses.map((use) => use.service.id)).size;
  const versions = new Set(uses.map((use) => use.component.version)).size;
  return (
    <div className="space-y-2">
      <Summary>
        {count(services, "service")} {services === 1 ? "uses" : "use"} {component.name}, in{" "}
        {count(versions, "version")}. Every version is listed; {component.version} is in bold.
      </Summary>
      <Table aria-label={`Services using ${component.name}`} className="text-xs">
        <TableHeader>
          <TableRow>
            <TableHead>Service</TableHead>
            <TableHead>Version</TableHead>
            <TableHead>How</TableHead>
            <TableHead>Owners</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {uses.map((use) => (
            <TableRow key={`${use.service.id}:${use.component.id}`}>
              <TableCell>
                <ServiceName service={use.service} />
              </TableCell>
              <TableCell className={use.component.id === component.id ? "font-semibold" : "text-muted-foreground"}>
                {use.component.version}
              </TableCell>
              <TableCell className="whitespace-normal">
                {use.direct ? (
                  <Direct />
                ) : (
                  <span>
                    Through{" "}
                    <span className="font-mono">
                      {use.through.map((step) => `${step.name} ${step.version}`).join(" › ")}
                    </span>
                  </span>
                )}
              </TableCell>
              <TableCell className="whitespace-normal text-muted-foreground">
                <Owners owners={use.owners} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/** Every service using this vendor. Vendors are linked to services directly. */
export function VendorUsedBy({ vendor }: { vendor: ThirdPartyVendor }) {
  const usage = useLookup(() => inventoryApi.vendorUsage(vendor.id), vendor.id);
  if (usage.state === "loading") return <Loading />;
  if (usage.state === "failed") return <Failed message={usage.message} />;

  const { services } = usage.value;
  if (services.length === 0) {
    return <Summary>No service uses {vendor.name}.</Summary>;
  }
  return (
    <div className="space-y-2">
      <Summary>
        {count(services.length, "service")} {services.length === 1 ? "uses" : "use"} {vendor.name}.
      </Summary>
      <Table aria-label={`Services using ${vendor.name}`} className="text-xs">
        <TableHeader>
          <TableRow>
            <TableHead>Service</TableHead>
            <TableHead>How</TableHead>
            <TableHead>Owners</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {services.map((using) => (
            <TableRow key={using.service.id}>
              <TableCell>
                <ServiceName service={using.service} />
              </TableCell>
              <TableCell>
                <Direct />
              </TableCell>
              <TableCell className="whitespace-normal text-muted-foreground">
                <Owners owners={using.owners} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

type Lookup<T> = { state: "loading" } | { state: "failed"; message: string } | { state: "loaded"; value: T };

/** Loads once per key; a row's panel mounts when opened, so it asks when someone looks. */
function useLookup<T>(load: () => Promise<T>, key: string): Lookup<T> {
  const [result, setResult] = useState<{ key: string; lookup: Lookup<T> } | null>(null);

  useEffect(() => {
    let cancelled = false;
    load()
      .then((value) => {
        if (!cancelled) setResult({ key, lookup: { state: "loaded", value } });
      })
      .catch((err) => {
        if (!cancelled) {
          setResult({
            key,
            lookup: { state: "failed", message: err instanceof ApiError ? err.message : "Could not load who uses it." },
          });
        }
      });
    return () => {
      cancelled = true;
    };
    // `load` is a fresh closure every render; `key` names what it loads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  return result && result.key === key ? result.lookup : { state: "loading" };
}

function ServiceName({ service }: { service: BusinessService }) {
  return (
    <span className="inline-flex items-center gap-2">
      <Link
        href={`/inventory/services/${service.id}`}
        className="font-medium text-primary underline-offset-4 hover:underline"
      >
        {service.name}
      </Link>
      <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-medium ${CRITICALITY_CLASS[service.criticality]}`}>
        {service.criticality}
      </span>
    </span>
  );
}

function Direct() {
  return (
    <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 font-medium text-emerald-600 dark:text-emerald-400">
      Direct
    </span>
  );
}

function Owners({ owners }: { owners: Owner[] }) {
  return <>{owners.length > 0 ? owners.map((owner) => owner.name).join(", ") : "No owners assigned"}</>;
}

function Summary({ children }: { children: React.ReactNode }) {
  return <p className="text-xs text-muted-foreground">{children}</p>;
}

function Loading() {
  return (
    <div className="space-y-2" aria-busy="true">
      <Skeleton className="h-4 w-64" />
      <Skeleton className="h-8 w-full" />
    </div>
  );
}

function Failed({ message }: { message: string }) {
  return (
    <p role="alert" className="text-xs text-destructive">
      {message}
    </p>
  );
}

function count(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? "" : "s"}`;
}
