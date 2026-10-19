"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import {
  RelationshipSection,
  type RelationshipConfig,
} from "@/components/app/inventory/relationship-section";
import { ApiError, directoryApi, inventoryApi } from "@/lib/api";
import { canComposeService, canWriteInventory } from "@/lib/roles";
import type {
  BusinessCriticality,
  BusinessService,
  DirectoryMember,
  Owner,
  ServiceExposure,
  SoftwareComponent,
  ThirdPartyVendor,
} from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";
import { ServiceRepository } from "@/components/app/inventory/service-repository";
import { RecommendedUpgrades } from "@/components/app/responses/upgrade-recommendations";

const CRITICALITY_CLASS: Record<BusinessCriticality, string> = {
  LOW: "bg-muted text-muted-foreground",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  HIGH: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  CRITICAL: "bg-red-500/10 text-red-600 dark:text-red-400",
};

const EXPOSURE_CLASS: Record<ServiceExposure, string> = {
  ISOLATED: "bg-muted text-muted-foreground",
  INTERNAL: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  INTERNET_FACING: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
};

const EXPOSURE_LABEL: Record<ServiceExposure, string> = {
  ISOLATED: "Isolated",
  INTERNAL: "Internal",
  INTERNET_FACING: "Internet-facing",
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

// Owners are organization members holding SERVICE_OWNER. Picker rows are directory members (id = their
// user id, passed to `attach`); linked rows are owner records (id = the owner row, passed to `detach`).
const ownersConfig: RelationshipConfig = {
  entityLabel: "owner",
  title: "Responsible service owners",
  addPlaceholder: "Assign a member with the Service Owner role…",
  emptyLabel: "No owners assigned yet.",
  note: "Members holding the Service Owner role. An owner can keep this service's SBOM, components, and vendors up to date; its criticality, exposure, and owners are set by an analyst or compliance admin.",
  noOptionsLabel: "No available members hold the Service Owner role — grant it under Users.",
  listLinked: (serviceId, page, size) => inventoryApi.listServiceOwners(serviceId, { page, size }),
  searchOptions: async (serviceId, term, size) => {
    const [members, assigned] = await Promise.all([
      directoryApi.listMembers({ role: "SERVICE_OWNER", search: term || undefined, size }),
      inventoryApi.listServiceOwners(serviceId, { size: 100 }),
    ]);
    const taken = new Set(assigned.content.map((owner) => owner.userId));
    return members.content.filter((member) => member.enabled && !taken.has(member.id));
  },
  attach: async (serviceId, userId) => {
    await inventoryApi.assignServiceOwner(serviceId, userId);
  },
  detach: (serviceId, ownerId) => inventoryApi.removeServiceOwner(serviceId, ownerId),
  optionLabel: (row) => {
    const member = row as DirectoryMember;
    return member.displayName ? `${member.displayName} · ${member.email}` : member.email;
  },
  renderRow: (row) => {
    const owner = row as Owner;
    return (
      <span className="flex flex-wrap items-baseline gap-x-2 text-sm">
        <span className="font-medium">{owner.name}</span>
        {owner.email && <span className="text-muted-foreground">{owner.email}</span>}
      </span>
    );
  },
};

type Status = "loading" | "ok" | "forbidden" | "notfound" | "error";

export function ServiceDetailView({ serviceId }: { serviceId: string }) {
  const { user, isLoading: authLoading } = useAuth();
  // Governance (analyst / compliance admin) registers and re-grades services and assigns owners; an
  // assigned owner may keep the service's composition current. The backend enforces both — these only
  // decide which controls to show (see lib/roles).
  const canGovern = canWriteInventory(user?.roles ?? []);
  const [isAssignedOwner, setIsAssignedOwner] = useState(false);
  const canCompose = canComposeService(user?.roles ?? [], isAssignedOwner);

  const [service, setService] = useState<BusinessService | null>(null);
  const [status, setStatus] = useState<Status>("loading");
  const [componentsRefresh, setComponentsRefresh] = useState(0);

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

  // Only a SERVICE_OWNER outside the governance roles needs to know whether they own *this* service.
  useEffect(() => {
    if (authLoading || !user || canGovern || !user.roles.includes("SERVICE_OWNER")) return;
    const controller = new AbortController();
    inventoryApi
      .listServiceOwners(serviceId, { size: 100 })
      .then((owners) => {
        if (!controller.signal.aborted) {
          setIsAssignedOwner(owners.content.some((owner) => owner.userId === user.id));
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setIsAssignedOwner(false);
      });
    return () => controller.abort();
  }, [authLoading, user, canGovern, serviceId]);

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
          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${EXPOSURE_CLASS[service.exposure]}`}
          >
            {EXPOSURE_LABEL[service.exposure]}
          </span>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          What this service is made of — its components and vendors — and who is accountable for it.
        </p>
        {!canGovern && isAssignedOwner && (
          <p className="mt-1 text-xs text-muted-foreground">
            You own this service, so you can keep its SBOM, components, and vendors current. Its
            criticality, exposure, and owners are set by an analyst or compliance admin.
          </p>
        )}
      </div>

      <ServiceRepository
        key={`${service.id}:${user?.orgId}`}
        service={service}
        canCompose={canCompose}
        onChange={setService}
        onImported={() => setComponentsRefresh((n) => n + 1)}
      />

      <RecommendedUpgrades
        serviceId={service.id}
        enabled
        orgId={user?.orgId ?? null}
        refreshSignal={componentsRefresh}
        importScan={service.importScan}
      />

      <RelationshipSection
        serviceId={serviceId}
        canWrite={canCompose}
        config={componentsConfig}
        refreshSignal={componentsRefresh}
      />
      <RelationshipSection serviceId={serviceId} canWrite={canCompose} config={vendorsConfig} />
      <RelationshipSection serviceId={serviceId} canWrite={canGovern} config={ownersConfig} />

      <p className="text-xs text-muted-foreground">
        Which other services use one of these? Open <span className="font-medium">Used by</span> on its row in{" "}
        <Link href="/inventory/components" className="underline underline-offset-4 hover:text-foreground">
          Components
        </Link>{" "}
        or{" "}
        <Link href="/inventory/vendors" className="underline underline-offset-4 hover:text-foreground">
          Vendors
        </Link>
        .
      </p>
    </div>
  );
}
