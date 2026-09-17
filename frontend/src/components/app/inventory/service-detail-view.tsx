"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Upload } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  RelationshipSection,
  type RelationshipConfig,
} from "@/components/app/inventory/relationship-section";
import { ApiError, inventoryApi } from "@/lib/api";
import { canWriteInventory } from "@/lib/roles";
import type {
  BusinessCriticality,
  BusinessService,
  Owner,
  SoftwareComponent,
  ThirdPartyVendor,
} from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

const CRITICALITY_CLASS: Record<BusinessCriticality, string> = {
  LOW: "bg-muted text-muted-foreground",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
};

// Module-level configs → stable identities, safe as effect deps in RelationshipSection. Each pairs
// its loaders with its renderers, casting the erased `{ id }` row back to the concrete type.
const componentsConfig: RelationshipConfig = {
  entityLabel: "component",
  title: "Components",
  addPlaceholder: "Link an existing component…",
  emptyLabel: "No components linked yet — import an SBOM or link one above.",
  note: "Direct components only. An SBOM's transitive dependencies aren't listed here — they live in Components and are reached through the dependency map.",
  listLinked: (serviceId, page, size) => inventoryApi.listServiceComponents(serviceId, { page, size }),
  searchOptions: async (serviceId, term, size) =>
    (await inventoryApi.listComponents({ search: term, size, excludeLinkedToService: serviceId })).content,
  attach: (serviceId, id) => inventoryApi.addServiceComponent(serviceId, id),
  detach: (serviceId, id) => inventoryApi.removeServiceComponent(serviceId, id),
  optionLabel: (row) => {
    const component = row as SoftwareComponent;
    return `${component.ecosystem} · ${component.name} ${component.version}`;
  },
  renderRow: (row) => {
    const component = row as SoftwareComponent;
    return (
      <span className="flex flex-wrap items-baseline gap-x-2 text-sm">
        <span className="rounded bg-muted px-1.5 py-0.5 text-xs font-medium text-muted-foreground">
          {component.ecosystem}
        </span>
        <span className="font-medium">{component.name}</span>
        <span className="text-muted-foreground">{component.version}</span>
      </span>
    );
  },
};

const vendorsConfig: RelationshipConfig = {
  entityLabel: "vendor",
  title: "Vendors",
  addPlaceholder: "Link an existing vendor…",
  emptyLabel: "No vendors linked yet.",
  listLinked: (serviceId, page, size) => inventoryApi.listServiceVendors(serviceId, { page, size }),
  searchOptions: async (serviceId, term, size) =>
    (await inventoryApi.listVendors({ search: term, size, excludeLinkedToService: serviceId })).content,
  attach: (serviceId, id) => inventoryApi.addServiceVendor(serviceId, id),
  detach: (serviceId, id) => inventoryApi.removeServiceVendor(serviceId, id),
  optionLabel: (row) => (row as ThirdPartyVendor).name,
  renderRow: (row) => <span className="text-sm font-medium">{(row as ThirdPartyVendor).name}</span>,
};

const ownersConfig: RelationshipConfig = {
  entityLabel: "owner",
  title: "Owners",
  addPlaceholder: "Link an existing owner…",
  emptyLabel: "No owners linked yet.",
  listLinked: (serviceId, page, size) => inventoryApi.listServiceOwners(serviceId, { page, size }),
  searchOptions: async (serviceId, term, size) =>
    (await inventoryApi.listOwners({ search: term, size, excludeLinkedToService: serviceId })).content,
  attach: (serviceId, id) => inventoryApi.addServiceOwner(serviceId, id),
  detach: (serviceId, id) => inventoryApi.removeServiceOwner(serviceId, id),
  optionLabel: (row) => (row as Owner).name,
  renderRow: (row) => <span className="text-sm font-medium">{(row as Owner).name}</span>,
};

type Status = "loading" | "ok" | "forbidden" | "notfound" | "error";

export function ServiceDetailView({ serviceId }: { serviceId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  const canWrite = canWriteInventory(user?.roles ?? []);

  const [service, setService] = useState<BusinessService | null>(null);
  const [status, setStatus] = useState<Status>("loading");
  const [componentsRefresh, setComponentsRefresh] = useState(0);
  const [importing, setImporting] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (authLoading) return;
    const controller = new AbortController();
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setStatus("loading");
    inventoryApi
      .getService(serviceId)
      .then((loaded) => {
        if (controller.signal.aborted) return;
        setService(loaded);
        setStatus("ok");
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        if (err instanceof ApiError && err.status === 403) setStatus("forbidden");
        else if (err instanceof ApiError && err.status === 404) setStatus("notfound");
        else setStatus("error");
      });
    return () => controller.abort();
  }, [authLoading, serviceId, user?.orgId]);

  async function onFileSelected(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // Clear the input so re-selecting the same file fires onChange again.
    if (fileRef.current) fileRef.current.value = "";
    if (!file) return;

    let text: string;
    try {
      text = await file.text();
    } catch {
      toast.error("Could not read that file.");
      return;
    }
    try {
      JSON.parse(text);
    } catch {
      toast.error("That file isn't valid JSON.");
      return;
    }

    setImporting(true);
    try {
      const result = await inventoryApi.importSbom(serviceId, text);
      toast.success(
        `SBOM imported — ${result.componentsCreated} new, ${result.componentsMatched} matched, ` +
          `${result.serviceComponentsLinked} linked, ${result.dependenciesCreated} dependencies` +
          (result.componentsSkipped ? `, ${result.componentsSkipped} skipped (no version)` : "") +
          ".",
      );
      setComponentsRefresh((n) => n + 1);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "SBOM import failed — please try again.");
    } finally {
      setImporting(false);
    }
  }

  const backLink = (
    <Link
      href="/inventory/services"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" />
      Services
    </Link>
  );

  if (status === "loading") {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        {backLink}
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (status !== "ok" || !service) {
    const message =
      status === "forbidden"
        ? "You don't have access to this service — an Aegis domain role is required."
        : status === "notfound"
          ? "This service doesn't exist in your active organization."
          : "Could not load this service. Please try again.";
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        {backLink}
        <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
          {message}
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div>
        {backLink}
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold tracking-tight">{service.name}</h1>
          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${CRITICALITY_CLASS[service.criticality]}`}
          >
            {service.criticality}
          </span>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          Compose this service from the components, vendors, and owners that make it up.
        </p>
      </div>

      {canWrite && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
          <div>
            <p className="text-sm font-medium">Import from SBOM</p>
            <p className="text-xs text-muted-foreground">
              Upload a CycloneDX JSON SBOM to create this service&apos;s components and their
              dependency edges. Import is additive and idempotent.
            </p>
          </div>
          <Button disabled={importing} onClick={() => fileRef.current?.click()}>
            <Upload />
            {importing ? "Importing…" : "Upload CycloneDX JSON"}
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept=".json,application/json,application/vnd.cyclonedx+json"
            hidden
            onChange={onFileSelected}
          />
        </div>
      )}

      <RelationshipSection
        serviceId={serviceId}
        canWrite={canWrite}
        config={componentsConfig}
        refreshSignal={componentsRefresh}
      />
      <RelationshipSection serviceId={serviceId} canWrite={canWrite} config={vendorsConfig} />
      <RelationshipSection serviceId={serviceId} canWrite={canWrite} config={ownersConfig} />

      <p className="text-xs text-muted-foreground">
        Wired up? Trace the blast radius on the{" "}
        <Link href="/inventory/map" className="underline underline-offset-4 hover:text-foreground">
          dependency map
        </Link>
        .
      </p>
    </div>
  );
}
