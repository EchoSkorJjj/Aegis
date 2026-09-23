"use client";

import { type FormEvent, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowLeft, GitBranch, Info, Loader2, Upload } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
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
  SbomImportResult,
  ServiceExposure,
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

/** One toast summary shared by both SBOM import paths (file upload and repo generation). */
function importedSummary(result: SbomImportResult): string {
  return (
    `SBOM imported — ${result.componentsCreated} new, ${result.componentsMatched} matched, ` +
    `${result.serviceComponentsLinked} linked, ${result.dependenciesCreated} dependencies` +
    (result.componentsSkipped ? `, ${result.componentsSkipped} skipped (no version)` : "") +
    "."
  );
}

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
  const [importing, setImporting] = useState(false);
  const [repoOpen, setRepoOpen] = useState(false);
  const [repoUrl, setRepoUrl] = useState("");
  const [repoRef, setRepoRef] = useState("");
  const [repoToken, setRepoToken] = useState("");
  const [repoImporting, setRepoImporting] = useState(false);
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
      toast.success(importedSummary(result));
      setComponentsRefresh((n) => n + 1);
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "SBOM import failed — please try again.");
    } finally {
      setImporting(false);
    }
  }

  async function importFromRepo(event: FormEvent) {
    event.preventDefault();
    const url = repoUrl.trim();
    if (!url) return;
    setRepoImporting(true);
    try {
      const result = await inventoryApi.generateSbomFromRepo(serviceId, url, repoRef, repoToken);
      toast.success(importedSummary(result));
      setComponentsRefresh((n) => n + 1);
      setRepoOpen(false);
      setRepoUrl("");
      setRepoRef("");
      setRepoToken("");
    } catch (err) {
      toast.error(
        err instanceof ApiError ? err.message : "Couldn't generate an SBOM from that repository.",
      );
    } finally {
      setRepoImporting(false);
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

      {canCompose && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
          <div className="min-w-0">
            <p className="text-sm font-medium">Import from SBOM</p>
            <p className="text-xs text-muted-foreground">
              Upload a CycloneDX JSON, or generate one straight from a Git repository. Import is
              additive and idempotent.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button disabled={importing} onClick={() => fileRef.current?.click()}>
              <Upload />
              {importing ? "Importing…" : "Upload CycloneDX JSON"}
            </Button>
            <Popover open={repoOpen} onOpenChange={setRepoOpen}>
              <PopoverTrigger
                render={
                  <Button variant="outline">
                    <GitBranch />
                    Import from GitHub
                  </Button>
                }
              />
              <PopoverContent align="end" className="w-80">
                <form className="space-y-3" onSubmit={importFromRepo}>
                  <div className="space-y-1">
                    <p className="text-sm font-medium">Generate from a repository</p>
                    <p className="text-xs text-muted-foreground">
                      GitHub, GitLab, or Bitbucket. We shallow-clone the repo and run syft (static — it
                      never runs your code) to build a CycloneDX SBOM, then import it.
                    </p>
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs font-medium text-muted-foreground">Repository URL</label>
                    <Input
                      value={repoUrl}
                      onChange={(event) => setRepoUrl(event.target.value)}
                      placeholder="https://github.com/owner/repo"
                      autoFocus
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs font-medium text-muted-foreground">
                      Branch or tag (optional)
                    </label>
                    <Input
                      value={repoRef}
                      onChange={(event) => setRepoRef(event.target.value)}
                      placeholder="main"
                    />
                  </div>
                  <div className="space-y-1">
                    <div className="flex items-center gap-1">
                      <label className="text-xs font-medium text-muted-foreground">
                        Access token (private repos)
                      </label>
                      <TooltipProvider>
                        <Tooltip>
                          <TooltipTrigger
                            render={
                              <button
                                type="button"
                                aria-label="How to create a read-only access token"
                                className="inline-flex text-muted-foreground hover:text-foreground"
                              >
                                <Info className="size-3.5" />
                              </button>
                            }
                          />
                          <TooltipContent side="top" align="start" className="max-w-sm">
                            <div className="space-y-1.5 text-left">
                              <p className="font-medium">Create a read-only token for a private repo</p>
                              <p>
                                <span className="font-medium">GitHub</span> — Settings → Developer settings →
                                Personal access tokens. Fine-grained: set <em>Resource owner</em> to the repo&apos;s
                                owner, select the repo, grant <em>Contents: Read</em>. Or a classic token with the{" "}
                                <code className="rounded bg-background/15 px-1 font-mono">repo</code> scope.
                              </p>
                              <p>
                                <span className="font-medium">GitLab</span> — Settings → Access tokens → scope{" "}
                                <code className="rounded bg-background/15 px-1 font-mono">read_repository</code>.
                              </p>
                              <p>
                                <span className="font-medium">Bitbucket</span> — Repo/Workspace settings → Access
                                tokens → <em>Repositories: Read</em>.
                              </p>
                              <p className="opacity-80">Paste it below — used only for this clone, never stored.</p>
                            </div>
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    </div>
                    <Input
                      type="password"
                      autoComplete="off"
                      value={repoToken}
                      onChange={(event) => setRepoToken(event.target.value)}
                      placeholder="Only for a private repository"
                    />
                    <p className="text-[11px] text-muted-foreground">
                      A read-only token. Used only for this clone — never stored.
                    </p>
                  </div>
                  <Button type="submit" className="w-full" disabled={repoImporting || !repoUrl.trim()}>
                    {repoImporting ? (
                      <>
                        <Loader2 className="animate-spin" />
                        Generating…
                      </>
                    ) : (
                      "Generate & import"
                    )}
                  </Button>
                </form>
              </PopoverContent>
            </Popover>
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger
                  render={
                    <Button variant="ghost" size="icon-sm" aria-label="How to generate an SBOM yourself">
                      <Info />
                    </Button>
                  }
                />
                <TooltipContent side="bottom" align="end" className="max-w-sm">
                  <div className="space-y-1.5 text-left">
                    <p className="font-medium">Generate a CycloneDX JSON yourself:</p>
                    <code className="block rounded bg-background/15 px-1.5 py-1 font-mono text-[11px]">
                      syft scan dir:. -o cyclonedx-json &gt; sbom.json
                    </code>
                    <p className="opacity-80">or, without installing anything:</p>
                    <code className="block rounded bg-background/15 px-1.5 py-1 font-mono text-[11px]">
                      npx @cyclonedx/cdxgen@latest -o sbom.json
                    </code>
                    <p className="opacity-80">Then use “Upload CycloneDX JSON”.</p>
                  </div>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
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
        canWrite={canCompose}
        config={componentsConfig}
        refreshSignal={componentsRefresh}
      />
      <RelationshipSection serviceId={serviceId} canWrite={canCompose} config={vendorsConfig} />
      <RelationshipSection serviceId={serviceId} canWrite={canGovern} config={ownersConfig} />

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
